import type { ReactNode } from 'react';
import type { WorkspaceDensity } from './types';

interface WorkspaceShellProps {
  density: WorkspaceDensity;
  rightOpen: boolean;
  children: ReactNode;
  liveStrip?: ReactNode;
  onCloseRight: () => void;
}

export function WorkspaceShell({
  density,
  rightOpen,
  children,
  liveStrip,
  onCloseRight,
}: WorkspaceShellProps) {
  return (
    <div className={`workspace-shell density-${density.toLowerCase()} ${rightOpen ? 'right-open' : ''}`}>
      {children}
      {liveStrip}
      <button
        type="button"
        className="workspace-right-backdrop"
        onClick={onCloseRight}
        aria-label="콘솔 닫기"
      />
    </div>
  );
}
