import { useEffect, useMemo, useState } from 'react';
import type {
  AutopilotLiveResponse,
  OrderLifecycleGroupSummary,
} from '../../api';
import type {
  AutopilotCandidateView,
  AutopilotState,
  AutopilotTimelineEvent,
} from '../../lib/autopilot/AutopilotOrchestrator';
import './AutopilotLiveDock.css';

type TimelineItem = {
  id: string;
  at: number;
  market?: string;
  type: string;
  level: 'INFO' | 'WARN' | 'ERROR';
  action: string;
  detail: string;
  screenshotId?: string;
  source: 'LOCAL' | 'SERVER';
};

type OrderFeedFilter = 'ALL' | 'REQUESTED' | 'FILLED' | 'FAILED';
type StrategyCodeFilter = 'ALL' | 'AUTOPILOT' | 'MANUAL';
const PAGE_SIZE_OPTIONS = [30, 50, 100] as const;

const EMPTY_SUMMARY: OrderLifecycleGroupSummary = {
  buyRequested: 0,
  buyFilled: 0,
  sellRequested: 0,
  sellFilled: 0,
  pending: 0,
  cancelled: 0,
};

function formatTs(ts: number): string {
  return new Date(ts).toLocaleTimeString('ko-KR', {
    timeZone: 'Asia/Seoul',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function stageLabel(stage: string): string {
  switch (stage) {
    case 'RULE_PASS':
      return '규칙통과';
    case 'RULE_FAIL':
      return '규칙탈락';
    case 'SLOT_FULL':
      return '슬롯부족';
    case 'POSITION_OPEN':
      return '보유중';
    case 'WORKER_ACTIVE':
      return '워커실행중';
    case 'COOLDOWN':
      return '쿨다운';
    case 'LLM_REJECT':
      return 'LLM거절';
    case 'PLAYWRIGHT_WARN':
      return 'UI경고';
    case 'ENTERED':
      return '진입진행';
    default:
      return stage;
  }
}

function orderEventLabel(eventType: string): string {
  switch (eventType) {
    case 'BUY_REQUESTED':
      return '매수 요청';
    case 'BUY_FILLED':
      return '매수 체결';
    case 'SELL_REQUESTED':
      return '매도 요청';
    case 'SELL_FILLED':
      return '매도 체결';
    case 'CANCEL_REQUESTED':
      return '취소 요청';
    case 'CANCELLED':
      return '취소 완료';
    case 'FAILED':
      return '실패';
    default:
      return eventType;
  }
}

function strategyGroupLabel(strategyGroup: string): string {
  switch (strategyGroup) {
    case 'MANUAL':
      return '수동';
    case 'GUIDED':
      return 'Guided';
    case 'AUTOPILOT_MCP':
      return '오토파일럿(MCP)';
    case 'CORE_ENGINE':
      return '코어 엔진';
    default:
      return strategyGroup;
  }
}

function orderFeedMatchesFilter(eventType: string, filter: OrderFeedFilter): boolean {
  if (filter === 'ALL') return true;
  if (filter === 'REQUESTED') {
    return eventType.endsWith('_REQUESTED') || eventType === 'CANCEL_REQUESTED';
  }
  if (filter === 'FILLED') {
    return eventType.endsWith('_FILLED');
  }
  return eventType === 'CANCELLED' || eventType === 'FAILED';
}

function orderFeedMatchesStrategyCode(strategyCode: string | null | undefined, filter: StrategyCodeFilter): boolean {
  if (filter === 'ALL') return true;
  const code = (strategyCode || 'GUIDED_TRADING').toUpperCase();
  if (filter === 'AUTOPILOT') {
    return code.includes('AUTOPILOT');
  }
  return !code.includes('AUTOPILOT');
}

function toTimelineEvent(event: AutopilotTimelineEvent): TimelineItem {
  return {
    id: event.id,
    at: event.at,
    market: event.market,
    type: event.type,
    level: event.level,
    action: event.action,
    detail: event.detail,
    screenshotId: event.screenshotId,
    source: 'LOCAL',
  };
}

function normalizeServerCandidates(liveData: AutopilotLiveResponse | undefined): AutopilotCandidateView[] {
  if (!liveData) return [];
  const now = Date.now();
  return liveData.candidates.map((candidate) => ({
    market: candidate.market,
    koreanName: candidate.koreanName,
    recommendedEntryWinRate: candidate.recommendedEntryWinRate ?? null,
    marketEntryWinRate: candidate.marketEntryWinRate ?? null,
    stage: candidate.stage as AutopilotCandidateView['stage'],
    reason: candidate.reason,
    updatedAt: now,
  }));
}

interface AutopilotLiveDockProps {
  open: boolean;
  collapsed: boolean;
  onToggleCollapse: () => void;
  autopilotEnabled: boolean;
  autopilotState: AutopilotState;
  liveData?: AutopilotLiveResponse;
  loading?: boolean;
}

export function AutopilotLiveDock({
  open,
  collapsed,
  onToggleCollapse,
  autopilotEnabled,
  autopilotState,
  liveData,
  loading = false,
}: AutopilotLiveDockProps) {
  const [selectedGroup, setSelectedGroup] = useState('TOTAL');
  const [orderFeedFilter, setOrderFeedFilter] = useState<OrderFeedFilter>('FILLED');
  const [strategyCodeFilter, setStrategyCodeFilter] = useState<StrategyCodeFilter>('ALL');
  const [timelinePageSize, setTimelinePageSize] = useState<number>(30);
  const [orderFeedPageSize, setOrderFeedPageSize] = useState<number>(30);
  const [timelinePage, setTimelinePage] = useState(1);
  const [orderFeedPage, setOrderFeedPage] = useState(1);
  const screenshotById = useMemo(
    () => new Map(autopilotState.screenshots.map((shot) => [shot.id, shot])),
    [autopilotState.screenshots]
  );

  const timeline = useMemo(() => {
    const local = autopilotState.events.map(toTimelineEvent);
    return [...local]
      .sort((a, b) => b.at - a.at)
      .slice(0, 400);
  }, [autopilotState.events]);

  const orderFeed = useMemo(() => {
    const rows = liveData?.orderEvents ?? [];
    return rows
      .filter((event) => orderFeedMatchesFilter(event.eventType, orderFeedFilter))
      .filter((event) => orderFeedMatchesStrategyCode(event.strategyCode, strategyCodeFilter))
      .slice(0, 400);
  }, [liveData?.orderEvents, orderFeedFilter, strategyCodeFilter]);

  const timelinePageCount = useMemo(
    () => Math.max(1, Math.ceil(timeline.length / timelinePageSize)),
    [timeline.length, timelinePageSize]
  );
  const orderFeedPageCount = useMemo(
    () => Math.max(1, Math.ceil(orderFeed.length / orderFeedPageSize)),
    [orderFeed.length, orderFeedPageSize]
  );
  const pagedTimeline = useMemo(() => {
    const start = (timelinePage - 1) * timelinePageSize;
    return timeline.slice(start, start + timelinePageSize);
  }, [timeline, timelinePage, timelinePageSize]);
  const pagedOrderFeed = useMemo(() => {
    const start = (orderFeedPage - 1) * orderFeedPageSize;
    return orderFeed.slice(start, start + orderFeedPageSize);
  }, [orderFeed, orderFeedPage, orderFeedPageSize]);
  useEffect(() => {
    setOrderFeedPage(1);
  }, [orderFeedFilter, strategyCodeFilter]);
  useEffect(() => {
    setTimelinePage(1);
  }, [timelinePageSize]);
  useEffect(() => {
    setOrderFeedPage(1);
  }, [orderFeedPageSize]);
  useEffect(() => {
    if (timelinePage > timelinePageCount) {
      setTimelinePage(timelinePageCount);
    }
  }, [timelinePage, timelinePageCount]);
  useEffect(() => {
    if (orderFeedPage > orderFeedPageCount) {
      setOrderFeedPage(orderFeedPageCount);
    }
  }, [orderFeedPage, orderFeedPageCount]);

  const [selectedTimelineId, setSelectedTimelineId] = useState<string | null>(null);
  useEffect(() => {
    if (pagedTimeline.length === 0) {
      setSelectedTimelineId(null);
      return;
    }
    if (!selectedTimelineId || !pagedTimeline.some((item) => item.id === selectedTimelineId)) {
      setSelectedTimelineId(pagedTimeline[0].id);
    }
  }, [pagedTimeline, selectedTimelineId]);

  const selectedTimeline = pagedTimeline.find((item) => item.id === selectedTimelineId) ?? null;
  const selectedShot = selectedTimeline?.screenshotId
    ? screenshotById.get(selectedTimeline.screenshotId)
    : undefined;

  const groupTabs = useMemo(() => {
    const keys = Object.keys(liveData?.orderSummary.groups ?? {}).sort();
    return ['TOTAL', ...keys];
  }, [liveData?.orderSummary.groups]);

  const summary = useMemo(() => {
    if (!liveData) return EMPTY_SUMMARY;
    if (selectedGroup === 'TOTAL') return liveData.orderSummary.total;
    return liveData.orderSummary.groups[selectedGroup] ?? EMPTY_SUMMARY;
  }, [liveData, selectedGroup]);

  const localFlow = autopilotState.orderFlowLocal;
  const candidates = autopilotState.candidates.length > 0
    ? autopilotState.candidates
    : normalizeServerCandidates(liveData);
  const appliedThreshold = liveData?.appliedRecommendedWinRateThreshold
    ?? autopilotState.appliedRecommendedWinRateThreshold;
  const thresholdMode = liveData?.thresholdMode ?? autopilotState.thresholdMode;
  const thresholdLabel = thresholdMode === 'DYNAMIC_P70' ? '동적 P70(추천가)' : '고정(현재가)';

  if (!open) return null;

  return (
    <section className={`autopilot-live-dock ${collapsed ? 'collapsed' : ''}`}>
      <header className="autopilot-live-header">
        <div>
          <strong>오토파일럿 라이브 도크</strong>
          <span>{autopilotEnabled ? '실행 중' : '대기'}</span>
        </div>
        <button type="button" onClick={onToggleCollapse}>
          {collapsed ? '펼치기' : '접기'}
        </button>
      </header>

      {!collapsed && (
        <>
          <div className="autopilot-funnel-tabs">
            {groupTabs.map((group) => (
              <button
                key={group}
                type="button"
                className={group === selectedGroup ? 'active' : ''}
                onClick={() => setSelectedGroup(group)}
              >
                {group}
              </button>
            ))}
          </div>

          <div className="autopilot-funnel-grid">
            <div>
              <label>매수 요청</label>
              <strong>{summary.buyRequested}</strong>
            </div>
            <div>
              <label>매수 체결</label>
              <strong>{summary.buyFilled}</strong>
            </div>
            <div>
              <label>매도 요청</label>
              <strong>{summary.sellRequested}</strong>
            </div>
            <div>
              <label>매도 체결</label>
              <strong>{summary.sellFilled}</strong>
            </div>
            <div>
              <label>대기</label>
              <strong>{summary.pending}</strong>
            </div>
            <div>
              <label>취소/실패</label>
              <strong>{summary.cancelled}</strong>
            </div>
          </div>

          <div className="autopilot-local-funnel">
            로컬 추적: {localFlow.buyRequested} → {localFlow.buyFilled} → {localFlow.sellRequested} → {localFlow.sellFilled}
            {` (대기 ${localFlow.pending}, 취소 ${localFlow.cancelled})`}
          </div>
          <div className="autopilot-threshold-meta">
            임계값 모드: {thresholdLabel} · 적용 기준: {appliedThreshold.toFixed(1)}%
          </div>

          <div className="autopilot-live-body">
            <div className="dock-column candidates">
              <div className="column-title">후보 상위 {candidates.length}</div>
              <div className="candidate-list">
                {candidates.map((candidate) => (
                  <div key={candidate.market} className="candidate-item">
                    <div className="candidate-top">
                      <strong>{candidate.market}</strong>
                      <span className={`stage stage-${candidate.stage.toLowerCase()}`}>{stageLabel(candidate.stage)}</span>
                    </div>
                    <div className="candidate-metrics">
                      추천 {candidate.recommendedEntryWinRate?.toFixed(1) ?? '-'}% · 현재 {candidate.marketEntryWinRate?.toFixed(1) ?? '-'}%
                    </div>
                    <p>{candidate.reason}</p>
                  </div>
                ))}
                {candidates.length === 0 && <div className="empty">후보 없음</div>}
              </div>
            </div>

            <div className="dock-column timeline">
              <div className="column-title with-pagination">
                <span>실시간 액션 타임라인</span>
                <div className="timeline-pagination">
                  <label className="page-size-select">
                    <span>개수</span>
                    <select
                      value={timelinePageSize}
                      onChange={(event) => setTimelinePageSize(Number(event.target.value))}
                    >
                      {PAGE_SIZE_OPTIONS.map((size) => (
                        <option key={size} value={size}>{size}</option>
                      ))}
                    </select>
                  </label>
                  <button
                    type="button"
                    onClick={() => setTimelinePage((prev) => Math.max(1, prev - 1))}
                    disabled={timelinePage <= 1}
                  >
                    이전
                  </button>
                  <span>{timelinePage} / {timelinePageCount}</span>
                  <button
                    type="button"
                    onClick={() => setTimelinePage((prev) => Math.min(timelinePageCount, prev + 1))}
                    disabled={timelinePage >= timelinePageCount}
                  >
                    다음
                  </button>
                </div>
              </div>
              <div className="timeline-layout">
                <div className="timeline-list">
                  {pagedTimeline.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      className={`timeline-item ${selectedTimelineId === item.id ? 'active' : ''}`}
                      onClick={() => setSelectedTimelineId(item.id)}
                    >
                      <div className="timeline-head">
                        <span>{item.market || item.type}</span>
                        <span>{formatTs(item.at)}</span>
                      </div>
                      <div className="timeline-action">{item.action}</div>
                      <p>{item.detail}</p>
                    </button>
                  ))}
                  {pagedTimeline.length === 0 && (
                    <div className="empty">{loading ? '로딩 중...' : '이벤트 없음'}</div>
                  )}
                </div>
                <div className="timeline-detail">
                  {selectedTimeline ? (
                    <>
                      <div className="detail-head">
                        <strong>{selectedTimeline.action}</strong>
                        <span>{selectedTimeline.level}</span>
                      </div>
                      <p>{selectedTimeline.detail}</p>
                      {selectedShot ? (
                        <img src={selectedShot.src} alt="playwright evidence" loading="lazy" />
                      ) : (
                        <div className="empty">스크린샷 없음</div>
                      )}
                    </>
                  ) : (
                    <div className="empty">이벤트를 선택하세요</div>
                  )}
                </div>
              </div>
            </div>

            <div className="dock-column workers">
              <div className="column-title">워커 상태</div>
              <div className="worker-list">
                {autopilotState.workers.map((worker) => (
                  <div key={worker.market} className="worker-card">
                    <div className="worker-top">
                      <strong>{worker.market}</strong>
                      <span>{worker.status}</span>
                    </div>
                    <p>{worker.note}</p>
                    <small>업데이트 {formatTs(worker.updatedAt)}</small>
                  </div>
                ))}
                {autopilotState.workers.length === 0 && <div className="empty">실행 중 워커 없음</div>}
              </div>
            </div>
          </div>

          <div className="autopilot-order-feed">
            <div className="autopilot-order-feed-head">
              <div className="autopilot-order-feed-title">
                <strong>전체 코인 체결 내역</strong>
                <span>기본 필터는 체결이며, 필요 시 요청/취소·실패까지 확인할 수 있습니다.</span>
              </div>
              <div className="autopilot-order-feed-filters">
                <label className="page-size-select">
                  <span>개수</span>
                  <select
                    value={orderFeedPageSize}
                    onChange={(event) => setOrderFeedPageSize(Number(event.target.value))}
                  >
                    {PAGE_SIZE_OPTIONS.map((size) => (
                      <option key={size} value={size}>{size}</option>
                    ))}
                  </select>
                </label>
                {(['ALL', 'REQUESTED', 'FILLED', 'FAILED'] as const).map((filter) => (
                  <button
                    key={filter}
                    type="button"
                    className={orderFeedFilter === filter ? 'active' : ''}
                    onClick={() => setOrderFeedFilter(filter)}
                  >
                    {filter === 'ALL' ? '전체' : filter === 'REQUESTED' ? '요청' : filter === 'FILLED' ? '체결' : '취소/실패'}
                  </button>
                ))}
              </div>
            </div>
            {liveData?.decisionStats && (
              <div className="autopilot-order-feed-decision-stats">
                <span>RULE_PASS {liveData.decisionStats.rulePass}</span>
                <span>RULE_FAIL {liveData.decisionStats.ruleFail}</span>
                <span>LLM_REJECT {liveData.decisionStats.llmReject}</span>
                <span>ENTERED {liveData.decisionStats.entered}</span>
                <span>PENDING_TIMEOUT {liveData.decisionStats.pendingTimeout}</span>
              </div>
            )}
            <div className="autopilot-order-feed-strategy-tabs">
              {([
                ['ALL', '전체'],
                ['AUTOPILOT', '오토파일럿'],
                ['MANUAL', '수동/기타'],
              ] as const).map(([filter, label]) => (
                <button
                  key={filter}
                  type="button"
                  className={strategyCodeFilter === filter ? 'active' : ''}
                  onClick={() => setStrategyCodeFilter(filter)}
                >
                  {label}
                </button>
              ))}
            </div>

            <div className="autopilot-order-feed-list">
              {pagedOrderFeed.map((event) => (
                <div key={event.id} className={`order-feed-item event-${event.eventType.toLowerCase()}`}>
                  <div className="order-feed-top">
                    <div className="order-feed-left">
                      <strong>{event.market}</strong>
                      <span>{orderEventLabel(event.eventType)}</span>
                    </div>
                    <div className="order-feed-right">
                      <span>{formatTs(new Date(event.createdAt).getTime())}</span>
                    </div>
                  </div>
                  <div className="order-feed-meta">
                    <span className="engine-chip">
                      엔진: {strategyGroupLabel(event.strategyGroup)}
                      {event.strategyCode ? ` (${event.strategyCode})` : ''}
                    </span>
                    {event.side && <span>방향: {event.side}</span>}
                    {event.orderId && <span>주문ID: {event.orderId}</span>}
                    {event.price != null && <span>가격: {event.price.toLocaleString('ko-KR')}</span>}
                    {event.quantity != null && <span>수량: {event.quantity}</span>}
                  </div>
                  {event.message && <p>{event.message}</p>}
                </div>
              ))}
              {pagedOrderFeed.length === 0 && (
                <div className="empty">{loading ? '주문 이력 로딩 중...' : '주문/체결 이력이 없습니다.'}</div>
              )}
            </div>
            {orderFeedPageCount > 1 && (
              <div className="order-feed-pagination">
                <button
                  type="button"
                  onClick={() => setOrderFeedPage((prev) => Math.max(1, prev - 1))}
                  disabled={orderFeedPage <= 1}
                >
                  이전
                </button>
                <span>{orderFeedPage} / {orderFeedPageCount}</span>
                <button
                  type="button"
                  onClick={() => setOrderFeedPage((prev) => Math.min(orderFeedPageCount, prev + 1))}
                  disabled={orderFeedPage >= orderFeedPageCount}
                >
                  다음
                </button>
              </div>
            )}
          </div>
        </>
      )}
    </section>
  );
}
