import {
  guidedTradingApi,
  type GuidedAgentContextResponse,
  type GuidedTradePosition,
} from '../../api';
import {
  executeMcpTool,
  requestOneShotText,
  type TradingMode,
} from '../llmService';
import {
  MarketWorker,
  type WorkerStateSnapshot,
  type WorkerStatus,
} from './MarketWorker';

export interface AutopilotConfig {
  enabled: boolean;
  interval: string;
  tradingMode: TradingMode;
  amountKrw: number;
  dailyLossLimitKrw: number;
  maxConcurrentPositions: number;
  minRecommendedWinRate: number;
  candidateLimit: number;
  cooldownMs: number;
  workerTickMs: number;
  llmReviewIntervalMs: number;
  llmModel: string;
  playwrightEnabled: boolean;
}

export interface AutopilotState {
  enabled: boolean;
  blockedByDailyLoss: boolean;
  blockedReason: string | null;
  startedAt: number | null;
  workers: WorkerStateSnapshot[];
  logs: string[];
}

interface OrchestratorCallbacks {
  onState: (state: AutopilotState) => void;
  onLog: (message: string) => void;
}

const REVIEW_ACTIONS = ['HOLD', 'PARTIAL_TP', 'FULL_EXIT'] as const;

function isValidReviewAction(value: string): value is (typeof REVIEW_ACTIONS)[number] {
  return REVIEW_ACTIONS.includes(value as (typeof REVIEW_ACTIONS)[number]);
}

export class AutopilotOrchestrator {
  private config: AutopilotConfig;
  private readonly callbacks: OrchestratorCallbacks;
  private readonly workers = new Map<string, MarketWorker>();
  private readonly workerStates = new Map<string, WorkerStateSnapshot>();
  private readonly cooldownUntilByMarket = new Map<string, number>();
  private readonly logs: string[] = [];
  private loopId: number | null = null;
  private supervisorId: number | null = null;
  private running = false;
  private startedAt: number | null = null;
  private blockedByDailyLoss = false;
  private blockedReason: string | null = null;

  constructor(config: AutopilotConfig, callbacks: OrchestratorCallbacks) {
    this.config = config;
    this.callbacks = callbacks;
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.startedAt = Date.now();
    this.writeLog('오토파일럿 시작');
    void this.tick();
    this.loopId = window.setInterval(() => void this.tick(), 15000);
    this.supervisorId = window.setInterval(() => void this.supervise(), 60000);
    this.emitState();
  }

  stop(): void {
    this.running = false;
    if (this.loopId != null) {
      window.clearInterval(this.loopId);
      this.loopId = null;
    }
    if (this.supervisorId != null) {
      window.clearInterval(this.supervisorId);
      this.supervisorId = null;
    }
    for (const worker of this.workers.values()) {
      worker.stop('오케스트레이터 중지');
    }
    this.workers.clear();
    this.workerStates.clear();
    this.writeLog('오토파일럿 중지');
    this.emitState();
  }

  updateConfig(next: AutopilotConfig): void {
    this.config = next;
  }

  pauseMarket(market: string, durationMs: number, reason: string): void {
    const normalized = market.trim().toUpperCase();
    const worker = this.workers.get(normalized);
    if (worker) {
      worker.pause(durationMs, reason);
      this.writeLog(`[${normalized}] 워커 일시정지: ${reason}`);
      return;
    }
    this.cooldownUntilByMarket.set(normalized, Date.now() + durationMs);
    this.writeLog(`[${normalized}] 외부 쿨다운 등록: ${reason}`);
  }

  private emitState(): void {
    const state: AutopilotState = {
      enabled: this.running && this.config.enabled,
      blockedByDailyLoss: this.blockedByDailyLoss,
      blockedReason: this.blockedReason,
      startedAt: this.startedAt,
      workers: Array.from(this.workerStates.values()).sort((a, b) => a.market.localeCompare(b.market)),
      logs: [...this.logs],
    };
    this.callbacks.onState(state);
  }

  private writeLog(message: string): void {
    const line = `[${new Date().toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul' })}] ${message}`;
    this.logs.unshift(line);
    if (this.logs.length > 60) this.logs.length = 60;
    this.callbacks.onLog(line);
    this.emitState();
  }

  private async tick(): Promise<void> {
    if (!this.running || !this.config.enabled) return;
    try {
      const stats = await guidedTradingApi.getTodayStats();
      this.blockedByDailyLoss = stats.totalPnlKrw <= this.config.dailyLossLimitKrw;
      this.blockedReason = this.blockedByDailyLoss
        ? `일일 손실 제한 도달 (${stats.totalPnlKrw.toLocaleString('ko-KR')}원 <= ${this.config.dailyLossLimitKrw.toLocaleString('ko-KR')}원)`
        : null;

      const openPositions = await guidedTradingApi.getOpenPositions();
      this.cleanupExpiredCooldowns();
      this.ensureWorkersForOpenPositions(openPositions);

      if (this.blockedByDailyLoss) {
        this.emitState();
        return;
      }

      const markets = await guidedTradingApi.getMarkets(
        'MARKET_ENTRY_WIN_RATE',
        'DESC',
        this.config.interval,
        this.config.tradingMode
      );

      const candidates = markets
        .filter((item) => (item.recommendedEntryWinRate ?? 0) >= this.config.minRecommendedWinRate)
        .slice(0, this.config.candidateLimit)
        .map((item) => item.market);

      const openMarketSet = new Set(
        openPositions
          .filter((position) => position.status === 'OPEN' || position.status === 'PENDING_ENTRY')
          .map((position) => position.market)
      );

      let availableSlots = Math.max(0, this.config.maxConcurrentPositions - openMarketSet.size);
      for (const market of candidates) {
        if (availableSlots <= 0) break;
        if (openMarketSet.has(market)) continue;
        if (this.workers.has(market)) continue;
        const cooldownUntil = this.cooldownUntilByMarket.get(market);
        if (cooldownUntil && cooldownUntil > Date.now()) continue;
        this.spawnWorker(market);
        availableSlots -= 1;
      }

      this.emitState();
    } catch (error) {
      this.writeLog(`오케스트레이터 tick 실패: ${error instanceof Error ? error.message : '알 수 없는 오류'}`);
    }
  }

  private ensureWorkersForOpenPositions(openPositions: GuidedTradePosition[]): void {
    for (const position of openPositions) {
      if (position.status !== 'OPEN' && position.status !== 'PENDING_ENTRY') continue;
      if (this.workers.has(position.market)) continue;
      this.spawnWorker(position.market);
    }
  }

  private spawnWorker(market: string): void {
    const normalized = market.trim().toUpperCase();
    const worker = new MarketWorker(
      {
        market: normalized,
        tickMs: this.config.workerTickMs,
        cooldownMs: this.config.cooldownMs,
        llmReviewMs: this.config.llmReviewIntervalMs,
        minLlmConfidence: 70,
        enablePlaywrightCheck: this.config.playwrightEnabled,
      },
      {
        fetchContext: async (workerMarket) =>
          guidedTradingApi.getAgentContext(workerMarket, this.config.interval, this.config.interval === 'tick' ? 300 : 120, 20, this.config.tradingMode),
        evaluateEntry: async (workerMarket, context) => this.evaluateEntry(workerMarket, context),
        verifyWithPlaywright: async (workerMarket) => this.verifyWithPlaywright(workerMarket),
        startGuidedEntry: async (workerMarket) =>
          guidedTradingApi.start({
            market: workerMarket,
            amountKrw: this.config.amountKrw,
            orderType: 'MARKET',
          }).then(() => undefined),
        fallbackEntryByMcp: async (workerMarket) => this.fallbackEntryByMcp(workerMarket),
        getPosition: async (workerMarket) => guidedTradingApi.getPosition(workerMarket),
        stopPosition: async (workerMarket, reason) => {
          this.writeLog(`[${workerMarket}] 청산 실행: ${reason}`);
          await guidedTradingApi.stop(workerMarket);
        },
        partialTakeProfit: async (workerMarket, ratio, reason) => {
          this.writeLog(`[${workerMarket}] 부분익절 실행 ${Math.round(ratio * 100)}%: ${reason}`);
          await guidedTradingApi.partialTakeProfit(workerMarket, ratio);
        },
        reviewOpenPosition: async (workerMarket, position) => this.reviewOpenPosition(workerMarket, position),
        onState: (snapshot) => {
          this.workerStates.set(snapshot.market, snapshot);
          if (snapshot.status === 'COOLDOWN' && snapshot.cooldownUntil) {
            this.cooldownUntilByMarket.set(snapshot.market, snapshot.cooldownUntil);
          }
          if (snapshot.status === 'STOPPED') {
            this.workers.delete(snapshot.market);
            this.workerStates.delete(snapshot.market);
          }
          this.emitState();
        },
        onLog: (message) => this.writeLog(message),
      }
    );
    this.workers.set(normalized, worker);
    worker.start();
    this.writeLog(`[${normalized}] 워커 생성`);
  }

  private cleanupExpiredCooldowns(): void {
    const now = Date.now();
    for (const [market, until] of this.cooldownUntilByMarket.entries()) {
      if (until <= now) {
        this.cooldownUntilByMarket.delete(market);
      }
    }
  }

  private async evaluateEntry(
    market: string,
    context: GuidedAgentContextResponse
  ): Promise<{ approve: boolean; confidence: number; reason: string }> {
    const response = await requestOneShotText({
      model: this.config.llmModel,
      tradingMode: this.config.tradingMode,
      context,
      prompt: [
        `${market} 진입 승인 여부를 판단해.`,
        '반드시 JSON 한 줄로만 답해.',
        '형식: {"approve":true|false,"confidence":0-100,"reason":"..."}',
        '규칙: 변동성 과다/손절 근접/추세 훼손이면 approve=false.',
      ].join('\n'),
    });

    const parsed = this.parseJsonObject(response);
    const approve = Boolean(parsed?.approve);
    const confidence = this.toSafeNumber(parsed?.confidence, 0);
    const reason = typeof parsed?.reason === 'string' ? parsed.reason : 'LLM 응답 파싱 실패';
    return { approve, confidence, reason };
  }

  private async verifyWithPlaywright(market: string): Promise<{ ok: boolean; note: string }> {
    if (!this.config.playwrightEnabled) return { ok: true, note: '비활성' };
    const snapshot = await executeMcpTool('browser_snapshot', { market }, 'playwright');
    if (snapshot.isError) {
      return { ok: false, note: snapshot.content?.[0]?.text || 'playwright snapshot 실패' };
    }
    return { ok: true, note: 'snapshot 성공' };
  }

  private async fallbackEntryByMcp(market: string): Promise<void> {
    const buyResult = await executeMcpTool(
      'buyMarketOrder',
      {
        market,
        krwAmount: this.config.amountKrw,
      },
      'trading'
    );

    if (buyResult.isError) {
      throw new Error(buyResult.content?.[0]?.text || 'MCP 직접 주문 실패');
    }

    const mergedText = buyResult.content
      .map((item) => item.text || '')
      .join('\n');
    if (mergedText.includes('"success":false') || mergedText.includes('"success": false')) {
      throw new Error(`MCP 직접 주문 실패: ${mergedText}`);
    }

    await guidedTradingApi.adoptPosition({
      market,
      mode: this.config.tradingMode,
      interval: this.config.interval,
      entrySource: 'MCP_DIRECT',
      notes: 'autopilot fallback',
    });
  }

  private async reviewOpenPosition(
    market: string,
    position: GuidedTradePosition
  ): Promise<{ action: 'HOLD' | 'PARTIAL_TP' | 'FULL_EXIT'; reason: string }> {
    const response = await requestOneShotText({
      model: this.config.llmModel,
      tradingMode: this.config.tradingMode,
      prompt: [
        `${market} 포지션 관리 판단.`,
        `현재 미실현손익 ${position.unrealizedPnlPercent.toFixed(2)}%.`,
        '반드시 JSON 한 줄로만 답해.',
        '형식: {"action":"HOLD|PARTIAL_TP|FULL_EXIT","reason":"..."}',
      ].join('\n'),
    });
    const parsed = this.parseJsonObject(response);
    const action = typeof parsed?.action === 'string' ? parsed.action.toUpperCase() : 'HOLD';
    const reason = typeof parsed?.reason === 'string' ? parsed.reason : '검토';
    if (isValidReviewAction(action)) {
      return { action, reason };
    }
    return { action: 'HOLD', reason };
  }

  private parseJsonObject(text: string): Record<string, unknown> | null {
    const codeBlockMatch = text.match(/```json\s*([\s\S]*?)```/i);
    const candidate = codeBlockMatch ? codeBlockMatch[1] : text;
    const objectMatch = candidate.match(/\{[\s\S]*\}/);
    const raw = objectMatch ? objectMatch[0] : candidate;
    try {
      const parsed = JSON.parse(raw);
      if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
        return parsed as Record<string, unknown>;
      }
      return null;
    } catch {
      return null;
    }
  }

  private toSafeNumber(value: unknown, fallback: number): number {
    if (typeof value === 'number' && Number.isFinite(value)) return value;
    if (typeof value === 'string') {
      const parsed = Number(value);
      if (Number.isFinite(parsed)) return parsed;
    }
    return fallback;
  }

  private async supervise(): Promise<void> {
    if (!this.running || !this.config.enabled) return;
    const workerSummary = Array.from(this.workerStates.values())
      .map((worker) => `${worker.market}:${worker.status}`)
      .join(', ');
    if (!workerSummary) return;
    try {
      const advice = await requestOneShotText({
        model: this.config.llmModel,
        tradingMode: this.config.tradingMode,
        prompt: [
          '오토파일럿 워커 상태 요약 기반으로 1줄 조언을 해줘.',
          `workers=${workerSummary}`,
          '리스크 증가 시 손절 가속 또는 신규 진입 억제 우선.',
        ].join('\n'),
      });
      if (advice) {
        this.writeLog(`Supervisor: ${advice}`);
      }
    } catch (error) {
      this.writeLog(`Supervisor 실패: ${error instanceof Error ? error.message : '알 수 없는 오류'}`);
    }
  }
}

export function isActiveWorkerStatus(status: WorkerStatus): boolean {
  return status !== 'STOPPED';
}
