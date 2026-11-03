import { useEffect } from 'react';
import { useSessionStore } from '@/entities/session/store';
import { statusApi } from '@/shared/api/status';
import styles from './StatusBar.module.css';
import { useServerStatusStore } from './server-status-store';
import { useUiStore } from './ui-store';

/** 服务健康状态的轮询间隔。 */
const STATUS_POLL_INTERVAL_MS = 15_000;

/**
 * 底部状态条。
 *
 * 连接与存储信息来自 `/status`，事件序号来自当前会话快照；token 用量尚未接入，
 * 保持明确的空值而不是展示估算数字。
 */
export function StatusBar() {
  const approvalMode = useUiStore((state) => state.approvalMode);
  const lastSeq = useSessionStore((state) => state.lastSeq);
  const phase = useServerStatusStore((state) => state.phase);
  const serverStatus = useServerStatusStore((state) => state.status);
  const connectionError = useServerStatusStore((state) => state.error);
  const setConnected = useServerStatusStore((state) => state.setConnected);
  const setDisconnected = useServerStatusStore((state) => state.setDisconnected);
  const setConnecting = useServerStatusStore((state) => state.setConnecting);

  useEffect(() => {
    let mounted = true;

    setConnecting();

    const refreshStatus = () => {
      void statusApi
        .get()
        .then((status) => {
          if (mounted) setConnected(status);
        })
        .catch((error: unknown) => {
          if (mounted) {
            setDisconnected(readableError(error));
          }
        });
    };

    refreshStatus();
    const timer = window.setInterval(refreshStatus, STATUS_POLL_INTERVAL_MS);

    return () => {
      mounted = false;
      window.clearInterval(timer);
    };
  }, [setConnected, setConnecting, setDisconnected]);

  const isConnected = phase === 'connected' && serverStatus !== null;
  const connectionLabel =
    phase === 'connecting'
      ? '连接中…'
      : phase === 'connected'
        ? '已连接'
        : '未连接';
  const storageLabel = isConnected
    ? `${serverStatus.storage} · ${serverStatus.wal ? 'WAL' : 'journal'}`
    : '—';
  const dotClass = isConnected ? styles.ok : phase === 'disconnected' ? styles.error : '';

  return (
    <footer className={styles.statusbar}>
      <span className={`${styles.dot} ${dotClass}`} aria-hidden="true" />
      <span title={phase === 'disconnected' ? (connectionError ?? undefined) : undefined}>
        {connectionLabel}
      </span>
      <span className={styles.sep} aria-hidden="true" />
      <span title="模型用量统计尚未接入（M2）">tokens —</span>
      <span className={styles.sep} aria-hidden="true" />
      <span>seq {lastSeq || '—'}</span>
      <span className={styles.spacer} />
      <span>{storageLabel}</span>
      <span className={styles.sep} aria-hidden="true" />
      <span>审批 {approvalMode}</span>
    </footer>
  );
}

/** 将未知的请求失败转换为适合在状态栏提示的短文本。 */
function readableError(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  return message || '未知错误';
}
