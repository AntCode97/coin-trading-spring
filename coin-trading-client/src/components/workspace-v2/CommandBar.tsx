import { useCallback, useRef, useState } from 'react';
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
  onNavigateAiTrader?: () => void;
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
  onNavigateAiTrader,
}: CommandBarProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  const toggleMenu = useCallback(() => setMenuOpen((prev) => !prev), []);
  const closeMenu = useCallback(() => setMenuOpen(false), []);

  return (
    <header className="workspace-command-bar">
      <div className="workspace-command-left">
        <strong>{selectedMarket}</strong>
        <span className={`connection-dot ${connectionLabel === '연결됨' ? 'ok' : 'warn'}`} />
        <span className="cb-session">{sessionLabel}</span>
      </div>

      <div className="workspace-command-pills">
        <span className={`cb-pill ${todayPnlKrw >= 0 ? 'up' : 'down'}`}>
          PnL {todayPnlKrw >= 0 ? '+' : ''}{formatCompactKrw(todayPnlKrw)}
        </span>
        <span className="cb-pill">
          엔진 {activeEngineCount}/3
        </span>
        <span className="cb-pill">
          노출 {exposurePercent}%
        </span>
        <span className={`cb-pill ${warningCount > 0 ? 'warn' : ''}`}>
          경고 {warningCount}
        </span>
      </div>

      <div className="workspace-command-right">
        <button type="button" className="emergency-btn" onClick={onEmergencyStop}>
          긴급 중지
        </button>
        <div className="cb-overflow-wrap" ref={menuRef}>
          <button type="button" className="cb-overflow-btn" onClick={toggleMenu}>
            ···
          </button>
          {menuOpen && (
            <div className="cb-overflow-menu" onClick={closeMenu}>
              <button type="button" onClick={onSync} disabled={syncPending}>
                {syncPending ? '동기화...' : '계정 동기화'}
              </button>
              <button type="button" onClick={onToggleDensity}>
                {density === 'COMFORT' ? 'Compact 보기' : 'Comfort 보기'}
              </button>
              <button type="button" onClick={onOpenChat}>채팅</button>
              <button type="button" onClick={onToggleRightPanel}>
                {rightPanelOpen ? '우측 패널 숨기기' : '우측 패널 보기'}
              </button>
              {onNavigateAiTrader && (
                <button type="button" onClick={onNavigateAiTrader}>AI Day Trader</button>
              )}
            </div>
          )}
        </div>
      </div>
    </header>
  );
}
