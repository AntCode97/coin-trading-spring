import { useEffect, useMemo, useRef, useState } from 'react';
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
  guidedTradingApi,
  type GuidedCopilotResponse,
  type GuidedChartResponse,
  type GuidedCandle,
  type GuidedMarketItem,
  type GuidedMarketSortBy,
  type GuidedSortDirection,
  type GuidedRealtimeTicker,
  type GuidedStartRequest,
} from '../api';
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
  const [copilotProvider, setCopilotProvider] = useState<'OPENAI' | 'ZAI'>('OPENAI');
  const [copilotPrompt, setCopilotPrompt] = useState<string>('');
  const [copilotAutoRefresh, setCopilotAutoRefresh] = useState<boolean>(true);

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

  const copilotQuery = useQuery<GuidedCopilotResponse>({
    queryKey: ['guided-copilot', selectedMarket, interval, copilotProvider, copilotPrompt],
    queryFn: () => guidedTradingApi.getCopilotAnalysis({
      market: selectedMarket,
      interval,
      count: interval === 'tick' ? 300 : 120,
      provider: copilotProvider,
      userPrompt: copilotPrompt.trim().length > 0 ? copilotPrompt.trim() : undefined,
    }),
    enabled: Boolean(chartQuery.data),
    refetchInterval: copilotAutoRefresh ? 7000 : false,
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
  const copilot = copilotQuery.data;

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

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const next: WorkspacePrefs = {
      selectedMarket,
      interval,
      sortBy,
      sortDirection,
    };
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  }, [selectedMarket, interval, sortBy, sortDirection]);

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
        rightPriceScale: {
          borderColor: '#2d3543',
        },
        timeScale: {
          borderColor: '#2d3543',
          timeVisible: true,
          secondsVisible: false,
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
      if (chartRef.current) {
        chartRef.current.remove();
      }
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

    const candlesticks: CandlestickData<Time>[] = payload.candles.map((candle) => toCandlestick(candle));
    series.setData(candlesticks);
    liveCandlesRef.current = candlesticks;

    priceLinesRef.current.forEach((line) => series.removePriceLine(line));
    priceLinesRef.current = [];

    priceLinesRef.current.push(series.createPriceLine({
      price: payload.recommendation.recommendedEntryPrice,
      color: '#f5c542',
      lineStyle: 2,
      lineWidth: 1,
      title: '추천 매수가',
    }));

    if (payload.activePosition) {
      priceLinesRef.current.push(series.createPriceLine({
        price: payload.activePosition.averageEntryPrice,
        color: '#41dba6',
        lineStyle: 1,
        lineWidth: 2,
        title: '내 매수가',
      }));
      priceLinesRef.current.push(series.createPriceLine({
        price: payload.activePosition.takeProfitPrice,
        color: '#ff4d67',
        lineStyle: 2,
        lineWidth: 1,
        title: '익절',
      }));
      priceLinesRef.current.push(series.createPriceLine({
        price: payload.activePosition.stopLossPrice,
        color: '#4c88ff',
        lineStyle: 2,
        lineWidth: 1,
        title: '손절',
      }));
      if (payload.activePosition.trailingStopPrice) {
        priceLinesRef.current.push(series.createPriceLine({
          price: payload.activePosition.trailingStopPrice,
          color: '#b084ff',
          lineStyle: 0,
          lineWidth: 1,
          title: '트레일링',
        }));
      }
    }

    const markers: SeriesMarker<Time>[] = events
      .filter((event) => event.price != null)
      .map((event) => {
        const isExit = event.eventType.includes('CLOSE') || event.eventType.includes('TAKE');
        const position: 'aboveBar' | 'belowBar' = isExit ? 'aboveBar' : 'belowBar';
        const shape: 'arrowDown' | 'arrowUp' = isExit ? 'arrowDown' : 'arrowUp';
        return {
          time: asUtc(new Date(event.createdAt).getTime()),
          position,
          color: isExit ? '#ff6078' : '#3ecf9f',
          shape,
          text: event.eventType,
        };
      });
    createSeriesMarkers(series, markers);

    if (shouldAutoFitRef.current) {
      chart.timeScale().fitContent();
      shouldAutoFitRef.current = false;
    }
  }, [chartQuery.data, events]);

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

  const handleStart = () => {
    if (!recommendation) return;
    const payload: GuidedStartRequest = {
      market: selectedMarket,
      amountKrw,
      orderType,
      limitPrice: orderType === 'LIMIT' && typeof limitPrice === 'number' ? limitPrice : undefined,
      stopLossPrice: typeof customStopLoss === 'number' ? customStopLoss : recommendation.stopLossPrice,
      takeProfitPrice: typeof customTakeProfit === 'number' ? customTakeProfit : recommendation.takeProfitPrice,
    };
    startMutation.mutate(payload);
  };

  return (
    <section className="guided-workspace">
      <header className="guided-header">
        <div>
          <h2>수동 코인 트레이딩 워크스페이스</h2>
          <p>코인 선택 → 차트 확인 → 추천 매수가/직접 가격으로 진입 → 자동 익절/손절/물타기 관리</p>
        </div>
      </header>

      <div className="guided-grid">
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
                  {item === 'tick' ? '틱' : item.replace('minute', '') === 'day' ? '일' : `${item.replace('minute', '')}분`}
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
              <div>
                <span>현재가</span>
                <strong>{formatKrw(recommendation.currentPrice)}</strong>
              </div>
              <div>
                <span>추천 매수가</span>
                <strong>{formatKrw(recommendation.recommendedEntryPrice)}</strong>
                <button type="button" className="guided-link-button" onClick={handleApplyRecommendedLimit}>
                  지정가에 적용
                </button>
              </div>
              <div>
                <span>손절가</span>
                <strong>{formatKrw(recommendation.stopLossPrice)}</strong>
              </div>
              <div>
                <span>익절가</span>
                <strong>{formatKrw(recommendation.takeProfitPrice)}</strong>
              </div>
              <div>
                <span>신뢰도</span>
                <strong>{(recommendation.confidence * 100).toFixed(1)}%</strong>
              </div>
              <div>
                <span>추천가 승률</span>
                <strong>{(recommendation.recommendedEntryWinRate ?? recommendation.predictedWinRate).toFixed(1)}%</strong>
              </div>
              <div>
                <span>현재가 승률</span>
                <strong>{(recommendation.marketEntryWinRate ?? recommendation.predictedWinRate).toFixed(1)}%</strong>
              </div>
              <div>
                <span>Risk/Reward</span>
                <strong>{recommendation.riskRewardRatio.toFixed(2)}R</strong>
              </div>
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
                <div>
                  <span>최우선 매도</span>
                  <strong>{orderbook.bestAsk != null ? formatKrw(orderbook.bestAsk) : '-'}</strong>
                </div>
                <div>
                  <span>최우선 매수</span>
                  <strong>{orderbook.bestBid != null ? formatKrw(orderbook.bestBid) : '-'}</strong>
                </div>
              </div>
              <div className="guided-orderbook-grid">
                <div className="head">매도호가</div>
                <div className="head">매도수량</div>
                <div className="head">매수호가</div>
                <div className="head">매수수량</div>
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

        <aside className="guided-order-panel">
          <h3>포지션 시작</h3>
          <label>
            주문금액(KRW)
            <input
              type="number"
              min={5100}
              step={1000}
              value={amountKrw}
              onChange={(event) => setAmountKrw(Number(event.target.value || 0))}
            />
          </label>

          <label>
            주문 방식
            <select value={orderType} onChange={(event) => setOrderType(event.target.value as 'MARKET' | 'LIMIT')}>
              <option value="LIMIT">지정가</option>
              <option value="MARKET">시장가</option>
            </select>
          </label>

          {orderType === 'LIMIT' && (
            <label>
              지정가
              <input
                type="number"
                value={limitPrice}
                step="any"
                onChange={(event) => setLimitPrice(event.target.value ? Number(event.target.value) : '')}
              />
            </label>
          )}

          <label>
            손절가(커스텀)
              <input
                type="number"
                value={customStopLoss}
                step="any"
                placeholder={recommendation ? recommendation.stopLossPrice.toString() : ''}
                onChange={(event) => setCustomStopLoss(event.target.value ? Number(event.target.value) : '')}
              />
            </label>

          <label>
            익절가(커스텀)
              <input
                type="number"
                value={customTakeProfit}
                step="any"
                placeholder={recommendation ? recommendation.takeProfitPrice.toString() : ''}
                onChange={(event) => setCustomTakeProfit(event.target.value ? Number(event.target.value) : '')}
              />
            </label>

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
                    {' · '}
                    가격 {orderSnapshot.currentOrder.price != null ? formatPlain(orderSnapshot.currentOrder.price) : '-'}
                    {' · '}
                    체결 {orderSnapshot.currentOrder.executedVolume != null ? formatPlain(orderSnapshot.currentOrder.executedVolume) : '-'}
                  </p>
                ) : (
                  <p>없음</p>
                )}
              </div>
              <div className="orders-section">
                <span>대기중 주문 ({orderSnapshot.pendingOrders.length})</span>
                {orderSnapshot.pendingOrders.slice(0, 5).map((o) => (
                  <p key={o.uuid}>
                    {o.side.toUpperCase()} {formatPlain(o.price ?? 0)} · 잔량 {formatPlain(o.remainingVolume ?? 0)}
                  </p>
                ))}
                {orderSnapshot.pendingOrders.length === 0 && <p>없음</p>}
              </div>
              <div className="orders-section">
                <span>완료/취소 주문 ({orderSnapshot.completedOrders.length})</span>
                {orderSnapshot.completedOrders.slice(0, 6).map((o) => (
                  <p key={o.uuid}>
                    {o.state ?? '-'} · {o.side.toUpperCase()} · 체결 {formatPlain(o.executedVolume ?? 0)}
                  </p>
                ))}
                {orderSnapshot.completedOrders.length === 0 && <p>없음</p>}
              </div>
            </div>
          )}

          <div className="guided-copilot-card">
            <div className="guided-copilot-head">
              <h4>AI 트레이딩 코파일럿</h4>
              <div className="guided-copilot-controls">
                <select
                  value={copilotProvider}
                  onChange={(event) => setCopilotProvider(event.target.value as 'OPENAI' | 'ZAI')}
                >
                  <option value="OPENAI">OpenAI</option>
                  <option value="ZAI">Z.AI</option>
                </select>
                <button
                  type="button"
                  onClick={() => void copilotQuery.refetch()}
                  disabled={copilotQuery.isFetching}
                >
                  {copilotQuery.isFetching ? '분석 중...' : '지금 분석'}
                </button>
              </div>
            </div>

            <label className="guided-copilot-prompt">
              추가 지시(선택)
              <input
                value={copilotPrompt}
                onChange={(event) => setCopilotPrompt(event.target.value)}
                placeholder="예: 분할 익절 기준을 보수적으로"
              />
            </label>

            <label className="guided-copilot-auto">
              <input
                type="checkbox"
                checked={copilotAutoRefresh}
                onChange={(event) => setCopilotAutoRefresh(event.target.checked)}
              />
              7초 자동 업데이트
            </label>

            {copilot && (
              <>
                <div className="guided-copilot-summary">
                  <span>신뢰도</span>
                  <strong>{copilot.confidence}%</strong>
                  <small>{new Date(copilot.generatedAt).toLocaleTimeString('ko-KR')}</small>
                </div>
                <p className="guided-copilot-analysis">{copilot.analysis}</p>
                <div className="guided-copilot-actions">
                  {copilot.actions.length === 0 && (
                    <div className="guided-copilot-action muted">지금은 관망 시그널이 우세합니다.</div>
                  )}
                  {copilot.actions.map((action, index) => (
                    <div key={`${action.type}-${index}`} className="guided-copilot-action">
                      <div className="top">
                        <strong>{action.title}</strong>
                        <span className={`urgency ${action.urgency.toLowerCase()}`}>{action.urgency}</span>
                      </div>
                      <p>{action.reason}</p>
                      <small>
                        {action.targetPrice != null ? `목표가 ${formatKrw(action.targetPrice)} · ` : ''}
                        {action.sizePercent != null ? `권장 비중 ${action.sizePercent}%` : '비중 유지'}
                      </small>
                    </div>
                  ))}
                </div>
              </>
            )}

            {copilotQuery.error && (
              <p className="guided-copilot-error">{copilotQuery.error instanceof Error ? copilotQuery.error.message : '코파일럿 분석 오류'}</p>
            )}
          </div>

          {statusMessage && <p className="guided-status">{statusMessage}</p>}
        </aside>
      </div>
    </section>
  );
}
