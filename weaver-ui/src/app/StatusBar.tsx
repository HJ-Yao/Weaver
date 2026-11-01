import { useEffect, useState } from 'react';
import { useSessionStore } from '@/entities/session/store';
import type { ServerStatusDto } from '@/shared/api/status';
import { statusApi } from '@/shared/api/status';
import styles from './StatusBar.module.css';
import { useUiStore } from './ui-store';

/** 服务健康状态的轮询间隔。 */
const STATUS_POLL_INTERVAL_MS = 15_000;

type ConnectionState =
  | { phase: 'connecting' }
  | { phase: 'connected'; status: ServerStatusDto }
  | { phase: 'disconnected'; error: string };

/**
 * 底部状态条。
 *
 * 连接与存储信息来自 `/status`，事件序号来自当前会话快照；token 用量尚未接入，
 * 保持明确的空值而不是展示估算数字。
 */
export function StatusBar() {
  const approvalMode = useUiStore((state) => state.approvalMode);
  const lastSeq = useSessionStore((state) => state.lastSeq);
  const [connection, setConnection] = useState<ConnectionState>({ phase: 'connecting' });

  useEffect(() => {
    let mounted = true;

    const refreshStatus = () => {
      void statusApi
        .get()
        .then((status) => {
          if (mounted) setConnection({ phase: 'connected', status });
        })
        .catch((error: unknown) => {
          if (mounted) {
            setConnection({ phase: 'disconnected', error: readableError(error) });
          }
        });
    };

    refreshStatus();
    const timer = window.setInterval(refreshStatus, STATUS_POLL_INTERVAL_MS);

    return () => {
      mounted = false;
      window.clearInterval(timer);
    };
  }, []);

  const isConnected = connection.phase === 'connected';
  const connectionLabel =
    connection.phase === 'connecting'
      ? '连接中…'
      : connection.phase === 'connected'
        ? '已连接'
        : '未连接';
  const storageLabel = isConnected
    ? `${connection.status.storage} · ${connection.status.wal ? 'WAL' : 'journal'}`
    : '—';
  const dotClass = isConnected ? styles.ok : connection.phase === 'disconnected' ? styles.error : '';

  return (
    <footer className={styles.statusbar}>
      <span className={`${styles.dot} ${dotClass}`} aria-hidden="true" />
      <span title={connection.phase === 'disconnected' ? connection.error : undefined}>
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
