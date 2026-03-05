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
          <span>1 / 5</span>
          <strong>즉시 실행 컨트롤</strong>
        </header>
        {executionGate}
      </article>

      <article className="action-console-section">
        <header>
          <span>2 / 5</span>
          <strong>자동매매 엔진 스위치</strong>
        </header>
        {engineControl}
      </article>

      <article className="action-console-section">
        <header>
          <span>3 / 5</span>
          <strong>리스크/한도 설정</strong>
        </header>
        {riskPreset}
      </article>

      <article className="action-console-section">
        <header>
          <span>4 / 5</span>
          <strong>실시간 후보·이벤트</strong>
        </header>
        {candidateQueue}
      </article>

      <article className="action-console-section advanced">
        <header>
          <span>5 / 5</span>
          <strong>고급 파라미터</strong>
        </header>
        {advanced}
      </article>

      {footer ? <div className="action-console-footer">{footer}</div> : null}
    </section>
  );
}
