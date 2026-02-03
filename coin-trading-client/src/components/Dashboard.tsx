import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api';
import './Dashboard.css';

export default function Dashboard() {
  const [daysAgo, setDaysAgo] = useState(0);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['dashboard', daysAgo],
    queryFn: () => dashboardApi.getData(daysAgo),
    refetchInterval: 30000,
  });

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
      <div className="pro-loading-container">
        <div className="pro-spinner"></div>
        <p className="pro-loading-text">데이터를 불러오는 중...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="pro-error-container">
        <div className="pro-error-icon">⚠️</div>
        <p className="pro-error-text">
          오류가 발생했습니다: {error instanceof Error ? error.message : '알 수 없는 오류'}
        </p>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="pro-error-container">
        <p className="pro-error-text">데이터를 불러올 수 없습니다</p>
      </div>
    );
  }

  const exitReasonLabels: Record<string, { label: string; className: string }> = {
    TAKE_PROFIT: { label: '목표 달성', className: 'pro-exit-success' },
    STOP_LOSS: { label: '손절', className: 'pro-exit-danger' },
    TIMEOUT: { label: '시간 초과', className: 'pro-exit-warning' },
    TRAILING_STOP: { label: '트레일링', className: 'pro-exit-info' },
    ABANDONED_NO_BALANCE: { label: '잔고부족', className: 'pro-exit-warning' },
    SIGNAL_REVERSAL: { label: '반전신호', className: 'pro-exit-warning' },
    MANUAL: { label: '수동', className: 'pro-exit-blue' },
    IMBALANCE_FLIP: { label: '밸런스변화', className: 'pro-exit-blue' },
    UNKNOWN: { label: '기타', className: 'pro-exit-warning' },
  };

  const totalPnl = data.todayStats.totalPnl;
  const totalPnlPercent = data.totalAssetKrw > 0
    ? (totalPnl / (data.totalAssetKrw - totalPnl)) * 100
    : 0;
  const isPositive = totalPnl >= 0;

  return (
    <div className="pro-container">
      <div className="pro-content">
        {/* Header */}
        <header className="pro-header">
          <div className="pro-header-left">
            <div className="pro-logo-icon">📈</div>
            <div className="pro-title-section">
              <h1 className="pro-title">코인 트레이딩 대시보드</h1>
              <p className="pro-subtitle">실시간 포트폴리오 모니터링</p>
            </div>
          </div>
          <div className="pro-header-right">
            <div className="pro-live-badge">
              <span className="pro-live-dot"></span>
              <span className="pro-live-text">LIVE</span>
            </div>
            <button className="pro-refresh-btn" onClick={() => refetch()}>
              🔄 새로고침
            </button>
          </div>
        </header>

        {/* Asset Card */}
        <section className="pro-asset-section">
          <div className={`pro-asset-card ${isPositive ? '' : 'pro-loss'}`}>
            <div className="pro-asset-main">
              <div className="pro-asset-label">총 자산 평가금액</div>
              <div className="pro-asset-value">
                {data.totalAssetKrw.toLocaleString('ko-KR')}
                <span className="pro-asset-unit">KRW</span>
              </div>
              <div className="pro-asset-breakdown">
                <span className="pro-asset-breakdown-item">
                  💰 예수금: <strong>{data.krwBalance.toLocaleString('ko-KR')}</strong> KRW
                </span>
                {data.coinAssets && data.coinAssets.length > 0 && (
                  <span className="pro-asset-breakdown-item">
                    🪙 코인: <strong>
                      {data.coinAssets
                        .reduce((sum, c) => sum + c.value, 0)
                        .toLocaleString('ko-KR')}
                    </strong> KRW
                  </span>
                )}
              </div>
            </div>
            <div className="pro-pnl-section">
              <div className={`pro-pnl-badge ${isPositive ? 'pro-profit' : 'pro-loss'}`}>
                <span className="pro-pnl-icon">{isPositive ? '▲' : '▼'}</span>
                <span className="pro-pnl-amount">
                  {isPositive ? '+' : ''}{totalPnl.toLocaleString('ko-KR')} KRW
                </span>
                <span className="pro-pnl-percent">
                  {isPositive ? '+' : ''}{totalPnlPercent.toFixed(2)}%
                </span>
              </div>
            </div>
          </div>
        </section>

        {/* Main Grid */}
        <div className="pro-main-grid">
          {/* Left Column */}
          <div className="pro-left-column">
            {/* Open Positions */}
            {data.openPositions.length > 0 && (
              <div className="pro-card">
                <div className="pro-card-header">
                  <div className="pro-card-title-section">
                    <div className="pro-card-icon">📊</div>
                    <h2 className="pro-card-title">열린 포지션</h2>
                  </div>
                  <span className="pro-card-badge">{data.openPositions.length}</span>
                </div>
                <div className="pro-position-list">
                  {data.openPositions.map((pos, idx) => {
                    const isPosProfit = pos.pnl >= 0;
                    return (
                      <div key={idx} className="pro-position-item">
                        <div className="pro-position-header">
                          <span className="pro-market-symbol">{pos.market}</span>
                          <span className={`pro-strategy-badge ${
                            pos.strategy === 'Meme Scalper' ? 'pro-strategy-meme' :
                            pos.strategy === 'Volume Surge' ? 'pro-strategy-volume' :
                            'pro-strategy-dca'
                          }`}>
                            {pos.strategy}
                          </span>
                        </div>
                        <div className="pro-position-details">
                          <div className="pro-price-info">
                            <div className="pro-price-group">
                              <span className="pro-price-label">진입가</span>
                              <span className="pro-price-value">{pos.entryPrice.toLocaleString()}</span>
                            </div>
                            <div className="pro-price-group">
                              <span className="pro-price-label">현재가</span>
                              <span className={`pro-price-value ${isPosProfit ? 'pro-price-up' : 'pro-price-down'}`}>
                                {pos.currentPrice.toLocaleString()}
                              </span>
                            </div>
                            <div className="pro-price-group">
                              <span className="pro-price-label">수량</span>
                              <span className="pro-price-value">{pos.quantity.toFixed(4)}</span>
                            </div>
                          </div>
                          <div className="pro-position-action">
                            <div className={`pro-pnl-box ${isPosProfit ? 'pro-pnl-positive' : 'pro-pnl-negative'}`}>
                              <span className="pro-pnl-amount">
                                {isPosProfit ? '+' : ''}{pos.pnl.toLocaleString()}원
                              </span>
                              <span className="pro-pnl-percent">
                                ({isPosProfit ? '+' : ''}{pos.pnlPercent.toFixed(2)}%)
                              </span>
                            </div>
                            <button
                              onClick={() => handleManualSell(pos.market, pos.strategy)}
                              className="pro-sell-btn"
                            >
                              매도
                            </button>
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Stats */}
            <div className="pro-card">
              <div className="pro-card-header">
                <div className="pro-card-title-section">
                  <div className="pro-card-icon">📈</div>
                  <h2 className="pro-card-title">오늘의 거래 통계</h2>
                </div>
              </div>
              <div className="pro-stats-grid">
                <div className="pro-stat-item">
                  <div className="pro-stat-icon">📋</div>
                  <div className="pro-stat-label">총 거래</div>
                  <div className="pro-stat-value">{data.todayStats.totalTrades}</div>
                </div>
                <div className="pro-stat-item">
                  <div className="pro-stat-icon">✅</div>
                  <div className="pro-stat-label">승리</div>
                  <div className="pro-stat-value pro-green">{data.todayStats.winCount}</div>
                </div>
                <div className="pro-stat-item">
                  <div className="pro-stat-icon">❌</div>
                  <div className="pro-stat-label">패배</div>
                  <div className="pro-stat-value pro-red">{data.todayStats.lossCount}</div>
                </div>
                <div className="pro-stat-item">
                  <div className="pro-stat-icon">🎯</div>
                  <div className="pro-stat-label">승률</div>
                  <div className={`pro-stat-value ${
                    data.todayStats.winRate >= 0.6 ? 'pro-green' :
                    data.todayStats.winRate >= 0.4 ? 'pro-orange' :
                    'pro-red'
                  }`}>
                    {(data.todayStats.winRate * 100).toFixed(1)}%
                  </div>
                </div>
                <div className={`pro-stat-highlight ${isPositive ? 'pro-stat-positive' : 'pro-stat-negative'}`}>
                  <div className="pro-stat-label">총 손익</div>
                  <div className={`pro-stat-value ${isPositive ? 'pro-green' : 'pro-red'}`}>
                    {isPositive ? '+' : ''}{totalPnl.toLocaleString()}원
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Right Column */}
          <div className="pro-right-column">
            <div className="pro-card">
              <div className="pro-card-header">
                <div className="pro-card-title-section">
                  <div className="pro-card-icon">📜</div>
                  <h2 className="pro-card-title">거래 내역</h2>
                </div>
                <div className="pro-date-selector">
                  <button
                    onClick={() => handleDateChange(-1)}
                    disabled={daysAgo >= 7}
                    className="pro-date-btn"
                  >
                    ◀
                  </button>
                  <span className="pro-date-display">{data.currentDateStr}</span>
                  <button
                    onClick={() => handleDateChange(1)}
                    disabled={daysAgo <= 0}
                    className="pro-date-btn"
                  >
                    ▶
                  </button>
                </div>
              </div>

              {data.todayTrades.length === 0 ? (
                <div className="pro-empty-state">
                  <div className="pro-empty-icon">📊</div>
                  <p className="pro-empty-text">해당 날짜에 체결된 거래가 없습니다</p>
                </div>
              ) : (
                <div className="pro-trade-list">
                  {data.todayTrades.map((trade, idx) => {
                    const isTradeProfit = trade.pnlAmount >= 0;
                    const exitReason = exitReasonLabels[trade.exitReason] || exitReasonLabels.UNKNOWN;
                    return (
                      <div key={idx} className="pro-trade-item">
                        <div className="pro-trade-main">
                          <div className="pro-trade-header">
                            <span className="pro-market-symbol">{trade.market}</span>
                            <div className="pro-trade-badges">
                              <span className={`pro-strategy-badge pro-strategy-badge-small ${
                                trade.strategy === 'Meme Scalper' ? 'pro-strategy-meme' :
                                trade.strategy === 'Volume Surge' ? 'pro-strategy-volume' :
                                'pro-strategy-dca'
                              }`}>
                                {trade.strategy}
                              </span>
                              <span className={`pro-exit-badge ${exitReason.className}`}>
                                {exitReason.label}
                              </span>
                            </div>
                          </div>
                          <div className="pro-trade-prices">
                            <span className="pro-trade-price">
                              <span className="pro-trade-price-label">진입</span>
                              {trade.entryPrice.toLocaleString()}
                            </span>
                            <span className="pro-trade-arrow">→</span>
                            <span className="pro-trade-price">
                              <span className="pro-trade-price-label">청산</span>
                              {trade.exitPrice.toLocaleString()}
                            </span>
                          </div>
                        </div>
                        <div className="pro-trade-side">
                          <div className={`pro-trade-pnl ${isTradeProfit ? 'pro-pnl-positive' : 'pro-pnl-negative'}`}>
                            <span className="pro-trade-pnl-amount">
                              {isTradeProfit ? '+' : ''}{trade.pnlAmount.toLocaleString()}원
                            </span>
                            <span className="pro-trade-pnl-percent">
                              {isTradeProfit ? '+' : ''}{trade.pnlPercent.toFixed(2)}%
                            </span>
                          </div>
                          <div className="pro-trade-time">
                            ⏱ {trade.holdingMinutes}분
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
    </div>
  );
}
