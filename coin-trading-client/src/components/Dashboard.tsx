import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api';
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
  const [emergencyMode, setEmergencyMode] = useState(false);

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

  // 긴급 전체 매도
  const handleEmergencySellAll = async () => {
    if (!data || data.openPositions.length === 0) return;

    const totalLoss = data.openPositions.reduce((sum, pos) => sum + (pos.pnl < 0 ? pos.pnl : 0), 0);
    const lossText = totalLoss < 0 ? `${Math.abs(totalLoss).toLocaleString()}원 손실` : `${totalLoss.toLocaleString()}원 이익`;

    if (!confirm(`긴급 전체 매도를 실행합니다.\n\n${data.openPositions.length}개 포지션 (${lossText})\n\n정말 모두 매도하시겠습니까?`)) {
      return;
    }

    let successCount = 0;
    let failCount = 0;

    for (const pos of data.openPositions) {
      try {
        const result = await dashboardApi.manualClose(pos.market, pos.strategy);
        if (result.success) {
          successCount++;
        } else {
          failCount++;
          console.error(`${pos.market} 매도 실패:`, result.error);
        }
      } catch (e: any) {
        failCount++;
        console.error(`${pos.market} 매도 오류:`, e);
      }
    }

    alert(`전체 매도 완료!\n성공: ${successCount}건\n실패: ${failCount}건`);
    refetch();
  };

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

  // 총 손실 계산 (긴급 모드 판단용)
  const totalLoss = data.openPositions.reduce((sum, pos) => sum + (pos.pnl < 0 ? pos.pnl : 0), 0);
  const hasSignificantLoss = totalLoss < -5000; // 5000원 이상 손실이면 경고

  return (
    <div className={`toss-container ${emergencyMode ? 'toss-emergency-mode' : ''}`}>
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
            <button className="toss-refresh-btn" onClick={() => refetch()}>
              새로고침
            </button>
            {/* 긴급 모드 토글 (모바일용) */}
            <button
              className={`toss-emergency-toggle ${emergencyMode ? 'active' : ''}`}
              onClick={() => setEmergencyMode(!emergencyMode)}
              aria-label="긴급 모드"
            >
              🚨
            </button>
          </div>
        </header>

        {/* 긴급 경고 배너 */}
        {hasSignificantLoss && (
          <div className="toss-emergency-banner">
            <span className="toss-emergency-icon">⚠️</span>
            <span className="toss-emergency-text">
              전체 손실 {Math.abs(totalLoss).toLocaleString()}원 - 긴급 매도 고려
            </span>
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

        {/* 긴급 액션 버튼 (모바일 우선) */}
        {data.openPositions.length > 0 && (
          <section className="toss-emergency-actions">
            <button
              className="toss-emergency-sell-all-btn"
              onClick={handleEmergencySellAll}
            >
              <span className="toss-emergency-icon">🚨</span>
              <span className="toss-emergency-text">전체 매도 ({data.openPositions.length}개)</span>
            </button>
          </section>
        )}

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
                <div className="toss-trade-list">
                  {data.todayTrades.map((trade, idx) => {
                    const isTradeProfit = trade.pnlAmount >= 0;
                    const exitReason = exitReasonLabels[trade.exitReason] || exitReasonLabels.UNKNOWN;
                    return (
                      <div key={idx} className="toss-trade-item">
                        <div className="toss-trade-main">
                          <div className="toss-trade-header">
                            <span className="toss-market-symbol">{trade.market}</span>
                            <div className="toss-trade-badges">
                              <span className={`toss-strategy-badge toss-strategy-badge-small ${
                                trade.strategy === 'Meme Scalper' ? 'toss-strategy-meme' :
                                trade.strategy === 'Volume Surge' ? 'toss-strategy-volume' :
                                'toss-strategy-dca'
                              }`}>
                                {trade.strategy}
                              </span>
                              <span className={`toss-exit-badge ${exitReason.className}`}>
                                {exitReason.label}
                              </span>
                            </div>
                          </div>
                          <div className="toss-trade-prices">
                            <span className="toss-trade-price">
                              <span className="toss-trade-price-label">{trade.entryPrice.toLocaleString()}원</span>
                              <span className="toss-trade-arrow">→</span>
                              <span className="toss-trade-price-label">{trade.exitPrice.toLocaleString()}원</span>
                            </span>
                          </div>
                        </div>
                        <div className="toss-trade-side">
                          <div className={`toss-trade-pnl ${isTradeProfit ? 'toss-pnl-positive' : 'toss-pnl-negative'}`}>
                            <span className="toss-trade-pnl-amount">
                              {isTradeProfit ? '+' : ''}{trade.pnlAmount.toLocaleString()}원
                            </span>
                            <span className="toss-trade-pnl-percent">
                              {isTradeProfit ? '+' : ''}{trade.pnlPercent.toFixed(2)}%
                            </span>
                          </div>
                          <div className="toss-trade-time">
                            {trade.holdingMinutes}분 보유
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

        {/* 긴급 모드 하단 고정 버튼 (모바일) */}
        {emergencyMode && data.openPositions.length > 0 && (
          <div className="toss-emergency-fixed">
            <button
              className="toss-emergency-fixed-btn"
              onClick={handleEmergencySellAll}
            >
              🚨 전체 매도 ({data.openPositions.length}개)
            </button>
          </div>
        )}
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
