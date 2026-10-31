import type { ToolCallItem, ToolStatus } from '@/entities/session/model';
import { useSessionStore } from '@/entities/session/store';
import { formatDuration } from '@/shared/lib/format';
import styles from './ToolCard.module.css';

const STATUS_LABEL: Record<ToolStatus, string> = {
  running: '运行中',
  done: '完成',
  failed: '失败',
};

/**
 * Inline tool invocation.
 *
 * The whole header is the toggle: it is a real `<button>` with
 * `aria-expanded`, so keyboard and screen-reader users get the same behaviour
 * as a click.
 */
export function ToolCard({ item }: { item: ToolCallItem }) {
  const toggleExpanded = useSessionStore((state) => state.toggleToolExpanded);

  return (
    <div className={styles.card}>
      <button
        type="button"
        className={styles.head}
        aria-expanded={item.expanded}
        onClick={() => toggleExpanded(item.id)}
      >
        <span className={styles.icon} aria-hidden="true">
          ◈
        </span>
        <span className={styles.name}>{item.toolName}</span>
        <span className={styles.args}>{item.argumentsJson}</span>
        <span className={`${styles.status} ${styles[item.status]}`}>{STATUS_LABEL[item.status]}</span>
        {item.durationMs !== undefined && (
          <span className={styles.time}>{formatDuration(item.durationMs)}</span>
        )}
        <span className={styles.caret} aria-hidden="true">
          ▾
        </span>
      </button>

      {item.expanded && <pre className={styles.body}>{item.resultText ?? '（无输出）'}</pre>}
    </div>
  );
}
