import { useSessionStore } from '@/entities/session/store';
import { Composer } from '@/features/composer/Composer';
import { DiffViewer } from '@/features/diff-viewer/DiffViewer';
import { MessageStream } from '@/features/message-stream/MessageStream';
import { ChatHeader } from './ChatHeader';
import styles from './ChatPage.module.css';

/**
 * Conversation screen.
 *
 * Three stacked regions: header (auto), transcript (fills, scrolls) and
 * composer (auto). The diff viewer is an overlay inside this section so it
 * covers the conversation but never the rail or the context panel.
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
