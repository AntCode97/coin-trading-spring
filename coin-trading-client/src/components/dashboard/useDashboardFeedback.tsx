import { useCallback, useEffect, useRef, useState } from 'react';

export type FeedbackType = 'success' | 'error' | 'info';

export interface ConfirmActionOptions {
  title: string;
  description?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
}

interface PendingConfirm extends ConfirmActionOptions {
  id: number;
  resolve: (accepted: boolean) => void;
}

interface ToastMessage {
  id: number;
  message: string;
  type: FeedbackType;
}

const TOAST_DURATION_MS = 4200;

export function useDashboardFeedback() {
  const sequenceRef = useRef(0);
  const timeoutHandlesRef = useRef<number[]>([]);

  const [pendingConfirm, setPendingConfirm] = useState<PendingConfirm | null>(null);
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const dismissToast = useCallback((toastId: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== toastId));
  }, []);

  const notify = useCallback((message: string, type: FeedbackType = 'info') => {
    const toastId = ++sequenceRef.current;
    setToasts((current) => [...current, { id: toastId, message, type }]);

    const timeoutHandle = window.setTimeout(() => {
      dismissToast(toastId);
    }, TOAST_DURATION_MS);

    timeoutHandlesRef.current.push(timeoutHandle);
  }, [dismissToast]);

  const confirmAction = useCallback((options: ConfirmActionOptions): Promise<boolean> => {
    return new Promise<boolean>((resolve) => {
      const confirmId = ++sequenceRef.current;
      setPendingConfirm({
        ...options,
        id: confirmId,
        resolve,
      });
    });
  }, []);

  const settleConfirm = useCallback((accepted: boolean) => {
    setPendingConfirm((current) => {
      if (!current) {
        return null;
      }

      current.resolve(accepted);
      return null;
    });
  }, []);

  useEffect(() => {
    return () => {
      timeoutHandlesRef.current.forEach((handle) => window.clearTimeout(handle));
      timeoutHandlesRef.current = [];
    };
  }, []);

  const feedbackUi = (
    <>
      <div className="dashboard-toast-stack" aria-live="polite">
        {toasts.map((toast) => (
          <div key={toast.id} className={`dashboard-toast dashboard-toast-${toast.type}`}>
            <span className="dashboard-toast-message">{toast.message}</span>
            <button
              type="button"
              className="dashboard-toast-close"
              onClick={() => dismissToast(toast.id)}
              aria-label="알림 닫기"
            >
              ×
            </button>
          </div>
        ))}
      </div>

      {pendingConfirm && (
        <div
          className="dashboard-confirm-overlay"
          role="presentation"
          onClick={() => settleConfirm(false)}
        >
          <div
            className={`dashboard-confirm-modal ${pendingConfirm.danger ? 'danger' : ''}`}
            role="dialog"
            aria-modal="true"
            aria-labelledby={`dashboard-confirm-title-${pendingConfirm.id}`}
            onClick={(event) => event.stopPropagation()}
          >
            <h3
              id={`dashboard-confirm-title-${pendingConfirm.id}`}
              className="dashboard-confirm-title"
            >
              {pendingConfirm.title}
            </h3>
            {pendingConfirm.description && (
              <p className="dashboard-confirm-description">{pendingConfirm.description}</p>
            )}
            <div className="dashboard-confirm-actions">
              <button
                type="button"
                className="dashboard-confirm-cancel"
                onClick={() => settleConfirm(false)}
              >
                {pendingConfirm.cancelLabel ?? '취소'}
              </button>
              <button
                type="button"
                className={`dashboard-confirm-accept ${pendingConfirm.danger ? 'danger' : ''}`}
                onClick={() => settleConfirm(true)}
              >
                {pendingConfirm.confirmLabel ?? '확인'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );

  return {
    confirmAction,
    notify,
    feedbackUi,
  };
}
