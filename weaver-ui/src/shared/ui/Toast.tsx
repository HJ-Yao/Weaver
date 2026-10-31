import { useEffect } from 'react';
import { useUiStore } from '@/app/ui-store';
import styles from './Toast.module.css';

/** Transient status message. Announced politely, never steals focus. */
export function Toast() {
  const toast = useUiStore((state) => state.toast);
  const dismiss = useUiStore((state) => state.dismissToast);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(dismiss, 2600);
    return () => window.clearTimeout(timer);
  }, [toast, dismiss]);

  return (
    <div className={`${styles.toast} ${toast ? styles.show : ''}`} role="status" aria-live="polite">
      {toast}
    </div>
  );
}
