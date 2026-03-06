import type { AutopilotState, AutopilotCandidateView } from '../../lib/autopilot/AutopilotOrchestrator';
import type { AutopilotLiveResponse, OrderLifecycleGroupSummary } from '../../api';
import { EmptyState } from './EmptyState';
import './LiveStrip.css';

interface LiveStripProps {
  autopilotEnabled: boolean;
  swingEnabled: boolean;
  positionEnabled: boolean;
  autopilotState: AutopilotState;
  liveData?: AutopilotLiveResponse;
  candidates: AutopilotCandidateView[];
  healthScore: number;
  onOpenDrawer: () => void;
}

function clampPercent(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(100, Math.round(value)));
}

function stageClass(stage: string): string {
  switch (stage) {
    case 'AUTO_PASS': return 'auto-pass';
    case 'BORDERLINE': return 'borderline';
    case 'ENTERED': return 'entered';
    default: return 'default';
  }
}

function stageLabel(stage: string): string {
  switch (stage) {
    case 'AUTO_PASS': return '통과';
    case 'BORDERLINE': return '경계';
    case 'RULE_FAIL': return '탈락';
    case 'ENTERED': return '진입';
    case 'COOLDOWN': return '쿨다운';
    default: return stage;
  }
}

const EMPTY_SUMMARY: OrderLifecycleGroupSummary = {
  buyRequested: 0, buyFilled: 0, sellRequested: 0, sellFilled: 0, pending: 0, cancelled: 0,
};

export function LiveStrip({
  autopilotEnabled,
  swingEnabled,
  positionEnabled,
  autopilotState,
  liveData,
  candidates,
  healthScore,
  onOpenDrawer,
}: LiveStripProps) {
  const summary = liveData?.orderSummary?.total ?? EMPTY_SUMMARY;
  const topCandidates = candidates.slice(0, 3);
  const workerCount = autopilotState.workers.length;
  const tokenPct = clampPercent(
    autopilotState.llmBudget.dailyTokenCap === 0
      ? 100
      : ((autopilotState.llmBudget.dailyTokenCap - autopilotState.llmBudget.usedTokens)
          / autopilotState.llmBudget.dailyTokenCap) * 100
  );

  return (
    <div className="live-strip" onClick={onOpenDrawer} role="button" tabIndex={0}>
      <div className="ls-engines">
        <div className={`ls-engine-dot ${autopilotEnabled ? 'on' : 'off'}`} title="SCALP">
          <span className="ls-dot" />S
        </div>
        <div className={`ls-engine-dot ${swingEnabled ? 'on' : 'off'}`} title="SWING">
          <span className="ls-dot" />W
        </div>
        <div className={`ls-engine-dot ${positionEnabled ? 'on' : 'off'}`} title="POSITION">
          <span className="ls-dot" />P
        </div>
      </div>

      <div className="ls-health">
        <svg className="ls-ring" viewBox="0 0 36 36">
          <circle className="ls-ring-bg" cx="18" cy="18" r="15.9" />
          <circle
            className="ls-ring-fill"
            cx="18" cy="18" r="15.9"
            strokeDasharray={`${healthScore} ${100 - healthScore}`}
            strokeDashoffset="25"
          />
        </svg>
        <span className="ls-ring-label">{healthScore}</span>
      </div>

      <div className="ls-funnel">
        <div className="ls-funnel-item">
          <span>매수요청</span><strong>{summary.buyRequested}</strong>
        </div>
        <span className="ls-arrow">&rsaquo;</span>
        <div className="ls-funnel-item">
          <span>매수체결</span><strong>{summary.buyFilled}</strong>
        </div>
        <span className="ls-arrow">&rsaquo;</span>
        <div className="ls-funnel-item">
          <span>매도요청</span><strong>{summary.sellRequested}</strong>
        </div>
        <span className="ls-arrow">&rsaquo;</span>
        <div className="ls-funnel-item">
          <span>매도체결</span><strong>{summary.sellFilled}</strong>
        </div>
      </div>

      <div className="ls-meta">
        <span>워커 {workerCount}</span>
        <span>토큰 {tokenPct}%</span>
      </div>

      <div className="ls-candidates">
        {topCandidates.length === 0 ? (
          <EmptyState variant="empty" message="후보 없음" />
        ) : (
          topCandidates.map((c) => (
            <span key={c.market} className={`ls-cand-badge ${stageClass(c.stage)}`}>
              {c.market.replace('KRW-', '')}
              <small>{stageLabel(c.stage)}</small>
            </span>
          ))
        )}
      </div>

      <div className="ls-expand-hint">상세 보기 &darr;</div>
    </div>
  );
}
