export type LlmBudgetEngine = 'SCALP' | 'SWING' | 'POSITION';
export type LlmBudgetAction = 'ENTRY_REVIEW' | 'FINE_AGENT' | 'POSITION_REVIEW';

export interface LlmGovernorConfig {
  dailyTokenCap: number;
  riskReserveTokens: number;
}

export interface LlmGovernorSpendDecision {
  allow: boolean;
  reason?: string;
  fallbackMode: boolean;
}

export interface LlmGovernorSnapshot {
  kstDate: string;
  dailyTokenCap: number;
  usedTokens: number;
  remainingTokens: number;
  entryBudgetTotal: number;
  entryUsedTokens: number;
  entryRemainingTokens: number;
  riskReserveTokens: number;
  riskUsedTokens: number;
  riskRemainingTokens: number;
  entryCapByEngine: Record<LlmBudgetEngine, number>;
  entryUsedByEngine: Record<LlmBudgetEngine, number>;
  entryRemainingByEngine: Record<LlmBudgetEngine, number>;
  fallbackMode: boolean;
}

const KST_TIMEZONE = 'Asia/Seoul';
const ENGINE_RATIOS: Record<LlmBudgetEngine, number> = {
  SCALP: 0.4,
  SWING: 0.35,
  POSITION: 0.25,
};

class LlmTokenGovernor {
  private config: LlmGovernorConfig = {
    dailyTokenCap: 200_000,
    riskReserveTokens: 40_000,
  };
  private kstDate = this.kstDateKey();
  private usedTokens = 0;
  private riskUsedTokens = 0;
  private entryUsedByEngine: Record<LlmBudgetEngine, number> = {
    SCALP: 0,
    SWING: 0,
    POSITION: 0,
  };

  configure(next: Partial<LlmGovernorConfig>): void {
    this.rolloverIfNeeded();
    const dailyTokenCap = this.normalizeCap(next.dailyTokenCap ?? this.config.dailyTokenCap);
    const riskReserveTokens = this.normalizeReserve(next.riskReserveTokens ?? this.config.riskReserveTokens, dailyTokenCap);
    this.config = { dailyTokenCap, riskReserveTokens };
  }

  getSnapshot(): LlmGovernorSnapshot {
    this.rolloverIfNeeded();
    const entryCaps = this.resolveEntryCaps();
    const entryUsedTokens =
      this.entryUsedByEngine.SCALP +
      this.entryUsedByEngine.SWING +
      this.entryUsedByEngine.POSITION;
    const entryRemainingByEngine: Record<LlmBudgetEngine, number> = {
      SCALP: Math.max(0, entryCaps.SCALP - this.entryUsedByEngine.SCALP),
      SWING: Math.max(0, entryCaps.SWING - this.entryUsedByEngine.SWING),
      POSITION: Math.max(0, entryCaps.POSITION - this.entryUsedByEngine.POSITION),
    };
    const remainingTokens = Math.max(0, this.config.dailyTokenCap - this.usedTokens);
    return {
      kstDate: this.kstDate,
      dailyTokenCap: this.config.dailyTokenCap,
      usedTokens: this.usedTokens,
      remainingTokens,
      entryBudgetTotal: entryCaps.SCALP + entryCaps.SWING + entryCaps.POSITION,
      entryUsedTokens,
      entryRemainingTokens: Math.max(0, entryCaps.SCALP + entryCaps.SWING + entryCaps.POSITION - entryUsedTokens),
      riskReserveTokens: this.config.riskReserveTokens,
      riskUsedTokens: this.riskUsedTokens,
      riskRemainingTokens: Math.max(0, this.config.riskReserveTokens - this.riskUsedTokens),
      entryCapByEngine: entryCaps,
      entryUsedByEngine: { ...this.entryUsedByEngine },
      entryRemainingByEngine,
      fallbackMode: remainingTokens <= 0,
    };
  }

  canSpend(action: LlmBudgetAction, engine: LlmBudgetEngine, estimatedTokens: number): LlmGovernorSpendDecision {
    this.rolloverIfNeeded();
    const tokens = this.normalizeTokens(estimatedTokens);
    const snapshot = this.getSnapshot();
    if (snapshot.remainingTokens < tokens) {
      return {
        allow: false,
        reason: `일일 토큰 상한 도달 (${snapshot.usedTokens}/${snapshot.dailyTokenCap})`,
        fallbackMode: true,
      };
    }

    if (action === 'POSITION_REVIEW') {
      if (snapshot.riskRemainingTokens < tokens) {
        return {
          allow: false,
          reason: `리스크 reserve 부족 (${snapshot.riskUsedTokens}/${snapshot.riskReserveTokens})`,
          fallbackMode: snapshot.remainingTokens <= 0,
        };
      }
      return { allow: true, fallbackMode: snapshot.remainingTokens <= 0 };
    }

    if (snapshot.entryRemainingByEngine[engine] < tokens) {
      return {
        allow: false,
        reason: `${engine} 엔진 entry budget 부족 (${snapshot.entryUsedByEngine[engine]}/${snapshot.entryCapByEngine[engine]})`,
        fallbackMode: true,
      };
    }

    return { allow: true, fallbackMode: snapshot.remainingTokens <= 0 };
  }

  spend(action: LlmBudgetAction, engine: LlmBudgetEngine, estimatedTokens: number): LlmGovernorSpendDecision {
    const decision = this.canSpend(action, engine, estimatedTokens);
    if (!decision.allow) return decision;

    const tokens = this.normalizeTokens(estimatedTokens);
    this.usedTokens += tokens;
    if (action === 'POSITION_REVIEW') {
      this.riskUsedTokens += tokens;
    } else {
      this.entryUsedByEngine[engine] += tokens;
    }

    const snapshot = this.getSnapshot();
    return {
      allow: true,
      fallbackMode: snapshot.remainingTokens <= 0 || snapshot.entryRemainingByEngine[engine] <= 0,
    };
  }

  recordUsage(action: LlmBudgetAction, engine: LlmBudgetEngine, usedTokens: number): void {
    this.rolloverIfNeeded();
    const tokens = this.normalizeTokens(usedTokens);
    const entryCaps = this.resolveEntryCaps();
    this.usedTokens = Math.min(this.config.dailyTokenCap, this.usedTokens + tokens);
    if (action === 'POSITION_REVIEW') {
      this.riskUsedTokens = Math.min(this.config.riskReserveTokens, this.riskUsedTokens + tokens);
      return;
    }
    this.entryUsedByEngine[engine] = Math.min(
      entryCaps[engine],
      this.entryUsedByEngine[engine] + tokens
    );
  }

  private resolveEntryCaps(): Record<LlmBudgetEngine, number> {
    const entryBudget = Math.max(0, this.config.dailyTokenCap - this.config.riskReserveTokens);
    const scalp = Math.floor(entryBudget * ENGINE_RATIOS.SCALP);
    const swing = Math.floor(entryBudget * ENGINE_RATIOS.SWING);
    const position = Math.max(0, entryBudget - scalp - swing);
    return {
      SCALP: scalp,
      SWING: swing,
      POSITION: position,
    };
  }

  private normalizeCap(value: number): number {
    if (!Number.isFinite(value)) return 200_000;
    return Math.min(2_000_000, Math.max(20_000, Math.round(value)));
  }

  private normalizeReserve(value: number, cap: number): number {
    if (!Number.isFinite(value)) return Math.min(40_000, cap);
    return Math.min(cap, Math.max(0, Math.round(value)));
  }

  private normalizeTokens(value: number): number {
    if (!Number.isFinite(value)) return 1;
    return Math.max(1, Math.round(value));
  }

  private kstDateKey(now = new Date()): string {
    return now.toLocaleDateString('en-CA', { timeZone: KST_TIMEZONE });
  }

  private rolloverIfNeeded(): void {
    const next = this.kstDateKey();
    if (next === this.kstDate) return;
    this.kstDate = next;
    this.usedTokens = 0;
    this.riskUsedTokens = 0;
    this.entryUsedByEngine = {
      SCALP: 0,
      SWING: 0,
      POSITION: 0,
    };
  }
}

const singleton = new LlmTokenGovernor();

export function getLlmTokenGovernor(): LlmTokenGovernor {
  return singleton;
}
