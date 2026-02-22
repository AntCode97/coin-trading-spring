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
  type GuidedRealtimeTicker,
  type GuidedStartRequest,
} from '../api';
import {
  checkConnection,
  startLogin,
  logout,
  sendChatMessage,
  clearConversation,
  connectMcp,
  CODEX_MODELS,
  type AgentAction,
  type ChatMessage,
  type CodexModelId,
  type LlmConnectionStatus,
} from '../lib/llmService';
import './ManualTraderWorkspace.css';

const KRW_FORMATTER = new Intl.NumberFormat('ko-KR');
const STORAGE_KEY = 'guided-trader.preferences.v1';

type WorkspacePrefs = {
  selectedMarket?: string;
  interval?: string;
  sortBy?: GuidedMarketSortBy;
  sortDirection?: GuidedSortDirection;
};

function loadPrefs(): WorkspacePrefs {
  if (typeof window === 'undefined') return {};
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    return JSON.parse(raw) as WorkspacePrefs;
  } catch {
    return {};
  }
}

function asUtc(secondsOrMillis: number): UTCTimestamp {
  const sec = secondsOrMillis > 10_000_000_000 ? Math.floor(secondsOrMillis / 1000) : Math.floor(secondsOrMillis);
  return sec as UTCTimestamp;
}

function formatKrw(value: number): string {
  if (!Number.isFinite(value)) return '-';
  if (Math.abs(value) >= 1000) {
    return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}원`;
  }
  if (Math.abs(value) >= 1) {
    return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 6 })}원`;
  }
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 10 })}원`;
}

function formatPlain(value: number): string {
  if (!Number.isFinite(value)) return '-';
  if (Math.abs(value) >= 1000) {
    return value.toLocaleString('ko-KR', { maximumFractionDigits: 2 });
  }
  if (Math.abs(value) >= 1) {
    return value.toLocaleString('ko-KR', { maximumFractionDigits: 6 });
  }
  return value.toLocaleString('ko-KR', { maximumFractionDigits: 10 });
}

function formatPct(value: number): string {
  return `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`;
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

// MCP URL을 API Base URL에서 도출
function deriveMcpUrl(): string {
  const apiBase = getApiBaseUrl().replace(/\/$/, '');
  // http://host:port/api → http://host:port/mcp
  return apiBase.replace(/\/api$/, '/mcp');
}

export default function ManualTraderWorkspace() {
  const prefs = useMemo(() => loadPrefs(), []);
  const [selectedMarket, setSelectedMarket] = useState<string>(prefs.selectedMarket ?? 'KRW-BTC');
  const [interval, setIntervalValue] = useState<string>(prefs.interval ?? 'minute30');
  const [search, setSearch] = useState<string>('');
  const [sortBy, setSortBy] = useState<GuidedMarketSortBy>(prefs.sortBy ?? 'TURNOVER');
  const [sortDirection, setSortDirection] = useState<GuidedSortDirection>(prefs.sortDirection ?? 'DESC');
  const [amountKrw, setAmountKrw] = useState<number>(20000);
  const [orderType, setOrderType] = useState<'MARKET' | 'LIMIT'>('LIMIT');
  const [limitPrice, setLimitPrice] = useState<number | ''>('');
  const [customStopLoss, setCustomStopLoss] = useState<number | ''>('');
  const [customTakeProfit, setCustomTakeProfit] = useState<number | ''>('');
  const [statusMessage, setStatusMessage] = useState<string>('');
  const [llmStatus, setLlmStatus] = useState<LlmConnectionStatus>('checking');

  // 탭 상태
  const [activeTab, setActiveTab] = useState<'order' | 'chat'>('chat');

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
  const chatEndRef = useRef<HTMLDivElement | null>(null);

  const openAiConnected = llmStatus === 'connected';
  const providerChecking = llmStatus === 'checking';

  const chartContainerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const priceLinesRef = useRef<IPriceLine[]>([]);
  const shouldAutoFitRef = useRef<boolean>(true);
  const liveCandlesRef = useRef<CandlestickData<Time>[]>([]);

  const marketsQuery = useQuery<GuidedMarketItem[]>({
    queryKey: ['guided-markets', sortBy, sortDirection],
    queryFn: () => guidedTradingApi.getMarkets(sortBy, sortDirection),
    refetchInterval: 15000,
  });

  const chartQuery = useQuery<GuidedChartResponse>({
    queryKey: ['guided-chart', selectedMarket, interval],
    queryFn: () => guidedTradingApi.getChart(selectedMarket, interval, interval === 'tick' ? 500 : 180),
    refetchInterval: 5000,
  });

  const tickerQuery = useQuery<GuidedRealtimeTicker | null>({
    queryKey: ['guided-realtime-ticker', selectedMarket],
    queryFn: () => guidedTradingApi.getRealtimeTicker(selectedMarket),
    refetchInterval: 1000,
  });

  const agentContextQuery = useQuery<GuidedAgentContextResponse>({
    queryKey: ['guided-agent-context', selectedMarket, interval],
    queryFn: () => guidedTradingApi.getAgentContext(selectedMarket, interval, interval === 'tick' ? 300 : 120, 20),
    enabled: true,
    refetchInterval: 7000,
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
      setStatusMessage(e instanceof Error ? e.message : '정지 실패');
    },
  });

  const recommendation = chartQuery.data?.recommendation;
  const activePosition = chartQuery.data?.activePosition;
  const events = chartQuery.data?.events ?? [];
  const orderbook = chartQuery.data?.orderbook;
  const orderSnapshot = chartQuery.data?.orderSnapshot;

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

  // MCP 연결 및 도구 목록 로드
  useEffect(() => {
    const mcpUrl = deriveMcpUrl();
    connectMcp(mcpUrl)
      .then((tools) => {
        setMcpTools(tools);
        setMcpConnected(tools.length > 0);
      })
      .catch(() => { /* MCP 실패해도 채팅은 가능 */ });
  }, []);

  // 채팅 스크롤
  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatMessages, chatStreamText]);

  // 저장
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const next: WorkspacePrefs = { selectedMarket, interval, sortBy, sortDirection };
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  }, [selectedMarket, interval, sortBy, sortDirection]);

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

    const candlesticks: CandlestickData<Time>[] = payload.candles.map(toCandlestick);
    series.setData(candlesticks);
    liveCandlesRef.current = candlesticks;

    priceLinesRef.current.forEach((line) => series.removePriceLine(line));
    priceLinesRef.current = [];

    priceLinesRef.current.push(series.createPriceLine({
      price: payload.recommendation.recommendedEntryPrice,
      color: '#f5c542', lineStyle: 2, lineWidth: 1, title: '추천 매수가',
    }));

    if (payload.activePosition) {
      priceLinesRef.current.push(series.createPriceLine({ price: payload.activePosition.averageEntryPrice, color: '#41dba6', lineStyle: 1, lineWidth: 2, title: '내 매수가' }));
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
      chart.timeScale().fitContent();
      shouldAutoFitRef.current = false;
    }
  }, [chartQuery.data, events]);

  // 실시간 캔들 업데이트
  useEffect(() => {
    const series = candleSeriesRef.current;
    const ticker = tickerQuery.data;
    const payload = chartQuery.data;
    if (!series || !ticker || !payload) return;
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
  }, [tickerQuery.data, interval, chartQuery.data]);

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

  const executeAgentAction = async (action: AgentAction) => {
    const normalizedType = action.type.toUpperCase();
    const urgency = action.urgency.toUpperCase();
    const requiresConfirm = urgency === 'HIGH' || normalizedType === 'FULL_EXIT' || normalizedType === 'PARTIAL_TP';

    if (requiresConfirm) {
      const ok = window.confirm(`[${normalizedType}] ${action.title}\n${action.reason}`);
      if (!ok) return;
    }

    if (normalizedType === 'FULL_EXIT') {
      if (!activePosition) { setStatusMessage('청산할 포지션 없음.'); return; }
      stopMutation.mutate();
      return;
    }
    if (normalizedType === 'PARTIAL_TP') {
      if (!activePosition) { setStatusMessage('익절할 포지션 없음.'); return; }
      const ratio = typeof action.sizePercent === 'number' ? Math.max(0.1, Math.min(0.9, action.sizePercent / 100)) : 0.5;
      try {
        await guidedTradingApi.partialTakeProfit(selectedMarket, ratio);
        void chartQuery.refetch();
        setStatusMessage(`부분 익절 ${Math.round(ratio * 100)}% 실행.`);
      } catch (e) {
        setStatusMessage(e instanceof Error ? e.message : '부분 익절 실패');
      }
      return;
    }
    if (normalizedType === 'ADD' || normalizedType === 'WAIT_RETEST') {
      applyAgentActionToOrderForm(action);
      if (!activePosition && recommendation) {
        const payload: GuidedStartRequest = {
          market: selectedMarket, amountKrw, orderType,
          limitPrice: orderType === 'LIMIT' && typeof limitPrice === 'number' ? limitPrice : undefined,
          stopLossPrice: typeof customStopLoss === 'number' ? customStopLoss : recommendation.stopLossPrice,
          takeProfitPrice: typeof customTakeProfit === 'number' ? customTakeProfit : recommendation.takeProfitPrice,
        };
        startMutation.mutate(payload);
      }
    }
  };

  const handleStart = () => {
    if (!recommendation) return;
    const payload: GuidedStartRequest = {
      market: selectedMarket, amountKrw, orderType,
      limitPrice: orderType === 'LIMIT' && typeof limitPrice === 'number' ? limitPrice : undefined,
      stopLossPrice: typeof customStopLoss === 'number' ? customStopLoss : recommendation.stopLossPrice,
      takeProfitPrice: typeof customTakeProfit === 'number' ? customTakeProfit : recommendation.takeProfitPrice,
    };
    startMutation.mutate(payload);
  };

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
  }, [chatBusy, chatInput, chatModel, llmStatus, autoContext, agentContextQuery.data, mcpTools]);

  // 자동 분석
  useEffect(() => {
    if (!autoAnalysis || llmStatus !== 'connected') return;
    const timer = window.setInterval(() => {
      if (chatBusy) return;
      const context = agentContextQuery.data ?? null;
      if (!context) return;
      setChatBusy(true);
      setChatStreamText('');
      const llmTools = mcpTools;
      sendChatMessage({
        userMessage: '현재 시점 분석과 조언을 부탁합니다.',
        model: chatModel,
        context,
        mcpTools: llmTools.length > 0 ? llmTools : undefined,
        onStreamDelta: (text) => setChatStreamText(text),
      })
        .then((msgs) => {
          setChatMessages((prev) => [...prev, ...msgs]);
          setChatStreamText('');
        })
        .catch(() => { /* 자동 분석 실패 무시 */ })
        .finally(() => setChatBusy(false));
    }, 15000);
    return () => window.clearInterval(timer);
  }, [autoAnalysis, chatModel, llmStatus, chatBusy, agentContextQuery.data, mcpTools]);

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

  return (
    <section className="guided-workspace">
      <header className="guided-header">
        <div>
          <h2>수동 코인 트레이딩 워크스페이스</h2>
          <p>코인 선택 → 차트 확인 → 추천 매수가/직접 가격으로 진입 → 자동 익절/손절/물타기 관리</p>
        </div>
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
                className={`guided-market-row ${selectedMarket === item.market ? 'active' : ''}`}
                onClick={() => setSelectedMarket(item.market)}
                type="button"
              >
                <div className="name-wrap">
                  <strong>{item.koreanName}</strong>
                  <span>{item.market}</span>
                </div>
                <div className="price-wrap">
                  <strong>{formatKrw(item.tradePrice)}</strong>
                  <span className={item.changeRate >= 0 ? 'up' : 'down'}>{formatPct(item.changeRate)}</span>
                  <small>거래대금 {KRW_FORMATTER.format(item.accTradePrice)}</small>
                </div>
              </button>
            ))}
          </div>
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
                  <span>추세 {(recommendation.winRateBreakdown.trend * 100).toFixed(0)}</span>
                  <span>눌림 {(recommendation.winRateBreakdown.pullback * 100).toFixed(0)}</span>
                  <span>변동성 {(recommendation.winRateBreakdown.volatility * 100).toFixed(0)}</span>
                  <span>RR {(recommendation.winRateBreakdown.riskReward * 100).toFixed(0)}</span>
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
              <div><span>손절가</span><strong>{formatKrw(recommendation.stopLossPrice)}</strong></div>
              <div><span>익절가</span><strong>{formatKrw(recommendation.takeProfitPrice)}</strong></div>
              <div><span>신뢰도</span><strong>{(recommendation.confidence * 100).toFixed(1)}%</strong></div>
              <div><span>추천가 승률</span><strong>{(recommendation.recommendedEntryWinRate ?? recommendation.predictedWinRate).toFixed(1)}%</strong></div>
              <div><span>현재가 승률</span><strong>{(recommendation.marketEntryWinRate ?? recommendation.predictedWinRate).toFixed(1)}%</strong></div>
              <div><span>Risk/Reward</span><strong>{recommendation.riskRewardRatio.toFixed(2)}R</strong></div>
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
              주문/포지션
            </button>
            <button
              type="button"
              className={activeTab === 'chat' ? 'active' : ''}
              onClick={() => setActiveTab('chat')}
            >
              AI 채팅
            </button>
          </div>

          {/* 주문/포지션 탭 */}
          {activeTab === 'order' && (
            <div className="guided-tab-content">
              <h3>포지션 시작</h3>
              <label>
                주문금액(KRW)
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
              <label>손절가(커스텀)<input type="number" value={customStopLoss} step="any" placeholder={recommendation ? recommendation.stopLossPrice.toString() : ''} onChange={(e) => setCustomStopLoss(e.target.value ? Number(e.target.value) : '')} /></label>
              <label>익절가(커스텀)<input type="number" value={customTakeProfit} step="any" placeholder={recommendation ? recommendation.takeProfitPrice.toString() : ''} onChange={(e) => setCustomTakeProfit(e.target.value ? Number(e.target.value) : '')} /></label>
              <div className="guided-actions">
                <button type="button" onClick={handleStart} disabled={startMutation.isPending || !recommendation}>
                  {startMutation.isPending ? '주문 중...' : '자동매매 시작'}
                </button>
                <button type="button" className="danger" onClick={() => stopMutation.mutate()} disabled={stopMutation.isPending || !activePosition}>
                  {stopMutation.isPending ? '정지 중...' : '포지션 정지'}
                </button>
              </div>

              {activePosition && (
                <div className="guided-position-card">
                  <h4>진행 포지션</h4>
                  <p>상태: {activePosition.status}</p>
                  <p>평균가: {formatKrw(activePosition.averageEntryPrice)}</p>
                  <p>보유수량: {activePosition.remainingQuantity.toFixed(6)}</p>
                  <p>미실현: {formatPct(activePosition.unrealizedPnlPercent)}</p>
                  <p>절반익절: {activePosition.halfTakeProfitDone ? '완료' : '대기'}</p>
                  <p>물타기: {activePosition.dcaCount}/{activePosition.maxDcaCount}</p>
                </div>
              )}

              {orderSnapshot && (
                <div className="guided-orders-card">
                  <h4>내 주문 현황</h4>
                  <div className="orders-section">
                    <span>현재 주문</span>
                    {orderSnapshot.currentOrder ? (
                      <p>
                        {orderSnapshot.currentOrder.side.toUpperCase()} / {orderSnapshot.currentOrder.ordType.toUpperCase()} / {orderSnapshot.currentOrder.state ?? '-'}
                        {' · '}가격 {orderSnapshot.currentOrder.price != null ? formatPlain(orderSnapshot.currentOrder.price) : '-'}
                        {' · '}체결 {orderSnapshot.currentOrder.executedVolume != null ? formatPlain(orderSnapshot.currentOrder.executedVolume) : '-'}
                      </p>
                    ) : (<p>없음</p>)}
                  </div>
                  <div className="orders-section">
                    <span>대기중 주문 ({orderSnapshot.pendingOrders.length})</span>
                    {orderSnapshot.pendingOrders.slice(0, 5).map((o) => (
                      <p key={o.uuid}>{o.side.toUpperCase()} {formatPlain(o.price ?? 0)} · 잔량 {formatPlain(o.remainingVolume ?? 0)}</p>
                    ))}
                    {orderSnapshot.pendingOrders.length === 0 && <p>없음</p>}
                  </div>
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
                    <div key={tool.name} className="guided-tool-item">
                      <code>{tool.name}</code>
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
                                <button type="button" onClick={() => void executeAgentAction(action)}>즉시 실행</button>
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
    </section>
  );
}
