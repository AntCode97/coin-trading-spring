import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  CandlestickSeries,
  ColorType,
  createChart,
  createSeriesMarkers,
  type CandlestickData,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
} from 'lightweight-charts';
import {
  getApiBaseUrl,
  guidedTradingApi,
  type GuidedAgentContextResponse,
  type GuidedChartResponse,
  type GuidedCandle,
  type GuidedMarketItem,
  type GuidedMarketSortBy,
  type GuidedSortDirection,
  type GuidedOrderItem,
  type GuidedRealtimeTicker,
  type GuidedStartRequest,
  type GuidedTradePosition,
  type GuidedDailyStats,
  type AutopilotLiveResponse,
} from '../api';
import {
  checkConnection,
  startLogin,
  logout,
  sendChatMessage,
  clearConversation,
  connectMcpServers,
  getPlaywrightStatus,
  startPlaywrightMcp,
  stopPlaywrightMcp,
  CODEX_MODELS,
  type AgentAction,
  type ChatMessage,
  type CodexModelId,
  type LlmConnectionStatus,
  type TradingMode,
} from '../lib/llmService';
import { usePlanExecution } from '../lib/usePlanExecution';
import { PlanPanel } from './PlanPanel';
import {
  AutopilotOrchestrator,
  type AutopilotState,
  type AutopilotTimelineEvent,
} from '../lib/autopilot/AutopilotOrchestrator';
import { AutopilotLiveDock } from './autopilot/AutopilotLiveDock';
import {
  FocusedScalpLiveDock,
  type FocusedScalpDecisionCardView,
  type FocusedScalpDecisionSummaryView,
  type FocusedScalpEntryState,
  type FocusedScalpManageState,
} from './autopilot/FocusedScalpLiveDock';
import { InvestmentLiveDock } from './autopilot/InvestmentLiveDock';
import './ManualTraderWorkspace.css';

const KRW_FORMATTER = new Intl.NumberFormat('ko-KR');
const STORAGE_KEY = 'guided-trader.preferences.v1';
const STRATEGY_CODE_SCALP = 'GUIDED_AUTOPILOT_SCALP';
const STRATEGY_CODE_SWING = 'GUIDED_AUTOPILOT_SWING';
const STRATEGY_CODE_POSITION = 'GUIDED_AUTOPILOT_POSITION';
const INVEST_MAJOR_MARKETS = ['KRW-BTC', 'KRW-ETH', 'KRW-SOL', 'KRW-XRP', 'KRW-ADA'];
const DEFAULT_AUTOPILOT_CAPITAL_POOL_KRW = 300_000;
const ENGINE_CAPITAL_RATIOS = {
  SCALP: 0.4,
  SWING: 0.35,
  POSITION: 0.25,
} as const;

type MonitorTab = 'SCALP' | 'INVEST';

function createInitialAutopilotState(): AutopilotState {
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

function formatVolume(value: number): string {
  if (value >= 1_0000_0000_0000) return `${(value / 1_0000_0000_0000).toFixed(1)}조`;
  if (value >= 1_0000_0000) return `${(value / 1_0000_0000).toFixed(0)}억`;
  if (value >= 1_0000_0000 * 0.1) return `${(value / 1_0000_0000).toFixed(1)}억`;
  if (value >= 1_0000) return `${(value / 1_0000).toFixed(0)}만`;
  return KRW_FORMATTER.format(Math.round(value));
}

type WorkspacePrefs = {
  selectedMarket?: string;
  interval?: string;
  sortBy?: GuidedMarketSortBy;
  sortDirection?: GuidedSortDirection;
  aiRefreshSec?: number;
  tradingMode?: TradingMode;
  autopilotEnabled?: boolean;
  swingAutopilotEnabled?: boolean;
  positionAutopilotEnabled?: boolean;
  dailyLossLimitKrw?: number;
  autopilotMaxConcurrentPositions?: number;
  autopilotAmountKrw?: number;
  autopilotCapitalPoolKrw?: number;
  autopilotInterval?: string;
  autopilotMode?: TradingMode;
  entryPolicy?: 'BALANCED' | 'AGGRESSIVE' | 'CONSERVATIVE';
  entryOrderMode?: 'ADAPTIVE' | 'MARKET' | 'LIMIT';
  pendingEntryTimeoutSec?: number;
  marketFallbackAfterCancel?: boolean;
  playwrightEnabled?: boolean;
  playwrightAutoStart?: boolean;
  playwrightMcpPort?: number;
  playwrightMcpUrl?: string;
  autopilotDockCollapsed?: boolean;
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
  focusedScalpDockCollapsed?: boolean;
  investmentDockCollapsed?: boolean;
  monitorTab?: MonitorTab;
};

type ConnectPlaywrightOptions = {
  allowAutoStart?: boolean;
};

function loadPrefs(): WorkspacePrefs {
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

function migrateWorkspacePrefs(prefs: WorkspacePrefs): WorkspacePrefs {
  const next: WorkspacePrefs = { ...prefs };
  const hasEngineSplitPrefs =
    typeof prefs.swingAutopilotEnabled === 'boolean' ||
    typeof prefs.positionAutopilotEnabled === 'boolean' ||
    typeof prefs.autopilotCapitalPoolKrw === 'number' ||
    typeof prefs.monitorTab === 'string';

  if (!hasEngineSplitPrefs) {
    const legacyEnabled = prefs.autopilotEnabled ?? false;
    const legacyMode = prefs.autopilotMode ?? 'SCALP';
    if (legacyEnabled) {
      if (legacyMode === 'SWING') {
        next.autopilotEnabled = false;
        next.swingAutopilotEnabled = true;
        next.positionAutopilotEnabled = false;
        next.monitorTab = 'INVEST';
      } else if (legacyMode === 'POSITION') {
        next.autopilotEnabled = false;
        next.swingAutopilotEnabled = false;
        next.positionAutopilotEnabled = true;
        next.monitorTab = 'INVEST';
      } else {
        next.autopilotEnabled = true;
        next.swingAutopilotEnabled = false;
        next.positionAutopilotEnabled = false;
        next.monitorTab = 'SCALP';
      }
    } else {
      next.swingAutopilotEnabled = false;
      next.positionAutopilotEnabled = false;
      next.monitorTab = 'SCALP';
    }

    const legacyAmountKrw = Math.max(5100, Math.round(prefs.autopilotAmountKrw ?? 10_000));
    const legacyMaxPositions = Math.min(10, Math.max(1, prefs.autopilotMaxConcurrentPositions ?? 6));
    next.autopilotCapitalPoolKrw = Math.max(
      DEFAULT_AUTOPILOT_CAPITAL_POOL_KRW,
      legacyAmountKrw * legacyMaxPositions
    );
  }

  if (!next.monitorTab) {
    next.monitorTab =
      (next.swingAutopilotEnabled || next.positionAutopilotEnabled)
        ? 'INVEST'
        : 'SCALP';
  }

  return next;
}

function asUtc(secondsOrMillis: number): UTCTimestamp {
  const sec = secondsOrMillis > 10_000_000_000 ? Math.floor(secondsOrMillis / 1000) : Math.floor(secondsOrMillis);
  return sec as UTCTimestamp;
}

function formatKrw(value: number): string {
  if (!Number.isFinite(value)) return '-';
  if (Math.abs(value) >= 1_000_000) {
    return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}원`;
  }
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 10 })}원`;
}

function formatPlain(value: number): string {
  if (!Number.isFinite(value)) return '-';
  if (Math.abs(value) >= 1_000_000) {
    return value.toLocaleString('ko-KR', { maximumFractionDigits: 2 });
  }
  return value.toLocaleString('ko-KR', { maximumFractionDigits: 10 });
}

function formatPct(value: number): string {
  return `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`;
}

function formatWinRate(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '-';
  return `${value.toFixed(1)}%`;
}

function normalizeFocusedScalpMarket(raw: string): string | null {
  const token = raw.trim().toUpperCase();
  if (!token) return null;
  const market = token.startsWith('KRW-') ? token : `KRW-${token}`;
  const symbol = market.slice(4);
  if (!symbol || !/^[A-Z0-9]+$/.test(symbol)) return null;
  return market;
}

function normalizeFocusedScalpMarkets(raw: string): { markets: string[]; invalidTokens: string[] } {
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

type FocusedScalpContextMenu = {
  x: number;
  y: number;
  market: string;
  koreanName: string;
  included: boolean;
};

function classifyFocusedEntryEvent(event?: AutopilotTimelineEvent): {
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

function classifyFocusedManageEvent(event?: AutopilotTimelineEvent): {
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

function isWinRateSort(sortBy: GuidedMarketSortBy): boolean {
  return sortBy === 'RECOMMENDED_ENTRY_WIN_RATE' || sortBy === 'MARKET_ENTRY_WIN_RATE';
}

function keyLevelColor(label: string): string {
  if (label.startsWith('SMA20')) return '#7dd3fc';
  if (label.startsWith('SMA60')) return '#c4b5fd';
  if (label.includes('지지')) return '#86efac';
  return '#a0a0a0';
}

type PositionState = 'NONE' | 'PENDING' | 'OPEN' | 'DANGER';

function derivePositionState(
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

function intervalSeconds(interval: string): number {
  if (interval === 'tick') return 1;
  if (interval === 'day') return 86400;
  if (interval.startsWith('minute')) {
    const minute = Number(interval.replace('minute', ''));
    if (Number.isFinite(minute) && minute > 0) return minute * 60;
  }
  return 60;
}

function toBucketStart(epochSec: number, interval: string): number {
  const step = intervalSeconds(interval);
  return Math.floor(epochSec / step) * step;
}

function toCandlestick(candle: GuidedCandle): CandlestickData<Time> {
  return {
    time: asUtc(candle.timestamp),
    open: candle.open,
    high: candle.high,
    low: candle.low,
    close: candle.close,
  };
}

function buildFallbackCandle(payload: GuidedChartResponse, interval: string): CandlestickData<Time> | null {
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

function computeVisiblePriceRange(
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

// KST 시간 포맷터
function formatKstTime(epochSec: number): string {
  const d = new Date(epochSec * 1000);
  return d.toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit' });
}

function formatKstDateTime(epochSec: number): string {
  const d = new Date(epochSec * 1000);
  return d.toLocaleDateString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function formatOrderDateTime(value?: string | null): string {
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

function normalizeOrderSide(side?: string | null): 'BUY' | 'SELL' | 'UNKNOWN' {
  const normalized = side?.trim().toLowerCase();
  if (normalized === 'bid' || normalized === 'buy') return 'BUY';
  if (normalized === 'ask' || normalized === 'sell') return 'SELL';
  return 'UNKNOWN';
}

function orderStateLabel(state?: string | null): string {
  const normalized = state?.trim().toLowerCase();
  if (normalized === 'done') return '체결';
  if (normalized === 'wait') return '대기';
  if (normalized === 'cancel') return '취소';
  return normalized ? normalized.toUpperCase() : '-';
}

function orderTypeLabel(ordType?: string | null): string {
  const normalized = ordType?.trim().toLowerCase();
  if (normalized === 'limit') return '지정가';
  if (normalized === 'price' || normalized === 'market') return '시장가';
  return ordType ? ordType.toUpperCase() : '-';
}

// MCP URL을 API Base URL에서 도출
function deriveMcpUrl(): string {
  const apiBase = getApiBaseUrl().replace(/\/$/, '');
  // http://host:port/api → http://host:port/mcp
  return apiBase.replace(/\/api$/, '/mcp');
}

function derivePlaywrightMcpUrl(port: number): string {
  return `http://127.0.0.1:${port}/mcp`;
}

const GLOSSARY: Record<string, string> = {
  'SL': '손절가(Stop Loss). 손실을 제한하기 위해 자동으로 매도하는 가격.',
  '손절': '손실을 제한하기 위해 미리 정한 가격에서 자동 매도하는 것.',
  '익절': '이익 실현(Take Profit). 목표 수익에 도달하면 자동 매도하는 것.',
  'R/R': '위험 대비 보상 비율(Risk/Reward). 예: 2.2R은 손절 1만큼 감수하면 2.2만큼 수익 가능.',
  'Risk/Reward': '위험 대비 보상 비율. 높을수록 수익 잠재력이 큼. 2R 이상이 양호.',
  '신뢰도': 'AI가 현재 시장 상황을 분석한 매수 추천 확신도. 높을수록 유리한 진입 타이밍.',
  '승률': '과거 거래에서 수익을 낸 비율. AI가 현재 조건 기반으로 예측한 값.',
  '추세': '가격이 이동평균선 위에 있는지 판단하는 지표. 높을수록 상승 추세.',
  '눌림': '상승 추세에서 일시적으로 하락한 정도. 적당히 눌린 곳이 좋은 매수 시점.',
  '변동성': '가격 변동 폭. 낮을수록 안정적이고 예측이 쉬움.',
  'RR': '위험 대비 보상 비율(Risk/Reward). 높을수록 유리한 거래.',
  '물타기': '가격이 떨어졌을 때 추가 매수하여 평균 매수가를 낮추는 전략.',
  '절반익절': '보유 수량의 절반을 먼저 매도하여 이익을 확보하는 전략.',
  '트레일링': '가격이 오를 때 손절가도 함께 올려서 수익을 보호하는 방식.',
  '미실현 손익': '아직 매도하지 않은 상태에서의 현재 수익/손실률.',
  '추천가 승률': 'AI 추천 매수가로 진입했을 때의 예상 승률.',
  '현재가 승률': '지금 시장가로 바로 매수했을 때의 예상 승률.',
};

function Tip({ label, children }: { label: string; children?: React.ReactNode }) {
  const tip = GLOSSARY[label];
  if (!tip) return <>{children ?? label}</>;
  return (
    <span className="term-tip" data-tooltip={tip}>
      {children ?? label}
    </span>
  );
}

export default function ManualTraderWorkspace() {
  const prefs = useMemo(() => loadPrefs(), []);
  const [selectedMarket, setSelectedMarket] = useState<string>(prefs.selectedMarket ?? 'KRW-BTC');
  const [interval, setIntervalValue] = useState<string>(prefs.interval ?? 'minute30');
  const [search, setSearch] = useState<string>('');
  const [sortBy, setSortBy] = useState<GuidedMarketSortBy>(prefs.sortBy ?? 'TURNOVER');
  const [sortDirection, setSortDirection] = useState<GuidedSortDirection>(prefs.sortDirection ?? 'DESC');
  const [aiEnabled, setAiEnabled] = useState(true);
  const [aiRefreshSec, setAiRefreshSec] = useState(prefs.aiRefreshSec ?? 7);
  const [tradingMode, setTradingMode] = useState<TradingMode>(prefs.tradingMode ?? 'SWING');
  const [amountKrw, setAmountKrw] = useState<number>(20000);
  const [orderType, setOrderType] = useState<'MARKET' | 'LIMIT'>('LIMIT');
  const [limitPrice, setLimitPrice] = useState<number | ''>('');
  const [customStopLoss, setCustomStopLoss] = useState<number | ''>('');
  const [customTakeProfit, setCustomTakeProfit] = useState<number | ''>('');
  const [statusMessage, setStatusMessage] = useState<string>('');
  const [llmStatus, setLlmStatus] = useState<LlmConnectionStatus>('checking');

  // 탭 상태
  const [activeTab, setActiveTab] = useState<'order' | 'chat'>('order');
  const [showAdvanced, setShowAdvanced] = useState(false);

  // 채팅 상태
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [chatInput, setChatInput] = useState('');
  const [chatBusy, setChatBusy] = useState(false);
  const [chatStreamText, setChatStreamText] = useState('');
  const [autoContext, setAutoContext] = useState(true);
  const [autoAnalysis, setAutoAnalysis] = useState(false);
  const [showTools, setShowTools] = useState(false);
  const [chatModel, setChatModel] = useState<CodexModelId>('gpt-5.3-codex');
  const [mcpTools, setMcpTools] = useState<McpTool[]>([]);
  const [mcpConnected, setMcpConnected] = useState(false);
  const [playwrightEnabled, setPlaywrightEnabled] = useState<boolean>(prefs.playwrightEnabled ?? true);
  const [playwrightAutoStart, setPlaywrightAutoStart] = useState<boolean>(prefs.playwrightAutoStart ?? true);
  const [playwrightMcpPort, setPlaywrightMcpPort] = useState<number>(prefs.playwrightMcpPort ?? 8931);
  const [playwrightMcpUrl, setPlaywrightMcpUrl] = useState<string>(
    prefs.playwrightMcpUrl ?? derivePlaywrightMcpUrl(prefs.playwrightMcpPort ?? 8931)
  );
  const [playwrightStatus, setPlaywrightStatus] = useState<PlaywrightMcpStatus | null>(null);
  const [playwrightAction, setPlaywrightAction] = useState<'idle' | 'starting' | 'stopping'>('idle');
  const [monitorTab, setMonitorTab] = useState<MonitorTab>(prefs.monitorTab ?? 'SCALP');
  const [autopilotEnabled, setAutopilotEnabled] = useState<boolean>(prefs.autopilotEnabled ?? false);
  const [swingAutopilotEnabled, setSwingAutopilotEnabled] = useState<boolean>(prefs.swingAutopilotEnabled ?? false);
  const [positionAutopilotEnabled, setPositionAutopilotEnabled] = useState<boolean>(prefs.positionAutopilotEnabled ?? false);
  const [dailyLossLimitKrw, setDailyLossLimitKrw] = useState<number>(prefs.dailyLossLimitKrw ?? -30000);
  const [autopilotMaxConcurrentPositions, setAutopilotMaxConcurrentPositions] = useState<number>(
    Math.min(10, Math.max(1, prefs.autopilotMaxConcurrentPositions ?? 6))
  );
  const [autopilotAmountKrw, setAutopilotAmountKrw] = useState<number>(
    Math.max(5100, Math.round(prefs.autopilotAmountKrw ?? 10000))
  );
  const [autopilotCapitalPoolKrw, setAutopilotCapitalPoolKrw] = useState<number>(
    Math.max(20_000, Math.round(prefs.autopilotCapitalPoolKrw ?? DEFAULT_AUTOPILOT_CAPITAL_POOL_KRW))
  );
  const [autopilotInterval] = useState<string>(
    prefs.autopilotInterval ?? 'minute1'
  );
  const [autopilotMode] = useState<TradingMode>(
    prefs.autopilotMode ?? 'SCALP'
  );
  const [entryPolicy, setEntryPolicy] = useState<'BALANCED' | 'AGGRESSIVE' | 'CONSERVATIVE'>(
    prefs.entryPolicy ?? 'BALANCED'
  );
  const [entryOrderMode, setEntryOrderMode] = useState<'ADAPTIVE' | 'MARKET' | 'LIMIT'>(
    prefs.entryOrderMode ?? 'ADAPTIVE'
  );
  const [pendingEntryTimeoutSec, setPendingEntryTimeoutSec] = useState<number>(
    Math.min(900, Math.max(30, Math.round(prefs.pendingEntryTimeoutSec ?? 45)))
  );
  const [marketFallbackAfterCancel, setMarketFallbackAfterCancel] = useState<boolean>(
    prefs.marketFallbackAfterCancel ?? true
  );
  const [autopilotDockCollapsed, setAutopilotDockCollapsed] = useState<boolean>(
    prefs.autopilotDockCollapsed ?? false
  );
  const [winRateThresholdMode, setWinRateThresholdMode] = useState<'DYNAMIC_P70' | 'FIXED'>(
    prefs.winRateThresholdMode ?? 'DYNAMIC_P70'
  );
  const [fixedMinMarketWinRate, setFixedMinMarketWinRate] = useState<number>(
    prefs.fixedMinMarketWinRate ?? prefs.fixedMinRecommendedWinRate ?? 60
  );
  const [minLlmConfidence, setMinLlmConfidence] = useState<number>(prefs.minLlmConfidence ?? 60);
  const [llmDailyTokenCap, setLlmDailyTokenCap] = useState<number>(
    Math.max(20_000, Math.round(prefs.llmDailyTokenCap ?? 200_000))
  );
  const [llmRiskReserveTokens, setLlmRiskReserveTokens] = useState<number>(
    Math.max(0, Math.min(Math.max(20_000, Math.round(prefs.llmDailyTokenCap ?? 200_000)), Math.round(prefs.llmRiskReserveTokens ?? 40_000)))
  );
  const [rejectCooldownSeconds, setRejectCooldownSeconds] = useState<number>(
    Math.min(
      3600,
      Math.max(
        300,
        Math.round(
          prefs.rejectCooldownSeconds
            ?? ((prefs.rejectCooldownMinutes ?? 5) * 60)
        )
      )
    )
  );
  const [postExitCooldownMinutes, setPostExitCooldownMinutes] = useState<number>(prefs.postExitCooldownMinutes ?? 8);
  const [focusedScalpEnabled, setFocusedScalpEnabled] = useState<boolean>(prefs.focusedScalpEnabled ?? false);
  const [focusedScalpMarketsInput, setFocusedScalpMarketsInput] = useState<string>(
    (prefs.focusedScalpMarkets ?? []).join(', ')
  );
  const [focusedScalpWindowOpen, setFocusedScalpWindowOpen] = useState(false);
  const [focusedScalpTargetMarket, setFocusedScalpTargetMarket] = useState<string>(
    prefs.selectedMarket ?? 'KRW-BTC'
  );
  const [focusedScalpContextMenu, setFocusedScalpContextMenu] = useState<FocusedScalpContextMenu | null>(null);
  const [focusedScalpDockCollapsed, setFocusedScalpDockCollapsed] = useState<boolean>(
    prefs.focusedScalpDockCollapsed ?? false
  );
  const [investmentDockCollapsed, setInvestmentDockCollapsed] = useState<boolean>(
    prefs.investmentDockCollapsed ?? false
  );
  const [focusedScalpPollIntervalSec, setFocusedScalpPollIntervalSec] = useState<number>(
    Math.min(300, Math.max(5, Math.round(prefs.focusedScalpPollIntervalSec ?? 20)))
  );
  const focusedScalpParsed = useMemo(
    () => normalizeFocusedScalpMarkets(focusedScalpMarketsInput),
    [focusedScalpMarketsInput]
  );
  useEffect(() => {
    setLlmRiskReserveTokens((prev) => Math.min(prev, llmDailyTokenCap));
  }, [llmDailyTokenCap]);
  const [autopilotState, setAutopilotState] = useState<AutopilotState>(createInitialAutopilotState());
  const [swingAutopilotState, setSwingAutopilotState] = useState<AutopilotState>(createInitialAutopilotState());
  const [positionAutopilotState, setPositionAutopilotState] = useState<AutopilotState>(createInitialAutopilotState());
  const autopilotRef = useRef<AutopilotOrchestrator | null>(null);
  const swingAutopilotRef = useRef<AutopilotOrchestrator | null>(null);
  const positionAutopilotRef = useRef<AutopilotOrchestrator | null>(null);
  const chatBusyRef = useRef(false);
  const agentContextRef = useRef<GuidedAgentContextResponse | null>(null);
  const mcpToolsRef = useRef<McpTool[]>([]);
  const autoAnalysisInFlightRef = useRef(false);
  const chatEndRef = useRef<HTMLDivElement | null>(null);
  const winRateSort = isWinRateSort(sortBy);

  const openAiConnected = llmStatus === 'connected';
  const providerChecking = llmStatus === 'checking';

  const chartContainerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const priceLinesRef = useRef<IPriceLine[]>([]);
  const shouldAutoFitRef = useRef<boolean>(true);
  const liveCandlesRef = useRef<CandlestickData<Time>[]>([]);

  const marketsQuery = useQuery<GuidedMarketItem[]>({
    queryKey: winRateSort
      ? ['guided-markets', sortBy, sortDirection, interval, tradingMode]
      : ['guided-markets', sortBy, sortDirection],
    queryFn: () =>
      guidedTradingApi.getMarkets(
        sortBy,
        sortDirection,
        winRateSort ? interval : undefined,
        winRateSort ? tradingMode : undefined
      ),
    refetchInterval: 15000,
  });

  const chartQuery = useQuery<GuidedChartResponse>({
    queryKey: ['guided-chart', selectedMarket, interval, tradingMode],
    queryFn: () => guidedTradingApi.getChart(selectedMarket, interval, interval === 'tick' ? 500 : 180, tradingMode),
    refetchInterval: aiEnabled ? aiRefreshSec * 1000 : 30000,
  });

  const tickerQuery = useQuery<GuidedRealtimeTicker | null>({
    queryKey: ['guided-realtime-ticker', selectedMarket],
    queryFn: () => guidedTradingApi.getRealtimeTicker(selectedMarket),
    refetchInterval: 1000,
  });

  const agentContextQuery = useQuery<GuidedAgentContextResponse>({
    queryKey: ['guided-agent-context', selectedMarket, interval, tradingMode],
    queryFn: () => guidedTradingApi.getAgentContext(selectedMarket, interval, interval === 'tick' ? 300 : 120, 20, tradingMode),
    enabled: aiEnabled,
    refetchInterval: aiEnabled ? aiRefreshSec * 1000 : false,
  });

  const openPositionsQuery = useQuery<GuidedTradePosition[]>({
    queryKey: ['guided-open-positions'],
    queryFn: () => guidedTradingApi.getOpenPositions(),
    refetchInterval: 5000,
  });

  const todayStatsQuery = useQuery<GuidedDailyStats>({
    queryKey: ['guided-today-stats'],
    queryFn: () => guidedTradingApi.getTodayStats(),
    refetchInterval: 30000,
  });

  const scalpAutopilotLiveQuery = useQuery<AutopilotLiveResponse>({
    queryKey: [
      'guided-autopilot-live',
      'minute1',
      'SCALP',
      winRateThresholdMode,
      fixedMinMarketWinRate,
      STRATEGY_CODE_SCALP,
    ],
    queryFn: () =>
      guidedTradingApi.getAutopilotLive(
        'minute1',
        'SCALP',
        winRateThresholdMode,
        fixedMinMarketWinRate,
        STRATEGY_CODE_SCALP
      ),
    refetchInterval: 5000,
  });

  const swingAutopilotLiveQuery = useQuery<AutopilotLiveResponse>({
    queryKey: [
      'guided-autopilot-live',
      'minute30',
      'SWING',
      winRateThresholdMode,
      fixedMinMarketWinRate,
      STRATEGY_CODE_SWING,
    ],
    queryFn: () =>
      guidedTradingApi.getAutopilotLive(
        'minute30',
        'SWING',
        winRateThresholdMode,
        fixedMinMarketWinRate,
        STRATEGY_CODE_SWING
      ),
    refetchInterval: 5000,
  });

  const positionAutopilotLiveQuery = useQuery<AutopilotLiveResponse>({
    queryKey: [
      'guided-autopilot-live',
      'day',
      'POSITION',
      winRateThresholdMode,
      fixedMinMarketWinRate,
      STRATEGY_CODE_POSITION,
    ],
    queryFn: () =>
      guidedTradingApi.getAutopilotLive(
        'day',
        'POSITION',
        winRateThresholdMode,
        fixedMinMarketWinRate,
        STRATEGY_CODE_POSITION
      ),
    refetchInterval: 5000,
  });

  const startMutation = useMutation({
    mutationFn: (payload: GuidedStartRequest) => guidedTradingApi.start(payload),
    onSuccess: () => {
      setStatusMessage('자동매매 시작 주문이 접수되었습니다.');
      void chartQuery.refetch();
    },
    onError: (e: unknown) => {
      setStatusMessage(e instanceof Error ? e.message : '주문 실패');
    },
  });

  const stopMutation = useMutation({
    mutationFn: () => guidedTradingApi.stop(selectedMarket),
    onSuccess: () => {
      setStatusMessage('자동매매 포지션 정지/청산 요청을 전송했습니다.');
      void chartQuery.refetch();
    },
    onError: (e: unknown) => {
      const axiosMsg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      const msg = axiosMsg || (e instanceof Error ? e.message : '정지 실패');
      setStatusMessage(msg);
      alert(msg);
    },
  });

  const syncMutation = useMutation({
    mutationFn: () => guidedTradingApi.syncPositions(),
    onSuccess: (result) => {
      setStatusMessage(result.message);
      void todayStatsQuery.refetch();
      void openPositionsQuery.refetch();
      void chartQuery.refetch();
    },
    onError: (e: unknown) => {
      setStatusMessage(e instanceof Error ? e.message : '동기화 실패');
    },
  });

  const recommendation = chartQuery.data?.recommendation;
  const activePosition = chartQuery.data?.activePosition;
  const events = chartQuery.data?.events ?? [];
  const orderbook = chartQuery.data?.orderbook;
  const orderSnapshot = chartQuery.data?.orderSnapshot;
  const marketOrderHistory = useMemo<GuidedOrderItem[]>(() => {
    if (!orderSnapshot) return [];
    const merged: GuidedOrderItem[] = [];
    if (orderSnapshot.currentOrder) merged.push(orderSnapshot.currentOrder);
    merged.push(...orderSnapshot.pendingOrders);
    merged.push(...orderSnapshot.completedOrders);

    const deduped = new Map<string, GuidedOrderItem>();
    for (const order of merged) {
      if (!order?.uuid) continue;
      const prev = deduped.get(order.uuid);
      if (!prev) {
        deduped.set(order.uuid, order);
        continue;
      }
      const prevDate = prev.createdAt ? Date.parse(prev.createdAt) : 0;
      const nextDate = order.createdAt ? Date.parse(order.createdAt) : 0;
      if (nextDate >= prevDate) {
        deduped.set(order.uuid, order);
      }
    }

    return Array.from(deduped.values()).sort((a, b) => {
      const ta = a.createdAt ? Date.parse(a.createdAt) : 0;
      const tb = b.createdAt ? Date.parse(b.createdAt) : 0;
      return tb - ta;
    });
  }, [orderSnapshot]);
  const marketOrderStats = useMemo(() => {
    let done = 0;
    let wait = 0;
    let cancel = 0;
    let buyRequested = 0;
    let buyFilled = 0;
    let sellRequested = 0;
    let sellFilled = 0;
    for (const order of marketOrderHistory) {
      const state = order.state?.trim().toLowerCase();
      const side = normalizeOrderSide(order.side);
      if (state === 'done') done += 1;
      else if (state === 'wait') wait += 1;
      else if (state === 'cancel') cancel += 1;

      const isRequested = state === 'wait' || state === 'done' || state === 'cancel';
      if (side === 'BUY' && isRequested) buyRequested += 1;
      if (side === 'SELL' && isRequested) sellRequested += 1;
      if (side === 'BUY' && state === 'done') buyFilled += 1;
      if (side === 'SELL' && state === 'done') sellFilled += 1;
    }
    return { done, wait, cancel, buyRequested, buyFilled, sellRequested, sellFilled };
  }, [marketOrderHistory]);

  const { plan, currentPrice: planCurrentPrice, startPlan, cancelPlan, dismissPlan, isRunning: isPlanRunning } =
    usePlanExecution({
      market: selectedMarket,
      activePosition,
      recommendation,
      amountKrw,
      customStopLoss: typeof customStopLoss === 'number' ? customStopLoss : null,
      customTakeProfit: typeof customTakeProfit === 'number' ? customTakeProfit : null,
      onComplete: () => void chartQuery.refetch(),
    });

  const filteredMarkets = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    const rows = marketsQuery.data ?? [];
    if (!keyword) return rows;
    return rows.filter((row) =>
      row.market.toLowerCase().includes(keyword) ||
      row.koreanName.toLowerCase().includes(keyword) ||
      row.symbol.toLowerCase().includes(keyword)
    );
  }, [marketsQuery.data, search]);
  const focusedScalpMarketSet = useMemo(() => new Set(focusedScalpParsed.markets), [focusedScalpParsed.markets]);

  const applyFocusedScalpMarkets = useCallback((nextMarkets: string[]) => {
    setFocusedScalpMarketsInput(nextMarkets.join(', '));
  }, []);

  const addFocusedScalpMarket = useCallback((market: string) => {
    const normalized = normalizeFocusedScalpMarket(market);
    if (!normalized) return false;
    if (focusedScalpMarketSet.has(normalized)) return true;
    if (focusedScalpParsed.markets.length >= 8) {
      setStatusMessage('선택 코인 단타는 최대 8개까지만 등록할 수 있습니다.');
      return false;
    }
    applyFocusedScalpMarkets([...focusedScalpParsed.markets, normalized]);
    setStatusMessage(`${normalized} 선택 코인 단타에 추가`);
    return true;
  }, [applyFocusedScalpMarkets, focusedScalpMarketSet, focusedScalpParsed.markets]);

  const removeFocusedScalpMarket = useCallback((market: string) => {
    const normalized = normalizeFocusedScalpMarket(market);
    if (!normalized) return;
    if (!focusedScalpMarketSet.has(normalized)) return;
    applyFocusedScalpMarkets(focusedScalpParsed.markets.filter((item) => item !== normalized));
    setStatusMessage(`${normalized} 선택 코인 단타에서 제거`);
  }, [applyFocusedScalpMarkets, focusedScalpMarketSet, focusedScalpParsed.markets]);

  const clearFocusedScalpMarkets = useCallback(() => {
    applyFocusedScalpMarkets([]);
    setStatusMessage('선택 코인 단타 목록을 초기화했습니다.');
  }, [applyFocusedScalpMarkets]);

  const openFocusedScalpWindow = useCallback((market?: string) => {
    const normalized = normalizeFocusedScalpMarket(market ?? selectedMarket) ?? selectedMarket;
    setFocusedScalpTargetMarket(normalized);
    setFocusedScalpWindowOpen(true);
  }, [selectedMarket]);

  const handleMarketContextMenu = useCallback((event: React.MouseEvent<HTMLButtonElement>, item: GuidedMarketItem) => {
    event.preventDefault();
    const menuWidth = 240;
    const menuHeight = 132;
    setSelectedMarket(item.market);
    setFocusedScalpContextMenu({
      x: Math.max(8, Math.min(event.clientX, window.innerWidth - menuWidth)),
      y: Math.max(8, Math.min(event.clientY, window.innerHeight - menuHeight)),
      market: item.market,
      koreanName: item.koreanName,
      included: focusedScalpMarketSet.has(item.market),
    });
  }, [focusedScalpMarketSet]);

  const focusedScalpDecisionCards = useMemo<FocusedScalpDecisionCardView[]>(() => {
    const marketNameMap = new Map((marketsQuery.data ?? []).map((item) => [item.market, item.koreanName]));
    const openPositionMap = new Map(
      (openPositionsQuery.data ?? [])
        .filter((position) => position.status === 'OPEN' || position.status === 'PENDING_ENTRY')
        .map((position) => [position.market, position])
    );
    const workerMap = new Map(autopilotState.workers.map((worker) => [worker.market, worker]));

    const entryActions = new Set(['ENTRY_SUCCESS', 'ENTRY_FAILED', 'LLM_REJECT', 'BUY_REQUESTED', 'BUY_FILLED', 'CANCELLED']);
    const manageActions = new Set(['POSITION_REVIEW', 'PARTIAL_TP', 'POSITION_EXIT', 'SUPERVISION', 'SELL_REQUESTED', 'SELL_FILLED']);

    return focusedScalpParsed.markets.map((market) => {
      const worker = workerMap.get(market);
      const openPosition = openPositionMap.get(market);
      const marketEvents = autopilotState.events.filter((event) => event.market === market);
      const entryEvent = marketEvents.find((event) => entryActions.has(event.action));
      const manageEvent = marketEvents.find((event) => manageActions.has(event.action));
      const entry = classifyFocusedEntryEvent(entryEvent);
      const manage = classifyFocusedManageEvent(manageEvent);
      const recentEvents = marketEvents
        .filter((event) => event.type === 'WORKER' || event.type === 'ORDER' || event.type === 'LLM' || event.type === 'SYSTEM')
        .slice(0, 6);

      return {
        market,
        koreanName: marketNameMap.get(market) ?? market,
        workerStatus: worker?.status ?? 'IDLE',
        workerNote: worker?.note ?? '워커 대기',
        positionStatus: openPosition?.status ?? 'NO_POSITION',
        entryState: entry.state,
        entryLabel: entry.label,
        entryDetail: entry.detail,
        entryAt: entry.at,
        manageState: manage.state,
        manageLabel: manage.label,
        manageDetail: manage.detail,
        manageAt: manage.at,
        recentEvents,
      };
    });
  }, [autopilotState.events, autopilotState.workers, focusedScalpParsed.markets, marketsQuery.data, openPositionsQuery.data]);

  const focusedScalpDecisionSummary = useMemo<FocusedScalpDecisionSummaryView>(() => {
    let entered = 0;
    let pending = 0;
    let noEntry = 0;
    let takeProfit = 0;
    let stopLoss = 0;
    let hold = 0;
    let supervision = 0;

    for (const card of focusedScalpDecisionCards) {
      if (card.entryState === 'ENTERED') entered += 1;
      if (card.entryState === 'PENDING') pending += 1;
      if (card.entryState === 'NO_ENTRY') noEntry += 1;
      if (card.manageState === 'TAKE_PROFIT') takeProfit += 1;
      if (card.manageState === 'STOP_LOSS') stopLoss += 1;
      if (card.manageState === 'HOLD') hold += 1;
      if (card.manageState === 'SUPERVISION') supervision += 1;
    }

    return { entered, pending, noEntry, takeProfit, stopLoss, hold, supervision };
  }, [focusedScalpDecisionCards]);

  const connectMcpAndPlaywright = useCallback(async (options?: ConnectPlaywrightOptions) => {
    const tradingMcpUrl = deriveMcpUrl();
    let resolvedPlaywrightUrl = playwrightMcpUrl.trim();
    let status = await getPlaywrightStatus();
    const shouldAutoStart = (options?.allowAutoStart ?? true) && playwrightEnabled && playwrightAutoStart;

    if (shouldAutoStart && !status?.running) {
      status = await startPlaywrightMcp({ port: playwrightMcpPort, host: '127.0.0.1' });
    }

    if (status?.url) {
      resolvedPlaywrightUrl = status.url;
    }
    if (status?.port && status.port > 0 && status.port !== playwrightMcpPort) {
      setPlaywrightMcpPort(status.port);
    }
    if (status?.url && status.url !== playwrightMcpUrl) {
      setPlaywrightMcpUrl(status.url);
    }
    setPlaywrightStatus(status);

    const servers: DesktopMcpServerConfig[] = [{ serverId: 'trading', url: tradingMcpUrl }];
    if (playwrightEnabled && status?.running && resolvedPlaywrightUrl) {
      servers.push({ serverId: 'playwright', url: resolvedPlaywrightUrl });
    }

    const tools = await connectMcpServers(servers);
    setMcpTools(tools);
    setMcpConnected(tools.length > 0);
  }, [playwrightAutoStart, playwrightEnabled, playwrightMcpPort, playwrightMcpUrl]);

  const handlePlaywrightStart = useCallback(async () => {
    setPlaywrightAction('starting');
    try {
      const next = await startPlaywrightMcp({ port: playwrightMcpPort, host: '127.0.0.1' });
      setPlaywrightStatus(next);
      if (next?.port && next.port > 0) {
        setPlaywrightMcpPort(next.port);
      }
      if (next?.url) {
        setPlaywrightMcpUrl(next.url);
      }
      await connectMcpAndPlaywright({ allowAutoStart: false });
      if (next?.port && next.port !== playwrightMcpPort) {
        setStatusMessage(`Playwright MCP 시작 및 연결 완료 (포트 자동 전환: ${playwrightMcpPort} -> ${next.port})`);
      } else {
        setStatusMessage('Playwright MCP 시작 및 연결 완료');
      }
    } catch (error) {
      setStatusMessage(error instanceof Error ? error.message : 'Playwright MCP 시작 실패');
    } finally {
      setPlaywrightAction('idle');
    }
  }, [connectMcpAndPlaywright, playwrightMcpPort]);

  const handlePlaywrightStop = useCallback(async () => {
    setPlaywrightAction('stopping');
    try {
      const next = await stopPlaywrightMcp();
      setPlaywrightStatus(next);
      await connectMcpAndPlaywright({ allowAutoStart: false });
      setStatusMessage('Playwright MCP 중지');
    } catch (error) {
      setStatusMessage(error instanceof Error ? error.message : 'Playwright MCP 중지 실패');
    } finally {
      setPlaywrightAction('idle');
    }
  }, [connectMcpAndPlaywright]);

  // MCP 연결 및 도구 목록 로드
  useEffect(() => {
    connectMcpAndPlaywright().catch(() => { /* MCP 실패해도 채팅은 가능 */ });
  }, [connectMcpAndPlaywright]);

  useEffect(() => {
    let active = true;
    const syncStatus = async () => {
      const status = await getPlaywrightStatus();
      if (active) setPlaywrightStatus(status);
    };
    syncStatus().catch(() => undefined);
    const timer = window.setInterval(() => {
      void syncStatus();
    }, 2000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  // 채팅 스크롤
  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatMessages, chatStreamText]);

  // 저장
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const next: WorkspacePrefs = {
      selectedMarket,
      interval,
      sortBy,
      sortDirection,
      aiRefreshSec,
      tradingMode,
      monitorTab,
      autopilotEnabled,
      swingAutopilotEnabled,
      positionAutopilotEnabled,
      dailyLossLimitKrw,
      autopilotMaxConcurrentPositions,
      autopilotAmountKrw,
      autopilotCapitalPoolKrw,
      autopilotInterval,
      autopilotMode,
      entryPolicy,
      entryOrderMode,
      pendingEntryTimeoutSec,
      marketFallbackAfterCancel,
      playwrightEnabled,
      playwrightAutoStart,
      playwrightMcpPort,
      playwrightMcpUrl,
      autopilotDockCollapsed,
      winRateThresholdMode,
      fixedMinMarketWinRate,
      minLlmConfidence,
      llmDailyTokenCap,
      llmRiskReserveTokens,
      rejectCooldownSeconds,
      postExitCooldownMinutes,
      focusedScalpEnabled,
      focusedScalpMarkets: focusedScalpParsed.markets,
      focusedScalpPollIntervalSec,
      focusedScalpDockCollapsed,
      investmentDockCollapsed,
    };
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  }, [
    selectedMarket,
    interval,
    sortBy,
    sortDirection,
    aiRefreshSec,
    tradingMode,
    monitorTab,
    autopilotEnabled,
    swingAutopilotEnabled,
    positionAutopilotEnabled,
    dailyLossLimitKrw,
    autopilotMaxConcurrentPositions,
    autopilotAmountKrw,
    autopilotCapitalPoolKrw,
    autopilotInterval,
    autopilotMode,
    entryPolicy,
    entryOrderMode,
    pendingEntryTimeoutSec,
    marketFallbackAfterCancel,
    playwrightEnabled,
    playwrightAutoStart,
    playwrightMcpPort,
    playwrightMcpUrl,
    autopilotDockCollapsed,
    winRateThresholdMode,
    fixedMinMarketWinRate,
    minLlmConfidence,
    llmDailyTokenCap,
    llmRiskReserveTokens,
    rejectCooldownSeconds,
    postExitCooldownMinutes,
    focusedScalpEnabled,
    focusedScalpParsed.markets,
    focusedScalpPollIntervalSec,
    focusedScalpDockCollapsed,
    investmentDockCollapsed,
  ]);

  useEffect(() => {
    if (!focusedScalpContextMenu) return;
    const close = () => setFocusedScalpContextMenu(null);
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        close();
      }
    };
    window.addEventListener('click', close);
    window.addEventListener('scroll', close, true);
    window.addEventListener('resize', close);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('click', close);
      window.removeEventListener('scroll', close, true);
      window.removeEventListener('resize', close);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [focusedScalpContextMenu]);

  useEffect(() => {
    if (!focusedScalpWindowOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setFocusedScalpWindowOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [focusedScalpWindowOpen]);

  // 차트 초기화
  useEffect(() => {
    const container = chartContainerRef.current;
    if (!container) return;

    if (!chartRef.current) {
      const chart = createChart(container, {
        height: 460,
        layout: {
          background: { type: ColorType.Solid, color: '#0f1117' },
          textColor: '#cfd4dc',
          fontFamily: 'Inter, system-ui, sans-serif',
        },
        grid: {
          vertLines: { color: '#1f2530' },
          horzLines: { color: '#1f2530' },
        },
        rightPriceScale: { borderColor: '#2d3543' },
        timeScale: {
          borderColor: '#2d3543',
          timeVisible: true,
          secondsVisible: false,
          tickMarkFormatter: (time: UTCTimestamp) => formatKstTime(time as number),
        },
        localization: {
          timeFormatter: (time: number) => formatKstDateTime(time),
        },
      });

      const candlestick = chart.addSeries(CandlestickSeries, {
        upColor: '#ff5a6a',
        downColor: '#4c88ff',
        borderUpColor: '#ff5a6a',
        borderDownColor: '#4c88ff',
        wickUpColor: '#ff5a6a',
        wickDownColor: '#4c88ff',
      });

      chartRef.current = chart;
      candleSeriesRef.current = candlestick;
    }

    const resizeObserver = new ResizeObserver(() => {
      if (!chartRef.current || !chartContainerRef.current) return;
      chartRef.current.applyOptions({ width: chartContainerRef.current.clientWidth });
    });
    resizeObserver.observe(container);

    return () => {
      resizeObserver.disconnect();
      if (chartRef.current) chartRef.current.remove();
      chartRef.current = null;
      candleSeriesRef.current = null;
      priceLinesRef.current = [];
    };
  }, []);

  useEffect(() => {
    shouldAutoFitRef.current = true;
    liveCandlesRef.current = [];
    const series = candleSeriesRef.current;
    const chart = chartRef.current;
    if (series) {
      series.setData([]);
      createSeriesMarkers(series, []);
      priceLinesRef.current.forEach((line) => series.removePriceLine(line));
      priceLinesRef.current = [];
    }
    if (chart) {
      chart.priceScale('right').applyOptions({
        autoScale: true,
        mode: 0,
        invertScale: false,
      });
    }
  }, [selectedMarket, interval]);

  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    chart.applyOptions({
      timeScale: {
        borderColor: '#2d3543',
        timeVisible: true,
        secondsVisible: interval === 'tick',
      },
    });
  }, [interval]);

  useEffect(() => {
    const series = candleSeriesRef.current;
    const chart = chartRef.current;
    const payload = chartQuery.data;
    if (!series || !chart || !payload) return;

    let candlesticks: CandlestickData<Time>[] = payload.candles.map(toCandlestick);
    if (candlesticks.length === 0) {
      const fallback = buildFallbackCandle(payload, interval);
      candlesticks = fallback ? [fallback] : [];
    }
    series.setData(candlesticks);
    liveCandlesRef.current = candlesticks;

    priceLinesRef.current.forEach((line) => series.removePriceLine(line));
    priceLinesRef.current = [];

    priceLinesRef.current.push(series.createPriceLine({
      price: payload.recommendation.recommendedEntryPrice,
      color: '#f5c542', lineStyle: 2, lineWidth: 1, title: '추천 매수가',
    }));

    // AI key levels (SMA20, SMA60, 지지선)
    if (payload.recommendation.keyLevels) {
      for (const kl of payload.recommendation.keyLevels) {
        priceLinesRef.current.push(series.createPriceLine({
          price: kl.price,
          color: keyLevelColor(kl.label),
          lineStyle: 1,
          lineWidth: 1,
          title: kl.label,
        }));
      }
    }

    if (payload.activePosition) {
      const isFilled = payload.activePosition.status === 'OPEN';
      const entryLabel = isFilled ? '내 매수가' : '주문가 (미체결)';
      const entryColor = isFilled ? '#41dba6' : '#a0a0a0';
      const entryStyle = isFilled ? 1 : 3; // 1=Dashed, 3=LargeDashed
      priceLinesRef.current.push(series.createPriceLine({ price: payload.activePosition.averageEntryPrice, color: entryColor, lineStyle: entryStyle, lineWidth: 2, title: entryLabel }));
      priceLinesRef.current.push(series.createPriceLine({ price: payload.activePosition.takeProfitPrice, color: '#ff4d67', lineStyle: 2, lineWidth: 1, title: '익절' }));
      priceLinesRef.current.push(series.createPriceLine({ price: payload.activePosition.stopLossPrice, color: '#4c88ff', lineStyle: 2, lineWidth: 1, title: '손절' }));
      if (payload.activePosition.trailingStopPrice) {
        priceLinesRef.current.push(series.createPriceLine({ price: payload.activePosition.trailingStopPrice, color: '#b084ff', lineStyle: 0, lineWidth: 1, title: '트레일링' }));
      }
    }

    const markers: SeriesMarker<Time>[] = events
      .filter((event) => event.price != null)
      .map((event) => {
        const isExit = event.eventType.includes('CLOSE') || event.eventType.includes('TAKE');
        return {
          time: asUtc(new Date(event.createdAt).getTime()),
          position: (isExit ? 'aboveBar' : 'belowBar') as 'aboveBar' | 'belowBar',
          color: isExit ? '#ff6078' : '#3ecf9f',
          shape: (isExit ? 'arrowDown' : 'arrowUp') as 'arrowDown' | 'arrowUp',
          text: event.eventType,
        };
      });
    createSeriesMarkers(series, markers);

    if (shouldAutoFitRef.current) {
      chart.priceScale('right').applyOptions({
        autoScale: true,
        mode: 0,
        invertScale: false,
      });
      chart.timeScale().fitContent();
      const visibleRange = computeVisiblePriceRange(payload, candlesticks);
      if (visibleRange) {
        chart.priceScale('right').setVisibleRange(visibleRange);
      }
      shouldAutoFitRef.current = false;
    }
  }, [chartQuery.data, events, interval]);

  // 실시간 캔들 업데이트
  useEffect(() => {
    const series = candleSeriesRef.current;
    const ticker = tickerQuery.data;
    const payload = chartQuery.data;
    if (!series || !ticker || !payload) return;
    if (ticker.market !== payload.market || payload.market !== selectedMarket) return;
    if (ticker.tradePrice <= 0) return;

    const nowSec = ticker.timestamp
      ? (ticker.timestamp > 10_000_000_000 ? Math.floor(ticker.timestamp / 1000) : Math.floor(ticker.timestamp))
      : Math.floor(Date.now() / 1000);
    const bucket = toBucketStart(nowSec, interval);

    const candles = [...liveCandlesRef.current];
    if (candles.length === 0) return;

    const last = candles[candles.length - 1];
    const lastSec = Number(last.time);
    if (bucket < lastSec) return;

    if (bucket === lastSec) {
      const updated: CandlestickData<Time> = {
        ...last,
        high: Math.max(last.high, ticker.tradePrice),
        low: Math.min(last.low, ticker.tradePrice),
        close: ticker.tradePrice,
      };
      candles[candles.length - 1] = updated;
      liveCandlesRef.current = candles;
      series.update(updated);
      return;
    }

    const next: CandlestickData<Time> = {
      time: asUtc(bucket),
      open: last.close,
      high: ticker.tradePrice,
      low: ticker.tradePrice,
      close: ticker.tradePrice,
    };
    candles.push(next);
    liveCandlesRef.current = candles;
    series.update(next);
  }, [tickerQuery.data, interval, chartQuery.data, selectedMarket]);

  useEffect(() => {
    if (!recommendation) return;
    if (orderType === 'LIMIT' && limitPrice === '') {
      setLimitPrice(recommendation.recommendedEntryPrice);
    }
  }, [recommendation, orderType, limitPrice]);

  const handleApplyRecommendedLimit = () => {
    if (!recommendation) return;
    setOrderType('LIMIT');
    setLimitPrice(recommendation.recommendedEntryPrice);
    setStatusMessage('추천 매수가를 지정가에 적용했습니다.');
  };

  const handleCheckStatus = useCallback(async () => {
    setLlmStatus('checking');
    try {
      const status = await checkConnection();
      setLlmStatus(status);
    } catch {
      setLlmStatus('error');
    }
  }, []);

  const handleLogin = useCallback(async () => {
    setLlmStatus('checking');
    try {
      await startLogin();
      setLlmStatus('connected');
      setStatusMessage('OpenAI 로그인 완료.');
    } catch (error) {
      setLlmStatus('error');
      setStatusMessage(error instanceof Error ? error.message : 'OpenAI 로그인 실패');
    }
  }, []);

  const handleLogout = useCallback(async () => {
    await logout();
    setLlmStatus('disconnected');
    clearConversation();
    setChatMessages([]);
    setStatusMessage('OpenAI 로그아웃 완료.');
  }, []);

  // 에이전트 액션 실행
  const applyAgentActionToOrderForm = (action: AgentAction) => {
    const normalizedType = action.type.toUpperCase();
    if (normalizedType === 'ADD' || normalizedType === 'WAIT_RETEST') {
      setOrderType('LIMIT');
      if (typeof action.targetPrice === 'number' && Number.isFinite(action.targetPrice)) setLimitPrice(action.targetPrice);
      if (typeof action.sizePercent === 'number' && Number.isFinite(action.sizePercent)) {
        setAmountKrw(Math.max(5100, Math.round((amountKrw * action.sizePercent) / 100)));
      }
      setStatusMessage(`에이전트 액션(${action.title})을 주문 폼에 반영.`);
      setActiveTab('order');
      return;
    }
    if (normalizedType === 'PARTIAL_TP' || normalizedType === 'FULL_EXIT') {
      if (typeof action.targetPrice === 'number') setCustomTakeProfit(action.targetPrice);
      setStatusMessage(`${action.title}을 확인하세요.`);
      setActiveTab('order');
      return;
    }
    setStatusMessage(`관망/대기 액션(${action.title}) 확인.`);
  };

  const executeAgentAction = (action: AgentAction) => {
    const normalizedType = action.type.toUpperCase();
    const urgency = action.urgency.toUpperCase();
    const requiresConfirm = urgency === 'HIGH' || normalizedType === 'FULL_EXIT' || normalizedType === 'PARTIAL_TP';

    if (requiresConfirm) {
      const ok = window.confirm(`[${normalizedType}] ${action.title}\n${action.reason}`);
      if (!ok) return;
    }

    startPlan(action);
    setActiveTab('order');
  };

  const pauseAutopilotForManualMarket = useCallback((reason: string) => {
    if (autopilotEnabled) {
      autopilotRef.current?.pauseMarket(selectedMarket, 30 * 60 * 1000, reason);
    }
    if (swingAutopilotEnabled) {
      swingAutopilotRef.current?.pauseMarket(selectedMarket, 30 * 60 * 1000, reason);
    }
    if (positionAutopilotEnabled) {
      positionAutopilotRef.current?.pauseMarket(selectedMarket, 30 * 60 * 1000, reason);
    }
  }, [autopilotEnabled, positionAutopilotEnabled, selectedMarket, swingAutopilotEnabled]);

  const handleStart = () => {
    if (!recommendation) return;
    pauseAutopilotForManualMarket('수동 진입으로 워커 30분 일시정지');
    const payload: GuidedStartRequest = {
      market: selectedMarket, amountKrw, orderType,
      limitPrice: orderType === 'LIMIT' && typeof limitPrice === 'number' ? limitPrice : undefined,
      stopLossPrice: typeof customStopLoss === 'number' ? customStopLoss : recommendation.stopLossPrice,
      takeProfitPrice: typeof customTakeProfit === 'number' ? customTakeProfit : recommendation.takeProfitPrice,
      interval,
      mode: tradingMode,
      entrySource: 'MANUAL',
      strategyCode: 'GUIDED_TRADING',
    };
    startMutation.mutate(payload);
  };

  const handleOneClickEntry = () => {
    if (!recommendation) return;
    pauseAutopilotForManualMarket('수동 원클릭 진입으로 워커 30분 일시정지');
    const ot = recommendation.suggestedOrderType === 'MARKET' ? 'MARKET' : 'LIMIT';
    const payload: GuidedStartRequest = {
      market: selectedMarket,
      amountKrw,
      orderType: ot,
      limitPrice: ot === 'LIMIT' ? recommendation.recommendedEntryPrice : undefined,
      stopLossPrice: recommendation.stopLossPrice,
      takeProfitPrice: recommendation.takeProfitPrice,
      interval,
      mode: tradingMode,
      entrySource: 'MANUAL',
      strategyCode: 'GUIDED_TRADING',
    };
    startMutation.mutate(payload);
  };

  const handlePartialTakeProfit = async (ratio = 0.5) => {
    try {
      pauseAutopilotForManualMarket('수동 부분익절로 워커 30분 일시정지');
      await guidedTradingApi.partialTakeProfit(selectedMarket, ratio);
      void chartQuery.refetch();
      setStatusMessage(`부분 익절 ${Math.round(ratio * 100)}% 실행.`);
    } catch (e) {
      setStatusMessage(e instanceof Error ? e.message : '부분 익절 실패');
    }
  };

  useEffect(() => {
    if ((autopilotEnabled || swingAutopilotEnabled || positionAutopilotEnabled) && llmStatus !== 'connected') {
      setStatusMessage('오토파일럿은 OpenAI 로그인 상태에서만 시작할 수 있습니다.');
    }
  }, [autopilotEnabled, llmStatus, positionAutopilotEnabled, swingAutopilotEnabled]);

  const resolveEngineAmount = useCallback((budgetKrw: number, maxPositions: number): number => {
    const perSlot = Math.floor(budgetKrw / Math.max(1, maxPositions));
    return Math.max(5100, Math.min(20_000, Math.min(autopilotAmountKrw, perSlot)));
  }, [autopilotAmountKrw]);

  useEffect(() => {
    if (!autopilotEnabled) {
      autopilotRef.current?.stop();
      autopilotRef.current = null;
      setAutopilotState((prev) => ({ ...prev, enabled: false }));
      return;
    }
    if (llmStatus !== 'connected') return;

    const budgetKrw = Math.max(5100, Math.round(autopilotCapitalPoolKrw * ENGINE_CAPITAL_RATIOS.SCALP));
    const maxConcurrent = Math.max(1, autopilotMaxConcurrentPositions);
    const orchestrator = new AutopilotOrchestrator(
      {
        enabled: true,
        interval: 'minute1',
        confirmInterval: 'minute10',
        tradingMode: 'SCALP',
        amountKrw: resolveEngineAmount(budgetKrw, maxConcurrent),
        dailyLossLimitKrw,
        maxConcurrentPositions: maxConcurrent,
        winRateThresholdMode,
        fixedMinMarketWinRate,
        minLlmConfidence,
        candidateLimit: 15,
        rejectCooldownMs: rejectCooldownSeconds * 1000,
        postExitCooldownMs: postExitCooldownMinutes * 60 * 1000,
        workerTickMs: 8000,
        llmReviewIntervalMs: 8000,
        llmModel: chatModel,
        playwrightEnabled,
        entryPolicy,
        entryOrderMode,
        pendingEntryTimeoutSec,
        marketFallbackAfterCancel,
        llmDailySoftCap: 240,
        llmDailyTokenCap,
        llmRiskReserveTokens,
        focusedScalpEnabled,
        focusedScalpMarkets: focusedScalpParsed.markets,
        focusedScalpPollIntervalMs: focusedScalpPollIntervalSec * 1000,
        focusedWarnHoldingMs: 90 * 60 * 1000,
        focusedMaxHoldingMs: 120 * 60 * 1000,
        focusedEntryGate: 'FAST_ONLY',
        strategyCode: STRATEGY_CODE_SCALP,
        strategyCodePrefix: STRATEGY_CODE_SCALP,
        capitalBudgetKrw: budgetKrw,
        fineAgentEnabled: false,
        fineAgentMode: 'LITE',
        llmEntryReviewMaxPerTick: 1,
      },
      {
        onState: (next) => setAutopilotState(next),
        onLog: () => undefined,
      }
    );

    autopilotRef.current = orchestrator;
    orchestrator.start();
    return () => {
      orchestrator.stop();
      if (autopilotRef.current === orchestrator) {
        autopilotRef.current = null;
      }
    };
  }, [
    autopilotEnabled,
    llmStatus,
    autopilotAmountKrw,
    autopilotCapitalPoolKrw,
    autopilotMaxConcurrentPositions,
    chatModel,
    dailyLossLimitKrw,
    entryOrderMode,
    entryPolicy,
    fixedMinMarketWinRate,
    focusedScalpEnabled,
    focusedScalpParsed.markets,
    focusedScalpPollIntervalSec,
    marketFallbackAfterCancel,
    minLlmConfidence,
    llmDailyTokenCap,
    llmRiskReserveTokens,
    pendingEntryTimeoutSec,
    playwrightEnabled,
    postExitCooldownMinutes,
    rejectCooldownSeconds,
    resolveEngineAmount,
    winRateThresholdMode,
  ]);

  useEffect(() => {
    if (!swingAutopilotEnabled) {
      swingAutopilotRef.current?.stop();
      swingAutopilotRef.current = null;
      setSwingAutopilotState((prev) => ({ ...prev, enabled: false }));
      return;
    }
    if (llmStatus !== 'connected') return;

    const budgetKrw = Math.max(5100, Math.round(autopilotCapitalPoolKrw * ENGINE_CAPITAL_RATIOS.SWING));
    const maxConcurrent = 2;
    const orchestrator = new AutopilotOrchestrator(
      {
        enabled: true,
        interval: 'minute30',
        confirmInterval: 'minute240',
        tradingMode: 'SWING',
        amountKrw: resolveEngineAmount(budgetKrw, maxConcurrent),
        dailyLossLimitKrw,
        maxConcurrentPositions: maxConcurrent,
        winRateThresholdMode,
        fixedMinMarketWinRate,
        minLlmConfidence,
        candidateLimit: 10,
        rejectCooldownMs: rejectCooldownSeconds * 1000,
        postExitCooldownMs: postExitCooldownMinutes * 60 * 1000,
        workerTickMs: 12_000,
        llmReviewIntervalMs: 12_000,
        llmModel: chatModel,
        playwrightEnabled: false,
        entryPolicy,
        entryOrderMode,
        pendingEntryTimeoutSec,
        marketFallbackAfterCancel,
        llmDailySoftCap: 180,
        llmDailyTokenCap,
        llmRiskReserveTokens,
        focusedScalpEnabled: false,
        focusedScalpMarkets: [],
        focusedScalpPollIntervalMs: 20_000,
        focusedWarnHoldingMs: 90 * 60 * 1000,
        focusedMaxHoldingMs: 120 * 60 * 1000,
        focusedEntryGate: 'FAST_ONLY',
        strategyCode: STRATEGY_CODE_SWING,
        strategyCodePrefix: STRATEGY_CODE_SWING,
        capitalBudgetKrw: budgetKrw,
        marketAllowlist: INVEST_MAJOR_MARKETS,
        fineAgentEnabled: true,
        fineAgentMode: 'LITE',
        llmEntryReviewMaxPerTick: 1,
      },
      {
        onState: (next) => setSwingAutopilotState(next),
        onLog: () => undefined,
      }
    );

    swingAutopilotRef.current = orchestrator;
    orchestrator.start();
    return () => {
      orchestrator.stop();
      if (swingAutopilotRef.current === orchestrator) {
        swingAutopilotRef.current = null;
      }
    };
  }, [
    swingAutopilotEnabled,
    llmStatus,
    autopilotCapitalPoolKrw,
    dailyLossLimitKrw,
    winRateThresholdMode,
    fixedMinMarketWinRate,
    minLlmConfidence,
    llmDailyTokenCap,
    llmRiskReserveTokens,
    rejectCooldownSeconds,
    postExitCooldownMinutes,
    chatModel,
    entryPolicy,
    entryOrderMode,
    pendingEntryTimeoutSec,
    marketFallbackAfterCancel,
    resolveEngineAmount,
  ]);

  useEffect(() => {
    if (!positionAutopilotEnabled) {
      positionAutopilotRef.current?.stop();
      positionAutopilotRef.current = null;
      setPositionAutopilotState((prev) => ({ ...prev, enabled: false }));
      return;
    }
    if (llmStatus !== 'connected') return;

    const budgetKrw = Math.max(5100, Math.round(autopilotCapitalPoolKrw * ENGINE_CAPITAL_RATIOS.POSITION));
    const maxConcurrent = 1;
    const orchestrator = new AutopilotOrchestrator(
      {
        enabled: true,
        interval: 'day',
        confirmInterval: 'day',
        tradingMode: 'POSITION',
        amountKrw: resolveEngineAmount(budgetKrw, maxConcurrent),
        dailyLossLimitKrw,
        maxConcurrentPositions: maxConcurrent,
        winRateThresholdMode,
        fixedMinMarketWinRate,
        minLlmConfidence,
        candidateLimit: 8,
        rejectCooldownMs: rejectCooldownSeconds * 1000,
        postExitCooldownMs: postExitCooldownMinutes * 60 * 1000,
        workerTickMs: 20_000,
        llmReviewIntervalMs: 20_000,
        llmModel: chatModel,
        playwrightEnabled: false,
        entryPolicy,
        entryOrderMode,
        pendingEntryTimeoutSec,
        marketFallbackAfterCancel,
        llmDailySoftCap: 120,
        llmDailyTokenCap,
        llmRiskReserveTokens,
        focusedScalpEnabled: false,
        focusedScalpMarkets: [],
        focusedScalpPollIntervalMs: 20_000,
        focusedWarnHoldingMs: 90 * 60 * 1000,
        focusedMaxHoldingMs: 120 * 60 * 1000,
        focusedEntryGate: 'FAST_ONLY',
        strategyCode: STRATEGY_CODE_POSITION,
        strategyCodePrefix: STRATEGY_CODE_POSITION,
        capitalBudgetKrw: budgetKrw,
        marketAllowlist: INVEST_MAJOR_MARKETS,
        fineAgentEnabled: true,
        fineAgentMode: 'LITE',
        llmEntryReviewMaxPerTick: 1,
      },
      {
        onState: (next) => setPositionAutopilotState(next),
        onLog: () => undefined,
      }
    );

    positionAutopilotRef.current = orchestrator;
    orchestrator.start();
    return () => {
      orchestrator.stop();
      if (positionAutopilotRef.current === orchestrator) {
        positionAutopilotRef.current = null;
      }
    };
  }, [
    positionAutopilotEnabled,
    llmStatus,
    autopilotCapitalPoolKrw,
    dailyLossLimitKrw,
    winRateThresholdMode,
    fixedMinMarketWinRate,
    minLlmConfidence,
    llmDailyTokenCap,
    llmRiskReserveTokens,
    rejectCooldownSeconds,
    postExitCooldownMinutes,
    chatModel,
    entryPolicy,
    entryOrderMode,
    pendingEntryTimeoutSec,
    marketFallbackAfterCancel,
    resolveEngineAmount,
  ]);

  const positionState: PositionState = derivePositionState(
    activePosition,
    tickerQuery.data?.tradePrice ?? recommendation?.currentPrice ?? 0
  );
  const currentTickerPrice = tickerQuery.data?.tradePrice ?? recommendation?.currentPrice ?? 0;

  // 채팅 메시지 전송
  const handleSendChat = useCallback(async () => {
    if (chatBusy || !chatInput.trim()) return;
    if (llmStatus !== 'connected') {
      setStatusMessage('OpenAI 미연결 상태.');
      return;
    }

    const userText = chatInput.trim();
    setChatInput('');
    setChatBusy(true);
    setChatStreamText('');

    try {
      const context = autoContext ? (agentContextQuery.data ?? null) : null;
      const llmTools = mcpTools;

      const newMessages = await sendChatMessage({
        userMessage: userText,
        model: chatModel,
        context,
        mcpTools: llmTools.length > 0 ? llmTools : undefined,
        tradingMode,
        onStreamDelta: (text) => setChatStreamText(text),
        onToolCall: (name, args) => {
          setChatMessages((prev) => [
            ...prev,
            {
              id: crypto.randomUUID(),
              role: 'tool' as const,
              content: '',
              timestamp: Date.now(),
              toolCall: { name, args },
            },
          ]);
        },
        onToolResult: (name, result) => {
          setChatMessages((prev) => {
            const updated = [...prev];
            for (let i = updated.length - 1; i >= 0; i--) {
              if (updated[i].toolCall?.name === name && !updated[i].toolCall?.result) {
                updated[i] = {
                  ...updated[i],
                  toolCall: { ...updated[i].toolCall!, result },
                };
                break;
              }
            }
            return updated;
          });
        },
      });

      // 도구 메시지는 이미 onToolCall/onToolResult에서 추가됨
      // user/assistant 메시지만 추가
      setChatMessages((prev) => [
        ...prev,
        ...newMessages.filter((m) => m.role === 'user' || m.role === 'assistant'),
      ]);
      setChatStreamText('');
    } catch (e) {
      const errMsg = e instanceof Error ? e.message : '채팅 오류';
      setChatMessages((prev) => [
        ...prev,
        { id: crypto.randomUUID(), role: 'system', content: errMsg, timestamp: Date.now() },
      ]);
    } finally {
      setChatBusy(false);
    }
  }, [chatBusy, chatInput, chatModel, llmStatus, autoContext, agentContextQuery.data, mcpTools, tradingMode]);

  // 자동 분석
  useEffect(() => {
    chatBusyRef.current = chatBusy;
  }, [chatBusy]);

  useEffect(() => {
    agentContextRef.current = agentContextQuery.data ?? null;
  }, [agentContextQuery.data]);

  useEffect(() => {
    mcpToolsRef.current = mcpTools;
  }, [mcpTools]);

  useEffect(() => {
    if (!autoAnalysis || llmStatus !== 'connected') return;

    let cancelled = false;
    const runAutoAnalysis = () => {
      if (cancelled) return;
      if (chatBusyRef.current || autoAnalysisInFlightRef.current) return;

      const context = agentContextRef.current;
      if (!context) return;

      autoAnalysisInFlightRef.current = true;
      chatBusyRef.current = true;
      setChatBusy(true);
      setChatStreamText('');

      const llmTools = mcpToolsRef.current;
      sendChatMessage({
        userMessage: '현재 시점 분석과 조언을 부탁합니다.',
        model: chatModel,
        context,
        mcpTools: llmTools.length > 0 ? llmTools : undefined,
        tradingMode,
        onStreamDelta: (text) => setChatStreamText(text),
      })
        .then((msgs) => {
          if (cancelled) return;
          setChatMessages((prev) => [...prev, ...msgs]);
          setChatStreamText('');
        })
        .catch((error) => {
          if (cancelled) return;
          const msg = error instanceof Error ? error.message : '자동 분석 실패';
          setChatMessages((prev) => [
            ...prev,
            { id: crypto.randomUUID(), role: 'system', content: `[자동분석 실패] ${msg}`, timestamp: Date.now() },
          ]);
        })
        .finally(() => {
          autoAnalysisInFlightRef.current = false;
          chatBusyRef.current = false;
          setChatBusy(false);
        });
    };

    runAutoAnalysis();
    const timer = window.setInterval(runAutoAnalysis, 15000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [autoAnalysis, chatModel, llmStatus, tradingMode]);

  useEffect(() => {
    void handleCheckStatus();
  }, [handleCheckStatus]);

  // 마켓 변경시 대화 초기화
  useEffect(() => {
    clearConversation();
    setChatMessages([]);
  }, [selectedMarket]);

  // ---------- 렌더링 ----------

  const totalToolCount = mcpTools.length;
  const playwrightRunning = playwrightStatus?.running ?? false;
  const playwrightStarting = playwrightAction === 'starting' || playwrightStatus?.status === 'starting';
  const playwrightStopping = playwrightAction === 'stopping';

  return (
    <section className="guided-workspace">
      <header className="guided-header">
        <div>
          <h2>수동 코인 트레이딩 워크스페이스</h2>
          <p>코인 선택 → 차트 확인 → 추천 매수가/직접 가격으로 진입 → 자동 익절/손절/물타기 관리</p>
        </div>
        {todayStatsQuery.data && (
          <div className="today-stats-bar">
            <span className="today-stats-label">오늘 수익</span>
            <span className={`today-stats-pnl ${todayStatsQuery.data.totalPnlKrw >= 0 ? 'positive' : 'negative'}`}>
              {todayStatsQuery.data.totalPnlKrw >= 0 ? '+' : ''}{todayStatsQuery.data.totalPnlKrw.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}원
            </span>
            <span className="today-stats-detail">
              {todayStatsQuery.data.totalTrades}건 ({todayStatsQuery.data.wins}승 {todayStatsQuery.data.losses}패)
              {todayStatsQuery.data.totalTrades > 0 && ` · 승률 ${todayStatsQuery.data.winRate.toFixed(0)}%`}
            </span>
            {todayStatsQuery.data.openPositionCount > 0 && (
              <span className="today-stats-invested">
                투자중 {todayStatsQuery.data.totalInvestedKrw.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}원
              </span>
            )}
            <button
              className="today-stats-sync-btn"
              onClick={() => syncMutation.mutate()}
              disabled={syncMutation.isPending}
              title="DB와 실제 빗썸 잔고를 동기화합니다"
            >
              {syncMutation.isPending ? '...' : 'Sync'}
            </button>
          </div>
        )}
      </header>

      <div className="guided-grid">
        {/* 좌측: 마켓 보드 */}
        <aside className="guided-market-board">
          <div className="guided-board-toolbar">
            <input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="코인 검색 (BTC, 비트코인)"
            />
            <div className="guided-sort-controls">
              <select value={sortBy} onChange={(event) => setSortBy(event.target.value as GuidedMarketSortBy)}>
                <option value="TURNOVER">거래대금</option>
                <option value="CHANGE_RATE">변동률</option>
                <option value="VOLUME">거래량</option>
                <option value="SURGE_RATE">급등률</option>
                <option value="MARKET_CAP_FLOW">시총변동(대체)</option>
                <option value="RECOMMENDED_ENTRY_WIN_RATE">추천가 승률</option>
                <option value="MARKET_ENTRY_WIN_RATE">현재가 승률</option>
              </select>
              <select value={sortDirection} onChange={(event) => setSortDirection(event.target.value as GuidedSortDirection)}>
                <option value="DESC">높은순</option>
                <option value="ASC">낮은순</option>
              </select>
            </div>
          </div>
          <div className="guided-market-list">
            {filteredMarkets.map((item) => (
              <button
                key={item.market}
                className={`guided-market-row ${selectedMarket === item.market ? 'active' : ''} ${focusedScalpMarketSet.has(item.market) ? 'focused' : ''}`}
                onClick={() => setSelectedMarket(item.market)}
                onContextMenu={(event) => handleMarketContextMenu(event, item)}
                type="button"
              >
                <div className="name-wrap">
                  <strong>{item.koreanName}</strong>
                  <span>
                    {item.market}
                    {focusedScalpMarketSet.has(item.market) && <em className="focused-chip">단타</em>}
                  </span>
                </div>
                <div className="price-wrap">
                  <strong>{formatKrw(item.tradePrice)}</strong>
                  <span className={item.changeRate >= 0 ? 'up' : 'down'}>{formatPct(item.changeRate)}</span>
                  {winRateSort ? (
                    <>
                      <small className="winrate-line">
                        {`추천 ${formatWinRate(item.recommendedEntryWinRate)} · 현재 ${formatWinRate(item.marketEntryWinRate)}`}
                      </small>
                      <small className="turnover-line">{`거래대금 ${formatVolume(item.accTradePrice)}`}</small>
                    </>
                  ) : (
                    <small>{`거래대금 ${formatVolume(item.accTradePrice)}`}</small>
                  )}
                </div>
              </button>
            ))}
          </div>

          {/* 내 포지션 */}
          {openPositionsQuery.data && openPositionsQuery.data.length > 0 && (
            <div className="my-positions-section">
              <div className="my-positions-header">내 포지션 ({openPositionsQuery.data.length})</div>
              {openPositionsQuery.data.map((pos) => (
                <button
                  key={pos.tradeId}
                  type="button"
                  className={`my-position-row ${selectedMarket === pos.market ? 'active' : ''}`}
                  onClick={() => setSelectedMarket(pos.market)}
                >
                  <div className="my-pos-left">
                    <strong>{pos.market.replace('KRW-', '')}</strong>
                    <span className={`my-pos-status ${pos.status === 'OPEN' ? 'open' : 'pending'}`}>
                      {pos.status === 'OPEN' ? '보유' : '대기'}
                    </span>
                  </div>
                  <div className="my-pos-right">
                    <span className={pos.unrealizedPnlPercent >= 0 ? 'profit' : 'loss'}>
                      {formatPct(pos.unrealizedPnlPercent)}
                    </span>
                    <small>{formatKrw(pos.currentPrice)}</small>
                  </div>
                </button>
              ))}
            </div>
          )}
        </aside>

        {/* 중앙: 차트 */}
        <div className="guided-chart-panel">
          <div className="guided-chart-toolbar">
            <strong>{selectedMarket}</strong>
            <div className="intervals">
              {['tick', 'minute1', 'minute10', 'minute30', 'minute60', 'day'].map((item) => (
                <button
                  key={item}
                  className={interval === item ? 'active' : ''}
                  onClick={() => setIntervalValue(item)}
                  type="button"
                >
                  {item === 'tick' ? '틱' : item === 'day' ? '일' : `${item.replace('minute', '')}분`}
                </button>
              ))}
            </div>
          </div>
          <div className="guided-chart-shell">
            <div className="guided-chart" ref={chartContainerRef} />
            {recommendation && (
              <div className="guided-winrate-overlay">
                <div className="winrate-header">
                  <span>추천가 기준 예상 승률</span>
                  <strong>{(recommendation.recommendedEntryWinRate ?? recommendation.predictedWinRate).toFixed(1)}%</strong>
                </div>
                <div className="winrate-bar">
                  <div
                    className="winrate-fill"
                    style={{ width: `${Math.min(100, Math.max(0, recommendation.recommendedEntryWinRate ?? recommendation.predictedWinRate))}%` }}
                  />
                </div>
                <div className="winrate-factors">
                  <span><Tip label="추세">추세</Tip> {(recommendation.winRateBreakdown.trend * 100).toFixed(0)}</span>
                  <span><Tip label="눌림">눌림</Tip> {(recommendation.winRateBreakdown.pullback * 100).toFixed(0)}</span>
                  <span><Tip label="변동성">변동성</Tip> {(recommendation.winRateBreakdown.volatility * 100).toFixed(0)}</span>
                  <span><Tip label="RR">RR</Tip> {(recommendation.winRateBreakdown.riskReward * 100).toFixed(0)}</span>
                </div>
              </div>
            )}
          </div>

          {recommendation && (
            <div className="guided-recommendation">
              <div><span>현재가</span><strong>{formatKrw(recommendation.currentPrice)}</strong></div>
              <div>
                <span>추천 매수가</span><strong>{formatKrw(recommendation.recommendedEntryPrice)}</strong>
                <button type="button" className="guided-link-button" onClick={handleApplyRecommendedLimit}>지정가에 적용</button>
              </div>
              <div><span><Tip label="손절">손절가</Tip></span><strong>{formatKrw(recommendation.stopLossPrice)}</strong></div>
              <div><span><Tip label="익절">익절가</Tip></span><strong>{formatKrw(recommendation.takeProfitPrice)}</strong></div>
              <div><span><Tip label="신뢰도">신뢰도</Tip></span><strong>{(recommendation.confidence * 100).toFixed(1)}%</strong></div>
              <div><span><Tip label="추천가 승률">추천가 승률</Tip></span><strong>{(recommendation.recommendedEntryWinRate ?? recommendation.predictedWinRate).toFixed(1)}%</strong></div>
              <div><span><Tip label="현재가 승률">현재가 승률</Tip></span><strong>{(recommendation.marketEntryWinRate ?? recommendation.predictedWinRate).toFixed(1)}%</strong></div>
              <div><span><Tip label="Risk/Reward">Risk/Reward</Tip></span><strong>{recommendation.riskRewardRatio.toFixed(2)}R</strong></div>
            </div>
          )}

          {orderbook && (
            <div className="guided-orderbook-card">
              <div className="guided-orderbook-head">
                <strong>빗썸 호가</strong>
                <span>
                  스프레드 {orderbook.spread != null ? formatPlain(orderbook.spread) : '-'}
                  {orderbook.spreadPercent != null ? ` (${orderbook.spreadPercent.toFixed(3)}%)` : ''}
                </span>
              </div>
              <div className="guided-orderbook-best">
                <div><span>최우선 매도</span><strong>{orderbook.bestAsk != null ? formatKrw(orderbook.bestAsk) : '-'}</strong></div>
                <div><span>최우선 매수</span><strong>{orderbook.bestBid != null ? formatKrw(orderbook.bestBid) : '-'}</strong></div>
              </div>
              <div className="guided-orderbook-grid">
                <div className="head">매도호가</div><div className="head">매도수량</div>
                <div className="head">매수호가</div><div className="head">매수수량</div>
                {orderbook.units.slice(0, 6).map((unit, idx) => (
                  <div key={`row-${idx}`} className="guided-orderbook-row">
                    <div className="ask">{formatPlain(unit.askPrice)}</div>
                    <div>{formatPlain(unit.askSize)}</div>
                    <div className="bid">{formatPlain(unit.bidPrice)}</div>
                    <div>{formatPlain(unit.bidSize)}</div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {orderSnapshot && (
            <div className="guided-trade-history-card">
              <div className="guided-trade-history-head">
                <strong>{selectedMarket} 주문/체결 내역</strong>
                <span>최근 {Math.min(20, marketOrderHistory.length)} / 총 {marketOrderHistory.length}건</span>
              </div>
              <div className="guided-trade-history-summary">
                <span className="funnel buy-requested">매수요청 {marketOrderStats.buyRequested}</span>
                <span className="funnel buy-filled">매수체결 {marketOrderStats.buyFilled}</span>
                <span className="funnel sell-requested">매도요청 {marketOrderStats.sellRequested}</span>
                <span className="funnel sell-filled">매도체결 {marketOrderStats.sellFilled}</span>
                <span className="done">체결 {marketOrderStats.done}</span>
                <span className="wait">대기 {marketOrderStats.wait}</span>
                <span className="cancel">취소 {marketOrderStats.cancel}</span>
              </div>
              {marketOrderHistory.length === 0 ? (
                <div className="guided-trade-history-empty">최근 주문 내역이 없습니다.</div>
              ) : (
                <div className="guided-trade-history-list">
                  {marketOrderHistory.slice(0, 20).map((order) => {
                    const side = normalizeOrderSide(order.side);
                    const state = order.state?.trim().toLowerCase();
                    const stateClass = state === 'done' ? 'done' : state === 'wait' ? 'wait' : state === 'cancel' ? 'cancel' : 'unknown';
                    const volume = order.volume != null && Number.isFinite(order.volume) ? order.volume : null;
                    const executedVolume = order.executedVolume != null && Number.isFinite(order.executedVolume)
                      ? order.executedVolume
                      : null;
                    const fillRatio = volume != null && volume > 0 && executedVolume != null
                      ? Math.min(100, (executedVolume / volume) * 100)
                      : null;
                    return (
                      <div key={order.uuid} className="guided-trade-history-row">
                        <div className="left">
                          <span className={`side ${side === 'BUY' ? 'buy' : side === 'SELL' ? 'sell' : 'unknown'}`}>
                            {side === 'BUY' ? '매수' : side === 'SELL' ? '매도' : '-'}
                          </span>
                          <span className={`state ${stateClass}`}>
                            {orderStateLabel(order.state)}
                          </span>
                          <span className="ordtype">{orderTypeLabel(order.ordType)}</span>
                        </div>
                        <div className="mid">
                          <strong>{order.price != null ? formatKrw(order.price) : '시장가'}</strong>
                          <small>
                            체결 {executedVolume != null ? formatPlain(executedVolume) : '-'}
                            {` / 주문 ${volume != null ? formatPlain(volume) : '-'}`}
                            {fillRatio != null ? ` (${fillRatio.toFixed(0)}%)` : ''}
                          </small>
                        </div>
                        <div className="right">{formatOrderDateTime(order.createdAt)}</div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </div>

        {/* 우측: 탭 기반 패널 */}
        <aside className="guided-order-panel">
          {/* 탭 바 */}
          <div className="guided-tab-bar">
            <button
              type="button"
              className={activeTab === 'order' ? 'active' : ''}
              onClick={() => setActiveTab('order')}
            >
              주문/포지션{isPlanRunning && <span className="plan-running-dot" />}
            </button>
            <button
              type="button"
              className={activeTab === 'chat' ? 'active' : ''}
              onClick={() => setActiveTab('chat')}
            >
              AI 채팅 (고급)
            </button>
          </div>

          {/* 주문/포지션 탭 — 상태 기반 */}
          {activeTab === 'order' && (
            <div className="guided-tab-content">
              {/* AI 컨트롤 */}
              <div className="guided-ai-controls-bar">
                <button
                  type="button"
                  className={`guided-ai-toggle ${aiEnabled ? 'on' : 'off'}`}
                  onClick={() => setAiEnabled(!aiEnabled)}
                >
                  AI {aiEnabled ? 'ON' : 'OFF'}
                </button>
                <div className="guided-mode-selector">
                  {(['SCALP', 'SWING', 'POSITION'] as const).map((m) => (
                    <button
                      key={m}
                      type="button"
                      className={`guided-mode-btn ${tradingMode === m ? 'active' : ''}`}
                      onClick={() => setTradingMode(m)}
                    >
                      {m === 'SCALP' ? '초단타' : m === 'SWING' ? '단타' : '장타'}
                    </button>
                  ))}
                </div>
                <div className="guided-interval-slider">
                  <input
                    type="range"
                    min={1}
                    max={60}
                    step={1}
                    value={aiRefreshSec}
                    onChange={(e) => setAiRefreshSec(Number(e.target.value))}
                    disabled={!aiEnabled}
                    list="ai-interval-ticks"
                  />
                  <datalist id="ai-interval-ticks">
                    <option value="1" />
                    <option value="3" />
                    <option value="5" />
                    <option value="7" />
                    <option value="10" />
                    <option value="15" />
                    <option value="30" />
                    <option value="60" />
                  </datalist>
                  <span className="guided-interval-value">{aiRefreshSec}s</span>
                </div>
              </div>

              <div className="autopilot-panel">
                <div className="autopilot-header">
                  <strong>멀티 오토파일럿 엔진</strong>
                  <label className="autopilot-switch">
                    <input
                      type="checkbox"
                      checked={autopilotEnabled}
                      onChange={(event) => setAutopilotEnabled(event.target.checked)}
                    />
                    <span>{autopilotEnabled ? 'SCALP ON' : 'SCALP OFF'}</span>
                  </label>
                </div>
                <div className="autopilot-grid">
                  <label className="autopilot-checkbox inline">
                    <input
                      type="checkbox"
                      checked={swingAutopilotEnabled}
                      onChange={(event) => setSwingAutopilotEnabled(event.target.checked)}
                    />
                    SWING 엔진 ON
                  </label>
                  <label className="autopilot-checkbox inline">
                    <input
                      type="checkbox"
                      checked={positionAutopilotEnabled}
                      onChange={(event) => setPositionAutopilotEnabled(event.target.checked)}
                    />
                    POSITION 엔진 ON
                  </label>
                </div>
                <div className="autopilot-row">
                  <label>
                    엔진 총 예산 풀(원)
                    <input
                      type="number"
                      min={20000}
                      step={1000}
                      value={autopilotCapitalPoolKrw}
                      onChange={(event) => setAutopilotCapitalPoolKrw(Math.max(20_000, Math.round(Number(event.target.value || DEFAULT_AUTOPILOT_CAPITAL_POOL_KRW))))}
                    />
                  </label>
                  <small>
                    비율: SCALP 40% / SWING 35% / POSITION 25%
                  </small>
                </div>
                <div className="autopilot-row">
                  <label>
                    일일 손실 제한
                    <input
                      type="number"
                      value={dailyLossLimitKrw}
                      step={1000}
                      onChange={(event) => setDailyLossLimitKrw(Number(event.target.value || -30000))}
                    />
                  </label>
                  <small>기본 -30,000원</small>
                </div>
                <div className="autopilot-grid">
                  <label>
                    포지션당 투자금(원)
                    <input
                      type="number"
                      min={5100}
                      step={100}
                      value={autopilotAmountKrw}
                      onChange={(event) => setAutopilotAmountKrw(Math.max(5100, Math.round(Number(event.target.value || 10000))))}
                    />
                  </label>
                  <label>
                    동시 포지션 최대(종목)
                    <input
                      type="number"
                      min={1}
                      max={10}
                      step={1}
                      value={autopilotMaxConcurrentPositions}
                      onChange={(event) => {
                        const raw = Number(event.target.value || 6);
                        setAutopilotMaxConcurrentPositions(Math.min(10, Math.max(1, raw)));
                      }}
                    />
                  </label>
                </div>
                <div className="autopilot-row">
                  <small>엔진 주기: SCALP(1m/10m), SWING(30m/240m), POSITION(day/day)</small>
                </div>
                <div className="autopilot-grid">
                  <label className="autopilot-checkbox inline">
                    <input
                      type="checkbox"
                      checked={focusedScalpEnabled}
                      onChange={(event) => setFocusedScalpEnabled(event.target.checked)}
                    />
                    선택 코인 단타 루프 사용
                  </label>
                  <label>
                    선택 코인 확인 주기(초)
                    <input
                      type="number"
                      min={5}
                      max={300}
                      step={1}
                      value={focusedScalpPollIntervalSec}
                      onChange={(event) => {
                        const raw = Number(event.target.value || 20);
                        setFocusedScalpPollIntervalSec(Math.min(300, Math.max(5, Math.round(raw))));
                      }}
                    />
                  </label>
                </div>
                <div className="focused-scalp-ux">
                  <div className="focused-scalp-ux-head">
                    <strong>선택 코인 단타 주문창</strong>
                    <div className="focused-scalp-ux-actions">
                      <button type="button" onClick={() => openFocusedScalpWindow()}>
                        주문창 열기
                      </button>
                      <button
                        type="button"
                        className="ghost"
                        onClick={clearFocusedScalpMarkets}
                        disabled={focusedScalpParsed.markets.length === 0}
                      >
                        목록 비우기
                      </button>
                    </div>
                  </div>
                  <small>왼쪽 코인 리스트에서 코인을 우클릭해 추가/제거하세요. (최대 8개)</small>
                  <div className="focused-scalp-chip-list">
                    {focusedScalpParsed.markets.length === 0 ? (
                      <span className="focused-scalp-chip-empty">등록된 코인이 없습니다.</span>
                    ) : (
                      focusedScalpParsed.markets.map((market) => (
                        <span key={market} className="focused-scalp-chip">
                          {market}
                          <button
                            type="button"
                            onClick={() => removeFocusedScalpMarket(market)}
                            aria-label={`${market} 제거`}
                          >
                            ×
                          </button>
                        </span>
                      ))
                    )}
                  </div>
                  {focusedScalpEnabled && focusedScalpParsed.markets.length === 0 && (
                    <small className="autopilot-warning">선택 코인 단타 루프가 켜져 있지만 등록된 코인이 없습니다.</small>
                  )}
                </div>
                <div className="autopilot-grid">
                  <label>
                    진입 정책
                    <select
                      value={entryPolicy}
                      onChange={(event) => setEntryPolicy(event.target.value as 'BALANCED' | 'AGGRESSIVE' | 'CONSERVATIVE')}
                    >
                      <option value="BALANCED">균형형</option>
                      <option value="AGGRESSIVE">공격형</option>
                      <option value="CONSERVATIVE">보수형</option>
                    </select>
                  </label>
                  <label>
                    주문 정책
                    <select
                      value={entryOrderMode}
                      onChange={(event) => setEntryOrderMode(event.target.value as 'ADAPTIVE' | 'MARKET' | 'LIMIT')}
                    >
                      <option value="ADAPTIVE">적응형</option>
                      <option value="MARKET">시장가</option>
                      <option value="LIMIT">지정가</option>
                    </select>
                  </label>
                </div>
                <div className="autopilot-grid">
                  <label>
                    PENDING_ENTRY 타임아웃(초)
                    <input
                      type="number"
                      min={30}
                      max={900}
                      step={1}
                      value={pendingEntryTimeoutSec}
                      onChange={(event) => {
                        const raw = Number(event.target.value || 45);
                        setPendingEntryTimeoutSec(Math.min(900, Math.max(30, Math.round(raw))));
                      }}
                    />
                  </label>
                  <label className="autopilot-checkbox inline">
                    <input
                      type="checkbox"
                      checked={marketFallbackAfterCancel}
                      onChange={(event) => setMarketFallbackAfterCancel(event.target.checked)}
                    />
                    타임아웃 취소 후 시장가 폴백
                  </label>
                </div>
                <div className="autopilot-grid">
                  <label>
                    승률 임계값 모드
                    <select
                      value={winRateThresholdMode}
                      onChange={(event) => setWinRateThresholdMode(event.target.value as 'DYNAMIC_P70' | 'FIXED')}
                    >
                      <option value="DYNAMIC_P70">동적(P70)</option>
                      <option value="FIXED">고정</option>
                    </select>
                  </label>
                  <label>
                    고정 최소 현재가 승률(%)
                    <input
                      type="number"
                      min={50}
                      max={80}
                      step={0.5}
                      value={fixedMinMarketWinRate}
                      onChange={(event) => setFixedMinMarketWinRate(Number(event.target.value || 60))}
                      disabled={winRateThresholdMode !== 'FIXED'}
                    />
                  </label>
                </div>
                <div className="autopilot-grid">
                  <label>
                    LLM 최소 신뢰도
                    <input
                      type="number"
                      min={0}
                      max={100}
                      step={1}
                      value={minLlmConfidence}
                      onChange={(event) => setMinLlmConfidence(Number(event.target.value || 60))}
                    />
                  </label>
                  <label>
                    진입 거절/실패 쿨다운(초)
                    <input
                      type="number"
                      min={300}
                      max={3600}
                      step={1}
                      value={rejectCooldownSeconds}
                      onChange={(event) => {
                        const raw = Number(event.target.value || 300);
                        setRejectCooldownSeconds(Math.min(3600, Math.max(300, Math.round(raw))));
                      }}
                    />
                  </label>
                </div>
                <div className="autopilot-grid">
                  <label>
                    일일 LLM 토큰 상한
                    <input
                      type="number"
                      min={20000}
                      max={2000000}
                      step={1000}
                      value={llmDailyTokenCap}
                      onChange={(event) => {
                        const raw = Number(event.target.value || 200000);
                        setLlmDailyTokenCap(Math.min(2_000_000, Math.max(20_000, Math.round(raw))));
                      }}
                    />
                  </label>
                  <label>
                    리스크 리뷰 reserve(토큰)
                    <input
                      type="number"
                      min={0}
                      max={llmDailyTokenCap}
                      step={1000}
                      value={llmRiskReserveTokens}
                      onChange={(event) => {
                        const raw = Number(event.target.value || 40000);
                        setLlmRiskReserveTokens(Math.min(llmDailyTokenCap, Math.max(0, Math.round(raw))));
                      }}
                    />
                  </label>
                </div>
                <div className="autopilot-row">
                  <small>FineAgent 기본 모드: INVEST 전용 · LITE(SYNTH+PM만 LLM)</small>
                </div>
                <div className="autopilot-grid">
                  <label>
                    청산 후 쿨다운(분)
                    <input
                      type="number"
                      min={5}
                      max={180}
                      step={1}
                      value={postExitCooldownMinutes}
                      onChange={(event) => setPostExitCooldownMinutes(Number(event.target.value || 8))}
                    />
                  </label>
                </div>
                {autopilotState.blockedByDailyLoss && autopilotState.blockedReason && (
                  <p className="autopilot-warning">{autopilotState.blockedReason}</p>
                )}

                <div className="autopilot-divider" />

                <div className="autopilot-header">
                  <strong>Playwright MCP</strong>
                  <label className="autopilot-switch">
                    <input
                      type="checkbox"
                      checked={playwrightEnabled}
                      onChange={(event) => setPlaywrightEnabled(event.target.checked)}
                    />
                    <span>{playwrightEnabled ? '사용' : '미사용'}</span>
                  </label>
                </div>
                <div className="autopilot-grid">
                  <label>
                    포트
                    <input
                      type="number"
                      min={1024}
                      max={65535}
                      value={playwrightMcpPort}
                      onChange={(event) => {
                        const nextPort = Number(event.target.value || 8931);
                        setPlaywrightMcpPort(nextPort);
                        setPlaywrightMcpUrl(derivePlaywrightMcpUrl(nextPort));
                      }}
                    />
                  </label>
                  <label>
                    URL
                    <input
                      type="text"
                      value={playwrightMcpUrl}
                      onChange={(event) => setPlaywrightMcpUrl(event.target.value)}
                    />
                  </label>
                </div>
                <label className="autopilot-checkbox">
                  <input
                    type="checkbox"
                    checked={playwrightAutoStart}
                    onChange={(event) => setPlaywrightAutoStart(event.target.checked)}
                  />
                  앱 시작 시 Playwright MCP 자동 실행
                </label>
                <div className="autopilot-actions">
                  <button
                    type="button"
                    onClick={() => void handlePlaywrightStart()}
                    disabled={playwrightStarting || playwrightRunning}
                  >
                    {playwrightStarting ? '시작 중...' : playwrightRunning ? '실행 중' : 'Playwright 시작'}
                  </button>
                  <button
                    type="button"
                    onClick={() => void handlePlaywrightStop()}
                    className="danger"
                    disabled={playwrightStopping || !playwrightRunning}
                  >
                    {playwrightStopping ? '중지 중...' : playwrightRunning ? 'Playwright 중지' : '중지됨'}
                  </button>
                </div>
                <p className={`autopilot-meta ${playwrightRunning ? 'running' : 'stopped'}`}>
                  상태: {playwrightStatus?.status ?? 'unknown'}
                  {playwrightStatus?.url ? ` · ${playwrightStatus.url}` : ''}
                  {playwrightStatus?.lastError ? ` · 오류: ${playwrightStatus.lastError}` : ''}
                </p>

                <div className="autopilot-workers">
                  <strong>요약</strong>
                  <div className="autopilot-worker-row">
                    <span>실행 워커</span>
                    <span>{autopilotState.workers.length}개</span>
                  </div>
                  <div className="autopilot-worker-row">
                    <span>로컬 이벤트</span>
                    <span>{autopilotState.events.length}건</span>
                  </div>
                  <div className="autopilot-worker-row">
                    <span>후보</span>
                    <span>{autopilotState.candidates.length}개</span>
                  </div>
                </div>
              </div>

              {/* PlanPanel — 플랜 실행 중이면 주문 패널 대신 표시 */}
              {plan && (
                <PlanPanel
                  plan={plan}
                  currentPrice={planCurrentPrice}
                  onCancel={cancelPlan}
                  onDismiss={dismissPlan}
                />
              )}

              {/* State A: NONE — 포지션 없음 */}
              {!plan && positionState === 'NONE' && recommendation && (
                <div className="state-panel state-none">
                  <div className="state-header">
                    <span>{recommendation.confidence >= 0.5 ? '매수 유리' : '관망'}</span>
                    <strong><Tip label="신뢰도">신뢰도</Tip> {(recommendation.confidence * 100).toFixed(0)}%</strong>
                  </div>
                  <div className="guided-ai-winrate-bar">
                    <div className="guided-ai-winrate-label">
                      <span><Tip label="승률">승률</Tip></span>
                      <span>{(recommendation.recommendedEntryWinRate ?? recommendation.predictedWinRate).toFixed(1)}%</span>
                    </div>
                    <div className="guided-ai-bar-track">
                      <div
                        className="guided-ai-bar-fill"
                        style={{ width: `${Math.min(100, Math.max(0, recommendation.recommendedEntryWinRate ?? recommendation.predictedWinRate))}%` }}
                      />
                    </div>
                  </div>
                  <div className="guided-ai-prices">
                    <div><span>추천진입</span><strong>{formatPlain(recommendation.recommendedEntryPrice)}</strong></div>
                    <div>
                      <span><Tip label="손절">손절</Tip></span>
                      <strong className="loss">
                        {formatPlain(recommendation.stopLossPrice)}
                        <small> ({((recommendation.stopLossPrice - recommendation.recommendedEntryPrice) / recommendation.recommendedEntryPrice * 100).toFixed(1)}%)</small>
                      </strong>
                    </div>
                    <div>
                      <span><Tip label="익절">익절</Tip></span>
                      <strong className="profit">
                        {formatPlain(recommendation.takeProfitPrice)}
                        <small> (+{((recommendation.takeProfitPrice - recommendation.recommendedEntryPrice) / recommendation.recommendedEntryPrice * 100).toFixed(1)}%)</small>
                      </strong>
                    </div>
                    <div><span><Tip label="R/R">R/R</Tip></span><strong>{recommendation.riskRewardRatio.toFixed(2)}R</strong></div>
                  </div>

                  <div className="entry-amount-row">
                    <label className="entry-amount-label">매수 금액</label>
                    <div className="entry-amount-controls">
                      {[10000, 20000, 50000, 100000].map((v) => (
                        <button
                          key={v}
                          type="button"
                          className={`amount-chip ${amountKrw === v ? 'active' : ''}`}
                          onClick={() => setAmountKrw(v)}
                        >
                          {v >= 10000 ? `${v / 10000}만` : `${v.toLocaleString()}`}
                        </button>
                      ))}
                      <input
                        type="number"
                        className="entry-amount-input"
                        min={5100}
                        step={1000}
                        value={amountKrw}
                        onChange={(e) => setAmountKrw(Number(e.target.value || 0))}
                      />
                      <span className="entry-amount-unit">원</span>
                    </div>
                  </div>

                  <button
                    type="button"
                    className="one-click-entry"
                    onClick={handleOneClickEntry}
                    disabled={startMutation.isPending}
                  >
                    {startMutation.isPending ? '주문 중...' : `AI 추천대로 ${amountKrw.toLocaleString()}원 진입`}
                  </button>

                  <details className="advanced-settings" open={showAdvanced} onToggle={(e) => setShowAdvanced((e.target as HTMLDetailsElement).open)}>
                    <summary>고급 설정 (금액/가격 커스텀)</summary>
                    <div className="advanced-body">
                      <label>
                        금액(KRW)
                        <input type="number" min={5100} step={1000} value={amountKrw} onChange={(e) => setAmountKrw(Number(e.target.value || 0))} />
                      </label>
                      <label>
                        주문 방식
                        <select value={orderType} onChange={(e) => setOrderType(e.target.value as 'MARKET' | 'LIMIT')}>
                          <option value="LIMIT">지정가</option>
                          <option value="MARKET">시장가</option>
                        </select>
                      </label>
                      {orderType === 'LIMIT' && (
                        <label>지정가<input type="number" value={limitPrice} step="any" onChange={(e) => setLimitPrice(e.target.value ? Number(e.target.value) : '')} /></label>
                      )}
                      <label>손절가<input type="number" value={customStopLoss} step="any" placeholder={recommendation.stopLossPrice.toString()} onChange={(e) => setCustomStopLoss(e.target.value ? Number(e.target.value) : '')} /></label>
                      <label>익절가<input type="number" value={customTakeProfit} step="any" placeholder={recommendation.takeProfitPrice.toString()} onChange={(e) => setCustomTakeProfit(e.target.value ? Number(e.target.value) : '')} /></label>
                      <button type="button" className="custom-entry-btn" onClick={handleStart} disabled={startMutation.isPending}>
                        {startMutation.isPending ? '주문 중...' : '커스텀 설정으로 진입'}
                      </button>
                    </div>
                  </details>
                </div>
              )}

              {positionState === 'NONE' && !recommendation && aiEnabled && (
                <div className="state-panel state-none">
                  <div className="guided-ai-loading">데이터 로딩 중...</div>
                </div>
              )}
              {positionState === 'NONE' && !aiEnabled && (
                <div className="state-panel state-none">
                  <div className="guided-ai-loading">AI 분석 비활성화</div>
                </div>
              )}

              {/* State B: PENDING — 미체결 */}
              {!plan && positionState === 'PENDING' && activePosition && (
                <div className="state-panel state-pending">
                  <div className="state-header pending-header">
                    <span className="pending-dot" />
                    <strong>체결 대기 중</strong>
                  </div>
                  <div className="pending-info">
                    <div><span>주문가</span><strong>{formatKrw(activePosition.averageEntryPrice)}</strong></div>
                    <div><span>현재가</span><strong>{formatKrw(currentTickerPrice)}</strong></div>
                  </div>
                  <PendingTimer createdAt={activePosition.createdAt} />
                  <button
                    type="button"
                    className="cancel-order-btn"
                    onClick={() => {
                      pauseAutopilotForManualMarket('수동 주문취소로 워커 30분 일시정지');
                      stopMutation.mutate();
                    }}
                    disabled={stopMutation.isPending}
                  >
                    {stopMutation.isPending ? '취소 중...' : '주문 취소'}
                  </button>
                </div>
              )}

              {/* State C/D: OPEN / DANGER */}
              {!plan && (positionState === 'OPEN' || positionState === 'DANGER') && activePosition && (
                <div className={`state-panel state-open ${positionState === 'DANGER' ? 'danger' : ''}`}>
                  <div className="state-header">
                    <span><Tip label="미실현 손익">미실현 손익</Tip></span>
                    <strong className={activePosition.unrealizedPnlPercent >= 0 ? 'profit' : 'loss'}>
                      {formatPct(activePosition.unrealizedPnlPercent)}
                    </strong>
                  </div>
                  <div className="guided-ai-prices">
                    <div><span>진입가</span><strong>{formatPlain(activePosition.averageEntryPrice)}</strong></div>
                    <div><span>현재가</span><strong>{formatPlain(currentTickerPrice)}</strong></div>
                    <div><span><Tip label="손절">손절</Tip></span><strong className="loss">{formatPlain(activePosition.stopLossPrice)}</strong></div>
                    <div><span><Tip label="익절">익절</Tip></span><strong className="profit">{formatPlain(activePosition.takeProfitPrice)}</strong></div>
                  </div>

                  {/* 손절 거리 미터 */}
                  <div className="sl-meter">
                    <div className="sl-meter-label">
                      <span><Tip label="SL">SL까지</Tip></span>
                      <span>{Math.abs((currentTickerPrice - activePosition.stopLossPrice) / currentTickerPrice * 100).toFixed(2)}%</span>
                    </div>
                    <div className="sl-meter-track">
                      <div
                        className={`sl-meter-fill ${positionState === 'DANGER' ? 'danger' : ''}`}
                        style={{
                          width: `${Math.min(100, Math.max(0, Math.abs((currentTickerPrice - activePosition.stopLossPrice) / (activePosition.averageEntryPrice - activePosition.stopLossPrice)) * 100))}%`,
                        }}
                      />
                    </div>
                  </div>

                  <div className="position-meta">
                    <span><Tip label="물타기">물타기</Tip> {activePosition.dcaCount}/{activePosition.maxDcaCount}</span>
                    <span><Tip label="절반익절">{activePosition.halfTakeProfitDone ? '절반익절 완료' : '절반익절 대기'}</Tip></span>
                    <span><Tip label="트레일링">트레일링</Tip>: {activePosition.trailingActive ? '활성' : '비활성'}</span>
                  </div>

                  <div className="position-actions">
                    {!activePosition.halfTakeProfitDone && (
                      <button type="button" className="partial-tp-btn" onClick={() => void handlePartialTakeProfit(0.5)}>
                        부분 익절 50%
                      </button>
                    )}
                    <button
                      type="button"
                      className={`full-exit-btn ${positionState === 'DANGER' ? 'urgent' : ''}`}
                      onClick={() => {
                        pauseAutopilotForManualMarket('수동 청산으로 워커 30분 일시정지');
                        stopMutation.mutate();
                      }}
                      disabled={stopMutation.isPending}
                    >
                      {positionState === 'DANGER' ? '즉시 청산' : '전체 청산'}
                    </button>
                  </div>
                </div>
              )}

              {/* 성과 푸터 */}
              {agentContextQuery.data?.performance && (
                <div className="perf-footer">
                  <div><span>거래</span><strong>{agentContextQuery.data.performance.sampleSize}건</strong></div>
                  <div><span>승/패</span><strong>{agentContextQuery.data.performance.winCount}/{agentContextQuery.data.performance.lossCount}</strong></div>
                  <div><span><Tip label="승률">승률</Tip></span><strong>{agentContextQuery.data.performance.winRate.toFixed(0)}%</strong></div>
                  <div><span>평균</span><strong>{formatPct(agentContextQuery.data.performance.avgPnlPercent)}</strong></div>
                </div>
              )}
            </div>
          )}

          {/* AI 채팅 탭 */}
          {activeTab === 'chat' && (
            <div className="guided-chat-panel">
              {/* 상태바 */}
              <div className="guided-chat-statusbar">
                <div className="guided-provider-status">
                  <span className={`guided-status-dot ${openAiConnected ? 'ok' : 'off'}`} />
                  <span>
                    {llmStatus === 'checking' ? '확인 중' : llmStatus === 'error' ? '오류' : llmStatus === 'expired' ? '만료' : openAiConnected ? 'OpenAI 연결됨' : '미연결'}
                  </span>
                </div>
                <div className="guided-chat-status-actions">
                  {openAiConnected ? (
                    <button type="button" onClick={() => void handleLogout()}>로그아웃</button>
                  ) : (
                    <button type="button" onClick={() => void handleLogin()} disabled={providerChecking}>
                      {providerChecking ? '확인 중...' : '로그인'}
                    </button>
                  )}
                </div>
              </div>
              <div className="guided-model-selector">
                <label>모델</label>
                <select value={chatModel} onChange={(e) => setChatModel(e.target.value as CodexModelId)}>
                  {CODEX_MODELS.map((m) => (
                    <option key={m.id} value={m.id}>{m.label}</option>
                  ))}
                </select>
              </div>

              {/* 도구 목록 접이식 */}
              <button
                type="button"
                className="guided-tools-toggle"
                onClick={() => setShowTools(!showTools)}
              >
                사용 가능 도구 ({totalToolCount}개) {showTools ? '▲' : '▼'}
              </button>
              {showTools && (
                <div className="guided-tools-list">
                  {mcpTools.map((tool) => (
                    <div key={tool.qualifiedName || `${tool.serverId || 'default'}:${tool.name}`} className="guided-tool-item">
                      <code>{tool.qualifiedName || tool.name}</code>
                      <span>{tool.description || ''}</span>
                    </div>
                  ))}
                  {mcpTools.length === 0 && <p className="guided-tools-empty">{mcpConnected ? '등록된 도구 없음' : 'MCP 연결 중...'}</p>}
                </div>
              )}

              {/* 채팅 메시지 영역 */}
              <div className="guided-chat-messages">
                {chatMessages.length === 0 && !chatBusy && (
                  <div className="guided-chat-empty">
                    <p>AI 트레이딩 코파일럿</p>
                    <small>"{selectedMarket} 지금 어때?" 같은 질문을 입력하세요.</small>
                    <small>MCP 도구를 호출하여 실시간 데이터를 분석합니다.</small>
                  </div>
                )}

                {chatMessages.map((msg) => {
                  if (msg.role === 'tool' && msg.toolCall) {
                    return (
                      <div key={msg.id} className="guided-chat-message tool-call">
                        <div className="guided-chat-tool-header">도구 호출</div>
                        <code>{msg.toolCall.name}({msg.toolCall.args})</code>
                        {msg.toolCall.result && (
                          <pre className="guided-chat-tool-result">
                            {msg.toolCall.result.length > 300 ? msg.toolCall.result.slice(0, 300) + '...' : msg.toolCall.result}
                          </pre>
                        )}
                      </div>
                    );
                  }

                  return (
                    <div key={msg.id} className={`guided-chat-message ${msg.role}`}>
                      <div className="guided-chat-role">
                        {msg.role === 'user' ? '나' : msg.role === 'assistant' ? 'AI' : '시스템'}
                      </div>
                      <div className="guided-chat-content">{msg.content}</div>
                      {msg.actions && msg.actions.length > 0 && (
                        <div className="guided-chat-actions">
                          {msg.actions.map((action, idx) => (
                            <div key={idx} className="guided-chat-action-item">
                              <div className="guided-chat-action-top">
                                <strong>{action.title}</strong>
                                <span className={`urgency ${action.urgency.toLowerCase()}`}>{action.urgency}</span>
                              </div>
                              <p>{action.reason}</p>
                              <small>
                                {action.targetPrice != null ? `목표가 ${formatKrw(action.targetPrice)} · ` : ''}
                                {action.sizePercent != null ? `비중 ${action.sizePercent}%` : ''}
                              </small>
                              <div className="guided-chat-action-buttons">
                                <button type="button" onClick={() => applyAgentActionToOrderForm(action)}>주문 반영</button>
                                <button type="button" onClick={() => executeAgentAction(action)} disabled={isPlanRunning}>플랜 실행</button>
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                      <small className="guided-chat-time">
                        {new Date(msg.timestamp).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul' })}
                      </small>
                    </div>
                  );
                })}

                {chatBusy && chatStreamText && (
                  <div className="guided-chat-message assistant streaming">
                    <div className="guided-chat-role">AI</div>
                    <div className="guided-chat-content">{chatStreamText}</div>
                  </div>
                )}

                {chatBusy && !chatStreamText && (
                  <div className="guided-chat-message assistant streaming">
                    <div className="guided-chat-role">AI</div>
                    <div className="guided-chat-content guided-chat-thinking">분석 중...</div>
                  </div>
                )}

                <div ref={chatEndRef} />
              </div>

              {/* 입력 영역 */}
              <div className="guided-chat-input-area">
                <div className="guided-chat-input-row">
                  <input
                    value={chatInput}
                    onChange={(e) => setChatInput(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void handleSendChat(); } }}
                    placeholder={`${selectedMarket} 분석 질문...`}
                    disabled={chatBusy || !openAiConnected}
                  />
                  <button
                    type="button"
                    onClick={() => void handleSendChat()}
                    disabled={chatBusy || !chatInput.trim() || !openAiConnected}
                  >
                    전송
                  </button>
                </div>
                <div className="guided-chat-toggles">
                  <label>
                    <input type="checkbox" checked={autoContext} onChange={(e) => setAutoContext(e.target.checked)} />
                    자동 컨텍스트
                  </label>
                  <label>
                    <input type="checkbox" checked={autoAnalysis} onChange={(e) => setAutoAnalysis(e.target.checked)} />
                    15초 자동 분석
                  </label>
                </div>
              </div>
            </div>
          )}

          {statusMessage && <p className="guided-status">{statusMessage}</p>}
        </aside>
      </div>

      {focusedScalpContextMenu && (
        <div
          className="focused-scalp-context-menu"
          style={{ left: focusedScalpContextMenu.x, top: focusedScalpContextMenu.y }}
          onClick={(event) => event.stopPropagation()}
        >
          <div className="menu-title">
            {focusedScalpContextMenu.koreanName} <span>{focusedScalpContextMenu.market}</span>
          </div>
          <button
            type="button"
            onClick={() => {
              if (focusedScalpContextMenu.included) {
                removeFocusedScalpMarket(focusedScalpContextMenu.market);
              } else {
                addFocusedScalpMarket(focusedScalpContextMenu.market);
              }
              setFocusedScalpContextMenu(null);
            }}
          >
            {focusedScalpContextMenu.included ? '선택 코인 단타에서 제거' : '선택 코인 단타에 추가'}
          </button>
          <button
            type="button"
            onClick={() => {
              openFocusedScalpWindow(focusedScalpContextMenu.market);
              setFocusedScalpContextMenu(null);
            }}
          >
            단타 주문창 열기
          </button>
        </div>
      )}

      {focusedScalpWindowOpen && (
        <div className="focused-scalp-modal-backdrop" onClick={() => setFocusedScalpWindowOpen(false)}>
          <div className="focused-scalp-modal" onClick={(event) => event.stopPropagation()}>
            <div className="focused-scalp-modal-head">
              <strong>선택 코인 단타 주문창</strong>
              <button type="button" onClick={() => setFocusedScalpWindowOpen(false)}>닫기</button>
            </div>
            <label>
              대상 코인
              <select
                value={focusedScalpTargetMarket}
                onChange={(event) => setFocusedScalpTargetMarket(event.target.value)}
              >
                {!marketsQuery.data?.some((item) => item.market === focusedScalpTargetMarket) && (
                  <option value={focusedScalpTargetMarket}>{focusedScalpTargetMarket}</option>
                )}
                {(marketsQuery.data ?? []).map((item) => (
                  <option key={item.market} value={item.market}>
                    {item.market} · {item.koreanName}
                  </option>
                ))}
              </select>
            </label>
            <div className="focused-scalp-modal-actions">
              <button
                type="button"
                onClick={() => {
                  if (focusedScalpMarketSet.has(focusedScalpTargetMarket)) {
                    removeFocusedScalpMarket(focusedScalpTargetMarket);
                  } else {
                    addFocusedScalpMarket(focusedScalpTargetMarket);
                  }
                }}
              >
                {focusedScalpMarketSet.has(focusedScalpTargetMarket) ? '목록에서 제거' : '목록에 추가'}
              </button>
              <button
                type="button"
                className="ghost"
                onClick={() => {
                  setSelectedMarket(focusedScalpTargetMarket);
                  setFocusedScalpWindowOpen(false);
                }}
              >
                차트로 이동
              </button>
            </div>
            <div className="focused-scalp-modal-list">
              <strong>등록된 선택 코인 ({focusedScalpParsed.markets.length}/8)</strong>
              <div className="focused-scalp-chip-list">
                {focusedScalpParsed.markets.length === 0 ? (
                  <span className="focused-scalp-chip-empty">등록된 코인이 없습니다.</span>
                ) : (
                  focusedScalpParsed.markets.map((market) => (
                    <span key={market} className="focused-scalp-chip">
                      {market}
                      <button
                        type="button"
                        onClick={() => removeFocusedScalpMarket(market)}
                        aria-label={`${market} 제거`}
                      >
                        ×
                      </button>
                    </span>
                  ))
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      <div className="guided-monitor-tabs">
        <button
          type="button"
          className={monitorTab === 'SCALP' ? 'active' : ''}
          onClick={() => setMonitorTab('SCALP')}
        >
          초단타 시스템
        </button>
        <button
          type="button"
          className={monitorTab === 'INVEST' ? 'active' : ''}
          onClick={() => setMonitorTab('INVEST')}
        >
          투자 시스템
        </button>
      </div>

      {monitorTab === 'SCALP' ? (
        <>
          <AutopilotLiveDock
            open={true}
            collapsed={autopilotDockCollapsed}
            onToggleCollapse={() => setAutopilotDockCollapsed((prev) => !prev)}
            autopilotEnabled={autopilotEnabled}
            autopilotState={autopilotState}
            liveData={scalpAutopilotLiveQuery.data}
            loading={scalpAutopilotLiveQuery.isLoading}
          />
          <FocusedScalpLiveDock
            open={true}
            collapsed={focusedScalpDockCollapsed}
            onToggleCollapse={() => setFocusedScalpDockCollapsed((prev) => !prev)}
            enabled={focusedScalpEnabled}
            cards={focusedScalpDecisionCards}
            summary={focusedScalpDecisionSummary}
            onSelectMarket={(market) => setSelectedMarket(market)}
          />
        </>
      ) : (
        <InvestmentLiveDock
          open={true}
          collapsed={investmentDockCollapsed}
          onToggleCollapse={() => setInvestmentDockCollapsed((prev) => !prev)}
          swingEnabled={swingAutopilotEnabled}
          positionEnabled={positionAutopilotEnabled}
          swingState={swingAutopilotState}
          positionState={positionAutopilotState}
          swingLiveData={swingAutopilotLiveQuery.data}
          positionLiveData={positionAutopilotLiveQuery.data}
          loading={swingAutopilotLiveQuery.isLoading || positionAutopilotLiveQuery.isLoading}
        />
      )}
    </section>
  );
}

function PendingTimer({ createdAt }: { createdAt: string }) {
  const [elapsed, setElapsed] = useState('');

  useEffect(() => {
    const start = new Date(createdAt).getTime();
    const tick = () => {
      const diff = Math.max(0, Math.floor((Date.now() - start) / 1000));
      const m = Math.floor(diff / 60);
      const s = diff % 60;
      setElapsed(`${m}분 ${s.toString().padStart(2, '0')}초`);
    };
    tick();
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, [createdAt]);

  return <div className="pending-timer">경과: {elapsed}</div>;
}
