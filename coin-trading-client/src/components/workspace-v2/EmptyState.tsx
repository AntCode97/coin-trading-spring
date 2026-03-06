interface EmptyStateProps {
  variant?: 'loading' | 'empty' | 'error';
  message?: string;
}

export function EmptyState({ variant = 'empty', message }: EmptyStateProps) {
  const defaultMessage =
    variant === 'loading' ? '데이터 로딩 중' :
    variant === 'error' ? '연결 오류' :
    '데이터 없음';

  return (
    <div className={`wp-empty-state wp-empty-${variant}`}>
      <div className="wp-empty-indicator" />
      <span>{message ?? defaultMessage}</span>
    </div>
  );
}
