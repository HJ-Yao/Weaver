import { describe, expect, it } from 'vitest';
import { formatDuration, formatRelativeTime } from './format';

describe('formatDuration', () => {
  it('uses milliseconds below one second', () => {
    expect(formatDuration(142)).toBe('142ms');
  });

  it('uses one decimal above one second', () => {
    expect(formatDuration(3100)).toBe('3.1s');
  });
});

describe('formatRelativeTime', () => {
  const now = Date.parse('2026-10-31T12:00:00Z');

  it('reports minutes and hours', () => {
    expect(formatRelativeTime('2026-10-31T11:58:00Z', now)).toBe('2 分钟前');
    expect(formatRelativeTime('2026-10-31T11:00:00Z', now)).toBe('1 小时前');
  });

  it('falls back to a date after two days', () => {
    expect(formatRelativeTime('2026-10-28T09:00:00Z', now)).toMatch(/月/);
  });

  it('returns an empty label for invalid input', () => {
    expect(formatRelativeTime('not-a-date', now)).toBe('');
  });
});
