import { useShallow } from 'zustand/react/shallow';
import type { ToolCallItem } from '@/entities/session/model';
import { useSessionStore } from '@/entities/session/store';
import { formatDuration } from '@/shared/lib/format';
import styles from './ContextPanel.module.css';

/** 运行时暴露的工具；TODO(M2): 从 `ToolRegistry` 加载。 */
const AVAILABLE_TOOLS = [
  { name: 'file', meta: 'read / write / list' },
  { name: 'shell', meta: '30s · 64KB' },
  { name: 'git', meta: 'status / diff / commit' },
];

/** 使用显式映射，使 CSS 类名仍可搜索，避免动态字符串查找。 */
const STATUS_CLASS: Record<ToolCallItem['status'], string> = {
  running: styles.statusRunning,
  done: styles.statusDone,
  failed: styles.statusFailed,
};

/** 当前运行中的工具调用及注册表目录。 */
export function ToolsPanel() {
  const tools = useSessionStore(
    useShallow((state) =>
      state.transcript.filter((item): item is ToolCallItem => item.kind === 'tool'),
    ),
  );

  return (
    <>
      <div className={styles.panelHead}>
        <span>本轮工具调用</span>
        <span className={styles.tiny}>{tools.length} 个</span>
      </div>

      {tools.map((tool) => (
        <div key={tool.id} className={styles.row}>
          <span className={`${styles.dot} ${STATUS_CLASS[tool.status]}`} aria-hidden="true" />
          <span className={styles.rowName}>{tool.toolName}</span>
          <span className={styles.rowMeta}>
            {tool.durationMs !== undefined ? formatDuration(tool.durationMs) : '进行中'}
          </span>
        </div>
      ))}

      {tools.length === 0 && <p className={styles.empty}>本轮还没有工具调用。</p>}

      <div className={styles.panelHead}>
        <span>可用工具</span>
        <span className={styles.tiny}>{AVAILABLE_TOOLS.length}</span>
      </div>

      {AVAILABLE_TOOLS.map((tool) => (
        <div key={tool.name} className={`${styles.row} ${styles.muted}`}>
          <span className={styles.rowName}>{tool.name}</span>
          <span className={styles.rowMeta}>{tool.meta}</span>
        </div>
      ))}
    </>
  );
}
