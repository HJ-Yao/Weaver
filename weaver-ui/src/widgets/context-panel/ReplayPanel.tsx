import { useEffect, useState } from 'react';
import { Icon } from '@/shared/ui/Icon';
import styles from './ContextPanel.module.css';

/**
 * 带播放控制的事件时间线。
 *
 * TODO(M3): 用会话详情接口提供的 `SessionEvent[]` 折叠结果替换 `DEMO_EVENTS`；
 * 回放必须满足“现场 == 回放”，不能是脚本化列表。播放控件只影响本地状态，
 * 不会影响实时会话。
 */
const DEMO_EVENTS = [
  '用户消息',
  '助手消息',
  '工具开始 · file',
  '工具完成 · file',
  '助手消息',
  '工具开始 · shell',
  '工具完成 · shell',
  '助手消息',
  '审批请求 · git.commit',
  '审批结果',
  '工具开始 · git',
  '运行结束',
];

/** 播放的一次步进，单位为毫秒。 */
const TICK_MS = 700;

export function ReplayPanel() {
  const [seq, setSeq] = useState(1);
  const [playing, setPlaying] = useState(false);

  useEffect(() => {
    if (!playing) return;
    const timer = window.setInterval(() => {
      setSeq((current) => Math.min(DEMO_EVENTS.length, current + 1));
    }, TICK_MS);
    return () => window.clearInterval(timer);
  }, [playing]);

  useEffect(() => {
    if (seq >= DEMO_EVENTS.length) setPlaying(false);
  }, [seq]);

  return (
    <>
      <div className={styles.panelHead}>
        <span>事件时间线</span>
        <span className={styles.tiny}>
          事件 {seq} / {DEMO_EVENTS.length}
        </span>
      </div>

      <div className={styles.replayControls}>
        <button
          type="button"
          className={styles.action}
          onClick={() => {
            if (seq >= DEMO_EVENTS.length) setSeq(1);
            setPlaying((previous) => !previous);
          }}
        >
          <Icon name={playing ? 'pause' : 'play'} size={12} />
          {playing ? '暂停' : '播放'}
        </button>
        <button
          type="button"
          className={styles.action}
          onClick={() => {
            setPlaying(false);
            setSeq((current) => Math.min(DEMO_EVENTS.length, current + 1));
          }}
        >
          <Icon name="step" size={12} />
          下一步
        </button>
        <span className={styles.spacer} />
        <span className={styles.tiny}>1×</span>
      </div>

      <ol className={styles.timeline}>
        {DEMO_EVENTS.map((label, index) => {
          const eventSeq = index + 1;
          const className = [
            styles.timelineItem,
            eventSeq < seq ? styles.timelineDone : '',
            eventSeq === seq ? styles.timelineCurrent : '',
          ]
            .filter(Boolean)
            .join(' ');

          return (
            <li key={eventSeq}>
              <button type="button" className={className} onClick={() => setSeq(eventSeq)}>
                <span className={styles.timelineDot} aria-hidden="true" />
                {label}
              </button>
            </li>
          );
        })}
      </ol>
    </>
  );
}
