import { describe, it, expect } from 'vitest'
import { nodeIssues } from '@/views/workflow/composables/useNodeIssues'

describe('nodeIssues（严格镜像后端 validateAndOrder 的 require* 规则）', () => {
  it('start/end 永不标红（后端不强制）', () => {
    expect(nodeIssues('start', {})).toEqual([])
    expect(nodeIssues('end', {})).toEqual([])
    expect(nodeIssues('end', { outputs: [] })).toEqual([])
  })

  it('llm：缺模型/模型非数字/缺用户提示词', () => {
    expect(nodeIssues('llm', {})).toEqual(['缺少模型', '缺少用户提示词'])
    expect(nodeIssues('llm', { modelId: 'abc', userPrompt: 'hi' })).toEqual(['缺少模型'])
    expect(nodeIssues('llm', { modelId: '3', userPrompt: '  ' })).toEqual(['缺少用户提示词'])
    expect(nodeIssues('llm', { modelId: '3', userPrompt: 'hi' })).toEqual([])
  })

  it('knowledge-retrieval：缺知识库/缺检索内容', () => {
    expect(nodeIssues('knowledge-retrieval', {})).toEqual(['缺少知识库', '缺少检索内容'])
    expect(nodeIssues('knowledge-retrieval', { datasetIds: [], query: 'q' })).toEqual(['缺少知识库'])
    expect(nodeIssues('knowledge-retrieval', { datasetIds: ['5'], query: 'q' })).toEqual([])
  })

  it('condition：三元组缺失与 operator 白名单', () => {
    expect(nodeIssues('condition', {})).toEqual(['缺少左值', '缺少或非法的比较符', '缺少右值'])
    expect(nodeIssues('condition', { left: 'a', operator: '~=', right: 'b' }))
      .toEqual(['缺少或非法的比较符'])
    expect(nodeIssues('condition', { left: '{{start.q}}', operator: 'contains', right: 'x' }))
      .toEqual([])
  })

  it('http：method 白名单（大小写不敏感，同后端）与 url', () => {
    expect(nodeIssues('http', {})).toEqual(['缺少或非法的请求方法', '缺少 URL'])
    expect(nodeIssues('http', { method: 'get', url: 'https://a.com' })).toEqual([])
    expect(nodeIssues('http', { method: 'PATCH', url: 'https://a.com' }))
      .toEqual(['缺少或非法的请求方法'])
  })

  it('data 为 null/undefined 时按空对象处理', () => {
    expect(nodeIssues('llm', null)).toEqual(['缺少模型', '缺少用户提示词'])
    expect(nodeIssues('start', undefined)).toEqual([])
  })
})

describe('nodeIssues - code', () => {
  it('code 空 → 缺少代码', () => {
    expect(nodeIssues('code', {})).toContain('缺少代码')
    expect(nodeIssues('code', { code: '   ' })).toContain('缺少代码')
  })
  it('code 有内容 → 无问题', () => {
    expect(nodeIssues('code', { code: 'def main():\n    return {}' })).toEqual([])
  })
})
