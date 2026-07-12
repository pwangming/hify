import { describe, it, expect, beforeEach, vi } from 'vitest'
import { getDraft, saveDraft } from '@/api/workflow'
import { useWorkflowGraph } from '@/views/workflow/composables/useWorkflowGraph'
import type { DraftResponse, GraphDef } from '@/types/workflow'

vi.mock('@/api/workflow', () => ({ getDraft: vi.fn(), saveDraft: vi.fn() }))

const GRAPH: GraphDef = {
  nodes: [
    { id: 'start', type: 'start', data: {}, position: { x: 80, y: 200 } },
    { id: 'llm_1', type: 'llm', data: { modelId: '3', userPrompt: 'hi' }, position: { x: 320, y: 200 } },
    { id: 'end', type: 'end', data: {}, position: { x: 640, y: 200 } },
  ],
  edges: [
    { source: 'start', target: 'llm_1', sourceHandle: null },
    { source: 'llm_1', target: 'end', sourceHandle: null },
  ],
}
const DRAFT: DraftResponse = { graph: GRAPH, updateTime: '2026-07-11T10:00:00+08:00' }

describe('useWorkflowGraph', () => {
  beforeEach(() => vi.clearAllMocks())

  it('load：有草稿 → 转换进画布，savedAt 就位，dirty=false', async () => {
    vi.mocked(getDraft).mockResolvedValue(DRAFT)
    const g = useWorkflowGraph('42')
    await g.load()
    expect(g.nodes.value).toHaveLength(3)
    expect(g.edges.value[0].id).toBe('e-start-_-llm_1')
    expect(g.savedAt.value).toBe('2026-07-11T10:00:00+08:00')
    expect(g.dirty.value).toBe(false)
  })

  it('load：无草稿（null）→ 预置 start/end，dirty=false', async () => {
    vi.mocked(getDraft).mockResolvedValue(null)
    const g = useWorkflowGraph('42')
    await g.load()
    expect(g.nodes.value.map((n) => n.id)).toEqual(['start', 'end'])
    expect(g.savedAt.value).toBeNull()
    expect(g.dirty.value).toBe(false)
  })

  it('addNode：生成自增 id、data 空对象、可删；置 dirty', async () => {
    vi.mocked(getDraft).mockResolvedValue(DRAFT)
    const g = useWorkflowGraph('42')
    await g.load()
    const id = g.addNode('llm', { x: 400, y: 300 })
    expect(id).toBe('llm_2') // 画布已有 llm_1
    const added = g.nodes.value.find((n) => n.id === id)!
    expect(added).toMatchObject({ type: 'llm', data: {}, deletable: true })
    expect(g.dirty.value).toBe(true)
  })

  it('connect：按规则生成边 id；同一条边重复连线幂等', async () => {
    vi.mocked(getDraft).mockResolvedValue(null)
    const g = useWorkflowGraph('42')
    await g.load()
    g.connect({ source: 'start', target: 'end' })
    g.connect({ source: 'start', target: 'end' })
    expect(g.edges.value).toHaveLength(1)
    expect(g.edges.value[0].id).toBe('e-start-_-end')
    expect(g.dirty.value).toBe(true)
  })

  it('updateNodeData：合并补丁到指定节点且 dirty', async () => {
    vi.mocked(getDraft).mockResolvedValue(DRAFT)
    const g = useWorkflowGraph('42')
    await g.load()
    g.updateNodeData('llm_1', { userPrompt: 'hello {{start.q}}' })
    const llm = g.nodes.value.find((n) => n.id === 'llm_1')
    expect(llm?.data).toMatchObject({ modelId: '3', userPrompt: 'hello {{start.q}}' })
    expect(g.dirty.value).toBe(true)
  })

  it('updateNodeData：目标节点不存在时静默忽略', async () => {
    vi.mocked(getDraft).mockResolvedValue(DRAFT)
    const g = useWorkflowGraph('42')
    await g.load()
    g.updateNodeData('ghost', { userPrompt: 'x' })
    expect(g.dirty.value).toBe(false)
  })

  it('save：提交 fromFlow 结果；成功后 dirty 清除、savedAt 更新', async () => {
    vi.mocked(getDraft).mockResolvedValue(DRAFT)
    vi.mocked(saveDraft).mockResolvedValue({ ...DRAFT, updateTime: '2026-07-11T11:00:00+08:00' })
    const g = useWorkflowGraph('42')
    await g.load()
    g.addNode('http', { x: 100, y: 400 })
    await g.save()
    expect(saveDraft).toHaveBeenCalledWith('42', expect.objectContaining({
      nodes: expect.arrayContaining([expect.objectContaining({ id: 'http_1', type: 'http' })]),
    }))
    // 提交体里不允许出现前端专属的边 id 字段
    const sent = vi.mocked(saveDraft).mock.calls[0][1]
    expect(sent.edges.every((e) => !('id' in e))).toBe(true)
    expect(g.dirty.value).toBe(false)
    expect(g.savedAt.value).toBe('2026-07-11T11:00:00+08:00')
  })
})
