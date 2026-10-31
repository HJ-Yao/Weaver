import { useEffect, useRef } from 'react';
import { useUiStore } from '@/app/ui-store';
import { Icon } from '@/shared/ui/Icon';
import styles from './DiffViewer.module.css';

/**
 * 全宽 diff 浮层。
 *
 * 渲染在聊天列内部而非模态框中，因此上下文面板仍可使用；
 * 点击另一个文件会替换内容，不会叠加查看器。按 Escape 关闭并把焦点还给聊天区。
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
