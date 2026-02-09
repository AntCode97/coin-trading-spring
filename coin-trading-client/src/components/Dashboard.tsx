import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  dashboardApi,
  systemControlApi,
  type ClosedTradeInfo,
  type FundingScanResult,
  type FundingStatus,
  type PositionInfo,
  type SyncResult,
  type SystemControlResult,
} from '../api';
import {
  type ConfirmActionOptions,
  useDashboardFeedback,
} from './dashboard/useDashboardFeedback';

import './Dashboard.css';

type PositionFilter = 'ALL' | 'PROFIT' | 'LOSING' | 'HIGH_RISK';
type PositionSort = 'RISK_DESC' | 'PNL_ASC' | 'PNL_DESC' | 'VALUE_DESC';
type TradeFilter = 'ALL' | 'PROFIT' | 'LOSS';
type ActionResponse = SystemControlResult | FundingScanResult | SyncResult;

const MAX_LOOKBACK_DAYS = 30;
const HIGH_RISK_PNL_THRESHOLD = -2.0;

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

function formatDate(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function addDays(date: Date, days: number): Date {
  const result = new Date(date);
  result.setDate(result.getDate() + days);
  return result;
}

function normalizeDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function isStopLossBreached(position: PositionInfo): boolean {
  return position.stopLossPrice > 0 && position.currentPrice <= position.stopLossPrice;
}

function isHighRiskPosition(position: PositionInfo): boolean {
  return isStopLossBreached(position) || position.pnlPercent <= HIGH_RISK_PNL_THRESHOLD;
}

function matchesPositionFilter(position: PositionInfo, filter: PositionFilter): boolean {
  if (filter === 'PROFIT') {
    return position.pnl >= 0;
  }
  if (filter === 'LOSING') {
    return position.pnl < 0;
  }
  if (filter === 'HIGH_RISK') {
    return isHighRiskPosition(position);
  }
  return true;
}

function sortPositions(positions: PositionInfo[], sortType: PositionSort): PositionInfo[] {
  const copied = [...positions];

  if (sortType === 'PNL_ASC') {
    copied.sort((a, b) => a.pnl - b.pnl);
    return copied;
  }

  if (sortType === 'PNL_DESC') {
    copied.sort((a, b) => b.pnl - a.pnl);
    return copied;
  }

  if (sortType === 'VALUE_DESC') {
    copied.sort((a, b) => b.value - a.value);
    return copied;
  }

  copied.sort((a, b) => {
    const riskGap = Number(isHighRiskPosition(b)) - Number(isHighRiskPosition(a));
    if (riskGap !== 0) {
      return riskGap;
    }
    return a.pnl - b.pnl;
  });
  return copied;
}

function toErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim().length > 0) {
    return error.message;
  }
  return '알 수 없는 오류';
}

function extractActionMessage(result: ActionResponse): string {
  if ('totalOpportunities' in result) {
    return `스캔 완료: 총 ${result.totalOpportunities}개 기회 발견`;
  }

  if ('message' in result && typeof result.message === 'string' && result.message.trim().length > 0) {
    return result.message;
  }

  return '완료되었습니다.';
}

export default function Dashboard() {
  const [requestDate, setRequestDate] = useState<string | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [systemControlExpanded, setSystemControlExpanded] = useState(false);
  const [executingAction, setExecutingAction] = useState<string | null>(null);
  const [fundingExpanded, setFundingExpanded] = useState(false);
  const [positionSearch, setPositionSearch] = useState('');
  const [positionFilter, setPositionFilter] = useState<PositionFilter>('ALL');
  const [positionSort, setPositionSort] = useState<PositionSort>('RISK_DESC');
  const [tradeFilter, setTradeFilter] = useState<TradeFilter>('ALL');
  const [lastSyncResult, setLastSyncResult] = useState<SyncResult | null>(null);

  const { confirmAction, notify, feedbackUi } = useDashboardFeedback();

  const {
    data,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['dashboard', requestDate],
    queryFn: () => dashboardApi.getData(requestDate),
    refetchInterval: 10000,
  });

  const { data: fundingStatus, refetch: refetchFunding } = useQuery<FundingStatus>({
    queryKey: ['funding-status'],
    queryFn: () => systemControlApi.getFundingStatus(),
    refetchInterval: 30000,
  });

  const {
    data: fundingScanResult,
    refetch: refetchOpportunities,
  } = useQuery<FundingScanResult>({
    queryKey: ['funding-opportunities'],
    queryFn: () => systemControlApi.scanFundingOpportunities(),
    enabled: fundingExpanded,
  });

  const currentDate = requestDate ? new Date(`${requestDate}T00:00:00`) : new Date();

  const today = normalizeDay(new Date());
  const maxLookbackDate = addDays(today, -MAX_LOOKBACK_DAYS);

  const canMovePrevDate = normalizeDay(currentDate) > maxLookbackDate;
  const canMoveNextDate = requestDate !== null;

  const filteredPositions = useMemo(() => {
    if (!data) {
      return [];
    }

    const normalizedQuery = positionSearch.trim().toLowerCase();

    const selected = data.openPositions.filter((position) => {
      if (normalizedQuery.length > 0) {
        const market = position.market.toLowerCase();
        const strategy = position.strategy.toLowerCase();
        const matched = market.includes(normalizedQuery) || strategy.includes(normalizedQuery);
        if (!matched) {
          return false;
        }
      }

      return matchesPositionFilter(position, positionFilter);
    });

    return sortPositions(selected, positionSort);
  }, [data, positionFilter, positionSearch, positionSort]);

  const filteredTrades = useMemo(() => {
    if (!data) {
      return [];
    }

    if (tradeFilter === 'PROFIT') {
      return data.todayTrades.filter((trade) => trade.pnlAmount >= 0);
    }

    if (tradeFilter === 'LOSS') {
      return data.todayTrades.filter((trade) => trade.pnlAmount < 0);
    }

    return data.todayTrades;
  }, [data, tradeFilter]);

  const riskSummary = useMemo(() => {
    if (!data) {
      return {
        openCount: 0,
        highRiskCount: 0,
        stopLossBreachCount: 0,
        losingExposure: 0,
      };
    }

    const highRiskCount = data.openPositions.filter((position) => isHighRiskPosition(position)).length;
    const stopLossBreachCount = data.openPositions.filter((position) => isStopLossBreached(position)).length;
    const losingExposure = data.openPositions
      .filter((position) => position.pnl < 0)
      .reduce((sum, position) => sum + position.value, 0);

    return {
      openCount: data.openPositions.length,
      highRiskCount,
      stopLossBreachCount,
      losingExposure,
    };
  }, [data]);

  const executeSystemAction = async (
    actionName: string,
    apiCall: () => Promise<ActionResponse>,
    options?: {
      confirm?: ConfirmActionOptions;
      successMessage?: (result: ActionResponse) => string;
      refreshFunding?: boolean;
      refreshOpportunities?: boolean;
    }
  ) => {
    if (options?.confirm) {
      const accepted = await confirmAction(options.confirm);
      if (!accepted) {
        return;
      }
    }

    setExecutingAction(actionName);

    try {
      const result = await apiCall();
      const message = options?.successMessage ? options.successMessage(result) : extractActionMessage(result);
      notify(message, 'success');
      await refetch();

      if (options?.refreshFunding) {
        await refetchFunding();
      }

      if (options?.refreshOpportunities) {
        await refetchOpportunities();
      }
    } catch (actionError) {
      notify(`오류: ${toErrorMessage(actionError)}`, 'error');
    } finally {
      setExecutingAction(null);
    }
  };

  const handleDateChange = (offset: number) => {
    const selectedDay = normalizeDay(currentDate);
    const nextDate = normalizeDay(addDays(selectedDay, offset));

    if (nextDate > today || nextDate < maxLookbackDate) {
      return;
    }

    const movingToToday = nextDate.getTime() === today.getTime();
    setRequestDate(movingToToday ? null : formatDate(nextDate));
  };

  const handleManualSell = async (market: string, strategy: string) => {
    const accepted = await confirmAction({
      title: `${market} (${strategy}) 포지션 매도`,
      description: '실거래 주문이 즉시 실행될 수 있습니다. 현재 시장 가격과 슬리피지를 확인한 뒤 진행하세요.',
      confirmLabel: '매도 실행',
      danger: true,
    });

    if (!accepted) {
      return;
    }

    const actionName = `manual-sell:${market}:${strategy}`;
    setExecutingAction(actionName);

    try {
      const result = await dashboardApi.manualClose(market, strategy);
      if (result.success) {
        notify('매도 주문이 접수되었습니다.', 'success');
        await refetch();
      } else {
        notify(`매도 실패: ${result.error ?? '알 수 없는 오류'}`, 'error');
      }
    } catch (manualSellError) {
      notify(`매도 오류: ${toErrorMessage(manualSellError)}`, 'error');
    } finally {
      setExecutingAction(null);
    }
  };

  const handleSync = async () => {
    const accepted = await confirmAction({
      title: '실제 잔고와 포지션 동기화',
      description: '잔고가 없는 포지션은 자동으로 CLOSED 처리됩니다. 실거래 중이라면 먼저 미체결 주문을 확인하세요.',
      confirmLabel: '동기화 실행',
      danger: true,
    });

    if (!accepted) {
      return;
    }

    setSyncing(true);

    try {
      const result = await dashboardApi.syncPositions();
      setLastSyncResult(result);

      if (result.fixedCount > 0) {
        notify(`동기화 완료: ${result.fixedCount}건 수정`, 'success');
      } else {
        notify('동기화 완료: 수정 항목 없음', 'info');
      }

      await refetch();
    } catch (syncError) {
      notify(`동기화 오류: ${toErrorMessage(syncError)}`, 'error');
    } finally {
      setSyncing(false);
    }
  };

  const handleFundingToggle = async () => {
    if (!fundingStatus) {
      return;
    }

    const nextState = !fundingStatus.autoTradingEnabled;
    const accepted = await confirmAction({
      title: `펀딩 자동거래 ${nextState ? '활성화' : '비활성화'}`,
      description: '자동진입/자동청산 상태가 즉시 변경됩니다.',
      confirmLabel: nextState ? '자동 ON' : '자동 OFF',
      danger: !nextState,
    });

    if (!accepted) {
      return;
    }

    setExecutingAction('funding-toggle');

    try {
      await systemControlApi.toggleFundingAutoTrading(nextState);
      notify(nextState ? '자동 거래가 활성화되었습니다.' : '자동 거래가 비활성화되었습니다.', 'success');
      await refetchFunding();
    } catch (toggleError) {
      notify(`토글 오류: ${toErrorMessage(toggleError)}`, 'error');
    } finally {
      setExecutingAction(null);
    }
  };

  const handleFundingScan = async () => {
    await executeSystemAction(
      'funding-scan',
      () => systemControlApi.scanFundingOpportunities(),
      {
        confirm: {
          title: '펀딩 차익 기회 스캔',
          description: '실시간 펀딩 비율 데이터를 조회합니다.',
          confirmLabel: '스캔 실행',
        },
        successMessage: (result) => {
          if ('totalOpportunities' in result) {
            return `스캔 완료: 총 ${result.totalOpportunities}개 기회`;
          }
          return extractActionMessage(result);
        },
        refreshOpportunities: true,
      }
    );
  };

  const isActionExecuting = (actionName: string): boolean => executingAction === actionName;
  const hasExecutingAction = executingAction !== null;

  if (isLoading) {
    return (
      <div className="toss-loading-container">
        <div className="toss-spinner"></div>
        <p className="toss-loading-text">데이터를 불러오는 중...</p>
        {feedbackUi}
      </div>
    );
  }

  if (error) {
    return (
      <div className="toss-error-container">
        <p className="toss-error-text">
          오류가 발생했습니다: {error instanceof Error ? error.message : '알 수 없는 오류'}
        </p>
        <button className="toss-retry-btn" type="button" onClick={() => void refetch()}>재시도</button>
        {feedbackUi}
      </div>
    );
  }

  if (!data) {
    return (
      <div className="toss-error-container">
        <p className="toss-error-text">데이터를 불러올 수 없습니다</p>
        <button className="toss-retry-btn" type="button" onClick={() => void refetch()}>재시도</button>
        {feedbackUi}
      </div>
    );
  }

  const totalPnl = data.todayStats.totalPnl;
  const investedBase = data.totalAssetKrw - totalPnl;
  const totalPnlPercent = investedBase > 0 ? (totalPnl / investedBase) * 100 : 0;
  const isPositive = totalPnl >= 0;

  return (
    <div className="toss-container">
      <div className="toss-content">
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

            {data.activeStrategies && data.activeStrategies.length > 0 && (
              <div className="toss-strategies-container">
                {data.activeStrategies.map((strategy) => (
                  <span
                    key={strategy.code}
                    className={`toss-strategy-tag toss-strategy-${strategy.code}`}
                    title={strategy.description}
                  >
                    {strategy.name}
                  </span>
                ))}
              </div>
            )}

            <button
              className={`toss-sync-btn ${syncing ? 'syncing' : ''}`}
              type="button"
              onClick={() => void handleSync()}
              disabled={syncing}
            >
              {syncing ? '동기화 중...' : '잔고 동기화'}
            </button>

            <button
              className={`toss-system-toggle ${systemControlExpanded ? 'expanded' : ''}`}
              type="button"
              onClick={() => setSystemControlExpanded((current) => !current)}
            >
              ⚙️ 시스템 제어
              <span className={`toss-toggle-arrow ${systemControlExpanded ? 'open' : ''}`}>▼</span>
            </button>

            <button
              className={`toss-funding-toggle ${fundingExpanded ? 'expanded' : ''}`}
              type="button"
              onClick={() => setFundingExpanded((current) => !current)}
            >
              💰 펀딩 차익
              <span className={`toss-toggle-arrow ${fundingExpanded ? 'open' : ''}`}>▼</span>
            </button>

            <button className="toss-refresh-btn" type="button" onClick={() => void refetch()}>
              새로고침
            </button>
          </div>
        </header>

        {lastSyncResult && (
          <section className={`toss-sync-report ${lastSyncResult.fixedCount > 0 ? 'has-fix' : ''}`}>
            <div className="toss-sync-report-header">
              <h3>최근 동기화 결과</h3>
              <span className="toss-sync-report-count">수정 {lastSyncResult.fixedCount}건</span>
            </div>
            <p className="toss-sync-report-message">{lastSyncResult.message}</p>
            {lastSyncResult.actions.length > 0 && (
              <div className="toss-sync-action-list">
                {lastSyncResult.actions.slice(0, 6).map((action, index) => (
                  <div
                    key={`${action.market}-${action.strategy}-${index}`}
                    className="toss-sync-action-item"
                  >
                    <strong>{action.market}</strong> ({action.strategy}) - {action.reason}
                  </div>
                ))}
                {lastSyncResult.actions.length > 6 && (
                  <div className="toss-sync-action-item muted">
                    외 {lastSyncResult.actions.length - 6}건
                  </div>
                )}
              </div>
            )}
          </section>
        )}

        {systemControlExpanded && (
          <div className="system-control-panel">
            <div className="system-control-grid">
              <div className="control-group">
                <h3 className="control-group-title">🤖 AI 분석</h3>
                <div className="control-buttons">
                  <button
                    className="control-btn control-btn-primary"
                    type="button"
                    onClick={() => void executeSystemAction(
                      'optimizer',
                      () => systemControlApi.runOptimizer(),
                      {
                        confirm: {
                          title: '전체 거래 분석 실행',
                          description: '거래 내역을 분석해 전략 파라미터 개선안을 생성합니다.',
                          confirmLabel: '분석 시작',
                        },
                      }
                    )}
                    disabled={isActionExecuting('optimizer')}
                    title="AI가 거래 기록을 분석하여 최적의 전략 파라미터를 제안합니다"
                  >
                    {isActionExecuting('optimizer') ? '분석 중...' : '전체 거래 분석'}
                  </button>
                </div>
              </div>

              <div className="control-group">
                <h3 className="control-group-title">📊 전략 분석</h3>
                <div className="control-buttons">
                  <button
                    className="control-btn"
                    type="button"
                    onClick={() => void executeSystemAction('vs-reflection', () => systemControlApi.runVolumeSurgeReflection())}
                    disabled={isActionExecuting('vs-reflection')}
                    title="거래량이 급증하는 종목 단타 거래의 성과를 분석합니다"
                  >
                    {isActionExecuting('vs-reflection') ? '분석 중...' : '거래량 급등 분석'}
                  </button>
                  <button
                    className="control-btn"
                    type="button"
                    onClick={() => void executeSystemAction('ms-reflection', () => systemControlApi.runMemeScalperReflection())}
                    disabled={isActionExecuting('ms-reflection')}
                    title="밈 코인 단타 거래의 성과를 분석합니다"
                  >
                    {isActionExecuting('ms-reflection') ? '분석 중...' : '단타 매매 분석'}
                  </button>
                </div>
              </div>

              <div className="control-group">
                <h3 className="control-group-title">🔄 전략 재시작</h3>
                <div className="control-buttons">
                  <button
                    className="control-btn control-btn-warning"
                    type="button"
                    onClick={() => void executeSystemAction(
                      'vs-reset',
                      () => systemControlApi.resetVolumeSurgeCircuitBreaker(),
                      {
                        confirm: {
                          title: '거래량 급등 전략 재시작',
                          description: '연속 손실로 정지된 전략을 즉시 다시 활성화합니다.',
                          confirmLabel: '재시작',
                        },
                      }
                    )}
                    disabled={isActionExecuting('vs-reset')}
                    title="연속 손실로 정지된 거래량 급등 전략을 다시 시작합니다"
                  >
                    {isActionExecuting('vs-reset') ? '재시작 중...' : '거래량 급등 재시작'}
                  </button>

                  <button
                    className="control-btn control-btn-warning"
                    type="button"
                    onClick={() => void executeSystemAction(
                      'ms-reset',
                      () => systemControlApi.resetMemeScalperCircuitBreaker(),
                      {
                        confirm: {
                          title: '단타 매매 전략 재시작',
                          description: '연속 손실로 정지된 전략을 즉시 다시 활성화합니다.',
                          confirmLabel: '재시작',
                        },
                      }
                    )}
                    disabled={isActionExecuting('ms-reset')}
                    title="연속 손실로 정지된 단타 매매 전략을 다시 시작합니다"
                  >
                    {isActionExecuting('ms-reset') ? '재시작 중...' : '단타 매매 재시작'}
                  </button>
                </div>
              </div>

              <div className="control-group">
                <h3 className="control-group-title">📡 데이터 갱신</h3>
                <div className="control-buttons">
                  <button
                    className="control-btn"
                    type="button"
                    onClick={() => void executeSystemAction('exchange-rate', () => systemControlApi.refreshExchangeRate())}
                    disabled={isActionExecuting('exchange-rate')}
                    title="원-달러 환율 정보를 최신으로 갱신합니다"
                  >
                    {isActionExecuting('exchange-rate') ? '갱신 중...' : '환율 정보 갱신'}
                  </button>
                  <button
                    className="control-btn"
                    type="button"
                    onClick={() => void handleFundingScan()}
                    disabled={isActionExecuting('funding-scan')}
                    title="펀딩 비율 차익거래 기회를 스캔합니다"
                  >
                    {isActionExecuting('funding-scan') ? '스캔 중...' : '차익거래 기회 스캔'}
                  </button>
                  <button
                    className="control-btn control-btn-secondary"
                    type="button"
                    onClick={() => void executeSystemAction('sync-orders', () => dashboardApi.syncOrders())}
                    disabled={isActionExecuting('sync-orders')}
                    title="체결되지 않은 주문 내역을 확인합니다"
                  >
                    {isActionExecuting('sync-orders') ? '확인 중...' : '미체결 주문 확인'}
                  </button>
                  <button
                    className="control-btn control-btn-secondary"
                    type="button"
                    onClick={() => void executeSystemAction('cache-refresh', () => systemControlApi.refreshCache())}
                    disabled={isActionExecuting('cache-refresh')}
                    title="설정 캐시를 초기화합니다"
                  >
                    {isActionExecuting('cache-refresh') ? '갱신 중...' : '캐시 초기화'}
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

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
                        .reduce((sum, coinAsset) => sum + coinAsset.value, 0)
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

        <section className="toss-risk-overview">
          <div className={`toss-risk-metric ${riskSummary.highRiskCount > 0 ? 'critical' : ''}`}>
            <span className="metric-label">고위험 포지션</span>
            <strong className="metric-value">{riskSummary.highRiskCount} / {riskSummary.openCount}</strong>
          </div>
          <div className={`toss-risk-metric ${riskSummary.stopLossBreachCount > 0 ? 'critical' : ''}`}>
            <span className="metric-label">손절선 이탈</span>
            <strong className="metric-value">{riskSummary.stopLossBreachCount}건</strong>
          </div>
          <div className="toss-risk-metric">
            <span className="metric-label">손실 노출 금액</span>
            <strong className="metric-value">{riskSummary.losingExposure.toLocaleString('ko-KR')}원</strong>
          </div>
        </section>

        <div className="toss-main-grid">
          <div className="toss-left-column">
            {data.openPositions.length > 0 && (
              <div className="toss-card">
                <div className="toss-card-header">
                  <div className="toss-card-title-section">
                    <div className="toss-card-icon">📊</div>
                    <h2 className="toss-card-title">보유 중인 포지션</h2>
                  </div>
                  <span className="toss-card-badge">{filteredPositions.length}개</span>
                </div>

                <div className="toss-toolbar">
                  <input
                    className="toss-filter-input"
                    value={positionSearch}
                    placeholder="마켓/전략 검색"
                    onChange={(event) => setPositionSearch(event.target.value)}
                  />
                  <select
                    className="toss-filter-select"
                    value={positionFilter}
                    onChange={(event) => setPositionFilter(event.target.value as PositionFilter)}
                  >
                    <option value="ALL">전체</option>
                    <option value="HIGH_RISK">고위험</option>
                    <option value="LOSING">손실</option>
                    <option value="PROFIT">수익</option>
                  </select>
                  <select
                    className="toss-filter-select"
                    value={positionSort}
                    onChange={(event) => setPositionSort(event.target.value as PositionSort)}
                  >
                    <option value="RISK_DESC">위험도순</option>
                    <option value="PNL_ASC">손익 오름차순</option>
                    <option value="PNL_DESC">손익 내림차순</option>
                    <option value="VALUE_DESC">포지션 규모순</option>
                  </select>
                </div>

                {filteredPositions.length === 0 ? (
                  <div className="toss-empty-state compact">
                    <p className="toss-empty-text">조건에 맞는 포지션이 없습니다.</p>
                  </div>
                ) : (
                  <div className="toss-position-list">
                    {filteredPositions.map((position) => {
                      const positive = position.pnl >= 0;
                      const highRisk = isHighRiskPosition(position);
                      const stopLossBreached = isStopLossBreached(position);
                      const actionName = `manual-sell:${position.market}:${position.strategy}`;

                      return (
                        <div
                          key={`${position.market}-${position.strategy}-${position.entryTime}`}
                          className={`toss-position-item ${highRisk ? 'toss-position-danger' : ''}`}
                        >
                          <div className="toss-position-header">
                            <span className="toss-market-symbol">{position.market}</span>
                            <span className={`toss-strategy-badge ${
                              position.strategy === 'Meme Scalper' ? 'toss-strategy-meme' :
                              position.strategy === 'Volume Surge' ? 'toss-strategy-volume' :
                              'toss-strategy-dca'
                            }`}>
                              {position.strategy}
                            </span>
                            {highRisk && (
                              <span className={`toss-risk-chip ${stopLossBreached ? 'danger' : 'warning'}`}>
                                {stopLossBreached ? '손절선 이탈' : '손실 주의'}
                              </span>
                            )}
                          </div>

                          <div className="toss-position-details">
                            <div className="toss-price-info">
                              <div className="toss-price-group">
                                <span className="toss-price-label">매수가</span>
                                <span className="toss-price-value">{position.entryPrice.toLocaleString()}원</span>
                              </div>
                              <div className="toss-price-group">
                                <span className="toss-price-label">현재가</span>
                                <span className={`toss-price-value ${positive ? 'toss-price-up' : 'toss-price-down'}`}>
                                  {position.currentPrice.toLocaleString()}원
                                </span>
                              </div>
                              <div className="toss-price-group">
                                <span className="toss-price-label">수량</span>
                                <span className="toss-price-value">{position.quantity.toFixed(4)}</span>
                              </div>
                              <div className="toss-price-group">
                                <span className="toss-price-label">손절/익절</span>
                                <span className="toss-price-value">
                                  {position.stopLossPrice > 0 ? position.stopLossPrice.toLocaleString() : '-'} /
                                  {' '}
                                  {position.takeProfitPrice > 0 ? position.takeProfitPrice.toLocaleString() : '-'}
                                </span>
                              </div>
                            </div>

                            <div className="toss-position-action">
                              <div className={`toss-pnl-box ${positive ? 'toss-pnl-positive' : 'toss-pnl-negative'} ${highRisk ? 'toss-pnl-danger' : ''}`}>
                                <span className="toss-pnl-amount">
                                  {positive ? '+' : ''}{position.pnl.toLocaleString()}원
                                </span>
                                <span className="toss-pnl-percent">
                                  ({positive ? '+' : ''}{position.pnlPercent.toFixed(2)}%)
                                </span>
                              </div>
                              <button
                                type="button"
                                onClick={() => void handleManualSell(position.market, position.strategy)}
                                className="toss-sell-btn"
                                disabled={hasExecutingAction && !isActionExecuting(actionName)}
                              >
                                {isActionExecuting(actionName) ? '매도 중...' : '매도하기'}
                              </button>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            )}

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
                <div className={`toss-stat-highlight ${isPositive ? 'toss-stat-positive' : 'toss-stat-negative'}`}>
                  <div className="toss-stat-label">수익률</div>
                  <div className={`toss-stat-value ${isPositive ? 'toss-green' : 'toss-red'}`}>
                    {isPositive ? '+' : ''}{data.todayStats.roi.toFixed(2)}%
                  </div>
                  <div className="toss-stat-sublabel">투자금 {data.todayStats.totalInvested.toLocaleString()}원</div>
                </div>
              </div>
            </div>
          </div>

          <div className="toss-right-column">
            <div className="toss-card">
              <div className="toss-card-header">
                <div className="toss-card-title-section">
                  <div className="toss-card-icon">📋</div>
                  <h2 className="toss-card-title">체결 내역</h2>
                </div>
                <div className="toss-date-selector">
                  <button
                    type="button"
                    onClick={() => handleDateChange(-1)}
                    disabled={!canMovePrevDate}
                    className="toss-date-btn"
                    aria-label="이전 날짜"
                  >
                    ◀
                  </button>
                  <span className="toss-date-display">{data.currentDateStr}</span>
                  <button
                    type="button"
                    onClick={() => handleDateChange(1)}
                    disabled={!canMoveNextDate}
                    className="toss-date-btn"
                    aria-label="다음 날짜"
                  >
                    ▶
                  </button>
                </div>
              </div>

              <div className="toss-toolbar compact">
                <select
                  className="toss-filter-select"
                  value={tradeFilter}
                  onChange={(event) => setTradeFilter(event.target.value as TradeFilter)}
                >
                  <option value="ALL">전체</option>
                  <option value="PROFIT">수익 거래</option>
                  <option value="LOSS">손실 거래</option>
                </select>
              </div>

              {filteredTrades.length === 0 ? (
                <div className="toss-empty-state">
                  <div className="toss-empty-icon">📊</div>
                  <p className="toss-empty-text">체결된 거래가 없어요</p>
                </div>
              ) : (
                <div className="tv-trade-list">
                  {filteredTrades.map((trade, index) => (
                    <TradeRow key={`${trade.market}-${trade.entryTime}-${index}`} trade={trade} />
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>

        {fundingExpanded && fundingStatus && (
          <div className="funding-arbitrage-panel">
            <div className="funding-header">
              <h2 className="funding-title">💰 펀딩 비율 차익거래</h2>
              <button
                className={`funding-toggle-btn ${fundingStatus.autoTradingEnabled ? 'enabled' : 'disabled'}`}
                type="button"
                onClick={() => void handleFundingToggle()}
                disabled={isActionExecuting('funding-toggle')}
              >
                {isActionExecuting('funding-toggle')
                  ? '변경 중...'
                  : fundingStatus.autoTradingEnabled ? '자동 ON' : '자동 OFF'}
              </button>
            </div>

            <div className="funding-status-grid">
              <div className="funding-status-item">
                <span className="funding-label">상태</span>
                <span className={`funding-value ${fundingStatus.enabled ? 'status-active' : 'status-inactive'}`}>
                  {fundingStatus.enabled ? '활성' : '비활성'}
                </span>
              </div>
              <div className="funding-status-item">
                <span className="funding-label">오픈 포지션</span>
                <span className="funding-value">{fundingStatus.openPositionsCount}개</span>
              </div>
              <div className="funding-status-item">
                <span className="funding-label">총 PnL</span>
                <span className={`funding-value ${fundingStatus.totalPnl >= 0 ? 'pnl-profit' : 'pnl-loss'}`}>
                  {fundingStatus.totalPnl.toLocaleString()}원
                </span>
              </div>
            </div>

            <div className="funding-controls">
              <button
                className="funding-control-btn"
                type="button"
                onClick={() => void handleFundingScan()}
                disabled={isActionExecuting('funding-scan')}
              >
                {isActionExecuting('funding-scan') ? '스캔 중...' : '📊 기회 스캔'}
              </button>
            </div>

            {fundingStatus.openPositionsCount > 0 && (
              <div className="funding-positions-section">
                <h3 className="funding-section-title">진입 포지션 ({fundingStatus.openPositionsCount})</h3>
                <div className="funding-positions-list">
                  {fundingStatus.openPositions.map((position) => (
                    <div key={position.id} className="funding-position-card">
                      <div className="fp-header">
                        <span className="fp-symbol">{position.symbol}</span>
                        <span className={`fp-status ${position.status}`}>{position.status}</span>
                      </div>
                      <div className="fp-details">
                        <div className="fp-detail">
                          <span className="fp-label">진입가:</span>
                          <span className="fp-value">
                            현물 {position.spotPrice?.toLocaleString() ?? '-'} / 선물 {position.perpPrice?.toLocaleString() ?? '-'}
                          </span>
                        </div>
                        <div className="fp-detail">
                          <span className="fp-label">펀딩 비율:</span>
                          <span className="fp-value">
                            {position.fundingRate !== null ? `${position.fundingRate.toFixed(6)}%` : '-'}
                          </span>
                        </div>
                        <div className="fp-detail">
                          <span className="fp-label">수령 펀딩:</span>
                          <span className="fp-value">{position.totalFundingReceived.toLocaleString()}원</span>
                        </div>
                        {position.netPnl !== null && (
                          <div className="fp-detail">
                            <span className="fp-label">PnL:</span>
                            <span className={`fp-value ${position.netPnl >= 0 ? 'pnl-profit' : 'pnl-loss'}`}>
                              {position.netPnl.toLocaleString()}원
                            </span>
                          </div>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {fundingScanResult && fundingScanResult.opportunities.length > 0 && (
              <div className="funding-opportunities-section">
                <h3 className="funding-section-title">현재 기회 ({fundingScanResult.opportunities.length})</h3>
                <div className="funding-opportunities-list">
                  {fundingScanResult.opportunities.map((opportunity, index) => (
                    <div key={`${opportunity.symbol}-${index}`} className={`funding-opp-card ${opportunity.isRecommendedEntry ? 'recommended' : ''}`}>
                      <div className="fo-header">
                        <span className="fo-symbol">{opportunity.symbol}</span>
                        <span className="fo-rate">{opportunity.fundingRate}</span>
                        <span className="fo-annualized">{opportunity.annualizedRate}</span>
                      </div>
                      <div className="fo-details">
                        <span className="fo-label">{opportunity.minutesUntilFunding}분 후 펀딩</span>
                        <span className="fo-label">현물 {opportunity.markPrice.toLocaleString()}</span>
                        <span className="fo-label">선물 {opportunity.indexPrice.toLocaleString()}</span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {feedbackUi}
    </div>
  );
}

function TradeRow({ trade }: { trade: ClosedTradeInfo }) {
  const isTradeProfit = trade.pnlAmount >= 0;
  const exitReason = exitReasonLabels[trade.exitReason] || exitReasonLabels.UNKNOWN;

  return (
    <div className={`tv-trade-row ${isTradeProfit ? 'tv-profit' : 'tv-loss'}`}>
      <div className={`tv-indicator ${isTradeProfit ? 'tv-indicator-profit' : 'tv-indicator-loss'}`} />

      <div className="tv-trade-content">
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
            <span className={`tv-exit-reason ${exitReason.className}`} data-reason={exitReason.label}>{exitReason.label}</span>
            <span className="tv-holding-time">{trade.holdingMinutes}m</span>
          </div>
        </div>

        <div className="tv-trade-details">
          <div className="tv-prices">
            <div className="tv-price-group">
              <span className="tv-price-label">IN</span>
              <span className="tv-price-value">{trade.entryPrice.toLocaleString()}</span>
            </div>
            <svg className="tv-arrow-icon" width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
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
}
