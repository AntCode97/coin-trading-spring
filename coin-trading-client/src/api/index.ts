import axios from 'axios';
import { getWebToken, clearWebToken } from '../lib/authToken';

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL as string | undefined)?.trim() || '/api';
const DESKTOP_TOKEN = (import.meta.env.VITE_DESKTOP_TRADING_TOKEN as string | undefined)?.trim();
const DESKTOP_MODE = import.meta.env.VITE_DESKTOP_MODE === 'true';

function buildDefaultHeaders(): Record<string, string> {
  if (DESKTOP_MODE && DESKTOP_TOKEN) {
    return { 'X-Desktop-Token': DESKTOP_TOKEN };
  }
  return {};
}

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
  headers: buildDefaultHeaders(),
});

// Long-running API 클라이언트 (LLM 최적화, 회고 등은 10분 타임아웃)
const longRunningApi = axios.create({
  baseURL: API_BASE_URL,
  timeout: 600000, // 10분
  headers: buildDefaultHeaders(),
});

// 웹 모드: sessionStorage 토큰을 매 요청에 주입
if (!DESKTOP_MODE) {
  const injectWebToken = (config: import('axios').InternalAxiosRequestConfig) => {
    const token = getWebToken();
    if (token) {
      config.headers.set('X-Desktop-Token', token);
    }
    return config;
  };

  api.interceptors.request.use(injectWebToken);
  longRunningApi.interceptors.request.use(injectWebToken);

  const handleAuthError = (error: unknown) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      clearWebToken();
      window.dispatchEvent(new CustomEvent('web-auth-expired'));
    }
    return Promise.reject(error);
  };

  api.interceptors.response.use(undefined, handleAuthError);
  longRunningApi.interceptors.response.use(undefined, handleAuthError);
}

export function getApiBaseUrl(): string {
  return API_BASE_URL;
}

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
  // APM 필드
  regime?: string;
  entryRegime?: string;
  confluenceScore?: number;
  entryConfluenceScore?: number;
  lastApmAction?: string;
  lastApmReason?: string;
  divergenceWarning?: string;
  progressiveStage?: string;
  adjustedStopLoss?: number;
  adjustedTakeProfit?: number;
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
  suspendedStrategies: SuspendedStrategyInfo[];
}

export interface StrategyInfo {
  name: string;
  description: string;
  code: string;
}

export interface SuspendedStrategyInfo {
  name: string;
  code: string;
  reason: string;
  consecutiveLosses: number;
  dailyPnl: number;
  dailyLossPercent?: number | null;
  actionType: 'VOLUME_SURGE' | 'MEME_SCALPER' | 'MAIN_TRADING';
  market?: string | null;
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

export interface ValidationGateStatus {
  canApplyChanges: boolean;
  reason: string;
  checkedAt: string;
  market: string;
  totalWindows: number;
  validWindows: number;
  avgOutOfSampleSharpe: number;
  avgOutOfSampleReturn: number;
  avgOutOfSampleTrades: number;
  decayPercent: number;
  isOverfitted: boolean;
  minValidWindows: number;
  minOutOfSampleSharpe: number;
  minOutOfSampleTrades: number;
  maxSharpeDecayPercent: number;
  maxOutOfSampleDrawdownPercent: number;
  executionSlippageBps: number;
  cached: boolean;
}

export interface RiskThrottleStatus {
  multiplier: number;
  severity: string;
  blockNewBuys: boolean;
  reason: string;
  sampleSize: number;
  recentConsecutiveLosses: number;
  winRate: number;
  avgPnlPercent: number;
  enabled: boolean;
  cached: boolean;
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

function normalizeSystemControlResult(raw: unknown): SystemControlResult {
  const result = asApiObject(raw);
  const fallbackMessage = toStringValue(result.result, '완료되었습니다.');
  const message = toStringValue(result.message, fallbackMessage);
  const data = asApiObject(result.data);

  return {
    success: toBoolean(result.success, true),
    message,
    data,
  };
}

function normalizeValidationGateStatus(raw: unknown): ValidationGateStatus {
  const status = asApiObject(raw);

  return {
    canApplyChanges: toBoolean(status.canApplyChanges),
    reason: toStringValue(status.reason),
    checkedAt: toStringValue(status.checkedAt),
    market: toStringValue(status.market),
    totalWindows: toNumber(status.totalWindows, 0),
    validWindows: toNumber(status.validWindows, 0),
    avgOutOfSampleSharpe: toNumber(status.avgOutOfSampleSharpe, 0),
    avgOutOfSampleReturn: toNumber(status.avgOutOfSampleReturn, 0),
    avgOutOfSampleTrades: toNumber(status.avgOutOfSampleTrades, 0),
    decayPercent: toNumber(status.decayPercent, 0),
    isOverfitted: toBoolean(status.isOverfitted, false),
    minValidWindows: toNumber(status.minValidWindows, 0),
    minOutOfSampleSharpe: toNumber(status.minOutOfSampleSharpe, 0),
    minOutOfSampleTrades: toNumber(status.minOutOfSampleTrades, 0),
    maxSharpeDecayPercent: toNumber(status.maxSharpeDecayPercent, 0),
    maxOutOfSampleDrawdownPercent: toNumber(status.maxOutOfSampleDrawdownPercent, 0),
    executionSlippageBps: toNumber(status.executionSlippageBps, 0),
    cached: toBoolean(status.cached, false),
  };
}

function normalizeRiskThrottleStatus(raw: unknown): RiskThrottleStatus {
  const status = asApiObject(raw);

  return {
    multiplier: toNumber(status.multiplier, 1),
    severity: toStringValue(status.severity, 'NORMAL'),
    blockNewBuys: toBoolean(status.blockNewBuys, false),
    reason: toStringValue(status.reason),
    sampleSize: toNumber(status.sampleSize, 0),
    recentConsecutiveLosses: toNumber(status.recentConsecutiveLosses, 0),
    winRate: toNumber(status.winRate, 0),
    avgPnlPercent: toNumber(status.avgPnlPercent, 0),
    enabled: toBoolean(status.enabled, false),
    cached: toBoolean(status.cached, false),
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
    longRunningApi.post('/optimizer/run').then(res => normalizeSystemControlResult(res.data)),

  // Volume Surge (장기 실행 - 10분 타임아웃)
  runVolumeSurgeReflection: (): Promise<SystemControlResult> =>
    longRunningApi.post('/volume-surge/reflect').then(res => normalizeSystemControlResult(res.data)),

  // Meme Scalper (장기 실행 - 10분 타임아웃)
  runMemeScalperReflection: (): Promise<SystemControlResult> =>
    longRunningApi.post('/meme-scalper/reflect').then(res => normalizeSystemControlResult(res.data)),

  // 나머지는 기본 api 사용 (10초 타임아웃)
  resetVolumeSurgeCircuitBreaker: (): Promise<SystemControlResult> =>
    api.post('/volume-surge/reset-circuit-breaker').then(res => normalizeSystemControlResult(res.data)),

  resetMemeScalperCircuitBreaker: (): Promise<SystemControlResult> =>
    api.post('/meme-scalper/reset').then(res => normalizeSystemControlResult(res.data)),

  resetMainTradingCircuitBreaker: (market: string): Promise<SystemControlResult> =>
    api.post(`/trading/circuit-breaker/reset/${encodeURIComponent(market)}`).then(res => normalizeSystemControlResult(res.data)),

  // Kimchi Premium
  refreshExchangeRate: (): Promise<SystemControlResult> =>
    api.post('/kimchi-premium/exchange-rate/refresh').then(res => normalizeSystemControlResult(res.data)),

  getOptimizerValidationGate: (forceRefresh = false): Promise<ValidationGateStatus> =>
    api.get('/optimizer/validation-gate', { params: { forceRefresh } }).then(res => normalizeValidationGateStatus(res.data)),

  getRiskThrottleStatus: (
    market = 'KRW-BTC',
    strategy?: string,
    forceRefresh = false
  ): Promise<RiskThrottleStatus> =>
    api.get(`/trading/risk-throttle/${market}`, { params: { strategy, forceRefresh } })
      .then(res => normalizeRiskThrottleStatus(res.data)),

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
    api.post('/settings/cache/refresh').then(res => normalizeSystemControlResult(res.data)),

  // Trading Amounts
  getTradingAmounts: (): Promise<TradingAmountsResponse> =>
    api.get('/settings/trading-amounts').then(res => res.data),

  setTradingAmount: (strategyCode: string, amountKrw: number): Promise<SystemControlResult> =>
    api.post('/settings/trading-amounts', { strategyCode, amountKrw }).then(res => normalizeSystemControlResult(res.data)),
};

export interface StrategyTradingAmount {
  strategyCode: string;
  label: string;
  amountKrw: number;
  defaultAmountKrw: number;
}

export interface TradingAmountsResponse {
  success: boolean;
  amounts: StrategyTradingAmount[];
  minOrderAmountKrw: number;
}

export interface GuidedMarketItem {
  market: string;
  symbol: string;
  koreanName: string;
  englishName?: string | null;
  tradePrice: number;
  changeRate: number;
  changePrice: number;
  accTradePrice: number;
  accTradeVolume: number;
  surgeRate: number;
  marketCapFlowScore: number;
  recommendedEntryWinRate: number | null;
  marketEntryWinRate: number | null;
}

export type GuidedMarketSortBy =
  | 'TURNOVER'
  | 'CHANGE_RATE'
  | 'VOLUME'
  | 'SURGE_RATE'
  | 'MARKET_CAP_FLOW'
  | 'RECOMMENDED_ENTRY_WIN_RATE'
  | 'MARKET_ENTRY_WIN_RATE';

export type GuidedSortDirection = 'ASC' | 'DESC';

export interface GuidedCandle {
  timestamp: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface GuidedKeyLevel {
  price: number;
  label: string;
  type: string;
}

export interface GuidedRecommendation {
  market: string;
  currentPrice: number;
  recommendedEntryPrice: number;
  stopLossPrice: number;
  takeProfitPrice: number;
  confidence: number;
  predictedWinRate: number;
  recommendedEntryWinRate: number;
  marketEntryWinRate: number;
  riskRewardRatio: number;
  winRateBreakdown: {
    trend: number;
    pullback: number;
    volatility: number;
    riskReward: number;
  };
  suggestedOrderType: string;
  rationale: string[];
  keyLevels?: GuidedKeyLevel[];
}

export interface GuidedTradeEvent {
  id: number;
  tradeId: number;
  eventType: string;
  price?: number | null;
  quantity?: number | null;
  message?: string | null;
  createdAt: string;
}

export interface GuidedTradePosition {
  tradeId: number;
  market: string;
  status: string;
  entryOrderType: string;
  entryOrderId?: string | null;
  averageEntryPrice: number;
  currentPrice: number;
  entryQuantity: number;
  remainingQuantity: number;
  stopLossPrice: number;
  takeProfitPrice: number;
  trailingActive: boolean;
  trailingPeakPrice?: number | null;
  trailingStopPrice?: number | null;
  dcaCount: number;
  maxDcaCount: number;
  halfTakeProfitDone: boolean;
  unrealizedPnlPercent: number;
  realizedPnl: number;
  realizedPnlPercent: number;
  lastAction?: string | null;
  recommendationReason?: string | null;
  createdAt: string;
  updatedAt: string;
  closedAt?: string | null;
  exitReason?: string | null;
}

export interface GuidedOrderbookUnit {
  askPrice: number;
  askSize: number;
  bidPrice: number;
  bidSize: number;
}

export interface GuidedOrderbook {
  market: string;
  timestamp?: number | null;
  bestAsk?: number | null;
  bestBid?: number | null;
  spread?: number | null;
  spreadPercent?: number | null;
  totalAskSize?: number | null;
  totalBidSize?: number | null;
  units: GuidedOrderbookUnit[];
}

export interface GuidedOrderItem {
  uuid: string;
  side: string;
  ordType: string;
  state?: string | null;
  market: string;
  price?: number | null;
  volume?: number | null;
  remainingVolume?: number | null;
  executedVolume?: number | null;
  createdAt?: string | null;
}

export interface GuidedOrderSnapshot {
  currentOrder?: GuidedOrderItem | null;
  pendingOrders: GuidedOrderItem[];
  completedOrders: GuidedOrderItem[];
}

export interface GuidedRealtimeTicker {
  market: string;
  tradePrice: number;
  changeRate?: number | null;
  tradeVolume?: number | null;
  timestamp?: number | null;
}

export interface GuidedChartResponse {
  market: string;
  interval: string;
  candles: GuidedCandle[];
  recommendation: GuidedRecommendation;
  activePosition?: GuidedTradePosition | null;
  events: GuidedTradeEvent[];
  orderbook?: GuidedOrderbook | null;
  orderSnapshot: GuidedOrderSnapshot;
}

export interface GuidedDailyStats {
  totalTrades: number;
  wins: number;
  losses: number;
  totalPnlKrw: number;
  avgPnlPercent: number;
  winRate: number;
  openPositionCount: number;
  totalInvestedKrw: number;
  trades: GuidedClosedTradeView[];
}

export interface GuidedClosedTradeView {
  tradeId: number;
  market: string;
  averageEntryPrice: number;
  averageExitPrice: number;
  entryQuantity: number;
  realizedPnl: number;
  realizedPnlPercent: number;
  dcaCount: number;
  halfTakeProfitDone: boolean;
  createdAt: string;
  closedAt?: string | null;
  exitReason?: string | null;
}

export interface GuidedPerformanceSnapshot {
  sampleSize: number;
  winCount: number;
  lossCount: number;
  winRate: number;
  avgPnlPercent: number;
}

export interface GuidedAgentContextResponse {
  market: string;
  generatedAt: string;
  chart: GuidedChartResponse;
  recentClosedTrades: GuidedClosedTradeView[];
  performance: GuidedPerformanceSnapshot;
}

export interface OrderLifecycleGroupSummary {
  buyRequested: number;
  buyFilled: number;
  sellRequested: number;
  sellFilled: number;
  pending: number;
  cancelled: number;
}

export interface OrderLifecycleSummary {
  total: OrderLifecycleGroupSummary;
  groups: Record<string, OrderLifecycleGroupSummary>;
}

export interface OrderLifecycleEvent {
  id: number;
  orderId?: string | null;
  market: string;
  side?: string | null;
  eventType: string;
  strategyGroup: string;
  strategyCode?: string | null;
  price?: number | null;
  quantity?: number | null;
  message?: string | null;
  createdAt: string;
}

export interface AutopilotCandidateView {
  market: string;
  koreanName: string;
  recommendedEntryWinRate?: number | null;
  marketEntryWinRate?: number | null;
  stage: string;
  reason: string;
}

export interface AutopilotEventView {
  market: string;
  eventType: string;
  level: string;
  message: string;
  createdAt: string;
}

export interface AutopilotLiveResponse {
  orderSummary: OrderLifecycleSummary;
  orderEvents: OrderLifecycleEvent[];
  autopilotEvents: AutopilotEventView[];
  candidates: AutopilotCandidateView[];
  thresholdMode: 'DYNAMIC_P70' | 'FIXED';
  appliedRecommendedWinRateThreshold: number;
  requestedMinRecommendedWinRate?: number | null;
  decisionStats?: {
    rulePass: number;
    ruleFail: number;
    llmReject: number;
    entered: number;
    pendingTimeout: number;
  };
  strategyCodeSummary?: Record<string, {
    buyRequested: number;
    buyFilled: number;
    sellRequested: number;
    sellFilled: number;
    failed: number;
    cancelled: number;
  }>;
}

export interface GuidedStartRequest {
  market: string;
  amountKrw: number;
  orderType?: string;
  limitPrice?: number;
  stopLossPrice?: number;
  takeProfitPrice?: number;
  trailingTriggerPercent?: number;
  trailingOffsetPercent?: number;
  maxDcaCount?: number;
  dcaStepPercent?: number;
  halfTakeProfitRatio?: number;
  interval?: string;
  mode?: string;
  entrySource?: 'MANUAL' | 'AUTOPILOT';
  strategyCode?: string;
}

export interface GuidedAdoptRequest {
  market: string;
  mode?: string;
  interval?: string;
  entrySource?: string;
  notes?: string;
}

export interface GuidedAdoptResponse {
  positionId: number;
  adopted: boolean;
  avgEntryPrice: number;
  quantity: number;
}

export interface GuidedPnlReconcileItem {
  tradeId: number;
  market: string;
  confidence: 'HIGH' | 'LOW' | 'LEGACY' | string;
  reason: string;
  recalculated: boolean;
  realizedPnl: number;
  realizedPnlPercent: number;
}

export interface GuidedPnlReconcileResult {
  windowDays: number;
  dryRun: boolean;
  scannedTrades: number;
  updatedTrades: number;
  unchangedTrades: number;
  highConfidenceTrades: number;
  lowConfidenceTrades: number;
  sample: GuidedPnlReconcileItem[];
}

export interface DesktopMcpTool {
  name: string;
  description?: string;
  serverId?: string;
  qualifiedName?: string;
  originName?: string;
  inputSchema?: {
    type: string;
    properties?: Record<string, { type: string; description?: string }>;
    required?: string[];
  };
}

export interface GuidedSyncResult {
  success: boolean;
  message: string;
  actions: GuidedSyncAction[];
  fixedCount: number;
}

export interface GuidedSyncAction {
  type: string;
  market: string;
  detail: string;
}

export const guidedTradingApi = {
  getMarkets: (
    sortBy: GuidedMarketSortBy = 'TURNOVER',
    sortDirection: GuidedSortDirection = 'DESC',
    interval?: string,
    mode?: string
  ): Promise<GuidedMarketItem[]> => {
    const params: Record<string, string> = { sortBy, sortDirection };
    if (interval) params.interval = interval;
    if (mode) params.mode = mode;
    return api.get('/guided-trading/markets', { params }).then((res) => res.data);
  },

  getChart: (
    market: string,
    interval = 'minute30',
    count = 120,
    mode?: string
  ): Promise<GuidedChartResponse> =>
    api.get('/guided-trading/chart', { params: { market, interval, count, mode } }).then((res) => res.data),

  getRecommendation: (
    market: string,
    interval = 'minute30',
    count = 120,
    mode?: string
  ): Promise<GuidedRecommendation> =>
    api.get('/guided-trading/recommendation', { params: { market, interval, count, mode } }).then((res) => res.data),

  getPosition: (market: string): Promise<GuidedTradePosition | null> =>
    api.get(`/guided-trading/position/${encodeURIComponent(market)}`).then((res) => res.data),

  getRealtimeTicker: (market: string): Promise<GuidedRealtimeTicker | null> =>
    api.get('/guided-trading/ticker', { params: { market } }).then((res) => res.data),

  start: (payload: GuidedStartRequest): Promise<GuidedTradePosition> =>
    api.post('/guided-trading/start', payload).then((res) => res.data),

  adoptPosition: (payload: GuidedAdoptRequest): Promise<GuidedAdoptResponse> =>
    api.post('/guided-trading/positions/adopt', payload).then((res) => res.data),

  stop: (market: string): Promise<GuidedTradePosition> =>
    api.post('/guided-trading/stop', null, { params: { market } }).then((res) => res.data),

  partialTakeProfit: (market: string, ratio = 0.5): Promise<GuidedTradePosition> =>
    api.post('/guided-trading/partial-take-profit', null, { params: { market, ratio } }).then((res) => res.data),

  cancelPending: (market: string): Promise<GuidedTradePosition | null> =>
    api.post('/guided-trading/cancel-pending', null, { params: { market } }).then((res) => res.data),

  getAgentContext: (
    market: string,
    interval = 'minute30',
    count = 120,
    closedTradeLimit = 20,
    mode?: string
  ): Promise<GuidedAgentContextResponse> =>
    api.get('/guided-trading/agent/context', { params: { market, interval, count, closedTradeLimit, mode } }).then((res) => res.data),

  getOpenPositions: (): Promise<GuidedTradePosition[]> =>
    api.get('/guided-trading/positions/open').then((res) => res.data),

  getTodayStats: (): Promise<GuidedDailyStats> =>
    api.get('/guided-trading/stats/today').then((res) => res.data),

  getAutopilotLive: (
    interval = 'minute30',
    mode?: string,
    thresholdMode?: 'DYNAMIC_P70' | 'FIXED',
    minRecommendedWinRate?: number
  ): Promise<AutopilotLiveResponse> =>
    api
      .get('/guided-trading/autopilot/live', {
        params: { interval, mode, thresholdMode, minRecommendedWinRate },
      })
      .then((res) => res.data),

  getClosedTrades: (limit = 50): Promise<GuidedClosedTradeView[]> =>
    api.get('/guided-trading/trades/closed', { params: { limit } }).then((res) => res.data),

  reconcileClosedTrades: (
    windowDays = 30,
    dryRun = true
  ): Promise<GuidedPnlReconcileResult> =>
    api
      .post('/guided-trading/reconcile/closed', null, { params: { windowDays, dryRun } })
      .then((res) => res.data),

  syncPositions: (): Promise<GuidedSyncResult> =>
    api.post('/guided-trading/sync').then((res) => res.data),

};

export default api;
