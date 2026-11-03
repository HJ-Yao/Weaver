import { useEffect, useMemo, useState } from 'react';
import { useUiStore } from '@/app/ui-store';
import { useSessionStore } from '@/entities/session/store';
import { sessionApi, type SessionEventDto } from '@/shared/api/sessions';
import { Icon } from '@/shared/ui/Icon';
import styles from './ContextPanel.module.css';

/** 事件时间线当前的数据状态。 */
type ReplayStatus = 'loading' | 'ready' | 'error';

/** 播放的一次步进，单位为毫秒。 */
const TICK_MS = 700;

/**
 * 当前会话的真实事件时间线。
 *
 * 事件来自会话事实源，只控制本地时间线游标；播放不会过滤聊天区 transcript。
 * 切换会话时取消旧请求，防止迟到的响应覆盖当前时间线。
 */
export function ReplayPanel() {
  const activeSessionId = useSessionStore((state) => state.activeSessionId);
  const setContextTab = useUiStore((state) => state.setContextTab);
  const [events, setEvents] = useState<SessionEventDto[]>([]);
  const [status, setStatus] = useState<ReplayStatus>('loading');
  const [error, setError] = useState<string | null>(null);
  const [activeSeq, setActiveSeq] = useState<number | null>(null);
  const [playing, setPlaying] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setStatus('loading');
    setError(null);
    setEvents([]);
    setActiveSeq(null);
    setPlaying(false);

    if (!activeSessionId) {
      setStatus('ready');
      return () => {
        cancelled = true;
      };
    }

    void sessionApi
      .events(activeSessionId)
      .then((loadedEvents) => {
        if (cancelled) return;
        setEvents(loadedEvents);
        setActiveSeq(loadedEvents[0]?.seq ?? null);
        setStatus('ready');
      })
      .catch((loadError: unknown) => {
        if (cancelled) return;
        const message = loadError instanceof Error ? loadError.message : String(loadError);
        setError(`事件加载失败：${message || '未知错误'}`);
        setStatus('error');
      });

    return () => {
      cancelled = true;
    };
  }, [activeSessionId, reloadKey]);

  const toolNames = useMemo(() => {
    const names = new Map<string, string>();
    for (const event of events) {
      if (event.type === 'TOOL_STARTED' && event.callId && event.toolName) {
        names.set(event.callId, event.toolName);
      }
    }
    return names;
  }, [events]);

  const currentIndex = events.findIndex((event) => event.seq === activeSeq);

  useEffect(() => {
    if (!playing || status !== 'ready' || events.length === 0) return;

    const timer = window.setInterval(() => {
      setActiveSeq((current) => {
        const index = events.findIndex((event) => event.seq === current);
        return events[Math.min(index + 1, events.length - 1)]?.seq ?? null;
      });
    }, TICK_MS);

    return () => window.clearInterval(timer);
  }, [events, playing, status]);

  useEffect(() => {
    if (playing && currentIndex === events.length - 1) {
      setPlaying(false);
    }
  }, [currentIndex, events.length, playing]);

  const advance = () => {
    setPlaying(false);
    setActiveSeq((current) => {
      const index = events.findIndex((event) => event.seq === current);
      return events[Math.min(index + 1, events.length - 1)]?.seq ?? null;
    });
  };

  const togglePlayback = () => {
    if (currentIndex < 0 || currentIndex === events.length - 1) {
      setActiveSeq(events[0]?.seq ?? null);
    }
    setPlaying((previous) => !previous);
  };

  return (
    <>
      <div className={styles.row}>
        <span>回放模式</span>
        <span className={styles.spacer} />
        <button type="button" className={styles.action} onClick={() => setContextTab('tools')}>
          退出回放
        </button>
      </div>

      <div className={styles.panelHead}>
        <span>事件时间线</span>
        <span className={styles.tiny}>
          事件 {currentIndex >= 0 ? currentIndex + 1 : 0} / {events.length}
        </span>
      </div>

      <div className={styles.replayControls}>
        <button
          type="button"
          className={styles.action}
          disabled={status !== 'ready' || events.length === 0}
          onClick={togglePlayback}
        >
          <Icon name={playing ? 'pause' : 'play'} size={12} />
          {playing ? '暂停' : '播放'}
        </button>
        <button
          type="button"
          className={styles.action}
          disabled={status !== 'ready' || currentIndex < 0 || currentIndex >= events.length - 1}
          onClick={advance}
        >
          <Icon name="step" size={12} />
          下一步
        </button>
        <span className={styles.spacer} />
        <span className={styles.tiny}>1×</span>
      </div>

      {status === 'loading' && <p className={styles.empty}>加载中…</p>}

      {status === 'error' && (
        <div>
          <p className={styles.empty} role="alert">
            {error}
          </p>
          <button
            type="button"
            className={styles.action}
            onClick={() => setReloadKey((value) => value + 1)}
          >
            重试
          </button>
        </div>
      )}

      {status === 'ready' && events.length === 0 && (
        <p className={styles.empty}>这个会话还没有事件</p>
      )}

      {status === 'ready' && events.length > 0 && (
        <ol className={styles.timeline}>
          {events.map((event, index) => {
            const className = [
              styles.timelineItem,
              index < currentIndex ? styles.timelineDone : '',
              event.seq === activeSeq ? styles.timelineCurrent : '',
            ]
              .filter(Boolean)
              .join(' ');

            return (
              <li key={event.seq}>
                <button
                  type="button"
                  className={className}
                  aria-current={event.seq === activeSeq ? 'step' : undefined}
                  onClick={() => {
                    setPlaying(false);
                    setActiveSeq(event.seq);
                  }}
                >
                  <span className={styles.timelineDot} aria-hidden="true" />
                  {eventLabel(event, toolNames)}
                </button>
              </li>
            );
          })}
        </ol>
      )}
    </>
  );
}

/** 将事件事实转换为时间线文案；类型分支由 DTO 联合类型穷尽覆盖。 */
function eventLabel(event: SessionEventDto, toolNames: Map<string, string>): string {
  switch (event.type) {
    case 'USER_MESSAGE':
      return '用户消息';
    case 'ASSISTANT_MESSAGE':
      return '助手消息';
    case 'TOOL_MESSAGE':
      return '工具消息';
    case 'TOOL_STARTED':
      return `工具开始 · ${toolReference(event.toolName, event.callId)}`;
    case 'TOOL_COMPLETED':
      return `工具完成 · ${toolReference(
        (event.callId ? toolNames.get(event.callId) : null) ?? null,
        event.callId,
      )}`;
    case 'APPROVAL_REQUESTED':
      return `审批请求 · ${toolReference(event.toolName, event.callId)}`;
    case 'APPROVAL_RESOLVED':
      return `审批结果 · ${
        event.approved == null ? '结果未知' : event.approved ? '已批准' : '已拒绝'
      }`;
    case 'RUN_FINISHED':
      return `运行结束 · ${event.aborted ? '已中止' : '完成'}`;
  }
}

function toolReference(toolName: string | null, callId: string | null): string {
  return toolName ?? callId?.slice(0, 8) ?? '未知工具';
}
