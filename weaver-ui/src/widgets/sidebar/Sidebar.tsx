import { PluginNav } from './PluginNav';
import { SessionList } from './SessionList';
import styles from './Sidebar.module.css';

interface SidebarProps {
  /** 要显示的次级面板；设置页没有侧栏。 */
  route: 'chat' | 'plugins' | 'settings';
}

/**
 * 第二列：显示会话列表或插件导航。
 *
 * 两个面板的数据源和空状态不同，因此拆成独立组件；
 * 侧栏只决定当前路由显示哪一个。
 */
export function Sidebar({ route }: SidebarProps) {
  if (route === 'settings') return null;

  return (
    <aside
      className={styles.sidebar}
      aria-label={route === 'plugins' ? '插件导航' : '会话列表'}
    >
      {route === 'plugins' ? <PluginNav /> : <SessionList />}
    </aside>
  );
}
