import { useEffect, useRef } from 'react';
import { useUiStore } from '@/app/ui-store';
import { Icon } from '@/shared/ui/Icon';
import styles from './DiffViewer.module.css';

/**
 * Full-width diff overlay.
 *
 * Rendered inside the chat column (not a modal) so the context panel stays
 * usable — clicking another file swaps the content instead of stacking viewers.
 * Escape closes it and focus moves back to the chat.
 */
export function DiffViewer() {
  const file = useUiStore((state) => state.openDiff);
  const close = useUiStore((state) => state.closeDiffViewer);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!file) return;

    closeRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [file, close]);

  if (!file) return null;

  return (
    <div className={styles.viewer} role="region" aria-label="Diff 预览">
      <div className={styles.head}>
        <div className={styles.fileHead}>
          <span className={styles.path}>{file.path}</span>
          <span className={styles.stat}>
            <b className={styles.add}>+{file.additions}</b>
            <b className={styles.del}>-{file.deletions}</b>
          </span>
        </div>
        <button ref={closeRef} type="button" className={styles.close} aria-label="关闭" onClick={close}>
          <Icon name="close" size={16} />
        </button>
      </div>

      <div className={styles.body}>
        {file.lines.length === 0 ? (
          <p className={styles.empty}>完整改动需要连接后端读取该文件后展开。</p>
        ) : (
          <pre className={styles.diff}>
            {file.lines.map((line, index) => (
              <span key={index} className={`${styles.line} ${styles[line.kind]}`}>
                {line.text}
              </span>
            ))}
          </pre>
        )}
      </div>
    </div>
  );
}
