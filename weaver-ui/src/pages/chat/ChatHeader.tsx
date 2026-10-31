import { useSessionStore } from '@/entities/session/store';
import { useUiStore } from '@/app/ui-store';
import styles from './ChatHeader.module.css';

/**
 * 对话页头：标题、运行状态指示和页面级操作。
 *
 * TODO(M2): 导出操作尚无后端，目前只显示状态。
 */
export function ChatHeader() {
  const title = useSessionStore(
    (state) => state.sessions.find((session) => session.id === state.activeSessionId)?.title,
  );
  const running = useSessionStore((state) => state.running);
  const setContextTab = useUiStore((state) => state.setContextTab);
  const rightCollapsed = useUiStore((state) => state.rightCollapsed);
  const toggleRight = useUiStore((state) => state.toggleRight);
  const showToast = useUiStore((state) => state.showToast);

  const openReplay = () => {
    if (rightCollapsed) toggleRight();
    setContextTab('replay');
  };

  return (
    <header className={styles.header}>
      <div>
        <h1 className={styles.title}>{title ?? '新会话'}</h1>
        <p className={styles.status}>
          <span className={`${styles.dot} ${running ? styles.live : ''}`} aria-hidden="true" />
          {running ? '运行中' : '空闲'} · 3.2k tokens
        </p>
      </div>

      <div className={styles.actions}>
        <button type="button" className={styles.action} onClick={openReplay}>
          回放
        </button>
        <button
          type="button"
          className={styles.action}
          onClick={() => showToast('会话导出将在后续版本接入。')}
        >
          导出
        </button>
      </div>
    </header>
  );
}
