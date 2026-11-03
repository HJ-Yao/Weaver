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
 * 无活动会话且加载完成时，页面改为欢迎区和输入区，不渲染聊天页头与消息列表。
 */
export function ChatPage() {
  const activeSessionId = useSessionStore((state) => state.activeSessionId);
  const status = useSessionStore((state) => state.status);
  const error = useSessionStore((state) => state.error);

  if (!activeSessionId) {
    return (
      <section className={`${styles.chat} ${styles.emptyChat}`} aria-label="对话">
        {status === 'error' ? (
          <p className={styles.stateError} role="alert">
            {error ?? '会话加载失败'}
          </p>
        ) : status === 'ready' ? (
          <>
            <section className={styles.welcome}>
              <h1 className={styles.welcomeTitle}>想构建点什么？</h1>
              <p className={styles.welcomeHint}>
                描述一个任务，或直接提问；第一句话会自动创建会话。
              </p>
            </section>
            <Composer />
          </>
        ) : (
          <p className={styles.stateMessage}>加载中…</p>
        )}
      </section>
    );
  }

  return (
    <section className={styles.chat} aria-label="对话">
      <ChatHeader />
      <MessageStream />
      <Composer />
      <DiffViewer />
    </section>
  );
}
