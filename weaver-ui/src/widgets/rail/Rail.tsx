import { NavLink } from 'react-router-dom';
import { Icon, type IconName } from '@/shared/ui/Icon';
import styles from './Rail.module.css';

interface RailItem {
  to: string;
  label: string;
  icon: IconName;
  /** `end` keeps `/` from matching every route. */
  end?: boolean;
}

const PRIMARY: RailItem[] = [
  { to: '/', label: '首页', icon: 'home', end: true },
  { to: '/plugins', label: '插件', icon: 'plugins' },
];

/**
 * Leftmost icon rail.
 *
 * Only one destination is highlighted at a time because `NavLink` owns the
 * active state — no manual toggling, so the rail can never disagree with the
 * current route.
 */
export function Rail() {
  return (
    <nav className={styles.rail} aria-label="主导航">
      {PRIMARY.map((item) => (
        <RailLink key={item.to} item={item} />
      ))}

      <span className={styles.spacer} />

      <RailLink item={{ to: '/settings', label: '设置', icon: 'settings' }} />
    </nav>
  );
}

function RailLink({ item }: { item: RailItem }) {
  return (
    <NavLink
      to={item.to}
      end={item.end}
      title={item.label}
      aria-label={item.label}
      className={({ isActive }) => (isActive ? `${styles.button} ${styles.active}` : styles.button)}
    >
      <Icon name={item.icon} />
    </NavLink>
  );
}
