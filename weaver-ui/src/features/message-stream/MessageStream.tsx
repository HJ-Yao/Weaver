import { useEffect, useRef, useState } from 'react';
import { useUiStore } from '@/app/ui-store';
import { useSessionStore } from '@/entities/session/store';
import type { MessageItem } from '@/entities/session/model';
import { ApprovalCard } from '@/features/approval/ApprovalCard';
import { Markdown } from '@/features/markdown/Markdown';
import { ToolCard } from '@/features/tool-call/ToolCard';
import styles from './MessageStream.module.css';

/**
 * 按顺序渲染 transcript。
 *
 * 列表负责滚动：内容变化后固定到底部，这是流式运行期间预期的行为。
 * TODO(M2): 用户向上滚动查看历史时停止固定在底部。
 */
export function MessageStream() {
  const transcript = useSessionStore((state) => state.transcript);
  const running = useSessionStore((state) => state.running);
  const deleteTurn = useSessionStore((state) => state.deleteTurn);
  const showToast = useUiStore((state) => state.showToast);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const list = listRef.current;
    if (list) list.scrollTop = list.scrollHeight;
  }, [transcript]);

  return (
    <div
      className={styles.messages}
      ref={listRef}
      role="log"
      aria-label="对话消息"
      aria-live="polite"
    >
      {transcript.map((item) => {
        // 工具与审批共用同一个 callId，因此 React key 必须带上类型前缀。
        if (item.kind === 'message') {
          return (
            <MessageBubble
              key={`message-${item.id}`}
              item={item}
              running={running}
              deleteTurn={deleteTurn}
              showToast={showToast}
            />
          );
        }
        if (item.kind === 'tool') return <ToolCard key={`tool-${item.id}`} item={item} />;
        return <ApprovalCard key={`approval-${item.id}`} item={item} />;
      })}
    </div>
  );
}

interface MessageBubbleProps {
  item: MessageItem;
  running: boolean;
  deleteTurn: (seq: number) => Promise<boolean>;
  showToast: (message: string) => void;
}

function MessageBubble({ item, running, deleteTurn, showToast }: MessageBubbleProps) {
  const isUser = item.role === 'user';
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    if (!confirming) return;
    const timer = window.setTimeout(() => setConfirming(false), 3000);
    return () => window.clearTimeout(timer);
  }, [confirming]);

  const handleDelete = () => {
    if (item.seq === undefined) return;
    if (!confirming) {
      setConfirming(true);
      return;
    }

    setConfirming(false);
    void deleteTurn(item.seq)
      .then((deleted) => {
        if (!deleted) showToast('删除失败：请先停止当前运行');
      })
      .catch(() => showToast('删除失败：请先停止当前运行'));
  };

  return (
    <article
      className={`${styles.message} ${isUser ? styles.user : styles.assistant}`}
      title={item.seq === undefined ? undefined : `seq ${item.seq}`}
    >
      {isUser && item.seq !== undefined && !running && (
        <button
          type="button"
          className={styles.deleteButton}
          onClick={handleDelete}
          aria-label={confirming ? '确认删除' : '删除这一轮及之后'}
        >
          {confirming ? '确认删除' : '删除这一轮及之后'}
        </button>
      )}
      {!isUser && (
        <div className={styles.head}>
          <span>Weaver</span>
        </div>
      )}
      <div className={`${styles.bubble} ${item.streaming ? styles.streaming : ''}`}>
        {isUser ? item.content : <Markdown content={item.content} />}
        {item.stopped && <span className={styles.stopped}>已停止</span>}
      </div>
    </article>
  );
}
