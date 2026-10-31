/**
 * 展示格式化函数。
 *
 * 只包含纯函数：显式接收输入，因此无需全局冻结时钟即可进行单元测试。
 */

/** 将耗时格式化为 `142ms` 或 `3.1s`。 */
export function formatDuration(milliseconds: number): string {
  if (milliseconds < 1000) return `${Math.round(milliseconds)}ms`;
  return `${(milliseconds / 1000).toFixed(1)}s`;
}

/** 将 ISO 时间戳格式化为界面语言中的简短相对时间标签。 */
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
