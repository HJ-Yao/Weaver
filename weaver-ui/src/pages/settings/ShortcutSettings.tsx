import { Fragment, useEffect, useState } from 'react';
import styles from './SettingsPage.module.css';

/** One rebindable command. */
interface Shortcut {
  id: string;
  group: string;
  label: string;
  keys: string[];
}

const STORAGE_KEY = 'weaver.shortcuts';

/** Defaults; the user's overrides are merged on top when the page mounts. */
const DEFAULT_SHORTCUTS: Shortcut[] = [
  { id: 'open-settings', group: '全局', label: '打开设置', keys: ['Ctrl', ','] },
  { id: 'toggle-sidebar', group: '全局', label: '收起 / 展开侧栏', keys: ['Ctrl', 'B'] },
  { id: 'command-palette', group: '全局', label: '命令面板', keys: ['Ctrl', 'Shift', 'P'] },
  { id: 'new-session', group: '会话', label: '新建会话', keys: ['Ctrl', 'N'] },
  { id: 'search-session', group: '会话', label: '搜索会话', keys: ['Ctrl', 'K'] },
  { id: 'delete-session', group: '会话', label: '删除当前会话', keys: ['Ctrl', 'Shift', 'Backspace'] },
  { id: 'switch-workspace', group: '会话', label: '切换工作区', keys: ['Ctrl', 'O'] },
  { id: 'send', group: '消息', label: '发送消息', keys: ['Enter'] },
  { id: 'newline', group: '消息', label: '换行', keys: ['Shift', 'Enter'] },
  { id: 'stop', group: '消息', label: '停止生成', keys: ['Esc'] },
  { id: 'focus-input', group: '消息', label: '聚焦输入框', keys: ['Ctrl', 'L'] },
  { id: 'attach', group: '消息', label: '添加图片或文件', keys: ['Ctrl', 'U'] },
  { id: 'approve', group: '审批', label: '批准当前审批', keys: ['Ctrl', 'Enter'] },
  { id: 'reject', group: '审批', label: '拒绝当前审批', keys: ['Ctrl', 'Backspace'] },
  { id: 'replay-step', group: '回放', label: '上一步 / 下一步', keys: ['←', '→'] },
  { id: 'replay-play', group: '回放', label: '播放 / 暂停', keys: ['Space'] },
  { id: 'replay-exit', group: '回放', label: '退出回放', keys: ['Esc'] },
];

/** Merge stored overrides without letting a corrupt value break the page. */
function loadShortcuts(): Shortcut[] {
  let stored: Record<string, string[]> = {};
  try {
    stored = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}') as Record<string, string[]>;
  } catch {
    stored = {};
  }
  return DEFAULT_SHORTCUTS.map((shortcut) =>
    Array.isArray(stored[shortcut.id]) && stored[shortcut.id].length > 0
      ? { ...shortcut, keys: stored[shortcut.id] }
      : shortcut,
  );
}

function persist(shortcuts: Shortcut[]): void {
  const overrides = Object.fromEntries(shortcuts.map((shortcut) => [shortcut.id, shortcut.keys]));
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(overrides));
  } catch {
    // Preferences simply do not persist in private mode.
  }
}

/**
 * Keyboard shortcut editor.
 *
 * Click a row to record: the next non-modifier key becomes the binding. Escape
 * cancels and restores the previous value, so no binding can be lost by accident.
 * TODO(M4): bind these to real commands; today they are presentation only.
 */
export function ShortcutSettings() {
  const [shortcuts, setShortcuts] = useState<Shortcut[]>(loadShortcuts);
  const [recordingId, setRecordingId] = useState<string | null>(null);

  useEffect(() => {
    if (!recordingId) return;

    const onKeyDown = (event: KeyboardEvent) => {
      event.preventDefault();

      if (event.key === 'Escape') {
        setRecordingId(null);
        return;
      }

      const parts: string[] = [];
      if (event.ctrlKey) parts.push('Ctrl');
      if (event.altKey) parts.push('Alt');
      if (event.shiftKey) parts.push('Shift');
      if (event.metaKey) parts.push('Meta');
      if (!['Control', 'Alt', 'Shift', 'Meta'].includes(event.key)) {
        parts.push(event.key.length === 1 ? event.key.toUpperCase() : event.key);
      }
      if (parts.length === 0) return;

      const updated = shortcuts.map((shortcut) =>
        shortcut.id === recordingId ? { ...shortcut, keys: parts } : shortcut,
      );
      setShortcuts(updated);
      persist(updated);
      setRecordingId(null);
    };

    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [recordingId, shortcuts]);

  const groups = [...new Set(shortcuts.map((shortcut) => shortcut.group))];

  return (
    <div className={styles.panel}>
      {groups.map((group) => (
        <section key={group} className={styles.shortcutGroup}>
          <h2 className={styles.groupTitle}>{group}</h2>

          {shortcuts
            .filter((shortcut) => shortcut.group === group)
            .map((shortcut) => {
              const recording = recordingId === shortcut.id;
              return (
                <button
                  key={shortcut.id}
                  type="button"
                  className={`${styles.shortcutRow} ${recording ? styles.recording : ''}`}
                  onClick={() => setRecordingId(recording ? null : shortcut.id)}
                >
                  <span>{shortcut.label}</span>
                  <span className={styles.keys}>
                    {recording ? (
                      '按下快捷键…'
                    ) : (
                      shortcut.keys.map((key, index) => (
                        <Fragment key={`${shortcut.id}-${key}`}>
                          {index > 0 && <span className={styles.plus}>+</span>}
                          <kbd className={styles.kbd}>{key}</kbd>
                        </Fragment>
                      ))
                    )}
                  </span>
                </button>
              );
            })}
        </section>
      ))}
    </div>
  );
}
