import { Outlet, useLocation } from 'react-router-dom';
import { ContextPanel } from '@/widgets/context-panel/ContextPanel';
import { Rail } from '@/widgets/rail/Rail';
import { Sidebar } from '@/widgets/sidebar/Sidebar';
import { Icon } from '@/shared/ui/Icon';
import { Toast } from '@/shared/ui/Toast';
import { StatusBar } from './StatusBar';
import { useUiStore } from './ui-store';
import styles from './AppShell.module.css';

/** 三个顶层页面；每个页面的外壳布局各不相同。 */
type RouteKind = 'chat' | 'plugins' | 'settings';

function useRouteKind(): RouteKind {
  const { pathname } = useLocation();
  if (pathname.startsWith('/plugins')) return 'plugins';
  if (pathname.startsWith('/settings')) return 'settings';
  return 'chat';
}

/**
 * 应用外壳。
 *
 * 负责四列 grid（rail、sidebar、main、context）、顶栏和状态栏。
 * 页面渲染到 `<Outlet />` 中，自身不相对视口定位。
 */
export function AppShell() {
  const route = useRouteKind();
  const leftCollapsed = useUiStore((state) => state.leftCollapsed);
  const rightCollapsed = useUiStore((state) => state.rightCollapsed);
  const toggleLeft = useUiStore((state) => state.toggleLeft);

  return (
    <div className={styles.app}>
      <header className={styles.topbar}>
        <button
          type="button"
          className={styles.toggle}
          title="收起或展开侧栏"
          aria-label="收起或展开侧栏"
          aria-pressed={!leftCollapsed}
          onClick={toggleLeft}
        >
          <Icon name="panel-left" />
        </button>

        <div className={styles.brand}>
          <img src="/brand/weaver-logo.png" alt="" className={styles.brandMark} />
          <div className={styles.brandText}>
            <strong>Weaver</strong>
            <span>agent harness</span>
          </div>
        </div>
      </header>

      <div
        className={styles.shell}
        data-route={route}
        data-left={leftCollapsed}
        data-right={rightCollapsed}
      >
        <Rail />
        {route !== 'settings' && !leftCollapsed && <Sidebar route={route} />}
        <main className={styles.main} aria-label="工作区">
          <Outlet />
        </main>
        {route === 'chat' && !rightCollapsed && <ContextPanel />}
      </div>

      <StatusBar />
      <Toast />
    </div>
  );
}
