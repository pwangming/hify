import { describe, it, expect, beforeEach, vi } from 'vitest'
import { computed, nextTick, ref } from 'vue'
import { runWorkflow } from '@/api/workflow'
import { useWorkflowRun } from '@/views/workflow/composables/useWorkflowRun'
import type { NodeRunView, RunResponse } from '@/types/workflow'

vi.mock('@/api/workflow', () => ({ runWorkflow: vi.fn() }))

function nodeRun(nodeId: string, status: NodeRunView['status']): NodeRunView {
  return {
    id: '1', nodeId, nodeType: 'llm', status, inputs: {}, outputs: { text: 'hi' },
    errorMessage: null, elapsedMs: '3100', createTime: '2026-07-12T10:00:00+08:00',
  }
}
const RUN: RunResponse = {
  id: '9', status: 'succeeded', inputs: {}, outputs: { answer: 'ok' },
  errorMessage: null, elapsedMs: '3200', createTime: '2026-07-12T10:00:00+08:00',
  nodeRuns: [nodeRun('start', 'succeeded'), nodeRun('llm_1', 'succeeded')],
}

function setup(dirtyInit = false, canSave = true) {
  const dirty = ref(dirtyInit)
  const save = vi.fn(async () => { dirty.value = false })
  const run = useWorkflowRun('42', { dirty, canSave: computed(() => canSave), save })
  return { dirty, save, run }
}

describe('useWorkflowRun', () => {
  beforeEach(() => vi.clearAllMocks())

  it('非 dirty 直接运行：不调 save，结果写入 lastRun 与 nodeRunMap，记住 lastInputs', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN)
    const { save, run } = setup(false)
    await run.triggerRun({ city: '北京' })
    expect(save).not.toHaveBeenCalled()
    expect(runWorkflow).toHaveBeenCalledWith('42', { city: '北京' })
    expect(run.lastRun.value?.status).toBe('succeeded')
    expect(run.nodeRunMap.value['llm_1'].outputs).toEqual({ text: 'hi' })
    expect(run.lastInputs.value).toEqual({ city: '北京' })
  })

  it('dirty → 先自动保存再运行', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN)
    const { save, run } = setup(true)
    await run.triggerRun({})
    expect(save).toHaveBeenCalledTimes(1)
    expect(save.mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(runWorkflow).mock.invocationCallOrder[0])
    expect(run.lastRun.value).not.toBeNull()
  })

  it('保存失败 → 中止运行（不发请求），running 复位', async () => {
    const dirty = ref(true)
    const save = vi.fn(async () => { throw new Error('10004') })
    const run = useWorkflowRun('42', { dirty, canSave: computed(() => true), save })
    await run.triggerRun({})
    expect(runWorkflow).not.toHaveBeenCalled()
    expect(run.running.value).toBe(false)
    expect(run.lastRun.value).toBeNull()
  })

  it('canSave=false（非 owner）且 dirty → 跳过保存直接运行，结果可见', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN)
    const { save, run } = setup(true, false)
    await run.triggerRun({})
    expect(save).not.toHaveBeenCalled()
    expect(run.lastRun.value?.status).toBe('succeeded')
  })

  it('HTTP 错误（运行未发生）→ 保留上一次结果', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN)
    const { run } = setup(false)
    await run.triggerRun({})
    vi.mocked(runWorkflow).mockRejectedValue(new Error('18001'))
    await run.triggerRun({})
    expect(run.lastRun.value?.id).toBe('9')
    expect(run.running.value).toBe(false)
  })

  it('图一改（dirty 置真）→ 结果清空', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN)
    const { dirty, run } = setup(false)
    await run.triggerRun({})
    expect(run.lastRun.value).not.toBeNull()
    dirty.value = true
    await nextTick()
    expect(run.lastRun.value).toBeNull()
    expect(run.nodeRunMap.value).toEqual({})
  })

  it('运行期间改图 → 响应到达后丢弃展示（结果映在新图上会误导）', async () => {
    const { dirty, run } = setup(false)
    let resolveRun!: (r: RunResponse) => void
    vi.mocked(runWorkflow).mockReturnValue(new Promise((r) => { resolveRun = r }) as never)
    const p = run.triggerRun({})
    expect(run.running.value).toBe(true)
    dirty.value = true
    await nextTick()
    resolveRun(RUN)
    await p
    expect(run.lastRun.value).toBeNull()
  })
})
