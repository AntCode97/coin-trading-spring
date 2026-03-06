import type {
  CandlestickData,
  Time,
  UTCTimestamp,
} from 'lightweight-charts';
import {
  getApiBaseUrl,
  type AutopilotOpportunityProfile,
  type GuidedChartResponse,
  type GuidedCandle,
  type GuidedMarketSortBy,
} from '../api';
import type { AutopilotTimelineEvent } from '../lib/autopilot/AutopilotOrchestrator';
import type {
  FocusedScalpEntryState,
  FocusedScalpManageState,
} from './autopilot/FocusedScalpLiveDock';
import type { AutopilotState } from '../lib/autopilot/AutopilotOrchestrator';
import {
  CODEX_MODELS,
  ZAI_MODELS,
  type CodexModelId,
  type ZaiModelId,
  type LlmProviderId,
  type ZaiEndpointMode,
  type DelegationMode,
  type TradingMode,
} from '../lib/llmService';
import type { WorkspaceDensity } from './workspace-v2/types';

// ── Constants ──

export const KRW_FORMATTER = new Intl.NumberFormat('ko-KR');
export const STORAGE_KEY = 'guided-trader.preferences.v1';
export const STRATEGY_CODE_SCALP = 'GUIDED_AUTOPILOT_SCALP';
export const STRATEGY_CODE_SWING = 'GUIDED_AUTOPILOT_SWING';
export const STRATEGY_CODE_POSITION = 'GUIDED_AUTOPILOT_POSITION';
export const INVEST_MAJOR_MARKETS = ['KRW-BTC', 'KRW-ETH', 'KRW-SOL', 'KRW-XRP', 'KRW-ADA'];
export const DEFAULT_AUTOPILOT_CAPITAL_POOL_KRW = 300_000;
export const DEFAULT_AMOUNT_PRESETS = [10000, 20000, 50000, 100000];
export const MIN_ORDER_AMOUNT_KRW = 5_100;
export const ENGINE_CAPITAL_RATIOS = {
  SCALP: 0.4,
  SWING: 0.35,
  POSITION: 0.25,
} as const;

// ── Types ──

export type WorkspacePrefs = {
  selectedMarket?: string;
  interval?: string;
  sortBy?: GuidedMarketSortBy;
  sortDirection?: 'ASC' | 'DESC';
  aiRefreshSec?: number;
  tradingMode?: TradingMode;
  autopilotEnabled?: boolean;
  swingAutopilotEnabled?: boolean;
  positionAutopilotEnabled?: boolean;
  dailyLossLimitKrw?: number;
  autopilotMaxConcurrentPositions?: number;
  autopilotAmountKrw?: number;
  autopilotCapitalPoolKrw?: number;
  amountPresets?: number[];
  defaultAmountKrw?: number;
  autopilotInterval?: string;
  autopilotMode?: TradingMode;
  opportunityProfile?: AutopilotOpportunityProfile;
  entryPolicy?: 'BALANCED' | 'AGGRESSIVE' | 'CONSERVATIVE';
  entryOrderMode?: 'ADAPTIVE' | 'MARKET' | 'LIMIT';
  pendingEntryTimeoutSec?: number;
  marketFallbackAfterCancel?: boolean;
  playwrightEnabled?: boolean;
  playwrightAutoStart?: boolean;
  playwrightMcpPort?: number;
  playwrightMcpUrl?: string;
  winRateThresholdMode?: 'DYNAMIC_P70' | 'FIXED';
  fixedMinRecommendedWinRate?: number;
  fixedMinMarketWinRate?: number;
  minLlmConfidence?: number;
  llmDailyTokenCap?: number;
  llmRiskReserveTokens?: number;
  rejectCooldownSeconds?: number;
  rejectCooldownMinutes?: number;
  postExitCooldownMinutes?: number;
  focusedScalpEnabled?: boolean;
  focusedScalpMarkets?: string[];
  focusedScalpPollIntervalSec?: number;
  llmProvider?: LlmProviderId;
  openAiModel?: CodexModelId;
  zaiModel?: ZaiModelId;
  zaiEndpointMode?: ZaiEndpointMode;
  delegationMode?: DelegationMode;
  workspaceDensity?: WorkspaceDensity;
  actionConsoleOpen?: boolean;
  chatModel?: string; // legacy
};

export type ConnectPlaywrightOptions = {
  allowAutoStart?: boolean;
};

export type FocusedScalpContextMenu = {
  x: number;
  y: number;
  market: string;
  koreanName: string;
  included: boolean;
};

export type PositionState = 'NONE' | 'PENDING' | 'OPEN' | 'DANGER';

// ── Autopilot State Factory ──

export function createInitialAutopilotState(): AutopilotState {
  return {
    enabled: false,
    blockedByDailyLoss: false,
    blockedReason: null,
    startedAt: null,
    thresholdMode: 'DYNAMIC_P70',
    appliedRecommendedWinRateThreshold: 0,
    workers: [],
    logs: [],
    events: [],
    candidates: [],
    decisionBreakdown: {
      autoPass: 0,
      borderline: 0,
      ruleFail: 0,
    },
    llmBudget: {
      dailyTokenCap: 200_000,
      usedTokens: 0,
      remainingTokens: 200_000,
      entryBudgetTotal: 160_000,
      entryUsedTokens: 0,
      entryRemainingTokens: 160_000,
      riskReserveTokens: 40_000,
      riskUsedTokens: 0,
      riskRemainingTokens: 40_000,
      fallbackMode: false,
    },
    focusedScalp: {
      enabled: false,
      markets: [],
      pollIntervalSec: 20,
    },
    orderFlowLocal: {
      buyRequested: 0,
      buyFilled: 0,
      sellRequested: 0,
      sellFilled: 0,
      pending: 0,
      cancelled: 0,
    },
    screenshots: [],
  };
}

// ── Formatting ──

export function formatVolume(value: number): string {
  if (value >= 1_0000_0000_0000) return `${(value / 1_0000_0000_0000).toFixed(1)}조`;
  if (value >= 1_0000_0000) return `${(value / 1_0000_0000).toFixed(0)}억`;
  if (value >= 1_0000_0000 * 0.1) return `${(value / 1_0000_0000).toFixed(1)}억`;
  if (value >= 1_0000) return `${(value / 1_0000).toFixed(0)}만`;
  return KRW_FORMATTER.format(Math.round(value));
}

export function formatKrw(value: number): string {
  if (!Number.isFinite(value)) return '-';
  if (Math.abs(value) >= 1_000_000) {
    return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}원`;
  }
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 10 })}원`;
}

export function formatCompactKrw(value: number): string {
  if (!Number.isFinite(value)) return '-';
  const abs = Math.abs(value);
  const sign = value < 0 ? '-' : '';
  if (abs >= 100_000_000) {
    return `${sign}${(abs / 100_000_000).toFixed(abs >= 1_000_000_000 ? 0 : 1)}억`;
  }
  if (abs >= 10_000) {
    return `${sign}${(abs / 10_000).toFixed(abs >= 1_000_000 ? 0 : 1)}만`;
  }
  return `${sign}${Math.round(abs).toLocaleString('ko-KR')}원`;
}

export function clampPercent(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(100, Math.round(value)));
}

export function formatPlain(value: number): string {
  if (!Number.isFinite(value)) return '-';
  if (Math.abs(value) >= 1_000_000) {
    return value.toLocaleString('ko-KR', { maximumFractionDigits: 2 });
  }
  return value.toLocaleString('ko-KR', { maximumFractionDigits: 10 });
}

export function formatPct(value: number): string {
  return `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`;
}

export function formatWinRate(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '-';
  return `${value.toFixed(1)}%`;
}

export function formatAmountPreset(value: number): string {
  if (value >= 10_000) {
    const unit = value / 10_000;
    return Number.isInteger(unit) ? `${unit.toFixed(0)}만` : `${unit.toFixed(1)}만`;
  }
  return KRW_FORMATTER.format(value);
}

export function formatKstTime(epochSec: number): string {
  const d = new Date(epochSec * 1000);
  return d.toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit' });
}

export function formatKstDateTime(epochSec: number): string {
  const d = new Date(epochSec * 1000);
  return d.toLocaleDateString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function formatOrderDateTime(value?: string | null): string {
  if (!value) return '-';
  const parsed = new Date(value);
  if (!Number.isFinite(parsed.getTime())) return value;
  return parsed.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

// ── Amount Parsing ──

export function clampAmount(value: unknown, fallback: number): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.max(MIN_ORDER_AMOUNT_KRW, Math.round(parsed));
}

export function normalizeAmountPresets(raw: unknown): number[] {
  const source = Array.isArray(raw)
    ? raw
    : typeof raw === 'string'
      ? raw.split(',')
      : DEFAULT_AMOUNT_PRESETS;
  const values = source
    .map((item) => clampAmount(item, Number.NaN))
    .filter((value) => Number.isFinite(value) && value >= MIN_ORDER_AMOUNT_KRW)
    .sort((left, right) => left - right);
  const deduped = [...new Set(values)];
  return deduped.length > 0 ? deduped : Array.from(DEFAULT_AMOUNT_PRESETS);
}

const AMOUNT_PRESET_TOKEN_RE = /\d+(?:\.\d+)?(?:,\d{3})*(?:\s*(?:만원|만|천|천원|백만|억|원))?(?=\s|$|[,;|\n])/g;
const AMOUNT_PRESET_UNIT_MULTIPLIERS: Record<string, number> = {
  만: 10_000,
  만원: 10_000,
  천: 1_000,
  천원: 1_000,
  백만: 1_000_000,
  억: 100_000_000,
  원: 1,
};

function parseAmountPresetToken(rawToken: string): number | null {
  const trimmed = rawToken.replace(/\s+/g, '');
  const match = trimmed.match(/^([0-9]+(?:\.[0-9]+)?(?:,\d{3})*)\s*(만|만원|천|천원|백만|억|원)?$/);
  if (!match) return null;

  const numberPart = Number(match[1].replace(/,/g, ''));
  if (!Number.isFinite(numberPart)) return null;

  const unit = match[2] ?? '';
  const multiplier = AMOUNT_PRESET_UNIT_MULTIPLIERS[unit] ?? 1;
  return clampAmount(numberPart * multiplier, Number.NaN);
}

export function parseAmountPresetInput(raw: string): number[] {
  const tokenizedValues = raw.match(AMOUNT_PRESET_TOKEN_RE) ?? [];
  const values = tokenizedValues
    .map(parseAmountPresetToken)
    .filter((value): value is number => Number.isFinite(value))
    .filter((value) => Number.isFinite(value) && value >= MIN_ORDER_AMOUNT_KRW)
    .sort((left, right) => left - right);

  const deduped = [...new Set(values)];
  return deduped.length > 0 ? deduped : [DEFAULT_AMOUNT_PRESETS[0]];
}

// ── Preferences ──

const OPENAI_MODEL_SET = new Set<string>(CODEX_MODELS.map((model) => model.id));
const ZAI_MODEL_SET = new Set<string>(ZAI_MODELS.map((model) => model.id));

export function isCodexModelId(value: unknown): value is CodexModelId {
  return typeof value === 'string' && OPENAI_MODEL_SET.has(value);
}

export function isZaiModelId(value: unknown): value is ZaiModelId {
  return typeof value === 'string' && ZAI_MODEL_SET.has(value);
}

export function normalizeLlmProvider(value: unknown): LlmProviderId {
  return value === 'zai' ? 'zai' : 'openai';
}

export function normalizeZaiEndpointMode(value: unknown): ZaiEndpointMode {
  return value === 'general' ? 'general' : 'coding';
}

export function normalizeDelegationMode(value: unknown): DelegationMode {
  if (value === 'AUTO_ONLY' || value === 'MANUAL_ONLY' || value === 'AUTO_AND_MANUAL') {
    return value;
  }
  return 'AUTO_AND_MANUAL';
}

export function migrateWorkspacePrefs(prefs: WorkspacePrefs): WorkspacePrefs {
  const next: WorkspacePrefs = { ...prefs };
  const hasEngineSplitPrefs =
    typeof prefs.swingAutopilotEnabled === 'boolean' ||
    typeof prefs.positionAutopilotEnabled === 'boolean' ||
    typeof prefs.autopilotCapitalPoolKrw === 'number';

  if (!hasEngineSplitPrefs) {
    const legacyEnabled = prefs.autopilotEnabled ?? false;
    const legacyMode = prefs.autopilotMode ?? 'SCALP';
    if (legacyEnabled) {
      if (legacyMode === 'SWING') {
        next.autopilotEnabled = false;
        next.swingAutopilotEnabled = true;
        next.positionAutopilotEnabled = false;
      } else if (legacyMode === 'POSITION') {
        next.autopilotEnabled = false;
        next.swingAutopilotEnabled = false;
        next.positionAutopilotEnabled = true;
      } else {
        next.autopilotEnabled = true;
        next.swingAutopilotEnabled = false;
        next.positionAutopilotEnabled = false;
      }
    } else {
      next.swingAutopilotEnabled = false;
      next.positionAutopilotEnabled = false;
    }

    const legacyAmountKrw = Math.max(MIN_ORDER_AMOUNT_KRW, Math.round(prefs.autopilotAmountKrw ?? 10_000));
    const legacyMaxPositions = Math.min(10, Math.max(1, prefs.autopilotMaxConcurrentPositions ?? 6));
    next.autopilotCapitalPoolKrw = Math.max(
      DEFAULT_AUTOPILOT_CAPITAL_POOL_KRW,
      legacyAmountKrw * legacyMaxPositions
    );
  }

  next.amountPresets = normalizeAmountPresets(next.amountPresets);
  const preferredDefaultAmount = clampAmount(
    next.defaultAmountKrw ?? next.amountPresets[0],
    next.amountPresets[0] ?? DEFAULT_AMOUNT_PRESETS[0]
  );
  next.defaultAmountKrw = preferredDefaultAmount;
  if (!next.amountPresets.includes(preferredDefaultAmount)) {
    next.amountPresets = [
      preferredDefaultAmount,
      ...next.amountPresets.filter((preset) => preset !== preferredDefaultAmount),
    ];
  }

  next.llmProvider = normalizeLlmProvider(next.llmProvider);
  const legacyOpenAiModel = isCodexModelId(next.chatModel) ? next.chatModel : undefined;
  next.openAiModel = isCodexModelId(next.openAiModel) ? next.openAiModel : (legacyOpenAiModel ?? 'gpt-4');
  next.zaiModel = isZaiModelId(next.zaiModel) ? next.zaiModel : 'glm-4.7-flash';
  next.zaiEndpointMode = normalizeZaiEndpointMode(next.zaiEndpointMode);
  next.delegationMode = normalizeDelegationMode(next.delegationMode);
  next.opportunityProfile = next.opportunityProfile === 'CROWD_PRESSURE' ? 'CROWD_PRESSURE' : 'CLASSIC';

  return next;
}

export function loadPrefs(): WorkspacePrefs {
  if (typeof window === 'undefined') return {};
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as WorkspacePrefs;
    return migrateWorkspacePrefs(parsed);
  } catch {
    return {};
  }
}

// ── Focused Scalp Helpers ──

export function normalizeFocusedScalpMarket(raw: string): string | null {
  const token = raw.trim().toUpperCase();
  if (!token) return null;
  const market = token.startsWith('KRW-') ? token : `KRW-${token}`;
  const symbol = market.slice(4);
  if (!symbol || !/^[A-Z0-9]+$/.test(symbol)) return null;
  return market;
}

export function normalizeFocusedScalpMarkets(raw: string): { markets: string[]; invalidTokens: string[] } {
  const tokens = raw
    .split(/[,\s]+/)
    .map((token) => token.trim().toUpperCase())
    .filter((token) => token.length > 0);
  const markets: string[] = [];
  const invalidTokens: string[] = [];
  const seen = new Set<string>();
  let overflowed = false;
  for (const token of tokens) {
    const market = normalizeFocusedScalpMarket(token);
    if (!market) {
      invalidTokens.push(token);
      continue;
    }
    if (seen.has(market)) continue;
    seen.add(market);
    if (markets.length >= 8) {
      overflowed = true;
      continue;
    }
    markets.push(market);
  }
  if (overflowed) {
    invalidTokens.push('MAX_8_EXCEEDED');
  }
  return { markets, invalidTokens };
}

export function classifyFocusedEntryEvent(event?: AutopilotTimelineEvent): {
  state: FocusedScalpEntryState;
  label: string;
  detail: string;
  at: number | null;
} {
  if (!event) {
    return { state: 'NONE', label: '진입 판단 없음', detail: '-', at: null };
  }
  if (event.action === 'ENTRY_SUCCESS' || event.action === 'BUY_FILLED') {
    return { state: 'ENTERED', label: '진입 완료', detail: event.detail, at: event.at };
  }
  if (event.action === 'BUY_REQUESTED') {
    return { state: 'PENDING', label: '진입 주문 진행', detail: event.detail, at: event.at };
  }
  if (event.action === 'LLM_REJECT') {
    return { state: 'NO_ENTRY', label: '진입 보류', detail: event.detail, at: event.at };
  }
  if (event.action === 'ENTRY_FAILED' || event.action === 'CANCELLED') {
    return { state: 'NO_ENTRY', label: '진입 실패', detail: event.detail, at: event.at };
  }
  return { state: 'NONE', label: event.action, detail: event.detail, at: event.at };
}

export function classifyFocusedManageEvent(event?: AutopilotTimelineEvent): {
  state: FocusedScalpManageState;
  label: string;
  detail: string;
  at: number | null;
} {
  if (!event) {
    return { state: 'NONE', label: '관리 판단 없음', detail: '-', at: null };
  }
  const detailUpper = event.detail.toUpperCase();
  if (event.action === 'POSITION_REVIEW' && detailUpper.startsWith('HOLD')) {
    return { state: 'HOLD', label: '유지 판단', detail: event.detail, at: event.at };
  }
  if (event.action === 'PARTIAL_TP' || event.detail.includes('익절') || detailUpper.includes('PARTIAL_TP')) {
    return { state: 'TAKE_PROFIT', label: '익절 실행', detail: event.detail, at: event.at };
  }
  if (
    event.action === 'POSITION_EXIT' &&
    (event.detail.includes('손절') || event.detail.includes('강제청산') || event.detail.includes('조기청산'))
  ) {
    return { state: 'STOP_LOSS', label: '손절/강제청산', detail: event.detail, at: event.at };
  }
  if (event.action === 'POSITION_EXIT') {
    return { state: 'TAKE_PROFIT', label: '포지션 청산', detail: event.detail, at: event.at };
  }
  if (event.action === 'SUPERVISION' || event.action === 'SELL_REQUESTED' || event.action === 'SELL_FILLED') {
    return { state: 'SUPERVISION', label: '감시/청산 진행', detail: event.detail, at: event.at };
  }
  return { state: 'NONE', label: event.action, detail: event.detail, at: event.at };
}

// ── Chart Utilities ──

export function asUtc(secondsOrMillis: number): UTCTimestamp {
  const sec = secondsOrMillis > 10_000_000_000 ? Math.floor(secondsOrMillis / 1000) : Math.floor(secondsOrMillis);
  return sec as UTCTimestamp;
}

export function intervalSeconds(interval: string): number {
  if (interval === 'tick') return 1;
  if (interval === 'day') return 86400;
  if (interval.startsWith('minute')) {
    const minute = Number(interval.replace('minute', ''));
    if (Number.isFinite(minute) && minute > 0) return minute * 60;
  }
  return 60;
}

export function toBucketStart(epochSec: number, interval: string): number {
  const step = intervalSeconds(interval);
  return Math.floor(epochSec / step) * step;
}

export function toCandlestick(candle: GuidedCandle): CandlestickData<Time> {
  return {
    time: asUtc(candle.timestamp),
    open: candle.open,
    high: candle.high,
    low: candle.low,
    close: candle.close,
  };
}

export function buildFallbackCandle(payload: GuidedChartResponse, interval: string): CandlestickData<Time> | null {
  const anchorPrice =
    payload.recommendation.currentPrice > 0
      ? payload.recommendation.currentPrice
      : payload.recommendation.recommendedEntryPrice;
  if (!Number.isFinite(anchorPrice) || anchorPrice <= 0) return null;

  const nowSec = Math.floor(Date.now() / 1000);
  const bucket = toBucketStart(nowSec, interval);
  return {
    time: asUtc(bucket),
    open: anchorPrice,
    high: anchorPrice,
    low: anchorPrice,
    close: anchorPrice,
  };
}

export function computeVisiblePriceRange(
  payload: GuidedChartResponse,
  candles: CandlestickData<Time>[]
): { from: number; to: number } | null {
  const values: number[] = [];
  for (const candle of candles) {
    values.push(candle.open, candle.high, candle.low, candle.close);
  }

  values.push(
    payload.recommendation.currentPrice,
    payload.recommendation.recommendedEntryPrice,
    payload.recommendation.stopLossPrice,
    payload.recommendation.takeProfitPrice
  );

  if (payload.activePosition) {
    values.push(
      payload.activePosition.averageEntryPrice,
      payload.activePosition.stopLossPrice,
      payload.activePosition.takeProfitPrice
    );
    if (payload.activePosition.trailingStopPrice != null) {
      values.push(payload.activePosition.trailingStopPrice);
    }
  }

  if (payload.recommendation.keyLevels) {
    for (const level of payload.recommendation.keyLevels) {
      values.push(level.price);
    }
  }

  const finite = values.filter((value) => Number.isFinite(value) && value > 0);
  if (finite.length === 0) return null;

  const minPrice = Math.min(...finite);
  const maxPrice = Math.max(...finite);
  if (!Number.isFinite(minPrice) || !Number.isFinite(maxPrice)) return null;

  const spread = Math.max(maxPrice - minPrice, maxPrice * 0.002);
  const padding = spread * 0.2;
  const from = Math.max(0.00000001, minPrice - padding);
  const to = maxPrice + padding;
  if (!(to > from)) return null;

  return { from, to };
}

// ── Order Utilities ──

export function normalizeOrderSide(side?: string | null): 'BUY' | 'SELL' | 'UNKNOWN' {
  const normalized = side?.trim().toLowerCase();
  if (normalized === 'bid' || normalized === 'buy') return 'BUY';
  if (normalized === 'ask' || normalized === 'sell') return 'SELL';
  return 'UNKNOWN';
}

export function orderStateLabel(state?: string | null): string {
  const normalized = state?.trim().toLowerCase();
  if (normalized === 'done') return '체결';
  if (normalized === 'wait') return '대기';
  if (normalized === 'cancel') return '취소';
  return normalized ? normalized.toUpperCase() : '-';
}

export function orderTypeLabel(ordType?: string | null): string {
  const normalized = ordType?.trim().toLowerCase();
  if (normalized === 'limit') return '지정가';
  if (normalized === 'price' || normalized === 'market') return '시장가';
  return ordType ? ordType.toUpperCase() : '-';
}

// ── Misc ──

export function isWinRateSort(sortBy: GuidedMarketSortBy): boolean {
  return sortBy === 'RECOMMENDED_ENTRY_WIN_RATE' || sortBy === 'MARKET_ENTRY_WIN_RATE';
}

export function keyLevelColor(label: string): string {
  if (label.startsWith('SMA20')) return '#7dd3fc';
  if (label.startsWith('SMA60')) return '#c4b5fd';
  if (label.includes('지지')) return '#86efac';
  return '#a0a0a0';
}

export function derivePositionState(
  activePosition: { status: string; stopLossPrice: number; averageEntryPrice: number } | null | undefined,
  currentPrice: number
): PositionState {
  if (!activePosition) return 'NONE';
  if (activePosition.status === 'PENDING_ENTRY') return 'PENDING';
  if (activePosition.status === 'OPEN') {
    const slDistance = Math.abs(currentPrice - activePosition.stopLossPrice) / currentPrice * 100;
    if (slDistance <= 1.0) return 'DANGER';
    return 'OPEN';
  }
  return 'NONE';
}

export function deriveMcpUrl(): string {
  const apiBase = getApiBaseUrl().replace(/\/$/, '');
  return apiBase.replace(/\/api$/, '/mcp');
}

export function derivePlaywrightMcpUrl(port: number): string {
  return `http://127.0.0.1:${port}/mcp`;
}
