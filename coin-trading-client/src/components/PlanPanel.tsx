import { useEffect, useState } from 'react';
import type { PlanExecution, PlanStep } from '../lib/planTypes';

interface PlanPanelProps {
  plan: PlanExecution;
  currentPrice: number | null;
  onCancel: () => void;
  onDismiss: () => void;
}

export function PlanPanel({ plan, currentPrice, onCancel, onDismiss }: PlanPanelProps) {
  const doneCount = plan.steps.filter((s) => s.status === 'done').length;
  const progressPct = plan.steps.length > 0 ? (doneCount / plan.steps.length) * 100 : 0;
  const isFinished = plan.status !== 'running';

  // 현재 모니터링 중인 step의 목표가
  const activeStep = plan.steps[plan.currentStepIndex];
  const targetPrice = activeStep?.params?.targetPrice as number | undefined;

  return (
    <div className="plan-panel">
      {/* 헤더 */}
      <div className="plan-header">
        <div className="plan-header-left">
          <span className="plan-action-badge">{plan.actionType}</span>
          <span className="plan-title">{plan.actionTitle}</span>
        </div>
        {plan.status === 'running' ? (
          <button type="button" className="plan-cancel-btn" onClick={onCancel}>
            취소
          </button>
        ) : (
          <button type="button" className="plan-cancel-btn" onClick={onDismiss}>
            닫기
          </button>
        )}
      </div>

      {/* 스텝 리스트 */}
      <div className="plan-steps">
        {plan.steps.map((step, idx) => (
          <PlanStepRow key={step.id} step={step} index={idx} />
        ))}
      </div>

      {/* 현재가 & 목표가 */}
      {plan.status === 'running' && currentPrice != null && targetPrice != null && (
        <div className="plan-price-info">
          <span>현재가 {currentPrice.toLocaleString('ko-KR')}원</span>
          <span className="plan-price-gap">
            (목표 {targetPrice.toLocaleString('ko-KR')}원까지{' '}
            {((currentPrice - targetPrice) / currentPrice * 100).toFixed(2)}%)
          </span>
        </div>
      )}

      {/* 진행 바 */}
      <div className="plan-progress">
        <div className="plan-progress-bar" style={{ width: `${progressPct}%` }} />
        <span className="plan-progress-label">
          {doneCount}/{plan.steps.length}
          {isFinished && ` — ${statusLabel(plan.status)}`}
        </span>
      </div>
    </div>
  );
}

function PlanStepRow({ step, index }: { step: PlanStep; index: number }) {
  return (
    <div className={`plan-step plan-step-${step.status}`}>
      <span className="plan-step-icon">{stepIcon(step.status)}</span>
      <div className="plan-step-content">
        <span className="plan-step-label">
          {index + 1}. {step.label}
        </span>
        {step.status === 'active' && step.startedAt && <ElapsedTimer startedAt={step.startedAt} />}
        {step.detail && <span className="plan-step-detail">{step.detail}</span>}
      </div>
    </div>
  );
}

function ElapsedTimer({ startedAt }: { startedAt: number }) {
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    const id = setInterval(() => setElapsed(Date.now() - startedAt), 1000);
    return () => clearInterval(id);
  }, [startedAt]);

  const sec = Math.floor(elapsed / 1000);
  const m = Math.floor(sec / 60);
  const s = sec % 60;

  return (
    <span className="plan-step-elapsed">
      {m}:{String(s).padStart(2, '0')}
    </span>
  );
}

function stepIcon(status: PlanStep['status']): string {
  switch (status) {
    case 'done': return '\u2713';
    case 'active': return '\u25CE';
    case 'failed': return '\u2717';
    case 'skipped': return '\u2014';
    case 'pending':
    default: return '\u25CB';
  }
}

function statusLabel(status: PlanExecution['status']): string {
  switch (status) {
    case 'completed': return '완료';
    case 'failed': return '실패';
    case 'cancelled': return '취소';
    default: return '';
  }
}
