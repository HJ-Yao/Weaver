import { useEffect } from 'react';
import { useUiStore } from '@/app/ui-store';
import styles from './Toast.module.css';

/** 临时状态消息。使用礼貌播报，且从不抢占焦点。 */
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
