import { useUiStore } from './ui-store';
import styles from './StatusBar.module.css';

/**
 * Bottom status strip.
 *
 * TODO(M2): drive every item from real state — connection health, token usage,
 * the last applied event seq and the active approval mode. Until then these are
 * placeholders that match the approved design.
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
