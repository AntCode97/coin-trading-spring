export type EngineFilter = 'ALL' | 'SCALP' | 'SWING' | 'POSITION';

interface EngineFilterPillsProps {
  value: EngineFilter;
  onChange: (filter: EngineFilter) => void;
}

const FILTERS: { key: EngineFilter; label: string }[] = [
  { key: 'ALL', label: '전체' },
  { key: 'SCALP', label: '초단타' },
  { key: 'SWING', label: '스윙' },
  { key: 'POSITION', label: '포지션' },
];

export function EngineFilterPills({ value, onChange }: EngineFilterPillsProps) {
  return (
    <div className="wp-engine-pills">
      {FILTERS.map(({ key, label }) => (
        <button
          key={key}
          type="button"
          className={`wp-engine-pill ${value === key ? 'active' : ''}`}
          onClick={() => onChange(key)}
        >
          {label}
        </button>
      ))}
    </div>
  );
}
