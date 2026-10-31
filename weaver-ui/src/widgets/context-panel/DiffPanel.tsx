import { useUiStore } from '@/app/ui-store';
import { demoDiffFiles } from '@/entities/diff/model';
import styles from './ContextPanel.module.css';

/**
 * Changed files in the workspace.
 *
 * Clicking a file opens the overlay viewer instead of appending to the chat:
 * the transcript stays an append-only event record.
 * TODO(M2): replace `demoDiffFiles` with the workspace diff endpoint.
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
