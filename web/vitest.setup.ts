/**
 * 全局测试守卫：把 Vue / Element Plus 的框架警告当成测试失败。
 *
 * 为什么需要：这些警告本地跑 `pnpm test` 时被 TTY reporter 折叠了看不见，
 * 而 CI 非 TTY 会全量打印——曾静默积到 131 条、把前端 CI 日志撑到 32 万行。
 * 更要紧的是它们里头混着真的生产代码问题（如 el-tag 收到非法 type），
 * 藏起来只会让它们继续在真实浏览器控制台里刷。
 *
 * 设计要点见 docs/superpowers/specs/2026-07-19-frontend-console-warnings-design.md：
 *  - 窄匹配：只拦框架警告，放行 src/api/request.ts 的 [ApiError] 等正当日志
 *  - 收集后在 afterEach 断言，而不是在 console 里直接 throw
 *    （throw 会被 Vue 内部 catch 吞掉，且堆栈错位、失败归属不准）
 */
import { afterEach, beforeEach, expect, vi } from 'vitest'

/** 认定为「框架警告」的模式。新增前先想清楚：它是噪音，还是在报真问题？ */
const FRAMEWORK_WARNING_PATTERNS = [/\[Vue warn\]/, /ElementPlusError/]

/** 本条测试内被显式豁免的模式，每个测试结束后清空 */
let allowed: RegExp[] = []
let captured: string[] = []

/**
 * 显式豁免某条框架警告，仅对当前测试生效。
 *
 * 用于「警告是被测行为本身的一部分」的场景（例如刻意传非法 prop 验证降级）。
 * 白名单是显式的、看得见的——不要为了让测试变绿而随手加。
 */
export function allowConsoleMessage(pattern: RegExp): void {
  allowed.push(pattern)
}

/**
 * Element Plus 用 `console.warn(new ElementPlusError(...))` 报弃用，错误名带在 Error.name 上
 * 而不在 message 里——只取 .message 会把 "ElementPlusError" 丢掉，模式就永远匹配不上。
 * 踩过一次，别改回去。
 */
function stringify(a: unknown): string {
  if (typeof a === 'string') return a
  if (a instanceof Error) return `${a.name}: ${a.message}`
  return String(a)
}

/** @returns 是否已被本守卫接管（true 表示不再透传给真 console） */
function record(args: unknown[]): boolean {
  const text = args.map(stringify).join(' ')
  if (!FRAMEWORK_WARNING_PATTERNS.some((p) => p.test(text))) return false
  if (allowed.some((p) => p.test(text))) return false
  // 只留首行：Vue warn 后面跟的组件树动辄上百行，收进报错信息里反而看不清
  captured.push(text.split('\n')[0].trim())
  return true
}

beforeEach(() => {
  allowed = []
  captured = []
  // 不匹配的日志（如 src/api/request.ts 的 [ApiError]）原样透传，
  // 否则守卫会把正当的调试信息一并吞掉，比不加守卫还难排查。
  const realWarn = console.warn.bind(console)
  const realError = console.error.bind(console)
  vi.spyOn(console, 'warn').mockImplementation((...args: unknown[]) => {
    if (!record(args)) realWarn(...args)
  })
  vi.spyOn(console, 'error').mockImplementation((...args: unknown[]) => {
    if (!record(args)) realError(...args)
  })
})

afterEach(() => {
  vi.mocked(console.warn).mockRestore?.()
  vi.mocked(console.error).mockRestore?.()
  if (captured.length === 0) return
  const list = [...new Set(captured)].map((m) => `  - ${m}`).join('\n')
  const total = captured.length
  captured = []
  expect.fail(
    `本测试产生了 ${total} 条框架警告（去重后如下）。请修根因，` +
      `确属被测行为的一部分再用 allowConsoleMessage() 显式豁免：\n${list}`,
  )
})
