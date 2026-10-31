/**
 * Display formatters.
 *
 * Pure functions only — they take explicit inputs so they can be unit tested
 * without freezing the clock globally.
 */

/** Format an elapsed duration as `142ms` or `3.1s`. */
export function formatDuration(milliseconds: number): string {
  if (milliseconds < 1000) return `${Math.round(milliseconds)}ms`;
  return `${(milliseconds / 1000).toFixed(1)}s`;
}

/** Format an ISO timestamp as a short relative label in the UI language. */
export function formatRelativeTime(iso: string, now: number = Date.now()): string {
  const time = new Date(iso).getTime();
  if (Number.isNaN(time)) return '';

  const minutes = Math.floor((now - time) / 60_000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;

  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;

  const date = new Date(time);
  const timeLabel = `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  const days = Math.floor(hours / 24);
  if (days === 1) return `昨天 ${timeLabel}`;
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日`;
}
