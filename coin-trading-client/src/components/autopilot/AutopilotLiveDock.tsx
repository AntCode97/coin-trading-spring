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
    case 'ALREADY_OPEN':
      return '기보유';
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

function fromServerOrderEvent(liveData: AutopilotLiveResponse | undefined): TimelineItem[] {
  if (!liveData) return [];
  return liveData.orderEvents.map((event) => ({
    id: `server-${event.id}`,
    at: new Date(event.createdAt).getTime(),
    market: event.market,
    type: 'ORDER',
    level: event.eventType === 'FAILED' ? 'ERROR' : 'INFO',
    action: event.eventType,
    detail: event.message || `${event.strategyGroup} ${event.eventType}`,
    source: 'SERVER',
  }));
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
  const screenshotById = useMemo(
    () => new Map(autopilotState.screenshots.map((shot) => [shot.id, shot])),
    [autopilotState.screenshots]
  );

  const timeline = useMemo(() => {
    const local = autopilotState.events.map(toTimelineEvent);
    const server = fromServerOrderEvent(liveData);
    return [...local, ...server]
      .sort((a, b) => b.at - a.at)
      .slice(0, 220);
  }, [autopilotState.events, liveData]);

  const [selectedTimelineId, setSelectedTimelineId] = useState<string | null>(null);
  useEffect(() => {
    if (timeline.length === 0) {
      setSelectedTimelineId(null);
      return;
    }
    if (!selectedTimelineId || !timeline.some((item) => item.id === selectedTimelineId)) {
      setSelectedTimelineId(timeline[0].id);
    }
  }, [timeline, selectedTimelineId]);

  const selectedTimeline = timeline.find((item) => item.id === selectedTimelineId) ?? null;
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
              <div className="column-title">실시간 액션 타임라인</div>
              <div className="timeline-layout">
                <div className="timeline-list">
                  {timeline.map((item) => (
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
                  {timeline.length === 0 && (
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
        </>
      )}
    </section>
  );
}
