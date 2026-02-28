import { useMemo } from 'react';
import type { AutopilotLiveResponse, OrderLifecycleEvent, OrderLifecycleGroupSummary } from '../../api';
import type { AutopilotState } from '../../lib/autopilot/AutopilotOrchestrator';
import './InvestmentLiveDock.css';

type InvestmentTimelineRow = {
  id: string;
  at: number;
  engine: 'SWING' | 'POSITION';
  market?: string;
  level: 'INFO' | 'WARN' | 'ERROR';
  action: string;
  detail: string;
};

interface InvestmentLiveDockProps {
  open: boolean;
  collapsed: boolean;
  onToggleCollapse: () => void;
  swingEnabled: boolean;
  positionEnabled: boolean;
  swingState: AutopilotState;
  positionState: AutopilotState;
  swingLiveData?: AutopilotLiveResponse;
  positionLiveData?: AutopilotLiveResponse;
  loading?: boolean;
}

function formatTs(ts: number): string {
  return new Date(ts).toLocaleTimeString('ko-KR', {
    timeZone: 'Asia/Seoul',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function normalizeOrderRows(
  rows: OrderLifecycleEvent[] | undefined,
  engine: 'SWING' | 'POSITION'
): Array<OrderLifecycleEvent & { engine: 'SWING' | 'POSITION' }> {
  if (!rows || rows.length === 0) return [];
  return rows.map((row) => ({ ...row, engine }));
}

function emptyFunnel(): OrderLifecycleGroupSummary {
  return {
    buyRequested: 0,
    buyFilled: 0,
    sellRequested: 0,
    sellFilled: 0,
    pending: 0,
    cancelled: 0,
  };
}

function sumFunnel(left: OrderLifecycleGroupSummary, right: OrderLifecycleGroupSummary): OrderLifecycleGroupSummary {
  return {
    buyRequested: left.buyRequested + right.buyRequested,
    buyFilled: left.buyFilled + right.buyFilled,
    sellRequested: left.sellRequested + right.sellRequested,
    sellFilled: left.sellFilled + right.sellFilled,
    pending: left.pending + right.pending,
    cancelled: left.cancelled + right.cancelled,
  };
}

export function InvestmentLiveDock({
  open,
  collapsed,
  onToggleCollapse,
  swingEnabled,
  positionEnabled,
  swingState,
  positionState,
  swingLiveData,
  positionLiveData,
  loading = false,
}: InvestmentLiveDockProps) {
  const swingFunnel = swingLiveData?.orderSummary?.total ?? emptyFunnel();
  const positionFunnel = positionLiveData?.orderSummary?.total ?? emptyFunnel();
  const combinedFunnel = sumFunnel(swingFunnel, positionFunnel);

  const timelineRows = useMemo<InvestmentTimelineRow[]>(() => {
    const swingRows: InvestmentTimelineRow[] = swingState.events.map((event) => ({
      id: `swing-${event.id}`,
      at: event.at,
      engine: 'SWING',
      market: event.market,
      level: event.level,
      action: event.action,
      detail: event.detail,
    }));
    const positionRows: InvestmentTimelineRow[] = positionState.events.map((event) => ({
      id: `position-${event.id}`,
      at: event.at,
      engine: 'POSITION',
      market: event.market,
      level: event.level,
      action: event.action,
      detail: event.detail,
    }));
    return [...swingRows, ...positionRows]
      .sort((a, b) => b.at - a.at)
      .slice(0, 200);
  }, [positionState.events, swingState.events]);

  const orderRows = useMemo(() => {
    return [
      ...normalizeOrderRows(swingLiveData?.orderEvents, 'SWING'),
      ...normalizeOrderRows(positionLiveData?.orderEvents, 'POSITION'),
    ]
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
      .slice(0, 160);
  }, [positionLiveData?.orderEvents, swingLiveData?.orderEvents]);

  if (!open) return null;

  return (
    <section className={`investment-live-dock ${collapsed ? 'collapsed' : ''}`}>
      <div className="investment-live-header">
        <div>
          <strong>투자 시스템 라이브 도크</strong>
          <span>
            SWING {swingEnabled ? 'ON' : 'OFF'} · POSITION {positionEnabled ? 'ON' : 'OFF'}
          </span>
        </div>
        <button type="button" onClick={onToggleCollapse}>
          {collapsed ? '열기' : '접기'}
        </button>
      </div>

      {!collapsed && (
        <>
          <div className="investment-live-summary">
            <article className="engine-card swing">
              <h4>SWING</h4>
              <div>워커 {swingState.workers.length}개</div>
              <div>후보 {swingState.candidates.length}개</div>
              <div>
                AUTO/BORDER/RULE {swingState.decisionBreakdown.autoPass}/
                {swingState.decisionBreakdown.borderline}/{swingState.decisionBreakdown.ruleFail}
              </div>
              <div className="funnel-row">
                퍼널 {swingFunnel.buyRequested}→{swingFunnel.buyFilled}→{swingFunnel.sellRequested}→{swingFunnel.sellFilled}
              </div>
            </article>
            <article className="engine-card position">
              <h4>POSITION</h4>
              <div>워커 {positionState.workers.length}개</div>
              <div>후보 {positionState.candidates.length}개</div>
              <div>
                AUTO/BORDER/RULE {positionState.decisionBreakdown.autoPass}/
                {positionState.decisionBreakdown.borderline}/{positionState.decisionBreakdown.ruleFail}
              </div>
              <div className="funnel-row">
                퍼널 {positionFunnel.buyRequested}→{positionFunnel.buyFilled}→{positionFunnel.sellRequested}→{positionFunnel.sellFilled}
              </div>
            </article>
            <article className="engine-card combined">
              <h4>통합 퍼널</h4>
              <div>
                {combinedFunnel.buyRequested} → {combinedFunnel.buyFilled} → {combinedFunnel.sellRequested} → {combinedFunnel.sellFilled}
              </div>
              <div>대기 {combinedFunnel.pending} · 취소 {combinedFunnel.cancelled}</div>
            </article>
          </div>

          <div className="investment-live-body">
            <div className="investment-col">
              <div className="col-title">통합 타임라인 ({timelineRows.length})</div>
              {timelineRows.length === 0 ? (
                <div className="empty">{loading ? '로딩 중...' : '이벤트 없음'}</div>
              ) : (
                <div className="timeline-list">
                  {timelineRows.map((row) => (
                    <div key={row.id} className="timeline-item">
                      <span className="time">{formatTs(row.at)}</span>
                      <span className={`engine ${row.engine.toLowerCase()}`}>{row.engine}</span>
                      <span className={`level ${row.level.toLowerCase()}`}>{row.level}</span>
                      <span className="action">{row.action}</span>
                      <span className="detail">
                        {row.market ? `[${row.market}] ` : ''}
                        {row.detail}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div className="investment-col">
              <div className="col-title">통합 주문 피드 ({orderRows.length})</div>
              {orderRows.length === 0 ? (
                <div className="empty">{loading ? '로딩 중...' : '주문 이벤트 없음'}</div>
              ) : (
                <div className="order-list">
                  {orderRows.map((row) => (
                    <div key={`${row.engine}-${row.id}`} className="order-item">
                      <span className="time">{formatTs(Date.parse(row.createdAt))}</span>
                      <span className={`engine ${row.engine.toLowerCase()}`}>{row.engine}</span>
                      <span className="event">{row.eventType}</span>
                      <span className="market">{row.market}</span>
                      <span className="msg">{row.message ?? row.strategyCode ?? '-'}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </>
      )}
    </section>
  );
}
