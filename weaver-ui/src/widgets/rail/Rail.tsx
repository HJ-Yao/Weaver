import { NavLink } from 'react-router-dom';
import { Icon, type IconName } from '@/shared/ui/Icon';
import styles from './Rail.module.css';

interface RailItem {
  to: string;
  label: string;
  icon: IconName;
  /** `end` 可避免 `/` 匹配所有路由。 */
  end?: boolean;
}

const PRIMARY: RailItem[] = [
  { to: '/', label: '首页', icon: 'home', end: true },
  { to: '/plugins', label: '插件', icon: 'plugins' },
];

/**
 * 最左侧图标 rail。
 *
 * 同一时间只高亮一个目标，因为激活状态由 `NavLink` 管理；
 * 无需手动切换，因此 rail 不会与当前路由不一致。
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
