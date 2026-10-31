import { Outlet, useLocation } from 'react-router-dom';
import { ContextPanel } from '@/widgets/context-panel/ContextPanel';
import { Rail } from '@/widgets/rail/Rail';
import { Sidebar } from '@/widgets/sidebar/Sidebar';
import { Icon } from '@/shared/ui/Icon';
import { Toast } from '@/shared/ui/Toast';
import { StatusBar } from './StatusBar';
import { useUiStore } from './ui-store';
import styles from './AppShell.module.css';

/** The three top-level screens; the shell layout differs for each. */
type RouteKind = 'chat' | 'plugins' | 'settings';

function useRouteKind(): RouteKind {
  const { pathname } = useLocation();
  if (pathname.startsWith('/plugins')) return 'plugins';
  if (pathname.startsWith('/settings')) return 'settings';
  return 'chat';
}

/**
 * Application frame.
 *
 * Owns the four-column grid (rail · sidebar · main · context), the top bar and
 * the status bar. Pages render into `<Outlet />` and never position themselves
 * against the viewport.
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
