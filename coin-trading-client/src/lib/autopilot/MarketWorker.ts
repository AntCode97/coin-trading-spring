import type { GuidedAgentContextResponse, GuidedTradePosition } from '../../api';

export type WorkerStatus =
  | 'SCANNING'
  | 'ANALYZING'
  | 'PLAYWRIGHT_CHECK'
  | 'ENTERING'
  | 'MANAGING'
  | 'PAUSED'
  | 'COOLDOWN'
  | 'ERROR'
  | 'STOPPED';

export interface WorkerStateSnapshot {
  market: string;
  status: WorkerStatus;
  note: string;
  startedAt: number;
  updatedAt: number;
  cooldownUntil: number | null;
}

export type WorkerEventType =
  | 'STATE'
  | 'LLM_REJECT'
  | 'PLAYWRIGHT_WARN'
  | 'ENTRY_SUCCESS'
  | 'ENTRY_FAILED'
  | 'POSITION_EXIT'
  | 'PARTIAL_TP'
  | 'SUPERVISION';

export interface WorkerEvent {
  market: string;
  type: WorkerEventType;
  level: 'INFO' | 'WARN' | 'ERROR';
  message: string;
  at: number;
}

export type WorkerOrderFlowType =
  | 'BUY_REQUESTED'
  | 'BUY_FILLED'
  | 'SELL_REQUESTED'
  | 'SELL_FILLED'
  | 'CANCELLED';

export interface WorkerOrderFlowEvent {
  market: string;
  type: WorkerOrderFlowType;
  message: string;
  at: number;
}

export interface WorkerConfig {
  market: string;
  tickMs: number;
  rejectCooldownMs: number;
  postExitCooldownMs: number;
  llmReviewMs: number;
  minLlmConfidence: number;
  enablePlaywrightCheck: boolean;
  entryPolicy: 'BALANCED' | 'AGGRESSIVE' | 'CONSERVATIVE';
  entryOrderMode: 'ADAPTIVE' | 'MARKET' | 'LIMIT';
  pendingEntryTimeoutMs: number;
  marketFallbackAfterCancel: boolean;
  skipLlmEntryReview: boolean;
}

interface WorkerEntryDecision {
  approve: boolean;
  confidence: number;
  reason: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH';
  riskTags?: string[];
  suggestedCooldownSec?: number | null;
}

interface WorkerEntryPlan {
  allow: boolean;
  reason: string;
  orderType: 'MARKET' | 'LIMIT';
  limitPrice?: number;
  entryGapPct: number;
}

export interface WorkerDependencies {
  fetchContext: (market: string) => Promise<GuidedAgentContextResponse>;
  evaluateEntry: (
    market: string,
    context: GuidedAgentContextResponse
  ) => Promise<WorkerEntryDecision>;
  planEntryOrder: (
    market: string,
    context: GuidedAgentContextResponse
  ) => Promise<WorkerEntryPlan>;
  verifyWithPlaywright: (market: string) => Promise<{ ok: boolean; note: string }>;
  startGuidedEntry: (market: string, plan?: { orderType: 'MARKET' | 'LIMIT'; limitPrice?: number }) => Promise<void>;
  fallbackEntryByMcp: (market: string) => Promise<void>;
  cancelPendingEntry: (market: string) => Promise<void>;
  getPosition: (market: string) => Promise<GuidedTradePosition | null>;
  stopPosition: (market: string, reason: string) => Promise<void>;
  partialTakeProfit: (market: string, ratio: number, reason: string) => Promise<void>;
  reviewOpenPosition?: (
    market: string,
    position: GuidedTradePosition
  ) => Promise<{ action: 'HOLD' | 'PARTIAL_TP' | 'FULL_EXIT'; reason: string }>;
  onState: (snapshot: WorkerStateSnapshot) => void;
  onLog: (message: string) => void;
  onEvent?: (event: WorkerEvent) => void;
  onOrderFlow?: (event: WorkerOrderFlowEvent) => void;
}

export class MarketWorker {
  private readonly config: WorkerConfig;
  private readonly deps: WorkerDependencies;
  private readonly startedAt = Date.now();
  private status: WorkerStatus = 'SCANNING';
  private note = '대기';
  private cooldownUntil: number | null = null;
  private pausedUntil: number | null = null;
  private timerId: number | null = null;
  private running = false;
  private ticking = false;
  private lastEventReviewAt = 0;
  private peakPnlPercent = Number.NEGATIVE_INFINITY;
  private hadOpenPosition = false;
  private pendingEntryObservedAt: number | null = null;
  private pendingFallbackTried = false;

  constructor(config: WorkerConfig, deps: WorkerDependencies) {
    this.config = config;
    this.deps = deps;
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.setState('SCANNING', '워커 시작');
    void this.tick();
    this.timerId = window.setInterval(() => void this.tick(), this.config.tickMs);
  }

  stop(reason = '워커 중지'): void {
    this.running = false;
    if (this.timerId != null) {
      window.clearInterval(this.timerId);
      this.timerId = null;
    }
    this.setState('STOPPED', reason);
  }

  pause(durationMs: number, reason: string): void {
    this.pausedUntil = Date.now() + Math.max(1000, durationMs);
    this.setState('PAUSED', reason);
  }

  getSnapshot(): WorkerStateSnapshot {
    return {
      market: this.config.market,
      status: this.status,
      note: this.note,
      startedAt: this.startedAt,
      updatedAt: Date.now(),
      cooldownUntil: this.cooldownUntil,
    };
  }

  private setState(status: WorkerStatus, note: string): void {
    this.status = status;
    this.note = note;
    this.deps.onState(this.getSnapshot());
    this.emitEvent('STATE', status === 'ERROR' ? 'ERROR' : 'INFO', `${status}: ${note}`);
  }

  private emitEvent(type: WorkerEventType, level: 'INFO' | 'WARN' | 'ERROR', message: string): void {
    this.deps.onEvent?.({
      market: this.config.market,
      type,
      level,
      message,
      at: Date.now(),
    });
  }

  private emitOrderFlow(type: WorkerOrderFlowType, message: string): void {
    this.deps.onOrderFlow?.({
      market: this.config.market,
      type,
      message,
      at: Date.now(),
    });
  }

  private async tick(): Promise<void> {
    if (!this.running || this.ticking) return;
    this.ticking = true;
    try {
      const now = Date.now();
      if (this.pausedUntil && now < this.pausedUntil) {
        this.setState('PAUSED', `일시정지 ${Math.ceil((this.pausedUntil - now) / 1000)}초`);
        return;
      }
      if (this.pausedUntil && now >= this.pausedUntil) {
        this.pausedUntil = null;
      }

      if (this.cooldownUntil && now < this.cooldownUntil) {
        this.setState('COOLDOWN', `쿨다운 ${Math.ceil((this.cooldownUntil - now) / 1000)}초`);
        await this.manageExistingPosition();
        return;
      }
      if (this.cooldownUntil && now >= this.cooldownUntil) {
        this.cooldownUntil = null;
      }

      const position = await this.deps.getPosition(this.config.market);
      if (position && (position.status === 'OPEN' || position.status === 'PENDING_ENTRY')) {
        this.hadOpenPosition = position.status === 'OPEN';
        if (position.status === 'PENDING_ENTRY') {
          await this.managePendingEntry();
        } else {
          this.pendingEntryObservedAt = null;
          this.pendingFallbackTried = false;
          await this.managePosition(position);
        }
        return;
      }

      if (this.hadOpenPosition) {
        this.hadOpenPosition = false;
        this.peakPnlPercent = Number.NEGATIVE_INFINITY;
        this.cooldownUntil = Date.now() + this.config.postExitCooldownMs;
        this.setState('COOLDOWN', '포지션 종료 감지, 재진입 쿨다운');
        return;
      }

      this.pendingEntryObservedAt = null;
      this.pendingFallbackTried = false;
      this.peakPnlPercent = Number.NEGATIVE_INFINITY;
      await this.tryEntry();
    } catch (error) {
      const message = error instanceof Error ? error.message : '알 수 없는 오류';
      this.setState('ERROR', message);
      this.deps.onLog(`[${this.config.market}] worker error: ${message}`);
      this.cooldownUntil = Date.now() + this.config.rejectCooldownMs;
    } finally {
      this.ticking = false;
    }
  }

  private async tryEntry(): Promise<void> {
    this.setState('ANALYZING', 'deterministicCheck');
    const context = await this.deps.fetchContext(this.config.market);
    const deterministic = this.deterministicCheck(context);
    if (!deterministic.allow) {
      const cooldownMs = 45_000;
      this.emitEvent('LLM_REJECT', 'WARN', `규칙 거절: ${deterministic.reason}`);
      this.cooldownUntil = Date.now() + cooldownMs;
      this.setState('COOLDOWN', `규칙 거절(${Math.ceil(cooldownMs / 1000)}초): ${deterministic.reason}`);
      return;
    }

    if (!this.config.skipLlmEntryReview) {
      this.setState('ANALYZING', 'llmCheck');
      const entryDecision = await this.deps.evaluateEntry(this.config.market, context);
      const llmResult = this.evaluateLlmDecision(entryDecision);
      if (!llmResult.allow) {
        const cooldownMs = this.resolveLlmRejectCooldownMs(entryDecision);
        this.emitEvent(
          'LLM_REJECT',
          entryDecision.severity === 'HIGH' ? 'ERROR' : 'WARN',
          `진입 거절 (${entryDecision.confidence.toFixed(0)}/${entryDecision.severity}) · 쿨다운 ${Math.ceil(cooldownMs / 1000)}초: ${entryDecision.reason}`
        );
        this.cooldownUntil = Date.now() + cooldownMs;
        this.setState('COOLDOWN', `진입 보류(${Math.ceil(cooldownMs / 1000)}초): ${entryDecision.reason}`);
        return;
      }
    } else {
      this.emitEvent('STATE', 'INFO', 'AUTO_PASS fast-lane: LLM 진입 심사 생략');
    }

    this.setState('ANALYZING', 'orderPlan');
    const plan = await this.deps.planEntryOrder(this.config.market, context);
    if (!plan.allow) {
      this.emitEvent('LLM_REJECT', 'WARN', `주문 계획 거절: ${plan.reason}`);
      this.cooldownUntil = Date.now() + 45_000;
      this.setState('COOLDOWN', `주문 계획 거절: ${plan.reason}`);
      return;
    }

    if (this.config.enablePlaywrightCheck) {
      this.setState('PLAYWRIGHT_CHECK', 'UI 검증 중');
      const check = await this.deps.verifyWithPlaywright(this.config.market);
      if (!check.ok) {
        this.emitEvent('PLAYWRIGHT_WARN', 'WARN', check.note);
        this.deps.onLog(`[${this.config.market}] playwright 검증 실패, 계속 진행: ${check.note}`);
      }
    }

    this.setState('ENTERING', '진입 실행');
    this.emitOrderFlow('BUY_REQUESTED', '진입 주문 요청');
    try {
      await this.deps.startGuidedEntry(this.config.market, {
        orderType: plan.orderType,
        limitPrice: plan.limitPrice,
      });
      this.pendingEntryObservedAt = Date.now();
      this.pendingFallbackTried = false;
      this.emitOrderFlow('BUY_FILLED', 'Guided 진입 성공');
      this.emitEvent('ENTRY_SUCCESS', 'INFO', 'Guided API 진입 성공');
      this.setState('MANAGING', 'Guided 진입 성공');
      this.deps.onLog(`[${this.config.market}] Guided API 진입 성공`);
    } catch (primaryError) {
      this.deps.onLog(
        `[${this.config.market}] Guided 진입 실패, MCP 폴백 시도: ${
          primaryError instanceof Error ? primaryError.message : '알 수 없는 오류'
        }`
      );
      this.emitEvent(
        'ENTRY_FAILED',
        'WARN',
        `Guided 실패: ${primaryError instanceof Error ? primaryError.message : '알 수 없는 오류'}`
      );

      this.emitOrderFlow('BUY_REQUESTED', 'MCP 폴백 진입 요청');
      try {
        await this.deps.fallbackEntryByMcp(this.config.market);
        this.pendingEntryObservedAt = null;
        this.pendingFallbackTried = false;
        this.emitOrderFlow('BUY_FILLED', 'MCP 폴백 진입 성공');
        this.emitEvent('ENTRY_SUCCESS', 'INFO', 'MCP 폴백 진입 성공');
        this.setState('MANAGING', 'MCP 폴백 진입 성공');
        this.deps.onLog(`[${this.config.market}] MCP 폴백 진입 성공`);
      } catch (fallbackError) {
        this.emitEvent(
          'ENTRY_FAILED',
          'ERROR',
          `MCP 폴백 실패: ${fallbackError instanceof Error ? fallbackError.message : '알 수 없는 오류'}`
        );
        throw fallbackError;
      }
    }
  }

  private async manageExistingPosition(): Promise<void> {
    const position = await this.deps.getPosition(this.config.market);
    if (!position) return;
    if (position.status === 'PENDING_ENTRY') {
      await this.managePendingEntry();
      return;
    }
    await this.managePosition(position);
  }

  private async managePendingEntry(): Promise<void> {
    this.setState('MANAGING', '진입 체결 대기');
    if (this.pendingEntryObservedAt == null) {
      this.pendingEntryObservedAt = Date.now();
      this.pendingFallbackTried = false;
      return;
    }

    const elapsed = Date.now() - this.pendingEntryObservedAt;
    if (elapsed < this.config.pendingEntryTimeoutMs) return;

    this.deps.onLog(`[${this.config.market}] pending 진입 타임아웃(${Math.round(elapsed / 1000)}초), 주문 취소`);
    await this.deps.cancelPendingEntry(this.config.market);
    this.emitOrderFlow('CANCELLED', '진입 대기 취소');
    this.emitEvent('ENTRY_FAILED', 'WARN', `pending timeout 취소 (${Math.round(elapsed / 1000)}초)`);

    if (this.config.marketFallbackAfterCancel && !this.pendingFallbackTried) {
      this.pendingFallbackTried = true;
      this.emitOrderFlow('BUY_REQUESTED', 'pending timeout 후 시장가 폴백');
      try {
        await this.deps.startGuidedEntry(this.config.market, { orderType: 'MARKET' });
        this.emitOrderFlow('BUY_FILLED', '시장가 폴백 진입 성공');
        this.emitEvent('ENTRY_SUCCESS', 'INFO', 'pending timeout 시장가 폴백 성공');
        this.pendingEntryObservedAt = Date.now();
        this.setState('MANAGING', '시장가 폴백 진입');
        return;
      } catch (error) {
        this.emitEvent(
          'ENTRY_FAILED',
          'ERROR',
          `시장가 폴백 실패: ${error instanceof Error ? error.message : '알 수 없는 오류'}`
        );
      }
    }

    const cooldownMs = 90_000;
    this.cooldownUntil = Date.now() + cooldownMs;
    this.pendingEntryObservedAt = null;
    this.setState('COOLDOWN', `pending timeout 후 쿨다운 ${Math.ceil(cooldownMs / 1000)}초`);
  }

  private deterministicCheck(context: GuidedAgentContextResponse): { allow: boolean; reason: string } {
    const recommendation = context.chart.recommendation;
    const rr = recommendation.riskRewardRatio;
    const current = recommendation.currentPrice;
    const stopLoss = recommendation.stopLossPrice;
    const takeProfit = recommendation.takeProfitPrice;

    if (!Number.isFinite(rr) || rr < 1.05) {
      return { allow: false, reason: `Risk/Reward ${rr.toFixed(2)}R < 1.05R` };
    }

    if (current > 0 && stopLoss > 0 && current <= stopLoss * 1.003) {
      return { allow: false, reason: '현재가가 손절가에 과도하게 근접' };
    }

    if (current > 0 && takeProfit > 0 && current >= takeProfit * 0.995) {
      return { allow: false, reason: '현재가가 익절가에 과도하게 근접' };
    }

    return { allow: true, reason: '정량 기본 규칙 통과' };
  }

  private evaluateLlmDecision(decision: WorkerEntryDecision): { allow: boolean; reason: string } {
    const severity = decision.severity || 'MEDIUM';
    const confidence = Number.isFinite(decision.confidence) ? decision.confidence : 0;

    if (this.config.entryPolicy === 'AGGRESSIVE') {
      if (severity === 'HIGH') {
        return { allow: false, reason: `고위험 신호(${decision.reason})` };
      }
      return { allow: true, reason: '공격형 정책: 고위험 외 진입 허용' };
    }

    if (this.config.entryPolicy === 'CONSERVATIVE') {
      if (!decision.approve || confidence < this.config.minLlmConfidence) {
        return { allow: false, reason: `보수형 정책 거절 (${confidence.toFixed(0)})` };
      }
      return { allow: true, reason: '보수형 정책 승인' };
    }

    if (decision.approve && confidence >= this.config.minLlmConfidence) {
      return { allow: true, reason: 'BALANCED: LLM 승인' };
    }

    if (!decision.approve && severity !== 'HIGH' && confidence >= 40) {
      return { allow: true, reason: 'BALANCED: 저/중위험 거절은 진입 허용' };
    }

    if (severity === 'HIGH') {
      return { allow: false, reason: `BALANCED: 고위험 거절 (${decision.reason})` };
    }

    return { allow: false, reason: `BALANCED: 신뢰도 부족 (${confidence.toFixed(0)})` };
  }

  private resolveRejectCooldownMs(severity: 'LOW' | 'MEDIUM' | 'HIGH', confidence: number): number {
    if (severity === 'HIGH') return 90_000;
    if (confidence >= 60) return 45_000;
    return Math.max(45_000, this.config.rejectCooldownMs);
  }

  private resolveLlmRejectCooldownMs(decision: WorkerEntryDecision): number {
    const fallbackMs = this.resolveRejectCooldownMs(decision.severity, decision.confidence);
    const suggestedSec = this.normalizeSuggestedCooldownSec(decision.suggestedCooldownSec);
    if (suggestedSec == null) return fallbackMs;

    const severityFloorSec = decision.severity === 'HIGH' ? 90 : 45;
    const severityCeilSec = decision.severity === 'HIGH' ? 300 : 120;
    const boundedSec = Math.min(severityCeilSec, Math.max(severityFloorSec, suggestedSec));
    return boundedSec * 1000;
  }

  private normalizeSuggestedCooldownSec(value: unknown): number | null {
    if (typeof value === 'number' && Number.isFinite(value)) {
      return Math.min(1200, Math.max(30, Math.round(value)));
    }
    if (typeof value === 'string') {
      const parsed = Number(value);
      if (Number.isFinite(parsed)) {
        return Math.min(1200, Math.max(30, Math.round(parsed)));
      }
    }
    return null;
  }

  private async managePosition(position: GuidedTradePosition): Promise<void> {
    this.hadOpenPosition = true;
    const pnl = position.unrealizedPnlPercent;
    this.peakPnlPercent = Math.max(this.peakPnlPercent, pnl);
    const peakDrawdown = this.peakPnlPercent - pnl;
    this.setState('MANAGING', `모니터링 (${pnl >= 0 ? '+' : ''}${pnl.toFixed(2)}%)`);

    if (this.deps.reviewOpenPosition && this.shouldRunEventDrivenReview(pnl, peakDrawdown, position.trailingActive)) {
      this.lastEventReviewAt = Date.now();
      const review = await this.deps.reviewOpenPosition(this.config.market, position);
      if (review.action === 'FULL_EXIT') {
        this.emitOrderFlow('SELL_REQUESTED', `LLM 조기청산 요청: ${review.reason}`);
        await this.deps.stopPosition(this.config.market, `LLM 조기청산: ${review.reason}`);
        this.emitOrderFlow('SELL_FILLED', `LLM 조기청산 체결: ${review.reason}`);
        this.emitEvent('POSITION_EXIT', 'WARN', `LLM 조기청산: ${review.reason}`);
        this.cooldownUntil = Date.now() + (pnl <= 0 ? 8 * 60_000 : 3 * 60_000);
        this.peakPnlPercent = Number.NEGATIVE_INFINITY;
        this.setState('COOLDOWN', 'LLM 조기청산');
        return;
      }
      if (review.action === 'PARTIAL_TP' && !position.halfTakeProfitDone) {
        this.emitOrderFlow('SELL_REQUESTED', `LLM 부분익절 요청: ${review.reason}`);
        await this.deps.partialTakeProfit(this.config.market, 0.5, `LLM 부분익절: ${review.reason}`);
        this.emitOrderFlow('SELL_FILLED', `LLM 부분익절 실행: ${review.reason}`);
        this.emitEvent('PARTIAL_TP', 'INFO', `LLM 부분익절: ${review.reason}`);
        this.deps.onLog(`[${this.config.market}] LLM 권고 부분익절 실행`);
      }
    }

    if (pnl <= -0.8) {
      this.emitOrderFlow('SELL_REQUESTED', '빠른 손절 요청');
      await this.deps.stopPosition(this.config.market, '빠른 손절');
      this.emitOrderFlow('SELL_FILLED', '빠른 손절 체결');
      this.emitEvent('POSITION_EXIT', 'WARN', '빠른 손절 실행');
      this.cooldownUntil = Date.now() + 8 * 60_000;
      this.peakPnlPercent = Number.NEGATIVE_INFINITY;
      this.setState('COOLDOWN', '손절 후 쿨다운');
      return;
    }

    if (!position.halfTakeProfitDone && pnl >= 1.2) {
      this.emitOrderFlow('SELL_REQUESTED', '부분익절 50% 요청');
      await this.deps.partialTakeProfit(this.config.market, 0.5, '1차 부분익절');
      this.emitOrderFlow('SELL_FILLED', '부분익절 50% 실행');
      this.emitEvent('PARTIAL_TP', 'INFO', '부분익절 50% 실행');
      this.deps.onLog(`[${this.config.market}] 부분익절 50% 실행`);
    }

    if (pnl >= 2.2) {
      this.emitOrderFlow('SELL_REQUESTED', '목표 수익 청산 요청');
      await this.deps.stopPosition(this.config.market, '목표 수익 청산');
      this.emitOrderFlow('SELL_FILLED', '목표 수익 청산 체결');
      this.emitEvent('POSITION_EXIT', 'INFO', '목표 수익 청산');
      this.cooldownUntil = Date.now() + 3 * 60_000;
      this.peakPnlPercent = Number.NEGATIVE_INFINITY;
      this.setState('COOLDOWN', '익절 후 쿨다운');
      return;
    }
  }

  private shouldRunEventDrivenReview(pnl: number, peakDrawdown: number, trailingActive: boolean): boolean {
    if (Date.now() - this.lastEventReviewAt < 30_000) return false;
    if (pnl <= -0.6) return true;
    if (pnl >= 1.6) return true;
    if (trailingActive && peakDrawdown >= 0.7) return true;
    return false;
  }
}
