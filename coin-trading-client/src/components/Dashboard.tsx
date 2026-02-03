import { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api';
import './Dashboard.css';

export default function Dashboard() {
  const [daysAgo, setDaysAgo] = useState(0);
  const [lastUpdateTime, setLastUpdateTime] = useState<Date>(new Date());

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['dashboard', daysAgo],
    queryFn: () => dashboardApi.getData(daysAgo),
    refetchInterval: 30000,
  });

  useEffect(() => {
    if (data) {
      setLastUpdateTime(new Date());
    }
  }, [data]);

  const handleDateChange = (offset: number) => {
    const newValue = Math.max(0, Math.min(7, daysAgo + offset));
    setDaysAgo(newValue);
  };

  const handleManualSell = async (market: string, strategy: string) => {
    if (!confirm(`${market} (${strategy}) 포지션을 매도하시겠습니까?`)) {
      return;
    }

    try {
      const result = await dashboardApi.manualClose(market, strategy);
      if (result.success) {
        alert('매도 완료!');
        refetch();
      } else {
        alert('매도 실패: ' + (result.error || '알 수 없는 오류'));
      }
    } catch (e: any) {
      alert('매도 오류: ' + e.message);
    }
  };

  if (isLoading) {
    return (
      <div className="tv-loading-container">
        <div className="tv-spinner"></div>
        <p className="tv-loading-text">데이터를 불러오는 중...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="tv-error-container">
        <div className="tv-error-icon">⚠️</div>
        <p className="tv-error-text">
          오류가 발생했습니다: {error instanceof Error ? error.message : '알 수 없는 오류'}
        </p>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="tv-error-container">
        <p className="tv-error-text">데이터를 불러올 수 없습니다</p>
      </div>
    );
  }

  const exitReasonLabels: Record<string, { label: string; className: string }> = {
    TAKE_PROFIT: { label: '목표 달성', className: 'tv-exit-reason-success' },
    STOP_LOSS: { label: '손절', className: 'tv-exit-reason-danger' },
    TIMEOUT: { label: '시간 초과', className: 'tv-exit-reason-warning' },
    TRAILING_STOP: { label: '트레일링', className: 'tv-exit-reason-info' },
    ABANDONED_NO_BALANCE: { label: '잔고부족', className: 'tv-exit-reason-muted' },
    SIGNAL_REVERSAL: { label: '반전신호', className: 'tv-exit-reason-warning' },
    MANUAL: { label: '수동', className: 'tv-exit-reason-secondary' },
    IMBALANCE_FLIP: { label: '밸런스변화', className: 'tv-exit-reason-blue' },
    UNKNOWN: { label: '기타', className: 'tv-exit-reason-muted' },
  };

  const totalPnl = data.todayStats.totalPnl;
  const totalPnlPercent = data.totalAssetKrw > 0
    ? (totalPnl / (data.totalAssetKrw - totalPnl)) * 100
    : 0;
  const isPositive = totalPnl >= 0;

  return (
    <div className="tv-container">
      {/* 헤더 */}
      <header className="tv-header">
        <div className="tv-header-left">
          <h1 className="tv-title">코인 트레이딩 대시보드</h1>
          <div className="tv-header-meta">
            <span className="tv-update-indicator">
              <span className="tv-pulse-dot"></span>
              마지막 업데이트: {lastUpdateTime.toLocaleTimeString('ko-KR')}
            </span>
          </div>
        </div>
        <div className="tv-header-right">
          <button
            onClick={() => refetch()}
            className="tv-refresh-button"
          >
            새로고침
          </button>
        </div>
      </header>

      {/* 총 자산 카드 */}
      <section className="tv-asset-section">
        <div className={`tv-asset-card ${isPositive ? 'tv-asset-card-positive' : 'tv-asset-card-negative'}`}>
          <div className="tv-asset-content">
            <div className="tv-asset-label">총 자산 평가금액</div>
            <div className="tv-asset-value">
              {data.totalAssetKrw.toLocaleString('ko-KR')}
              <span className="tv-asset-unit">KRW</span>
            </div>
            <div className="tv-asset-breakdown">
              <span className="tv-asset-breakdown-item">
                예수금: <strong>{data.krwBalance.toLocaleString('ko-KR')}</strong> KRW
              </span>
              {data.coinAssets && data.coinAssets.length > 0 && (
                <span className="tv-asset-breakdown-item">
                  코인: <strong>
                    {data.coinAssets
                      .reduce((sum, c) => sum + c.value, 0)
                      .toLocaleString('ko-KR')}
                  </strong> KRW
                </span>
              )}
            </div>
          </div>
          <div className={`tv-pnl-badge ${isPositive ? 'tv-pnl-positive' : 'tv-pnl-negative'}`}>
            <span className="tv-pnl-icon">{isPositive ? '▲' : '▼'}</span>
            <span className="tv-pnl-value">
              {isPositive ? '+' : ''}{totalPnl.toLocaleString('ko-KR')} KRW
            </span>
            <span className="tv-pnl-percent">
              ({isPositive ? '+' : ''}{totalPnlPercent.toFixed(2)}%)
            </span>
          </div>
        </div>
      </section>

      {/* 메인 컨텐츠 그리드 */}
      <div className="tv-main-grid">
        {/* 왼쪽 컬럼 */}
        <div className="tv-left-column">
          {/* 열린 포지션 */}
          {data.openPositions.length > 0 && (
            <div className="tv-card">
              <div className="tv-card-header">
                <h2 className="tv-card-title">열린 포지션</h2>
                <span className="tv-card-badge">{data.openPositions.length}</span>
              </div>
              <div className="tv-position-list">
                {data.openPositions.map((pos, idx) => {
                  const isPosProfit = pos.pnl >= 0;
                  return (
                    <div key={idx} className="tv-position-item">
                      <div className="tv-position-main">
                        <div className="tv-position-header">
                          <span className="tv-market-symbol">{pos.market}</span>
                          <span className={`tv-strategy-badge ${
                            pos.strategy === 'Meme Scalper' ? 'tv-strategy-meme' :
                            pos.strategy === 'Volume Surge' ? 'tv-strategy-volume' :
                            'tv-strategy-dca'
                          }`}>
                            {pos.strategy}
                          </span>
                        </div>
                        <div className="tv-position-prices">
                          <div className="tv-price-group">
                            <span className="tv-price-label">진입</span>
                            <span className="tv-price-value">{pos.entryPrice.toLocaleString()}</span>
                          </div>
                          <div className="tv-price-group">
                            <span className="tv-price-label">현재</span>
                            <span className={`tv-price-value ${isPosProfit ? 'tv-price-up' : 'tv-price-down'}`}>
                              {pos.currentPrice.toLocaleString()}
                            </span>
                          </div>
                          <div className="tv-price-group">
                            <span className="tv-price-label">수량</span>
                            <span className="tv-price-value">{pos.quantity.toFixed(4)}</span>
                          </div>
                        </div>
                      </div>
                      <div className="tv-position-side">
                        <div className={`tv-pnl-display ${isPosProfit ? 'tv-pnl-border-positive' : 'tv-pnl-border-negative'}`}>
                          <span className={`tv-pnl-amount ${isPosProfit ? 'tv-text-green' : 'tv-text-red'}`}>
                            {isPosProfit ? '+' : ''}{pos.pnl.toLocaleString()}원
                          </span>
                          <span className={`tv-pnl-percent ${isPosProfit ? 'tv-text-green' : 'tv-text-red'}`}>
                            ({isPosProfit ? '+' : ''}{pos.pnlPercent.toFixed(2)}%)
                          </span>
                        </div>
                        <button
                          onClick={() => handleManualSell(pos.market, pos.strategy)}
                          className="tv-sell-button"
                        >
                          매도
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* 오늘 통계 */}
          <div className="tv-card">
            <div className="tv-card-header">
              <h2 className="tv-card-title">오늘의 거래 통계</h2>
            </div>
            <div className="tv-stats-grid">
              <div className="tv-stat-item">
                <div className="tv-stat-label">총 거래</div>
                <div className="tv-stat-value">{data.todayStats.totalTrades}</div>
              </div>
              <div className="tv-stat-item">
                <div className="tv-stat-label">승리</div>
                <div className="tv-stat-value tv-text-green">{data.todayStats.winCount}</div>
              </div>
              <div className="tv-stat-item">
                <div className="tv-stat-label">패배</div>
                <div className="tv-stat-value tv-text-red">{data.todayStats.lossCount}</div>
              </div>
              <div className="tv-stat-item">
                <div className="tv-stat-label">승률</div>
                <div className={`tv-stat-value ${
                  data.todayStats.winRate >= 0.6 ? 'tv-text-green' :
                  data.todayStats.winRate >= 0.4 ? 'tv-text-orange' :
                  'tv-text-red'
                }`}>
                  {(data.todayStats.winRate * 100).toFixed(1)}%
                </div>
              </div>
              <div className={`tv-stat-item tv-stat-item-highlight ${isPositive ? 'tv-stat-border-positive' : 'tv-stat-border-negative'}`}>
                <div className="tv-stat-label">총 손익</div>
                <div className={`tv-stat-value-large ${isPositive ? 'tv-text-green' : 'tv-text-red'}`}>
                  {isPositive ? '+' : ''}{totalPnl.toLocaleString()}원
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* 오른쪽 컬럼: 거래 내역 */}
        <div className="tv-right-column">
          <div className="tv-card">
            <div className="tv-card-header">
              <h2 className="tv-card-title">거래 내역</h2>
              <div className="tv-date-selector">
                <button
                  onClick={() => handleDateChange(-1)}
                  disabled={daysAgo >= 7}
                  className={`tv-date-button ${daysAgo >= 7 ? 'tv-date-button-disabled' : ''}`}
                >
                  ◀
                </button>
                <span className="tv-date-display">{data.currentDateStr}</span>
                <button
                  onClick={() => handleDateChange(1)}
                  disabled={daysAgo <= 0}
                  className={`tv-date-button ${daysAgo <= 0 ? 'tv-date-button-disabled' : ''}`}
                >
                  ▶
                </button>
              </div>
            </div>

            {data.todayTrades.length === 0 ? (
              <div className="tv-empty-state">
                <div className="tv-empty-icon">📊</div>
                <p className="tv-empty-text">해당 날짜에 체결된 거래가 없습니다</p>
              </div>
            ) : (
              <div className="tv-trade-list">
                {data.todayTrades.map((trade, idx) => {
                  const isTradeProfit = trade.pnlAmount >= 0;
                  const exitReason = exitReasonLabels[trade.exitReason] || exitReasonLabels.UNKNOWN;
                  return (
                    <div key={idx} className="tv-trade-item">
                      <div className="tv-trade-main">
                        <div className="tv-trade-header">
                          <span className="tv-market-symbol">{trade.market}</span>
                          <span className={`tv-strategy-badge-small ${
                            trade.strategy === 'Meme Scalper' ? 'tv-strategy-meme' :
                            trade.strategy === 'Volume Surge' ? 'tv-strategy-volume' :
                            'tv-strategy-dca'
                          }`}>
                            {trade.strategy}
                          </span>
                          <span className={`tv-exit-reason-badge ${exitReason.className}`}>
                            {exitReason.label}
                          </span>
                        </div>
                        <div className="tv-trade-prices">
                          <span className="tv-trade-price">
                            <span className="tv-trade-price-label">진입:</span>
                            {trade.entryPrice.toLocaleString()}
                          </span>
                          <span className="tv-trade-price-arrow">→</span>
                          <span className="tv-trade-price">
                            <span className="tv-trade-price-label">청산:</span>
                            {trade.exitPrice.toLocaleString()}
                          </span>
                        </div>
                      </div>
                      <div className="tv-trade-side">
                        <div className={`tv-trade-pnl ${isTradeProfit ? 'tv-pnl-border-positive' : 'tv-pnl-border-negative'}`}>
                          <div className={`tv-trade-pnl-amount ${isTradeProfit ? 'tv-text-green' : 'tv-text-red'}`}>
                            {isTradeProfit ? '+' : ''}{trade.pnlAmount.toLocaleString()}원
                          </div>
                          <div className={`tv-trade-pnl-percent ${isTradeProfit ? 'tv-text-green' : 'tv-text-red'}`}>
                            {isTradeProfit ? '+' : ''}{trade.pnlPercent.toFixed(2)}%
                          </div>
                        </div>
                        <div className="tv-trade-holding-time">
                          {trade.holdingMinutes}분
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
