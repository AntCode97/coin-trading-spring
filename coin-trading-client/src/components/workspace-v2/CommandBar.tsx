import type { WorkspaceDensity } from './types';

interface CommandBarProps {
  selectedMarket: string;
  sessionLabel: string;
  connectionLabel: string;
  density: WorkspaceDensity;
  todayPnlKrw: number;
  exposurePercent: number;
  activeEngineCount: number;
  warningCount: number;
  armState: 'DISARMED' | 'ARMED';
  armCountdownSec: number;
  syncPending: boolean;
  rightPanelOpen: boolean;
  onArmPointerDown: () => void;
  onArmPointerUp: () => void;
  onArmPointerLeave: () => void;
  onArmClick: () => void;
  onEmergencyStop: () => void;
  onSync: () => void;
  onOpenChat: () => void;
  onToggleRightPanel: () => void;
  onToggleDensity: () => void;
}

function formatCompactKrw(value: number): string {
  if (Math.abs(value) >= 100_000_000) return `${(value / 100_000_000).toFixed(1)}억`;
  if (Math.abs(value) >= 10_000) return `${(value / 10_000).toFixed(1)}만`;
  return `${Math.round(value).toLocaleString('ko-KR')}원`;
}

export function CommandBar({
  selectedMarket,
  sessionLabel,
  connectionLabel,
  density,
  todayPnlKrw,
  exposurePercent,
  activeEngineCount,
  warningCount,
  armState,
  armCountdownSec,
  syncPending,
  rightPanelOpen,
  onArmPointerDown,
  onArmPointerUp,
  onArmPointerLeave,
  onArmClick,
  onEmergencyStop,
  onSync,
  onOpenChat,
  onToggleRightPanel,
  onToggleDensity,
}: CommandBarProps) {
  return (
    <header className="workspace-command-bar">
      <div className="workspace-command-left">
        <strong>{selectedMarket}</strong>
        <span>{sessionLabel}</span>
        <span className={`connection ${connectionLabel === '연결됨' ? 'ok' : 'warn'}`}>{connectionLabel}</span>
      </div>

      <div className="workspace-command-kpis">
        <article className={todayPnlKrw >= 0 ? 'up' : 'down'}>
          <span>오늘 손익</span>
          <strong>{todayPnlKrw >= 0 ? '+' : ''}{formatCompactKrw(todayPnlKrw)}</strong>
        </article>
        <article>
          <span>총 노출</span>
          <strong>{exposurePercent}%</strong>
        </article>
        <article>
          <span>활성 엔진</span>
          <strong>{activeEngineCount}/3</strong>
        </article>
        <article className={warningCount > 0 ? 'warn' : ''}>
          <span>경고</span>
          <strong>{warningCount}</strong>
        </article>
      </div>

      <div className="workspace-command-actions">
        <button
          type="button"
          className={`arm-btn ${armState === 'ARMED' ? 'armed' : 'disarmed'}`}
          onMouseDown={onArmPointerDown}
          onMouseUp={onArmPointerUp}
          onMouseLeave={onArmPointerLeave}
          onTouchStart={onArmPointerDown}
          onTouchEnd={onArmPointerUp}
          onClick={onArmClick}
        >
          {armState === 'ARMED' ? `ARMED ${armCountdownSec}s` : 'ARM'}
        </button>
        <button type="button" className="emergency-btn" onClick={onEmergencyStop}>긴급 정지</button>
        <button type="button" onClick={onSync} disabled={syncPending}>
          {syncPending ? '동기화...' : 'Sync'}
        </button>
        <button type="button" onClick={onOpenChat}>채팅</button>
        <button type="button" onClick={onToggleRightPanel}>{rightPanelOpen ? '콘솔 닫기' : '콘솔 열기'}</button>
        <button type="button" onClick={onToggleDensity}>{density === 'COMFORT' ? 'Compact' : 'Comfort'}</button>
      </div>
    </header>
  );
}
