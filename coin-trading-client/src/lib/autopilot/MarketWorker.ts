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
}

export interface WorkerDependencies {
  fetchContext: (market: string) => Promise<GuidedAgentContextResponse>;
  evaluateEntry: (
    market: string,
    context: GuidedAgentContextResponse
  ) => Promise<{ approve: boolean; confidence: number; reason: string }>;
  verifyWithPlaywright: (market: string) => Promise<{ ok: boolean; note: string }>;
  startGuidedEntry: (market: string) => Promise<void>;
  fallbackEntryByMcp: (market: string) => Promise<void>;
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
  private lastLlmReviewAt = 0;
  private hadOpenPosition = false;

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
        await this.managePosition(position);
        return;
      }

      if (this.hadOpenPosition) {
        this.hadOpenPosition = false;
        this.cooldownUntil = Date.now() + this.config.postExitCooldownMs;
        this.setState('COOLDOWN', '포지션 종료 감지, 재진입 쿨다운');
        return;
      }

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
    this.setState('ANALYZING', '진입 조건 점검');
    const context = await this.deps.fetchContext(this.config.market);
    const entryDecision = await this.deps.evaluateEntry(this.config.market, context);
    if (!entryDecision.approve || entryDecision.confidence < this.config.minLlmConfidence) {
      this.emitEvent(
        'LLM_REJECT',
        'WARN',
        `진입 거절 (${entryDecision.confidence.toFixed(0)}): ${entryDecision.reason}`
      );
      this.cooldownUntil = Date.now() + this.config.rejectCooldownMs;
      this.setState('COOLDOWN', `진입 보류: ${entryDecision.reason}`);
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
      await this.deps.startGuidedEntry(this.config.market);
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
    await this.managePosition(position);
  }

  private async managePosition(position: GuidedTradePosition): Promise<void> {
    if (position.status === 'PENDING_ENTRY') {
      this.setState('MANAGING', '진입 체결 대기');
      return;
    }

    this.hadOpenPosition = true;
    const pnl = position.unrealizedPnlPercent;
    this.setState('MANAGING', `모니터링 (${pnl >= 0 ? '+' : ''}${pnl.toFixed(2)}%)`);

    if (pnl <= -0.8) {
      this.emitOrderFlow('SELL_REQUESTED', '빠른 손절 요청');
      await this.deps.stopPosition(this.config.market, '빠른 손절');
      this.emitOrderFlow('SELL_FILLED', '빠른 손절 체결');
      this.emitEvent('POSITION_EXIT', 'WARN', '빠른 손절 실행');
      this.cooldownUntil = Date.now() + this.config.postExitCooldownMs;
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
      this.cooldownUntil = Date.now() + this.config.postExitCooldownMs;
      this.setState('COOLDOWN', '익절 후 쿨다운');
      return;
    }

    if (this.deps.reviewOpenPosition && Date.now() - this.lastLlmReviewAt >= this.config.llmReviewMs) {
      this.lastLlmReviewAt = Date.now();
      const review = await this.deps.reviewOpenPosition(this.config.market, position);
      if (review.action === 'FULL_EXIT') {
        this.emitOrderFlow('SELL_REQUESTED', `LLM 조기청산 요청: ${review.reason}`);
        await this.deps.stopPosition(this.config.market, `LLM 조기청산: ${review.reason}`);
        this.emitOrderFlow('SELL_FILLED', `LLM 조기청산 체결: ${review.reason}`);
        this.emitEvent('POSITION_EXIT', 'WARN', `LLM 조기청산: ${review.reason}`);
        this.cooldownUntil = Date.now() + this.config.postExitCooldownMs;
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
  }
}
