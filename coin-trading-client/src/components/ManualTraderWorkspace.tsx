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
  type GuidedStartRequest,
} from '../api';
import './ManualTraderWorkspace.css';

const KRW_FORMATTER = new Intl.NumberFormat('ko-KR');

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
  const [selectedMarket, setSelectedMarket] = useState<string>('KRW-BTC');
  const [interval, setIntervalValue] = useState<string>('minute30');
  const [search, setSearch] = useState<string>('');
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
    queryKey: ['guided-markets'],
    queryFn: () => guidedTradingApi.getMarkets(),
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
          <div className="guided-chart" ref={chartContainerRef} />

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
