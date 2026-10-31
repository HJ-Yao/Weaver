import { PluginNav } from './PluginNav';
import { SessionList } from './SessionList';
import styles from './Sidebar.module.css';

interface SidebarProps {
  /** Which secondary panel to show; settings has no sidebar. */
  route: 'chat' | 'plugins' | 'settings';
}

/**
 * Second column: either the conversation list or the plugin navigation.
 *
 * The two panels are separate components because they have different data
 * sources and different empty states; the sidebar only decides which one the
 * current route shows.
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
