import { useEffect, useMemo, useState } from 'react';
import type {
  AutopilotLiveResponse,
  AutopilotEventView,
  AutopilotOpportunityProfile,
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
  type: 'LLM' | 'WORKER' | 'ORDER' | 'PLAYWRIGHT' | 'SYSTEM' | 'CANDIDATE';
  level: 'INFO' | 'WARN' | 'ERROR';
  action: string;
  detail: string;
  screenshotId?: string;
  rawType?: string;
  source: 'LOCAL' | 'SERVER';
};
type TimelineSourceFilter = 'ALL' | 'LOCAL' | 'SERVER';
type TimelineLevelFilter = 'ALL' | 'INFO' | 'WARN' | 'ERROR';
type TimelineTypeFilter = 'ALL' | 'LLM' | 'WORKER' | 'ORDER' | 'PLAYWRIGHT' | 'SYSTEM' | 'CANDIDATE';

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

function clampPercent(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(100, Math.round(value)));
}

function formatScore(value: number | undefined | null): string {
  if (!Number.isFinite(value as number)) return '-';
  return `${Math.round(value as number)}점`;
}

function formatCrowdMetrics(candidate: AutopilotCandidateView): string | null {
  const crowd = candidate.crowdMetrics;
  if (!crowd) return null;
  return `flow ${crowd.flowScore.toFixed(1)} · spike ${crowd.priceSpike10sPercent.toFixed(2)}% · imbalance ${crowd.bidImbalance.toFixed(2)} · spread ${crowd.spreadPercent.toFixed(2)}%`;
}

function formatTs(ts: number): string {
  return new Date(ts).toLocaleTimeString('ko-KR', {
    timeZone: 'Asia/Seoul',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function formatRelativeAge(ts: number): string {
  const diffSec = Math.max(0, Math.floor((Date.now() - ts) / 1000));
  if (diffSec < 5) return '방금';
  if (diffSec < 60) return `${diffSec}초 전`;
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}분 전`;
  const diffHour = Math.floor(diffMin / 60);
  if (diffHour < 24) return `${diffHour}시간 전`;
  const diffDay = Math.floor(diffHour / 24);
  return `${diffDay}일 전`;
}

function stageLabel(stage: string): string {
  switch (stage) {
    case 'AUTO_PASS':
      return '자동통과';
    case 'BORDERLINE':
      return '경계';
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
    case 'TOKEN_BUDGET_SKIP':
      return '토큰스킵';
    case 'QUANT_FILTERED':
      return '정량필터';
    case 'RECHECK_SCHEDULED':
      return '재검토예약';
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
    rawType: event.type,
    source: 'LOCAL',
  };
}

function normalizeTimelineLevel(level: string): TimelineItem['level'] {
  if (level === 'WARN') return 'WARN';
  if (level === 'ERROR') return 'ERROR';
  return 'INFO';
}

function normalizeServerEventType(eventType: string): string {
  return eventType
    .trim()
    .toUpperCase()
    .replace(/[_\s]+/g, ' ')
    .trim() || 'EVENT';
}

function normalizeTimelineType(eventType: string): TimelineItem['type'] {
  const normalized = normalizeServerEventType(eventType);
  if (normalized.includes('PLAYWRIGHT') || normalized.includes('SCREENSHOT') || normalized.includes('BROWSER')) {
    return 'PLAYWRIGHT';
  }
  if (normalized.includes('LLM')) return 'LLM';
  if (normalized.includes('WORKER')) return 'WORKER';
  if (
    normalized.includes('CANDIDATE') ||
    normalized.includes('RULE') ||
    normalized.includes('BORDERLINE') ||
    normalized.includes('RECHECK') ||
    normalized.includes('TOKEN BUDGET') ||
    normalized.includes('QUANT')
  ) {
    return 'CANDIDATE';
  }
  if (
    normalized === 'FAILED' ||
    normalized.includes('ORDER') ||
    normalized.startsWith('BUY ') ||
    normalized.startsWith('SELL ') ||
    normalized.includes('FILLED') ||
    normalized.includes('REQUESTED') ||
    normalized.includes('CANCEL')
  ) {
    return 'ORDER';
  }
  return 'SYSTEM';
}

function timelineTypeLabel(type: TimelineTypeFilter | TimelineItem['type']): string {
  switch (type) {
    case 'LLM':
      return 'LLM';
    case 'WORKER':
      return '워커';
    case 'ORDER':
      return '주문';
    case 'PLAYWRIGHT':
      return '브라우저';
    case 'CANDIDATE':
      return '후보';
    case 'SYSTEM':
      return '시스템';
    case 'ALL':
      return '전체';
    default:
      return type;
  }
}

function timelineSourceLabel(source: TimelineSourceFilter | TimelineItem['source']): string {
  switch (source) {
    case 'LOCAL':
      return '로컬';
    case 'SERVER':
      return '서버';
    case 'ALL':
      return '전체';
    default:
      return source;
  }
}

function serverTimelineActionLabel(eventType: string): string {
  const token = eventType
    .trim()
    .toUpperCase()
    .replace(/\s+/g, '_');
  switch (token) {
    case 'WORKER_PRUNED':
      return '워커 정리';
    case 'LLM_REJECT':
      return 'LLM 거절';
    case 'PLAYWRIGHT_WARN':
      return 'UI 경고';
    case 'TOKEN_BUDGET_SKIP':
      return '토큰 예산 스킵';
    case 'RECHECK_SCHEDULED':
      return '재검토 예약';
    case 'AUTO_PASS':
      return '자동 통과';
    case 'RULE_FAIL':
      return '규칙 탈락';
    case 'RULE_PASS':
      return '규칙 통과';
    case 'BORDERLINE':
      return '경계 구간';
    default:
      if (
        token.endsWith('_REQUESTED') ||
        token.endsWith('_FILLED') ||
        token === 'CANCEL_REQUESTED' ||
        token === 'CANCELLED' ||
        token === 'FAILED'
      ) {
        return orderEventLabel(token);
      }
      return normalizeServerEventType(eventType);
  }
}

function toServerTimelineEvent(event: AutopilotEventView, index: number): TimelineItem {
  const parsed = Date.parse(event.createdAt);
  const at = Number.isFinite(parsed) ? parsed : Date.now();
  const rawType = normalizeServerEventType(event.eventType);
  return {
    id: `server-${at}-${index}`,
    at,
    market: event.market,
    type: normalizeTimelineType(event.eventType),
    level: normalizeTimelineLevel(event.level),
    action: serverTimelineActionLabel(event.eventType),
    detail: event.message,
    rawType,
    source: 'SERVER',
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
    expectancyPct: candidate.expectancyPct ?? null,
    score: candidate.score ?? null,
    riskRewardRatio: candidate.riskRewardRatio ?? null,
    entryGapPct: candidate.entryGapPct ?? null,
    opportunityProfile: candidate.opportunityProfile ?? liveData.opportunityProfile ?? 'CLASSIC',
    crowdMetrics: candidate.crowdMetrics ?? null,
    stage: candidate.stage as AutopilotCandidateView['stage'],
    reason: candidate.reason,
    updatedAt: now,
  }));
}

interface AutopilotLiveDockProps {
  autopilotEnabled: boolean;
  autopilotState: AutopilotState;
  liveData?: AutopilotLiveResponse;
  loading?: boolean;
  opportunityProfile?: AutopilotOpportunityProfile;
}

export function AutopilotLiveDock({
  autopilotEnabled,
  autopilotState,
  liveData,
  loading = false,
  opportunityProfile = 'CLASSIC',
}: AutopilotLiveDockProps) {
  const [selectedGroup, setSelectedGroup] = useState('TOTAL');
  const [orderFeedFilter, setOrderFeedFilter] = useState<OrderFeedFilter>('FILLED');
  const [strategyCodeFilter, setStrategyCodeFilter] = useState<StrategyCodeFilter>('ALL');
  const [timelinePageSize, setTimelinePageSize] = useState<number>(30);
  const [orderFeedPageSize, setOrderFeedPageSize] = useState<number>(30);
  const [timelinePage, setTimelinePage] = useState(1);
  const [orderFeedPage, setOrderFeedPage] = useState(1);
  const [timelineSourceFilter, setTimelineSourceFilter] = useState<TimelineSourceFilter>('ALL');
  const [timelineLevelFilter, setTimelineLevelFilter] = useState<TimelineLevelFilter>('ALL');
  const [timelineTypeFilter, setTimelineTypeFilter] = useState<TimelineTypeFilter>('ALL');
  const screenshotById = useMemo(
    () => new Map(autopilotState.screenshots.map((shot) => [shot.id, shot])),
    [autopilotState.screenshots]
  );

  const timelineAll = useMemo(() => {
    const local = autopilotState.events.map(toTimelineEvent);
    const server = (liveData?.autopilotEvents ?? []).map((event, index) => toServerTimelineEvent(event, index));
    return [...local, ...server].sort((a, b) => b.at - a.at);
  }, [autopilotState.events, liveData?.autopilotEvents]);

  const timeline = useMemo(() => {
    return timelineAll
      .filter((item) => {
        if (timelineSourceFilter !== 'ALL' && item.source !== timelineSourceFilter) {
          return false;
        }
        if (timelineLevelFilter !== 'ALL' && item.level !== timelineLevelFilter) {
          return false;
        }
        if (timelineTypeFilter !== 'ALL' && item.type !== timelineTypeFilter) {
          return false;
        }
        return true;
      })
      .slice(0, 400);
  }, [timelineAll, timelineSourceFilter, timelineLevelFilter, timelineTypeFilter]);

  const actionLayerSummary = useMemo(() => {
    const summary = {
      llm: 0,
      worker: 0,
      candidate: 0,
      order: 0,
      playwright: 0,
      system: 0,
      other: 0,
      server: 0,
      total: 0,
      localTotal: 0,
    };
    for (const item of timelineAll) {
      summary.total += 1;
      if (item.source === 'SERVER') {
        summary.server += 1;
        continue;
      }
      summary.localTotal += 1;
      if (item.type === 'LLM') summary.llm += 1;
      else if (item.type === 'WORKER') summary.worker += 1;
      else if (item.type === 'CANDIDATE') summary.candidate += 1;
      else if (item.type === 'ORDER') summary.order += 1;
      else if (item.type === 'PLAYWRIGHT') summary.playwright += 1;
      else if (item.type === 'SYSTEM') summary.system += 1;
      else summary.other += 1;
    }
    return summary;
  }, [timelineAll]);

  const filteredTimelineSummary = useMemo(() => {
    const summary = {
      warn: 0,
      error: 0,
      screenshots: 0,
      local: 0,
      server: 0,
    };
    for (const item of timeline) {
      if (item.level === 'WARN') summary.warn += 1;
      if (item.level === 'ERROR') summary.error += 1;
      if (item.screenshotId) summary.screenshots += 1;
      if (item.source === 'LOCAL') summary.local += 1;
      if (item.source === 'SERVER') summary.server += 1;
    }
    return summary;
  }, [timeline]);

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
    setTimelinePage(1);
  }, [timelineSourceFilter]);
  useEffect(() => {
    setTimelinePage(1);
  }, [timelineLevelFilter, timelineTypeFilter]);
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
  const latestVisibleTimeline = timeline[0] ?? null;
  const timelineVisibleStart = timeline.length === 0 ? 0 : (timelinePage - 1) * timelinePageSize + 1;
  const timelineVisibleEnd = Math.min(timeline.length, timelinePage * timelinePageSize);

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
  const candidates = useMemo(() => {
    const base = autopilotState.candidates.length > 0
      ? autopilotState.candidates
      : normalizeServerCandidates(liveData);
    return [...base].sort((left, right) => {
      const scoreGap = (right.score ?? Number.NEGATIVE_INFINITY) - (left.score ?? Number.NEGATIVE_INFINITY);
      if (Number.isFinite(scoreGap) && scoreGap !== 0) return scoreGap;
      return (right.expectancyPct ?? Number.NEGATIVE_INFINITY) - (left.expectancyPct ?? Number.NEGATIVE_INFINITY);
    });
  }, [autopilotState.candidates, liveData]);
  const stageBreakdown = useMemo(() => {
    if (autopilotState.candidates.length > 0) {
      return autopilotState.decisionBreakdown;
    }
    return candidates.reduce(
      (acc, candidate) => {
        if (candidate.stage === 'AUTO_PASS') acc.autoPass += 1;
        else if (candidate.stage === 'BORDERLINE') acc.borderline += 1;
        else if (candidate.stage === 'RULE_FAIL') acc.ruleFail += 1;
        return acc;
      },
      { autoPass: 0, borderline: 0, ruleFail: 0 }
    );
  }, [autopilotState.candidates.length, autopilotState.decisionBreakdown, candidates]);
  const expectancyLeaders = useMemo(
    () =>
      candidates
        .filter((candidate) => candidate.expectancyPct != null && Number.isFinite(candidate.expectancyPct))
        .sort((left, right) => (right.expectancyPct ?? 0) - (left.expectancyPct ?? 0))
        .slice(0, 5),
    [candidates]
  );
  const appliedThreshold = liveData?.appliedRecommendedWinRateThreshold
    ?? autopilotState.appliedRecommendedWinRateThreshold;
  const thresholdMode = liveData?.thresholdMode ?? autopilotState.thresholdMode;
  const effectiveOpportunityProfile = liveData?.opportunityProfile ?? opportunityProfile;
  const thresholdLabel = thresholdMode === 'DYNAMIC_P70' ? '동적 P70(추천가)' : '고정(현재가)';
  const focusedMarkets = autopilotState.focusedScalp.markets;
  const focusedEnabled = autopilotState.focusedScalp.enabled;
  const tokenBudgetSkipCount = candidates.filter((candidate) => candidate.stage === 'TOKEN_BUDGET_SKIP').length;
  const quantFilteredCount = candidates.filter((candidate) => candidate.stage === 'QUANT_FILTERED').length;
  const recheckScheduledCount = candidates.filter((candidate) => candidate.stage === 'RECHECK_SCHEDULED').length;
  const focusedWorkerSummary = useMemo(() => {
    if (focusedMarkets.length === 0) return '-';
    const focusedSet = new Set(focusedMarkets);
    const items = autopilotState.workers
      .filter((worker) => focusedSet.has(worker.market))
      .map((worker) => `${worker.market}:${worker.status}`);
    return items.length > 0 ? items.join(', ') : '대기';
  }, [autopilotState.workers, focusedMarkets]);

  const lastEvent = timelineAll[0] ?? null;
  const eventAgeSec = lastEvent ? Math.max(0, Math.floor((Date.now() - lastEvent.at) / 1000)) : null;
  const tokenUsageRate = autopilotState.llmBudget.dailyTokenCap === 0
    ? 0
    : (autopilotState.llmBudget.usedTokens / autopilotState.llmBudget.dailyTokenCap) * 100;
  const entryTokenHealth = clampPercent(100 - tokenUsageRate);
  const enterFillRate = clampPercent(
    summary.buyRequested === 0 ? 100 : (summary.buyFilled / summary.buyRequested) * 100
  );
  const exitFillRate = clampPercent(
    summary.sellRequested === 0 ? 100 : (summary.sellFilled / summary.sellRequested) * 100
  );
  const workerActivityRate = autopilotState.workers.length === 0
    ? 0
    : Math.min(
      100,
      Math.round(
        (autopilotState.workers.filter((worker) => /running|active|processing/i.test(worker.status)).length
          / Math.max(1, autopilotState.workers.length)) * 100
      )
    );
  const autopilotHealthScore = clampPercent(
    enterFillRate * 0.38 + exitFillRate * 0.32 + entryTokenHealth * 0.2 + workerActivityRate * 0.1
  );

  return (
    <section className="autopilot-live-dock">
      <header className="autopilot-live-header">
        <div>
          <strong>초단타 라이브</strong>
          <span>{autopilotEnabled ? '실행 중' : '대기'}</span>
        </div>
      </header>

      <div className="autopilot-command-hub">
          <div className="command-tile">
            <label>AI 헬스스코어</label>
            <strong>{autopilotHealthScore} / 100</strong>
            <div className="command-bar">
              <div className="command-fill" style={{ width: `${autopilotHealthScore}%` }} />
            </div>
          </div>
          <div className="command-tile">
            <label>진입 체결율</label>
            <strong>{formatScore(enterFillRate)}</strong>
            <small>{summary.buyFilled} / {summary.buyRequested}</small>
          </div>
          <div className="command-tile">
            <label>청산 체결율</label>
            <strong>{formatScore(exitFillRate)}</strong>
            <small>{summary.sellFilled} / {summary.sellRequested}</small>
          </div>
          <div className="command-tile">
            <label>워커/이벤트</label>
            <strong>{autopilotState.workers.length} / {autopilotState.events.length}</strong>
            <small>{focusedMarkets.length > 0 ? `포커스 ${focusedMarkets.length}개` : '포커스 없음'}</small>
          </div>
          <div className="command-tile">
            <label>마지막 액션</label>
            <strong>{lastEvent ? lastEvent.action : '대기'}</strong>
            <small>{eventAgeSec == null ? '이벤트 없음' : `마지막 ${eventAgeSec}초 전`}</small>
          </div>
          <div className="command-tile">
            <label>토큰 잔량</label>
            <strong>{entryTokenHealth}%</strong>
            <small>
              {autopilotState.llmBudget.usedTokens.toLocaleString('ko-KR')}
              {' / '}
              {autopilotState.llmBudget.dailyTokenCap.toLocaleString('ko-KR')}
            </small>
          </div>
          <div className="command-tile">
            <label>액션 레이어</label>
            <strong>{actionLayerSummary.localTotal + actionLayerSummary.server} 이벤트</strong>
            <small>LLM {actionLayerSummary.llm} · WORKER {actionLayerSummary.worker} · 후보 {actionLayerSummary.candidate}</small>
            <small>ORDER {actionLayerSummary.order} · Playwright {actionLayerSummary.playwright} · SERVER {actionLayerSummary.server}</small>
          </div>
        </div>

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
            전략 성향: {effectiveOpportunityProfile === 'CROWD_PRESSURE' ? 'CROWD_PRESSURE' : 'CLASSIC'}
            {effectiveOpportunityProfile === 'CROWD_PRESSURE'
              ? ' · pulse freshness <= 5s · 추천가 승률 gate 56% · score gate 60/70'
              : ` · 임계값 모드: ${thresholdLabel} · 적용 기준: ${appliedThreshold.toFixed(1)}%`}
          </div>
          <div className="autopilot-opportunity-meta">
            <span>AUTO_PASS {stageBreakdown.autoPass}</span>
            <span>BORDERLINE {stageBreakdown.borderline}</span>
            <span>RULE_FAIL {stageBreakdown.ruleFail}</span>
            <span>QUANT_FILTERED {quantFilteredCount}</span>
            <span>TOKEN_BUDGET_SKIP {tokenBudgetSkipCount}</span>
            <span>RECHECK_SCHEDULED {recheckScheduledCount}</span>
          </div>
          <div className={`autopilot-llm-budget ${autopilotState.llmBudget.fallbackMode ? 'warn' : ''}`}>
            토큰 사용량 {autopilotState.llmBudget.usedTokens.toLocaleString('ko-KR')}/{autopilotState.llmBudget.dailyTokenCap.toLocaleString('ko-KR')}
            {` · Entry ${autopilotState.llmBudget.entryUsedTokens.toLocaleString('ko-KR')}/${autopilotState.llmBudget.entryBudgetTotal.toLocaleString('ko-KR')}`}
            {` · Reserve ${autopilotState.llmBudget.riskUsedTokens.toLocaleString('ko-KR')}/${autopilotState.llmBudget.riskReserveTokens.toLocaleString('ko-KR')}`}
            {autopilotState.llmBudget.fallbackMode ? ' · Quant-only fallback 활성' : ''}
          </div>
          <div className="autopilot-focused-meta">
            <span>선택 코인 루프: {focusedEnabled ? 'ON' : 'OFF'}</span>
            <span>주기: {autopilotState.focusedScalp.pollIntervalSec}s</span>
            <span>대상: {focusedMarkets.length > 0 ? focusedMarkets.join(', ') : '-'}</span>
            <span>워커: {focusedWorkerSummary}</span>
            {focusedEnabled && focusedMarkets.length > 0 && (
              <span>전역 후보 제외: {focusedMarkets.length}개</span>
            )}
          </div>

          <div className="autopilot-live-body">
            <div className="dock-column candidates">
              <div className="column-title">후보 상위 {candidates.length}</div>
              {focusedEnabled && focusedMarkets.length > 0 && (
                <div className="candidate-exclusion-note">
                  선택 코인({focusedMarkets.join(', ')})은 전역 후보 선별에서 제외됩니다.
                </div>
              )}
              {expectancyLeaders.length > 0 && (
                <div className="expectancy-leaders">
                  {expectancyLeaders.map((candidate) => (
                    <span key={candidate.market}>
                      {candidate.market} {candidate.expectancyPct?.toFixed(3)}%
                    </span>
                  ))}
                </div>
              )}
              <div className="candidate-list">
                {candidates.map((candidate) => (
                  <div key={candidate.market} className="candidate-item">
                    <div className="candidate-top">
                      <strong>{candidate.market}</strong>
                      <span className={`stage stage-${candidate.stage.toLowerCase()}`}>{stageLabel(candidate.stage)}</span>
                    </div>
                    <div className="candidate-command-strip">
                      <span>기본점수 {formatScore(candidate.score ?? 0)}</span>
                      <span>기대값 {(candidate.expectancyPct ?? 0).toFixed(1)}%</span>
                      <span>신뢰도 {candidate.confidence != null ? `${(candidate.confidence * 100).toFixed(0)}%` : '-'}</span>
                    </div>
                    <div className="candidate-metrics">
                      추천 {candidate.recommendedEntryWinRate?.toFixed(1) ?? '-'}% · 현재 {candidate.marketEntryWinRate?.toFixed(1) ?? '-'}%
                    </div>
                    <div className="candidate-metrics">
                      기대값 {candidate.expectancyPct?.toFixed(3) ?? '-'}% · score {candidate.score?.toFixed(1) ?? '-'} · RR {candidate.riskRewardRatio?.toFixed(2) ?? '-'}R · 괴리 {candidate.entryGapPct?.toFixed(2) ?? '-'}%
                    </div>
                    {candidate.crowdMetrics && (
                      <div className="candidate-metrics">
                        {formatCrowdMetrics(candidate)}
                        {candidate.crowdMetrics.pulseAgeMs > 0 ? ` · pulse ${candidate.crowdMetrics.pulseAgeMs}ms` : ''}
                      </div>
                    )}
                    <div className="candidate-score-meter">
                      <div
                        className="candidate-score-fill"
                        style={{ width: `${clampPercent(Number(candidate.score ?? 0))}%` }}
                      />
                    </div>
                    <p>{candidate.reason}</p>
                  </div>
                ))}
                {candidates.length === 0 && <div className="empty">후보 없음</div>}
              </div>
            </div>

            <div className="dock-column timeline">
              <div className="column-title timeline-header">
                <div className="timeline-title-block">
                  <div className="timeline-title-copy">
                    <strong>실시간 액션 타임라인</strong>
                    <span>최신 이벤트 순으로 보이며, 카드를 누르면 오른쪽에서 전문과 스크린샷을 확인할 수 있습니다.</span>
                  </div>
                  <div className="timeline-summary-strip">
                    <div className="timeline-summary-card">
                      <label>표시 중</label>
                      <strong>{timeline.length}</strong>
                      <span>전체 {timelineAll.length}개</span>
                    </div>
                    <div className="timeline-summary-card">
                      <label>마지막 액션</label>
                      <strong>{latestVisibleTimeline ? latestVisibleTimeline.action : '대기'}</strong>
                      <span>
                        {latestVisibleTimeline
                          ? `${formatRelativeAge(latestVisibleTimeline.at)} · ${latestVisibleTimeline.market ?? timelineTypeLabel(latestVisibleTimeline.type)}`
                          : '이벤트 없음'}
                      </span>
                    </div>
                    <div className="timeline-summary-card">
                      <label>주의 상태</label>
                      <strong>{filteredTimelineSummary.warn + filteredTimelineSummary.error}</strong>
                      <span>WARN {filteredTimelineSummary.warn} · ERROR {filteredTimelineSummary.error}</span>
                    </div>
                    <div className="timeline-summary-card">
                      <label>증거 연결</label>
                      <strong>{filteredTimelineSummary.screenshots}</strong>
                      <span>{filteredTimelineSummary.local} 로컬 · {filteredTimelineSummary.server} 서버</span>
                    </div>
                  </div>
                </div>
                <div className="timeline-controls">
                  <div className="timeline-filter-row">
                    <div className="timeline-source-filters">
                      {(['ALL', 'LOCAL', 'SERVER'] as const).map((filter) => (
                        <button
                          key={filter}
                          type="button"
                          className={timelineSourceFilter === filter ? 'active' : ''}
                          onClick={() => setTimelineSourceFilter(filter)}
                        >
                          {timelineSourceLabel(filter)}
                        </button>
                      ))}
                    </div>
                    <label className="page-size-select timeline-filter-select">
                      <span>레벨</span>
                      <select
                        value={timelineLevelFilter}
                        onChange={(event) => setTimelineLevelFilter(event.target.value as TimelineLevelFilter)}
                      >
                        <option value="ALL">전체</option>
                        <option value="INFO">INFO</option>
                        <option value="WARN">WARN</option>
                        <option value="ERROR">ERROR</option>
                      </select>
                    </label>
                    <label className="page-size-select timeline-filter-select">
                      <span>타입</span>
                      <select
                        value={timelineTypeFilter}
                        onChange={(event) => setTimelineTypeFilter(event.target.value as TimelineTypeFilter)}
                      >
                        <option value="ALL">전체</option>
                        <option value="LLM">LLM</option>
                        <option value="WORKER">워커</option>
                        <option value="CANDIDATE">후보</option>
                        <option value="ORDER">주문</option>
                        <option value="PLAYWRIGHT">브라우저</option>
                        <option value="SYSTEM">시스템</option>
                      </select>
                    </label>
                  </div>
                  <div className="timeline-pagination">
                    <span>{timelineVisibleStart === 0 ? '0개' : `${timelineVisibleStart}-${timelineVisibleEnd} / ${timeline.length}`}</span>
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
              </div>
              <div className="timeline-layout">
                <div className="timeline-list">
                  {pagedTimeline.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      className={`timeline-item ${selectedTimelineId === item.id ? 'active' : ''} timeline-source-${item.source.toLowerCase()}`}
                      onClick={() => setSelectedTimelineId(item.id)}
                    >
                      <div className="timeline-rail">
                        <span className="timeline-time">{formatTs(item.at)}</span>
                        <span className="timeline-age">{formatRelativeAge(item.at)}</span>
                      </div>
                      <div className="timeline-item-body">
                        <div className="timeline-item-topline">
                          <div className="timeline-item-title-group">
                            <div className="timeline-action">{item.action}</div>
                            <span className="timeline-market-label">{item.market ?? timelineTypeLabel(item.type)}</span>
                          </div>
                          <span className="timeline-open-hint">{selectedTimelineId === item.id ? '선택됨' : '상세 보기'}</span>
                        </div>
                        <div className="timeline-meta-line">
                          <span className={`timeline-badge timeline-source-badge-${item.source.toLowerCase()}`}>{timelineSourceLabel(item.source)}</span>
                          <span className="timeline-badge">{timelineTypeLabel(item.type)}</span>
                          {item.rawType && item.rawType !== item.type && (
                            <span className="timeline-badge timeline-badge-raw">{item.rawType}</span>
                          )}
                          {item.level !== 'INFO' && (
                            <span className={`timeline-badge timeline-level-${item.level.toLowerCase()}`}>{item.level}</span>
                          )}
                          {item.screenshotId && <span className="timeline-badge timeline-badge-evidence">스크린샷</span>}
                        </div>
                        <p>{item.detail}</p>
                      </div>
                    </button>
                  ))}
                  {pagedTimeline.length === 0 && (
                    <div className="empty">{loading ? '로딩 중...' : '이벤트 없음'}</div>
                  )}
                </div>
                <div className="timeline-detail">
                  {selectedTimeline ? (
                    <>
                      <div className="timeline-detail-label">선택한 이벤트</div>
                      <div className="detail-head">
                        <div className="timeline-detail-title-group">
                          <strong>{selectedTimeline.action}</strong>
                          <span>{formatTs(selectedTimeline.at)} · {formatRelativeAge(selectedTimeline.at)}</span>
                        </div>
                        <span className={`timeline-detail-level timeline-detail-level-${selectedTimeline.level.toLowerCase()}`}>{selectedTimeline.level}</span>
                      </div>
                      <div className="detail-subhead">
                        <span className={`timeline-badge timeline-source-badge-${selectedTimeline.source.toLowerCase()}`}>{timelineSourceLabel(selectedTimeline.source)}</span>
                        <span className="timeline-badge">{timelineTypeLabel(selectedTimeline.type)}</span>
                        {selectedTimeline.rawType && selectedTimeline.rawType !== selectedTimeline.type && (
                          <span className="timeline-badge timeline-badge-raw">{selectedTimeline.rawType}</span>
                        )}
                        {selectedTimeline.market ? <span>시장: {selectedTimeline.market}</span> : null}
                      </div>
                      <div className="timeline-detail-copy">
                        <p>{selectedTimeline.detail}</p>
                      </div>
                      <div className="timeline-detail-media-head">
                        <strong>증거 / 스크린샷</strong>
                        <span>{selectedShot ? 'Playwright 캡처 연결됨' : '연결된 스크린샷 없음'}</span>
                      </div>
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
    </section>
  );
}
