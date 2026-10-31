import { useEffect, useRef } from 'react';
import { useSessionStore } from '@/entities/session/store';
import type { MessageItem } from '@/entities/session/model';
import { ApprovalCard } from '@/features/approval/ApprovalCard';
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
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const list = listRef.current;
    if (list) list.scrollTop = list.scrollHeight;
  }, [transcript]);

  return (
    <div className={styles.messages} ref={listRef} role="log" aria-label="对话消息" aria-live="polite">
      {transcript.map((item) => {
        if (item.kind === 'message') return <MessageBubble key={item.id} item={item} />;
        if (item.kind === 'tool') return <ToolCard key={item.id} item={item} />;
        return <ApprovalCard key={item.id} item={item} />;
      })}
    </div>
  );
}

function MessageBubble({ item }: { item: MessageItem }) {
  const isUser = item.role === 'user';
  return (
    <article className={`${styles.message} ${isUser ? styles.user : styles.assistant}`}>
      {!isUser && (
        <div className={styles.head}>
          <span>Weaver</span>
          {item.seq !== undefined && <span className={styles.seq}>seq {item.seq}</span>}
        </div>
      )}
      <div className={`${styles.bubble} ${item.streaming ? styles.streaming : ''}`}>
        {item.content}
      </div>
    </article>
  );
}
