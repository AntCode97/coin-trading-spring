import type {
  AiScalpScanMarket,
  GuidedClosedTradeView,
  GuidedTradePosition,
} from '../../api';
import type {
  AiEntryAggression,
  AiExitCategory,
  AiStrategyReflection,
  AiStrategyReviewAdjustments,
  AiUrgency,
} from './AiDayTraderModel';
import {
  createInitialAiStrategyReflection,
} from './AiDayTraderModel';

export type AiPerformanceRegime = 'NORMAL' | 'CAUTIOUS' | 'DEFENSIVE';
export type AiTempoProfileKey = 'DEFAULT' | 'DOLLAR_PEG';

export interface AiTempoProfile {
  key: AiTempoProfileKey;
  label: string;
  minEntryExpectancyPct: number;
  minEntryWinRate: number;
  minEntryRiskReward: number;
  maxEntryGapPct: number;
  maxEntrySpreadPct: number;
  minEntryCrowdFlow: number;
  earlyHoldMinutes: number;
  earlyExitPnlFloor: number;
  minStopDistancePct: number;
  maxStopDistancePct: number;
  minTakeDistancePct: number;
  maxTakeDistancePct: number;
  minTargetRiskReward: number;
  maxHoldingExtraMinutes: number;
  staleLossMinutes: number;
  staleFlatMinutes: number;
  manageNote: string;
}

export interface AiEntryAggressionProfile {
  label: string;
  finalistLimit: number;
  minBuyConfidence: number;
  expectancyOffset: number;
  winRateOffset: number;
  riskRewardOffset: number;
  gapMultiplier: number;
  spreadMultiplier: number;
  crowdFlowOffset: number;
  softPenaltyAllowance: number;
}

export interface AiPerformanceSnapshot {
  regime: AiPerformanceRegime;
  selectedAggression: AiEntryAggression;
  effectiveAggression: AiEntryAggression;
  consecutiveLosses: number;
  recentTradeCount: number;
  recentNetPnlKrw: number;
  recentAvgPnlPercent: number;
}

export interface AiDeterministicExitDecision {
  reason: string;
  exitReasonCode: string;
  category: AiExitCategory;
  confidence: number;
  urgency: AiUrgency;
}

const DEFAULT_TEMPO_PROFILE: AiTempoProfile = {
  key: 'DEFAULT',
  label: '기본',
  minEntryExpectancyPct: 0.05,
  minEntryWinRate: 53.5,
  minEntryRiskReward: 1.18,
  maxEntryGapPct: 0.55,
  maxEntrySpreadPct: 0.18,
  minEntryCrowdFlow: 18,
  earlyHoldMinutes: 2,
  earlyExitPnlFloor: -0.45,
  minStopDistancePct: 0.55,
  maxStopDistancePct: 1.10,
  minTakeDistancePct: 0.95,
  maxTakeDistancePct: 2.20,
  minTargetRiskReward: 1.55,
  maxHoldingExtraMinutes: 0,
  staleLossMinutes: 10,
  staleFlatMinutes: 18,
  manageNote: '일반 알트 템포다. 5~30분 안에 방향성이 선명해야 한다.',
};

const DOLLAR_PEG_TEMPO_PROFILE: AiTempoProfile = {
  key: 'DOLLAR_PEG',
  label: '달러 추종',
  minEntryExpectancyPct: 0.02,
  minEntryWinRate: 55.0,
  minEntryRiskReward: 1.05,
  maxEntryGapPct: 0.24,
  maxEntrySpreadPct: 0.12,
  minEntryCrowdFlow: 8,
  earlyHoldMinutes: 4,
  earlyExitPnlFloor: -0.30,
  minStopDistancePct: 0.32,
  maxStopDistancePct: 0.72,
  minTakeDistancePct: 0.42,
  maxTakeDistancePct: 1.20,
  minTargetRiskReward: 1.28,
  maxHoldingExtraMinutes: 10,
  staleLossMinutes: 18,
  staleFlatMinutes: 30,
  manageNote: '테더 같은 달러 추종 자산은 다른 코인보다 전개와 체결이 느리다. 작은 흔들림에 과민하게 청산하지 말고 10~40분 템포로 보라.',
};

const DOLLAR_PEG_SYMBOLS = new Set([
  'USDT',
  'USDC',
  'FDUSD',
  'TUSD',
  'USDP',
  'DAI',
  'BUSD',
]);

const ENTRY_AGGRESSION_PROFILES: Record<AiEntryAggression, AiEntryAggressionProfile> = {
  CONSERVATIVE: {
    label: '보수적',
    finalistLimit: 4,
    minBuyConfidence: 0.62,
    expectancyOffset: 0.05,
    winRateOffset: 1.5,
    riskRewardOffset: 0.12,
    gapMultiplier: 0.8,
    spreadMultiplier: 0.8,
    crowdFlowOffset: 8,
    softPenaltyAllowance: -0.35,
  },
  BALANCED: {
    label: '균형',
    finalistLimit: 6,
    minBuyConfidence: 0.56,
    expectancyOffset: 0,
    winRateOffset: 0,
    riskRewardOffset: 0,
    gapMultiplier: 1,
    spreadMultiplier: 1,
    crowdFlowOffset: 0,
    softPenaltyAllowance: 0,
  },
  AGGRESSIVE: {
    label: '공격적',
    finalistLimit: 8,
    minBuyConfidence: 0.48,
    expectancyOffset: -0.03,
    winRateOffset: -1.5,
    riskRewardOffset: -0.08,
    gapMultiplier: 1.25,
    spreadMultiplier: 1.25,
    crowdFlowOffset: -8,
    softPenaltyAllowance: 0.45,
  },
};

export const SHORTLIST_SYSTEM_PROMPT = [
  '너는 KRW 현물 롱과 바이낸스 USDT 선물 숏을 모두 다룰 수 있는 전업 초단타 트레이더다.',
  '목표는 5~30분 안에 끝낼 수 있는 유동성 높은 기회를 많이 잡는 것이다.',
  '과도하게 WAIT만 반복하지는 말되, 얇은 호가나 잡음성 급등처럼 손절이 쉬운 종목은 shortlist에서 낮춰라.',
  '평가 기준: 체결대금, 즉시성 있는 모멘텀, crowd pressure, 순기대값, 스프레드, 진입 괴리, follow-through 가능성.',
  'JSON만 반환한다.',
  '형식: {"shortlist":[{"market":"KRW-BTC","score":82,"reason":"짧은 근거"}]}',
  '최대 12개까지만 반환한다.',
].join('\n');

export const ENTRY_SYSTEM_PROMPT = [
  '너는 공격적이지만 규율 있는 초단타 트레이더다.',
  '보유시간 목표는 5~30분이다.',
  '하드리스크가 아니면 과도하게 WAIT 하지 말고, follow-through 가능성이 있으면 BUY를 우선 검토하라.',
  '순기대값이 명확히 음수이거나, 스프레드가 과도하게 넓거나, 급등 추격이면 WAIT를 선택하라.',
  '깨끗한 추세 지속이나 눌림 재가속만 BUY하고, 잡음성 급등/급락 반대매매는 피하라.',
  'JSON만 반환한다.',
  '형식: {"action":"BUY|WAIT","confidence":0.0,"reasoning":"짧은 근거","stopLoss":0,"takeProfit":0,"urgency":"LOW|MEDIUM|HIGH"}',
  'stopLoss는 현재가보다 낮아야 하고 takeProfit은 현재가보다 높아야 한다.',
].join('\n');

export const MANAGE_SYSTEM_PROMPT = [
  '너는 이미 보유한 초단타 포지션을 관리하는 트레이더다.',
  '보유시간 목표는 5~30분이며, 모멘텀이 약해지거나 downside가 커지면 SELL을 선택한다.',
  '진입 직후 1~2분의 작은 흔들림에는 과민반응하지 말고, 구조 훼손/유동성 붕괴/손절 근접에서만 강하게 SELL 하라.',
  'JSON만 반환한다.',
  '형식: {"action":"HOLD|SELL","confidence":0.0,"reasoning":"짧은 근거","urgency":"LOW|MEDIUM|HIGH"}',
].join('\n');

export const REVIEW_SYSTEM_PROMPT = [
  '너는 자신의 KRW 현물 초단타 거래를 복기하며 전략을 즉시 조정하는 전업 트레이더다.',
  '목표는 최근 손실 패턴을 빨리 끊고, 실제로 먹힌 패턴에 더 집중하는 것이다.',
  '최근 거래내역을 읽고 focusMarkets, avoidMarkets, preferredSetups, avoidSetups와 작은 수치 조정만 제안하라.',
  '조정은 보수적으로 해야 한다. 지나치게 큰 숫자를 내지 말라.',
  'JSON만 반환한다.',
  '형식: {"headline":"짧은 제목","summary":"짧은 요약","focusMarkets":["KRW-BTC"],"avoidMarkets":["KRW-WLD"],"preferredSetups":["짧은 문장"],"avoidSetups":["짧은 문장"],"adjustments":{"expectancyOffsetPct":0.00,"minWinRateOffset":0.0,"riskRewardOffset":0.0,"spreadMultiplier":1.0,"gapMultiplier":1.0,"crowdFlowOffset":0,"minBuyConfidenceOffset":0.0,"finalistLimitOffset":0,"maxHoldingMinutesOffset":0,"earlyExitPnlFloorOffset":0.0,"staleLossMinutesOffset":0,"staleFlatMinutesOffset":0}}',
].join('\n');

function clampNumber(value: number, minValue: number, maxValue: number): number {
  return Math.min(maxValue, Math.max(minValue, value));
}

function holdingMinutesFromTrade(trade: GuidedClosedTradeView): number {
  const start = new Date(trade.createdAt).getTime();
  const end = new Date(trade.closedAt ?? trade.createdAt).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end)) return 0;
  return Math.max(0, (end - start) / 60_000);
}

function isStopLossExit(exitReason?: string | null): boolean {
  const normalized = exitReason?.trim().toUpperCase() ?? '';
  return normalized === 'STOP_LOSS' || normalized === 'AI_STOP_LOSS' || normalized.includes('LOSS');
}

function isStaleExit(exitReason?: string | null): boolean {
  const normalized = exitReason?.trim().toUpperCase() ?? '';
  return normalized === 'AI_STALE_LOSER' || normalized === 'AI_STALE_FLAT' || normalized.includes('STALE');
}

function isProfitFadeExit(exitReason?: string | null): boolean {
  const normalized = exitReason?.trim().toUpperCase() ?? '';
  return normalized === 'AI_PROFIT_FADE' || normalized.includes('PROFIT_FADE');
}

function neutralAdjustments(): AiStrategyReviewAdjustments {
  return {
    expectancyOffsetPct: 0,
    minWinRateOffset: 0,
    riskRewardOffset: 0,
    spreadMultiplier: 1,
    gapMultiplier: 1,
    crowdFlowOffset: 0,
    minBuyConfidenceOffset: 0,
    finalistLimitOffset: 0,
    maxHoldingMinutesOffset: 0,
    earlyExitPnlFloorOffset: 0,
    staleLossMinutesOffset: 0,
    staleFlatMinutesOffset: 0,
  };
}

function topMarketsByScore(entries: Array<{ market: string; score: number }>, predicate: (entry: { market: string; score: number }) => boolean): string[] {
  return [...entries]
    .filter(predicate)
    .sort((left, right) => right.score - left.score)
    .slice(0, 3)
    .map((entry) => entry.market);
}

function formatPercentValue(value: number): string {
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toFixed(2)}%`;
}

export function getTempoProfile(market: string, koreanName?: string | null): AiTempoProfile {
  const symbol = market.replace(/^KRW-/, '').trim().toUpperCase();
  const name = (koreanName ?? '').trim().toUpperCase();
  if (
    DOLLAR_PEG_SYMBOLS.has(symbol) ||
    name.includes('테더') ||
    name.includes('USD') ||
    name.includes('달러')
  ) {
    return DOLLAR_PEG_TEMPO_PROFILE;
  }
  return DEFAULT_TEMPO_PROFILE;
}

export function getEffectiveMaxHoldingMinutes(maxHoldingMinutes: number, tempoProfile: AiTempoProfile): number {
  if (tempoProfile.key !== 'DOLLAR_PEG') {
    return maxHoldingMinutes;
  }
  return maxHoldingMinutes + tempoProfile.maxHoldingExtraMinutes;
}

export function buildPerformanceSnapshot(
  trades: GuidedClosedTradeView[],
  selectedAggression: AiEntryAggression
): AiPerformanceSnapshot {
  const recentTrades = trades.slice(0, 6);
  const recentNetPnlKrw = recentTrades.reduce((sum, trade) => sum + trade.realizedPnl, 0);
  const recentAvgPnlPercent = recentTrades.length > 0
    ? recentTrades.reduce((sum, trade) => sum + trade.realizedPnlPercent, 0) / recentTrades.length
    : 0;

  let consecutiveLosses = 0;
  for (const trade of trades) {
    if (trade.realizedPnl >= 0) break;
    consecutiveLosses += 1;
  }

  const recentWinRate = recentTrades.length > 0
    ? (recentTrades.filter((trade) => trade.realizedPnl > 0).length / recentTrades.length) * 100
    : 0;

  let regime: AiPerformanceRegime = 'NORMAL';
  if (
    consecutiveLosses >= 3 ||
    (recentTrades.length >= 5 && recentWinRate <= 25 && recentAvgPnlPercent <= -0.20)
  ) {
    regime = 'DEFENSIVE';
  } else if (
    consecutiveLosses >= 2 ||
    (recentTrades.length >= 4 && recentWinRate < 40 && recentAvgPnlPercent < -0.08)
  ) {
    regime = 'CAUTIOUS';
  }

  return {
    regime,
    selectedAggression,
    effectiveAggression: resolveEffectiveAggression(selectedAggression, regime),
    consecutiveLosses,
    recentTradeCount: recentTrades.length,
    recentNetPnlKrw,
    recentAvgPnlPercent,
  };
}

export function deriveDeterministicStrategyReflection(
  trades: GuidedClosedTradeView[],
  performanceSnapshot: AiPerformanceSnapshot,
): AiStrategyReflection {
  if (trades.length === 0) {
    return createInitialAiStrategyReflection();
  }

  const recentTrades = trades.slice(0, 12);
  const adjustments = neutralAdjustments();
  const preferredSetups: string[] = [];
  const avoidSetups: string[] = [];

  const quickLosses = recentTrades.filter((trade) => trade.realizedPnlPercent < 0 && holdingMinutesFromTrade(trade) <= 6).length;
  const staleLosses = recentTrades.filter((trade) => trade.realizedPnlPercent < 0 && isStaleExit(trade.exitReason)).length;
  const stopLosses = recentTrades.filter((trade) => trade.realizedPnlPercent < 0 && isStopLossExit(trade.exitReason)).length;
  const profitFades = recentTrades.filter((trade) => isProfitFadeExit(trade.exitReason)).length;
  const slowFlatLosses = recentTrades.filter((trade) => trade.realizedPnlPercent <= 0.04 && holdingMinutesFromTrade(trade) >= 22).length;

  if (performanceSnapshot.regime === 'CAUTIOUS') {
    adjustments.expectancyOffsetPct += 0.02;
    adjustments.minWinRateOffset += 1;
    adjustments.riskRewardOffset += 0.06;
    adjustments.spreadMultiplier *= 0.9;
    adjustments.gapMultiplier *= 0.9;
    adjustments.crowdFlowOffset += 4;
    adjustments.minBuyConfidenceOffset += 0.03;
    adjustments.finalistLimitOffset -= 1;
    adjustments.maxHoldingMinutesOffset -= 4;
    preferredSetups.push('거래대금 상위 · 체결 빠른 메이저 우선');
  } else if (performanceSnapshot.regime === 'DEFENSIVE') {
    adjustments.expectancyOffsetPct += 0.04;
    adjustments.minWinRateOffset += 2.5;
    adjustments.riskRewardOffset += 0.12;
    adjustments.spreadMultiplier *= 0.82;
    adjustments.gapMultiplier *= 0.8;
    adjustments.crowdFlowOffset += 8;
    adjustments.minBuyConfidenceOffset += 0.06;
    adjustments.finalistLimitOffset -= 2;
    adjustments.maxHoldingMinutesOffset -= 8;
    adjustments.earlyExitPnlFloorOffset += 0.05;
    adjustments.staleLossMinutesOffset -= 2;
    adjustments.staleFlatMinutesOffset -= 4;
    preferredSetups.push('최상위 유동성 · spread 얕은 후보만 우선');
  } else if (
    performanceSnapshot.recentTradeCount >= 4 &&
    performanceSnapshot.recentNetPnlKrw > 0 &&
    performanceSnapshot.recentAvgPnlPercent >= 0.10
  ) {
    adjustments.expectancyOffsetPct -= 0.01;
    adjustments.minBuyConfidenceOffset -= 0.02;
    adjustments.finalistLimitOffset += 1;
    preferredSetups.push('최근 먹힌 follow-through 패턴은 조금 더 넓게 검토');
  }

  if (quickLosses >= 2 || stopLosses >= 2) {
    adjustments.spreadMultiplier *= 0.86;
    adjustments.gapMultiplier *= 0.8;
    adjustments.expectancyOffsetPct += 0.02;
    adjustments.minBuyConfidenceOffset += 0.03;
    avoidSetups.push('초기 5분 안에 흔들리는 추격 진입');
  }

  if (staleLosses >= 2 || slowFlatLosses >= 2) {
    adjustments.maxHoldingMinutesOffset -= 6;
    adjustments.earlyExitPnlFloorOffset += 0.04;
    adjustments.staleLossMinutesOffset -= 3;
    adjustments.staleFlatMinutesOffset -= 5;
    avoidSetups.push('20분 넘게 수익 전개 없는 플랫 보유');
  }

  if (profitFades >= 2) {
    adjustments.earlyExitPnlFloorOffset += 0.02;
    preferredSetups.push('초반 수익 발생 시 이익 반납 전에 빨리 회수');
  }

  const marketScores = new Map<string, { pnlPercent: number; pnlKrw: number; wins: number; losses: number; trades: number }>();
  for (const trade of recentTrades) {
    const current = marketScores.get(trade.market) ?? { pnlPercent: 0, pnlKrw: 0, wins: 0, losses: 0, trades: 0 };
    current.pnlPercent += trade.realizedPnlPercent;
    current.pnlKrw += trade.realizedPnl;
    current.trades += 1;
    if (trade.realizedPnl >= 0) {
      current.wins += 1;
    } else {
      current.losses += 1;
    }
    marketScores.set(trade.market, current);
  }

  const focusMarkets = topMarketsByScore(
    [...marketScores.entries()].map(([market, value]) => ({ market, score: value.pnlPercent + value.wins * 0.18 + value.pnlKrw / 1000 })),
    (entry) => entry.score > 0,
  );
  const avoidMarkets = topMarketsByScore(
    [...marketScores.entries()].map(([market, value]) => ({ market, score: -(value.pnlPercent - value.losses * 0.2 - value.pnlKrw / 1000) })),
    (entry) => entry.score > 0.24,
  );

  if (focusMarkets.length > 0) {
    preferredSetups.push(`최근 성과 우위 시장: ${focusMarkets.map((market) => market.replace('KRW-', '')).join(', ')}`);
  }
  if (avoidMarkets.length > 0) {
    avoidSetups.push(`최근 손실 반복 시장: ${avoidMarkets.map((market) => market.replace('KRW-', '')).join(', ')}`);
  }

  const headline = performanceSnapshot.recentNetPnlKrw < 0
    ? quickLosses + stopLosses >= staleLosses + slowFlatLosses
      ? '추격 진입 축소 · 체결 좋은 후보 우선'
      : '느린 포지션 축소 · 빠른 전개 우선'
    : performanceSnapshot.recentNetPnlKrw > 0
      ? '먹히는 시장과 전개에 집중'
      : '균형 유지 · 추가 거래 복기 대기';

  const summary = [
    `최근 ${performanceSnapshot.recentTradeCount}건 ${performanceSnapshot.recentNetPnlKrw >= 0 ? '+' : ''}${Math.round(performanceSnapshot.recentNetPnlKrw).toLocaleString()}원`,
    `평균 ${performanceSnapshot.recentAvgPnlPercent.toFixed(2)}%`,
    `연속 손실 ${performanceSnapshot.consecutiveLosses}`,
    quickLosses > 0 ? `빠른 손실 ${quickLosses}` : null,
    staleLosses + slowFlatLosses > 0 ? `정체 손실 ${staleLosses + slowFlatLosses}` : null,
  ].filter(Boolean).join(' · ');

  return {
    status: 'READY',
    source: 'AUTO',
    updatedAt: Date.now(),
    basedOnTradeCount: recentTrades.length,
    basedOnNetPnlKrw: recentTrades.reduce((sum, trade) => sum + trade.realizedPnl, 0),
    headline,
    summary,
    focusMarkets,
    avoidMarkets,
    preferredSetups: preferredSetups.slice(0, 4),
    avoidSetups: avoidSetups.slice(0, 4),
    adjustments: sanitizeStrategyReviewAdjustments(adjustments),
  };
}

export function sanitizeStrategyReviewAdjustments(adjustments: Partial<AiStrategyReviewAdjustments>): AiStrategyReviewAdjustments {
  return {
    expectancyOffsetPct: clampNumber(adjustments.expectancyOffsetPct ?? 0, -0.03, 0.08),
    minWinRateOffset: clampNumber(adjustments.minWinRateOffset ?? 0, -2, 4),
    riskRewardOffset: clampNumber(adjustments.riskRewardOffset ?? 0, -0.08, 0.2),
    spreadMultiplier: clampNumber(adjustments.spreadMultiplier ?? 1, 0.72, 1.2),
    gapMultiplier: clampNumber(adjustments.gapMultiplier ?? 1, 0.72, 1.2),
    crowdFlowOffset: clampNumber(adjustments.crowdFlowOffset ?? 0, -8, 12),
    minBuyConfidenceOffset: clampNumber(adjustments.minBuyConfidenceOffset ?? 0, -0.06, 0.1),
    finalistLimitOffset: Math.round(clampNumber(adjustments.finalistLimitOffset ?? 0, -3, 2)),
    maxHoldingMinutesOffset: Math.round(clampNumber(adjustments.maxHoldingMinutesOffset ?? 0, -12, 8)),
    earlyExitPnlFloorOffset: clampNumber(adjustments.earlyExitPnlFloorOffset ?? 0, -0.05, 0.08),
    staleLossMinutesOffset: Math.round(clampNumber(adjustments.staleLossMinutesOffset ?? 0, -6, 6)),
    staleFlatMinutesOffset: Math.round(clampNumber(adjustments.staleFlatMinutesOffset ?? 0, -8, 8)),
  };
}

export function mergeStrategyReflection(
  base: AiStrategyReflection,
  overlay: Partial<AiStrategyReflection>,
): AiStrategyReflection {
  return {
    ...base,
    ...overlay,
    status: overlay.status ?? base.status,
    source: overlay.source ?? base.source,
    updatedAt: overlay.updatedAt ?? Date.now(),
    focusMarkets: overlay.focusMarkets?.slice(0, 4) ?? base.focusMarkets,
    avoidMarkets: overlay.avoidMarkets?.slice(0, 4) ?? base.avoidMarkets,
    preferredSetups: overlay.preferredSetups?.slice(0, 4) ?? base.preferredSetups,
    avoidSetups: overlay.avoidSetups?.slice(0, 4) ?? base.avoidSetups,
    adjustments: sanitizeStrategyReviewAdjustments({
      ...base.adjustments,
      ...(overlay.adjustments ?? {}),
    }),
  };
}

export function buildEntryAggressionProfile(
  selectedAggression: AiEntryAggression,
  regime: AiPerformanceRegime
): AiEntryAggressionProfile {
  const effectiveAggression = resolveEffectiveAggression(selectedAggression, regime);
  return ENTRY_AGGRESSION_PROFILES[effectiveAggression] ?? ENTRY_AGGRESSION_PROFILES.BALANCED;
}

export function applyStrategyReflectionToAggressionProfile(
  profile: AiEntryAggressionProfile,
  reflection: AiStrategyReflection | null | undefined,
): AiEntryAggressionProfile {
  if (!reflection) return profile;
  const adjustments = reflection.adjustments;
  return {
    ...profile,
    finalistLimit: Math.round(clampNumber(profile.finalistLimit + adjustments.finalistLimitOffset, 3, 8)),
    minBuyConfidence: clampNumber(profile.minBuyConfidence + adjustments.minBuyConfidenceOffset, 0.44, 0.78),
    expectancyOffset: clampNumber(profile.expectancyOffset + adjustments.expectancyOffsetPct, -0.05, 0.18),
    winRateOffset: clampNumber(profile.winRateOffset + adjustments.minWinRateOffset, -2.5, 6),
    riskRewardOffset: clampNumber(profile.riskRewardOffset + adjustments.riskRewardOffset, -0.12, 0.28),
    gapMultiplier: clampNumber(profile.gapMultiplier * adjustments.gapMultiplier, 0.6, 1.35),
    spreadMultiplier: clampNumber(profile.spreadMultiplier * adjustments.spreadMultiplier, 0.6, 1.35),
    crowdFlowOffset: clampNumber(profile.crowdFlowOffset + adjustments.crowdFlowOffset, -12, 18),
  };
}

export function applyStrategyReflectionToTempoProfile(
  profile: AiTempoProfile,
  reflection: AiStrategyReflection | null | undefined,
): AiTempoProfile {
  if (!reflection) return profile;
  const adjustments = reflection.adjustments;
  return {
    ...profile,
    earlyExitPnlFloor: clampNumber(profile.earlyExitPnlFloor + adjustments.earlyExitPnlFloorOffset, -0.62, -0.04),
    staleLossMinutes: Math.round(clampNumber(profile.staleLossMinutes + adjustments.staleLossMinutesOffset, 6, 34)),
    staleFlatMinutes: Math.round(clampNumber(profile.staleFlatMinutes + adjustments.staleFlatMinutesOffset, 10, 46)),
  };
}

function resolveEffectiveAggression(
  selectedAggression: AiEntryAggression,
  regime: AiPerformanceRegime
): AiEntryAggression {
  if (regime === 'DEFENSIVE') {
    return 'CONSERVATIVE';
  }
  if (regime === 'CAUTIOUS') {
    if (selectedAggression === 'AGGRESSIVE') {
      return 'BALANCED';
    }
    return 'CONSERVATIVE';
  }
  return selectedAggression;
}

export function evaluateEntryGate(
  scanMarket: AiScalpScanMarket,
  tempoProfile: AiTempoProfile,
  aggressionProfile: AiEntryAggressionProfile
): string | null {
  const minExpectancyPct = Math.max(-0.01, tempoProfile.minEntryExpectancyPct + aggressionProfile.expectancyOffset);
  const minWinRate = tempoProfile.minEntryWinRate + aggressionProfile.winRateOffset;
  const minRiskReward = Math.max(0.95, tempoProfile.minEntryRiskReward + aggressionProfile.riskRewardOffset);
  const maxSpreadPct = tempoProfile.maxEntrySpreadPct * aggressionProfile.spreadMultiplier;
  const maxEntryGapPct = tempoProfile.maxEntryGapPct * aggressionProfile.gapMultiplier;
  const minCrowdFlow = Math.max(0, tempoProfile.minEntryCrowdFlow + aggressionProfile.crowdFlowOffset);
  const expectancyPct = scanMarket.recommendation.expectancyPct;
  if (expectancyPct < -0.02) {
    return `순기대값 ${expectancyPct.toFixed(2)}% 음수`;
  }

  const recommendedWinRate = scanMarket.recommendation.recommendedEntryWinRate;
  const riskRewardRatio = scanMarket.recommendation.riskRewardRatio;
  if (riskRewardRatio < 0.95) {
    return `RR ${riskRewardRatio.toFixed(2)} 과소`;
  }

  const spreadPercent = scanMarket.crowd?.spreadPercent ?? scanMarket.featurePack.spreadPercent ?? 0;
  if (spreadPercent > maxSpreadPct * 1.8) {
    return `스프레드 ${spreadPercent.toFixed(2)}% 과다`;
  }

  const entryGapPct = Math.max(0, scanMarket.featurePack.entryGapPct ?? 0);
  if (entryGapPct > maxEntryGapPct * 2.0) {
    return `진입 괴리 ${entryGapPct.toFixed(2)}% 과다`;
  }

  const crowdFlow = scanMarket.crowd?.flowScore ?? scanMarket.featurePack.crowdFlowScore ?? 0;
  const priceChange = scanMarket.changeRate;
  if (tempoProfile.key === 'DOLLAR_PEG' && priceChange >= 1.2 && entryGapPct >= 0.08) {
    return `달러 추종 자산 급등 추격 ${priceChange.toFixed(2)}%`;
  }

  if (priceChange >= 4.0 && entryGapPct >= 0.18) {
    return `급등 추격 구간 ${priceChange.toFixed(2)}%`;
  }

  let softPenalty = 0;
  const penaltyReasons: string[] = [];

  if (expectancyPct < minExpectancyPct) {
    softPenalty += expectancyPct < minExpectancyPct * 0.5 ? 1.35 : 0.75;
    penaltyReasons.push(`exp ${expectancyPct.toFixed(2)}%`);
  }

  if (recommendedWinRate < minWinRate) {
    softPenalty += recommendedWinRate < minWinRate - 2.0 ? 1.0 : 0.55;
    penaltyReasons.push(`승률 ${recommendedWinRate.toFixed(1)}%`);
  }

  if (riskRewardRatio < minRiskReward) {
    softPenalty += riskRewardRatio < minRiskReward - 0.15 ? 1.0 : 0.55;
    penaltyReasons.push(`RR ${riskRewardRatio.toFixed(2)}`);
  }

  if (spreadPercent > maxSpreadPct) {
    softPenalty += spreadPercent > maxSpreadPct * 1.35 ? 0.95 : 0.45;
    penaltyReasons.push(`spread ${spreadPercent.toFixed(2)}%`);
  }

  if (entryGapPct > maxEntryGapPct) {
    softPenalty += entryGapPct > maxEntryGapPct * 1.4 ? 0.95 : 0.45;
    penaltyReasons.push(`gap ${entryGapPct.toFixed(2)}%`);
  }

  if (crowdFlow > 0 && crowdFlow < minCrowdFlow) {
    softPenalty += 0.35;
    penaltyReasons.push(`flow ${crowdFlow.toFixed(0)}`);
  }

  const strongOverride =
    expectancyPct >= minExpectancyPct + 0.08 &&
    recommendedWinRate >= minWinRate + 2.5 &&
    riskRewardRatio >= minRiskReward + 0.12;

  const hardSoftPenaltyLimit = 2.4 + aggressionProfile.softPenaltyAllowance;
  const weakSoftPenaltyLimit = 1.6 + aggressionProfile.softPenaltyAllowance * 0.5;

  if (softPenalty >= hardSoftPenaltyLimit && !strongOverride) {
    return `소프트게이트 초과 · ${penaltyReasons.join(' · ')}`;
  }

  if (softPenalty >= weakSoftPenaltyLimit && expectancyPct < minExpectancyPct + 0.03 && !strongOverride) {
    return `진입 근거 약함 · ${penaltyReasons.join(' · ')}`;
  }

  return null;
}

export function evaluateDeterministicExit(
  position: GuidedTradePosition,
  holdingMinutes: number,
  tempoProfile: AiTempoProfile,
  peakNetPnl: number
): AiDeterministicExitDecision | null {
  const netPnlPercent = position.unrealizedPnlPercent;
  const giveBack = peakNetPnl - netPnlPercent;
  const staleLossMinutes = tempoProfile.staleLossMinutes;
  const staleFlatMinutes = tempoProfile.staleFlatMinutes;

  if (netPnlPercent <= -0.62 && holdingMinutes >= 1.0) {
    return {
      reason: 'AI 하드 손실 가드 발동',
      exitReasonCode: 'AI_STOP_LOSS',
      category: 'STOP_LOSS',
      confidence: 0.96,
      urgency: 'HIGH',
    };
  }

  if (holdingMinutes >= staleLossMinutes && netPnlPercent <= -0.12) {
    return {
      reason: `${holdingMinutes.toFixed(1)}분째 follow-through 부재로 정체 손실 청산`,
      exitReasonCode: 'AI_STALE_LOSER',
      category: 'LLM_EXIT',
      confidence: 0.84,
      urgency: 'MEDIUM',
    };
  }

  if (holdingMinutes >= staleFlatMinutes && netPnlPercent < 0.10) {
    return {
      reason: `${holdingMinutes.toFixed(1)}분 경과에도 수익 전개가 약해 시간 청산`,
      exitReasonCode: 'AI_STALE_FLAT',
      category: 'LLM_EXIT',
      confidence: 0.82,
      urgency: 'MEDIUM',
    };
  }

  if (peakNetPnl >= 0.28 && giveBack >= 0.22 && netPnlPercent <= Math.max(0.05, peakNetPnl * 0.55)) {
    return {
      reason: `최고 ${formatPercentValue(peakNetPnl)} 후 이익 반납 ${formatPercentValue(giveBack)}로 보호 청산`,
      exitReasonCode: 'AI_PROFIT_FADE',
      category: 'TAKE_PROFIT',
      confidence: 0.9,
      urgency: 'HIGH',
    };
  }

  return null;
}
