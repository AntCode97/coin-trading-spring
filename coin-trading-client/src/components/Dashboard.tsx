import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { dashboardApi, systemControlApi } from '../api';
import './Dashboard.css';

// YYYY-MM-DD 형식으로 날짜 변환
function formatDate(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

// 날짜 더하기/빼기
function addDays(date: Date, days: number): Date {
  const result = new Date(date);
  result.setDate(result.getDate() + days);
  return result;
}

export default function Dashboard() {
  const [requestDate, setRequestDate] = useState<string | null>(null); // null = 오늘
  const [syncing, setSyncing] = useState(false);
  const [systemControlExpanded, setSystemControlExpanded] = useState(false);
  const [executingAction, setExecutingAction] = useState<string | null>(null);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['dashboard', requestDate],
    queryFn: () => dashboardApi.getData(requestDate),
    refetchInterval: 10000, // 10초마다 새로고침 (긴급 대응용)
  });

  // 현재 조회 중인 날짜 (null이면 오늘)
  const currentDate = requestDate ? new Date(requestDate + 'T00:00:00') : new Date();

  const handleDateChange = (offset: number) => {
    const newDate = addDays(currentDate, offset);
    const today = new Date();

    // 미래 날짜는 조회 불가
    if (newDate > today) {
      return;
    }

    // 최대 30일 전까지만 조회
    const maxDate = addDays(today, -30);
    if (newDate < maxDate) {
      return;
    }

    // 오늘이면 null, 아니면 YYYY-MM-DD
    const isNewToday =
      newDate.getDate() === today.getDate() &&
      newDate.getMonth() === today.getMonth() &&
      newDate.getFullYear() === today.getFullYear();

    setRequestDate(isNewToday ? null : formatDate(newDate));
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

  const handleSync = async () => {
    if (!confirm('실제 잔고와 DB 포지션을 동기화하시겠습니까?\n\n잔고가 없는 포지션은 자동으로 CLOSED 처리됩니다.')) {
      return;
    }

    setSyncing(true);
    try {
      const result = await dashboardApi.syncPositions();

      let message = result.message;
      if (result.actions.length > 0) {
        message += '\n\n상세 내역:\n';
        result.actions.forEach((action, idx) => {
          message += `${idx + 1}. ${action.market} (${action.strategy})\n`;
          message += `   ${action.reason}\n`;
        });
      }

      if (result.fixedCount > 0) {
        alert(`동기화 완료!\n\n${message}`);
      } else {
        alert(`동기화 완료!\n\n${message}\n\n수정된 항목이 없습니다. 모든 포지션이 정상입니다.`);
      }

      refetch();
    } catch (e: any) {
      alert('동기화 오류: ' + e.message);
    } finally {
      setSyncing(false);
    }
  };

  // 시스템 제어 핸들러
  const handleSystemAction = async (actionName: string, apiCall: () => Promise<any>, confirmMsg?: string) => {
    if (confirmMsg && !confirm(confirmMsg)) {
      return;
    }

    setExecutingAction(actionName);
    try {
      const result = await apiCall();
      alert(result.message || '완료되었습니다.');
      refetch();
    } catch (e: any) {
      alert('오류: ' + e.message);
    } finally {
      setExecutingAction(null);
    }
  };

  const isActionExecuting = (actionName: string) => executingAction === actionName;

  if (isLoading) {
    return (
      <div className="toss-loading-container">
        <div className="toss-spinner"></div>
        <p className="toss-loading-text">데이터를 불러오는 중...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="toss-error-container">
        <p className="toss-error-text">
          오류가 발생했습니다: {error instanceof Error ? error.message : '알 수 없는 오류'}
        </p>
        <button className="toss-retry-btn" onClick={() => refetch()}>재시도</button>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="toss-error-container">
        <p className="toss-error-text">데이터를 불러올 수 없습니다</p>
        <button className="toss-retry-btn" onClick={() => refetch()}>재시도</button>
      </div>
    );
  }

  const exitReasonLabels: Record<string, { label: string; className: string }> = {
    TAKE_PROFIT: { label: '목표 달성', className: 'toss-exit-success' },
    STOP_LOSS: { label: '손절', className: 'toss-exit-danger' },
    TIMEOUT: { label: '시간 초과', className: 'toss-exit-warning' },
    TRAILING_STOP: { label: '트레일링', className: 'toss-exit-info' },
    ABANDONED_NO_BALANCE: { label: '잔고부족', className: 'toss-exit-warning' },
    SIGNAL_REVERSAL: { label: '반전신호', className: 'toss-exit-warning' },
    MANUAL: { label: '수동', className: 'toss-exit-info' },
    IMBALANCE_FLIP: { label: '밸런스변화', className: 'toss-exit-info' },
    UNKNOWN: { label: '기타', className: 'toss-exit-warning' },
  };

  const totalPnl = data.todayStats.totalPnl;
  const totalPnlPercent = data.totalAssetKrw > 0
    ? (totalPnl / (data.totalAssetKrw - totalPnl)) * 100
    : 0;
  const isPositive = totalPnl >= 0;

  return (
    <div className="toss-container">
      <div className="toss-content">
        {/* Header */}
        <header className="toss-header">
          <div className="toss-header-left">
            <div className="toss-logo">📈</div>
            <div className="toss-title-section">
              <h1 className="toss-title">코인 트레이딩</h1>
              <p className="toss-subtitle">실시간 포트폴리오 현황</p>
            </div>
          </div>
          <div className="toss-header-right">
            <div className="toss-live-badge">
              <span className="toss-live-dot"></span>
              <span className="toss-live-text">실시간</span>
            </div>
            <button
              className={`toss-sync-btn ${syncing ? 'syncing' : ''}`}
              onClick={handleSync}
              disabled={syncing}
            >
              {syncing ? '동기화 중...' : '잔고 동기화'}
            </button>
            <button
              className={`toss-system-toggle ${systemControlExpanded ? 'expanded' : ''}`}
              onClick={() => setSystemControlExpanded(!systemControlExpanded)}
            >
              ⚙️ 시스템 제어
              <span className={`toss-toggle-arrow ${systemControlExpanded ? 'open' : ''}`}>▼</span>
            </button>
            <button className="toss-refresh-btn" onClick={() => refetch()}>
              새로고침
            </button>
          </div>
        </header>

        {/* System Control Panel */}
        {systemControlExpanded && (
          <div className="system-control-panel">
            <div className="system-control-grid">
              {/* AI & Optimization */}
              <div className="control-group">
                <h3 className="control-group-title">🤖 AI & 최적화</h3>
                <div className="control-buttons">
                  <button
                    className="control-btn control-btn-primary"
                    onClick={() => handleSystemAction('optimizer', () => systemControlApi.runOptimizer(), 'LLM 최적화를 실행하시겠습니까?')}
                    disabled={isActionExecuting('optimizer')}
                  >
                    {isActionExecuting('optimizer') ? '실행 중...' : 'LLM 최적화 실행'}
                  </button>
                </div>
              </div>

              {/* Strategy Control */}
              <div className="control-group">
                <h3 className="control-group-title">📊 전략 제어</h3>
                <div className="control-buttons">
                  <button
                    className="control-btn"
                    onClick={() => handleSystemAction('vs-reflection', () => systemControlApi.runVolumeSurgeReflection())}
                    disabled={isActionExecuting('vs-reflection')}
                  >
                    {isActionExecuting('vs-reflection') ? '실행 중...' : 'VS 회고'}
                  </button>
                  <button
                    className="control-btn control-btn-warning"
                    onClick={() => handleSystemAction('vs-reset', () => systemControlApi.resetVolumeSurgeCircuitBreaker(), 'Volume Surge 서킷 브레이커를 리셋하시겠습니까?')}
                    disabled={isActionExecuting('vs-reset')}
                  >
                    {isActionExecuting('vs-reset') ? '리셋 중...' : 'VS 서킷 리셋'}
                  </button>
                  <button
                    className="control-btn"
                    onClick={() => handleSystemAction('ms-reflection', () => systemControlApi.runMemeScalperReflection())}
                    disabled={isActionExecuting('ms-reflection')}
                  >
                    {isActionExecuting('ms-reflection') ? '실행 중...' : 'MS 회고'}
                  </button>
                  <button
                    className="control-btn control-btn-warning"
                    onClick={() => handleSystemAction('ms-reset', () => systemControlApi.resetMemeScalperCircuitBreaker(), 'Meme Scalper 서킷 브레이커를 리셋하시겠습니까?')}
                    disabled={isActionExecuting('ms-reset')}
                  >
                    {isActionExecuting('ms-reset') ? '리셋 중...' : 'MS 서킷 리셋'}
                  </button>
                </div>
              </div>

              {/* Data Refresh */}
              <div className="control-group">
                <h3 className="control-group-title">🔄 데이터 갱신</h3>
                <div className="control-buttons">
                  <button
                    className="control-btn"
                    onClick={() => handleSystemAction('exchange-rate', () => systemControlApi.refreshExchangeRate())}
                    disabled={isActionExecuting('exchange-rate')}
                  >
                    {isActionExecuting('exchange-rate') ? '갱신 중...' : '환율 갱신'}
                  </button>
                  <button
                    className="control-btn"
                    onClick={() => handleSystemAction('funding-scan', () => systemControlApi.scanFundingOpportunities())}
                    disabled={isActionExecuting('funding-scan')}
                  >
                    {isActionExecuting('funding-scan') ? '스캔 중...' : '펀딩 스캔'}
                  </button>
                  <button
                    className="control-btn control-btn-secondary"
                    onClick={() => handleSystemAction('cache-refresh', () => systemControlApi.refreshCache())}
                    disabled={isActionExecuting('cache-refresh')}
                  >
                    {isActionExecuting('cache-refresh') ? '갱신 중...' : '캐시 갱신'}
                  </button>
                </div>
              </div>

              {/* Sync */}
              <div className="control-group">
                <h3 className="control-group-title">🔗 동기화</h3>
                <div className="control-buttons">
                  <button
                    className="control-btn"
                    onClick={() => handleSystemAction('sync-orders', () => dashboardApi.syncOrders())}
                    disabled={isActionExecuting('sync-orders')}
                  >
                    {isActionExecuting('sync-orders') ? '확인 중...' : '미체결 주문 확인'}
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Asset Card */}
        <section className="toss-asset-section">
          <div className={`toss-asset-card ${isPositive ? '' : 'toss-loss'}`}>
            <div className="toss-asset-main">
              <div className="toss-asset-label">총 자산</div>
              <div className="toss-asset-value">
                {data.totalAssetKrw.toLocaleString('ko-KR')}
                <span className="toss-asset-unit">원</span>
              </div>
              <div className="toss-asset-breakdown">
                <span className="toss-asset-breakdown-item">
                  예수금 <strong>{data.krwBalance.toLocaleString('ko-KR')}</strong>원
                </span>
                {data.coinAssets && data.coinAssets.length > 0 && (
                  <span className="toss-asset-breakdown-item">
                    코인 <strong>
                      {data.coinAssets
                        .reduce((sum, c) => sum + c.value, 0)
                        .toLocaleString('ko-KR')}
                    </strong>원
                  </span>
                )}
              </div>
            </div>
            <div className="toss-pnl-section">
              <div className={`toss-pnl-badge ${isPositive ? 'toss-profit' : 'toss-loss'}`}>
                <span className="toss-pnl-icon">{isPositive ? '▲' : '▼'}</span>
                <span className="toss-pnl-amount">
                  {isPositive ? '+' : ''}{totalPnl.toLocaleString('ko-KR')}원
                </span>
                <span className="toss-pnl-percent">
                  {isPositive ? '+' : ''}{totalPnlPercent.toFixed(2)}%
                </span>
              </div>
            </div>
          </div>
        </section>

        {/* Main Grid */}
        <div className="toss-main-grid">
          {/* Left Column */}
          <div className="toss-left-column">
            {/* Open Positions */}
            {data.openPositions.length > 0 && (
              <div className="toss-card">
                <div className="toss-card-header">
                  <div className="toss-card-title-section">
                    <div className="toss-card-icon">📊</div>
                    <h2 className="toss-card-title">보유 중인 포지션</h2>
                  </div>
                  <span className="toss-card-badge">{data.openPositions.length}개</span>
                </div>
                <div className="toss-position-list">
                  {data.openPositions.map((pos, idx) => {
                    const isPosProfit = pos.pnl >= 0;
                    const isBigLoss = !isPosProfit && pos.pnl < -5000; // 5000원 이상 손실
                    return (
                      <div key={idx} className={`toss-position-item ${isBigLoss ? 'toss-position-danger' : ''}`}>
                        <div className="toss-position-header">
                          <span className="toss-market-symbol">{pos.market}</span>
                          <span className={`toss-strategy-badge ${
                            pos.strategy === 'Meme Scalper' ? 'toss-strategy-meme' :
                            pos.strategy === 'Volume Surge' ? 'toss-strategy-volume' :
                            'toss-strategy-dca'
                          }`}>
                            {pos.strategy}
                          </span>
                        </div>
                        <div className="toss-position-details">
                          <div className="toss-price-info">
                            <div className="toss-price-group">
                              <span className="toss-price-label">매수가</span>
                              <span className="toss-price-value">{pos.entryPrice.toLocaleString()}원</span>
                            </div>
                            <div className="toss-price-group">
                              <span className="toss-price-label">현재가</span>
                              <span className={`toss-price-value ${isPosProfit ? 'toss-price-up' : 'toss-price-down'}`}>
                                {pos.currentPrice.toLocaleString()}원
                              </span>
                            </div>
                            <div className="toss-price-group">
                              <span className="toss-price-label">수량</span>
                              <span className="toss-price-value">{pos.quantity.toFixed(4)}</span>
                            </div>
                          </div>
                          <div className="toss-position-action">
                            <div className={`toss-pnl-box ${isPosProfit ? 'toss-pnl-positive' : 'toss-pnl-negative'} ${isBigLoss ? 'toss-pnl-danger' : ''}`}>
                              <span className="toss-pnl-amount">
                                {isPosProfit ? '+' : ''}{pos.pnl.toLocaleString()}원
                              </span>
                              <span className="toss-pnl-percent">
                                ({isPosProfit ? '+' : ''}{pos.pnlPercent.toFixed(2)}%)
                              </span>
                            </div>
                            <button
                              onClick={() => handleManualSell(pos.market, pos.strategy)}
                              className="toss-sell-btn"
                            >
                              매도하기
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
            <div className="toss-card">
              <div className="toss-card-header">
                <div className="toss-card-title-section">
                  <div className="toss-card-icon">📈</div>
                  <h2 className="toss-card-title">오늘의 투자 성과</h2>
                </div>
              </div>
              <div className="toss-stats-grid">
                <div className="toss-stat-item">
                  <div className="toss-stat-label">총 거래</div>
                  <div className="toss-stat-value">{data.todayStats.totalTrades}회</div>
                </div>
                <div className="toss-stat-item">
                  <div className="toss-stat-label">수익</div>
                  <div className="toss-stat-value toss-green">{data.todayStats.winCount}회</div>
                </div>
                <div className="toss-stat-item">
                  <div className="toss-stat-label">손실</div>
                  <div className="toss-stat-value toss-red">{data.todayStats.lossCount}회</div>
                </div>
                <div className="toss-stat-item">
                  <div className="toss-stat-label">승률</div>
                  <div className={`toss-stat-value ${
                    data.todayStats.winRate >= 0.6 ? 'toss-green' :
                    data.todayStats.winRate >= 0.4 ? 'toss-orange' :
                    'toss-red'
                  }`}>
                    {(data.todayStats.winRate * 100).toFixed(0)}%
                  </div>
                </div>
                <div className={`toss-stat-highlight ${isPositive ? 'toss-stat-positive' : 'toss-stat-negative'}`}>
                  <div className="toss-stat-label">총 수익</div>
                  <div className={`toss-stat-value ${isPositive ? 'toss-green' : 'toss-red'}`}>
                    {isPositive ? '+' : ''}{totalPnl.toLocaleString()}원
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Right Column */}
          <div className="toss-right-column">
            <div className="toss-card">
              <div className="toss-card-header">
                <div className="toss-card-title-section">
                  <div className="toss-card-icon">📋</div>
                  <h2 className="toss-card-title">체결 내역</h2>
                </div>
                <div className="toss-date-selector">
                  <button
                    onClick={() => handleDateChange(-1)}
                    disabled={isTodayDisabled(currentDate)}
                    className="toss-date-btn"
                    aria-label="이전 날짜"
                  >
                    ◀
                  </button>
                  <span className="toss-date-display">{data.currentDateStr}</span>
                  <button
                    onClick={() => handleDateChange(1)}
                    disabled={requestDate === null}
                    className="toss-date-btn"
                    aria-label="다음 날짜"
                  >
                    ▶
                  </button>
                </div>
              </div>

              {data.todayTrades.length === 0 ? (
                <div className="toss-empty-state">
                  <div className="toss-empty-icon">📊</div>
                  <p className="toss-empty-text">체결된 거래가 없어요</p>
                </div>
              ) : (
                <div className="tv-trade-list">
                  {data.todayTrades.map((trade, idx) => {
                    const isTradeProfit = trade.pnlAmount >= 0;
                    const exitReason = exitReasonLabels[trade.exitReason] || exitReasonLabels.UNKNOWN;
                    return (
                      <div key={idx} className={`tv-trade-row ${isTradeProfit ? 'tv-profit' : 'tv-loss'}`}>
                        {/* Left Color Indicator */}
                        <div className={`tv-indicator ${isTradeProfit ? 'tv-indicator-profit' : 'tv-indicator-loss'}`} />

                        {/* Main Content */}
                        <div className="tv-trade-content">
                          {/* Header Row */}
                          <div className="tv-trade-header">
                            <div className="tv-trade-identity">
                              <span className="tv-market">{trade.market}</span>
                              <span className={`tv-strategy ${
                                trade.strategy === 'Meme Scalper' ? 'tv-strategy-meme' :
                                trade.strategy === 'Volume Surge' ? 'tv-strategy-volume' :
                                'tv-strategy-dca'
                              }`}>
                                {trade.strategy}
                              </span>
                            </div>
                            <div className="tv-trade-meta">
                              <span className="tv-exit-reason" data-reason={exitReason.label}>{exitReason.label}</span>
                              <span className="tv-holding-time">{trade.holdingMinutes}m</span>
                            </div>
                          </div>

                          {/* Detail Row */}
                          <div className="tv-trade-details">
                            <div className="tv-prices">
                              <div className="tv-price-group">
                                <span className="tv-price-label">IN</span>
                                <span className="tv-price-value">{trade.entryPrice.toLocaleString()}</span>
                              </div>
                              <svg className="tv-arrow-icon" width="12" height="12" viewBox="0 0 12 12" fill="none">
                                <path d="M4 2L8 6L4 10" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
                              </svg>
                              <div className="tv-price-group">
                                <span className="tv-price-label">OUT</span>
                                <span className="tv-price-value">{trade.exitPrice.toLocaleString()}</span>
                              </div>
                            </div>
                            <div className="tv-times">
                              <span className="tv-time">{trade.entryTimeFormatted}</span>
                              <span className="tv-time-separator">—</span>
                              <span className="tv-time">{trade.exitTimeFormatted}</span>
                            </div>
                          </div>
                        </div>

                        {/* PnL Section */}
                        <div className="tv-pnl-section">
                          <div className={`tv-pnl ${isTradeProfit ? 'tv-pnl-profit' : 'tv-pnl-loss'}`}>
                            <span className="tv-pnl-amount">
                              {isTradeProfit ? '+' : ''}{trade.pnlAmount.toLocaleString()}
                            </span>
                            <span className="tv-pnl-percent">
                              {isTradeProfit ? '+' : ''}{trade.pnlPercent.toFixed(2)}%
                            </span>
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

// 오늘 날짜인지 확인 (이전 버튼 disabled용)
function isTodayDisabled(date: Date): boolean {
  const today = new Date();
  const maxDate = addDays(today, -30);
  return date <= maxDate;
}
