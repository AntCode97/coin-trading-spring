import axios from 'axios';

const api = axios.create({
  baseURL: '/api',
  timeout: 10000,
});

// Long-running API 클라이언트 (LLM 최적화, 회고 등은 10분 타임아웃)
const longRunningApi = axios.create({
  baseURL: '/api',
  timeout: 600000, // 10분
});

export interface CoinAsset {
  symbol: string;
  quantity: number;
  avgPrice: number;
  currentPrice: number;
  value: number;
  pnl: number;
  pnlPercent: number;
}

export interface PositionInfo {
  market: string;
  strategy: string;
  entryPrice: number;
  currentPrice: number;
  quantity: number;
  value: number;
  pnl: number;
  pnlPercent: number;
  takeProfitPrice: number;
  stopLossPrice: number;
  entryTime: string;
  peakPrice?: number;
}

export interface ClosedTradeInfo {
  market: string;
  strategy: string;
  entryPrice: number;
  exitPrice: number;
  quantity: number;
  entryTime: string;
  entryTimeFormatted: string;
  exitTime: string;
  exitTimeFormatted: string;
  holdingMinutes: number;
  pnlAmount: number;
  pnlPercent: number;
  exitReason: string;
}

export interface StatsInfo {
  totalTrades: number;
  winCount: number;
  lossCount: number;
  totalPnl: number;
  winRate: number;
  totalInvested: number;  // 트레이딩에 사용한 총 금액
  roi: number;            // 트레이딩 금액 대비 수익률 (%)
}

export interface DashboardData {
  krwBalance: number;
  totalAssetKrw: number;
  coinAssets: CoinAsset[];
  openPositions: PositionInfo[];
  todayTrades: ClosedTradeInfo[];
  todayStats: StatsInfo;
  totalStats: StatsInfo;
  currentDateStr: string;
  requestDate: string;  // YYYY-MM-DD format
  activeStrategies: StrategyInfo[];
}

export interface StrategyInfo {
  name: string;
  description: string;
  code: string;
}

export interface SyncResult {
  success: boolean;
  message: string;
  actions: SyncAction[];
  fixedCount: number;
  verifiedCount: number;
}

export interface SyncAction {
  market: string;
  strategy: string;
  action: string;
  reason: string;
  dbQuantity: number;
  actualQuantity: number;
}

export interface SystemControlResult {
  success: boolean;
  message: string;
  data?: Record<string, unknown>;
}

export interface FundingStatus {
  enabled: boolean;
  autoTradingEnabled: boolean;
  openPositionsCount: number;
  openPositions: FundingPositionInfo[];
  totalPnl: number;
  lastCheckTime: string;
}

export interface FundingPositionInfo {
  id: number;
  symbol: string;
  spotPrice: number | null;
  perpPrice: number | null;
  entryTime: string;
  fundingRate: number | null;
  totalFundingReceived: number;
  netPnl: number | null;
  status: string;
}

export interface FundingOpportunity {
  exchange: string;
  symbol: string;
  fundingRate: string;
  annualizedRate: string;
  nextFundingTime: string;
  minutesUntilFunding: number;
  markPrice: number;
  indexPrice: number;
  isRecommendedEntry: boolean;
}

export interface FundingScanResult {
  scanTime: string;
  totalOpportunities: number;
  opportunities: FundingOpportunity[];
}

export interface FundingConfig {
  enabled: boolean;
  autoTradingEnabled: boolean;
  monitoringIntervalMs: number;
  minAnnualizedRate: number;
  maxMinutesUntilFunding: number;
  maxCapitalRatio: number;
  maxSinglePositionKrw: number;
  maxPositions: number;
  maxLeverage: number;
  highRateAlertThreshold: number;
  symbols: string[];
}

type ApiObject = Record<string, unknown>;

function asApiObject(value: unknown): ApiObject {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return {};
  }
  return value as ApiObject;
}

function toNumber(value: unknown, fallback = 0): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === 'string' && value.trim().length > 0) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return fallback;
}

function toNullableNumber(value: unknown): number | null {
  if (value === null || value === undefined) {
    return null;
  }
  return toNumber(value, 0);
}

function toBoolean(value: unknown, fallback = false): boolean {
  if (typeof value === 'boolean') {
    return value;
  }
  return fallback;
}

function toStringValue(value: unknown, fallback = ''): string {
  if (typeof value === 'string') {
    return value;
  }
  return fallback;
}

function normalizeFundingPosition(raw: unknown): FundingPositionInfo {
  const position = asApiObject(raw);
  return {
    id: toNumber(position.id, 0),
    symbol: toStringValue(position.symbol),
    spotPrice: toNullableNumber(position.spotPrice),
    perpPrice: toNullableNumber(position.perpPrice),
    entryTime: toStringValue(position.entryTime),
    fundingRate: toNullableNumber(position.fundingRate),
    totalFundingReceived: toNumber(position.totalFundingReceived, 0),
    netPnl: toNullableNumber(position.netPnl),
    status: toStringValue(position.status),
  };
}

function normalizeFundingOpportunity(raw: unknown): FundingOpportunity {
  const opportunity = asApiObject(raw);
  return {
    exchange: toStringValue(opportunity.exchange),
    symbol: toStringValue(opportunity.symbol),
    fundingRate: toStringValue(opportunity.fundingRate),
    annualizedRate: toStringValue(opportunity.annualizedRate),
    nextFundingTime: toStringValue(opportunity.nextFundingTime),
    minutesUntilFunding: toNumber(opportunity.minutesUntilFunding, 0),
    markPrice: toNumber(opportunity.markPrice, 0),
    indexPrice: toNumber(opportunity.indexPrice, 0),
    isRecommendedEntry: toBoolean(opportunity.isRecommendedEntry, false),
  };
}

function normalizeFundingStatus(raw: unknown): FundingStatus {
  const status = asApiObject(raw);
  const openPositionsRaw = Array.isArray(status.openPositions) ? status.openPositions : [];
  const openPositions = openPositionsRaw.map((position) => normalizeFundingPosition(position));
  const totalPnlRaw = status.totalPnl ?? status.totalPnL ?? 0;

  return {
    enabled: toBoolean(status.enabled),
    autoTradingEnabled: toBoolean(status.autoTradingEnabled),
    openPositionsCount: toNumber(status.openPositionsCount, openPositions.length),
    openPositions: openPositions,
    totalPnl: toNumber(totalPnlRaw, 0),
    lastCheckTime: toStringValue(status.lastCheckTime),
  };
}

function normalizeFundingScanResult(raw: unknown): FundingScanResult {
  const result = asApiObject(raw);
  const opportunitiesRaw = Array.isArray(result.opportunities) ? result.opportunities : [];
  const opportunities = opportunitiesRaw.map((opportunity) => normalizeFundingOpportunity(opportunity));

  return {
    scanTime: toStringValue(result.scanTime),
    totalOpportunities: toNumber(result.totalOpportunities, opportunities.length),
    opportunities,
  };
}

export type GenericApiResult = Record<string, unknown>;

export const dashboardApi = {
  getData: (date: string | null = null): Promise<DashboardData> =>
    api.get('/dashboard', { params: date ? { date } : {} }).then(res => res.data),

  manualClose: (market: string, strategy: string): Promise<{ success: boolean; error?: string }> =>
    api.post('/dashboard/manual-close', null, {
      params: { market, strategy },
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
    }).then(res => res.data),

  syncPositions: (): Promise<SyncResult> =>
    api.post('/sync/positions').then(res => res.data),

  syncOrders: (): Promise<SyncResult> =>
    api.post('/sync/orders').then(res => res.data),
};

export const systemControlApi = {
  // LLM Optimizer (장기 실행 - 10분 타임아웃)
  runOptimizer: (): Promise<SystemControlResult> =>
    longRunningApi.post('/optimizer/run').then(res => res.data),

  // Volume Surge (장기 실행 - 10분 타임아웃)
  runVolumeSurgeReflection: (): Promise<SystemControlResult> =>
    longRunningApi.post('/volume-surge/reflect').then(res => res.data),

  // Meme Scalper (장기 실행 - 10분 타임아웃)
  runMemeScalperReflection: (): Promise<SystemControlResult> =>
    longRunningApi.post('/meme-scalper/reflect').then(res => res.data),

  // 나머지는 기본 api 사용 (10초 타임아웃)
  resetVolumeSurgeCircuitBreaker: (): Promise<SystemControlResult> =>
    api.post('/volume-surge/reset-circuit-breaker').then(res => res.data),

  resetMemeScalperCircuitBreaker: (): Promise<SystemControlResult> =>
    api.post('/meme-scalper/reset').then(res => res.data),

  // Kimchi Premium
  refreshExchangeRate: (): Promise<SystemControlResult> =>
    api.post('/kimchi-premium/exchange-rate/refresh').then(res => res.data),

  // Funding Rate
  scanFundingOpportunities: (): Promise<FundingScanResult> =>
    api.post('/funding/scan').then(res => normalizeFundingScanResult(res.data)),

  getFundingStatus: (): Promise<FundingStatus> =>
    api.get('/funding/status').then(res => normalizeFundingStatus(res.data)),

  toggleFundingAutoTrading: (enabled: boolean): Promise<GenericApiResult> =>
    api.post('/funding/toggle-auto-trading', { enabled }).then(res => res.data),

  manualFundingEntry: (symbol: string, quantity: number, spotPrice: number, perpPrice: number, fundingRate: number): Promise<GenericApiResult> =>
    api.post('/funding/manual-entry', {
      symbol,
      quantity,
      spotPrice,
      perpPrice,
      fundingRate
    }).then(res => res.data),

  manualFundingClose: (positionId: number): Promise<GenericApiResult> =>
    api.post('/funding/manual-close', { positionId }).then(res => res.data),

  getFundingRiskCheck: (positionId: number): Promise<GenericApiResult> =>
    api.get(`/funding/risk-check/${positionId}`).then(res => res.data),

  getFundingConfig: (): Promise<FundingConfig> =>
    api.get('/funding/config').then(res => res.data),

  // Settings
  refreshCache: (): Promise<SystemControlResult> =>
    api.post('/settings/cache/refresh').then(res => res.data),
};

export default api;
