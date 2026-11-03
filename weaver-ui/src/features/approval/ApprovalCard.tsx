import type { ApprovalItem } from '@/entities/session/model';
import { useSessionStore } from '@/entities/session/store';
import styles from './ApprovalCard.module.css';

const STATUS_LABEL = {
  pending: '等待审批',
  approved: '已批准',
  rejected: '已拒绝',
} as const;

/**
 * 被阻塞的工具调用发起的待处理审批请求。
 *
 * 完成决策后由会话 store 将卡片从 transcript 移除；审计事实仍保留在事件日志与回放中。
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
        <span className={styles.hint}>批准结果会写入会话事件日志</span>
      </div>
    </div>
  );
}
