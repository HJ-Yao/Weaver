import { useEffect, useMemo, useState } from 'react';
import { useSessionStore } from '@/entities/session/store';
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
  const [query, setQuery] = useState('');

  useEffect(() => {
    void loadSessions();
  }, [loadSessions]);

  const visibleSessions = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return sessions;
    return sessions.filter((session) => session.title.toLowerCase().includes(needle));
  }, [sessions, query]);

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
              <button
                key={session.id}
                type="button"
                className={`${styles.session} ${session.id === activeSessionId ? styles.sessionActive : ''}`}
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
            ))}

            {visibleSessions.length === 0 && <p className={styles.empty}>没有匹配的会话</p>}
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
