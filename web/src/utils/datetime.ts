/**
 * 把时间格式化为易读的本地时间字符串「YYYY-MM-DD HH:mm:ss」。
 *
 * 入参可为 ISO-8601 字符串（后端 createTime 的形态，如 2026-06-20T10:00:00+08:00）或 Date。
 * 按浏览器本地时区展示；空值或非法时间返回占位符「-」，避免表格里出现 Invalid Date。
 */
export function formatDateTime(value: string | Date): string {
  if (!value) return '-'
  const d = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(d.getTime())) return '-'

  const pad = (n: number) => String(n).padStart(2, '0')
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  return `${date} ${time}`
}
