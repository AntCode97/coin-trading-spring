import { type ReactNode, useState } from 'react';

type ConsoleTab = 'CONTROL' | 'RISK' | 'MONITOR' | 'ADVANCED';

interface ActionConsoleProps {
  executionGate: ReactNode;
  engineControl: ReactNode;
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
              <header><strong>즉시 실행 컨트롤</strong></header>
              {executionGate}
            </article>
            <article className="action-console-section">
              <header><strong>자동매매 엔진 스위치</strong></header>
              {engineControl}
            </article>
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
