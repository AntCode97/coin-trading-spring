import { useEffect, useRef, useState } from 'react';
import type { AutopilotLiveResponse } from '../../api';
import type { AutopilotState } from '../../lib/autopilot/AutopilotOrchestrator';
import { AutopilotLiveDock } from '../autopilot/AutopilotLiveDock';
import {
  FocusedScalpLiveDock,
  type FocusedScalpDecisionCardView,
  type FocusedScalpDecisionSummaryView,
} from '../autopilot/FocusedScalpLiveDock';
import { InvestmentLiveDock } from '../autopilot/InvestmentLiveDock';
import { EngineFilterPills, type EngineFilter } from './EngineFilterPills';
import './DeepDiveDrawer.css';

type DrawerTab = 'TIMELINE' | 'CANDIDATES' | 'WORKERS' | 'ORDERS' | 'FOCUSED_SCALP';

interface DeepDiveDrawerProps {
  open: boolean;
  onClose: () => void;
  autopilotEnabled: boolean;
  autopilotState: AutopilotState;
  scalpLiveData?: AutopilotLiveResponse;
  scalpLoading?: boolean;
  swingEnabled: boolean;
  positionEnabled: boolean;
  swingState: AutopilotState;
  positionState: AutopilotState;
  swingLiveData?: AutopilotLiveResponse;
  positionLiveData?: AutopilotLiveResponse;
  investLoading?: boolean;
  focusedScalpEnabled: boolean;
  focusedScalpCards: FocusedScalpDecisionCardView[];
  focusedScalpSummary: FocusedScalpDecisionSummaryView;
  onSelectMarket?: (market: string) => void;
}

const TABS: { key: DrawerTab; label: string }[] = [
  { key: 'TIMELINE', label: '타임라인' },
  { key: 'CANDIDATES', label: '후보' },
  { key: 'WORKERS', label: '워커' },
  { key: 'ORDERS', label: '체결내역' },
  { key: 'FOCUSED_SCALP', label: '선택단타' },
];

export function DeepDiveDrawer({
  open,
  onClose,
  autopilotEnabled,
  autopilotState,
  scalpLiveData,
  scalpLoading,
  swingEnabled,
  positionEnabled,
  swingState,
  positionState,
  swingLiveData,
  positionLiveData,
  investLoading,
  focusedScalpEnabled,
  focusedScalpCards,
  focusedScalpSummary,
  onSelectMarket,
}: DeepDiveDrawerProps) {
  const [tab, setTab] = useState<DrawerTab>('TIMELINE');
  const [engine, setEngine] = useState<EngineFilter>('ALL');
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, onClose]);

  const showScalp = engine === 'ALL' || engine === 'SCALP';
  const showInvest = engine === 'ALL' || engine === 'SWING' || engine === 'POSITION';

  return (
    <div className={`dd-drawer ${open ? 'open' : ''}`}>
      <button type="button" className="dd-backdrop" onClick={onClose} aria-label="닫기" />
      <div className="dd-panel" ref={panelRef}>
        <header className="dd-header">
          <div className="dd-tabs">
            {TABS.map(({ key, label }) => (
              <button
                key={key}
                type="button"
                className={`dd-tab ${tab === key ? 'active' : ''}`}
                onClick={() => setTab(key)}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="dd-header-right">
            <EngineFilterPills value={engine} onChange={setEngine} />
            <button type="button" className="dd-close" onClick={onClose}>닫기</button>
          </div>
        </header>
        <div className="dd-body">
          {tab === 'FOCUSED_SCALP' ? (
            <FocusedScalpLiveDock
              open={true}
              collapsed={false}
              onToggleCollapse={() => {}}
              enabled={focusedScalpEnabled}
              cards={focusedScalpCards}
              summary={focusedScalpSummary}
              onSelectMarket={onSelectMarket}
            />
          ) : (
            <>
              {showScalp && (
                <AutopilotLiveDock
                  open={true}
                  collapsed={false}
                  onToggleCollapse={() => {}}
                  autopilotEnabled={autopilotEnabled}
                  autopilotState={autopilotState}
                  liveData={scalpLiveData}
                  loading={scalpLoading}
                />
              )}
              {showInvest && (
                <InvestmentLiveDock
                  open={true}
                  collapsed={false}
                  onToggleCollapse={() => {}}
                  swingEnabled={swingEnabled}
                  positionEnabled={positionEnabled}
                  swingState={swingState}
                  positionState={positionState}
                  swingLiveData={swingLiveData}
                  positionLiveData={positionLiveData}
                  loading={investLoading}
                />
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
