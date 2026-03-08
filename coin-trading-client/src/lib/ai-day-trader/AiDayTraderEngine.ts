import {
  guidedTradingApi,
  type AiScalpScanMarket,
  type GuidedClosedTradeView,
  type GuidedAgentContextResponse,
  type GuidedTradePosition,
} from '../../api';
import {
  requestOneShotTextWithMeta,
} from '../llmService';
import {
  createInitialAiTraderState,
  type AiClosedTrade,
  type AiDayTraderConfig,
  type AiEntryAggression,
  type AiExitCategory,
  type AiMonitorActor,
  type AiMonitorActorRole,
  type AiMonitorActorStatus,
  type AiRankedOpportunity,
  type AiTraderEvent,
  type AiTraderEventType,
  type AiTraderState,
  type AiTraderStatus,
  type AiUrgency,
} from './AiDayTraderModel';
import {
  ENTRY_SYSTEM_PROMPT,
  MANAGE_SYSTEM_PROMPT,
  SHORTLIST_SYSTEM_PROMPT,
  buildEntryAggressionProfile as buildPolicyEntryAggressionProfile,
  buildPerformanceSnapshot as buildPolicyPerformanceSnapshot,
  evaluateDeterministicExit as evaluatePolicyDeterministicExit,
  evaluateEntryGate as evaluatePolicyEntryGate,
  getEffectiveMaxHoldingMinutes as getPolicyEffectiveMaxHoldingMinutes,
  getTempoProfile as getPolicyTempoProfile,
  type AiDeterministicExitDecision,
  type AiEntryAggressionProfile,
  type AiPerformanceSnapshot,
  type AiTempoProfile,
} from './AiDayTraderPolicy';
import {
  normalizePosition as normalizeAiPosition,
} from './AiDayTraderPositionMath';
export {
  AI_ENTRY_AGGRESSION_OPTIONS,
  DEFAULT_AI_DAY_TRADER_CONFIG,
  createInitialAiTraderState,
  type AiClosedTrade,
  type AiDayTraderConfig,
  type AiEntryAggression,
  type AiExitCategory,
  type AiMonitorActor,
  type AiMonitorActorKind,
  type AiMonitorActorRole,
  type AiMonitorActorStatus,
  type AiRankedOpportunity,
  type AiTraderEvent,
  type AiTraderEventType,
  type AiTraderState,
  type AiTraderStatus,
  type AiUrgency,
} from './AiDayTraderModel';

interface AiEntryDecision {
  action: 'BUY' | 'WAIT';
  confidence: number;
  reasoning: string;
  stopLoss?: number;
  takeProfit?: number;
  urgency: AiUrgency;
}

interface AiManageDecision {
  action: 'HOLD' | 'SELL';
  confidence: number;
  reasoning: string;
  urgency: AiUrgency;
}

type StateListener = (state: AiTraderState) => void;

const SHORTLIST_LIMIT = 12;
const EVENT_HISTORY_LIMIT = 240;
const CLOSED_TRADE_LIMIT = 200;
const TAKE_PROFIT_COOLDOWN_MS = 120_000;
const LLM_EXIT_COOLDOWN_MS = 240_000;
const FORCED_EXIT_COOLDOWN_MS = 300_000;
const STOP_LOSS_COOLDOWN_MS = 480_000;
const CONTEXT_FAILURE_EXIT_THRESHOLD = 3;
const TODAY_STATS_SYNC_MS = 30_000;
const PERFORMANCE_GUARD_PAUSE_MS = 20 * 60_000;
const PERFORMANCE_MARKET_LOCK_MS = 4 * 60 * 60_000;
const PERFORMANCE_MARKET_DEEP_LOCK_MS = 12 * 60 * 60_000;
const MONITOR_CORE_RESET_MS = 2_800;
const MONITOR_SUBAGENT_DISMISS_MS = 4_200;

let nextId = 0;

function makeId(prefix: string): string {
  nextId += 1;
  return `${prefix}_${Date.now()}_${nextId}`;
}

function clamp(value: number, minValue: number, maxValue: number): number {
  return Math.min(maxValue, Math.max(minValue, value));
}

function toFiniteNumber(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function parseJsonObject(text: string): Record<string, unknown> | null {
  try {
    const fenced = text.match(/```json\s*([\s\S]*?)```/i);
    const raw = fenced?.[1] ?? text.match(/\{[\s\S]*\}/)?.[0];
    if (!raw) return null;
    return JSON.parse(raw.trim()) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function normalizeUrgency(value: unknown): AiUrgency {
  const normalized = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return normalized === 'LOW' || normalized === 'HIGH' ? normalized : 'MEDIUM';
}

function normalizeMarket(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim().toUpperCase();
  return normalized.startsWith('KRW-') ? normalized : null;
}

function normalizeStrategyPrefix(value: string): string {
  const normalized = value.trim().toUpperCase();
  return normalized || 'AI_SCALP_TRADER';
}

function cloneState(state: AiTraderState): AiTraderState {
  return {
    ...state,
    events: [...state.events],
    positions: [...state.positions],
    queue: [...state.queue],
    closedTrades: [...state.closedTrades],
    monitorActors: state.monitorActors.map((actor) => ({ ...actor })),
  };
}

export class AiDayTraderEngine {
  private config: AiDayTraderConfig;
  private state: AiTraderState;
  private listeners = new Set<StateListener>();
  private scanTimer: number | null = null;
  private manageTimer: number | null = null;
  private scanInFlight = false;
  private manageInFlight = false;
  private cooldownUntilByMarket = new Map<string, number>();
  private contextFailureCountByMarket = new Map<string, number>();
  private peakNetPnlByTradeId = new Map<number, number>();
  private lastTodayStatsSyncAt = 0;
  private todayStatsSyncFailed = false;
  private performancePauseUntil: number | null = null;
  private performancePauseTradeId: number | null = null;
  private monitorStatusResetTimers = new Map<string, number>();
  private pendingSubagentIdsByContext = new Map<string, string[]>();
  private subagentSequence = 0;
  private performanceSnapshot: AiPerformanceSnapshot = {
    regime: 'NORMAL',
    selectedAggression: 'BALANCED',
    effectiveAggression: 'BALANCED',
    consecutiveLosses: 0,
    recentTradeCount: 0,
    recentNetPnlKrw: 0,
    recentAvgPnlPercent: 0,
  };

  constructor(config: AiDayTraderConfig) {
    this.config = { ...config };
    this.state = createInitialAiTraderState();
  }

  subscribe(listener: StateListener): () => void {
    this.listeners.add(listener);
    listener(cloneState(this.state));
    return () => this.listeners.delete(listener);
  }

  getState(): AiTraderState {
    return cloneState(this.state);
  }

  updateConfig(patch: Partial<AiDayTraderConfig>) {
    this.config = { ...this.config, ...patch };
  }

  setMonitorFocus(actorId: string | null) {
    if (this.state.monitorFocusId === actorId) return;
    this.state.monitorFocusId = actorId;
    this.emit();
  }

  isRunning(): boolean {
    return this.state.running;
  }

  start() {
    if (this.state.running) {
      if (!this.state.entryEnabled) {
        this.state.entryEnabled = true;
        this.setBlockedReason(null);
        this.setStatus('IDLE');
        this.setCoreActorState('ENTRY', 'WAITING', { taskSummary: '신규 진입 재개 대기' });
        this.emit();
        this.pushEvent('STATUS', '신규 진입 재개');
        if (this.scanTimer === null) {
          this.scanTimer = window.setInterval(() => {
            void this.runScanCycle();
          }, this.config.scanIntervalMs);
        }
        if (this.manageTimer === null) {
          this.manageTimer = window.setInterval(() => {
            void this.runPositionManagement();
          }, this.config.positionCheckMs);
        }
        void this.runScanCycle();
      }
      return;
    }
    this.state.running = true;
    this.state.entryEnabled = true;
    this.state.status = 'IDLE';
    this.state.monitorFocusId = this.state.monitorFocusId ?? 'core-scan';
    this.resetCoreActors();
    this.emit();
    this.pushEvent('STATUS', 'LLM 초단타 터미널 시작');
    void this.syncTodayPerformance(true);
    void this.syncOpenPositions();
    void this.runScanCycle();
    void this.runPositionManagement();
    this.scanTimer = window.setInterval(() => {
      void this.runScanCycle();
    }, this.config.scanIntervalMs);
    this.manageTimer = window.setInterval(() => {
      void this.runPositionManagement();
    }, this.config.positionCheckMs);
  }

  stop() {
    if (!this.state.running) return;
    if (this.scanTimer !== null) {
      window.clearInterval(this.scanTimer);
      this.scanTimer = null;
    }
    this.state.entryEnabled = false;
    this.state.queue = [];
    if (this.state.positions.length === 0) {
      if (this.manageTimer !== null) {
        window.clearInterval(this.manageTimer);
        this.manageTimer = null;
      }
      this.state.running = false;
      this.state.status = 'IDLE';
      this.setBlockedReason(null);
      this.resetCoreActors();
      this.emit();
      this.pushEvent('STATUS', 'LLM 초단타 터미널 중지');
      return;
    }
    this.setBlockedReason('신규 진입 중지 · 보유 포지션만 자동 관리');
    this.setStatus('PAUSED');
    this.setCoreActorState('ENTRY', 'WAITING', { taskSummary: '신규 진입 중지 · 보유 포지션만 관리' });
    this.emit();
    this.pushEvent('STATUS', '신규 진입 중지 · 보유 포지션 관리 계속');
  }

  private emit() {
    const snapshot = cloneState(this.state);
    for (const listener of this.listeners) {
      listener(snapshot);
    }
  }

  private pushEvent(
    type: AiTraderEventType,
    message: string,
    detail?: string,
    market?: string,
    confidence?: number,
    urgency?: AiUrgency
  ) {
    const event: AiTraderEvent = {
      id: makeId('evt'),
      type,
      message,
      detail,
      market,
      confidence,
      urgency,
      timestamp: Date.now(),
    };
    this.state.events = [event, ...this.state.events].slice(0, EVENT_HISTORY_LIMIT);
    this.emit();
  }

  private setStatus(status: AiTraderStatus) {
    if (this.state.status === status) return;
    this.state.status = status;
    this.emit();
  }

  private resetCoreActors() {
    const now = Date.now();
    this.state.monitorActors = this.state.monitorActors.map((actor) => {
      if (actor.kind !== 'CORE') return actor;
      return {
        ...actor,
        status: 'IDLE',
        market: undefined,
        taskSummary: undefined,
        lastResult: undefined,
        updatedAt: now,
      };
    });
  }

  private withMonitorCleanup(actorId: string, callback: () => void, delayMs: number) {
    const existingTimer = this.monitorStatusResetTimers.get(actorId);
    if (existingTimer !== undefined) {
      window.clearTimeout(existingTimer);
    }
    const nextTimer = window.setTimeout(() => {
      this.monitorStatusResetTimers.delete(actorId);
      callback();
    }, delayMs);
    this.monitorStatusResetTimers.set(actorId, nextTimer);
  }

  private updateMonitorActor(
    actorId: string,
    patch: Partial<AiMonitorActor>,
    options?: { resetToIdleAfterMs?: number; removeAfterMs?: number }
  ) {
    const now = Date.now();
    let changed = false;
    this.state.monitorActors = this.state.monitorActors.map((actor) => {
      if (actor.id !== actorId) return actor;
      changed = true;
      return {
        ...actor,
        ...patch,
        updatedAt: now,
        startedAt: patch.status === 'BUSY' && actor.status !== 'BUSY' ? now : actor.startedAt || now,
      };
    });
    if (changed) {
      this.emit();
    }
    if (options?.resetToIdleAfterMs) {
      this.withMonitorCleanup(actorId, () => {
        this.updateMonitorActor(actorId, {
          status: 'IDLE',
          market: undefined,
          taskSummary: undefined,
        });
      }, options.resetToIdleAfterMs);
    }
    if (options?.removeAfterMs) {
      this.withMonitorCleanup(actorId, () => {
        this.state.monitorActors = this.state.monitorActors.filter((actor) => actor.id !== actorId);
        if (this.state.monitorFocusId === actorId) {
          this.state.monitorFocusId = null;
        }
        this.emit();
      }, options.removeAfterMs);
    }
  }

  private setCoreActorState(
    role: AiMonitorActorRole,
    status: AiMonitorActorStatus,
    patch: Partial<AiMonitorActor> = {},
    options?: { transient?: boolean }
  ) {
    const actorId = `core-${role.toLowerCase()}`;
    this.updateMonitorActor(actorId, {
      status,
      ...patch,
    }, options?.transient
      ? { resetToIdleAfterMs: MONITOR_CORE_RESET_MS }
      : undefined);
  }

  private buildSubagentLabel(toolName: string): string {
    if (toolName === 'delegate_to_zai_agent') {
      return 'Z.ai Delegate';
    }
    return toolName.replaceAll('_', ' ');
  }

  private buildToolTaskSummary(toolName: string, args: string): string {
    if (toolName !== 'delegate_to_zai_agent') {
      return args.slice(0, 120);
    }
    try {
      const parsed = JSON.parse(args) as Record<string, unknown>;
      const task = typeof parsed.task === 'string' ? parsed.task.trim() : '';
      return task.slice(0, 160) || 'delegate task';
    } catch {
      return 'delegate task';
    }
  }

  private startSubagentActor(contextId: string, parentRole: AiMonitorActorRole, market: string | undefined, toolName: string, args: string) {
    this.subagentSequence += 1;
    const actorId = `subagent-${this.subagentSequence}`;
    const actor: AiMonitorActor = {
      id: actorId,
      kind: 'SUBAGENT',
      role: 'DELEGATE',
      status: 'BUSY',
      label: this.buildSubagentLabel(toolName),
      market,
      taskSummary: this.buildToolTaskSummary(toolName, args),
      parentId: `core-${parentRole.toLowerCase()}`,
      toolName,
      startedAt: Date.now(),
      updatedAt: Date.now(),
    };
    this.state.monitorActors = [...this.state.monitorActors, actor];
    const queue = this.pendingSubagentIdsByContext.get(contextId) ?? [];
    queue.push(actorId);
    this.pendingSubagentIdsByContext.set(contextId, queue);
    this.state.monitorFocusId = actorId;
    this.emit();
  }

  private completeSubagentActor(contextId: string, toolName: string, result: string) {
    const queue = this.pendingSubagentIdsByContext.get(contextId);
    const actorId = queue?.shift();
    if (queue && queue.length === 0) {
      this.pendingSubagentIdsByContext.delete(contextId);
    }
    if (!actorId) return;
    const isError = /\"error\"|실패|error|timeout|초과|미연결/i.test(result);
    this.updateMonitorActor(actorId, {
      status: isError ? 'ERROR' : 'SUCCESS',
      lastResult: result.slice(0, 220),
      toolName,
    }, { removeAfterMs: MONITOR_SUBAGENT_DISMISS_MS });
  }

  private buildToolMonitorCallbacks(parentRole: AiMonitorActorRole, market?: string) {
    const contextId = makeId('subctx');
    return {
      onToolCall: (toolName: string, args: string) => {
        this.startSubagentActor(contextId, parentRole, market, toolName, args);
      },
      onToolResult: (toolName: string, result: string) => {
        this.completeSubagentActor(contextId, toolName, result);
      },
    };
  }

  private setBlockedReason(reason: string | null) {
    if (this.state.blockedReason === reason) return;
    const previous = this.state.blockedReason;
    this.state.blockedReason = reason;
    this.emit();
    if (reason) {
      this.pushEvent('STATUS', reason);
      return;
    }
    if (previous) {
      this.pushEvent('STATUS', '진입 차단 해제');
    }
  }

  private setIdleIfReady() {
    if (!this.state.running) return;
    if (this.scanInFlight || this.manageInFlight) return;
    if (!this.state.entryEnabled) {
      this.setStatus('PAUSED');
      return;
    }
    if (this.state.blockedReason) {
      this.setStatus('PAUSED');
      return;
    }
    this.setStatus('IDLE');
  }

  private async syncOpenPositions(): Promise<GuidedTradePosition[]> {
    const positions = await guidedTradingApi.getOpenPositions();
    const filtered = this.filterAiPositions(positions)
      .map((position) => this.normalizePosition(position))
      .sort((left, right) => new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime());
    const activeTradeIds = new Set(filtered.map((position) => position.tradeId));
    for (const position of filtered) {
      const previousPeak = this.peakNetPnlByTradeId.get(position.tradeId) ?? position.unrealizedPnlPercent;
      this.peakNetPnlByTradeId.set(position.tradeId, Math.max(previousPeak, position.unrealizedPnlPercent));
    }
    for (const tradeId of [...this.peakNetPnlByTradeId.keys()]) {
      if (!activeTradeIds.has(tradeId)) {
        this.peakNetPnlByTradeId.delete(tradeId);
      }
    }
    this.state.positions = filtered;
    this.emit();
    return filtered;
  }

  private filterAiPositions(positions: GuidedTradePosition[]): GuidedTradePosition[] {
    const strategyPrefix = normalizeStrategyPrefix(this.config.strategyCode);
    return positions.filter((position) => {
      const strategyCode = position.strategyCode?.trim().toUpperCase();
      return Boolean(strategyCode?.startsWith(strategyPrefix));
    });
  }

  private pruneTransientState() {
    const now = Date.now();
    for (const [market, until] of this.cooldownUntilByMarket.entries()) {
      if (until <= now) {
        this.cooldownUntilByMarket.delete(market);
      }
    }
    if (this.state.events.length > EVENT_HISTORY_LIMIT) {
      this.state.events = this.state.events.slice(0, EVENT_HISTORY_LIMIT);
    }
    if (this.state.closedTrades.length > CLOSED_TRADE_LIMIT) {
      this.state.closedTrades = this.state.closedTrades.slice(0, CLOSED_TRADE_LIMIT);
    }
  }

  private async runScanCycle() {
    if (!this.state.running || !this.state.entryEnabled || this.scanInFlight) return;
    this.scanInFlight = true;
    this.pruneTransientState();

    try {
      await this.syncTodayPerformance();
      const positions = await this.syncOpenPositions();
      if (!this.state.running || !this.state.entryEnabled) return;

      if (this.state.dailyPnl <= this.config.dailyLossLimitKrw) {
        this.setCoreActorState('SCAN', 'WAITING', { taskSummary: '일손실 차단으로 스캔 대기' });
        this.setCoreActorState('ENTRY', 'WAITING', { taskSummary: '일손실 차단으로 진입 대기' });
        this.setBlockedReason(
          `일손실 차단 활성화 · 실현 ${this.formatKrw(this.state.dailyPnl)} / 한도 ${this.formatKrw(this.config.dailyLossLimitKrw)}`
        );
        this.setStatus('PAUSED');
        return;
      }

      if (this.performancePauseUntil && this.performancePauseUntil > Date.now()) {
        this.setCoreActorState('SCAN', 'WAITING', { taskSummary: '성과 방어모드로 스캔 대기' });
        this.setCoreActorState('ENTRY', 'WAITING', { taskSummary: '성과 방어모드로 진입 대기' });
        const remainingMinutes = Math.max(1, Math.ceil((this.performancePauseUntil - Date.now()) / 60_000));
        this.setBlockedReason(
          `성과 방어모드 · ${remainingMinutes}분 후 재개 · 최근 ${this.performanceSnapshot.recentTradeCount}건 ${this.formatKrw(this.performanceSnapshot.recentNetPnlKrw)}`
        );
        this.setStatus('PAUSED');
        return;
      }

      if (positions.length >= this.config.maxConcurrentPositions) {
        this.setCoreActorState('ENTRY', 'WAITING', { taskSummary: '포지션 한도 도달' });
        this.setBlockedReason(
          `동시 포지션 한도 도달 · ${positions.length}/${this.config.maxConcurrentPositions}`
        );
        this.setStatus('PAUSED');
        return;
      }

      this.setBlockedReason(null);
      this.state.lastScanAt = Date.now();
      this.setStatus('SCANNING');
      this.setCoreActorState('SCAN', 'BUSY', { taskSummary: `유동성 상위 ${this.config.universeLimit}개 시장 스캔` });
      this.pushEvent('SCAN', `유동성 상위 ${this.config.universeLimit}개 시장 스캔`);

      const scan = await guidedTradingApi.getAiScalpScan('minute1', this.config.universeLimit, this.config.strategyCode);
      if (!this.state.running) return;
      this.setCoreActorState('SCAN', 'SUCCESS', {
        taskSummary: `스캔 완료 · ${scan.markets.length}개 시장`,
      }, { transient: true });

      this.state.scanCycles += 1;
      this.state.lastScanAt = Date.now();
      this.state.positions = this.filterAiPositions(scan.positions).map((position) => this.normalizePosition(position));
      this.emit();

      const openMarkets = new Set(this.state.positions.map((position) => position.market));
      const candidates = scan.markets.filter((market) => !openMarkets.has(market.market) && !this.isCoolingDown(market.market));
      if (candidates.length === 0) {
        this.state.queue = [];
        this.emit();
        this.setCoreActorState('ENTRY', 'WAITING', { taskSummary: '진입 가능한 후보 없음' });
        this.pushEvent('STATUS', '현재 진입 가능한 후보가 없음');
        return;
      }

      this.setStatus('RANKING');
      this.setCoreActorState('RANK', 'BUSY', { taskSummary: `${candidates.length}개 후보 shortlist 정리` });
      const ranked = await this.rankMarkets(candidates);
      this.state.queue = ranked;
      this.emit();
      this.setCoreActorState('RANK', 'SUCCESS', {
        taskSummary: `shortlist ${ranked.length}개`,
      }, { transient: true });
      this.pushEvent(
        'RANK',
        `LLM shortlist ${ranked.length}개`,
        ranked
          .slice(0, 3)
          .map((item) => `${item.market.replace('KRW-', '')} ${item.score.toFixed(0)}`)
          .join(' · ')
      );

      const aggressionProfile = this.getEntryAggressionProfile();
      const finalists = ranked.slice(0, aggressionProfile.finalistLimit);
      if (finalists.length === 0) {
        this.setCoreActorState('ENTRY', 'WAITING', { taskSummary: '최종 검토 후보 없음' });
        return;
      }

      this.setStatus('ANALYZING');
      this.setCoreActorState('ENTRY', 'BUSY', { taskSummary: `최종 ${finalists.length}개 진입 검토` });
      const activeMarkets = new Set(this.state.positions.map((position) => position.market));
      let activePositionCount = this.state.positions.length;

      for (const finalist of finalists) {
        if (!this.state.running || !this.state.entryEnabled || activePositionCount >= this.config.maxConcurrentPositions) break;
        if (activeMarkets.has(finalist.market)) continue;
        const scanMarket = candidates.find((candidate) => candidate.market === finalist.market);
        if (!scanMarket) continue;
        const tempoProfile = this.getTempoProfile(scanMarket.market, scanMarket.koreanName);

        const entryGateFailure = this.evaluateEntryGate(scanMarket, tempoProfile, aggressionProfile);
        if (entryGateFailure) {
          this.pushEvent(
            'BUY_DECISION',
            `${finalist.market} WAIT`,
            `${aggressionProfile.label} 진입 · ${tempoProfile.label} 템포 · 리스크 필터: ${entryGateFailure}`,
            finalist.market,
            0.99,
            'LOW'
          );
          continue;
        }

        this.state.finalistsReviewed += 1;
        this.emit();

        let context: GuidedAgentContextResponse;
        try {
          context = await guidedTradingApi.getAgentContext(
            finalist.market,
            'minute1',
            60,
            20,
            'SCALP',
            'CROWD_PRESSURE'
          );
        } catch (error) {
          this.pushEvent('ERROR', `${finalist.market} 진입 컨텍스트 조회 실패`, this.toErrorText(error), finalist.market);
          continue;
        }

        const decision = await this.requestEntryDecision(scanMarket, finalist, context, tempoProfile, aggressionProfile);
        if (!decision) continue;

        this.pushEvent(
          'BUY_DECISION',
          `${finalist.market} ${decision.action}`,
          decision.reasoning,
          finalist.market,
          decision.confidence,
          decision.urgency
        );

        if (decision.action !== 'BUY' || decision.confidence < aggressionProfile.minBuyConfidence) {
          continue;
        }

        const protectivePrices = this.resolveProtectivePrices(scanMarket, decision, tempoProfile);

        try {
          this.setStatus('EXECUTING');
          this.setCoreActorState('EXECUTION', 'BUSY', { market: finalist.market, taskSummary: '시장가 매수 실행' });
          const position = await guidedTradingApi.start({
            market: finalist.market,
            amountKrw: this.config.amountKrw,
            orderType: 'MARKET',
            interval: 'minute1',
            mode: 'SCALP',
            entrySource: 'AI_SCALP',
            strategyCode: normalizeStrategyPrefix(this.config.strategyCode),
            stopLossPrice: protectivePrices.stopLoss,
            takeProfitPrice: protectivePrices.takeProfit,
            maxDcaCount: 0,
          });

          this.state.buyExecutions += 1;
          activePositionCount += 1;
          activeMarkets.add(position.market);
          this.upsertPosition(position);
          this.contextFailureCountByMarket.set(position.market, 0);
          this.setCoreActorState('EXECUTION', 'SUCCESS', {
            market: position.market,
            taskSummary: '매수 체결 완료',
          }, { transient: true });
          this.setCoreActorState('ENTRY', 'SUCCESS', {
            market: position.market,
            taskSummary: 'BUY 결정 승인',
          }, { transient: true });
          this.pushEvent(
            'BUY_EXECUTION',
            `${position.market} 매수 실행`,
            `stop ${this.formatPrice(protectivePrices.stopLoss)} · take ${this.formatPrice(protectivePrices.takeProfit)} · ${decision.reasoning}`,
            position.market,
            decision.confidence,
            decision.urgency
          );
        } catch (error) {
          this.setCoreActorState('EXECUTION', 'ERROR', {
            market: finalist.market,
            taskSummary: this.toErrorText(error),
          }, { transient: true });
          this.pushEvent('ERROR', `${finalist.market} 매수 실패`, this.toErrorText(error), finalist.market);
        }
      }
      this.setCoreActorState('ENTRY', 'WAITING', { taskSummary: '다음 스캔 대기' });
    } catch (error) {
      this.setCoreActorState('SCAN', 'ERROR', { taskSummary: this.toErrorText(error) }, { transient: true });
      this.pushEvent('ERROR', '스캔 루프 실패', this.toErrorText(error));
    } finally {
      this.scanInFlight = false;
      this.setIdleIfReady();
    }
  }

  private async runPositionManagement() {
    if (!this.state.running || this.manageInFlight) return;
    this.manageInFlight = true;
    this.pruneTransientState();

    try {
      this.setCoreActorState('MANAGE', 'BUSY', { taskSummary: '보유 포지션 점검' });
      const positions = await this.syncOpenPositions();
      if (!this.state.running) return;
      if (positions.length === 0) {
        this.setCoreActorState('MANAGE', 'WAITING', { taskSummary: '보유 포지션 없음' });
        this.completeStopWhenFlat();
        return;
      }

      for (const position of positions) {
        if (!this.state.running) break;
        const tempoProfile = this.getTempoProfile(position.market);

        const holdingMinutes = this.getHoldingMinutes(position);
        const effectiveMaxHoldingMinutes = this.getEffectiveMaxHoldingMinutes(tempoProfile);
        if (holdingMinutes >= effectiveMaxHoldingMinutes) {
          await this.executeSell(
            position,
            `최대 보유시간 ${effectiveMaxHoldingMinutes}분 도달`,
            'FORCED_EXIT',
            undefined,
            'AI_TIME_STOP'
          );
          continue;
        }

        const deterministicExit = this.evaluateDeterministicExit(position, holdingMinutes, tempoProfile);
        if (deterministicExit) {
          await this.executeSell(
            position,
            deterministicExit.reason,
            deterministicExit.category,
            {
              confidence: deterministicExit.confidence,
              urgency: deterministicExit.urgency,
            },
            deterministicExit.exitReasonCode
          );
          continue;
        }

        let context: GuidedAgentContextResponse;
        try {
          context = await guidedTradingApi.getAgentContext(
            position.market,
            'minute1',
            30,
            20,
            'SCALP',
            'CROWD_PRESSURE'
          );
          this.contextFailureCountByMarket.set(position.market, 0);
        } catch (error) {
          this.setCoreActorState('MANAGE', 'ERROR', {
            market: position.market,
            taskSummary: `컨텍스트 실패 ${this.toErrorText(error)}`,
          }, { transient: true });
          const failureCount = (this.contextFailureCountByMarket.get(position.market) ?? 0) + 1;
          this.contextFailureCountByMarket.set(position.market, failureCount);
          this.pushEvent(
            'ERROR',
            `${position.market} 관리 컨텍스트 실패 (${failureCount}/${CONTEXT_FAILURE_EXIT_THRESHOLD})`,
            this.toErrorText(error),
            position.market
          );
          if (failureCount >= CONTEXT_FAILURE_EXIT_THRESHOLD) {
            await this.executeSell(position, '컨텍스트 조회 실패 누적', 'FORCED_EXIT', undefined, 'AI_CONTEXT_FAIL');
          }
          continue;
        }

        const decision = await this.requestManageDecision(position, context, tempoProfile, effectiveMaxHoldingMinutes);
        if (!decision) continue;

        this.pushEvent(
          'MANAGE_DECISION',
          `${position.market} ${decision.action}`,
          `${decision.reasoning} · 보유 ${holdingMinutes.toFixed(1)}분 · 미실현 ${this.formatPercent(position.unrealizedPnlPercent)}`,
          position.market,
          decision.confidence,
          decision.urgency
        );

        if (decision.action === 'SELL' && this.shouldDeferEarlySell(position, holdingMinutes, decision, tempoProfile)) {
          this.pushEvent(
            'STATUS',
            `${position.market} 초기 흔들림으로 HOLD 유지`,
            `보유 ${holdingMinutes.toFixed(1)}분 · 미실현 ${this.formatPercent(position.unrealizedPnlPercent)} · ${decision.reasoning}`,
            position.market,
            decision.confidence,
            decision.urgency
          );
          continue;
        }

        if (decision.action === 'SELL' && decision.confidence >= 0.58) {
          await this.executeSell(position, decision.reasoning, undefined, decision, 'AI_LLM_EXIT');
        }
      }

      await this.syncOpenPositions();
      this.setCoreActorState('MANAGE', 'SUCCESS', {
        taskSummary: `관리 루프 완료 · ${positions.length}개 포지션`,
      }, { transient: true });
      this.completeStopWhenFlat();
    } catch (error) {
      this.setCoreActorState('MANAGE', 'ERROR', { taskSummary: this.toErrorText(error) }, { transient: true });
      this.pushEvent('ERROR', '포지션 관리 루프 실패', this.toErrorText(error));
    } finally {
      this.manageInFlight = false;
      this.setIdleIfReady();
    }
  }

  private async rankMarkets(markets: AiScalpScanMarket[]): Promise<AiRankedOpportunity[]> {
    const normalizedMarkets = markets.map((market) => ({
      market: market.market,
      koreanName: market.koreanName,
      tradePrice: market.tradePrice,
      changeRate: market.changeRate,
      turnover: market.turnover,
      liquidityRank: market.liquidityRank,
      expectancyPct: market.recommendation.expectancyPct,
      recommendedEntryWinRate: market.recommendation.recommendedEntryWinRate,
      marketEntryWinRate: market.recommendation.marketEntryWinRate,
      riskRewardRatio: market.recommendation.riskRewardRatio,
      entryGapPct: market.featurePack.entryGapPct,
      confidence: market.featurePack.confidence,
      flowScore: market.crowd?.flowScore ?? market.featurePack.crowdFlowScore ?? 0,
      spreadPercent: market.crowd?.spreadPercent ?? market.featurePack.spreadPercent ?? 0,
      bidImbalance: market.crowd?.bidImbalance ?? market.featurePack.bidImbalance ?? 0,
      notionalSpikeRatio: market.crowd?.notionalSpikeRatio ?? market.featurePack.notionalSpikeRatio ?? 0,
    }));
    const deterministic = this.buildDeterministicShortlist(markets);

    try {
      const toolMonitor = this.buildToolMonitorCallbacks('RANK');
      const response = await requestOneShotTextWithMeta({
        prompt: [
          SHORTLIST_SYSTEM_PROMPT,
          '',
          `후보 시장 요약(JSON): ${JSON.stringify(normalizedMarkets)}`,
        ].join('\n'),
        provider: this.config.provider,
        model: this.config.model,
        tradingMode: 'SCALP',
        zaiEndpointMode: this.config.zaiEndpointMode,
        delegationMode: this.config.delegationMode,
        zaiDelegateModel: this.config.zaiDelegateModel,
        onToolCall: toolMonitor.onToolCall,
        onToolResult: toolMonitor.onToolResult,
      });

      const parsed = parseJsonObject(response.text);
      const rawShortlist = Array.isArray(parsed?.shortlist) ? parsed.shortlist : [];
      const marketMap = new Map(markets.map((market) => [market.market, market]));
      const dedupe = new Set<string>();
      const shortlist: AiRankedOpportunity[] = [];

      for (const item of rawShortlist) {
        if (typeof item !== 'object' || item === null) continue;
        const shortlistItem = item as Record<string, unknown>;
        const market = normalizeMarket(shortlistItem.market);
        if (!market || dedupe.has(market)) continue;
        const scanMarket = marketMap.get(market);
        if (!scanMarket) continue;

        dedupe.add(market);
        shortlist.push({
          market,
          koreanName: scanMarket.koreanName,
          score: clamp(toFiniteNumber(shortlistItem.score, 65), 0, 100),
          reason:
            typeof shortlistItem.reason === 'string' &&
            shortlistItem.reason.trim().length > 0
              ? shortlistItem.reason.trim()
              : this.buildDeterministicReason(scanMarket),
          tradePrice: scanMarket.tradePrice,
          changeRate: scanMarket.changeRate,
          turnover: scanMarket.turnover,
          crowd: scanMarket.crowd,
        });
        if (shortlist.length >= Math.min(SHORTLIST_LIMIT, markets.length)) {
          break;
        }
      }

      if (shortlist.length > 0) {
        return shortlist;
      }
    } catch (error) {
      this.setCoreActorState('RANK', 'ERROR', { taskSummary: this.toErrorText(error) }, { transient: true });
      this.pushEvent('ERROR', 'LLM shortlist 실패, 정량 정렬로 폴백', this.toErrorText(error));
    }

    return deterministic;
  }

  private async requestEntryDecision(
    scanMarket: AiScalpScanMarket,
    finalist: AiRankedOpportunity,
    context: GuidedAgentContextResponse,
    tempoProfile: AiTempoProfile,
    aggressionProfile: AiEntryAggressionProfile
  ): Promise<AiEntryDecision | null> {
    try {
      const toolMonitor = this.buildToolMonitorCallbacks('ENTRY', scanMarket.market);
      const response = await requestOneShotTextWithMeta({
        prompt: [
          ENTRY_SYSTEM_PROMPT,
          '',
          `진입 강도: ${aggressionProfile.label}. finalist ${aggressionProfile.finalistLimit}개, BUY confidence 하한 ${Math.round(aggressionProfile.minBuyConfidence * 100)}%.`,
          `템포 프로필: ${tempoProfile.label}. ${tempoProfile.manageNote}`,
          `후보 요약: ${JSON.stringify({
            market: scanMarket.market,
            koreanName: scanMarket.koreanName,
            score: finalist.score,
            reason: finalist.reason,
            tradePrice: scanMarket.tradePrice,
            changeRate: scanMarket.changeRate,
            turnover: scanMarket.turnover,
            recommendation: scanMarket.recommendation,
            featurePack: scanMarket.featurePack,
            crowd: scanMarket.crowd,
          })}`,
        ].join('\n'),
        provider: this.config.provider,
        model: this.config.model,
        context,
        tradingMode: 'SCALP',
        zaiEndpointMode: this.config.zaiEndpointMode,
        delegationMode: this.config.delegationMode,
        zaiDelegateModel: this.config.zaiDelegateModel,
        onToolCall: toolMonitor.onToolCall,
        onToolResult: toolMonitor.onToolResult,
      });

      const parsed = parseJsonObject(response.text);
      if (!parsed) {
        this.setCoreActorState('ENTRY', 'ERROR', {
          market: scanMarket.market,
          taskSummary: '진입 응답 파싱 실패',
        }, { transient: true });
        this.pushEvent('ERROR', `${scanMarket.market} 진입 응답 파싱 실패`, response.text.slice(0, 200), scanMarket.market);
        return null;
      }

      const action = typeof parsed.action === 'string' ? parsed.action.trim().toUpperCase() : 'WAIT';
      const normalizedAction: AiEntryDecision['action'] = action === 'BUY' ? 'BUY' : 'WAIT';
      return {
        action: normalizedAction,
        confidence: clamp(toFiniteNumber(parsed.confidence, 0), 0, 1),
        reasoning:
          typeof parsed.reasoning === 'string' && parsed.reasoning.trim().length > 0
            ? parsed.reasoning.trim()
            : finalist.reason,
        stopLoss: toFiniteNumber(parsed.stopLoss, NaN),
        takeProfit: toFiniteNumber(parsed.takeProfit, NaN),
        urgency: normalizeUrgency(parsed.urgency),
      };
    } catch (error) {
      this.setCoreActorState('ENTRY', 'ERROR', {
        market: scanMarket.market,
        taskSummary: this.toErrorText(error),
      }, { transient: true });
      this.pushEvent('ERROR', `${scanMarket.market} 진입 LLM 실패`, this.toErrorText(error), scanMarket.market);
      return null;
    }
  }

  private async requestManageDecision(
    position: GuidedTradePosition,
    context: GuidedAgentContextResponse,
    tempoProfile: AiTempoProfile,
    effectiveMaxHoldingMinutes: number
  ): Promise<AiManageDecision | null> {
    try {
      const toolMonitor = this.buildToolMonitorCallbacks('MANAGE', position.market);
      const response = await requestOneShotTextWithMeta({
        prompt: [
          MANAGE_SYSTEM_PROMPT,
          '',
          `템포 프로필: ${tempoProfile.label}. ${tempoProfile.manageNote}`,
          `포지션 요약: ${JSON.stringify({
            market: position.market,
            averageEntryPrice: position.averageEntryPrice,
            currentPrice: position.currentPrice,
            stopLossPrice: position.stopLossPrice,
            takeProfitPrice: position.takeProfitPrice,
            unrealizedPnlPercent: position.unrealizedPnlPercent,
            createdAt: position.createdAt,
            strategyCode: position.strategyCode,
            effectiveMaxHoldingMinutes,
          })}`,
        ].join('\n'),
        provider: this.config.provider,
        model: this.config.model,
        context,
        tradingMode: 'SCALP',
        zaiEndpointMode: this.config.zaiEndpointMode,
        delegationMode: this.config.delegationMode,
        zaiDelegateModel: this.config.zaiDelegateModel,
        onToolCall: toolMonitor.onToolCall,
        onToolResult: toolMonitor.onToolResult,
      });

      const parsed = parseJsonObject(response.text);
      if (!parsed) {
        this.setCoreActorState('MANAGE', 'ERROR', {
          market: position.market,
          taskSummary: '관리 응답 파싱 실패',
        }, { transient: true });
        this.pushEvent('ERROR', `${position.market} 관리 응답 파싱 실패`, response.text.slice(0, 200), position.market);
        return null;
      }

      const action = typeof parsed.action === 'string' ? parsed.action.trim().toUpperCase() : 'HOLD';
      return {
        action: action === 'SELL' ? 'SELL' : 'HOLD',
        confidence: clamp(toFiniteNumber(parsed.confidence, 0), 0, 1),
        reasoning:
          typeof parsed.reasoning === 'string' && parsed.reasoning.trim().length > 0
            ? parsed.reasoning.trim()
            : '시장 흐름 유지',
        urgency: normalizeUrgency(parsed.urgency),
      };
    } catch (error) {
      this.setCoreActorState('MANAGE', 'ERROR', {
        market: position.market,
        taskSummary: this.toErrorText(error),
      }, { transient: true });
      this.pushEvent('ERROR', `${position.market} 관리 LLM 실패`, this.toErrorText(error), position.market);
      return null;
    }
  }

  private resolveProtectivePrices(
    scanMarket: AiScalpScanMarket,
    decision: AiEntryDecision,
    tempoProfile: AiTempoProfile
  ) {
    const currentPrice = scanMarket.tradePrice > 0 ? scanMarket.tradePrice : scanMarket.recommendation.currentPrice;
    const spreadPercent = scanMarket.crowd?.spreadPercent ?? scanMarket.featurePack.spreadPercent ?? 0;
    const entryGapPct = Math.max(0, scanMarket.featurePack.entryGapPct ?? 0);
    const recommendationStopDistance = this.calculateDownsideDistancePercent(currentPrice, scanMarket.recommendation.stopLossPrice);
    const llmStopDistance = this.calculateDownsideDistancePercent(currentPrice, decision.stopLoss);
    const rawStopDistance = Math.max(
      recommendationStopDistance,
      llmStopDistance,
      tempoProfile.minStopDistancePct,
      spreadPercent * 4.5,
      Math.min(entryGapPct, 0.35) * 1.2
    );
    const stopDistancePercent = clamp(rawStopDistance, tempoProfile.minStopDistancePct, tempoProfile.maxStopDistancePct);

    const recommendationTakeDistance = this.calculateUpsideDistancePercent(currentPrice, scanMarket.recommendation.takeProfitPrice);
    const llmTakeDistance = this.calculateUpsideDistancePercent(currentPrice, decision.takeProfit);
    const rawTakeDistance = Math.max(
      recommendationTakeDistance,
      llmTakeDistance,
      tempoProfile.minTakeDistancePct,
      spreadPercent * 8,
      stopDistancePercent * Math.max(scanMarket.recommendation.riskRewardRatio || 0, tempoProfile.minTargetRiskReward)
    );
    const minTakeDistance = Math.max(tempoProfile.minTakeDistancePct, stopDistancePercent * tempoProfile.minTargetRiskReward);
    const takeDistancePercent = clamp(rawTakeDistance, minTakeDistance, tempoProfile.maxTakeDistancePct);

    const stopLoss = currentPrice * (1 - stopDistancePercent / 100);
    const takeProfit = currentPrice * (1 + takeDistancePercent / 100);

    return {
      stopLoss,
      takeProfit,
    };
  }

  private buildDeterministicShortlist(markets: AiScalpScanMarket[]): AiRankedOpportunity[] {
    return [...markets]
      .sort((left, right) => {
        const expectancyGap = right.recommendation.expectancyPct - left.recommendation.expectancyPct;
        if (Math.abs(expectancyGap) > 1e-9) return expectancyGap;
        const flowGap =
          (right.crowd?.flowScore ?? right.featurePack.crowdFlowScore ?? -Infinity) -
          (left.crowd?.flowScore ?? left.featurePack.crowdFlowScore ?? -Infinity);
        if (Math.abs(flowGap) > 1e-9) return flowGap;
        return right.turnover - left.turnover;
      })
      .slice(0, Math.min(SHORTLIST_LIMIT, markets.length))
      .map((market, index) => {
        const crowdFlow = market.crowd?.flowScore ?? market.featurePack.crowdFlowScore ?? 0;
        const score = clamp(
          62 +
            market.recommendation.expectancyPct * 120 +
            crowdFlow * 0.18 +
            market.recommendation.riskRewardRatio * 4 -
            market.featurePack.entryGapPct * 3 -
            index * 1.5,
          0,
          100
        );
        return {
          market: market.market,
          koreanName: market.koreanName,
          score,
          reason: this.buildDeterministicReason(market),
          tradePrice: market.tradePrice,
          changeRate: market.changeRate,
          turnover: market.turnover,
          crowd: market.crowd,
        };
      });
  }

  private buildDeterministicReason(market: AiScalpScanMarket): string {
    const parts = [
      `exp ${market.recommendation.expectancyPct.toFixed(2)}%`,
      `RR ${market.recommendation.riskRewardRatio.toFixed(2)}`,
      `spread ${(market.crowd?.spreadPercent ?? market.featurePack.spreadPercent ?? 0).toFixed(2)}%`,
    ];
    const flowScore = market.crowd?.flowScore ?? market.featurePack.crowdFlowScore;
    if (typeof flowScore === 'number' && Number.isFinite(flowScore)) {
      parts.push(`flow ${flowScore.toFixed(1)}`);
    }
    return parts.join(' · ');
  }

  private async executeSell(
    position: GuidedTradePosition,
    reason: string,
    forcedCategory?: AiExitCategory,
    decision?: Pick<AiManageDecision, 'confidence' | 'urgency'>,
    exitReasonCode?: string
  ) {
    try {
      this.setStatus('EXECUTING');
      this.setCoreActorState('EXECUTION', 'BUSY', { market: position.market, taskSummary: reason });
      const result = await guidedTradingApi.stop(position.market, exitReasonCode ?? this.resolveServerExitReason(reason, forcedCategory));
      const closed =
        result.status === 'CLOSED' ||
        Boolean(result.closedAt) ||
        (typeof result.remainingQuantity === 'number' && result.remainingQuantity <= 0);
      const pnlKrw = toFiniteNumber(result.realizedPnl, 0);
      const pnlPercent = toFiniteNumber(result.realizedPnlPercent, 0);
      const exitCategory = this.resolveExitCategory(reason, pnlKrw, pnlPercent, forcedCategory);

      this.pushEvent(
        'SELL_EXECUTION',
        `${position.market} 매도 실행`,
        `${reason} · 실현 ${this.formatKrw(pnlKrw)} / ${this.formatPercent(pnlPercent)}`,
        position.market,
        decision?.confidence,
        decision?.urgency
      );

      if (closed) {
        this.removePosition(position.market);
        this.registerClosedTrade({
          market: position.market,
          pnlKrw,
          pnlPercent,
          holdingMinutes: this.getHoldingMinutes(result),
          exitCategory,
          closedAt: result.closedAt ?? new Date().toISOString(),
        });
        const cooldownMs = this.resolveCooldownMs(exitCategory);
        this.cooldownUntilByMarket.set(position.market, Date.now() + cooldownMs);
        this.contextFailureCountByMarket.delete(position.market);
        this.setCoreActorState('EXECUTION', 'SUCCESS', {
          market: position.market,
          taskSummary: `${closed ? '매도 체결 완료' : '포지션 동기화'}`,
        }, { transient: true });
      } else {
        this.upsertPosition(result);
        this.setCoreActorState('EXECUTION', 'SUCCESS', {
          market: position.market,
          taskSummary: '부분 청산/동기화',
        }, { transient: true });
      }
    } catch (error) {
      this.setCoreActorState('EXECUTION', 'ERROR', {
        market: position.market,
        taskSummary: this.toErrorText(error),
      }, { transient: true });
      this.pushEvent('ERROR', `${position.market} 매도 실패`, this.toErrorText(error), position.market);
    }
  }

  private resolveExitCategory(
    reason: string,
    pnlKrw: number,
    pnlPercent: number,
    forcedCategory?: AiExitCategory
  ): AiExitCategory {
    if (forcedCategory) return forcedCategory;
    if (/보유시간|컨텍스트|실패|max hold|failure/i.test(reason)) return 'FORCED_EXIT';
    if (pnlKrw > 0 || pnlPercent > 0) return 'TAKE_PROFIT';
    if (pnlKrw < 0 || pnlPercent < 0) return 'STOP_LOSS';
    return 'LLM_EXIT';
  }

  private resolveCooldownMs(exitCategory: AiExitCategory): number {
    switch (exitCategory) {
      case 'TAKE_PROFIT':
        return TAKE_PROFIT_COOLDOWN_MS;
      case 'LLM_EXIT':
        return LLM_EXIT_COOLDOWN_MS;
      case 'FORCED_EXIT':
        return FORCED_EXIT_COOLDOWN_MS;
      case 'STOP_LOSS':
      default:
        return STOP_LOSS_COOLDOWN_MS;
    }
  }

  private resolveServerExitReason(reason: string, forcedCategory?: AiExitCategory): string {
    const explicitCode = reason.trim().match(/AI_[A-Z0-9_]+/)?.[0];
    if (explicitCode) return explicitCode;
    if (/최대 보유시간|TIME STOP/i.test(reason)) return 'AI_TIME_STOP';
    if (/컨텍스트|context/i.test(reason)) return 'AI_CONTEXT_FAIL';
    switch (forcedCategory) {
      case 'TAKE_PROFIT':
        return 'AI_PROFIT_EXIT';
      case 'STOP_LOSS':
        return 'AI_STOP_LOSS';
      case 'FORCED_EXIT':
        return 'AI_FORCED_EXIT';
      case 'LLM_EXIT':
      default:
        return 'AI_LLM_EXIT';
    }
  }

  private evaluateEntryGate(
    scanMarket: AiScalpScanMarket,
    tempoProfile: AiTempoProfile,
    aggressionProfile: AiEntryAggressionProfile
  ): string | null {
    return evaluatePolicyEntryGate(scanMarket, tempoProfile, aggressionProfile);
  }

  private shouldDeferEarlySell(
    position: GuidedTradePosition,
    holdingMinutes: number,
    decision: AiManageDecision,
    tempoProfile: AiTempoProfile
  ): boolean {
    return (
      holdingMinutes < tempoProfile.earlyHoldMinutes &&
      decision.confidence < 0.72 &&
      decision.urgency !== 'HIGH' &&
      position.unrealizedPnlPercent > tempoProfile.earlyExitPnlFloor
    );
  }

  private getEntryAggressionProfile(): AiEntryAggressionProfile {
    return buildPolicyEntryAggressionProfile(this.getSelectedEntryAggression(), this.performanceSnapshot.regime);
  }

  private getTempoProfile(market: string, koreanName?: string | null): AiTempoProfile {
    return getPolicyTempoProfile(market, koreanName);
  }

  private getEffectiveMaxHoldingMinutes(tempoProfile: AiTempoProfile): number {
    return getPolicyEffectiveMaxHoldingMinutes(this.config.maxHoldingMinutes, tempoProfile);
  }

  private calculateDownsideDistancePercent(referencePrice: number, candidatePrice?: number): number {
    if (!Number.isFinite(referencePrice) || referencePrice <= 0 || !Number.isFinite(candidatePrice) || (candidatePrice as number) <= 0) {
      return 0;
    }
    return Math.max(0, ((referencePrice - (candidatePrice as number)) / referencePrice) * 100);
  }

  private calculateUpsideDistancePercent(referencePrice: number, candidatePrice?: number): number {
    if (!Number.isFinite(referencePrice) || referencePrice <= 0 || !Number.isFinite(candidatePrice) || (candidatePrice as number) <= 0) {
      return 0;
    }
    return Math.max(0, (((candidatePrice as number) - referencePrice) / referencePrice) * 100);
  }

  private normalizePosition(position: GuidedTradePosition): GuidedTradePosition {
    return normalizeAiPosition(position);
  }

  private async syncTodayPerformance(force = false) {
    if (!force && Date.now() - this.lastTodayStatsSyncAt < TODAY_STATS_SYNC_MS) {
      return;
    }
    this.lastTodayStatsSyncAt = Date.now();

    try {
      const stats = await guidedTradingApi.getTodayStats(normalizeStrategyPrefix(this.config.strategyCode));
      const trades = [...stats.trades].sort((left, right) => {
        const rightTime = new Date(right.closedAt ?? right.createdAt).getTime();
        const leftTime = new Date(left.closedAt ?? left.createdAt).getTime();
        return rightTime - leftTime;
      });
      const previousSnapshot = this.performanceSnapshot;
      this.state.closedTrades = trades.map((trade) => this.mapClosedTradeView(trade));
      this.recalculateClosedTradeStats();
      this.performanceSnapshot = this.buildPerformanceSnapshot(trades);
      if (
        previousSnapshot.regime !== this.performanceSnapshot.regime ||
        previousSnapshot.effectiveAggression !== this.performanceSnapshot.effectiveAggression
      ) {
        this.pushEvent(
          'STATUS',
          `성과 모드 ${this.performanceSnapshot.regime}`,
          `선택 ${this.performanceSnapshot.selectedAggression} -> 적용 ${this.performanceSnapshot.effectiveAggression} · 최근 ${this.performanceSnapshot.recentTradeCount}건 평균 ${this.formatPercent(this.performanceSnapshot.recentAvgPnlPercent)}`
        );
      }
      this.applyPerformanceMarketLocks(trades);
      this.applyPerformancePause(trades);
      this.todayStatsSyncFailed = false;
      this.emit();
    } catch (error) {
      if (!this.todayStatsSyncFailed) {
        this.pushEvent('ERROR', '오늘 거래 통계 동기화 실패', this.toErrorText(error));
      }
      this.todayStatsSyncFailed = true;
    }
  }

  private mapClosedTradeView(trade: GuidedClosedTradeView): AiClosedTrade {
    return {
      market: trade.market,
      pnlKrw: trade.realizedPnl,
      pnlPercent: trade.realizedPnlPercent,
      holdingMinutes: this.getHoldingMinutes({
        createdAt: trade.createdAt,
        closedAt: trade.closedAt ?? trade.createdAt,
      }),
      exitCategory: this.mapExitReasonToCategory(trade.exitReason, trade.realizedPnlPercent),
      closedAt: trade.closedAt ?? trade.createdAt,
    };
  }

  private mapExitReasonToCategory(exitReason?: string | null, pnlPercent = 0): AiExitCategory {
    const normalized = exitReason?.trim().toUpperCase() ?? '';
    if (normalized.includes('PROFIT') || normalized.includes('TAKE') || normalized.includes('TP')) {
      return 'TAKE_PROFIT';
    }
    if (
      normalized.includes('STOP_LOSS') ||
      normalized.includes('LOSS_CUT') ||
      normalized === 'STOP_LOSS' ||
      normalized === 'AI_STOP_LOSS'
    ) {
      return 'STOP_LOSS';
    }
    if (normalized.includes('TIME_STOP') || normalized.includes('CONTEXT_FAIL') || normalized.includes('FORCED')) {
      return 'FORCED_EXIT';
    }
    if (pnlPercent > 0) return 'TAKE_PROFIT';
    if (pnlPercent < 0) return 'LLM_EXIT';
    return 'FORCED_EXIT';
  }

  private buildPerformanceSnapshot(trades: GuidedClosedTradeView[]): AiPerformanceSnapshot {
    return buildPolicyPerformanceSnapshot(trades, this.getSelectedEntryAggression());
  }

  private getSelectedEntryAggression(): AiEntryAggression {
    return this.config.entryAggression ?? 'BALANCED';
  }

  private applyPerformancePause(trades: GuidedClosedTradeView[]) {
    if (trades.length === 0) {
      this.performancePauseUntil = null;
      return;
    }
    const latestTradeId = trades[0]?.tradeId ?? null;
    if (this.performanceSnapshot.regime !== 'DEFENSIVE' || latestTradeId === null) {
      if (this.performancePauseUntil && this.performancePauseUntil <= Date.now()) {
        this.performancePauseUntil = null;
      }
      return;
    }
    if (this.performancePauseTradeId === latestTradeId) {
      return;
    }
    this.performancePauseTradeId = latestTradeId;
    this.performancePauseUntil = Date.now() + PERFORMANCE_GUARD_PAUSE_MS;
    this.pushEvent(
      'STATUS',
      `성과 방어모드 ${Math.round(PERFORMANCE_GUARD_PAUSE_MS / 60_000)}분`,
      `최근 ${this.performanceSnapshot.recentTradeCount}건 ${this.formatKrw(this.performanceSnapshot.recentNetPnlKrw)} / 평균 ${this.formatPercent(this.performanceSnapshot.recentAvgPnlPercent)} / 연속 손실 ${this.performanceSnapshot.consecutiveLosses}`
    );
  }

  private applyPerformanceMarketLocks(trades: GuidedClosedTradeView[]) {
    const grouped = new Map<string, GuidedClosedTradeView[]>();
    for (const trade of trades) {
      const current = grouped.get(trade.market) ?? [];
      current.push(trade);
      grouped.set(trade.market, current);
    }

    for (const [market, marketTrades] of grouped.entries()) {
      const recentTrades = marketTrades
        .sort((left, right) => new Date(right.closedAt ?? right.createdAt).getTime() - new Date(left.closedAt ?? left.createdAt).getTime())
        .slice(0, 3);
      const consecutiveLosses = recentTrades.findIndex((trade) => trade.realizedPnl >= 0);
      const leadingLosses = consecutiveLosses === -1 ? recentTrades.length : consecutiveLosses;
      const totalPnlPercent = recentTrades.reduce((sum, trade) => sum + trade.realizedPnlPercent, 0);

      let lockDuration = 0;
      let lockReason = '';
      if (leadingLosses >= 2) {
        lockDuration = leadingLosses >= 3 ? PERFORMANCE_MARKET_DEEP_LOCK_MS : PERFORMANCE_MARKET_LOCK_MS;
        lockReason = `최근 ${leadingLosses}연속 손실`;
      } else if (recentTrades.length >= 3 && totalPnlPercent <= -0.60) {
        lockDuration = PERFORMANCE_MARKET_LOCK_MS;
        lockReason = `최근 3건 ${totalPnlPercent.toFixed(2)}%`;
      }

      if (lockDuration <= 0) continue;

      const nextUntil = Date.now() + lockDuration;
      const currentUntil = this.cooldownUntilByMarket.get(market) ?? 0;
      if (currentUntil >= nextUntil) continue;
      this.cooldownUntilByMarket.set(market, nextUntil);
      this.pushEvent(
        'STATUS',
        `${market} 재진입 락`,
        `${lockReason} · ${Math.round(lockDuration / 60_000)}분 차단`,
        market
      );
    }
  }

  private evaluateDeterministicExit(
    position: GuidedTradePosition,
    holdingMinutes: number,
    tempoProfile: AiTempoProfile
  ): AiDeterministicExitDecision | null {
    return evaluatePolicyDeterministicExit(
      position,
      holdingMinutes,
      tempoProfile,
      this.peakNetPnlByTradeId.get(position.tradeId) ?? position.unrealizedPnlPercent
    );
  }

  private registerClosedTrade(trade: AiClosedTrade) {
    this.state.closedTrades = [trade, ...this.state.closedTrades].slice(0, CLOSED_TRADE_LIMIT);
    this.recalculateClosedTradeStats();
    this.emit();
  }

  private recalculateClosedTradeStats() {
    this.state.dailyPnl = this.state.closedTrades.reduce((sum, trade) => sum + trade.pnlKrw, 0);
    this.state.wins = this.state.closedTrades.filter((trade) => trade.pnlKrw > 0 || trade.pnlPercent > 0).length;
    this.state.losses = this.state.closedTrades.filter((trade) => trade.pnlKrw < 0 || trade.pnlPercent < 0).length;
  }

  private upsertPosition(position: GuidedTradePosition) {
    const normalizedPosition = this.normalizePosition(position);
    const previousPeak = this.peakNetPnlByTradeId.get(normalizedPosition.tradeId) ?? normalizedPosition.unrealizedPnlPercent;
    this.peakNetPnlByTradeId.set(
      normalizedPosition.tradeId,
      Math.max(previousPeak, normalizedPosition.unrealizedPnlPercent)
    );
    const next = [...this.state.positions];
    const index = next.findIndex((item) => item.tradeId === normalizedPosition.tradeId || item.market === normalizedPosition.market);
    if (index >= 0) {
      next[index] = normalizedPosition;
    } else {
      next.unshift(normalizedPosition);
    }
    this.state.positions = next;
    this.emit();
  }

  private removePosition(market: string) {
    const removedTradeIds = this.state.positions
      .filter((position) => position.market === market)
      .map((position) => position.tradeId);
    for (const tradeId of removedTradeIds) {
      this.peakNetPnlByTradeId.delete(tradeId);
    }
    this.state.positions = this.state.positions.filter((position) => position.market !== market);
    this.emit();
  }

  private completeStopWhenFlat(): boolean {
    if (!this.state.running || this.state.entryEnabled || this.state.positions.length > 0) {
      return false;
    }
    if (this.scanTimer !== null) {
      window.clearInterval(this.scanTimer);
      this.scanTimer = null;
    }
    if (this.manageTimer !== null) {
      window.clearInterval(this.manageTimer);
      this.manageTimer = null;
    }
    this.state.running = false;
    this.state.status = 'IDLE';
    this.state.blockedReason = null;
    this.resetCoreActors();
    this.emit();
    this.pushEvent('STATUS', '보유 포지션 정리 완료 · 엔진 완전 중지');
    return true;
  }

  private isCoolingDown(market: string): boolean {
    const until = this.cooldownUntilByMarket.get(market);
    return typeof until === 'number' && until > Date.now();
  }

  private getHoldingMinutes(position: Pick<GuidedTradePosition, 'createdAt'> & { closedAt?: string | null }): number {
    const createdAt = new Date(position.createdAt).getTime();
    const endedAt = position.closedAt ? new Date(position.closedAt).getTime() : Date.now();
    if (!Number.isFinite(createdAt)) return 0;
    return Math.max(0, (endedAt - createdAt) / 60_000);
  }

  private formatKrw(value: number): string {
    const sign = value > 0 ? '+' : '';
    return `${sign}${Math.round(value).toLocaleString()}원`;
  }

  private formatPercent(value: number): string {
    const sign = value > 0 ? '+' : '';
    return `${sign}${value.toFixed(2)}%`;
  }

  private formatPrice(value: number): string {
    if (!Number.isFinite(value)) return '-';
    return value.toLocaleString('ko-KR', { maximumFractionDigits: 4 });
  }

  private toErrorText(error: unknown): string {
    if (error instanceof Error && error.message) return error.message;
    return String(error);
  }
}
