import type { CSSProperties } from 'react';
import { useUiStore, type Accent, type Theme } from '@/app/ui-store';
import styles from './SettingsPage.module.css';

const ACCENTS: { id: Accent; label: string; color: string }[] = [
  { id: 'violet', label: '紫色', color: '#a66cff' },
  { id: 'blue', label: '蓝色', color: '#5b8cff' },
  { id: 'teal', label: '青色', color: '#3cc9c0' },
  { id: 'green', label: '绿色', color: '#5fd3a0' },
  { id: 'amber', label: '琥珀', color: '#e8b45a' },
  { id: 'coral', label: '珊瑚', color: '#ff7a6b' },
  { id: 'pink', label: '粉色', color: '#f472b6' },
  { id: 'graphite', label: '石墨', color: '#b8c0cc' },
];

/**
 * 主题与强调色。
 *
 * 二者都直接写入 UI store，由 store 立即将 token 应用到 `<html>`，
 * 因此没有容易遗漏的“应用”步骤。
 */
export function AppearanceSettings() {
  const theme = useUiStore((state) => state.theme);
  const accent = useUiStore((state) => state.accent);
  const setTheme = useUiStore((state) => state.setTheme);
  const setAccent = useUiStore((state) => state.setAccent);

  return (
    <div className={styles.panel}>
      <label className={styles.field}>
        <span>主题</span>
        <select value={theme} onChange={(event) => setTheme(event.target.value as Theme)}>
          <option value="dark">深色</option>
          <option value="light">浅色</option>
        </select>
      </label>

      <div className={styles.field}>
        <span id="accent-label">主色</span>
        <div className={styles.swatches} role="group" aria-labelledby="accent-label">
          {ACCENTS.map((item) => (
            <button
              key={item.id}
              type="button"
              className={`${styles.swatch} ${accent === item.id ? styles.swatchActive : ''}`}
              style={{ '--swatch': item.color } as CSSProperties}
              aria-label={item.label}
              aria-pressed={accent === item.id}
              onClick={() => setAccent(item.id)}
            />
          ))}
        </div>
      </div>

      {/* TODO(M4): 外壳支持紧凑模式后接入字号与密度设置。 */}
      <label className={styles.field}>
        <span>字号</span>
        <select disabled title="尚未实现">
          <option>中</option>
        </select>
      </label>

      <label className={styles.field}>
        <span>密度</span>
        <select disabled title="尚未实现">
          <option>紧凑</option>
        </select>
      </label>
    </div>
  );
}
