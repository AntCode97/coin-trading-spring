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

export interface WorkerConfig {
  market: string;
  tickMs: number;
  cooldownMs: number;
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
        this.cooldownUntil = Date.now() + this.config.cooldownMs;
        this.setState('COOLDOWN', '포지션 종료 감지, 재진입 쿨다운');
        return;
      }

      await this.tryEntry();
    } catch (error) {
      const message = error instanceof Error ? error.message : '알 수 없는 오류';
      this.setState('ERROR', message);
      this.deps.onLog(`[${this.config.market}] worker error: ${message}`);
      this.cooldownUntil = Date.now() + this.config.cooldownMs;
    } finally {
      this.ticking = false;
    }
  }

  private async tryEntry(): Promise<void> {
    this.setState('ANALYZING', '진입 조건 점검');
    const context = await this.deps.fetchContext(this.config.market);
    const entryDecision = await this.deps.evaluateEntry(this.config.market, context);
    if (!entryDecision.approve || entryDecision.confidence < this.config.minLlmConfidence) {
      this.cooldownUntil = Date.now() + this.config.cooldownMs;
      this.setState('COOLDOWN', `진입 보류: ${entryDecision.reason}`);
      return;
    }

    if (this.config.enablePlaywrightCheck) {
      this.setState('PLAYWRIGHT_CHECK', 'UI 검증 중');
      const check = await this.deps.verifyWithPlaywright(this.config.market);
      if (!check.ok) {
        this.deps.onLog(`[${this.config.market}] playwright 검증 실패, 계속 진행: ${check.note}`);
      }
    }

    this.setState('ENTERING', '진입 실행');
    try {
      await this.deps.startGuidedEntry(this.config.market);
      this.setState('MANAGING', 'Guided 진입 성공');
      this.deps.onLog(`[${this.config.market}] Guided API 진입 성공`);
    } catch (primaryError) {
      this.deps.onLog(
        `[${this.config.market}] Guided 진입 실패, MCP 폴백 시도: ${
          primaryError instanceof Error ? primaryError.message : '알 수 없는 오류'
        }`
      );
      await this.deps.fallbackEntryByMcp(this.config.market);
      this.setState('MANAGING', 'MCP 폴백 진입 성공');
      this.deps.onLog(`[${this.config.market}] MCP 폴백 진입 성공`);
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
      await this.deps.stopPosition(this.config.market, '빠른 손절');
      this.cooldownUntil = Date.now() + this.config.cooldownMs;
      this.setState('COOLDOWN', '손절 후 쿨다운');
      return;
    }

    if (!position.halfTakeProfitDone && pnl >= 1.2) {
      await this.deps.partialTakeProfit(this.config.market, 0.5, '1차 부분익절');
      this.deps.onLog(`[${this.config.market}] 부분익절 50% 실행`);
    }

    if (pnl >= 2.2) {
      await this.deps.stopPosition(this.config.market, '목표 수익 청산');
      this.cooldownUntil = Date.now() + this.config.cooldownMs;
      this.setState('COOLDOWN', '익절 후 쿨다운');
      return;
    }

    if (this.deps.reviewOpenPosition && Date.now() - this.lastLlmReviewAt >= this.config.llmReviewMs) {
      this.lastLlmReviewAt = Date.now();
      const review = await this.deps.reviewOpenPosition(this.config.market, position);
      if (review.action === 'FULL_EXIT') {
        await this.deps.stopPosition(this.config.market, `LLM 조기청산: ${review.reason}`);
        this.cooldownUntil = Date.now() + this.config.cooldownMs;
        this.setState('COOLDOWN', 'LLM 조기청산');
        return;
      }
      if (review.action === 'PARTIAL_TP' && !position.halfTakeProfitDone) {
        await this.deps.partialTakeProfit(this.config.market, 0.5, `LLM 부분익절: ${review.reason}`);
        this.deps.onLog(`[${this.config.market}] LLM 권고 부분익절 실행`);
      }
    }
  }
}
