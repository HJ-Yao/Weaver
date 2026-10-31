import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AppearanceSettings } from './AppearanceSettings';
import { ModelSettings } from './ModelSettings';
import { ShortcutSettings } from './ShortcutSettings';
import styles from './SettingsPage.module.css';

type SettingsTab = 'appearance' | 'shortcuts' | 'model';

const TABS: { id: SettingsTab; label: string }[] = [
  { id: 'appearance', label: '外观' },
  { id: 'shortcuts', label: '键盘快捷键' },
  { id: 'model', label: '模型 API' },
];

/**
 * 设置页面。
 *
 * 以全宽页面而非对话框渲染（外壳会收起 sidebar 和 context 列），
 * 这样快捷键列表等长面板可以独立滚动，rail 也始终可访问。
 */
export function SettingsPage() {
  const [tab, setTab] = useState<SettingsTab>('appearance');

  return (
    <section className={styles.page} aria-label="设置">
      <header className={styles.head}>
        <div>
          <h1 className={styles.title}>设置</h1>
          <p className={styles.subtitle}>外观、快捷键与模型连接</p>
        </div>
        <Link to="/" className={styles.back}>
          ← 返回会话
        </Link>
      </header>

      <div className={styles.layout}>
        <nav className={styles.nav} aria-label="设置分类">
          {TABS.map((item) => (
            <button
              key={item.id}
              type="button"
              className={`${styles.navItem} ${tab === item.id ? styles.navItemActive : ''}`}
              aria-current={tab === item.id ? 'page' : undefined}
              onClick={() => setTab(item.id)}
            >
              {item.label}
            </button>
          ))}
        </nav>

        <div className={styles.body}>
          {tab === 'appearance' && <AppearanceSettings />}
          {tab === 'shortcuts' && <ShortcutSettings />}
          {tab === 'model' && <ModelSettings />}
        </div>
      </div>
    </section>
  );
}
