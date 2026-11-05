import { useEffect, useMemo, useRef, useState } from 'react';
import { useUiStore } from '@/app/ui-store';
import { useSessionStore } from '@/entities/session/store';
import type { SessionSummary } from '@/entities/session/model';
import { formatRelativeTime } from '@/shared/lib/format';
import styles from './Sidebar.module.css';

/**
 * 带前端筛选的会话列表。
 *
 * 侧栏只持有已加载的会话，因此筛选在本地进行；
 * TODO(M2): 列表分页后为服务端搜索增加防抖。
 */
export function SessionList() {
  const sessions = useSessionStore((state) => state.sessions);
  const activeSessionId = useSessionStore((state) => state.activeSessionId);
  const status = useSessionStore((state) => state.status);
  const error = useSessionStore((state) => state.error);
  const loadSessions = useSessionStore((state) => state.loadSessions);
  const selectSession = useSessionStore((state) => state.selectSession);
  const createSession = useSessionStore((state) => state.createSession);
  const renameSession = useSessionStore((state) => state.renameSession);
  const deleteSession = useSessionStore((state) => state.deleteSession);
  const showToast = useUiStore((state) => state.showToast);
  const [query, setQuery] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draftTitle, setDraftTitle] = useState('');
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const confirmDeleteTimer = useRef<number | null>(null);
  const cancelRenameRef = useRef(false);

  useEffect(() => {
    void loadSessions();
  }, [loadSessions]);

  useEffect(() => {
    return () => {
      if (confirmDeleteTimer.current !== null) {
        window.clearTimeout(confirmDeleteTimer.current);
      }
    };
  }, []);

  const visibleSessions = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return sessions;
    return sessions.filter((session) => session.title.toLowerCase().includes(needle));
  }, [sessions, query]);

  const startRename = (session: SessionSummary) => {
    setConfirmDeleteId(null);
    if (confirmDeleteTimer.current !== null) {
      window.clearTimeout(confirmDeleteTimer.current);
      confirmDeleteTimer.current = null;
    }
    cancelRenameRef.current = false;
    setDraftTitle(session.title);
    setEditingId(session.id);
  };

  const cancelRename = () => {
    cancelRenameRef.current = true;
    setEditingId(null);
  };

  const commitRename = (session: SessionSummary) => {
    if (editingId !== session.id) return;
    if (cancelRenameRef.current) {
      cancelRenameRef.current = false;
      return;
    }

    const nextTitle = draftTitle.trim();
    setEditingId(null);
    if (!nextTitle || nextTitle === session.title) return;

    void renameSession(session.id, nextTitle).then((renamed) => {
      if (!renamed) showToast('重命名失败');
    });
  };

  const requestDelete = (session: SessionSummary) => {
    if (confirmDeleteId !== session.id) {
      if (confirmDeleteTimer.current !== null) {
        window.clearTimeout(confirmDeleteTimer.current);
      }
      setEditingId(null);
      setConfirmDeleteId(session.id);
      confirmDeleteTimer.current = window.setTimeout(() => {
        setConfirmDeleteId(null);
        confirmDeleteTimer.current = null;
      }, 3000);
      return;
    }

    if (confirmDeleteTimer.current !== null) {
      window.clearTimeout(confirmDeleteTimer.current);
      confirmDeleteTimer.current = null;
    }
    setConfirmDeleteId(null);
    void deleteSession(session.id).then((deleted) => {
      if (!deleted) showToast('删除失败：会话可能正在运行');
    });
  };

  return (
    <>
      <div className={styles.top}>
        <span className={styles.label}>会话</span>
      </div>

      <div className={styles.head}>
        <button
          type="button"
          className={styles.newSession}
          onClick={() => void createSession()}
        >
          ＋ 新建会话
        </button>
        <input
          className={styles.search}
          type="search"
          value={query}
          placeholder="搜索会话"
          aria-label="搜索会话"
          onChange={(event) => setQuery(event.target.value)}
        />
      </div>

      <div className={styles.list}>
        {status === 'idle' || status === 'loading' ? (
          <p className={styles.state}>加载中…</p>
        ) : status === 'error' ? (
          // 详情请求失败也会进入此分支；此时隐藏列表，聊天区则保留之前的 transcript。
          // TODO(M2): 拆分列表错误与详情错误，并将详情错误显示为横幅。
          <div className={styles.state} role="alert">
            <p className={styles.error}>{error ?? '会话加载失败'}</p>
            <button
              type="button"
              className={styles.retry}
              onClick={() => void loadSessions(true)}
            >
              重试
            </button>
          </div>
        ) : sessions.length === 0 ? (
          <p className={styles.state}>还没有会话</p>
        ) : (
          <>
            {visibleSessions.map((session) => (
              <div
                key={session.id}
                className={`${styles.sessionRow} ${
                  session.id === activeSessionId ? styles.sessionRowActive : ''
                }`}
              >
                {editingId === session.id ? (
                  <div className={styles.session}>
                    <input
                      className={styles.sessionRename}
                      value={draftTitle}
                      aria-label={`重命名会话 ${session.title}`}
                      autoFocus
                      onChange={(event) => setDraftTitle(event.target.value)}
                      onClick={(event) => event.stopPropagation()}
                      onBlur={() => commitRename(session)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          event.preventDefault();
                          event.currentTarget.blur();
                        } else if (event.key === 'Escape') {
                          event.preventDefault();
                          cancelRename();
                        }
                      }}
                    />
                    <span className={styles.sessionMeta}>
                      {session.stepCount ? `${session.stepCount} 步 · ` : ''}
                      {formatRelativeTime(session.createdAt)}
                    </span>
                  </div>
                ) : (
                  <button
                    type="button"
                    className={styles.session}
                    onClick={() => void selectSession(session.id)}
                  >
                    <span className={styles.sessionTitle}>{session.title}</span>
                    <span className={styles.sessionMeta}>
                      {session.stepCount ? `${session.stepCount} 步 · ` : ''}
                      {formatRelativeTime(session.createdAt)}
                    </span>
                    {session.id === activeSessionId && (
                      <span className={styles.sessionDot} aria-hidden="true" />
                    )}
                  </button>
                )}
                <div className={styles.sessionActions}>
                  <button
                    type="button"
                    className={styles.sessionAction}
                    aria-label={`重命名 ${session.title}`}
                    title="重命名"
                    onClick={(event) => {
                      event.stopPropagation();
                      startRename(session);
                    }}
                  >
                    ✎
                  </button>
                  <button
                    type="button"
                    className={`${styles.sessionAction} ${
                      confirmDeleteId === session.id ? styles.sessionActionConfirm : ''
                    }`}
                    aria-label={
                      confirmDeleteId === session.id
                        ? `确认删除 ${session.title}`
                        : `删除 ${session.title}`
                    }
                    title={confirmDeleteId === session.id ? '确认删除' : '删除'}
                    onClick={(event) => {
                      event.stopPropagation();
                      requestDelete(session);
                    }}
                  >
                    {confirmDeleteId === session.id ? '确认删除' : '✕'}
                  </button>
                </div>
              </div>
            ))}

            {visibleSessions.length === 0 && (
              <p className={styles.empty}>没有匹配的会话</p>
            )}
          </>
        )}
      </div>

      <div className={styles.footer}>
        <span className={`${styles.dot} ${styles.ok}`} aria-hidden="true" />
        <span>本地工作区</span>
        <span className={styles.spacer} />
        <span className={styles.tiny}>sqlite · 1.2 MB</span>
      </div>
    </>
  );
}
