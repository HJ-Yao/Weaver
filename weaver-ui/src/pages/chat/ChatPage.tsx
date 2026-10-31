import { useSessionStore } from '@/entities/session/store';
import { Composer } from '@/features/composer/Composer';
import { DiffViewer } from '@/features/diff-viewer/DiffViewer';
import { MessageStream } from '@/features/message-stream/MessageStream';
import { ChatHeader } from './ChatHeader';
import styles from './ChatPage.module.css';

/**
 * 对话页面。
 *
 * 页面由三个纵向区域组成：页头（自动高度）、transcript（填充并滚动）
 * 和输入区（自动高度）。diff 查看器作为此区域内的浮层，
 * 只覆盖对话，不遮挡 rail 或上下文面板。
 */
export function ChatPage() {
  const activeSessionId = useSessionStore((state) => state.activeSessionId);
  const status = useSessionStore((state) => state.status);
  const error = useSessionStore((state) => state.error);
  const createSession = useSessionStore((state) => state.createSession);

  const emptyState =
    status === 'error' ? (
      <p className={styles.stateError} role="alert">
        {error ?? '会话加载失败'}
      </p>
    ) : status === 'ready' ? (
      <div className={styles.emptyState}>
        <p className={styles.emptyTitle}>选择一个会话</p>
        <button
          type="button"
          className={styles.emptyAction}
          onClick={() => void createSession()}
        >
          ＋ 新建会话
        </button>
      </div>
    ) : (
      <p className={styles.stateMessage}>加载中…</p>
    );

  return (
    <section className={styles.chat} aria-label="对话">
      <ChatHeader />
      {!activeSessionId ? (
        emptyState
      ) : (
        <>
          <MessageStream />
          <Composer />
          <DiffViewer />
        </>
      )}
    </section>
  );
}
