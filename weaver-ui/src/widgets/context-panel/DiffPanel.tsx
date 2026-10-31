import { useUiStore } from '@/app/ui-store';
import { demoDiffFiles } from '@/entities/diff/model';
import styles from './ContextPanel.module.css';

/**
 * 工作区中的变更文件。
 *
 * 点击文件会打开浮层查看器，而不会向聊天中追加内容，
 * 使 transcript 保持为只追加的事件记录。
 * TODO(M2): 用工作区 diff 接口替换 `demoDiffFiles`。
 */
export function DiffPanel() {
  const openDiffViewer = useUiStore((state) => state.openDiffViewer);

  return (
    <>
      <div className={styles.panelHead}>
        <span>工作区改动</span>
        <span className={styles.tiny}>{demoDiffFiles.length} files</span>
      </div>

      {demoDiffFiles.map((file) => (
        <button
          key={file.path}
          type="button"
          className={styles.diffFile}
          onClick={() => openDiffViewer(file)}
        >
          <span className={styles.diffPath}>{file.path}</span>
          <span className={styles.diffStat}>
            <b className={styles.add}>+{file.additions}</b>
            <b className={styles.del}>-{file.deletions}</b>
          </span>
        </button>
      ))}
    </>
  );
}
