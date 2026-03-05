import type { ReactNode } from 'react';

interface SwitchButtonProps {
  label: ReactNode;
  checked: boolean;
  onToggle: () => void;
  disabled?: boolean;
  hint?: ReactNode;
  className?: string;
}

export function SwitchButton({
  label,
  checked,
  onToggle,
  disabled = false,
  hint,
  className,
}: SwitchButtonProps) {
  return (
    <button
      type="button"
      className={`workspace-switch ${checked ? 'on' : 'off'}${disabled ? ' disabled' : ''}${className ? ` ${className}` : ''}`}
      aria-pressed={checked}
      disabled={disabled}
      onClick={onToggle}
    >
      <div className="workspace-switch-copy">
        <strong>{label}</strong>
        {hint ? <small>{hint}</small> : null}
      </div>
      <span className={`workspace-switch-pill ${checked ? 'on' : 'off'}`}>{checked ? 'ON' : 'OFF'}</span>
    </button>
  );
}
