import { type ReactNode, useState } from 'react';

type ConsoleTab = 'CONTROL' | 'RISK' | 'MONITOR' | 'ADVANCED';

interface ActionConsoleProps {
  executionGate: ReactNode;
  engineControl: ReactNode;
  engineExtra?: ReactNode;
  riskPreset: ReactNode;
  candidateQueue: ReactNode;
  advanced: ReactNode;
  footer?: ReactNode;
}

const TABS: { key: ConsoleTab; label: string }[] = [
  { key: 'CONTROL', label: '컨트롤' },
  { key: 'RISK', label: '리스크' },
  { key: 'MONITOR', label: '모니터' },
  { key: 'ADVANCED', label: '고급' },
];

export function ActionConsole({
  executionGate,
  engineControl,
  engineExtra,
  riskPreset,
  candidateQueue,
  advanced,
  footer,
}: ActionConsoleProps) {
  const [activeTab, setActiveTab] = useState<ConsoleTab>('CONTROL');

  return (
    <section className="workspace-action-console">
      <div className="ac-tab-bar">
        {TABS.map(({ key, label }) => (
          <button
            key={key}
            type="button"
            className={`ac-tab ${activeTab === key ? 'active' : ''}`}
            onClick={() => setActiveTab(key)}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="ac-tab-content">
        {activeTab === 'CONTROL' && (
          <>
            <article className="action-console-section">
              <header><strong>액션</strong></header>
              {executionGate}
            </article>
            <article className="action-console-section">
              <header><strong>엔진</strong></header>
              {engineControl}
            </article>
            {engineExtra && (
              <article className="action-console-section ac-section-compact">
                <header><strong>주문 옵션</strong></header>
                <div className="autopilot-toggle-strip">{engineExtra}</div>
              </article>
            )}
          </>
        )}

        {activeTab === 'RISK' && (
          <article className="action-console-section">
            <header><strong>리스크/한도 설정</strong></header>
            {riskPreset}
          </article>
        )}

        {activeTab === 'MONITOR' && (
          <article className="action-console-section">
            <header><strong>실시간 후보/이벤트</strong></header>
            {candidateQueue}
          </article>
        )}

        {activeTab === 'ADVANCED' && (
          <article className="action-console-section">
            <header><strong>고급 파라미터</strong></header>
            {advanced}
          </article>
        )}
      </div>

      {footer ? <div className="action-console-footer">{footer}</div> : null}
    </section>
  );
}
