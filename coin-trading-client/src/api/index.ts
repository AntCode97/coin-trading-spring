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

// System Control API Responses
export interface SystemControlResult {
  success: boolean;
  message: string;
  data?: Record<string, any>;
}

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
    longRunningApi.post('/meme-scaler/reflect').then(res => res.data),

  // 나머지는 기본 api 사용 (10초 타임아웃)
  resetVolumeSurgeCircuitBreaker: (): Promise<SystemControlResult> =>
    api.post('/volume-surge/reset-circuit-breaker').then(res => res.data),

  resetMemeScalperCircuitBreaker: (): Promise<SystemControlResult> =>
    api.post('/meme-scaler/reset').then(res => res.data),

  // Kimchi Premium
  refreshExchangeRate: (): Promise<SystemControlResult> =>
    api.post('/kimchi-premium/exchange-rate/refresh').then(res => res.data),

  // Funding Rate
  scanFundingOpportunities: (): Promise<SystemControlResult> =>
    api.post('/funding/scan').then(res => res.data),

  // Settings
  refreshCache: (): Promise<SystemControlResult> =>
    api.post('/settings/cache/refresh').then(res => res.data),
};

export default api;
