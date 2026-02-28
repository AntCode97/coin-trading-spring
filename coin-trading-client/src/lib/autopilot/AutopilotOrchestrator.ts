import {
  type AutopilotDecisionLogRequest,
  type AutopilotOpportunityView,
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
  type WorkerEvent,
  type WorkerOrderFlowEvent,
  type WorkerStateSnapshot,
  type WorkerStatus,
} from './MarketWorker';
import {
  runFineGrainedAgentPipeline,
  type FineGrainedDecisionResult,
} from './FineGrainedAgentPipeline';

export interface AutopilotConfig {
  enabled: boolean;
  interval: string;
  confirmInterval: string;
  tradingMode: TradingMode;
  amountKrw: number;
  dailyLossLimitKrw: number;
  maxConcurrentPositions: number;
  winRateThresholdMode: 'DYNAMIC_P70' | 'FIXED';
  fixedMinMarketWinRate: number;
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
  llmDailySoftCap: number;
  focusedScalpEnabled: boolean;
  focusedScalpMarkets: string[];
  focusedScalpPollIntervalMs: number;
  focusedWarnHoldingMs: number;
  focusedMaxHoldingMs: number;
  focusedEntryGate: 'FAST_ONLY';
  fineAgentEnabled?: boolean;
  fineAgentMaxPerTick?: number;
  fineAgentDecisionTtlMs?: number;
}

export type CandidateStage =
  | 'AUTO_PASS'
  | 'BORDERLINE'
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
  expectancyPct?: number | null;
  score?: number | null;
  riskRewardRatio?: number | null;
  entryGapPct?: number | null;
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
  decisionBreakdown: {
    autoPass: number;
    borderline: number;
    ruleFail: number;
  };
  llmBudget: {
    dailySoftCap: number;
    usedToday: number;
    exceeded: boolean;
  };
  focusedScalp: {
    enabled: boolean;
    markets: string[];
    pollIntervalSec: number;
  };
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
  expectancyPct: number;
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

interface SpawnWorkerOptions {
  skipLlmEntryReview: boolean;
  entryAmountKrw: number;
  sourceStage: 'AUTO_PASS' | 'BORDERLINE' | 'FOCUSED_SCALP';
  updateCandidateView?: boolean;
  workerTickMs?: number;
  warnHoldingMs?: number | null;
  maxHoldingMs?: number | null;
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
  private readonly focusedWorkerMarkets = new Set<string>();
  private readonly fineDecisionCache = new Map<string, { at: number; decision: FineGrainedDecisionResult }>();
  private readonly runId = crypto.randomUUID();
  private orderFlowLocal: AutopilotOrderFlowLocal = createEmptyOrderFlow();
  private loopId: number | null = null;
  private running = false;
  private startedAt: number | null = null;
  private blockedByDailyLoss = false;
  private blockedReason: string | null = null;
  private appliedRecommendedWinRateThreshold = 0;
  private llmUsageDateKst = this.kstDateKey();
  private llmUsageToday = 0;
  private llmSoftCapWarned = false;
  private lastOpenMarketSet = new Set<string>();
  private focusedEmptyWarned = false;
  private decisionBreakdown = {
    autoPass: 0,
    borderline: 0,
    ruleFail: 0,
  };

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
    this.loopId = window.setInterval(() => void this.tick(), 10000);
    this.emitState();
  }

  stop(): void {
    this.running = false;
    if (this.loopId != null) {
      window.clearInterval(this.loopId);
      this.loopId = null;
    }
    for (const worker of this.workers.values()) {
      worker.stop('오케스트레이터 중지');
    }
    this.workers.clear();
    this.workerStates.clear();
    this.focusedWorkerMarkets.clear();
    this.lastOpenMarketSet = new Set();
    this.focusedEmptyWarned = false;
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
    const focusedMarkets = this.normalizeFocusedMarkets();
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
      decisionBreakdown: { ...this.decisionBreakdown },
      llmBudget: {
        dailySoftCap: this.config.llmDailySoftCap,
        usedToday: this.llmUsageToday,
        exceeded: this.llmUsageToday >= this.config.llmDailySoftCap,
      },
      focusedScalp: {
        enabled: this.config.focusedScalpEnabled,
        markets: focusedMarkets,
        pollIntervalSec: Math.max(1, Math.round(this.config.focusedScalpPollIntervalMs / 1000)),
      },
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
      expectancyPct: seed?.expectancyPct ?? prev?.expectancyPct ?? null,
      score: seed?.score ?? prev?.score ?? null,
      riskRewardRatio: seed?.riskRewardRatio ?? prev?.riskRewardRatio ?? null,
      entryGapPct: seed?.entryGapPct ?? prev?.entryGapPct ?? null,
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
      this.rolloverLlmUsageIfNeeded();
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
      const openMarketSet = new Set(
        openPositions
          .filter((position) => position.status === 'OPEN' || position.status === 'PENDING_ENTRY')
          .map((position) => position.market)
      );
      this.lastOpenMarketSet = openMarketSet;
      const focusedMarkets = this.normalizeFocusedMarkets();
      const focusedMarketSet = new Set(focusedMarkets);
      this.syncFocusedWorkers(focusedMarkets);
      this.ensureWorkersForOpenPositions(openPositions);
      if (this.config.focusedScalpEnabled && focusedMarkets.length === 0) {
        if (!this.focusedEmptyWarned) {
          this.focusedEmptyWarned = true;
          this.pushEvent({
            type: 'SYSTEM',
            level: 'WARN',
            action: 'FOCUSED_SCALP_EMPTY',
            detail: '선택 코인 단타 루프가 활성화됐지만 코인 목록이 비어 있어 대기 상태입니다.',
          });
          this.writeLog('선택 코인 단타 루프: 코인 목록 없음');
        }
      } else {
        this.focusedEmptyWarned = false;
      }

      if (this.blockedByDailyLoss) {
        this.emitState();
        return;
      }

      const opportunities = await guidedTradingApi.getAutopilotOpportunities(
        this.config.interval,
        this.config.confirmInterval,
        this.config.tradingMode,
        this.config.candidateLimit
      );
      const scopedCandidates = opportunities.opportunities
        .filter((candidate) => !focusedMarketSet.has(candidate.market))
        .slice(0, this.config.candidateLimit);
      this.appliedRecommendedWinRateThreshold = this.resolveOpportunityDisplayThreshold(scopedCandidates);

      let availableSlots = Math.max(0, this.config.maxConcurrentPositions - openMarketSet.size);
      const nextCandidates: AutopilotCandidateView[] = [];
      const activeGlobalMarkets = new Set<string>();
      const fineAgentEnabled = this.isFineAgentEnabled();
      const fineAgentMaxPerTick = this.resolveFineAgentMaxPerTick();
      let fineAgentUsedInTick = 0;
      let autoPass = 0;
      let borderline = 0;
      let ruleFail = 0;

      for (const candidate of scopedCandidates) {
        this.candidateScoreByMarket.set(candidate.market, {
          market: candidate.market,
          score: candidate.score,
          riskRewardRatio: candidate.riskReward1m,
          entryGapPct: candidate.entryGapPct1m,
          expectancyPct: candidate.expectancyPct,
        });

        const evaluation = this.evaluateOpportunityEligibility(
          candidate,
          openMarketSet,
          availableSlots
        );
        let stage: CandidateStage = candidate.stage as CandidateStage;
        let reason = candidate.reason;
        let fineDecision: FineGrainedDecisionResult | null = null;
        let executed = false;
        let entryAmountKrw: number | null = null;

        if (!evaluation.allow) {
          stage = evaluation.stage;
          reason = evaluation.reason;
        } else if (
          fineAgentEnabled &&
          (stage === 'AUTO_PASS' || stage === 'BORDERLINE') &&
          fineAgentUsedInTick < fineAgentMaxPerTick
        ) {
          fineAgentUsedInTick += 1;
          fineDecision = await this.resolveFineGrainedDecision(candidate);
          stage = fineDecision.stage;
          reason = `FineAgent: ${fineDecision.reason}`;
          this.pushEvent({
            type: 'CANDIDATE',
            level: fineDecision.approve ? 'INFO' : 'WARN',
            market: candidate.market,
            action: 'FINE_AGENT_REVIEW',
            detail: `stage=${fineDecision.stage}, score=${fineDecision.score.toFixed(1)}, confidence=${fineDecision.confidence.toFixed(0)} | ${fineDecision.reason}`,
          });
        }
        const stageForStats = stage;

        if (evaluation.allow && (stage === 'AUTO_PASS' || stage === 'BORDERLINE')) {
          activeGlobalMarkets.add(candidate.market);
          const sourceStage = stage === 'AUTO_PASS' ? 'AUTO_PASS' : 'BORDERLINE';
          entryAmountKrw = this.resolveEntryAmount(sourceStage);
          this.spawnWorker(
            candidate.market,
            {
              market: candidate.market,
              koreanName: candidate.koreanName,
              recommendedEntryWinRate: candidate.recommendedEntryWinRate1m ?? null,
              marketEntryWinRate: candidate.marketEntryWinRate1m ?? null,
              expectancyPct: candidate.expectancyPct,
              score: candidate.score,
              riskRewardRatio: candidate.riskReward1m,
              entryGapPct: candidate.entryGapPct1m,
            },
            {
              skipLlmEntryReview: sourceStage === 'AUTO_PASS',
              entryAmountKrw,
              sourceStage,
            }
          );
          stage = 'ENTERED';
          reason = `${sourceStage} 워커 생성 (진입금 ${entryAmountKrw.toLocaleString('ko-KR')}원)`;
          availableSlots -= 1;
          executed = true;
          this.pushEvent({
            type: 'CANDIDATE',
            level: 'INFO',
            market: candidate.market,
            action: 'ENTERED',
            detail: `${candidate.koreanName} ${sourceStage} 후보 진입 파이프라인 시작`,
          });
        }

        if (stageForStats === 'AUTO_PASS') autoPass += 1;
        else if (stageForStats === 'BORDERLINE') borderline += 1;
        else if (stageForStats === 'RULE_FAIL') ruleFail += 1;

        if (fineDecision) {
          void this.logFineGrainedDecision(candidate, fineDecision, executed, entryAmountKrw);
        }

        nextCandidates.push({
          market: candidate.market,
          koreanName: candidate.koreanName,
          recommendedEntryWinRate: candidate.recommendedEntryWinRate1m ?? null,
          marketEntryWinRate: candidate.marketEntryWinRate1m ?? null,
          expectancyPct: candidate.expectancyPct,
          score: candidate.score,
          riskRewardRatio: candidate.riskReward1m,
          entryGapPct: candidate.entryGapPct1m,
          stage,
          reason,
          updatedAt: Date.now(),
        });
      }

      this.decisionBreakdown = { autoPass, borderline, ruleFail };
      this.pruneIdleWorkers(activeGlobalMarkets, openMarketSet, focusedMarketSet);
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

  private isFineAgentEnabled(): boolean {
    return this.config.fineAgentEnabled !== false;
  }

  private resolveFineAgentMaxPerTick(): number {
    return Math.min(8, Math.max(1, Math.round(this.config.fineAgentMaxPerTick ?? 3)));
  }

  private resolveFineAgentDecisionTtlMs(): number {
    return Math.min(5 * 60_000, Math.max(15_000, Math.round(this.config.fineAgentDecisionTtlMs ?? 60_000)));
  }

  private async resolveFineGrainedDecision(
    candidate: AutopilotOpportunityView
  ): Promise<FineGrainedDecisionResult> {
    const ttlMs = this.resolveFineAgentDecisionTtlMs();
    const cached = this.fineDecisionCache.get(candidate.market);
    const now = Date.now();
    if (cached && now - cached.at <= ttlMs) {
      return cached.decision;
    }

    const context = await guidedTradingApi.getAgentContext(
      candidate.market,
      this.config.interval,
      120,
      20,
      this.config.tradingMode
    );
    for (let i = 0; i < 5; i += 1) {
      this.recordLlmUsage('FINE_AGENT_PIPELINE', candidate.market);
    }
    const decision = await runFineGrainedAgentPipeline({
      market: candidate.market,
      tradingMode: this.config.tradingMode,
      model: this.config.llmModel,
      minLlmConfidence: this.config.minLlmConfidence,
      candidate,
      context,
    });

    this.fineDecisionCache.set(candidate.market, {
      at: now,
      decision,
    });
    for (const [market, item] of this.fineDecisionCache.entries()) {
      if (now - item.at > ttlMs * 2) {
        this.fineDecisionCache.delete(market);
      }
    }
    return decision;
  }

  private async logFineGrainedDecision(
    candidate: AutopilotOpportunityView,
    decision: FineGrainedDecisionResult,
    executed: boolean,
    entryAmountKrw: number | null
  ): Promise<void> {
    const payload: AutopilotDecisionLogRequest = {
      runId: this.runId,
      market: candidate.market,
      interval: this.config.interval,
      mode: this.config.tradingMode,
      stage: decision.stage,
      approve: decision.approve,
      confidence: decision.confidence,
      score: decision.score,
      reason: decision.reason,
      executed,
      entryAmountKrw,
      specialistOutputs: decision.specialists.map((item) => this.toSerializable(item)),
      synthOutput: this.toSerializable(decision.synth),
      pmOutput: this.toSerializable(decision.pm),
      orderPlan: this.toSerializable(decision.pm.orderPlan),
    };
    try {
      await guidedTradingApi.logAutopilotDecision(payload);
    } catch (error) {
      const message = error instanceof Error ? error.message : '결정 로그 전송 실패';
      this.pushEvent({
        type: 'SYSTEM',
        level: 'WARN',
        market: candidate.market,
        action: 'DECISION_LOG_WARN',
        detail: message,
      });
    }
  }

  private toSerializable(value: unknown): Record<string, unknown> {
    try {
      return JSON.parse(JSON.stringify(value)) as Record<string, unknown>;
    } catch {
      return {};
    }
  }

  private evaluateOpportunityEligibility(
    candidate: AutopilotOpportunityView,
    openMarketSet: Set<string>,
    availableSlots: number
  ): { allow: boolean; stage: CandidateStage; reason: string } {
    if (candidate.stage === 'RULE_FAIL') {
      return {
        allow: false,
        stage: 'RULE_FAIL',
        reason: candidate.reason,
      };
    }
    if (openMarketSet.has(candidate.market)) {
      return {
        allow: false,
        stage: 'POSITION_OPEN',
        reason: '이미 포지션 존재',
      };
    }
    const now = Date.now();
    const cooldownUntil = this.cooldownUntilByMarket.get(candidate.market);
    if (cooldownUntil && cooldownUntil > now) {
      return {
        allow: false,
        stage: 'COOLDOWN',
        reason: `워커 쿨다운 ${Math.ceil((cooldownUntil - now) / 1000)}초`,
      };
    }
    const workerState = this.workerStates.get(candidate.market);
    if (
      workerState?.status === 'COOLDOWN' &&
      workerState.cooldownUntil != null &&
      workerState.cooldownUntil > now
    ) {
      return {
        allow: false,
        stage: 'COOLDOWN',
        reason: `워커 쿨다운 ${Math.ceil((workerState.cooldownUntil - now) / 1000)}초`,
      };
    }
    if (this.workers.has(candidate.market)) {
      return {
        allow: false,
        stage: 'WORKER_ACTIVE',
        reason: '이미 워커 실행 중',
      };
    }
    if (availableSlots <= 0) {
      return {
        allow: false,
        stage: 'SLOT_FULL',
        reason: '동시 포지션 슬롯 부족',
      };
    }
    return {
      allow: true,
      stage: 'RULE_PASS',
      reason: '진입 가능',
    };
  }

  private resolveEntryAmount(stage: 'AUTO_PASS' | 'BORDERLINE'): number {
    const scale = stage === 'AUTO_PASS' ? 1.15 : 0.85;
    const scaled = Math.round(this.config.amountKrw * scale);
    return Math.max(5100, Math.min(20_000, scaled));
  }

  private resolveOpportunityDisplayThreshold(candidates: AutopilotOpportunityView[]): number {
    const wins = candidates
      .map((candidate) => candidate.recommendedEntryWinRate1m)
      .filter((value): value is number => value != null && Number.isFinite(value));
    if (wins.length === 0) return 0;
    const avg = wins.reduce((acc, value) => acc + value, 0) / wins.length;
    return Math.round(avg * 10) / 10;
  }

  private normalizeFocusedMarket(value: string): string | null {
    const raw = value.trim().toUpperCase();
    if (!raw) return null;
    const withPrefix = raw.startsWith('KRW-') ? raw : `KRW-${raw}`;
    const symbol = withPrefix.replace('KRW-', '');
    if (!symbol || !/^[A-Z0-9]+$/.test(symbol)) return null;
    return withPrefix;
  }

  private normalizeFocusedMarkets(): string[] {
    const seen = new Set<string>();
    const normalized: string[] = [];
    for (const raw of this.config.focusedScalpMarkets) {
      const market = this.normalizeFocusedMarket(raw);
      if (!market || seen.has(market)) continue;
      seen.add(market);
      normalized.push(market);
      if (normalized.length >= 8) break;
    }
    return normalized;
  }

  private syncFocusedWorkers(focusedMarkets: string[]): void {
    const targetMarkets = this.config.focusedScalpEnabled ? new Set(focusedMarkets) : new Set<string>();
    for (const market of Array.from(this.focusedWorkerMarkets)) {
      if (targetMarkets.has(market)) continue;
      const worker = this.workers.get(market);
      if (worker) {
        worker.stop('선택 코인 루프 대상 해제');
      }
      this.focusedWorkerMarkets.delete(market);
      this.pushEvent({
        type: 'SYSTEM',
        level: 'INFO',
        market,
        action: 'FOCUSED_SCALP_STOP',
        detail: '선택 코인 단타 루프에서 제거',
      });
    }

    if (!this.config.focusedScalpEnabled) return;

    for (const market of focusedMarkets) {
      const wasFocusedWorker = this.focusedWorkerMarkets.has(market);
      this.focusedWorkerMarkets.add(market);
      if (this.workers.has(market) && !wasFocusedWorker) {
        const existing = this.workers.get(market);
        existing?.stop('선택 코인 루프로 전환');
        this.workers.delete(market);
        this.workerStates.delete(market);
      }
      if (this.workers.has(market)) continue;
      this.spawnWorker(
        market,
        {
          market,
          koreanName: market,
          recommendedEntryWinRate: null,
          marketEntryWinRate: null,
        },
        {
          skipLlmEntryReview: this.config.focusedEntryGate === 'FAST_ONLY',
          entryAmountKrw: Math.max(5100, Math.min(20_000, this.config.amountKrw)),
          sourceStage: 'FOCUSED_SCALP',
          updateCandidateView: false,
          workerTickMs: this.config.focusedScalpPollIntervalMs,
          warnHoldingMs: this.config.focusedWarnHoldingMs,
          maxHoldingMs: this.config.focusedMaxHoldingMs,
        }
      );
      this.pushEvent({
        type: 'SYSTEM',
        level: 'INFO',
        market,
        action: 'FOCUSED_SCALP_START',
        detail: `선택 코인 단타 루프 시작 (${Math.max(1, Math.round(this.config.focusedScalpPollIntervalMs / 1000))}초)`,
      });
    }
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

  private pruneIdleWorkers(
    shortlistMarkets: Set<string>,
    openMarketSet: Set<string>,
    focusedMarketSet: Set<string>
  ): void {
    for (const [market, worker] of this.workers.entries()) {
      if (focusedMarketSet.has(market) || this.focusedWorkerMarkets.has(market)) continue;
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
    options?: SpawnWorkerOptions
  ): void {
    const normalized = market.trim().toUpperCase();
    if (this.workers.has(normalized)) return;
    const effectiveOptions: SpawnWorkerOptions = options ?? {
      skipLlmEntryReview: false,
      entryAmountKrw: this.resolveEntryAmount('BORDERLINE'),
      sourceStage: 'BORDERLINE',
      updateCandidateView: true,
    };

    const worker = new MarketWorker(
      {
        market: normalized,
        tickMs: effectiveOptions.workerTickMs ?? this.config.workerTickMs,
        rejectCooldownMs: this.config.rejectCooldownMs,
        postExitCooldownMs: this.config.postExitCooldownMs,
        llmReviewMs: this.config.llmReviewIntervalMs,
        minLlmConfidence: this.config.minLlmConfidence,
        enablePlaywrightCheck: this.config.playwrightEnabled,
        entryPolicy: this.config.entryPolicy,
        entryOrderMode: this.config.entryOrderMode,
        pendingEntryTimeoutMs: Math.max(10_000, this.config.pendingEntryTimeoutSec * 1000),
        marketFallbackAfterCancel: this.config.marketFallbackAfterCancel,
        skipLlmEntryReview: effectiveOptions.skipLlmEntryReview,
        warnHoldingMs: effectiveOptions.warnHoldingMs ?? null,
        maxHoldingMs: effectiveOptions.maxHoldingMs ?? null,
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
            amountKrw: effectiveOptions.entryAmountKrw,
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
        canEnterNewPosition: (workerMarket) => {
          if (this.blockedByDailyLoss) {
            return {
              allow: false,
              reason: this.blockedReason ?? '일일 손실 제한으로 신규 진입 차단',
            };
          }
          if (this.lastOpenMarketSet.has(workerMarket)) {
            return { allow: true };
          }
          const openCount = this.lastOpenMarketSet.size;
          if (openCount >= this.config.maxConcurrentPositions) {
            return {
              allow: false,
              reason: `동시 포지션 슬롯 부족 (${openCount}/${this.config.maxConcurrentPositions})`,
            };
          }
          return { allow: true };
        },
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
    const scoreNote = seedCandidate?.score != null ? `score ${seedCandidate.score.toFixed(1)}` : 'score -';
    if (effectiveOptions.updateCandidateView !== false) {
      this.setCandidateStage(
        normalized,
        'ENTERED',
        `워커 생성 (${effectiveOptions.sourceStage}, ${scoreNote}, ${effectiveOptions.entryAmountKrw.toLocaleString('ko-KR')}원)`,
        seedCandidate
      );
    }
    worker.start();
    this.writeLog(
      `[${normalized}] 워커 생성 (${effectiveOptions.sourceStage}, 진입금 ${effectiveOptions.entryAmountKrw.toLocaleString('ko-KR')}원)`
    );
  }

  private cleanupExpiredCooldowns(): void {
    const now = Date.now();
    for (const [market, until] of this.cooldownUntilByMarket.entries()) {
      if (until <= now) {
        this.cooldownUntilByMarket.delete(market);
      }
    }
  }

  private kstDateKey(now = new Date()): string {
    return now.toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' });
  }

  private rolloverLlmUsageIfNeeded(): void {
    const key = this.kstDateKey();
    if (this.llmUsageDateKst === key) return;
    this.llmUsageDateKst = key;
    this.llmUsageToday = 0;
    this.llmSoftCapWarned = false;
  }

  private recordLlmUsage(action: string, market?: string): void {
    this.rolloverLlmUsageIfNeeded();
    this.llmUsageToday += 1;
    if (!this.llmSoftCapWarned && this.llmUsageToday >= this.config.llmDailySoftCap) {
      this.llmSoftCapWarned = true;
      this.pushEvent({
        type: 'LLM',
        level: 'WARN',
        market,
        action: 'LLM_SOFT_CAP',
        detail: `일일 LLM 사용량이 소프트 상한(${this.config.llmDailySoftCap}회)을 초과했습니다. 호출은 계속 허용됩니다. (${action})`,
      });
      this.writeLog(`LLM 소프트 상한 초과: ${this.llmUsageToday}/${this.config.llmDailySoftCap}`);
    }
  }

  private async evaluateEntry(
    market: string,
    context: GuidedAgentContextResponse
  ): Promise<EntryDecision> {
    this.recordLlmUsage('ENTRY_REVIEW', market);
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
    this.recordLlmUsage('POSITION_REVIEW', market);
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
}

export function isActiveWorkerStatus(status: WorkerStatus): boolean {
  return status !== 'STOPPED';
}
