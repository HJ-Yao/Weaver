import { useShallow } from 'zustand/react/shallow';
import type { ApprovalItem } from '@/entities/session/model';
import { useSessionStore } from '@/entities/session/store';
import styles from './ContextPanel.module.css';

/**
 * Approval queue.
 *
 * Pending items are actionable here *and* inline in the transcript; both call
 * the same store action, so the two surfaces cannot disagree.
 */
export function ApprovalsPanel() {
  const approvals = useSessionStore(
    useShallow((state) =>
      state.transcript.filter((item): item is ApprovalItem => item.kind === 'approval'),
    ),
  );
  const resolveApproval = useSessionStore((state) => state.resolveApproval);

  const pending = approvals.filter((approval) => approval.status === 'pending');
  const resolved = approvals.filter((approval) => approval.status !== 'pending');

  return (
    <>
      <div className={styles.panelHead}>
        <span>待审批</span>
        <span className={styles.tiny}>{pending.length}</span>
      </div>

      {pending.map((approval) => (
        <div key={approval.id} className={styles.approvalMini}>
          <div className={styles.miniTitle}>
            <span className={styles.rowName}>{approval.toolName}</span>
            <span className={styles.risk}>{approval.reversibility}</span>
          </div>
          <pre className={styles.miniBody}>{approval.argumentsJson}</pre>
          <div className={styles.miniActions}>
            <button
              type="button"
              className={styles.approve}
              onClick={() => resolveApproval(approval.id, true)}
            >
              批准
            </button>
            <button
              type="button"
              className={styles.reject}
              onClick={() => resolveApproval(approval.id, false)}
            >
              拒绝
            </button>
          </div>
        </div>
      ))}

      {pending.length === 0 && <p className={styles.empty}>没有等待中的审批。</p>}

      {resolved.length > 0 && (
        <>
          <div className={styles.panelHead}>
            <span>已处理</span>
          </div>
          {resolved.map((approval) => (
            <div key={approval.id} className={`${styles.row} ${styles.muted}`}>
              <span className={styles.rowName}>{approval.toolName}</span>
              <span className={styles.rowMeta}>
                {approval.status === 'approved' ? '已批准' : '已拒绝'}
              </span>
            </div>
          ))}
        </>
      )}
    </>
  );
}
