import { describe, it, expect } from 'vitest'
import { upstreamVars } from '@/views/workflow/composables/useUpstreamVars'
import type { FlowEdge, FlowNode } from '@/views/workflow/composables/graphTransform'

function n(id: string, type: FlowNode['type'], data: FlowNode['data'] = {}): FlowNode {
  return { id, type, data, position: { x: 0, y: 0 }, deletable: true }
}
function e(source: string, target: string, sourceHandle?: string): FlowEdge {
  return { id: `e-${source}-${sourceHandle ?? '_'}-${target}`, source, target, ...(sourceHandle ? { sourceHandle } : {}) }
}

describe('upstreamVars（沿入边反向遍历祖先）', () => {
  const nodes = [
    n('start', 'start', { inputs: [{ name: 'city' }, { name: '' }] }),
    n('kb_1', 'knowledge-retrieval'),
    n('if_1', 'condition'),
    n('llm_1', 'llm'),
    n('http_1', 'http'),
    n('end', 'end'),
  ]

  it('链式：llm_1 的祖先是 kb_1 与 start（近者在前），字段对齐 executor 输出', () => {
    const edges = [e('start', 'kb_1'), e('kb_1', 'llm_1')]
    expect(upstreamVars('llm_1', nodes, edges)).toEqual([
      { nodeId: 'kb_1', type: 'knowledge-retrieval', fields: ['text', 'count'] },
      { nodeId: 'start', type: 'start', fields: ['city'] },
    ])
  })

  it('分支汇聚：end 能看到 true/false 两侧节点', () => {
    const edges = [
      e('start', 'if_1'), e('if_1', 'llm_1', 'true'), e('if_1', 'http_1', 'false'),
      e('llm_1', 'end'), e('http_1', 'end'),
    ]
    const ids = upstreamVars('end', nodes, edges).map((v) => v.nodeId)
    expect(ids).toContain('llm_1')
    expect(ids).toContain('http_1')
    expect(ids).toContain('if_1')
    expect(ids).toContain('start')
    const http = upstreamVars('end', nodes, edges).find((v) => v.nodeId === 'http_1')
    expect(http?.fields).toEqual(['status', 'body', 'headers'])
    const cond = upstreamVars('end', nodes, edges).find((v) => v.nodeId === 'if_1')
    expect(cond?.fields).toEqual(['result'])
  })

  it('未连线节点：祖先为空', () => {
    expect(upstreamVars('llm_1', nodes, [])).toEqual([])
  })

  it('环不死循环（画布可画环，运行时才报错）', () => {
    const edges = [e('llm_1', 'http_1'), e('http_1', 'llm_1')]
    const vars = upstreamVars('llm_1', nodes, edges)
    expect(vars.map((v) => v.nodeId)).toEqual(['http_1'])
  })

  it('start 未声明 inputs：出现在列表但 fields 为空（面板提示未声明）', () => {
    const bare = [n('start', 'start'), n('llm_1', 'llm')]
    expect(upstreamVars('llm_1', bare, [e('start', 'llm_1')])).toEqual([
      { nodeId: 'start', type: 'start', fields: [] },
    ])
  })
})
