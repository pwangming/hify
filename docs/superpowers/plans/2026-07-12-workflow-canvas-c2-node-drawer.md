# 画布 C2：六类节点配置抽屉 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 画布节点单击打开右侧配置抽屉，六类节点各自表单即时写回 data，未配齐标红徽章，变量面板点击插入 `{{nodeId.field}}`。

**Architecture:** 抽屉壳（NodeConfigDrawer）+ 六个按类型拆分的子表单组件 + 变量面板；判定/遍历/插入三块纯逻辑收进 composables 单测。纯前端（web/），后端零改动。Spec：`docs/superpowers/specs/2026-07-12-workflow-canvas-c2-design.md`。

**Tech Stack:** Vue 3 `<script setup>` + TypeScript + Element Plus + Vue Flow + vitest（@vue/test-utils）。

## Global Constraints

- **后端零改动**：`server/` 目录任何文件不许动。
- 工作目录：前端命令一律在 `/home/wang/playlab/hify/web` 下执行；git 命令在仓库根。
- **Vue 模板大坑（本计划反复出现）**：`{{ }}` 在**文本节点**会被当插值解析。变量示例文案（如 `{{kb_1.text}}`）只能放 `placeholder` 等**属性**里（属性不做插值，安全），绝不能直接写进标签文本；若必须出现在文本里，用 `<code v-text="'{{llm_1.text}}'" />`。
- 变量语法 `{{nodeId.field}}`，名字字符集 `[\w-]+`（对齐后端 `GraphValidator.VAR` 正则）。
- 组件用 Element Plus（el-form/el-input/el-select/el-drawer/el-tag/el-tooltip/el-empty），图标用 `@element-plus/icons-vue`，禁止自造轮子。
- 表单组件通用契约：`props: { data: <该类型NodeData>; disabled: boolean }`，`emits: { update: [patch: <该类型NodeData>] }`，有变量字段的表单 `defineExpose({ insertVar(text: string) })`。补丁是**不可变副本**（数组/对象整体替换，不原地改 props）。
- `data-test` 属性沿用 kebab 命名（既有先例 `wf-save`）。
- 测试放对应目录 `__tests__/`，文件名 `<name>.spec.ts`。
- 每个 Task 走 TDD（先写失败测试）并单独 commit；**本计划文件是执行账本：完成一步勾一格，勾选随该 Task 一起提交，只许勾选不许改其他内容**。
- 样式 SCSS 用既有全局变量（`$spacing-sm`/`$spacing-md`），色彩用 Element Plus CSS 变量（如 `var(--el-color-warning)`）。

---

### Task 1: 类型收窄（types/workflow.ts）

**Files:**
- Modify: `web/src/types/workflow.ts`
- Modify: `web/src/views/workflow/composables/graphTransform.ts:13`（FlowNode.data 类型）
- Modify: `web/src/views/workflow/composables/useWorkflowGraph.ts`（import 类型对齐，如有编译错）

**Interfaces:**
- Produces: `StartInputDecl / StartNodeData / LlmNodeData / KnowledgeNodeData / ConditionNodeData / HttpNodeData / EndOutputDecl / EndNodeData / NodeDataMap / WorkflowNodeData`（后续所有 Task 引用这些名字，不得改名）

纯类型改动无运行时行为，本 Task 以 typecheck 代替失败测试。

- [x] **Step 1: 修改 types/workflow.ts**

把 `GraphNode` 的 `data: Record<string, unknown>` 替换为按类型的联合。在 `WorkflowNodeType` 定义之后插入：

```ts
/** start 节点输入声明项。纯前端约定：引擎透传触发入参、不读 data；用途=下游变量提示+C3 运行表单预填。 */
export interface StartInputDecl {
  name: string
}

export interface StartNodeData {
  inputs?: StartInputDecl[]
}

export interface LlmNodeData {
  modelId?: string
  systemPrompt?: string
  userPrompt?: string
}

export interface KnowledgeNodeData {
  datasetIds?: string[]
  query?: string
}

export interface ConditionNodeData {
  left?: string
  operator?: string
  right?: string
}

export interface HttpNodeData {
  method?: string
  url?: string
  headers?: Record<string, string>
  body?: string
}

/** end 输出声明项：value 是模板，典型值 {{llm_1.text}}。 */
export interface EndOutputDecl {
  name: string
  value: string
}

export interface EndNodeData {
  outputs?: EndOutputDecl[]
}

export interface NodeDataMap {
  start: StartNodeData
  llm: LlmNodeData
  'knowledge-retrieval': KnowledgeNodeData
  condition: ConditionNodeData
  http: HttpNodeData
  end: EndNodeData
}

/** 各类型 data 的并集；字段全可选（草稿允许半成品，必填是运行时语义）。 */
export type WorkflowNodeData = NodeDataMap[WorkflowNodeType]
```

`GraphNode` 改为：

```ts
/** 对齐后端 GraphNode record。data 为节点私有配置，按类型收窄（C2）。 */
export interface GraphNode {
  id: string
  type: WorkflowNodeType
  data: WorkflowNodeData
  /** API 手拼的老草稿可能无坐标，加载时网格兜底。 */
  position?: GraphNodePosition | null
}
```

- [x] **Step 2: graphTransform.ts 的 FlowNode.data 同步收窄**

```ts
// import 行改为
import type { GraphDef, GraphNodePosition, WorkflowNodeData, WorkflowNodeType } from '@/types/workflow'

// FlowNode 接口里
  data: WorkflowNodeData
```

- [x] **Step 3: typecheck + 全量测试确认无涟漪**

Run: `pnpm typecheck && pnpm test`
Expected: 全绿（既有 spec 的 data 字面量已用真实字段名 `modelId`/`left` 等，应无错；若个别字面量报错，改成合法字段名而非加 as 断言）

- [x] **Step 4: Commit**

```bash
git add web/src/types/workflow.ts web/src/views/workflow/composables/graphTransform.ts web/src/views/workflow/composables/useWorkflowGraph.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "refactor(web/workflow): GraphNode.data 收窄为按类型联合（C1 留账）"
```

---

### Task 2: useNodeIssues 未配齐判定纯函数

**Files:**
- Create: `web/src/views/workflow/composables/useNodeIssues.ts`
- Test: `web/src/views/workflow/composables/__tests__/useNodeIssues.spec.ts`

**Interfaces:**
- Consumes: Task 1 的类型
- Produces: `nodeIssues(type: WorkflowNodeType, data: WorkflowNodeData | null | undefined): string[]`；常量 `CONDITION_OPERATORS`（8 个）、`HTTP_METHODS`（4 个）——Task 7/10 的下拉选项直接引用

- [x] **Step 1: 写失败测试**

```ts
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
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/composables/__tests__/useNodeIssues.spec.ts`
Expected: FAIL（模块不存在）

- [x] **Step 3: 实现**

```ts
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
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/composables/__tests__/useNodeIssues.spec.ts`
Expected: PASS（6 个用例全绿）

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/composables/useNodeIssues.ts web/src/views/workflow/composables/__tests__/useNodeIssues.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): 未配齐判定纯函数（镜像后端 require* 规则）"
```

---

### Task 3: useUpstreamVars 可引用变量纯函数

**Files:**
- Create: `web/src/views/workflow/composables/useUpstreamVars.ts`
- Test: `web/src/views/workflow/composables/__tests__/useUpstreamVars.spec.ts`

**Interfaces:**
- Consumes: `FlowNode`/`FlowEdge`（graphTransform）、`StartNodeData`
- Produces: `interface UpstreamVar { nodeId: string; type: WorkflowNodeType; fields: string[] }`；`upstreamVars(nodeId: string, nodes: FlowNode[], edges: FlowEdge[]): UpstreamVar[]`（BFS 近者在前）

- [x] **Step 1: 写失败测试**

```ts
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
      { nodeId: 'start', type: 'start', fields: ['city'] }, // 空名声明被过滤
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
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/composables/__tests__/useUpstreamVars.spec.ts`
Expected: FAIL（模块不存在）

- [x] **Step 3: 实现**

```ts
import type { StartNodeData, WorkflowNodeType } from '@/types/workflow'
import type { FlowEdge, FlowNode } from './graphTransform'

/** 一个祖先节点及其可引用输出字段。 */
export interface UpstreamVar {
  nodeId: string
  type: WorkflowNodeType
  fields: string[]
}

/** 各类型节点的输出字段，对齐各 NodeExecutor 的 NodeResult outputs 键；start 特殊=声明的 inputs。 */
const OUTPUT_FIELDS: Record<WorkflowNodeType, string[]> = {
  start: [],
  llm: ['text'],
  'knowledge-retrieval': ['text', 'count'],
  condition: ['result'],
  http: ['status', 'body', 'headers'],
  end: [],
}

/**
 * 沿入边反向 BFS 收集祖先（近者在前，visited 防环）。
 * 与后端「只能引用拓扑序更早的节点」在已连线图上等价——只提示合法可引用项。
 */
export function upstreamVars(nodeId: string, nodes: FlowNode[], edges: FlowEdge[]): UpstreamVar[] {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const result: UpstreamVar[] = []
  const visited = new Set<string>([nodeId])
  const queue = [nodeId]
  while (queue.length > 0) {
    const cur = queue.shift()!
    for (const edge of edges) {
      if (edge.target !== cur || visited.has(edge.source)) continue
      visited.add(edge.source)
      queue.push(edge.source)
      const node = byId.get(edge.source)
      if (!node) continue
      const fields =
        node.type === 'start'
          ? ((node.data as StartNodeData).inputs ?? []).map((i) => i.name).filter((name) => name !== '')
          : OUTPUT_FIELDS[node.type]
      result.push({ nodeId: node.id, type: node.type, fields })
    }
  }
  return result
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/composables/__tests__/useUpstreamVars.spec.ts`
Expected: PASS（5 个用例全绿）

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/composables/useUpstreamVars.ts web/src/views/workflow/composables/__tests__/useUpstreamVars.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): 可引用变量计算纯函数（祖先遍历+防环）"
```

---

### Task 4: useWorkflowGraph.updateNodeData

**Files:**
- Modify: `web/src/views/workflow/composables/useWorkflowGraph.ts`
- Test: `web/src/views/workflow/composables/__tests__/useWorkflowGraph.spec.ts`（追加用例）

**Interfaces:**
- Produces: `updateNodeData(id: string, patch: WorkflowNodeData): void`（合并写回；Task 13/15 经由它落数据）

- [x] **Step 1: 在既有 spec 追加失败用例**

```ts
it('updateNodeData：合并补丁到指定节点且 dirty', async () => {
  const g = useWorkflowGraph('42')
  await g.load() // 既有 beforeEach 已 mock getDraft
  g.updateNodeData('llm_1', { userPrompt: 'hello {{start.q}}' })
  const llm = g.nodes.value.find((n) => n.id === 'llm_1')
  expect(llm?.data).toMatchObject({ modelId: '3', userPrompt: 'hello {{start.q}}' })
  expect(g.dirty.value).toBe(true)
})

it('updateNodeData：目标节点不存在时静默忽略', async () => {
  const g = useWorkflowGraph('42')
  await g.load()
  g.updateNodeData('ghost', { userPrompt: 'x' })
  expect(g.dirty.value).toBe(false)
})
```

（既有 spec 的 mock 草稿含 `llm_1`（data `{ modelId: '3', userPrompt: 'hi' }`）；若用例上下文不同，按该文件既有 fixture 名对齐，语义不变。）

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/composables/__tests__/useWorkflowGraph.spec.ts`
Expected: FAIL（updateNodeData is not a function）

- [x] **Step 3: 实现**

在 `useWorkflowGraph.ts` 里（`connect` 之后）加：

```ts
/** 抽屉表单即时写回：合并补丁到节点 data（不可变副本，dirty 由快照对比自动感知）。 */
function updateNodeData(id: string, patch: WorkflowNodeData) {
  const node = nodes.value.find((n) => n.id === id)
  if (!node) return
  // 联合类型无法直接展开合并，收窄为普通对象拼接（运行时都是普通 jsonb 对象）
  node.data = {
    ...(node.data as Record<string, unknown>),
    ...(patch as Record<string, unknown>),
  } as WorkflowNodeData
}
```

import 行加 `WorkflowNodeData`，return 对象加 `updateNodeData`。

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/composables/__tests__/useWorkflowGraph.spec.ts`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/composables/useWorkflowGraph.ts web/src/views/workflow/composables/__tests__/useWorkflowGraph.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): updateNodeData 合并写回节点配置"
```

---

### Task 5: useVarInsert 光标插入 composable

**Files:**
- Create: `web/src/views/workflow/composables/useVarInsert.ts`
- Test: `web/src/views/workflow/composables/__tests__/useVarInsert.spec.ts`

**Interfaces:**
- Produces: `interface InsertTarget { get(): string; set(v: string): void; el?(): HTMLInputElement | HTMLTextAreaElement | null | undefined }`；`useVarInsert(defaultKey: () => string)` 返回 `{ register(key, target), onFocus(key), insert(text), focusedKey }`——所有含变量字段的表单（Task 7~12）复用

- [x] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest'
import { useVarInsert } from '@/views/workflow/composables/useVarInsert'

function makeTarget(initial: string) {
  let value = initial
  return {
    target: { get: () => value, set: (v: string) => { value = v } },
    read: () => value,
  }
}

describe('useVarInsert', () => {
  it('未聚焦过：插入默认字段末尾', () => {
    const { register, insert } = useVarInsert(() => 'url')
    const a = makeTarget('https://a.com?q=')
    register('url', a.target)
    insert('{{start.q}}')
    expect(a.read()).toBe('https://a.com?q={{start.q}}')
  })

  it('聚焦过 body：插入 body 而非默认字段', () => {
    const { register, onFocus, insert } = useVarInsert(() => 'url')
    const url = makeTarget('u')
    const body = makeTarget('x')
    register('url', url.target)
    register('body', body.target)
    onFocus('body')
    insert('{{llm_1.text}}')
    expect(url.read()).toBe('u')
    expect(body.read()).toBe('x{{llm_1.text}}')
  })

  it('提供 el 时按光标位置插入', () => {
    const { register, insert } = useVarInsert(() => 'left')
    const t = makeTarget('ab')
    register('left', { ...t.target, el: () => ({ selectionStart: 1 }) as HTMLInputElement })
    insert('{{kb_1.text}}')
    expect(t.read()).toBe('a{{kb_1.text}}b')
  })

  it('聚焦的字段已注销（如行被删）：回落默认字段', () => {
    const { register, onFocus, insert } = useVarInsert(() => 'left')
    const t = makeTarget('')
    register('left', t.target)
    onFocus('value_5') // 从未 register 的 key
    insert('{{start.q}}')
    expect(t.read()).toBe('{{start.q}}')
  })

  it('默认字段也不存在：静默忽略', () => {
    const { insert } = useVarInsert(() => 'nope')
    expect(() => insert('{{x.y}}')).not.toThrow()
  })
})
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/composables/__tests__/useVarInsert.spec.ts`
Expected: FAIL（模块不存在）

- [x] **Step 3: 实现**

```ts
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
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/composables/__tests__/useVarInsert.spec.ts`
Expected: PASS（5 个用例全绿）

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/composables/useVarInsert.ts web/src/views/workflow/composables/__tests__/useVarInsert.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): 变量插入 composable（最后聚焦字段+光标处插入）"
```

---

### Task 6: VariablePanel 可引用变量面板

**Files:**
- Create: `web/src/views/workflow/components/VariablePanel.vue`
- Test: `web/src/views/workflow/components/__tests__/VariablePanel.spec.ts`

**Interfaces:**
- Consumes: `UpstreamVar`（Task 3）
- Produces: props `{ vars: UpstreamVar[]; disabled?: boolean }`，emits `insert: [varRef: string]`（varRef 形如 `{{kb_1.text}}`）

- [x] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import VariablePanel from '@/views/workflow/components/VariablePanel.vue'
import type { UpstreamVar } from '@/views/workflow/composables/useUpstreamVars'

const VARS: UpstreamVar[] = [
  { nodeId: 'kb_1', type: 'knowledge-retrieval', fields: ['text', 'count'] },
  { nodeId: 'start', type: 'start', fields: [] },
]

function mountPanel(props: { vars: UpstreamVar[]; disabled?: boolean }) {
  return mount(VariablePanel, { props, global: { plugins: [ElementPlus] } })
}

describe('VariablePanel', () => {
  it('按祖先分组列出字段标签', () => {
    const w = mountPanel({ vars: VARS })
    expect(w.text()).toContain('kb_1')
    const tags = w.findAll('[data-test="var-tag"]')
    expect(tags.map((t) => t.text())).toEqual(['text', 'count'])
  })

  it('点击标签 → emit insert 完整变量引用', async () => {
    const w = mountPanel({ vars: VARS })
    await w.findAll('[data-test="var-tag"]')[0].trigger('click')
    expect(w.emitted('insert')).toEqual([['{{kb_1.text}}']])
  })

  it('disabled 时点击不 emit', async () => {
    const w = mountPanel({ vars: VARS, disabled: true })
    await w.findAll('[data-test="var-tag"]')[0].trigger('click')
    expect(w.emitted('insert')).toBeUndefined()
  })

  it('无祖先 → 空态文案', () => {
    const w = mountPanel({ vars: [] })
    expect(w.text()).toContain('连线后这里会列出可引用的上游输出')
  })

  it('start 未声明输入 → 组内提示', () => {
    const w = mountPanel({ vars: VARS })
    expect(w.text()).toContain('未声明输入')
  })
})
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/__tests__/VariablePanel.spec.ts`
Expected: FAIL（组件不存在）

- [x] **Step 3: 实现**

```vue
<script setup lang="ts">
import type { UpstreamVar } from '../composables/useUpstreamVars'

const props = defineProps<{ vars: UpstreamVar[]; disabled?: boolean }>()
const emit = defineEmits<{ insert: [varRef: string] }>()

function onClick(nodeId: string, field: string) {
  if (props.disabled) return
  emit('insert', `{{${nodeId}.${field}}}`)
}
</script>

<template>
  <div class="var-panel" data-test="var-panel">
    <div class="var-panel__title">可引用变量</div>
    <el-empty
      v-if="vars.length === 0"
      description="连线后这里会列出可引用的上游输出"
      :image-size="48"
    />
    <div v-for="v in vars" :key="v.nodeId" class="var-panel__group">
      <div class="var-panel__node">{{ v.nodeId }}</div>
      <template v-if="v.fields.length > 0">
        <el-tag
          v-for="f in v.fields"
          :key="f"
          class="var-panel__tag"
          :class="{ 'var-panel__tag--disabled': disabled }"
          data-test="var-tag"
          @click="onClick(v.nodeId, f)"
          >{{ f }}</el-tag
        >
      </template>
      <span v-else class="var-panel__hint">未声明输入</span>
    </div>
  </div>
</template>

<style scoped lang="scss">
.var-panel {
  margin-top: $spacing-md;
  padding-top: $spacing-md;
  border-top: 1px solid var(--el-border-color-lighter);
}
.var-panel__title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: $spacing-sm;
}
.var-panel__group {
  margin-bottom: $spacing-sm;
}
.var-panel__node {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.var-panel__tag {
  margin-right: $spacing-sm;
  cursor: pointer;
}
.var-panel__tag--disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.var-panel__hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/__tests__/VariablePanel.spec.ts`
Expected: PASS（5 个用例全绿）

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/VariablePanel.vue web/src/views/workflow/components/__tests__/VariablePanel.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): 可引用变量面板（点击插入）"
```

---

### Task 7: ConditionForm 条件分支表单

**Files:**
- Create: `web/src/views/workflow/components/forms/ConditionForm.vue`
- Test: `web/src/views/workflow/components/forms/__tests__/ConditionForm.spec.ts`

**Interfaces:**
- Consumes: `ConditionNodeData`、`CONDITION_OPERATORS`（Task 2）、`useVarInsert`（Task 5）
- Produces: props `{ data: ConditionNodeData; disabled: boolean }`，emits `update: [patch: ConditionNodeData]`，expose `insertVar(text)`；根元素 `data-test="form-condition"`（Task 13 断言用；六个表单同规则 `form-<type>`）

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConditionForm from '@/views/workflow/components/forms/ConditionForm.vue'
import type { ConditionNodeData } from '@/types/workflow'

function mountForm(data: ConditionNodeData = {}, disabled = false) {
  return mount(ConditionForm, {
    props: { data, disabled },
    global: { plugins: [ElementPlus] },
  })
}

describe('ConditionForm', () => {
  it('回显 left/right', () => {
    const w = mountForm({ left: '{{kb_1.count}}', operator: '>', right: '0' })
    expect((w.find('[data-test="cond-left"] input').element as HTMLInputElement).value)
      .toBe('{{kb_1.count}}')
    expect((w.find('[data-test="cond-right"] input').element as HTMLInputElement).value).toBe('0')
  })

  it('改左值 → emit update 补丁', async () => {
    const w = mountForm()
    await w.find('[data-test="cond-left"] input').setValue('{{start.q}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ left: '{{start.q}}' }])
  })

  it('insertVar 默认插入 left 末尾', () => {
    const w = mountForm({ left: 'a=' })
    ;(w.vm as unknown as { insertVar: (t: string) => void }).insertVar('{{kb_1.text}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ left: 'a={{kb_1.text}}' }])
  })

  it('disabled 时输入框禁用', () => {
    const w = mountForm({}, true)
    expect(w.find('[data-test="cond-left"] input').attributes('disabled')).toBeDefined()
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/forms/__tests__/ConditionForm.spec.ts`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 实现**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import type { InputInstance } from 'element-plus'
import type { ConditionNodeData } from '@/types/workflow'
import { CONDITION_OPERATORS } from '../../composables/useNodeIssues'
import { useVarInsert } from '../../composables/useVarInsert'

const props = defineProps<{ data: ConditionNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: ConditionNodeData] }>()

const leftRef = ref<InputInstance>()
const rightRef = ref<InputInstance>()
const { register, onFocus, insert } = useVarInsert(() => 'left')
register('left', {
  get: () => props.data.left ?? '',
  set: (v) => emit('update', { left: v }),
  el: () => leftRef.value?.input,
})
register('right', {
  get: () => props.data.right ?? '',
  set: (v) => emit('update', { right: v }),
  el: () => rightRef.value?.input,
})
defineExpose({ insertVar: insert })
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-condition">
    <el-form-item label="左值" required>
      <el-input
        ref="leftRef"
        data-test="cond-left"
        :model-value="data.left ?? ''"
        placeholder="可引用变量，如 {{kb_1.count}}"
        @update:model-value="emit('update', { left: $event })"
        @focus="onFocus('left')"
      />
    </el-form-item>
    <el-form-item label="比较符" required>
      <el-select
        data-test="cond-operator"
        :model-value="data.operator"
        placeholder="选择比较符"
        @update:model-value="emit('update', { operator: $event })"
      >
        <el-option v-for="op in CONDITION_OPERATORS" :key="op" :value="op" :label="op" />
      </el-select>
    </el-form-item>
    <el-form-item label="右值" required>
      <el-input
        ref="rightRef"
        data-test="cond-right"
        :model-value="data.right ?? ''"
        placeholder="常量或变量"
        @update:model-value="emit('update', { right: $event })"
        @focus="onFocus('right')"
      />
    </el-form-item>
  </el-form>
</template>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/forms/__tests__/ConditionForm.spec.ts`
Expected: PASS（4 个用例全绿）

- [ ] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/forms/ConditionForm.vue web/src/views/workflow/components/forms/__tests__/ConditionForm.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): condition 节点配置表单"
```

---

### Task 8: LlmForm 模型+提示词表单

**Files:**
- Create: `web/src/views/workflow/components/forms/LlmForm.vue`
- Test: `web/src/views/workflow/components/forms/__tests__/LlmForm.spec.ts`

**Interfaces:**
- Consumes: `LlmNodeData`、`listChatModels`（`@/api/provider`，返回 `ModelOption[]`：`{ id, name, type, providerName }`）、`useVarInsert`
- Produces: props/emits 同通用契约，expose `insertVar`；根元素 `data-test="form-llm"`

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { listChatModels } from '@/api/provider'
import LlmForm from '@/views/workflow/components/forms/LlmForm.vue'
import type { LlmNodeData } from '@/types/workflow'

vi.mock('@/api/provider', () => ({ listChatModels: vi.fn() }))

const MODELS = [
  { id: '3', name: 'qwen-max', type: 'chat', providerName: '通义' },
  { id: '5', name: 'claude', type: 'chat', providerName: 'Anthropic' },
]

function mountForm(data: LlmNodeData = {}, disabled = false) {
  return mount(LlmForm, { props: { data, disabled }, global: { plugins: [ElementPlus] } })
}

describe('LlmForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listChatModels).mockResolvedValue(MODELS)
  })

  it('挂载拉取模型列表作为选项', async () => {
    mountForm()
    await flushPromises()
    expect(listChatModels).toHaveBeenCalled()
  })

  it('所选模型已失效 → 禁用兜底项，不裸露数字 id', async () => {
    const w = mountForm({ modelId: '99' })
    await flushPromises()
    const vm = w.vm as unknown as {
      selectOptions: { value: string; label: string; disabled: boolean }[]
    }
    expect(vm.selectOptions[0]).toEqual({
      value: '99',
      label: '已失效模型（已停用）',
      disabled: true,
    })
  })

  it('改用户提示词 → emit update', async () => {
    const w = mountForm()
    await flushPromises()
    await w.find('[data-test="llm-user-prompt"] textarea').setValue('总结：{{kb_1.text}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ userPrompt: '总结：{{kb_1.text}}' }])
  })

  it('insertVar 默认插入 userPrompt 末尾', async () => {
    const w = mountForm({ userPrompt: '内容：' })
    await flushPromises()
    ;(w.vm as unknown as { insertVar: (t: string) => void }).insertVar('{{kb_1.text}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ userPrompt: '内容：{{kb_1.text}}' }])
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/forms/__tests__/LlmForm.spec.ts`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 实现**

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { InputInstance } from 'element-plus'
import { listChatModels } from '@/api/provider'
import type { ModelOption } from '@/types/model'
import type { LlmNodeData } from '@/types/workflow'
import { useVarInsert } from '../../composables/useVarInsert'

const props = defineProps<{ data: LlmNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: LlmNodeData] }>()

const modelOptions = ref<ModelOption[]>([])
onMounted(async () => {
  try {
    modelOptions.value = await listChatModels()
  } catch {
    /* 失败由 request 拦截器统一 toast；下拉留空 */
  }
})

/** 可用模型 + 所选但已失效的模型作禁用项，避免裸露 modelId 数字（同 AppList 手法）。 */
const selectOptions = computed(() => {
  const opts = modelOptions.value.map((m) => ({
    value: m.id,
    label: `${m.providerName} / ${m.name}`,
    disabled: false,
  }))
  if (props.data.modelId && !modelOptions.value.some((m) => m.id === props.data.modelId)) {
    opts.unshift({ value: props.data.modelId, label: '已失效模型（已停用）', disabled: true })
  }
  return opts
})

const userPromptRef = ref<InputInstance>()
const { register, onFocus, insert } = useVarInsert(() => 'userPrompt')
register('userPrompt', {
  get: () => props.data.userPrompt ?? '',
  set: (v) => emit('update', { userPrompt: v }),
  el: () => userPromptRef.value?.textarea,
})
defineExpose({ insertVar: insert, selectOptions })
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-llm">
    <el-form-item label="模型" required>
      <el-select
        data-test="llm-model"
        :model-value="data.modelId"
        placeholder="选择模型"
        @update:model-value="emit('update', { modelId: $event })"
      >
        <el-option
          v-for="o in selectOptions"
          :key="o.value"
          :value="o.value"
          :label="o.label"
          :disabled="o.disabled"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="系统提示词">
      <el-input
        data-test="llm-system-prompt"
        type="textarea"
        :rows="3"
        :model-value="data.systemPrompt ?? ''"
        placeholder="可选"
        @update:model-value="emit('update', { systemPrompt: $event })"
      />
    </el-form-item>
    <el-form-item label="用户提示词" required>
      <el-input
        ref="userPromptRef"
        data-test="llm-user-prompt"
        type="textarea"
        :rows="6"
        :model-value="data.userPrompt ?? ''"
        placeholder="可引用变量，如 {{kb_1.text}}"
        @update:model-value="emit('update', { userPrompt: $event })"
        @focus="onFocus('userPrompt')"
      />
    </el-form-item>
  </el-form>
</template>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/forms/__tests__/LlmForm.spec.ts`
Expected: PASS（4 个用例全绿）

- [ ] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/forms/LlmForm.vue web/src/views/workflow/components/forms/__tests__/LlmForm.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): llm 节点配置表单（模型选择+提示词）"
```

---

### Task 9: KnowledgeForm 知识检索表单

**Files:**
- Create: `web/src/views/workflow/components/forms/KnowledgeForm.vue`
- Test: `web/src/views/workflow/components/forms/__tests__/KnowledgeForm.spec.ts`

**Interfaces:**
- Consumes: `KnowledgeNodeData`、`listDatasets`（`@/api/knowledge`，签名 `listDatasets({ page, size })` 返回 `{ list: Dataset[] }`，Dataset 含 `{ id, name }`）、`useVarInsert`
- Produces: props/emits 同通用契约，expose `insertVar`；根元素 `data-test="form-knowledge-retrieval"`

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { listDatasets } from '@/api/knowledge'
import KnowledgeForm from '@/views/workflow/components/forms/KnowledgeForm.vue'
import type { KnowledgeNodeData } from '@/types/workflow'

vi.mock('@/api/knowledge', () => ({ listDatasets: vi.fn() }))

const DATASETS = {
  list: [
    { id: '5', name: '产品手册' },
    { id: '8', name: 'FAQ' },
  ],
}

function mountForm(data: KnowledgeNodeData = {}, disabled = false) {
  return mount(KnowledgeForm, { props: { data, disabled }, global: { plugins: [ElementPlus] } })
}

describe('KnowledgeForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listDatasets).mockResolvedValue(DATASETS as never)
  })

  it('挂载拉取知识库列表（page/size 固定 1/100）', async () => {
    mountForm()
    await flushPromises()
    expect(listDatasets).toHaveBeenCalledWith({ page: 1, size: 100 })
  })

  it('已选但被删除的库 → 禁用兜底项', async () => {
    const w = mountForm({ datasetIds: ['99', '5'] })
    await flushPromises()
    const vm = w.vm as unknown as {
      selectOptions: { value: string; label: string; disabled: boolean }[]
    }
    expect(vm.selectOptions[0]).toEqual({ value: '99', label: '已删除的知识库', disabled: true })
  })

  it('改 query → emit update', async () => {
    const w = mountForm()
    await flushPromises()
    await w.find('[data-test="kb-query"] input').setValue('{{start.q}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ query: '{{start.q}}' }])
  })

  it('insertVar 默认插入 query 末尾', async () => {
    const w = mountForm({ query: '搜索：' })
    await flushPromises()
    ;(w.vm as unknown as { insertVar: (t: string) => void }).insertVar('{{start.q}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ query: '搜索：{{start.q}}' }])
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/forms/__tests__/KnowledgeForm.spec.ts`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 实现**

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { InputInstance } from 'element-plus'
import { listDatasets } from '@/api/knowledge'
import type { Dataset } from '@/types/knowledge'
import type { KnowledgeNodeData } from '@/types/workflow'
import { useVarInsert } from '../../composables/useVarInsert'

const props = defineProps<{ data: KnowledgeNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: KnowledgeNodeData] }>()

const datasetOptions = ref<Dataset[]>([])
onMounted(async () => {
  try {
    const res = await listDatasets({ page: 1, size: 100 })
    datasetOptions.value = res.list
  } catch {
    /* 失败由 request 拦截器统一 toast；下拉留空 */
  }
})

/** 现存知识库 + 已选但被删除的库作禁用项（同 AppList 手法）。 */
const selectOptions = computed(() => {
  const opts = datasetOptions.value.map((d) => ({ value: d.id, label: d.name, disabled: false }))
  for (const id of props.data.datasetIds ?? []) {
    if (!datasetOptions.value.some((d) => d.id === id)) {
      opts.unshift({ value: id, label: '已删除的知识库', disabled: true })
    }
  }
  return opts
})

const queryRef = ref<InputInstance>()
const { register, onFocus, insert } = useVarInsert(() => 'query')
register('query', {
  get: () => props.data.query ?? '',
  set: (v) => emit('update', { query: v }),
  el: () => queryRef.value?.input,
})
defineExpose({ insertVar: insert, selectOptions })
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-knowledge-retrieval">
    <el-form-item label="知识库" required>
      <el-select
        data-test="kb-datasets"
        multiple
        :model-value="data.datasetIds ?? []"
        placeholder="选择知识库（可多选）"
        @update:model-value="emit('update', { datasetIds: $event })"
      >
        <el-option
          v-for="o in selectOptions"
          :key="o.value"
          :value="o.value"
          :label="o.label"
          :disabled="o.disabled"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="检索内容" required>
      <el-input
        ref="queryRef"
        data-test="kb-query"
        :model-value="data.query ?? ''"
        placeholder="可引用变量，如 {{start.q}}"
        @update:model-value="emit('update', { query: $event })"
        @focus="onFocus('query')"
      />
    </el-form-item>
  </el-form>
</template>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/forms/__tests__/KnowledgeForm.spec.ts`
Expected: PASS（4 个用例全绿）

- [ ] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/forms/KnowledgeForm.vue web/src/views/workflow/components/forms/__tests__/KnowledgeForm.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): knowledge-retrieval 节点配置表单"
```

---

### Task 10: HttpForm HTTP 请求表单

**Files:**
- Create: `web/src/views/workflow/components/forms/HttpForm.vue`
- Test: `web/src/views/workflow/components/forms/__tests__/HttpForm.spec.ts`

**Interfaces:**
- Consumes: `HttpNodeData`、`HTTP_METHODS`（Task 2）、`useVarInsert`
- Produces: props/emits 同通用契约，expose `insertVar`；根元素 `data-test="form-http"`。headers 用**本地行状态**（key 编辑中可重复/为空），写回时过滤空 key、重复 key 后写赢（spec §8）——因此 Task 13 抽屉必须给表单 `:key="node.id"`（切节点重建实例，本地行状态才不串）

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import HttpForm from '@/views/workflow/components/forms/HttpForm.vue'
import type { HttpNodeData } from '@/types/workflow'

function mountForm(data: HttpNodeData = {}, disabled = false) {
  return mount(HttpForm, { props: { data, disabled }, global: { plugins: [ElementPlus] } })
}

describe('HttpForm', () => {
  it('method 为空时挂载即写回默认 GET（可编辑态）', () => {
    const w = mountForm()
    expect(w.emitted('update')?.[0]).toEqual([{ method: 'GET' }])
  })

  it('disabled（非 owner 只读）不写回默认值——否则白造 dirty', () => {
    const w = mountForm({}, true)
    expect(w.emitted('update')).toBeUndefined()
  })

  it('headers 回显为行，编辑后写回对象（空 key 过滤）', async () => {
    const w = mountForm({ method: 'POST', headers: { 'X-Token': 'abc' } })
    const keys = w.findAll('[data-test="http-header-key"] input')
    expect((keys[0].element as HTMLInputElement).value).toBe('X-Token')
    await w.find('[data-test="http-header-add"]').trigger('click')
    await w.findAll('[data-test="http-header-value"] input')[1].setValue('v2')
    // 新行 key 为空 → 写回时被过滤，headers 不变
    expect(w.emitted('update')?.at(-1)).toEqual([{ headers: { 'X-Token': 'abc' } }])
    await w.findAll('[data-test="http-header-key"] input')[1].setValue('X-Trace')
    expect(w.emitted('update')?.at(-1)).toEqual([
      { headers: { 'X-Token': 'abc', 'X-Trace': 'v2' } },
    ])
  })

  it('删行写回', async () => {
    const w = mountForm({ method: 'GET', headers: { A: '1', B: '2' } })
    await w.findAll('[data-test="http-header-remove"]')[0].trigger('click')
    expect(w.emitted('update')?.at(-1)).toEqual([{ headers: { B: '2' } }])
  })

  it('insertVar 默认插入 url 末尾', () => {
    const w = mountForm({ method: 'GET', url: 'https://a.com?q=' })
    ;(w.vm as unknown as { insertVar: (t: string) => void }).insertVar('{{start.q}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ url: 'https://a.com?q={{start.q}}' }])
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/forms/__tests__/HttpForm.spec.ts`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 实现**

```vue
<script setup lang="ts">
import { onMounted, ref, watchEffect } from 'vue'
import type { InputInstance } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import type { HttpNodeData } from '@/types/workflow'
import { HTTP_METHODS } from '../../composables/useNodeIssues'
import { useVarInsert } from '../../composables/useVarInsert'

const props = defineProps<{ data: HttpNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: HttpNodeData] }>()

// headers 本地行状态：key 编辑中允许空/重复，写回时过滤空 key、后写赢（spec §8）。
// 依赖抽屉侧 :key="node.id" 保证切换节点时本组件重建、行状态不串。
const rows = ref<{ key: string; value: string }[]>(
  Object.entries(props.data.headers ?? {}).map(([key, value]) => ({ key, value })),
)

function syncHeaders() {
  const headers: Record<string, string> = {}
  for (const r of rows.value) {
    if (r.key.trim() !== '') headers[r.key.trim()] = r.value
  }
  emit('update', { headers })
}
function addRow() {
  rows.value.push({ key: '', value: '' })
}
function removeRow(i: number) {
  rows.value.splice(i, 1)
  syncHeaders()
}
function updateKey(i: number, v: string) {
  rows.value[i].key = v
  syncHeaders()
}
function updateValue(i: number, v: string) {
  rows.value[i].value = v
  syncHeaders()
}

onMounted(() => {
  // 新节点默认 GET；只读态不写（非 owner 打开抽屉不应产生 dirty）
  if (!props.disabled && !props.data.method) emit('update', { method: 'GET' })
})

const urlRef = ref<InputInstance>()
const bodyRef = ref<InputInstance>()
const { register, onFocus, insert } = useVarInsert(() => 'url')
register('url', {
  get: () => props.data.url ?? '',
  set: (v) => emit('update', { url: v }),
  el: () => urlRef.value?.input,
})
register('body', {
  get: () => props.data.body ?? '',
  set: (v) => emit('update', { body: v }),
  el: () => bodyRef.value?.textarea,
})
watchEffect(() => {
  rows.value.forEach((_, i) => {
    register(`hv_${i}`, {
      get: () => rows.value[i]?.value ?? '',
      set: (v) => {
        if (rows.value[i]) {
          rows.value[i].value = v
          syncHeaders()
        }
      },
    })
  })
})
defineExpose({ insertVar: insert })
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-http">
    <el-form-item label="请求方法" required>
      <el-select
        data-test="http-method"
        :model-value="data.method ?? 'GET'"
        @update:model-value="emit('update', { method: $event })"
      >
        <el-option v-for="m in HTTP_METHODS" :key="m" :value="m" :label="m" />
      </el-select>
    </el-form-item>
    <el-form-item label="URL" required>
      <el-input
        ref="urlRef"
        data-test="http-url"
        :model-value="data.url ?? ''"
        placeholder="https://…，可引用变量，如 https://api.example.com?q={{start.q}}"
        @update:model-value="emit('update', { url: $event })"
        @focus="onFocus('url')"
      />
    </el-form-item>
    <el-form-item label="Headers">
      <div v-for="(row, i) in rows" :key="i" class="http-form__header-row">
        <el-input
          data-test="http-header-key"
          class="http-form__header-key"
          :model-value="row.key"
          placeholder="名称"
          @update:model-value="updateKey(i, $event)"
        />
        <el-input
          data-test="http-header-value"
          class="http-form__header-value"
          :model-value="row.value"
          placeholder="值，可引用变量"
          @update:model-value="updateValue(i, $event)"
          @focus="onFocus(`hv_${i}`)"
        />
        <el-button
          data-test="http-header-remove"
          :icon="Delete"
          text
          @click="removeRow(i)"
        />
      </div>
      <el-button data-test="http-header-add" :icon="Plus" text type="primary" @click="addRow"
        >添加 Header</el-button
      >
    </el-form-item>
    <el-form-item label="Body">
      <el-input
        ref="bodyRef"
        data-test="http-body"
        type="textarea"
        :rows="4"
        :model-value="data.body ?? ''"
        placeholder="可选，可引用变量"
        @update:model-value="emit('update', { body: $event })"
        @focus="onFocus('body')"
      />
    </el-form-item>
  </el-form>
</template>

<style scoped lang="scss">
.http-form__header-row {
  display: flex;
  gap: $spacing-sm;
  width: 100%;
  margin-bottom: $spacing-sm;
}
.http-form__header-key {
  width: 40%;
}
.http-form__header-value {
  flex: 1;
}
</style>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/forms/__tests__/HttpForm.spec.ts`
Expected: PASS（5 个用例全绿）

- [ ] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/forms/HttpForm.vue web/src/views/workflow/components/forms/__tests__/HttpForm.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): http 节点配置表单（method/url/headers/body）"
```

---

### Task 11: StartForm 输入声明表单

**Files:**
- Create: `web/src/views/workflow/components/forms/StartForm.vue`
- Test: `web/src/views/workflow/components/forms/__tests__/StartForm.spec.ts`

**Interfaces:**
- Consumes: `StartNodeData` / `StartInputDecl`
- Produces: props/emits 同通用契约；**无变量字段，不 expose insertVar**（抽屉侧可选调用已判空）；根元素 `data-test="form-start"`

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StartForm from '@/views/workflow/components/forms/StartForm.vue'
import type { StartNodeData } from '@/types/workflow'

function mountForm(data: StartNodeData = {}, disabled = false) {
  return mount(StartForm, { props: { data, disabled }, global: { plugins: [ElementPlus] } })
}

describe('StartForm', () => {
  it('回显声明行', () => {
    const w = mountForm({ inputs: [{ name: 'city' }, { name: 'q' }] })
    const names = w.findAll('[data-test="start-input-name"] input')
    expect(names.map((i) => (i.element as HTMLInputElement).value)).toEqual(['city', 'q'])
  })

  it('添加行 → emit 整个 inputs 数组', async () => {
    const w = mountForm({ inputs: [{ name: 'city' }] })
    await w.find('[data-test="start-input-add"]').trigger('click')
    expect(w.emitted('update')?.at(-1)).toEqual([{ inputs: [{ name: 'city' }, { name: '' }] }])
  })

  it('改名/删行 → emit 不可变副本', async () => {
    const w = mountForm({ inputs: [{ name: 'city' }, { name: 'q' }] })
    await w.findAll('[data-test="start-input-name"] input')[0].setValue('town')
    expect(w.emitted('update')?.at(-1)).toEqual([{ inputs: [{ name: 'town' }, { name: 'q' }] }])
    await w.findAll('[data-test="start-input-remove"]')[1].trigger('click')
    expect(w.emitted('update')?.at(-1)).toEqual([{ inputs: [{ name: 'city' }] }])
  })

  it('非法名字显示警告（不阻断）', async () => {
    const w = mountForm({ inputs: [{ name: '中文名' }] })
    expect(w.find('[data-test="start-input-warn"]').exists()).toBe(true)
    const ok = mountForm({ inputs: [{ name: 'city_1' }] })
    expect(ok.find('[data-test="start-input-warn"]').exists()).toBe(false)
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/forms/__tests__/StartForm.spec.ts`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 实现**

```vue
<script setup lang="ts">
import { Delete, Plus } from '@element-plus/icons-vue'
import type { StartInputDecl, StartNodeData } from '@/types/workflow'

const props = defineProps<{ data: StartNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: StartNodeData] }>()

/** 名字须匹配后端变量正则 [\w-]+，否则下游 {{start.名字}} 引用不上；只警告不阻断（草稿语义）。 */
const NAME_RE = /^[\w-]+$/

function rows(): StartInputDecl[] {
  return props.data.inputs ?? []
}
function updateRow(i: number, name: string) {
  emit('update', { inputs: rows().map((r, idx) => (idx === i ? { name } : r)) })
}
function addRow() {
  emit('update', { inputs: [...rows(), { name: '' }] })
}
function removeRow(i: number) {
  emit('update', { inputs: rows().filter((_, idx) => idx !== i) })
}
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-start">
    <el-form-item label="输入声明">
      <div class="start-form__tip">运行时由触发方传入，声明后下游可引用</div>
      <div v-for="(row, i) in data.inputs ?? []" :key="i" class="start-form__row">
        <el-input
          data-test="start-input-name"
          :model-value="row.name"
          placeholder="变量名，如 city"
          @update:model-value="updateRow(i, $event)"
        />
        <el-button data-test="start-input-remove" :icon="Delete" text @click="removeRow(i)" />
        <div
          v-if="row.name !== '' && !NAME_RE.test(row.name)"
          class="start-form__warn"
          data-test="start-input-warn"
        >
          仅限字母、数字、下划线或中划线
        </div>
      </div>
      <el-button data-test="start-input-add" :icon="Plus" text type="primary" @click="addRow"
        >添加输入</el-button
      >
    </el-form-item>
  </el-form>
</template>

<style scoped lang="scss">
.start-form__tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: $spacing-sm;
}
.start-form__row {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-sm;
  width: 100%;
  margin-bottom: $spacing-sm;
}
.start-form__warn {
  width: 100%;
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/forms/__tests__/StartForm.spec.ts`
Expected: PASS（4 个用例全绿）

- [ ] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/forms/StartForm.vue web/src/views/workflow/components/forms/__tests__/StartForm.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): start 节点输入声明表单"
```

---

### Task 12: EndForm 输出声明表单

**Files:**
- Create: `web/src/views/workflow/components/forms/EndForm.vue`
- Test: `web/src/views/workflow/components/forms/__tests__/EndForm.spec.ts`

**Interfaces:**
- Consumes: `EndNodeData` / `EndOutputDecl`、`useVarInsert`
- Produces: props/emits 同通用契约，expose `insertVar`（特殊：无行时自动新增一行再插入其 value，spec §4）；根元素 `data-test="form-end"`

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import EndForm from '@/views/workflow/components/forms/EndForm.vue'
import type { EndNodeData } from '@/types/workflow'

function mountForm(data: EndNodeData = {}, disabled = false) {
  return mount(EndForm, { props: { data, disabled }, global: { plugins: [ElementPlus] } })
}

describe('EndForm', () => {
  it('回显输出行并可编辑', async () => {
    const w = mountForm({ outputs: [{ name: 'answer', value: '{{llm_1.text}}' }] })
    expect((w.find('[data-test="end-output-name"] input').element as HTMLInputElement).value)
      .toBe('answer')
    await w.find('[data-test="end-output-value"] input').setValue('{{http_1.body}}')
    expect(w.emitted('update')?.at(-1)).toEqual([
      { outputs: [{ name: 'answer', value: '{{http_1.body}}' }] },
    ])
  })

  it('添加/删除行', async () => {
    const w = mountForm({ outputs: [{ name: 'a', value: '1' }] })
    await w.find('[data-test="end-output-add"]').trigger('click')
    expect(w.emitted('update')?.at(-1)).toEqual([
      { outputs: [{ name: 'a', value: '1' }, { name: '', value: '' }] },
    ])
    await w.findAll('[data-test="end-output-remove"]')[0].trigger('click')
    expect(w.emitted('update')?.at(-1)).toEqual([{ outputs: [] }])
  })

  it('insertVar 无行时自动新增一行再插入 value（spec §4）', () => {
    const w = mountForm({})
    ;(w.vm as unknown as { insertVar: (t: string) => void }).insertVar('{{llm_1.text}}')
    expect(w.emitted('update')?.at(-1)).toEqual([
      { outputs: [{ name: '', value: '{{llm_1.text}}' }] },
    ])
  })

  it('insertVar 有行且未聚焦 → 插入最后一行 value 末尾', () => {
    const w = mountForm({ outputs: [{ name: 'a', value: 'x' }, { name: 'b', value: 'y' }] })
    ;(w.vm as unknown as { insertVar: (t: string) => void }).insertVar('{{kb_1.count}}')
    expect(w.emitted('update')?.at(-1)).toEqual([
      { outputs: [{ name: 'a', value: 'x' }, { name: 'b', value: 'y{{kb_1.count}}' }] },
    ])
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/forms/__tests__/EndForm.spec.ts`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 实现**

```vue
<script setup lang="ts">
import { watchEffect } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import type { EndNodeData, EndOutputDecl } from '@/types/workflow'
import { useVarInsert } from '../../composables/useVarInsert'

const props = defineProps<{ data: EndNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: EndNodeData] }>()

function rows(): EndOutputDecl[] {
  return props.data.outputs ?? []
}
function updateRow(i: number, patch: Partial<EndOutputDecl>) {
  emit('update', { outputs: rows().map((r, idx) => (idx === i ? { ...r, ...patch } : r)) })
}
function addRow() {
  emit('update', { outputs: [...rows(), { name: '', value: '' }] })
}
function removeRow(i: number) {
  emit('update', { outputs: rows().filter((_, idx) => idx !== i) })
}

// 默认插入目标=最后一行 value；行是动态的，逐行注册（register 幂等覆盖）
const { register, onFocus, insert } = useVarInsert(() => `value_${rows().length - 1}`)
watchEffect(() => {
  rows().forEach((_, i) => {
    register(`value_${i}`, {
      get: () => rows()[i]?.value ?? '',
      set: (v) => updateRow(i, { value: v }),
    })
  })
})

/** 无行时自动新增一行再插入其 value（spec §4 拍板）。 */
function insertVar(text: string) {
  if (rows().length === 0) {
    emit('update', { outputs: [{ name: '', value: text }] })
    return
  }
  insert(text)
}
defineExpose({ insertVar })
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-end">
    <el-form-item label="输出声明">
      <div class="end-form__tip">运行的最终输出，按行渲染</div>
      <div v-for="(row, i) in data.outputs ?? []" :key="i" class="end-form__row">
        <el-input
          data-test="end-output-name"
          class="end-form__name"
          :model-value="row.name"
          placeholder="名称"
          @update:model-value="updateRow(i, { name: $event })"
        />
        <el-input
          data-test="end-output-value"
          class="end-form__value"
          :model-value="row.value"
          placeholder="值，可引用变量，如 {{llm_1.text}}"
          @update:model-value="updateRow(i, { value: $event })"
          @focus="onFocus(`value_${i}`)"
        />
        <el-button data-test="end-output-remove" :icon="Delete" text @click="removeRow(i)" />
      </div>
      <el-button data-test="end-output-add" :icon="Plus" text type="primary" @click="addRow"
        >添加输出</el-button
      >
    </el-form-item>
  </el-form>
</template>

<style scoped lang="scss">
.end-form__tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: $spacing-sm;
}
.end-form__row {
  display: flex;
  gap: $spacing-sm;
  width: 100%;
  margin-bottom: $spacing-sm;
}
.end-form__name {
  width: 40%;
}
.end-form__value {
  flex: 1;
}
</style>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/forms/__tests__/EndForm.spec.ts`
Expected: PASS（4 个用例全绿）

- [ ] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/forms/EndForm.vue web/src/views/workflow/components/forms/__tests__/EndForm.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): end 节点输出声明表单"
```

---

### Task 13: NodeConfigDrawer 抽屉壳

**Files:**
- Create: `web/src/views/workflow/components/NodeConfigDrawer.vue`
- Test: `web/src/views/workflow/components/__tests__/NodeConfigDrawer.spec.ts`

**Interfaces:**
- Consumes: 六个表单组件（Task 7~12）、`VariablePanel`（Task 6）、`upstreamVars`（Task 3）、`FlowNode`/`FlowEdge`
- Produces: props `{ node: FlowNode | null; nodes: FlowNode[]; edges: FlowEdge[]; canEdit: boolean }`，emits `close: []`、`update: [id: string, patch: WorkflowNodeData]`（Task 15 接 `graph.updateNodeData`）

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import NodeConfigDrawer from '@/views/workflow/components/NodeConfigDrawer.vue'
import type { FlowEdge, FlowNode } from '@/views/workflow/composables/graphTransform'

vi.mock('@/api/provider', () => ({ listChatModels: vi.fn().mockResolvedValue([]) }))
vi.mock('@/api/knowledge', () => ({
  listDatasets: vi.fn().mockResolvedValue({ list: [] }),
}))

// el-drawer 走 teleport，测试里换成透传壳
const DrawerStub = {
  name: 'ElDrawer',
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<div v-if="modelValue" data-test="drawer"><div data-test="drawer-header"><slot name="header" /></div><slot /></div>',
}

function n(id: string, type: FlowNode['type'], data: FlowNode['data'] = {}): FlowNode {
  return { id, type, data, position: { x: 0, y: 0 }, deletable: true }
}

const NODES: FlowNode[] = [
  n('start', 'start', { inputs: [{ name: 'q' }] }),
  n('kb_1', 'knowledge-retrieval'),
  n('if_1', 'condition'),
]
const EDGES: FlowEdge[] = [
  { id: 'e-start-_-kb_1', source: 'start', target: 'kb_1' },
  { id: 'e-kb_1-_-if_1', source: 'kb_1', target: 'if_1' },
]

function mountDrawer(node: FlowNode | null, canEdit = true) {
  return mount(NodeConfigDrawer, {
    props: { node, nodes: NODES, edges: EDGES, canEdit },
    global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub } },
  })
}

describe('NodeConfigDrawer', () => {
  beforeEach(() => vi.clearAllMocks())

  it('node 为 null 不渲染', () => {
    expect(mountDrawer(null).find('[data-test="drawer"]').exists()).toBe(false)
  })

  it('按类型挂对表单，标题含类型名与节点 id', async () => {
    const w = mountDrawer(NODES[2])
    await flushPromises()
    expect(w.find('[data-test="form-condition"]').exists()).toBe(true)
    expect(w.find('[data-test="drawer-header"]').text()).toContain('条件分支')
    expect(w.find('[data-test="drawer-header"]').text()).toContain('if_1')
  })

  it('表单 update → 转发为 (nodeId, patch)', async () => {
    const w = mountDrawer(NODES[2])
    await flushPromises()
    await w.find('[data-test="cond-left"] input').setValue('{{kb_1.count}}')
    expect(w.emitted('update')?.at(-1)).toEqual(['if_1', { left: '{{kb_1.count}}' }])
  })

  it('变量面板列出祖先，点击 → 插入当前表单默认字段', async () => {
    const w = mountDrawer(NODES[2])
    await flushPromises()
    const tags = w.findAll('[data-test="var-tag"]')
    expect(tags.map((t) => t.text())).toEqual(['text', 'count', 'q'])
    await tags[0].trigger('click')
    expect(w.emitted('update')?.at(-1)).toEqual(['if_1', { left: '{{kb_1.text}}' }])
  })

  it('start 节点不展示变量面板（无变量字段）', async () => {
    const w = mountDrawer(NODES[0])
    await flushPromises()
    expect(w.find('[data-test="form-start"]').exists()).toBe(true)
    expect(w.find('[data-test="var-panel"]').exists()).toBe(false)
  })

  it('canEdit=false → 表单控件禁用', async () => {
    const w = mountDrawer(NODES[2], false)
    await flushPromises()
    expect(w.find('[data-test="cond-left"] input').attributes('disabled')).toBeDefined()
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/__tests__/NodeConfigDrawer.spec.ts`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 实现**

```vue
<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import type { WorkflowNodeData, WorkflowNodeType } from '@/types/workflow'
import type { FlowEdge, FlowNode } from '../composables/graphTransform'
import { upstreamVars } from '../composables/useUpstreamVars'
import VariablePanel from './VariablePanel.vue'
import StartForm from './forms/StartForm.vue'
import LlmForm from './forms/LlmForm.vue'
import KnowledgeForm from './forms/KnowledgeForm.vue'
import ConditionForm from './forms/ConditionForm.vue'
import HttpForm from './forms/HttpForm.vue'
import EndForm from './forms/EndForm.vue'

const props = defineProps<{
  node: FlowNode | null
  nodes: FlowNode[]
  edges: FlowEdge[]
  canEdit: boolean
}>()
const emit = defineEmits<{ close: []; update: [id: string, patch: WorkflowNodeData] }>()

const FORMS: Record<WorkflowNodeType, Component> = {
  start: StartForm,
  llm: LlmForm,
  'knowledge-retrieval': KnowledgeForm,
  condition: ConditionForm,
  http: HttpForm,
  end: EndForm,
}
const TITLES: Record<WorkflowNodeType, string> = {
  start: '开始',
  llm: 'LLM',
  'knowledge-retrieval': '知识检索',
  condition: '条件分支',
  http: 'HTTP 请求',
  end: '结束',
}

const visible = computed({
  get: () => props.node != null,
  set: (v) => {
    if (!v) emit('close')
  },
})
const vars = computed(() =>
  props.node ? upstreamVars(props.node.id, props.nodes, props.edges) : [],
)
/** start 无变量字段，不展示面板；其余类型（含 end 的 outputs value）都可插入。 */
const showVars = computed(() => props.node != null && props.node.type !== 'start')

const formRef = ref<{ insertVar?: (text: string) => void } | null>(null)

function onUpdate(patch: WorkflowNodeData) {
  if (props.node) emit('update', props.node.id, patch)
}
function onInsert(text: string) {
  formRef.value?.insertVar?.(text)
}
</script>

<template>
  <el-drawer v-model="visible" :modal="false" :size="380" :with-header="true">
    <template #header>
      <span class="node-drawer__title">
        {{ node ? TITLES[node.type] : '' }} · {{ node?.id }}
      </span>
    </template>
    <!-- template 包一层 v-if：同元素上 v-if 对自身绑定不做类型收窄（vue-tsc 会报 node 可能为 null） -->
    <template v-if="node">
      <!-- :key=node.id：切换节点时强制重建表单实例（HttpForm 本地行状态、模型选项等不串台） -->
      <component
        :is="FORMS[node.type]"
        :key="node.id"
        ref="formRef"
        :data="node.data"
        :disabled="!canEdit"
        @update="onUpdate"
      />
      <VariablePanel v-if="showVars" :vars="vars" :disabled="!canEdit" @insert="onInsert" />
    </template>
  </el-drawer>
</template>

<style scoped lang="scss">
.node-drawer__title {
  font-weight: 600;
}
</style>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/__tests__/NodeConfigDrawer.spec.ts`
Expected: PASS（6 个用例全绿）

- [ ] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/NodeConfigDrawer.vue web/src/views/workflow/components/__tests__/NodeConfigDrawer.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): 节点配置抽屉壳（按类型挂表单+变量面板+只读态）"
```

---

### Task 14: CanvasNode 未配齐徽章

**Files:**
- Modify: `web/src/views/workflow/components/CanvasNode.vue`
- Test: `web/src/views/workflow/components/__tests__/CanvasNode.spec.ts`（追加用例）

**Interfaces:**
- Consumes: `nodeIssues`（Task 2）；Vue Flow 会把节点 `data` 作为 prop 传给自定义节点组件
- Produces: CanvasNode 新增可选 prop `data`

- [ ] **Step 1: 追加失败用例**

在既有 `CanvasNode.spec.ts` 追加（`mountNode` 辅助函数需加可选 `data` 参数：`props: { id, type, data }`）：

```ts
it('未配齐 → 显示警示徽章；配齐 → 不显示', () => {
  const bad = mountNode('llm', 'llm_1', {})
  expect(bad.find('[data-test="node-warn"]').exists()).toBe(true)
  const good = mountNode('llm', 'llm_1', { modelId: '3', userPrompt: 'hi' })
  expect(good.find('[data-test="node-warn"]').exists()).toBe(false)
})

it('start/end 永不显示徽章', () => {
  expect(mountNode('start', 'start', {}).find('[data-test="node-warn"]').exists()).toBe(false)
  expect(mountNode('end', 'end', {}).find('[data-test="node-warn"]').exists()).toBe(false)
})
```

`mountNode` 改为：

```ts
function mountNode(type: string, id = 'n1', data: Record<string, unknown> = {}) {
  return mount(CanvasNode, {
    props: { id, type, data },
    global: { stubs: { Handle: HandleStub }, plugins: [ElementPlus] },
  })
}
```

（文件头部补 `import ElementPlus from 'element-plus'`；el-tooltip 需要它。）

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/__tests__/CanvasNode.spec.ts`
Expected: 新增 2 个用例 FAIL，既有用例仍 PASS

- [ ] **Step 3: 实现**

`CanvasNode.vue` 改动点：

```ts
// script setup 增改：
import { computed, type Component } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue' // 加进既有 icons import
import type { WorkflowNodeData, WorkflowNodeType } from '@/types/workflow'
import { nodeIssues } from '../composables/useNodeIssues'

// props 加 data（Vue Flow 透传；老用法不传时按空对象）
const props = defineProps<{ id: string; type: string; data?: Record<string, unknown> }>()

const issues = computed(() =>
  nodeIssues(props.type as WorkflowNodeType, (props.data ?? {}) as WorkflowNodeData),
)
```

template 在根 div 内（`</div>` 前）加：

```vue
    <el-tooltip v-if="issues.length > 0" :content="issues.join('、')" placement="top">
      <span class="canvas-node__warn" data-test="node-warn">
        <el-icon><WarningFilled /></el-icon>
      </span>
    </el-tooltip>
```

style 加：

```scss
.canvas-node__warn {
  position: absolute;
  top: -8px;
  right: -8px;
  color: var(--el-color-warning);
  font-size: 16px;
  line-height: 1;
  background: var(--el-bg-color);
  border-radius: 50%;
}
```

（根 `.canvas-node` 需加 `position: relative;`，绝对定位徽章才相对节点框。）

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/__tests__/CanvasNode.spec.ts`
Expected: PASS（既有+新增全绿）

- [ ] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/CanvasNode.vue web/src/views/workflow/components/__tests__/CanvasNode.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): 画布节点未配齐警示徽章+tooltip"
```

---

### Task 15: WorkflowEditor 接线（选中节点 → 抽屉）

**Files:**
- Modify: `web/src/views/workflow/WorkflowEditor.vue`
- Test: `web/src/views/workflow/__tests__/WorkflowEditor.spec.ts`（追加用例）

**Interfaces:**
- Consumes: `NodeConfigDrawer`（Task 13）、`graph.updateNodeData`（Task 4）

- [ ] **Step 1: 追加失败用例**

在既有 `WorkflowEditor.spec.ts`：`mountEditor` 的 stubs 加 `NodeConfigDrawer: true`；文件头部 `import { nextTick } from 'vue'`。追加：

```ts
it('点节点 → 抽屉收到该节点；点空白 → 关闭', async () => {
  const w = mountEditor()
  await flushPromises()
  const vf = w.findComponent({ name: 'VueFlow' })
  vf.vm.$emit('node-click', { node: { id: 'start' } })
  await nextTick()
  const drawer = w.findComponent({ name: 'NodeConfigDrawer' })
  expect(drawer.props('node')).toMatchObject({ id: 'start', type: 'start' })
  expect(drawer.props('canEdit')).toBe(true)
  vf.vm.$emit('pane-click')
  await nextTick()
  expect(drawer.props('node')).toBeNull()
})

it('抽屉 update → 节点 data 写回，保存提交新配置', async () => {
  vi.mocked(saveDraft).mockResolvedValue(DRAFT)
  const w = mountEditor()
  await flushPromises()
  const vf = w.findComponent({ name: 'VueFlow' })
  vf.vm.$emit('node-click', { node: { id: 'start' } })
  await nextTick()
  const drawer = w.findComponent({ name: 'NodeConfigDrawer' })
  drawer.vm.$emit('update', 'start', { inputs: [{ name: 'q' }] })
  await nextTick()
  await w.find('[data-test="wf-save"]').trigger('click')
  await flushPromises()
  expect(saveDraft).toHaveBeenCalledWith('42', expect.objectContaining({
    nodes: expect.arrayContaining([
      expect.objectContaining({ id: 'start', data: { inputs: [{ name: 'q' }] } }),
    ]),
  }))
})

it('抽屉打开时删除选中节点 → node 回落 null（抽屉关闭）', async () => {
  const w = mountEditor()
  await flushPromises()
  const vf = w.findComponent({ name: 'VueFlow' })
  vf.vm.$emit('node-click', { node: { id: 'ghost' } })
  await nextTick()
  // 选中 id 在 nodes 里不存在 → computed 回落 null，防节点被删后抽屉悬空
  expect(w.findComponent({ name: 'NodeConfigDrawer' }).props('node')).toBeNull()
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/__tests__/WorkflowEditor.spec.ts`
Expected: 新增 3 个用例 FAIL（NodeConfigDrawer 未挂），既有用例仍 PASS

- [ ] **Step 3: 实现**

`WorkflowEditor.vue` 改动点：

```ts
// import 加
import NodeConfigDrawer from './components/NodeConfigDrawer.vue'
// computed 区域后加：
/** 抽屉选中态：id 存 ref，节点从 nodes 现取——节点被删时自动回落 null 关抽屉。 */
const selectedId = ref<string | null>(null)
const selectedNode = computed(
  () => graph.nodes.value.find((n) => n.id === selectedId.value) ?? null,
)
function onNodeClick(e: { node: { id: string } }) {
  selectedId.value = e.node.id
}
```

template：`<VueFlow>` 标签加两个事件：

```vue
        <VueFlow
          v-model:nodes="graph.nodes.value"
          v-model:edges="graph.edges.value"
          :node-types="nodeTypes"
          @connect="onConnect"
          @node-click="onNodeClick"
          @pane-click="selectedId = null"
        >
```

`</VueFlow>` 之后、`.wf-editor__canvas` div 内部末尾加：

```vue
        <NodeConfigDrawer
          :node="selectedNode"
          :nodes="graph.nodes.value"
          :edges="graph.edges.value"
          :can-edit="canEdit"
          @close="selectedId = null"
          @update="graph.updateNodeData"
        />
```

（`ref` 已在既有 import 里；`computed` 同。）

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/__tests__/WorkflowEditor.spec.ts`
Expected: PASS（既有+新增全绿）

- [ ] **Step 5: Commit**

```bash
git add web/src/views/workflow/WorkflowEditor.vue web/src/views/workflow/__tests__/WorkflowEditor.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "feat(web/workflow): 画布接线节点配置抽屉（选中/关闭/写回）"
```

---

### Task 16: 全量回归

**Files:** 无新文件（只跑命令；lint --fix 若改动文件则一并提交）

- [ ] **Step 1: 前端四件套**

Run（在 `/home/wang/playlab/hify/web`）：

```bash
pnpm test && pnpm typecheck && pnpm lint && pnpm build
```

Expected: 四条命令全部退出码 0。`pnpm lint` 带 --fix，若有自动修复产生 diff，检查后随本 Task 提交。

- [ ] **Step 2: 后端回归确认（本轮零后端改动，防呆）**

Run（在 `/home/wang/playlab/hify/server`）：

```bash
git status --porcelain ../server && mvn -q verify; echo "EXIT=$?"
```

Expected: `git status` 对 server/ 无输出（确认没碰后端）；`EXIT=0`（以退出码判定，**不 grep BUILD SUCCESS**——-q 会静音）。

- [ ] **Step 3: 收尾提交**

```bash
git add -A web docs/superpowers/plans/2026-07-12-workflow-canvas-c2-node-drawer.md
git commit -m "chore(web/workflow): C2 全量回归收尾（lint 自动修复与计划勾选）"
```

（若无 diff 可跳过 commit，仅勾选计划随最后一个有 diff 的提交走。）

---

## 人工验收（C2 DoD，spec §7）——计划执行完后由用户做

1. 纯画布从零配出 W3a 等价图（start→kb→condition→真假双分支 llm→end），保存后 Postman 触发运行 `succeeded`。
2. 未配齐节点显示徽章+tooltip 列缺失项，配齐即消失；半成品保存后刷新配置原样恢复。
3. 变量面板列出的恰为祖先节点输出（未连线为空态），点击插入光标处。
4. 非 owner 打开他人画布抽屉：能看配置，全字段只读。
5. 模型/知识库失效项禁用兜底展示（不裸露数字 id）。
