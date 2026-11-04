import { useState, type KeyboardEvent } from 'react';
import type { InboxEntryDto, InboxTargetDto } from '@/shared/api/sessions';
import styles from './Composer.module.css';

/** 待发送区所需数据与操作。 */
export interface QueuedMessagesProps {
  /** 按服务端到达顺序展示的信箱条目。 */
  entries: InboxEntryDto[];
  /** 撤回指定条目。 */
  onRemove: (id: string) => void;
  /** 保存指定条目的新文本。 */
  onEdit: (id: string, content: string) => void;
}

const TARGET_LABELS: Record<InboxTargetDto, string> = {
  STEP: '插话',
  TURN: '排队',
};

/**
 * 运行中待发送输入列表。
 *
 * 条目文本来自服务端 SSE 快照；编辑只维护当前行的临时草稿，
 * 按 Enter 提交给会话 store，按 Esc 放弃，不改变其它条目的编辑状态。
 */
export function QueuedMessages({ entries, onRemove, onEdit }: QueuedMessagesProps) {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState('');

  if (entries.length === 0) return null;

  const beginEdit = (entry: InboxEntryDto) => {
    setEditingId(entry.id);
    setEditDraft(entry.content);
  };

  const saveEdit = (id: string) => {
    const text = editDraft.trim();
    if (!text) return;
    onEdit(id, text);
    setEditingId(null);
  };

  const onEditorKeyDown = (event: KeyboardEvent<HTMLInputElement>, id: string) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      setEditingId(null);
      return;
    }
    if (event.key !== 'Enter' || event.nativeEvent.isComposing) return;
    event.preventDefault();
    saveEdit(id);
  };

  return (
    <section className={styles.queued} aria-label="待发送">
      <div className={styles.queuedHeader}>
        <span>待发送</span>
        <span className={styles.queuedCount}>{entries.length}</span>
      </div>
      <ul className={styles.queuedList}>
        {entries.map((entry) => (
          <li className={styles.queuedItem} key={entry.id}>
            <span
              className={`${styles.queuedBadge} ${
                entry.target === 'STEP' ? styles.queuedBadgeStep : styles.queuedBadgeTurn
              }`}
            >
              {TARGET_LABELS[entry.target]}
            </span>
            {editingId === entry.id ? (
              <input
                className={styles.queuedEditor}
                value={editDraft}
                aria-label="编辑待发送消息"
                autoFocus
                onChange={(event) => setEditDraft(event.target.value)}
                onKeyDown={(event) => onEditorKeyDown(event, entry.id)}
              />
            ) : (
              <span className={styles.queuedText} title={entry.content}>
                {entry.content}
              </span>
            )}
            <button
              type="button"
              className={styles.queuedAction}
              title="编辑"
              aria-label="编辑"
              onClick={() => beginEdit(entry)}
            >
              ✎
            </button>
            <button
              type="button"
              className={styles.queuedAction}
              title="撤回"
              aria-label="撤回"
              onClick={() => onRemove(entry.id)}
            >
              ✕
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
