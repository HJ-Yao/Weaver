import { useUiStore } from './ui-store';
import styles from './StatusBar.module.css';

/**
 * 底部状态条。
 *
 * TODO(M2): 所有项目改由真实状态驱动，包括连接健康度、token 用量、
 * 最后应用的事件 seq 和当前审批模式。在此之前，这些值是与已确认设计一致的占位项。
 */
export function StatusBar() {
  const approvalMode = useUiStore((state) => state.approvalMode);

  return (
    <footer className={styles.statusbar}>
      <span className={`${styles.dot} ${styles.ok}`} aria-hidden="true" />
      <span>已连接</span>
      <span className={styles.sep} aria-hidden="true" />
      <span>tokens 3.2k</span>
      <span className={styles.sep} aria-hidden="true" />
      <span>seq 9</span>
      <span className={styles.spacer} />
      <span>sqlite · WAL</span>
      <span className={styles.sep} aria-hidden="true" />
      <span>审批 {approvalMode}</span>
    </footer>
  );
}
