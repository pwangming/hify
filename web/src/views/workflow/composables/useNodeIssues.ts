import type { WorkflowNodeData, WorkflowNodeType } from '@/types/workflow'

/** condition operator 白名单，对齐后端 GraphValidator.CONDITION_OPERATORS。 */
export const CONDITION_OPERATORS = ['==', '!=', '>', '>=', '<', '<=', 'contains', 'notContains'] as const

/** http method 白名单，对齐后端 GraphValidator.HTTP_METHODS。 */
export const HTTP_METHODS = ['GET', 'POST', 'PUT', 'DELETE'] as const

function blank(v: unknown): boolean {
  return typeof v !== 'string' || v.trim() === ''
}

/**
 * 未配齐判定（提示性标红用）：严格镜像后端 validateAndOrder 的 require* 字段规则，
 * 只做字段级——图级问题（出边数/连通性/引用拓扑序）运行时后端 18001 兜底，不在此提示。
 * start/end 后端不强制任何字段，永远返回空数组。
 */
export function nodeIssues(
  type: WorkflowNodeType,
  data: WorkflowNodeData | null | undefined,
): string[] {
  const d = (data ?? {}) as Record<string, unknown>
  const issues: string[] = []
  if (type === 'llm') {
    if (blank(d.modelId) || !/^\d+$/.test(String(d.modelId))) issues.push('缺少模型')
    if (blank(d.userPrompt)) issues.push('缺少用户提示词')
  } else if (type === 'knowledge-retrieval') {
    if (!Array.isArray(d.datasetIds) || d.datasetIds.length === 0) issues.push('缺少知识库')
    if (blank(d.query)) issues.push('缺少检索内容')
  } else if (type === 'condition') {
    if (blank(d.left)) issues.push('缺少左值')
    if (blank(d.operator) || !(CONDITION_OPERATORS as readonly string[]).includes(String(d.operator))) {
      issues.push('缺少或非法的比较符')
    }
    if (blank(d.right)) issues.push('缺少右值')
  } else if (type === 'http') {
    if (blank(d.method) || !(HTTP_METHODS as readonly string[]).includes(String(d.method).toUpperCase())) {
      issues.push('缺少或非法的请求方法')
    }
    if (blank(d.url)) issues.push('缺少 URL')
  }
  return issues
}
