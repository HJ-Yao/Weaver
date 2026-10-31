import { useEffect, useId, useRef, useState } from 'react';
import { Icon } from './Icon';
import styles from './Dropdown.module.css';

/** 下拉菜单中的一个可选项。 */
export interface DropdownOption {
  id: string;
  label: string;
  /** 次要说明行，通常是路径或协议名。 */
  meta?: string;
}

interface DropdownProps {
  /** 触发控件的无障碍名称。 */
  label: string;
  options: DropdownOption[];
  value: string;
  onChange: (id: string) => void;
  /** 输入区位于视口底部，因此使用 `up`。 */
  placement?: 'up' | 'down';
  /** 菜单锚定的边缘；`right` 可让输入区的菜单保持在屏幕内。 */
  align?: 'left' | 'right';
  /** 没有选项匹配 `value` 时显示的内容。 */
  placeholder?: string;
}

/**
 * 小型无障碍选择器。
 *
 * 行为：点击切换，Escape 关闭，点击外部关闭，选中项用 `aria-current` 标记。
 * 目前有意不实现完整的 listbox 语义（roving tabindex）；每个选项都是真实按钮，
 * 因此 Tab 和 Enter 可原生工作。
 */
export function Dropdown({
  label,
  options,
  value,
  onChange,
  placement = 'down',
  align = 'left',
  placeholder = '未选择',
}: DropdownProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const menuId = useId();

  useEffect(() => {
    if (!open) return;

    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };

    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const current = options.find((option) => option.id === value);

  return (
    <div className={styles.root} ref={rootRef}>
      <button
        type="button"
        className={styles.trigger}
        aria-haspopup="true"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={label}
        onClick={() => setOpen((previous) => !previous)}
      >
        <span className={styles.current}>{current?.label ?? placeholder}</span>
        <Icon name="chevron-down" size={12} />
      </button>

      {open && (
        <ul
          id={menuId}
          className={`${styles.menu} ${placement === 'up' ? styles.up : styles.down} ${align === 'right' ? styles.alignRight : ''}`}
          aria-label={label}
        >
          {options.map((option) => (
            <li key={option.id}>
              <button
                type="button"
                className={styles.option}
                aria-current={option.id === value ? 'true' : undefined}
                onClick={() => {
                  onChange(option.id);
                  setOpen(false);
                }}
              >
                <span className={styles.optionLabel}>{option.label}</span>
                {option.meta && <span className={styles.optionMeta}>{option.meta}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
