import type {
  AiScalpScanMarket,
  GuidedClosedTradeView,
  GuidedTradePosition,
} from '../../api';
import type {
  AiEntryAggression,
  AiExitCategory,
  AiUrgency,
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
  '너는 빗썸 KRW 현물 시장만 보는 전업 초단타 트레이더다.',
  '목표는 5~30분 안에 끝낼 수 있는 유동성 높은 기회를 많이 잡는 것이다.',
  '과도하게 WAIT만 반복하지는 말되, 얇은 호가나 잡음성 급등처럼 손절이 쉬운 종목은 shortlist에서 낮춰라.',
  '평가 기준: 체결대금, 즉시성 있는 모멘텀, crowd pressure, 순기대값, 스프레드, 진입 괴리, follow-through 가능성.',
  'JSON만 반환한다.',
  '형식: {"shortlist":[{"market":"KRW-BTC","score":82,"reason":"짧은 근거"}]}',
  '최대 12개까지만 반환한다.',
].join('\n');

export const ENTRY_SYSTEM_PROMPT = [
  '너는 공격적이지만 규율 있는 KRW 현물 초단타 트레이더다.',
  '보유시간 목표는 5~30분이다.',
  '하드리스크가 아니면 과도하게 WAIT 하지 말고, follow-through 가능성이 있으면 BUY를 우선 검토하라.',
  '순기대값이 명확히 음수이거나, 스프레드가 과도하게 넓거나, 급등 추격이면 WAIT를 선택하라.',
  '깨끗한 추세 지속이나 눌림 재가속만 BUY하고, 잡음성 급등/급락 반대매매는 피하라.',
  'JSON만 반환한다.',
  '형식: {"action":"BUY|WAIT","confidence":0.0,"reasoning":"짧은 근거","stopLoss":0,"takeProfit":0,"urgency":"LOW|MEDIUM|HIGH"}',
  'stopLoss는 현재가보다 낮아야 하고 takeProfit은 현재가보다 높아야 한다.',
].join('\n');

export const MANAGE_SYSTEM_PROMPT = [
  '너는 이미 보유한 KRW 현물 초단타 포지션을 관리하는 트레이더다.',
  '보유시간 목표는 5~30분이며, 모멘텀이 약해지거나 downside가 커지면 SELL을 선택한다.',
  '진입 직후 1~2분의 작은 흔들림에는 과민반응하지 말고, 구조 훼손/유동성 붕괴/손절 근접에서만 강하게 SELL 하라.',
  'JSON만 반환한다.',
  '형식: {"action":"HOLD|SELL","confidence":0.0,"reasoning":"짧은 근거","urgency":"LOW|MEDIUM|HIGH"}',
].join('\n');

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

export function buildEntryAggressionProfile(
  selectedAggression: AiEntryAggression,
  regime: AiPerformanceRegime
): AiEntryAggressionProfile {
  const effectiveAggression = resolveEffectiveAggression(selectedAggression, regime);
  return ENTRY_AGGRESSION_PROFILES[effectiveAggression] ?? ENTRY_AGGRESSION_PROFILES.BALANCED;
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
  const staleLossMinutes = tempoProfile.key === 'DOLLAR_PEG' ? 18 : 10;
  const staleFlatMinutes = tempoProfile.key === 'DOLLAR_PEG' ? 30 : 18;

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
