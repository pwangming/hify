import { describe, it, expect } from 'vitest'
import {
  edgeId, presetGraph, toFlow, fromFlow, nextNodeId,
} from '@/views/workflow/composables/graphTransform'
import type { GraphDef } from '@/types/workflow'

/** W3a 风格的分支图（含 position 与 sourceHandle），当作后端读回的草稿。 */
const BRANCH: GraphDef = {
  nodes: [
    { id: 'start', type: 'start', data: {}, position: { x: 80, y: 200 } },
    { id: 'if_1', type: 'condition', data: { left: '{{start.q}}', operator: '==', right: '1' }, position: { x: 320, y: 200 } },
    { id: 'llm_1', type: 'llm', data: { modelId: '3', userPrompt: 'hi' }, position: { x: 560, y: 120 } },
    { id: 'end', type: 'end', data: {}, position: { x: 800, y: 200 } },
  ],
  edges: [
    { source: 'start', target: 'if_1', sourceHandle: null },
    { source: 'if_1', target: 'llm_1', sourceHandle: 'true' },
    { source: 'if_1', target: 'end', sourceHandle: 'false' },
  ],
}

describe('edgeId', () => {
  it('无 handle 用占位符 _，有 handle 用其值', () => {
    expect(edgeId({ source: 'start', target: 'llm_1' })).toBe('e-start-_-llm_1')
    expect(edgeId({ source: 'if_1', target: 'llm_1', sourceHandle: 'true' })).toBe('e-if_1-true-llm_1')
  })
})

describe('presetGraph', () => {
  it('空草稿预置 start/end：固定 id、不可删、不预连线、data 为空对象', () => {
    const { nodes, edges } = presetGraph()
    expect(nodes.map((n) => n.id)).toEqual(['start', 'end'])
    expect(nodes.every((n) => n.deletable === false)).toBe(true)
    expect(nodes.every((n) => Object.keys(n.data).length === 0)).toBe(true)
    expect(edges).toEqual([])
  })
})

describe('toFlow', () => {
  it('null 草稿返回预置图', () => {
    expect(toFlow(null).nodes.map((n) => n.id)).toEqual(['start', 'end'])
  })

  it('分支草稿：position 原样、sourceHandle 挂对、start/end 不可删、中间节点可删', () => {
    const { nodes, edges } = toFlow(BRANCH)
    expect(nodes.find((n) => n.id === 'if_1')?.position).toEqual({ x: 320, y: 200 })
    expect(nodes.find((n) => n.id === 'start')?.deletable).toBe(false)
    expect(nodes.find((n) => n.id === 'llm_1')?.deletable).toBe(true)
    const t = edges.find((e) => e.id === 'e-if_1-true-llm_1')
    expect(t).toMatchObject({ source: 'if_1', target: 'llm_1', sourceHandle: 'true' })
    // 普通边不带 sourceHandle 键（Vue Flow 里 undefined 与 null 行为不同，干脆不写）
    expect('sourceHandle' in edges.find((e) => e.id === 'e-start-_-if_1')!).toBe(false)
  })

  it('无 position 的老草稿按数组序网格兜底', () => {
    const legacy: GraphDef = {
      nodes: [
        { id: 'start', type: 'start', data: {} },
        { id: 'llm_1', type: 'llm', data: {} },
        { id: 'end', type: 'end', data: {} },
      ],
      edges: [],
    }
    const { nodes } = toFlow(legacy)
    const ps = nodes.map((n) => n.position)
    expect(ps[0]).toEqual({ x: 80, y: 80 })
    expect(ps[1]).toEqual({ x: 320, y: 80 })
    // 坐标互不重叠
    expect(new Set(ps.map((p) => `${p.x},${p.y}`)).size).toBe(3)
  })
})

describe('fromFlow / 往返保真', () => {
  it('toFlow → fromFlow 得到与后端草稿等价的图（剥掉前端边 id）', () => {
    const { nodes, edges } = toFlow(BRANCH)
    expect(fromFlow(nodes, edges)).toEqual(BRANCH)
  })

  it('坐标取整、targetHandle 不外漏', () => {
    const { nodes } = presetGraph()
    nodes[0].position = { x: 80.4, y: 199.6 }
    const edges = [{ id: 'e-start-_-end', source: 'start', target: 'end', targetHandle: null } as never]
    const graph = fromFlow(nodes, edges)
    expect(graph.nodes[0].position).toEqual({ x: 80, y: 200 })
    expect(graph.edges[0]).toEqual({ source: 'start', target: 'end', sourceHandle: null })
  })
})

describe('nextNodeId', () => {
  it('空画布从 1 起，类型前缀映射 llm/kb/if/http', () => {
    expect(nextNodeId([], 'llm')).toBe('llm_1')
    expect(nextNodeId([], 'knowledge-retrieval')).toBe('kb_1')
    expect(nextNodeId([], 'condition')).toBe('if_1')
    expect(nextNodeId([], 'http')).toBe('http_1')
  })

  it('取同前缀最大序号+1，不受其他前缀与非规范 id 干扰', () => {
    expect(nextNodeId(['start', 'llm_1', 'llm_9', 'kb_3', 'llm_x'], 'llm')).toBe('llm_10')
  })
})
