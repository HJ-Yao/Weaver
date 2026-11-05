import { useCallback, useEffect, useRef, useState } from 'react';
import { workspaceApi, type WorkspaceDirectoryDto } from '@/shared/api/workspace';
import styles from './WorkspacePicker.module.css';

interface WorkspacePickerProps {
  /** 控制模态框是否打开。 */
  open: boolean;
  /** 用户确认当前目录时触发。 */
  onSelect: (path: string) => void;
  /** 用户取消、关闭或按 Escape 时触发。 */
  onClose: () => void;
}

/**
 * 服务端目录浏览器。
 *
 * 每次请求成功后都以服务端返回的绝对路径作为当前位置，避免前端自行拼接
 * Windows 盘符或 Unix 根路径。导航失败时保留最后一次成功读取的目录。
 */
export function WorkspacePicker({ open, onSelect, onClose }: WorkspacePickerProps) {
  const [directory, setDirectory] = useState<WorkspaceDirectoryDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const requestId = useRef(0);

  const browse = useCallback(async (path?: string) => {
    const currentRequest = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const result = path ? await workspaceApi.browse(path) : await workspaceApi.browse();
      if (currentRequest !== requestId.current) return;
      setDirectory(result);
    } catch {
      if (currentRequest !== requestId.current) return;
      setError('无法读取该目录');
    } finally {
      if (currentRequest === requestId.current) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;

    if (open) {
      setDirectory(null);
      setError(null);
      if (!dialog.open) {
        if (typeof dialog.showModal === 'function') {
          dialog.showModal();
        } else {
          dialog.setAttribute('open', '');
        }
      }
      cancelRef.current?.focus();
      void browse();
    } else {
      requestId.current += 1;
      if (dialog.open) {
        if (typeof dialog.close === 'function') {
          dialog.close();
        } else {
          dialog.removeAttribute('open');
        }
      }
    }
  }, [browse, open]);

  useEffect(() => {
    if (!open) return;

    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      onClose();
    };
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [onClose, open]);

  return (
    <dialog
      ref={dialogRef}
      className={styles.dialog}
      aria-label="选择工作目录"
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <div className={styles.shell}>
        <header className={styles.head}>
          <div className={styles.heading}>
            <h2 className={styles.title}>选择工作目录</h2>
            <p className={styles.path} title={directory?.path}>
              {directory?.path ?? '正在定位主目录…'}
            </p>
          </div>
          <button type="button" className={styles.closeButton} aria-label="关闭" onClick={onClose}>
            ×
          </button>
        </header>

        <div className={styles.body}>
          <nav className={styles.roots} aria-label="文件系统根">
            <span className={styles.rootsLabel}>磁盘</span>
            {directory?.roots.map((root) => (
              <button
                key={root}
                type="button"
                className={styles.root}
                aria-current={root === directory.path ? 'true' : undefined}
                disabled={loading}
                onClick={() => void browse(root)}
              >
                {root}
              </button>
            ))}
          </nav>

          <section className={styles.content} aria-label="目录内容">
            <div className={styles.navigation}>
              <button
                type="button"
                className={styles.parent}
                aria-label="上级"
                disabled={!directory?.parent || loading}
                onClick={() => {
                  if (directory?.parent) void browse(directory.parent);
                }}
              >
                ← 上级
              </button>
            </div>

            {loading && (
              <p className={styles.state} role="status">
                正在读取目录…
              </p>
            )}
            {error && (
              <p className={styles.error} role="alert">
                {error}
              </p>
            )}

            {!loading && directory && directory.directories.length > 0 && (
              <ul className={styles.directoryList}>
                {directory.directories.map((entry) => (
                  <li key={entry.path}>
                    <button
                      type="button"
                      className={styles.directory}
                      onClick={() => void browse(entry.path)}
                    >
                      <span aria-hidden="true">▸</span>
                      <span>{entry.name}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
            {!loading && directory && directory.directories.length === 0 && (
              <p className={styles.state}>当前目录没有子目录</p>
            )}
          </section>
        </div>

        <footer className={styles.footer}>
          <button
            ref={cancelRef}
            type="button"
            className={styles.secondaryButton}
            onClick={onClose}
          >
            取消
          </button>
          <button
            type="button"
            className={styles.primaryButton}
            disabled={!directory || loading}
            onClick={() => {
              if (directory) onSelect(directory.path);
            }}
          >
            选择此文件夹
          </button>
        </footer>
      </div>
    </dialog>
  );
}
