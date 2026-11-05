import { useEffect, useState } from 'react';
import { useUiStore } from '@/app/ui-store';
import type { DiffFile } from '@/entities/diff/model';
import { useSessionStore } from '@/entities/session/store';
import { workspaceApi } from '@/shared/api/workspace';
import styles from './ContextPanel.module.css';

/** Diff 面板当前展示的数据状态。 */
type DiffStatus = 'loading' | 'ready' | 'error';

/**
 * 工作区中的变更文件。
 *
 * 点击文件会打开浮层查看器，而不会向聊天中追加内容，
 * 使 transcript 保持为只追加的事件记录。挂载时读取工作区接口。
 */
export function DiffPanel() {
  const openDiffViewer = useUiStore((state) => state.openDiffViewer);
  const activeSessionId = useSessionStore((state) => state.activeSessionId);
  const [files, setFiles] = useState<DiffFile[]>([]);
  const [status, setStatus] = useState<DiffStatus>('loading');
  const [error, setError] = useState<string | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setStatus('loading');
    setError(null);

    void workspaceApi
      .diff(activeSessionId ?? undefined)
      .then((result) => {
        if (cancelled) return;
        setFiles(result.files);
        setTruncated(result.truncated);
        setStatus('ready');
      })
      .catch((loadError: unknown) => {
        if (cancelled) return;
        const message = loadError instanceof Error ? loadError.message : String(loadError);
        setError(`工作区改动加载失败：${message || '未知错误'}`);
        setStatus('error');
      });

    return () => {
      cancelled = true;
    };
  }, [activeSessionId, reloadKey]);

  return (
    <>
      <div className={styles.panelHead}>
        <span>工作区改动</span>
        <span className={styles.tiny}>
          {files.length} files{truncated ? ' · 已截断' : ''}
        </span>
      </div>

      {status === 'loading' && <p className={styles.empty}>加载中…</p>}

      {status === 'error' && (
        <div>
          <p className={styles.empty}>{error}</p>
          <button
            type="button"
            className={styles.action}
            onClick={() => setReloadKey((value) => value + 1)}
          >
            重试
          </button>
        </div>
      )}

      {status === 'ready' && files.length === 0 && (
        <p className={styles.empty}>工作区没有未提交改动</p>
      )}

      {status === 'ready' &&
        files.map((file) => (
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
