import type { ReactNode } from 'react';

interface ActionConsoleProps {
  executionGate: ReactNode;
  engineControl: ReactNode;
  riskPreset: ReactNode;
  candidateQueue: ReactNode;
  advanced: ReactNode;
  footer?: ReactNode;
}

export function ActionConsole({
  executionGate,
  engineControl,
  riskPreset,
  candidateQueue,
  advanced,
  footer,
}: ActionConsoleProps) {
  return (
    <section className="workspace-action-console">
      <article className="action-console-section">
        <header>
          <span>Execution Gate</span>
          <strong>실행 보안</strong>
        </header>
        {executionGate}
      </article>

      <article className="action-console-section">
        <header>
          <span>Engine Control</span>
          <strong>엔진 제어</strong>
        </header>
        {engineControl}
      </article>

      <article className="action-console-section">
        <header>
          <span>Risk Preset</span>
          <strong>리스크 프리셋</strong>
        </header>
        {riskPreset}
      </article>

      <article className="action-console-section">
        <header>
          <span>Candidate Queue</span>
          <strong>후보 큐</strong>
        </header>
        {candidateQueue}
      </article>

      <article className="action-console-section advanced">
        <header>
          <span>Advanced</span>
          <strong>상세 파라미터</strong>
        </header>
        {advanced}
      </article>

      {footer ? <div className="action-console-footer">{footer}</div> : null}
    </section>
  );
}
