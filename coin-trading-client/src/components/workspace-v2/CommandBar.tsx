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
  syncPending: boolean;
  rightPanelOpen: boolean;
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
  syncPending,
  rightPanelOpen,
  onEmergencyStop,
  onSync,
  onOpenChat,
  onToggleRightPanel,
  onToggleDensity,
}: CommandBarProps) {
  const panelToggleLabel = rightPanelOpen ? '우측 패널 숨기기' : '우측 패널 보기';
  const panelToggleTitle = rightPanelOpen
    ? '우측 실행 패널을 숨기고 차트 영역을 넓게 봅니다.'
    : '우측 실행 패널을 다시 열어 엔진/리스크/후보 제어를 표시합니다.';
  const commandHelp = '즉시 조작 모드: 엔진 스위치, 프리셋, 주문 액션을 바로 실행할 수 있습니다.';

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
          className="emergency-btn"
          onClick={onEmergencyStop}
          title="SCALP/SWING/POSITION 엔진을 모두 OFF 하고 신규 진입을 즉시 중단합니다."
        >
          긴급 중지
        </button>
        <button type="button" onClick={onSync} disabled={syncPending}>
          {syncPending ? '동기화...' : '계정 동기화'}
        </button>
        <button type="button" onClick={onOpenChat}>채팅</button>
        <button type="button" onClick={onToggleRightPanel} title={panelToggleTitle}>{panelToggleLabel}</button>
        <button type="button" onClick={onToggleDensity}>{density === 'COMFORT' ? 'Compact' : 'Comfort'}</button>
        <p className="workspace-command-help">{commandHelp}</p>
      </div>
    </header>
  );
}
