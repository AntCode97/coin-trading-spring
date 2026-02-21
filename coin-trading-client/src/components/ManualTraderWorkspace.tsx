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
  type GuidedChartResponse,
  type GuidedMarketItem,
  type GuidedMarketSortBy,
  type GuidedSortDirection,
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
  return `${KRW_FORMATTER.format(Math.round(value))}원`;
}

function formatPct(value: number): string {
  return `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`;
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

  const chartContainerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const priceLinesRef = useRef<IPriceLine[]>([]);

  const marketsQuery = useQuery<GuidedMarketItem[]>({
    queryKey: ['guided-markets', sortBy, sortDirection],
    queryFn: () => guidedTradingApi.getMarkets(sortBy, sortDirection),
    refetchInterval: 15000,
  });

  const chartQuery = useQuery<GuidedChartResponse>({
    queryKey: ['guided-chart', selectedMarket, interval],
    queryFn: () => guidedTradingApi.getChart(selectedMarket, interval, 180),
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
      setStatusMessage(e instanceof Error ? e.message : '정지 실패');
    },
  });

  const recommendation = chartQuery.data?.recommendation;
  const activePosition = chartQuery.data?.activePosition;
  const events = chartQuery.data?.events ?? [];

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
    const series = candleSeriesRef.current;
    const chart = chartRef.current;
    const payload = chartQuery.data;
    if (!series || !chart || !payload) return;

    const candlesticks: CandlestickData<Time>[] = payload.candles.map((candle) => ({
      time: asUtc(candle.timestamp),
      open: candle.open,
      high: candle.high,
      low: candle.low,
      close: candle.close,
    }));
    series.setData(candlesticks);

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
    const latestCandle = payload.candles[payload.candles.length - 1];
    if (latestCandle) {
      markers.push({
        time: asUtc(latestCandle.timestamp),
        position: 'aboveBar',
        color: '#f5c542',
        shape: 'circle',
        text: `예상 승률 ${payload.recommendation.predictedWinRate.toFixed(1)}%`,
      });
    }
    createSeriesMarkers(series, markers);

    chart.timeScale().fitContent();
  }, [chartQuery.data, events]);

  useEffect(() => {
    if (!recommendation) return;
    if (orderType === 'LIMIT') {
      setLimitPrice(Math.round(recommendation.recommendedEntryPrice));
    }
  }, [recommendation, orderType]);

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
                  <strong>{KRW_FORMATTER.format(item.tradePrice)}</strong>
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
              {['minute1', 'minute10', 'minute30', 'minute60', 'day'].map((item) => (
                <button
                  key={item}
                  className={interval === item ? 'active' : ''}
                  onClick={() => setIntervalValue(item)}
                  type="button"
                >
                  {item.replace('minute', '') === 'day' ? '일' : `${item.replace('minute', '')}분`}
                </button>
              ))}
            </div>
          </div>
          <div className="guided-chart-shell">
            <div className="guided-chart" ref={chartContainerRef} />
            {recommendation && (
              <div className="guided-winrate-overlay">
                <div className="winrate-header">
                  <span>지금 진입 예상 승률</span>
                  <strong>{recommendation.predictedWinRate.toFixed(1)}%</strong>
                </div>
                <div className="winrate-bar">
                  <div
                    className="winrate-fill"
                    style={{ width: `${Math.min(100, Math.max(0, recommendation.predictedWinRate))}%` }}
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
                <span>예상 승률</span>
                <strong>{recommendation.predictedWinRate.toFixed(1)}%</strong>
              </div>
              <div>
                <span>Risk/Reward</span>
                <strong>{recommendation.riskRewardRatio.toFixed(2)}R</strong>
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
                onChange={(event) => setLimitPrice(event.target.value ? Number(event.target.value) : '')}
              />
            </label>
          )}

          <label>
            손절가(커스텀)
            <input
              type="number"
              value={customStopLoss}
              placeholder={recommendation ? Math.round(recommendation.stopLossPrice).toString() : ''}
              onChange={(event) => setCustomStopLoss(event.target.value ? Number(event.target.value) : '')}
            />
          </label>

          <label>
            익절가(커스텀)
            <input
              type="number"
              value={customTakeProfit}
              placeholder={recommendation ? Math.round(recommendation.takeProfitPrice).toString() : ''}
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

          {statusMessage && <p className="guided-status">{statusMessage}</p>}
        </aside>
      </div>
    </section>
  );
}
