import { useEffect, useId, useRef, useState } from 'react';
import { Icon } from './Icon';
import styles from './Dropdown.module.css';

/** One selectable row in a dropdown menu. */
export interface DropdownOption {
  id: string;
  label: string;
  /** Secondary line, usually a path or protocol name. */
  meta?: string;
}

interface DropdownProps {
  /** Accessible name for the trigger. */
  label: string;
  options: DropdownOption[];
  value: string;
  onChange: (id: string) => void;
  /** `up` is used by the composer, which sits at the bottom of the viewport. */
  placement?: 'up' | 'down';
  /** Which edge the menu is anchored to. `right` keeps the composer's menu on screen. */
  align?: 'left' | 'right';
  /** Shown when no option matches `value`. */
  placeholder?: string;
}

/**
 * A small accessible picker.
 *
 * Behaviour: click toggles, Escape closes, clicking outside closes, and the
 * selected option is marked with `aria-current`. It deliberately does not
 * implement full listbox semantics (roving tabindex) yet — each option is a
 * real button, so Tab and Enter work natively.
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
