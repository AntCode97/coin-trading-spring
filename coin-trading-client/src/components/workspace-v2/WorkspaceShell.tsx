import type { ReactNode } from 'react';
import type { WorkspaceDensity } from './types';

interface WorkspaceShellProps {
  density: WorkspaceDensity;
  rightOpen: boolean;
  children: ReactNode;
  onCloseRight: () => void;
}

export function WorkspaceShell({
  density,
  rightOpen,
  children,
  onCloseRight,
}: WorkspaceShellProps) {
  return (
    <div className={`workspace-shell density-${density.toLowerCase()} ${rightOpen ? 'right-open' : ''}`}>
      {children}
      <button
        type="button"
        className="workspace-right-backdrop"
        onClick={onCloseRight}
        aria-label="콘솔 닫기"
      />
    </div>
  );
}
