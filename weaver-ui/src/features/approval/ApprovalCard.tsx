import type { ApprovalItem } from '@/entities/session/model';
import { useSessionStore } from '@/entities/session/store';
import styles from './ApprovalCard.module.css';

const STATUS_LABEL = {
  pending: '等待审批',
  approved: '已批准',
  rejected: '已拒绝',
} as const;

/**
 * Approval request raised by a blocked tool call.
 *
 * The card stays in the transcript after a decision so the conversation keeps
 * an audit trail; only the actions are disabled.
 */
export function ApprovalCard({ item }: { item: ApprovalItem }) {
  const resolveApproval = useSessionStore((state) => state.resolveApproval);
  const pending = item.status === 'pending';

  return (
    <div className={`${styles.card} ${styles[item.status]}`}>
      <div className={styles.head}>
        <span className={`${styles.badge} ${styles[`badge_${item.status}`]}`}>
          {STATUS_LABEL[item.status]}
        </span>
        <span className={styles.name}>{item.toolName}</span>
        <span className={styles.risk}>{item.reversibility}</span>
      </div>

      <pre className={styles.body}>{item.argumentsJson}</pre>

      <div className={styles.actions}>
        <button
          type="button"
          className={styles.approve}
          disabled={!pending}
          onClick={() => resolveApproval(item.id, true)}
        >
          批准
        </button>
        <button
          type="button"
          className={styles.reject}
          disabled={!pending}
          onClick={() => resolveApproval(item.id, false)}
        >
          拒绝
        </button>
        <span className={styles.hint}>
          {pending ? '批准后本会话内同类操作将免问' : '决策已记录'}
        </span>
      </div>
    </div>
  );
}
