import {
  guidedTradingApi,
  type GuidedAgentContextResponse,
  type GuidedMarketItem,
  type GuidedTradePosition,
} from '../../api';
import {
  executeMcpTool,
  requestOneShotText,
  type TradingMode,
} from '../llmService';
import {
  MarketWorker,
  type WorkerEvent,
  type WorkerOrderFlowEvent,
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

export type CandidateStage =
  | 'RULE_PASS'
  | 'RULE_FAIL'
  | 'SLOT_FULL'
  | 'ALREADY_OPEN'
  | 'COOLDOWN'
  | 'LLM_REJECT'
  | 'PLAYWRIGHT_WARN'
  | 'ENTERED';

export interface AutopilotCandidateView {
  market: string;
  koreanName: string;
  recommendedEntryWinRate: number | null;
  marketEntryWinRate: number | null;
  stage: CandidateStage;
  reason: string;
  updatedAt: number;
}

export interface AutopilotOrderFlowLocal {
  buyRequested: number;
  buyFilled: number;
  sellRequested: number;
  sellFilled: number;
  pending: number;
  cancelled: number;
}

export interface AutopilotTimelineEvent {
  id: string;
  at: number;
  market?: string;
  type: 'SYSTEM' | 'CANDIDATE' | 'WORKER' | 'PLAYWRIGHT' | 'ORDER' | 'LLM';
  level: 'INFO' | 'WARN' | 'ERROR';
  action: string;
  detail: string;
  toolName?: string;
  toolArgs?: string;
  screenshotId?: string;
}

export interface AutopilotScreenshot {
  id: string;
  at: number;
  mimeType: string;
  src: string;
}

export interface AutopilotState {
  enabled: boolean;
  blockedByDailyLoss: boolean;
  blockedReason: string | null;
  startedAt: number | null;
  workers: WorkerStateSnapshot[];
  logs: string[];
  events: AutopilotTimelineEvent[];
  candidates: AutopilotCandidateView[];
  orderFlowLocal: AutopilotOrderFlowLocal;
  screenshots: AutopilotScreenshot[];
}

interface OrchestratorCallbacks {
  onState: (state: AutopilotState) => void;
  onLog: (message: string) => void;
}

const REVIEW_ACTIONS = ['HOLD', 'PARTIAL_TP', 'FULL_EXIT'] as const;

function isValidReviewAction(value: string): value is (typeof REVIEW_ACTIONS)[number] {
  return REVIEW_ACTIONS.includes(value as (typeof REVIEW_ACTIONS)[number]);
}

function createEmptyOrderFlow(): AutopilotOrderFlowLocal {
  return {
    buyRequested: 0,
    buyFilled: 0,
    sellRequested: 0,
    sellFilled: 0,
    pending: 0,
    cancelled: 0,
  };
}

interface McpImagePayload {
  mimeType: string;
  data: string;
}

export class AutopilotOrchestrator {
  private config: AutopilotConfig;
  private readonly callbacks: OrchestratorCallbacks;
  private readonly workers = new Map<string, MarketWorker>();
  private readonly workerStates = new Map<string, WorkerStateSnapshot>();
  private readonly cooldownUntilByMarket = new Map<string, number>();
  private readonly logs: string[] = [];
  private readonly events: AutopilotTimelineEvent[] = [];
  private readonly candidatesByMarket = new Map<string, AutopilotCandidateView>();
  private readonly screenshots = new Map<string, AutopilotScreenshot>();
  private readonly screenshotOrder: string[] = [];
  private orderFlowLocal: AutopilotOrderFlowLocal = createEmptyOrderFlow();
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
    this.pushEvent({
      type: 'SYSTEM',
      level: 'INFO',
      action: 'AUTOPILOT_START',
      detail: '오토파일럿을 시작했습니다.',
    });
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
    this.pushEvent({
      type: 'SYSTEM',
      level: 'INFO',
      action: 'AUTOPILOT_STOP',
      detail: '오토파일럿을 중지했습니다.',
    });
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
      this.pushEvent({
        type: 'WORKER',
        level: 'WARN',
        market: normalized,
        action: 'WORKER_PAUSE',
        detail: reason,
      });
      return;
    }
    this.cooldownUntilByMarket.set(normalized, Date.now() + durationMs);
    this.writeLog(`[${normalized}] 외부 쿨다운 등록: ${reason}`);
    this.setCandidateStage(normalized, 'COOLDOWN', reason);
  }

  private emitState(): void {
    const state: AutopilotState = {
      enabled: this.running && this.config.enabled,
      blockedByDailyLoss: this.blockedByDailyLoss,
      blockedReason: this.blockedReason,
      startedAt: this.startedAt,
      workers: Array.from(this.workerStates.values()).sort((a, b) => a.market.localeCompare(b.market)),
      logs: [...this.logs],
      events: [...this.events],
      candidates: Array.from(this.candidatesByMarket.values()).sort((a, b) => a.market.localeCompare(b.market)),
      orderFlowLocal: { ...this.orderFlowLocal },
      screenshots: Array.from(this.screenshots.values()),
    };
    this.callbacks.onState(state);
  }

  private writeLog(message: string): void {
    const line = `[${new Date().toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul' })}] ${message}`;
    this.logs.unshift(line);
    if (this.logs.length > 120) this.logs.length = 120;
    this.callbacks.onLog(line);
    this.emitState();
  }

  private pushEvent(event: Omit<AutopilotTimelineEvent, 'id' | 'at'>): void {
    this.events.unshift({
      id: crypto.randomUUID(),
      at: Date.now(),
      ...event,
    });
    if (this.events.length > 400) {
      this.events.length = 400;
    }
    this.emitState();
  }

  private setCandidateStage(
    market: string,
    stage: CandidateStage,
    reason: string,
    seed?: Partial<AutopilotCandidateView>
  ): void {
    const normalized = market.trim().toUpperCase();
    const prev = this.candidatesByMarket.get(normalized);
    this.candidatesByMarket.set(normalized, {
      market: normalized,
      koreanName: seed?.koreanName ?? prev?.koreanName ?? normalized,
      recommendedEntryWinRate: seed?.recommendedEntryWinRate ?? prev?.recommendedEntryWinRate ?? null,
      marketEntryWinRate: seed?.marketEntryWinRate ?? prev?.marketEntryWinRate ?? null,
      stage,
      reason,
      updatedAt: Date.now(),
    });
  }

  private replaceCandidates(candidates: AutopilotCandidateView[]): void {
    this.candidatesByMarket.clear();
    candidates.forEach((candidate) => this.candidatesByMarket.set(candidate.market, candidate));
  }

  private applyOrderFlowEvent(event: WorkerOrderFlowEvent): void {
    switch (event.type) {
      case 'BUY_REQUESTED':
        this.orderFlowLocal.buyRequested += 1;
        break;
      case 'BUY_FILLED':
        this.orderFlowLocal.buyFilled += 1;
        break;
      case 'SELL_REQUESTED':
        this.orderFlowLocal.sellRequested += 1;
        break;
      case 'SELL_FILLED':
        this.orderFlowLocal.sellFilled += 1;
        break;
      case 'CANCELLED':
        this.orderFlowLocal.cancelled += 1;
        break;
      default:
        break;
    }

    const inferredPending =
      this.orderFlowLocal.buyRequested +
      this.orderFlowLocal.sellRequested -
      this.orderFlowLocal.buyFilled -
      this.orderFlowLocal.sellFilled -
      this.orderFlowLocal.cancelled;
    this.orderFlowLocal.pending = Math.max(0, inferredPending);

    this.pushEvent({
      type: 'ORDER',
      level: 'INFO',
      market: event.market,
      action: event.type,
      detail: event.message,
    });
  }

  private handleWorkerEvent(event: WorkerEvent): void {
    if (event.type === 'LLM_REJECT') {
      this.setCandidateStage(event.market, 'LLM_REJECT', event.message);
    } else if (event.type === 'PLAYWRIGHT_WARN') {
      this.setCandidateStage(event.market, 'PLAYWRIGHT_WARN', event.message);
    } else if (event.type === 'ENTRY_SUCCESS') {
      this.setCandidateStage(event.market, 'ENTERED', event.message);
    }

    this.pushEvent({
      type: 'WORKER',
      level: event.level,
      market: event.market,
      action: event.type,
      detail: event.message,
    });
  }

  private async tick(): Promise<void> {
    if (!this.running || !this.config.enabled) return;
    try {
      const stats = await guidedTradingApi.getTodayStats();
      const nextBlockedByDailyLoss = stats.totalPnlKrw <= this.config.dailyLossLimitKrw;
      const nextBlockedReason = nextBlockedByDailyLoss
        ? `일일 손실 제한 도달 (${stats.totalPnlKrw.toLocaleString('ko-KR')}원 <= ${this.config.dailyLossLimitKrw.toLocaleString('ko-KR')}원)`
        : null;

      if (nextBlockedByDailyLoss && !this.blockedByDailyLoss) {
        this.pushEvent({
          type: 'SYSTEM',
          level: 'WARN',
          action: 'DAILY_LOSS_BLOCK',
          detail: nextBlockedReason ?? '일일 손실 제한으로 신규 진입 차단',
        });
      }

      this.blockedByDailyLoss = nextBlockedByDailyLoss;
      this.blockedReason = nextBlockedReason;

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

      const ranked = markets.slice(0, this.config.candidateLimit);
      const openMarketSet = new Set(
        openPositions
          .filter((position) => position.status === 'OPEN' || position.status === 'PENDING_ENTRY')
          .map((position) => position.market)
      );

      let availableSlots = Math.max(0, this.config.maxConcurrentPositions - openMarketSet.size);
      const nextCandidates: AutopilotCandidateView[] = [];

      for (const candidate of ranked) {
        const evaluation = this.evaluateCandidateEligibility(candidate, openMarketSet, availableSlots);
        let stage: CandidateStage = evaluation.stage;
        let reason = evaluation.reason;

        if (evaluation.stage === 'RULE_PASS') {
          this.spawnWorker(candidate.market, candidate);
          stage = 'ENTERED';
          reason = '워커 생성 후 진입 분석 시작';
          availableSlots -= 1;
          this.pushEvent({
            type: 'CANDIDATE',
            level: 'INFO',
            market: candidate.market,
            action: 'ENTERED',
            detail: `${candidate.koreanName} 후보 진입 파이프라인 시작`,
          });
        }

        nextCandidates.push({
          market: candidate.market,
          koreanName: candidate.koreanName,
          recommendedEntryWinRate: candidate.recommendedEntryWinRate ?? null,
          marketEntryWinRate: candidate.marketEntryWinRate ?? null,
          stage,
          reason,
          updatedAt: Date.now(),
        });
      }

      this.replaceCandidates(nextCandidates);
      this.emitState();
    } catch (error) {
      const message = error instanceof Error ? error.message : '알 수 없는 오류';
      this.writeLog(`오케스트레이터 tick 실패: ${message}`);
      this.pushEvent({
        type: 'SYSTEM',
        level: 'ERROR',
        action: 'ORCHESTRATOR_TICK_ERROR',
        detail: message,
      });
    }
  }

  private evaluateCandidateEligibility(
    market: GuidedMarketItem,
    openMarketSet: Set<string>,
    availableSlots: number
  ): { stage: CandidateStage; reason: string } {
    const recommended = market.recommendedEntryWinRate ?? 0;
    if (recommended < this.config.minRecommendedWinRate) {
      return {
        stage: 'RULE_FAIL',
        reason: `추천가 승률 ${recommended.toFixed(1)}% < ${this.config.minRecommendedWinRate}%`,
      };
    }
    if (openMarketSet.has(market.market)) {
      return {
        stage: 'ALREADY_OPEN',
        reason: '이미 포지션 존재',
      };
    }
    const now = Date.now();
    const cooldownUntil = this.cooldownUntilByMarket.get(market.market);
    if (cooldownUntil && cooldownUntil > now) {
      return {
        stage: 'COOLDOWN',
        reason: `워커 쿨다운 ${Math.ceil((cooldownUntil - now) / 1000)}초`,
      };
    }

    const workerState = this.workerStates.get(market.market);
    if (
      workerState?.status === 'COOLDOWN' &&
      workerState.cooldownUntil != null &&
      workerState.cooldownUntil > now
    ) {
      return {
        stage: 'COOLDOWN',
        reason: `워커 쿨다운 ${Math.ceil((workerState.cooldownUntil - now) / 1000)}초`,
      };
    }

    if (this.workers.has(market.market)) {
      return {
        stage: 'ALREADY_OPEN',
        reason: '이미 워커 실행 중',
      };
    }
    if (availableSlots <= 0) {
      return {
        stage: 'SLOT_FULL',
        reason: '동시 포지션 슬롯 부족',
      };
    }
    return {
      stage: 'RULE_PASS',
      reason: `추천가 승률 ${recommended.toFixed(1)}% 규칙 통과`,
    };
  }

  private ensureWorkersForOpenPositions(openPositions: GuidedTradePosition[]): void {
    for (const position of openPositions) {
      if (position.status !== 'OPEN' && position.status !== 'PENDING_ENTRY') continue;
      if (this.workers.has(position.market)) continue;
      this.spawnWorker(position.market, {
        market: position.market,
        koreanName: position.market,
        recommendedEntryWinRate: null,
        marketEntryWinRate: null,
      });
    }
  }

  private spawnWorker(market: string, seedCandidate?: Partial<AutopilotCandidateView>): void {
    const normalized = market.trim().toUpperCase();
    if (this.workers.has(normalized)) return;

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
        onEvent: (event) => this.handleWorkerEvent(event),
        onOrderFlow: (event) => this.applyOrderFlowEvent(event),
      }
    );

    this.workers.set(normalized, worker);
    this.setCandidateStage(normalized, 'ENTERED', '워커 생성', seedCandidate);
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

    this.pushEvent({
      type: 'LLM',
      level: approve ? 'INFO' : 'WARN',
      market,
      action: 'ENTRY_REVIEW',
      detail: `${approve ? '승인' : '거절'} (${confidence.toFixed(0)}): ${reason}`,
    });

    return { approve, confidence, reason };
  }

  private async verifyWithPlaywright(market: string): Promise<{ ok: boolean; note: string }> {
    if (!this.config.playwrightEnabled) return { ok: true, note: '비활성' };

    const snapshot = await this.tracePlaywrightAction(
      'browser_snapshot',
      { market },
      market,
      '차트 스냅샷 수집',
      true
    );

    await this.tracePlaywrightAction(
      'browser_click',
      { text: '현재가 승률' },
      market,
      '현재가 승률 정렬 클릭',
      true
    );

    if (snapshot.isError) {
      return { ok: false, note: this.extractMcpText(snapshot) || 'playwright snapshot 실패' };
    }
    return { ok: true, note: 'playwright 검증 완료' };
  }

  private async tracePlaywrightAction(
    toolName: string,
    args: Record<string, unknown>,
    market: string,
    label: string,
    captureEvidence: boolean
  ): Promise<McpToolResult> {
    const result = await executeMcpTool(toolName, args, 'playwright');
    let image = this.extractMcpImage(result);

    if (!image && captureEvidence && toolName !== 'browser_screenshot') {
      const evidence = await executeMcpTool('browser_screenshot', { fullPage: false }, 'playwright');
      image = this.extractMcpImage(evidence) ?? this.extractMcpImage(await executeMcpTool('browser_snapshot', {}, 'playwright'));
    }

    const screenshotId = image ? this.storeScreenshot(image) : undefined;
    const detailText = this.extractMcpText(result);

    this.pushEvent({
      type: 'PLAYWRIGHT',
      level: result.isError ? 'WARN' : 'INFO',
      market,
      action: toolName,
      detail: detailText || `${label}${result.isError ? ' 실패' : ' 완료'}`,
      toolName,
      toolArgs: JSON.stringify(args),
      screenshotId,
    });

    return result;
  }

  private extractMcpText(result: McpToolResult): string {
    return (result.content || [])
      .filter((item) => item.type === 'text' && typeof item.text === 'string' && item.text.trim().length > 0)
      .map((item) => item.text?.trim() ?? '')
      .join('\n')
      .trim();
  }

  private extractMcpImage(result: McpToolResult): McpImagePayload | null {
    const imageItem = (result.content || []).find((item) => item.type === 'image' && (item.data || item.url));
    if (!imageItem) return null;

    const mimeType = imageItem.mimeType || 'image/png';
    const data = imageItem.data || imageItem.url;
    if (!data) return null;
    return { mimeType, data };
  }

  private storeScreenshot(image: McpImagePayload): string {
    const id = crypto.randomUUID();
    const src = this.normalizeImageSource(image);
    this.screenshots.set(id, {
      id,
      at: Date.now(),
      mimeType: image.mimeType,
      src,
    });
    this.screenshotOrder.push(id);

    while (this.screenshotOrder.length > 150) {
      const oldest = this.screenshotOrder.shift();
      if (oldest) {
        this.screenshots.delete(oldest);
      }
    }

    return id;
  }

  private normalizeImageSource(image: McpImagePayload): string {
    const raw = image.data.trim();
    if (raw.startsWith('data:')) return raw;
    if (raw.startsWith('http://') || raw.startsWith('https://')) return raw;
    return `data:${image.mimeType};base64,${raw}`;
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
      throw new Error(this.extractMcpText(buyResult) || 'MCP 직접 주문 실패');
    }

    const mergedText = (buyResult.content || [])
      .map((item) => (item.type === 'text' ? item.text || '' : ''))
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

    this.pushEvent({
      type: 'LLM',
      level: action === 'HOLD' ? 'INFO' : 'WARN',
      market,
      action: 'POSITION_REVIEW',
      detail: `${action}: ${reason}`,
    });

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
        this.pushEvent({
          type: 'LLM',
          level: 'INFO',
          action: 'SUPERVISOR_ADVICE',
          detail: advice,
        });
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : '알 수 없는 오류';
      this.writeLog(`Supervisor 실패: ${message}`);
      this.pushEvent({
        type: 'SYSTEM',
        level: 'WARN',
        action: 'SUPERVISOR_ERROR',
        detail: message,
      });
    }
  }
}

export function isActiveWorkerStatus(status: WorkerStatus): boolean {
  return status !== 'STOPPED';
}
