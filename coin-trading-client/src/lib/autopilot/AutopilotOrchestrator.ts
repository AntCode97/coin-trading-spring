import {
  guidedTradingApi,
  type GuidedAgentContextResponse,
  type GuidedMarketItem,
  type GuidedRecommendation,
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
  winRateThresholdMode: 'DYNAMIC_P70' | 'FIXED';
  fixedMinRecommendedWinRate: number;
  minLlmConfidence: number;
  candidateLimit: number;
  rejectCooldownMs: number;
  postExitCooldownMs: number;
  workerTickMs: number;
  llmReviewIntervalMs: number;
  llmModel: string;
  playwrightEnabled: boolean;
  entryPolicy: 'BALANCED' | 'AGGRESSIVE' | 'CONSERVATIVE';
  entryOrderMode: 'ADAPTIVE' | 'MARKET' | 'LIMIT';
  pendingEntryTimeoutSec: number;
  marketFallbackAfterCancel: boolean;
}

export type CandidateStage =
  | 'RULE_PASS'
  | 'RULE_FAIL'
  | 'SLOT_FULL'
  | 'POSITION_OPEN'
  | 'WORKER_ACTIVE'
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
  thresholdMode: 'DYNAMIC_P70' | 'FIXED';
  appliedRecommendedWinRateThreshold: number;
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

interface CandidateScoreInfo {
  market: string;
  score: number;
  riskRewardRatio: number;
  entryGapPct: number;
}

interface CachedRecommendationMeta {
  at: number;
  recommendation: GuidedRecommendation;
}

interface EntryDecision {
  approve: boolean;
  confidence: number;
  reason: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH';
  riskTags: string[];
  suggestedCooldownSec: number | null;
}

interface PlannedEntryOrder {
  allow: boolean;
  reason: string;
  orderType: 'MARKET' | 'LIMIT';
  limitPrice?: number;
  entryGapPct: number;
}

interface LlmShortlistDecision {
  markets: Set<string>;
  budget: number;
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
  private readonly candidateScoreByMarket = new Map<string, CandidateScoreInfo>();
  private readonly screenshots = new Map<string, AutopilotScreenshot>();
  private readonly screenshotOrder: string[] = [];
  private readonly recommendationCache = new Map<string, CachedRecommendationMeta>();
  private orderFlowLocal: AutopilotOrderFlowLocal = createEmptyOrderFlow();
  private loopId: number | null = null;
  private supervisorId: number | null = null;
  private running = false;
  private startedAt: number | null = null;
  private blockedByDailyLoss = false;
  private blockedReason: string | null = null;
  private appliedRecommendedWinRateThreshold = 0;
  private lastLlmShortlistSignature: string | null = null;

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
      thresholdMode: this.config.winRateThresholdMode,
      appliedRecommendedWinRateThreshold: this.appliedRecommendedWinRateThreshold,
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
    const candidateMarkets = new Set(candidates.map((candidate) => candidate.market));
    for (const market of this.candidateScoreByMarket.keys()) {
      if (!candidateMarkets.has(market)) {
        this.candidateScoreByMarket.delete(market);
      }
    }
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
      const topTwenty = markets.slice(0, 20);
      await this.refreshRecommendationCache(topTwenty);
      const ranked = this.rankCandidates(topTwenty);
      const appliedThreshold = this.resolveRecommendedThreshold(markets, ranked);
      this.appliedRecommendedWinRateThreshold = appliedThreshold;
      const scopedCandidates = ranked.slice(0, this.config.candidateLimit);
      const llmShortlist = this.buildLlmShortlist(scopedCandidates, appliedThreshold);
      this.emitLlmShortlistEvent(scopedCandidates.length, llmShortlist);
      const openMarketSet = new Set(
        openPositions
          .filter((position) => position.status === 'OPEN' || position.status === 'PENDING_ENTRY')
          .map((position) => position.market)
      );

      let availableSlots = Math.max(0, this.config.maxConcurrentPositions - openMarketSet.size);
      const nextCandidates: AutopilotCandidateView[] = [];

      for (const candidate of scopedCandidates) {
        const evaluation = this.evaluateCandidateEligibility(
          candidate,
          openMarketSet,
          availableSlots,
          appliedThreshold
        );
        let stage: CandidateStage = evaluation.stage;
        let reason = evaluation.reason;
        const scoreInfo = this.candidateScoreByMarket.get(candidate.market);
        if (evaluation.stage === 'RULE_PASS' && !llmShortlist.markets.has(candidate.market)) {
          stage = 'RULE_FAIL';
          reason = `고확신 필터 미통과로 LLM 생략 (상한 ${llmShortlist.budget}개)`;
        }
        if (scoreInfo) {
          reason = `${reason} · score ${scoreInfo.score.toFixed(1)} / RR ${scoreInfo.riskRewardRatio.toFixed(2)} / 괴리 ${scoreInfo.entryGapPct.toFixed(2)}%`;
        }

        if (stage === 'RULE_PASS') {
          this.spawnWorker(candidate.market, candidate, scoreInfo);
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

      this.pruneIdleWorkers(llmShortlist.markets, openMarketSet);
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
    availableSlots: number,
    minRecommendedWinRate: number
  ): { stage: CandidateStage; reason: string } {
    const recommended = market.recommendedEntryWinRate;
    if (recommended == null || recommended < minRecommendedWinRate) {
      return {
        stage: 'RULE_FAIL',
        reason: `추천가 승률 ${recommended?.toFixed(1) ?? '-'}% < ${minRecommendedWinRate.toFixed(1)}%`,
      };
    }
    if (openMarketSet.has(market.market)) {
      return {
        stage: 'POSITION_OPEN',
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
        stage: 'WORKER_ACTIVE',
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
      reason: `추천가 승률 ${recommended.toFixed(1)}% 규칙 통과 (기준 ${minRecommendedWinRate.toFixed(1)}%)`,
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

  private buildLlmShortlist(
    candidates: GuidedMarketItem[],
    minRecommendedWinRate: number
  ): LlmShortlistDecision {
    const budget = Math.min(6, Math.max(2, Math.ceil(this.config.maxConcurrentPositions * 1.5)));
    const strict: GuidedMarketItem[] = [];
    const relaxed: GuidedMarketItem[] = [];

    for (const candidate of candidates) {
      const recommendedWin = candidate.recommendedEntryWinRate ?? 0;
      if (recommendedWin < minRecommendedWinRate) continue;

      const marketWin = candidate.marketEntryWinRate ?? 0;
      const score = this.candidateScoreByMarket.get(candidate.market);
      if (!score) continue;

      const strictPass =
        recommendedWin >= minRecommendedWinRate + 0.5 &&
        marketWin >= minRecommendedWinRate - 4 &&
        score.score >= 60 &&
        score.riskRewardRatio >= 1.18 &&
        score.entryGapPct <= 0.9;
      if (strictPass) {
        strict.push(candidate);
        continue;
      }

      const relaxedPass =
        marketWin >= minRecommendedWinRate - 7 &&
        score.score >= 56 &&
        score.riskRewardRatio >= 1.05 &&
        score.entryGapPct <= 1.2;
      if (relaxedPass) {
        relaxed.push(candidate);
      }
    }

    const byScoreDesc = (left: GuidedMarketItem, right: GuidedMarketItem): number => {
      const l = this.candidateScoreByMarket.get(left.market)?.score ?? 0;
      const r = this.candidateScoreByMarket.get(right.market)?.score ?? 0;
      return r - l;
    };

    strict.sort(byScoreDesc);
    relaxed.sort(byScoreDesc);

    const merged: GuidedMarketItem[] = [];
    const seen = new Set<string>();
    for (const candidate of strict) {
      if (seen.has(candidate.market)) continue;
      merged.push(candidate);
      seen.add(candidate.market);
    }
    for (const candidate of relaxed) {
      if (seen.has(candidate.market)) continue;
      merged.push(candidate);
      seen.add(candidate.market);
    }

    return {
      markets: new Set(merged.slice(0, budget).map((candidate) => candidate.market)),
      budget,
    };
  }

  private emitLlmShortlistEvent(candidateCount: number, shortlist: LlmShortlistDecision): void {
    const markets = Array.from(shortlist.markets).sort();
    const signature = `${candidateCount}|${shortlist.budget}|${markets.join(',')}`;
    if (this.lastLlmShortlistSignature === signature) return;
    this.lastLlmShortlistSignature = signature;

    this.pushEvent({
      type: 'SYSTEM',
      level: 'INFO',
      action: 'LLM_SHORTLIST',
      detail: `후보 ${candidateCount}개 중 ${shortlist.markets.size}개만 LLM 평가 (${markets.join(', ') || '-'})`,
    });
  }

  private pruneIdleWorkers(
    shortlistMarkets: Set<string>,
    openMarketSet: Set<string>
  ): void {
    for (const [market, worker] of this.workers.entries()) {
      if (openMarketSet.has(market) || shortlistMarkets.has(market)) continue;
      const state = this.workerStates.get(market);
      if (
        state &&
        (state.status === 'ENTERING'
          || state.status === 'MANAGING'
          || state.status === 'PLAYWRIGHT_CHECK'
          || state.status === 'PAUSED')
      ) {
        continue;
      }
      worker.stop('고확신 후보군 제외로 워커 정리');
      this.pushEvent({
        type: 'WORKER',
        level: 'INFO',
        market,
        action: 'WORKER_PRUNED',
        detail: 'LLM 평가 대상 축소로 비활성 워커 정리',
      });
    }
  }

  private spawnWorker(
    market: string,
    seedCandidate?: Partial<AutopilotCandidateView>,
    scoreInfo?: CandidateScoreInfo
  ): void {
    const normalized = market.trim().toUpperCase();
    if (this.workers.has(normalized)) return;

    const worker = new MarketWorker(
      {
        market: normalized,
        tickMs: this.config.workerTickMs,
        rejectCooldownMs: this.config.rejectCooldownMs,
        postExitCooldownMs: this.config.postExitCooldownMs,
        llmReviewMs: this.config.llmReviewIntervalMs,
        minLlmConfidence: this.config.minLlmConfidence,
        enablePlaywrightCheck: this.config.playwrightEnabled,
        entryPolicy: this.config.entryPolicy,
        entryOrderMode: this.config.entryOrderMode,
        pendingEntryTimeoutMs: Math.max(10_000, this.config.pendingEntryTimeoutSec * 1000),
        marketFallbackAfterCancel: this.config.marketFallbackAfterCancel,
      },
      {
        fetchContext: async (workerMarket) =>
          guidedTradingApi.getAgentContext(workerMarket, this.config.interval, this.config.interval === 'tick' ? 300 : 120, 20, this.config.tradingMode),
        evaluateEntry: async (workerMarket, context) => this.evaluateEntry(workerMarket, context),
        planEntryOrder: async (workerMarket, context) => this.planEntryOrder(workerMarket, context),
        verifyWithPlaywright: async (workerMarket) => this.verifyWithPlaywright(workerMarket),
        startGuidedEntry: async (workerMarket, plan) =>
          guidedTradingApi.start({
            market: workerMarket,
            amountKrw: this.config.amountKrw,
            orderType: plan?.orderType ?? 'MARKET',
            limitPrice: plan?.orderType === 'LIMIT' ? plan.limitPrice : undefined,
            interval: this.config.interval,
            mode: this.config.tradingMode,
            entrySource: 'AUTOPILOT',
            strategyCode: 'GUIDED_AUTOPILOT',
          }).then(() => undefined),
        fallbackEntryByMcp: async (workerMarket) => this.fallbackEntryByMcp(workerMarket),
        cancelPendingEntry: async (workerMarket) => guidedTradingApi.cancelPending(workerMarket).then(() => undefined),
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
    const scoreNote = scoreInfo ? `score ${scoreInfo.score.toFixed(1)}` : 'score -';
    this.setCandidateStage(normalized, 'ENTERED', `워커 생성 (${scoreNote})`, seedCandidate);
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

  private async refreshRecommendationCache(candidates: GuidedMarketItem[]): Promise<void> {
    const now = Date.now();
    const staleMarkets = candidates
      .map((candidate) => candidate.market)
      .filter((market) => {
        const cached = this.recommendationCache.get(market);
        return !cached || now - cached.at >= 60_000;
      })
      .slice(0, 5);

    if (staleMarkets.length === 0) return;

    const settled = await Promise.allSettled(
      staleMarkets.map((market) =>
        guidedTradingApi.getRecommendation(
          market,
          this.config.interval,
          this.config.interval === 'tick' ? 300 : 120,
          this.config.tradingMode
        )
      )
    );

    settled.forEach((result, idx) => {
      const market = staleMarkets[idx];
      if (result.status !== 'fulfilled') {
        this.pushEvent({
          type: 'SYSTEM',
          level: 'WARN',
          market,
          action: 'RECOMMENDATION_CACHE_WARN',
          detail: result.reason instanceof Error ? result.reason.message : '추천 정보 조회 실패',
        });
        return;
      }
      this.recommendationCache.set(market, {
        at: now,
        recommendation: result.value,
      });
    });
  }

  private rankCandidates(candidates: GuidedMarketItem[]): GuidedMarketItem[] {
    const scored = candidates.map((candidate) => {
      const recommendedWin = candidate.recommendedEntryWinRate ?? 0;
      const marketWin = candidate.marketEntryWinRate ?? 0;
      const cached = this.recommendationCache.get(candidate.market)?.recommendation;
      const riskReward = cached?.riskRewardRatio ?? 1.0;
      const entryGapPct = cached?.recommendedEntryPrice && cached.recommendedEntryPrice > 0
        ? Math.max(0, ((candidate.tradePrice - cached.recommendedEntryPrice) / cached.recommendedEntryPrice) * 100)
        : 0;

      const rrScore = Math.max(0, Math.min(100, ((riskReward - 0.8) / 1.6) * 100));
      const gapScore = Math.max(0, Math.min(100, 100 - entryGapPct * 70));
      const finalScore =
        marketWin * 0.44 +
        recommendedWin * 0.34 +
        rrScore * 0.16 +
        gapScore * 0.06;

      this.candidateScoreByMarket.set(candidate.market, {
        market: candidate.market,
        score: finalScore,
        riskRewardRatio: riskReward,
        entryGapPct,
      });
      return { candidate, score: finalScore };
    });

    return scored
      .sort((left, right) => right.score - left.score)
      .map((item) => item.candidate);
  }

  private resolveRecommendedThreshold(
    markets: GuidedMarketItem[],
    ranked: GuidedMarketItem[]
  ): number {
    if (this.config.winRateThresholdMode === 'FIXED') {
      return Math.min(80, Math.max(50, this.config.fixedMinRecommendedWinRate));
    }

    const values = markets
      .map((market) => market.recommendedEntryWinRate)
      .filter((value): value is number => value != null && Number.isFinite(value));
    if (values.length === 0) return 54;

    const sorted = [...values].sort((a, b) => a - b);
    const p65Index = Math.max(0, Math.ceil(sorted.length * 0.65) - 1);
    let threshold = Math.min(63, Math.max(56, sorted[p65Index]));

    while (threshold > 54) {
      const eligibleCount = ranked.filter(
        (candidate) => (candidate.recommendedEntryWinRate ?? 0) >= threshold
      ).length;
      if (eligibleCount >= 2) break;
      threshold -= 1;
    }

    threshold = Math.max(54, threshold);
    return Math.round(threshold * 10) / 10;
  }

  private async evaluateEntry(
    market: string,
    context: GuidedAgentContextResponse
  ): Promise<EntryDecision> {
    const response = await requestOneShotText({
      model: this.config.llmModel,
      tradingMode: this.config.tradingMode,
      context,
      prompt: [
        `${market} 진입 승인 여부를 판단해.`,
        '반드시 JSON 한 줄로만 답해.',
        '형식: {"approve":true|false,"confidence":0-100,"severity":"LOW|MEDIUM|HIGH","riskTags":["..."],"cooldownSec":30-1200,"reason":"..."}',
        '규칙: 변동성 과다/손절 근접/추세 훼손이면 approve=false.',
        'approve=false면 cooldownSec을 반드시 넣어. 위험 높을수록 길게.',
      ].join('\n'),
    });

    const parsed = this.parseJsonObject(response);
    const approve = Boolean(parsed?.approve);
    const confidence = this.toSafeNumber(parsed?.confidence, 0);
    const reason = typeof parsed?.reason === 'string' ? parsed.reason : 'LLM 응답 파싱 실패';
    const severity = this.parseRiskSeverity(parsed?.severity, reason);
    const riskTags = this.parseRiskTags(parsed?.riskTags, reason);
    const suggestedCooldownRaw = this.toSafeNumber(
      parsed?.cooldownSec ?? parsed?.retryAfterSec ?? parsed?.recheckSec,
      Number.NaN
    );
    const suggestedCooldownSec = Number.isFinite(suggestedCooldownRaw)
      ? Math.min(1200, Math.max(30, Math.round(suggestedCooldownRaw)))
      : null;

    this.pushEvent({
      type: 'LLM',
      level: approve ? 'INFO' : severity === 'HIGH' ? 'ERROR' : 'WARN',
      market,
      action: 'ENTRY_REVIEW',
      detail: `${approve ? '승인' : '거절'} (${confidence.toFixed(0)}/${severity})${suggestedCooldownSec ? ` · 쿨다운 ${suggestedCooldownSec}초` : ''}: ${reason}`,
    });

    return { approve, confidence, reason, severity, riskTags, suggestedCooldownSec };
  }

  private async planEntryOrder(
    market: string,
    context: GuidedAgentContextResponse
  ): Promise<PlannedEntryOrder> {
    const recommendation = context.chart.recommendation;
    const current = recommendation.currentPrice;
    const recommended = recommendation.recommendedEntryPrice;
    const entryGapPct = recommended > 0 ? Math.max(0, ((current - recommended) / recommended) * 100) : 0;

    if (this.config.entryOrderMode === 'MARKET') {
      return {
        allow: true,
        reason: '시장가 고정 모드',
        orderType: 'MARKET',
        entryGapPct,
      };
    }

    if (this.config.entryOrderMode === 'LIMIT') {
      return {
        allow: true,
        reason: '지정가 고정 모드',
        orderType: 'LIMIT',
        limitPrice: recommended,
        entryGapPct,
      };
    }

    if (entryGapPct <= 0.25) {
      return {
        allow: true,
        reason: `괴리 ${entryGapPct.toFixed(2)}% <= 0.25%`,
        orderType: 'MARKET',
        entryGapPct,
      };
    }

    if (entryGapPct <= 1.2) {
      return {
        allow: true,
        reason: `괴리 ${entryGapPct.toFixed(2)}% -> 지정가 진입`,
        orderType: 'LIMIT',
        limitPrice: recommended,
        entryGapPct,
      };
    }

    this.pushEvent({
      type: 'CANDIDATE',
      level: 'WARN',
      market,
      action: 'CHASE_RISK',
      detail: `진입 거절: 추천가 대비 괴리 ${entryGapPct.toFixed(2)}%`,
    });
    return {
      allow: false,
      reason: `추천가 대비 괴리 ${entryGapPct.toFixed(2)}% > 1.2%`,
      orderType: 'LIMIT',
      limitPrice: recommended,
      entryGapPct,
    };
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

  private parseRiskSeverity(value: unknown, reason: string): 'LOW' | 'MEDIUM' | 'HIGH' {
    if (typeof value === 'string') {
      const normalized = value.trim().toUpperCase();
      if (normalized === 'LOW' || normalized === 'MEDIUM' || normalized === 'HIGH') {
        return normalized;
      }
    }
    const loweredReason = reason.toLowerCase();
    if (
      loweredReason.includes('손절 근접') ||
      loweredReason.includes('추세 훼손') ||
      loweredReason.includes('급락') ||
      loweredReason.includes('고위험')
    ) {
      return 'HIGH';
    }
    return 'MEDIUM';
  }

  private parseRiskTags(value: unknown, reason: string): string[] {
    if (Array.isArray(value)) {
      return value
        .map((item) => (typeof item === 'string' ? item.trim().toUpperCase() : ''))
        .filter((item) => item.length > 0)
        .slice(0, 6);
    }
    const fallback: string[] = [];
    const loweredReason = reason.toLowerCase();
    if (loweredReason.includes('손절')) fallback.push('STOPLOSS_NEAR');
    if (loweredReason.includes('추세')) fallback.push('TREND_BROKEN');
    if (loweredReason.includes('변동성')) fallback.push('HIGH_VOLATILITY');
    return fallback;
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
    const advisoryStatuses = new Set<WorkerStatus>(['ENTERING', 'MANAGING', 'PLAYWRIGHT_CHECK']);
    const workerSummary = Array.from(this.workerStates.values())
      .filter((worker) => advisoryStatuses.has(worker.status))
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
