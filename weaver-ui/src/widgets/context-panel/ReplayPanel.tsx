import { useEffect, useMemo, useRef, useState } from 'react';
import { useUiStore } from '@/app/ui-store';
import { useSessionStore } from '@/entities/session/store';
import { sessionApi, type SessionEventDto } from '@/shared/api/sessions';
import { Icon } from '@/shared/ui/Icon';
import styles from './ContextPanel.module.css';
import { groupTurns, type ReplayTurnGroup } from './replayTurns';

/** 事件时间线当前的数据状态。 */
type ReplayStatus = 'loading' | 'ready' | 'error';

/** 事件详情块的可展示内容。 */
interface EventDetail {
  /** 详情类别，用于可访问名称。 */
  label: string;
  /** 原始正文，不在前端再次裁剪。 */
  content: string;
}

/** 播放的一次步进，单位为毫秒。 */
const TICK_MS = 700;

/** 分组标题中用户消息的预览长度。 */
const TURN_PREVIEW_CODE_POINTS = 30;

/** 时间线标签中消息正文的预览长度。 */
const LABEL_PREVIEW_CODE_POINTS = 40;

/** 工具开始标签右侧参数的预览长度。 */
const ARGUMENT_PREVIEW_CODE_POINTS = 30;

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
  const [expandedSeq, setExpandedSeq] = useState<number | null>(null);
  const [playing, setPlaying] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const itemRefs = useRef(new Map<number, HTMLButtonElement>());

  useEffect(() => {
    let cancelled = false;
    setStatus('loading');
    setError(null);
    setEvents([]);
    setActiveSeq(null);
    setExpandedSeq(null);
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

  const eventIndexes = useMemo(
    () => new Map(events.map((event, index) => [event.seq, index])),
    [events],
  );
  const turnGroups = useMemo(() => groupTurns(events), [events]);
  const currentIndex = activeSeq == null ? -1 : (eventIndexes.get(activeSeq) ?? -1);

  useEffect(() => {
    if (!playing || status !== 'ready' || events.length === 0) return;

    const timer = window.setInterval(() => {
      setActiveSeq((current) => {
        const index = current == null ? -1 : (eventIndexes.get(current) ?? -1);
        return events[Math.min(index + 1, events.length - 1)]?.seq ?? null;
      });
    }, TICK_MS);

    return () => window.clearInterval(timer);
  }, [eventIndexes, events, playing, status]);

  useEffect(() => {
    if (playing && currentIndex === events.length - 1) {
      setPlaying(false);
    }
  }, [currentIndex, events.length, playing]);

  useEffect(() => {
    if (status !== 'ready' || activeSeq == null) return;
    itemRefs.current.get(activeSeq)?.scrollIntoView?.({ block: 'nearest' });
  }, [activeSeq, status]);

  const advance = () => {
    setPlaying(false);
    setActiveSeq((current) => {
      const index = current == null ? -1 : (eventIndexes.get(current) ?? -1);
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
        <div className={styles.timelineGroups}>
          {turnGroups.map((group) => (
            <section
              key={group.turnNumber ?? 'prepare'}
              className={styles.timelineGroup}
              aria-label={turnHeading(group)}
            >
              <h3 className={styles.timelineGroupHead}>{turnHeading(group)}</h3>
              <ol className={styles.timeline}>
                {group.events.map((event) => {
                  const index = eventIndexes.get(event.seq) ?? -1;
                  const label = eventLabel(event, toolNames);
                  const className = [
                    styles.timelineItem,
                    index < currentIndex ? styles.timelineDone : '',
                    event.seq === activeSeq ? styles.timelineCurrent : '',
                  ]
                    .filter(Boolean)
                    .join(' ');
                  const detail = eventDetail(event);
                  const expanded = detail != null && expandedSeq === event.seq;
                  const detailId = `replay-event-detail-${event.seq}`;

                  return (
                    <li key={event.seq}>
                      <button
                        ref={(node) => {
                          if (node) {
                            itemRefs.current.set(event.seq, node);
                          } else {
                            itemRefs.current.delete(event.seq);
                          }
                        }}
                        type="button"
                        className={className}
                        aria-label={label}
                        aria-current={event.seq === activeSeq ? 'step' : undefined}
                        aria-expanded={detail ? expanded : undefined}
                        aria-controls={detail ? detailId : undefined}
                        onClick={() => {
                          setPlaying(false);
                          setActiveSeq(event.seq);
                          setExpandedSeq((current) =>
                            detail != null && current !== event.seq ? event.seq : null,
                          );
                        }}
                      >
                        <span className={styles.timelineDot} aria-hidden="true" />
                        <span className={styles.timelineText}>
                          <span className={styles.timelineLabel}>{label}</span>
                          {event.type === 'TOOL_STARTED' && event.argumentsJson ? (
                            <span className={styles.timelineMeta} aria-hidden="true">
                              {previewText(event.argumentsJson, ARGUMENT_PREVIEW_CODE_POINTS)}
                            </span>
                          ) : null}
                        </span>
                      </button>
                      {expanded ? (
                        <pre
                          id={detailId}
                          role="region"
                          aria-label={`${detail.label}详情`}
                          className={styles.timelineDetail}
                        >
                          {detail.content}
                        </pre>
                      ) : null}
                    </li>
                  );
                })}
              </ol>
            </section>
          ))}
        </div>
      )}
    </>
  );
}

/** 将事件事实转换为时间线标签；类型分支由 DTO 联合类型穷尽覆盖。 */
function eventLabel(event: SessionEventDto, toolNames: Map<string, string>): string {
  switch (event.type) {
    case 'USER_MESSAGE':
      return labelWithPreview('用户消息', event.content, LABEL_PREVIEW_CODE_POINTS);
    case 'ASSISTANT_MESSAGE':
      return labelWithPreview('助手消息', event.content, LABEL_PREVIEW_CODE_POINTS);
    case 'TOOL_MESSAGE':
      return '工具消息';
    case 'TOOL_STARTED':
      return `工具开始 · ${toolReference(event.toolName, event.callId)}`;
    case 'TOOL_COMPLETED': {
      const toolName = toolReference(
        (event.callId ? toolNames.get(event.callId) : null) ?? null,
        event.callId,
      );
      return `工具完成 · ${toolName}${event.resultError ? ' · 失败' : ''}`;
    }
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

function eventDetail(event: SessionEventDto): EventDetail | null {
  switch (event.type) {
    case 'USER_MESSAGE':
    case 'ASSISTANT_MESSAGE':
    case 'TOOL_MESSAGE':
      return event.content == null ? null : { label: '消息正文', content: event.content };
    case 'TOOL_STARTED':
      return event.argumentsJson == null
        ? null
        : { label: '工具参数', content: event.argumentsJson };
    case 'TOOL_COMPLETED':
      return event.resultContent == null
        ? null
        : { label: '工具结果', content: event.resultContent };
    case 'APPROVAL_RESOLVED':
      return event.reason == null ? null : { label: '审批原因', content: event.reason };
    case 'APPROVAL_REQUESTED':
    case 'RUN_FINISHED':
      return null;
  }
}

function turnHeading(group: ReplayTurnGroup): string {
  if (group.turnNumber == null) return '准备';
  const preview = previewText(group.userMessage?.content ?? null, TURN_PREVIEW_CODE_POINTS);
  return preview ? `第 ${group.turnNumber} 轮 · ${preview}` : `第 ${group.turnNumber} 轮`;
}

function labelWithPreview(label: string, content: string | null, limit: number): string {
  const preview = previewText(content, limit);
  return preview ? `${label} · ${preview}` : label;
}

function previewText(content: string | null, limit: number): string {
  if (!content) return '';
  const singleLine = content.replace(/\s+/gu, ' ').trim();
  const codePoints = Array.from(singleLine);
  if (codePoints.length <= limit) return singleLine;
  return `${codePoints.slice(0, limit).join('')}…`;
}

function toolReference(toolName: string | null, callId: string | null): string {
  return toolName ?? callId?.slice(0, 8) ?? '未知工具';
}
