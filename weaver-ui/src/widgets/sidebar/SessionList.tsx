import { useMemo, useState } from 'react';
import { useSessionStore } from '@/entities/session/store';
import { formatRelativeTime } from '@/shared/lib/format';
import { useUiStore } from '@/app/ui-store';
import styles from './Sidebar.module.css';

/**
 * Conversation list with client-side filtering.
 *
 * Filtering is local because the sidebar only holds the sessions already
 * loaded; TODO(M2): debounce a server-side search once the list is paged.
 */
export function SessionList() {
  const sessions = useSessionStore((state) => state.sessions);
  const activeSessionId = useSessionStore((state) => state.activeSessionId);
  const selectSession = useSessionStore((state) => state.selectSession);
  const showToast = useUiStore((state) => state.showToast);
  const [query, setQuery] = useState('');

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
          onClick={() => showToast('新建会话将在接入 API 后可用。')}
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
        {visibleSessions.map((session) => (
          <button
            key={session.id}
            type="button"
            className={`${styles.session} ${session.id === activeSessionId ? styles.sessionActive : ''}`}
            onClick={() => selectSession(session.id)}
          >
            <span className={styles.sessionTitle}>{session.title}</span>
            <span className={styles.sessionMeta}>
              {session.stepCount ? `${session.stepCount} 步 · ` : ''}
              {formatRelativeTime(session.createdAt)}
            </span>
            {session.id === activeSessionId && <span className={styles.sessionDot} aria-hidden="true" />}
          </button>
        ))}

        {visibleSessions.length === 0 && <p className={styles.empty}>没有匹配的会话</p>}
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
