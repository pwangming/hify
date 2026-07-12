import { ref } from 'vue'

/** 一个可插入变量的表单字段。el 拿不到（或无光标）时追加到值末尾。 */
export interface InsertTarget {
  get: () => string
  set: (value: string) => void
  el?: () => HTMLInputElement | HTMLTextAreaElement | null | undefined
}

/**
 * 「最后聚焦的支持变量字段 + 光标处插入」（spec §4）。
 * defaultKey 用函数：end 表单的默认目标（最后一行 value）随行数变化。
 */
export function useVarInsert(defaultKey: () => string) {
  const targets = new Map<string, InsertTarget>()
  const focusedKey = ref<string | null>(null)

  function register(key: string, target: InsertTarget) {
    targets.set(key, target)
  }

  function onFocus(key: string) {
    focusedKey.value = key
  }

  function insert(text: string) {
    const key =
      focusedKey.value != null && targets.has(focusedKey.value) ? focusedKey.value : defaultKey()
    const target = targets.get(key)
    if (!target) return
    const value = target.get() ?? ''
    const el = target.el?.()
    const pos = el?.selectionStart ?? value.length
    target.set(value.slice(0, pos) + text + value.slice(pos))
  }

  return { register, onFocus, insert, focusedKey }
}
