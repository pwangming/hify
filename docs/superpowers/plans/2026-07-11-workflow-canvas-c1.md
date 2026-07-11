# Workflow 画布 C1（画布地基+保存）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 workflow 应用交付可视化画布的地基——AppList 可建 workflow 应用、画布页可拖节点/连线/保存草稿、往返保真；后端放宽草稿保存校验使半成品可存。

**Architecture:** 后端仅一处小改（`GraphValidator` 拆 `validateBasics`/`validateAndOrder` 两层，保存走底线校验）。前端新增画布页：纯函数转换层（`graphTransform.ts`，GraphDef ↔ Vue Flow 结构）+ 状态 composable（`useWorkflowGraph.ts`，自有 refs 为唯一数据源）+ Vue Flow 受控模式渲染（`apply-default=false`，变更经 `applyNodeChanges/applyEdgeChanges` 写回自有 refs）。

**Tech Stack:** Spring Boot 3 / JUnit 5 / Mockito；Vue 3 `<script setup>` + TS + Element Plus + `@vue-flow/core`(+background/controls) + vitest(happy-dom) + @vue/test-utils。

**对应 spec：** `docs/superpowers/specs/2026-07-11-workflow-canvas-c1-design.md`（已获批）。

## Global Constraints

- 后端错误码 **18001 语义不变**（已发布契约只增不改）；零迁移、零新错误码、零 API 结构变更。
- start/end 节点 **id 字面固定 `"start"` / `"end"`**（后端 `GraphValidator` 连通性校验从这两个 id 出发）。
- 前端 id 一律 `string`；SFC 顺序 `<script setup lang="ts">` → `<template>` → `<style scoped lang="scss">`；props/emits 类型式声明；优先 Element Plus 组件与 `@element-plus/icons-vue` 图标，禁手造。
- 新依赖仅限 `@vue-flow/core` `@vue-flow/background` `@vue-flow/controls`（用户已拍板），不得夹带其他包。
- 前端测试放 `__tests__/`（vitest include 仅 `src/**/__tests__/**/*.{test,spec}.ts`），环境 happy-dom。
- 判定 mvn 结果看 surefire 汇总行 `Tests run: X, Failures: 0, Errors: 0` 与退出码，**禁止 grep BUILD SUCCESS**。
- `WorkflowRunFlowTest`（Testcontainers）与 Mockito attach 在普通沙箱可能失败，需提权/本机 Docker 运行——失败先判环境再判代码。
- 样式间距用 `$spacing-*` 变量（全局注入），不写魔法值；魔法值仅限画布几何初值并注明「视觉初值待实测微调」。
- 提交信息风格沿用仓库惯例（中文、`feat(workflow): ...` / `test: ...`）。

---

### Task 1: 后端——GraphValidator 拆两层 + saveDraft 切换底线校验

**Files:**
- Modify: `server/src/main/java/com/hify/workflow/service/engine/GraphValidator.java`
- Modify: `server/src/main/java/com/hify/workflow/service/WorkflowDraftService.java:46`（`validateAndOrder` → `validateBasics`）及类注释
- Test: `server/src/test/java/com/hify/workflow/service/engine/GraphValidatorTest.java`
- Test: `server/src/test/java/com/hify/workflow/service/WorkflowDraftServiceTest.java`

**Interfaces:**
- Produces: `public void validateBasics(GraphDef graph)`——底线校验（图非空、节点数上限、id 非空不重、类型合法、边引用节点存在），不合法抛 `BizException(18001)`。`validateAndOrder` 签名与行为不变（内部先调 `validateBasics`）。
- Consumes: 既有 `WorkflowError.GRAPH_INVALID`(18001)、`WorkflowProperties.getMaxNodes()`。

- [x] **Step 1: 写失败测试（GraphValidatorTest 追加）**

在 `GraphValidatorTest` 末尾（`http` 测试组之后、类结束大括号前）追加：

```java
    // —— 画布 C1：保存草稿的底线校验（validateBasics）——

    @Test
    void 底线校验_半成品图放行() {
        // llm 缺 modelId/userPrompt、无 end、无连线——都是「没配完」而非结构损坏，草稿必须可存
        GraphDef draft = new GraphDef(List.of(
                start(),
                new GraphNode("llm_1", "llm", Map.of())),
                List.of());
        assertDoesNotThrow(() -> validator.validateBasics(draft));
    }

    @Test
    void 底线校验_空图或null_报18001() {
        BizException ex = assertThrows(BizException.class,
                () -> validator.validateBasics(new GraphDef(List.of(), List.of())));
        assertEquals(18001, ex.errorCode().code());
        assertThrows(BizException.class, () -> validator.validateBasics(null));
    }

    @Test
    void 底线校验_节点id重复_报18001() {
        GraphDef graph = new GraphDef(List.of(
                new GraphNode("a", "llm", Map.of()),
                new GraphNode("a", "http", Map.of())),
                List.of());
        BizException ex = assertThrows(BizException.class, () -> validator.validateBasics(graph));
        assertTrue(ex.getMessage().contains("id 重复"));
    }

    @Test
    void 底线校验_未知节点类型_报18001() {
        GraphDef graph = new GraphDef(List.of(new GraphNode("x", "magic", Map.of())), List.of());
        BizException ex = assertThrows(BizException.class, () -> validator.validateBasics(graph));
        assertTrue(ex.getMessage().contains("未知节点类型"));
    }

    @Test
    void 底线校验_边引用不存在节点_报18001() {
        GraphDef graph = new GraphDef(List.of(start()),
                List.of(new GraphEdge("start", "ghost")));
        BizException ex = assertThrows(BizException.class, () -> validator.validateBasics(graph));
        assertTrue(ex.getMessage().contains("连线引用不存在的节点"));
    }

    @Test
    void 底线校验_超节点数上限_报18001() {
        List<GraphNode> nodes = new ArrayList<>();
        for (int i = 0; i <= 50; i++) {
            nodes.add(new GraphNode("n" + i, "llm", Map.of()));
        }
        BizException ex = assertThrows(BizException.class,
                () -> validator.validateBasics(new GraphDef(nodes, List.of())));
        assertTrue(ex.getMessage().contains("上限"));
    }
```

（`assertDoesNotThrow`/`ArrayList` 等 import 该文件已有，无需新增。）

- [x] **Step 2: 跑测试确认红**

Run: `mvn -f server/pom.xml test -Dtest=GraphValidatorTest`
Expected: FAIL，编译错误 `找不到符号: 方法 validateBasics`（方法尚不存在）。

- [x] **Step 3: 实现 validateBasics 并让 validateAndOrder 复用**

`GraphValidator.java`：把 `validateAndOrder` 开头的空图/上限/首个 for 循环（id/类型检查）与边循环里的 `containsKey` 抛错抽成 `validateBasics`。修改后两个方法的完整形态：

```java
    /** 保存草稿的底线校验：只保证「存进去还能读出来」，不管图配没配完（画布 C1 spec §2）。 */
    public void validateBasics(GraphDef graph) {
        if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
            throw invalid("至少需要一个节点");
        }
        List<GraphNode> nodes = graph.nodes();
        if (nodes.size() > props.getMaxNodes()) {
            throw invalid("节点数超过上限 " + props.getMaxNodes());
        }
        Set<String> ids = new HashSet<>();
        for (GraphNode n : nodes) {
            if (n.id() == null || n.id().isBlank()) {
                throw invalid("存在缺少 id 的节点");
            }
            if (!ids.add(n.id())) {
                throw invalid("节点 id 重复：" + n.id());
            }
            if (!NodeType.supported(n.type())) {
                throw invalid("未知节点类型：" + n.type());
            }
        }
        List<GraphEdge> edges = graph.edges() == null ? List.of() : graph.edges();
        for (GraphEdge e : edges) {
            if (!ids.contains(e.source()) || !ids.contains(e.target())) {
                throw invalid("连线引用不存在的节点：" + e.source() + " → " + e.target());
            }
        }
    }

    public List<GraphNode> validateAndOrder(GraphDef graph) {
        validateBasics(graph);
        List<GraphNode> nodes = graph.nodes();
        List<GraphEdge> edges = graph.edges() == null ? List.of() : graph.edges();

        Map<String, GraphNode> byId = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            byId.put(n.id(), n);
            if (NodeType.LLM.value().equals(n.type())) {
                requireLlmField(n, "modelId");
                requireLlmField(n, "userPrompt");
            }
            if (NodeType.KNOWLEDGE_RETRIEVAL.value().equals(n.type())) {
                requireKnowledgeRetrievalFields(n);
            }
            if (NodeType.CONDITION.value().equals(n.type())) {
                requireConditionFields(n);
            }
            if (NodeType.HTTP.value().equals(n.type())) {
                requireHttpFields(n);
            }
        }
        requireExactlyOne(nodes, NodeType.START.value());
        requireExactlyOne(nodes, NodeType.END.value());

        Map<String, List<String>> next = new HashMap<>();
        Map<String, List<String>> prev = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        byId.keySet().forEach(id -> inDegree.put(id, 0));
        for (GraphEdge e : edges) {
            next.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
            prev.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e.source());
            inDegree.merge(e.target(), 1, Integer::sum);
        }
        // ……以下（分支出边规则/连通性/拓扑排序/引用序）与现状逐字一致，不动……
    }
```

注意：类注释第一行「画布图校验 + 拓扑排序（保存草稿与触发运行共用，spec §4）」改为
「画布图校验 + 拓扑排序：保存草稿走 validateBasics 底线校验，触发运行走 validateAndOrder 全量校验（画布 C1 spec §2）」。

- [x] **Step 4: 跑测试确认绿**

Run: `mvn -f server/pom.xml test -Dtest=GraphValidatorTest`
Expected: `Tests run: 34, Failures: 0, Errors: 0`（原 28 + 新 6），退出码 0。

- [x] **Step 5: 写失败测试（WorkflowDraftServiceTest 调整）**

`WorkflowDraftServiceTest` 中把 `非法图_报18001且不落库` 整个方法替换为（配完整语义的两个用例——结构损坏仍拒、半成品放行）：

```java
    @Test
    void 结构损坏图_报18001且不落库() {
        // 节点 id 重复 = 结构性损坏，底线校验仍拒（区别于「没配完」的半成品）
        GraphDef broken = new GraphDef(List.of(
                new GraphNode("a", "llm", Map.of()),
                new GraphNode("a", "llm", Map.of())), List.of());
        BizException ex = assertThrows(BizException.class,
                () -> service.saveDraft(42L, broken, owner));
        assertEquals(18001, ex.errorCode().code());
        verify(defMapper, never()).upsertDraft(any(), any());
    }

    @Test
    void 半成品图_可保存() {
        // 画布 C1：llm 未配任何字段、无 end、无连线，保存必须放行（完整校验后移到触发运行）
        GraphDef draft = new GraphDef(List.of(
                new GraphNode("start", "start", Map.of()),
                new GraphNode("llm_1", "llm", Map.of())), List.of());
        when(defMapper.selectOne(any())).thenReturn(new WorkflowDef());

        service.saveDraft(42L, draft, owner);

        verify(defMapper).upsertDraft(eq(42L), any(GraphDef.class));
    }
```

- [x] **Step 6: 跑测试确认红**

Run: `mvn -f server/pom.xml test -Dtest=WorkflowDraftServiceTest`
Expected: FAIL——`半成品图_可保存` 抛 18001（saveDraft 还在跑全量校验）。

- [x] **Step 7: 切换 saveDraft**

`WorkflowDraftService.saveDraft` 中 `validator.validateAndOrder(graph);` 改为：

```java
        validator.validateBasics(graph);
```

类注释「保存前过 GraphValidator，不合法的图拒绝入库」改为
「保存前过 GraphValidator.validateBasics 底线校验（半成品可存，画布 C1）；全量校验在触发运行时」。

- [x] **Step 8: 跑测试确认绿**

Run: `mvn -f server/pom.xml test -Dtest='WorkflowDraftServiceTest,GraphValidatorTest'`
Expected: 两类全绿，退出码 0。

- [x] **Step 9: Commit**

```bash
git add server/src/main/java/com/hify/workflow/service/engine/GraphValidator.java \
        server/src/main/java/com/hify/workflow/service/WorkflowDraftService.java \
        server/src/test/java/com/hify/workflow/service/engine/GraphValidatorTest.java \
        server/src/test/java/com/hify/workflow/service/WorkflowDraftServiceTest.java
git commit -m "feat(workflow): 草稿保存放宽为底线校验，全量校验后移到触发运行（画布 C1）"
```

---

### Task 2: 后端——集成用例（半成品保存→运行时 18001）+ 全量回归

**Files:**
- Test: `server/src/test/java/com/hify/workflow/WorkflowRunFlowTest.java`

**Interfaces:**
- Consumes: Task 1 的 `validateBasics` 行为；既有 `draftService`/`runService`/`owner`/`appId` 测试脚手架。

- [x] **Step 1: 写失败测试**

`WorkflowRunFlowTest` 新增 import（按字母序插入既有 import 区）：

```java
import com.hify.common.exception.BizException;
```

并在 static import 区加入：

```java
import static org.junit.jupiter.api.Assertions.assertThrows;
```

在 `运行历史_游标翻页穿真库` 测试之后追加：

```java
    @Test
    void 半成品草稿可保存_触发运行才报18001() {
        // 画布 C1：保存只做底线校验；完整校验（llm 必填/须有 end/连通性）后移到触发运行
        GraphDef draft = new GraphDef(List.of(
                new GraphNode("start", "start", Map.of()),
                new GraphNode("llm_1", "llm", Map.of())),
                List.of());

        draftService.saveDraft(appId, draft, owner);
        assertEquals(2, draftService.getDraft(appId).graph().nodes().size());   // 读回一致

        BizException ex = assertThrows(BizException.class,
                () -> runService.run(appId, Map.of(), owner));
        assertEquals(18001, ex.errorCode().code());
    }
```

- [x] **Step 2: 跑集成测试（需 Docker，普通沙箱失败则提权）**

Run: `mvn -f server/pom.xml test -Dtest=WorkflowRunFlowTest`
Expected: `Tests run: 10, Failures: 0, Errors: 0`（原 9 + 新 1），退出码 0。
（Task 1 已实现，此用例直接绿——它是集成层防回归钉子，红/绿证据以 Task 1 单测为准。）

- [x] **Step 3: 后端全量回归**

Run: `mvn -f server/pom.xml verify`
Expected: `Tests run: 586, Failures: 0, Errors: 0`（579 + 本轮新增 7，以实际汇总为准，Failures/Errors 必须为 0），退出码 0，含 `ModularityTests` 与 `LayerRulesTest`。

- [x] **Step 4: Commit**

```bash
git add server/src/test/java/com/hify/workflow/WorkflowRunFlowTest.java
git commit -m "test(workflow): 集成钉子——半成品草稿可保存、触发运行才报18001"
```

---

### Task 3: 前端——装 Vue Flow + workflow 类型与 api 层

**Files:**
- Modify: `web/package.json`（经 pnpm add）
- Create: `web/src/types/workflow.ts`
- Create: `web/src/api/workflow.ts`
- Test: `web/src/api/__tests__/workflow.spec.ts`

**Interfaces:**
- Produces（后续任务全部依赖这些类型/函数名）:
  - `types/workflow.ts`: `WorkflowNodeType`、`GraphNodePosition {x,y}`、`GraphNode {id,type,data,position?}`、`GraphEdge {source,target,sourceHandle?}`、`GraphDef {nodes,edges}`、`DraftResponse {graph,updateTime}`
  - `api/workflow.ts`: `getDraft(appId: string): Promise<DraftResponse | null>`、`saveDraft(appId: string, graph: GraphDef): Promise<DraftResponse>`

- [x] **Step 1: 安装依赖**

Run: `cd web && pnpm add @vue-flow/core @vue-flow/background @vue-flow/controls`
Expected: 三包进 `dependencies`，lockfile 更新，无 peer 冲突告警。

- [x] **Step 2: 写类型文件**

`web/src/types/workflow.ts`：

```ts
/** 画布节点类型（对齐后端 NodeType 的 value）。 */
export type WorkflowNodeType = 'start' | 'llm' | 'knowledge-retrieval' | 'condition' | 'http' | 'end'

/** 画布坐标（对齐后端 GraphNode.position jsonb，引擎不读只保真）。 */
export interface GraphNodePosition {
  x: number
  y: number
}

/** 对齐后端 GraphNode record。data 为节点私有配置，C1 不编辑（C2 收窄为按类型的联合）。 */
export interface GraphNode {
  id: string
  type: WorkflowNodeType
  data: Record<string, unknown>
  /** API 手拼的老草稿可能无坐标，加载时网格兜底。 */
  position?: GraphNodePosition | null
}

/** 对齐后端 GraphEdge record；后端不存边 id，前端确定性生成（见 graphTransform.edgeId）。 */
export interface GraphEdge {
  source: string
  target: string
  /** condition 节点出口标记 "true"/"false"，普通边为 null。 */
  sourceHandle?: string | null
}

/** 画布定义（workflow_def.graph jsonb）。 */
export interface GraphDef {
  nodes: GraphNode[]
  edges: GraphEdge[]
}

/** 草稿视图。updateTime 供「上次保存时间」展示。 */
export interface DraftResponse {
  graph: GraphDef
  updateTime: string
}
```

- [x] **Step 3: 写失败的 api 测试**

`web/src/api/__tests__/workflow.spec.ts`（沿 `app.spec.ts` 手法）：

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import { getDraft, saveDraft } from '@/api/workflow'
import type { GraphDef } from '@/types/workflow'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const GRAPH: GraphDef = {
  nodes: [{ id: 'start', type: 'start', data: {}, position: { x: 80, y: 200 } }],
  edges: [],
}

describe('workflow api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getDraft → GET /workflow/apps/{appId}/draft', () => {
    getDraft('42')
    expect(request.get).toHaveBeenCalledWith('/workflow/apps/42/draft')
  })

  it('saveDraft → PUT /workflow/apps/{appId}/draft + graph 信封', () => {
    saveDraft('42', GRAPH)
    expect(request.put).toHaveBeenCalledWith('/workflow/apps/42/draft', { graph: GRAPH })
  })
})
```

- [x] **Step 4: 跑测试确认红**

Run: `cd web && pnpm vitest run src/api/__tests__/workflow.spec.ts`
Expected: FAIL——`Cannot find module '@/api/workflow'`。

- [x] **Step 5: 实现 api/workflow.ts**

```ts
import { request } from '@/api/request'
import type { DraftResponse, GraphDef } from '@/types/workflow'

// baseURL 已含 /api/v1（见 api/request.ts）。draft 是单例子资源：GET 读 / PUT 全量写。
const BASE = '/workflow/apps'

/** 读草稿；未保存过时后端 data=null。后端：GET /api/v1/workflow/apps/{appId}/draft */
export function getDraft(appId: string) {
  return request.get<DraftResponse | null>(`${BASE}/${appId}/draft`)
}

/** 全量保存草稿（半成品可存，完整校验在触发运行时）。后端：PUT .../draft */
export function saveDraft(appId: string, graph: GraphDef) {
  return request.put<DraftResponse>(`${BASE}/${appId}/draft`, { graph })
}
```

- [x] **Step 6: 跑测试确认绿 + typecheck**

Run: `cd web && pnpm vitest run src/api/__tests__/workflow.spec.ts && pnpm typecheck`
Expected: 2 passed；vue-tsc 无错。

- [x] **Step 7: Commit**

```bash
git add web/package.json web/pnpm-lock.yaml web/src/types/workflow.ts \
        web/src/api/workflow.ts web/src/api/__tests__/workflow.spec.ts
git commit -m "feat(web/workflow): 装 Vue Flow 三件套 + workflow 类型与草稿 api 层"
```

---

### Task 4: 前端——graphTransform 纯函数（转换层 TDD 核心）

**Files:**
- Create: `web/src/views/workflow/composables/graphTransform.ts`
- Test: `web/src/views/workflow/composables/__tests__/graphTransform.spec.ts`

**Interfaces:**
- Consumes: Task 3 的 `GraphDef/GraphNode/GraphEdge/GraphNodePosition/WorkflowNodeType`。
- Produces（Task 5/6/7 依赖）:
  - `interface FlowNode { id: string; type: WorkflowNodeType; position: GraphNodePosition; data: Record<string, unknown>; deletable: boolean }`
  - `interface FlowEdge { id: string; source: string; target: string; sourceHandle?: string }`
  - `edgeId(e: { source: string; target: string; sourceHandle?: string | null }): string`
  - `presetGraph(): { nodes: FlowNode[]; edges: FlowEdge[] }`
  - `toFlow(graph: GraphDef | null): { nodes: FlowNode[]; edges: FlowEdge[] }`
  - `fromFlow(nodes: FlowNode[], edges: FlowEdge[]): GraphDef`
  - `nextNodeId(existingIds: string[], type: WorkflowNodeType): string`

- [x] **Step 1: 写失败测试**

`web/src/views/workflow/composables/__tests__/graphTransform.spec.ts`：

```ts
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
```

- [x] **Step 2: 跑测试确认红**

Run: `cd web && pnpm vitest run src/views/workflow/composables/__tests__/graphTransform.spec.ts`
Expected: FAIL——`Cannot find module .../graphTransform`。

- [x] **Step 3: 实现 graphTransform.ts**

```ts
import type { GraphDef, GraphNodePosition, WorkflowNodeType } from '@/types/workflow'

/**
 * GraphDef（后端 jsonb 形态）↔ Vue Flow nodes/edges 的纯转换层。
 * 硬约定：start/end 节点 id 字面固定（后端连通性校验从这两个 id 出发）且不可删除。
 */

/** 结构上可直接喂给 Vue Flow 的节点（避免直接依赖库类型，保持本层纯净）。 */
export interface FlowNode {
  id: string
  type: WorkflowNodeType
  position: GraphNodePosition
  data: Record<string, unknown>
  deletable: boolean
}

/** 结构上可直接喂给 Vue Flow 的边。 */
export interface FlowEdge {
  id: string
  source: string
  target: string
  sourceHandle?: string
}

// 网格兜底与预置坐标：纯视觉初值，待实测微调
const GRID = { originX: 80, originY: 80, stepX: 240, stepY: 160, cols: 4 }
const PRESET_Y = 200
const PRESET_END_X = 640

/** 后端不存边 id，前端确定性生成：同一条业务边永远算出同一个 id。 */
export function edgeId(e: { source: string; target: string; sourceHandle?: string | null }): string {
  return `e-${e.source}-${e.sourceHandle ?? '_'}-${e.target}`
}

/** 新画布预置：start/end 固定 id、不可删、不预连线（spec C1 拍板）。 */
export function presetGraph(): { nodes: FlowNode[]; edges: FlowEdge[] } {
  return {
    nodes: [
      { id: 'start', type: 'start', position: { x: GRID.originX, y: PRESET_Y }, data: {}, deletable: false },
      { id: 'end', type: 'end', position: { x: PRESET_END_X, y: PRESET_Y }, data: {}, deletable: false },
    ],
    edges: [],
  }
}

function gridPosition(index: number): GraphNodePosition {
  return {
    x: GRID.originX + (index % GRID.cols) * GRID.stepX,
    y: GRID.originY + Math.floor(index / GRID.cols) * GRID.stepY,
  }
}

/** 后端草稿 → 画布。null（未保存过）→ 预置图；无 position 的老草稿按数组序网格兜底。 */
export function toFlow(graph: GraphDef | null): { nodes: FlowNode[]; edges: FlowEdge[] } {
  if (!graph) return presetGraph()
  const nodes = graph.nodes.map((n, i) => ({
    id: n.id,
    type: n.type,
    position: n.position ?? gridPosition(i),
    data: n.data ?? {},
    deletable: n.type !== 'start' && n.type !== 'end',
  }))
  const edges = graph.edges.map((e) => ({
    id: edgeId(e),
    source: e.source,
    target: e.target,
    // 普通边不写 sourceHandle 键：Vue Flow 对 undefined 和 null 语义不同
    ...(e.sourceHandle != null ? { sourceHandle: e.sourceHandle } : {}),
  }))
  return { nodes, edges }
}

/** 画布 → 后端草稿：剥掉前端专属字段（边 id / targetHandle），坐标取整。 */
export function fromFlow(nodes: FlowNode[], edges: FlowEdge[]): GraphDef {
  return {
    nodes: nodes.map((n) => ({
      id: n.id,
      type: n.type,
      data: n.data,
      position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
    })),
    edges: edges.map((e) => ({
      source: e.source,
      target: e.target,
      sourceHandle: e.sourceHandle ?? null,
    })),
  }
}

/** 节点 id 前缀（与既有测试/Postman 集合命名一致）；start/end 不经此生成。 */
const ID_PREFIX: Partial<Record<WorkflowNodeType, string>> = {
  llm: 'llm',
  'knowledge-retrieval': 'kb',
  condition: 'if',
  http: 'http',
}

/** 类型前缀 + (同前缀最大序号+1)。只保证与现存节点不冲突，不追溯已删除的历史 id。 */
export function nextNodeId(existingIds: string[], type: WorkflowNodeType): string {
  const prefix = ID_PREFIX[type] ?? type
  const re = new RegExp(`^${prefix}_(\\d+)$`)
  let max = 0
  for (const id of existingIds) {
    const m = re.exec(id)
    if (m) max = Math.max(max, Number(m[1]))
  }
  return `${prefix}_${max + 1}`
}
```

- [x] **Step 4: 跑测试确认绿 + typecheck**

Run: `cd web && pnpm vitest run src/views/workflow/composables/__tests__/graphTransform.spec.ts && pnpm typecheck`
Expected: 9 passed；vue-tsc 无错。

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/composables/graphTransform.ts \
        web/src/views/workflow/composables/__tests__/graphTransform.spec.ts
git commit -m "feat(web/workflow): GraphDef↔VueFlow 纯转换层（边id生成/网格兜底/往返保真/节点id自增）"
```

---

### Task 5: 前端——useWorkflowGraph composable（加载/保存/dirty/增删）

**Files:**
- Create: `web/src/views/workflow/composables/useWorkflowGraph.ts`
- Test: `web/src/views/workflow/composables/__tests__/useWorkflowGraph.spec.ts`

**Interfaces:**
- Consumes: Task 3 `getDraft/saveDraft`；Task 4 全部导出。
- Produces（Task 7 依赖）:

```ts
function useWorkflowGraph(appId: string): {
  nodes: Ref<FlowNode[]>          // 画布唯一数据源（受控模式）
  edges: Ref<FlowEdge[]>
  savedAt: Ref<string | null>     // 上次保存时间（ISO 串）
  loading: Ref<boolean>
  saving: Ref<boolean>
  dirty: ComputedRef<boolean>
  load(): Promise<void>
  save(): Promise<void>
  addNode(type: WorkflowNodeType, position: GraphNodePosition): string  // 返回新节点 id
  connect(c: { source: string; target: string; sourceHandle?: string | null }): void
}
```

- [x] **Step 1: 写失败测试**

`web/src/views/workflow/composables/__tests__/useWorkflowGraph.spec.ts`：

```ts
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
```

- [x] **Step 2: 跑测试确认红**

Run: `cd web && pnpm vitest run src/views/workflow/composables/__tests__/useWorkflowGraph.spec.ts`
Expected: FAIL——`Cannot find module .../useWorkflowGraph`。

- [x] **Step 3: 实现 useWorkflowGraph.ts**

```ts
import { computed, ref } from 'vue'
import { getDraft, saveDraft } from '@/api/workflow'
import type { GraphNodePosition, WorkflowNodeType } from '@/types/workflow'
import { edgeId, fromFlow, nextNodeId, toFlow } from './graphTransform'
import type { FlowEdge, FlowNode } from './graphTransform'

/**
 * 画布状态与草稿读写。nodes/edges 是画布唯一数据源（Vue Flow 受控模式），
 * dirty 用「当前 fromFlow 序列化 vs 上次保存快照」判定——拖动位置也算改动（position 要持久化）。
 */
export function useWorkflowGraph(appId: string) {
  const nodes = ref<FlowNode[]>([])
  const edges = ref<FlowEdge[]>([])
  const savedAt = ref<string | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const snapshot = ref('')

  const dirty = computed(
    () => JSON.stringify(fromFlow(nodes.value, edges.value)) !== snapshot.value,
  )

  function takeSnapshot() {
    snapshot.value = JSON.stringify(fromFlow(nodes.value, edges.value))
  }

  async function load() {
    loading.value = true
    try {
      const draft = await getDraft(appId)
      const flow = toFlow(draft?.graph ?? null)
      nodes.value = flow.nodes
      edges.value = flow.edges
      savedAt.value = draft?.updateTime ?? null
      takeSnapshot()
    } finally {
      loading.value = false
    }
  }

  async function save() {
    saving.value = true
    try {
      const graph = fromFlow(nodes.value, edges.value)
      const resp = await saveDraft(appId, graph)
      savedAt.value = resp.updateTime
      takeSnapshot()
    } finally {
      saving.value = false
    }
  }

  function addNode(type: WorkflowNodeType, position: GraphNodePosition): string {
    const id = nextNodeId(nodes.value.map((n) => n.id), type)
    nodes.value.push({ id, type, position, data: {}, deletable: true })
    return id
  }

  function connect(c: { source: string; target: string; sourceHandle?: string | null }) {
    const id = edgeId(c)
    if (edges.value.some((e) => e.id === id)) return // 重复连线幂等
    edges.value.push({
      id,
      source: c.source,
      target: c.target,
      ...(c.sourceHandle != null ? { sourceHandle: c.sourceHandle } : {}),
    })
  }

  return { nodes, edges, savedAt, loading, saving, dirty, load, save, addNode, connect }
}
```

- [x] **Step 4: 跑测试确认绿 + typecheck**

Run: `cd web && pnpm vitest run src/views/workflow/composables/__tests__/useWorkflowGraph.spec.ts && pnpm typecheck`
Expected: 5 passed；vue-tsc 无错。

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/composables/useWorkflowGraph.ts \
        web/src/views/workflow/composables/__tests__/useWorkflowGraph.spec.ts
git commit -m "feat(web/workflow): 画布状态 composable（加载/保存/dirty/加节点/连线幂等）"
```

---

### Task 6: 前端——CanvasNode 与 NodePalette 组件

**Files:**
- Create: `web/src/views/workflow/components/CanvasNode.vue`
- Create: `web/src/views/workflow/components/NodePalette.vue`
- Test: `web/src/views/workflow/components/__tests__/CanvasNode.spec.ts`
- Test: `web/src/views/workflow/components/__tests__/NodePalette.spec.ts`

**Interfaces:**
- Consumes: `@vue-flow/core` 的 `Handle`/`Position`；`@element-plus/icons-vue` 图标。
- Produces（Task 7 依赖）:
  - `CanvasNode.vue`：props `{ id: string; type: string }`（Vue Flow 以 nodeTypes 注入，其余 props 忽略）；condition 渲染 `id="true"`/`id="false"` 两个 source Handle。
  - `NodePalette.vue`：无 props；每个条目 `draggable`，dragstart 时 `dataTransfer.setData('application/hify-node', type)`。
  - 拖拽数据键约定：**`application/hify-node`**（Task 7 的 drop 端使用同一常量）。

- [x] **Step 1: 写失败测试（CanvasNode）**

`web/src/views/workflow/components/__tests__/CanvasNode.spec.ts`：

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import CanvasNode from '@/views/workflow/components/CanvasNode.vue'

// Handle 需要 VueFlow 注入的上下文，stub 掉——本测试只验证「按类型渲染哪几个连接点」
const HandleStub = {
  props: ['id', 'type', 'position'],
  template: '<span class="handle-stub" :data-type="type" :data-id="id ?? \'\'" />',
}

function mountNode(type: string, id = 'n1') {
  return mount(CanvasNode, {
    props: { id, type },
    global: { stubs: { Handle: HandleStub } },
  })
}

function handles(wrapper: ReturnType<typeof mountNode>) {
  return wrapper.findAll('.handle-stub').map((h) => ({
    type: h.attributes('data-type'),
    id: h.attributes('data-id'),
  }))
}

describe('CanvasNode', () => {
  it('展示类型标签与节点 id', () => {
    const w = mountNode('llm', 'llm_1')
    expect(w.text()).toContain('LLM')
    expect(w.text()).toContain('llm_1')
  })

  it('start：只出不进；end：只进不出', () => {
    expect(handles(mountNode('start', 'start'))).toEqual([{ type: 'source', id: '' }])
    expect(handles(mountNode('end', 'end'))).toEqual([{ type: 'target', id: '' }])
  })

  it('condition：一进两出，出口 handle id 为 true/false', () => {
    const hs = handles(mountNode('condition', 'if_1'))
    expect(hs).toContainEqual({ type: 'target', id: '' })
    expect(hs).toContainEqual({ type: 'source', id: 'true' })
    expect(hs).toContainEqual({ type: 'source', id: 'false' })
    expect(hs).toHaveLength(3)
  })

  it('普通节点（http）：一进一出', () => {
    const hs = handles(mountNode('http', 'http_1'))
    expect(hs.filter((h) => h.type === 'target')).toHaveLength(1)
    expect(hs.filter((h) => h.type === 'source')).toHaveLength(1)
  })
})
```

- [x] **Step 2: 写失败测试（NodePalette）**

`web/src/views/workflow/components/__tests__/NodePalette.spec.ts`：

```ts
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import NodePalette from '@/views/workflow/components/NodePalette.vue'

describe('NodePalette', () => {
  it('提供 llm/知识检索/条件/http 四类可拖条目，不含 start/end', () => {
    const w = mount(NodePalette, { global: { plugins: [ElementPlus] } })
    const items = w.findAll('[data-test^="palette-"]')
    expect(items.map((i) => i.attributes('data-test'))).toEqual([
      'palette-llm', 'palette-knowledge-retrieval', 'palette-condition', 'palette-http',
    ])
    expect(items.every((i) => i.attributes('draggable') === 'true')).toBe(true)
  })

  it('dragstart 把节点类型写进 dataTransfer（application/hify-node）', async () => {
    const w = mount(NodePalette, { global: { plugins: [ElementPlus] } })
    const setData = vi.fn()
    await w.find('[data-test="palette-llm"]').trigger('dragstart', {
      dataTransfer: { setData, effectAllowed: '' },
    })
    expect(setData).toHaveBeenCalledWith('application/hify-node', 'llm')
  })
})
```

- [x] **Step 3: 跑测试确认红**

Run: `cd web && pnpm vitest run src/views/workflow/components/__tests__/`
Expected: FAIL——两个组件模块不存在。

- [x] **Step 4: 实现 CanvasNode.vue**

```vue
<script setup lang="ts">
import { computed, type Component } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import {
  ChatDotRound, CircleCheck, Collection, Link, Switch, VideoPlay,
} from '@element-plus/icons-vue'

// Vue Flow 经 nodeTypes 注入的自定义节点：只声明用到的 props，其余（data/selected 等）忽略
const props = defineProps<{ id: string; type: string }>()

const META: Record<string, { label: string; icon: Component }> = {
  start: { label: '开始', icon: VideoPlay },
  llm: { label: 'LLM', icon: ChatDotRound },
  'knowledge-retrieval': { label: '知识检索', icon: Collection },
  condition: { label: '条件分支', icon: Switch },
  http: { label: 'HTTP 请求', icon: Link },
  end: { label: '结束', icon: CircleCheck },
}
const meta = computed(() => META[props.type] ?? { label: props.type, icon: Link })
</script>

<template>
  <div class="canvas-node" :class="`canvas-node--${type}`">
    <Handle v-if="type !== 'start'" type="target" :position="Position.Left" />
    <el-icon class="canvas-node__icon"><component :is="meta.icon" /></el-icon>
    <div class="canvas-node__text">
      <div class="canvas-node__label">{{ meta.label }}</div>
      <div class="canvas-node__id">{{ id }}</div>
    </div>
    <template v-if="type === 'condition'">
      <Handle id="true" type="source" :position="Position.Right" class="canvas-node__true" />
      <Handle id="false" type="source" :position="Position.Right" class="canvas-node__false" />
      <span class="canvas-node__branch canvas-node__branch--true">真</span>
      <span class="canvas-node__branch canvas-node__branch--false">假</span>
    </template>
    <Handle v-else-if="type !== 'end'" type="source" :position="Position.Right" />
  </div>
</template>

<style scoped lang="scss">
// 画布几何均为视觉初值，待实测微调
.canvas-node {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  min-width: 140px;
  padding: $spacing-sm $spacing-md;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-lighter);
}
.canvas-node__icon {
  font-size: 18px;
  color: var(--el-color-primary);
}
.canvas-node__label {
  font-size: 13px;
  font-weight: 600;
}
.canvas-node__id {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
// condition 双出口：true 上、false 下（与 Handle top 定位一致）
.canvas-node__true {
  top: 30%;
}
.canvas-node__false {
  top: 70%;
}
.canvas-node__branch {
  position: absolute;
  right: -18px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.canvas-node__branch--true {
  top: calc(30% - 8px);
}
.canvas-node__branch--false {
  top: calc(70% - 8px);
}
</style>
```

- [x] **Step 5: 实现 NodePalette.vue**

```vue
<script setup lang="ts">
import type { Component } from 'vue'
import { ChatDotRound, Collection, Link, Switch } from '@element-plus/icons-vue'
import type { WorkflowNodeType } from '@/types/workflow'

/** 拖拽数据键：与 WorkflowEditor 的 drop 端共用同一字符串。 */
const DRAG_KEY = 'application/hify-node'

// start/end 不在栏内：画布预置且不可删（spec §4 硬约定）
const ITEMS: { type: WorkflowNodeType; label: string; icon: Component }[] = [
  { type: 'llm', label: 'LLM', icon: ChatDotRound },
  { type: 'knowledge-retrieval', label: '知识检索', icon: Collection },
  { type: 'condition', label: '条件分支', icon: Switch },
  { type: 'http', label: 'HTTP 请求', icon: Link },
]

function onDragStart(event: DragEvent, type: WorkflowNodeType) {
  event.dataTransfer?.setData(DRAG_KEY, type)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
</script>

<template>
  <aside class="node-palette">
    <div class="node-palette__title">节点</div>
    <div
      v-for="item in ITEMS"
      :key="item.type"
      class="node-palette__item"
      :data-test="`palette-${item.type}`"
      draggable="true"
      @dragstart="onDragStart($event, item.type)"
    >
      <el-icon><component :is="item.icon" /></el-icon>
      <span>{{ item.label }}</span>
    </div>
    <div class="node-palette__hint">拖拽节点到画布</div>
  </aside>
</template>

<style scoped lang="scss">
.node-palette {
  width: 168px;
  flex-shrink: 0;
  padding: $spacing-md;
  border-right: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
}
.node-palette__title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: $spacing-sm;
}
.node-palette__item {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  padding: $spacing-sm;
  margin-bottom: $spacing-sm;
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  font-size: 13px;
  cursor: grab;
  &:hover {
    border-color: var(--el-color-primary);
    color: var(--el-color-primary);
  }
}
.node-palette__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
```

- [x] **Step 6: 跑测试确认绿 + typecheck**

Run: `cd web && pnpm vitest run src/views/workflow/components/__tests__/ && pnpm typecheck`
Expected: 6 passed；vue-tsc 无错。

- [x] **Step 7: Commit**

```bash
git add web/src/views/workflow/components/
git commit -m "feat(web/workflow): 画布节点组件（按类型渲染连接点）+ 左侧节点栏（拖拽源）"
```

---

### Task 7: 前端——WorkflowEditor 画布页 + 路由

**Files:**
- Create: `web/src/views/workflow/WorkflowEditor.vue`
- Modify: `web/src/router/index.ts`（`/apps/:appId/chat` 路由之后插入新路由）
- Test: `web/src/views/workflow/__tests__/WorkflowEditor.spec.ts`

**Interfaces:**
- Consumes: Task 5 `useWorkflowGraph`；Task 6 两组件与 `application/hify-node` 拖拽键；`getApp`(api/app)；`formatDateTime`(utils/datetime)；`useUserStore`。
- Produces: 路由 `name: 'WorkflowEditor'`, path `/apps/:appId/workflow`。

- [x] **Step 1: 写失败测试**

`web/src/views/workflow/__tests__/WorkflowEditor.spec.ts`。要点：stub 掉 `<VueFlow>`（happy-dom 不跑真画布，断言传入的受控 props），mock 路由与 api：

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { getApp } from '@/api/app'
import { getDraft, saveDraft } from '@/api/workflow'
import { useUserStore } from '@/stores/user'
import WorkflowEditor from '@/views/workflow/WorkflowEditor.vue'
import type { App } from '@/types/app'
import type { DraftResponse } from '@/types/workflow'

vi.mock('@/api/app', () => ({ getApp: vi.fn() }))
vi.mock('@/api/workflow', () => ({ getDraft: vi.fn(), saveDraft: vi.fn() }))

const routerPush = vi.fn()
let leaveGuard: (() => Promise<boolean> | boolean) | null = null
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { appId: '42' } }),
  useRouter: () => ({ push: routerPush }),
  onBeforeRouteLeave: (fn: () => Promise<boolean> | boolean) => {
    leaveGuard = fn
  },
}))

// @vue-flow/* 全 mock 成具名 stub：本测试只验证编辑器自身逻辑（加载/保存/权限/守卫），不测库。
// 注意 vi.mock 工厂会被提升，不能引用外部 const，stub 直接内联在工厂里。
vi.mock('@vue-flow/core', () => ({
  VueFlow: {
    name: 'VueFlow',
    props: ['nodes', 'edges'],
    template: '<div data-test="vf-stub" />',
  },
  Handle: { name: 'Handle', template: '<span />' },
  Position: { Left: 'left', Right: 'right' },
  useVueFlow: () => ({ screenToFlowCoordinate: (p: { x: number; y: number }) => p }),
}))
vi.mock('@vue-flow/background', () => ({ Background: { name: 'Background', template: '<span />' } }))
vi.mock('@vue-flow/controls', () => ({ Controls: { name: 'Controls', template: '<span />' } }))

globalThis.ResizeObserver = class {
  observe() {} unobserve() {} disconnect() {}
} as unknown as typeof ResizeObserver

const WF_APP: App = {
  id: '42', name: '工单分类器', description: null, type: 'workflow', modelId: null,
  modelName: null, modelUsable: false, config: { systemPrompt: null }, datasetIds: [],
  ownerId: '7', status: 'enabled',
  createTime: '2026-07-11T09:00:00+08:00', updateTime: '2026-07-11T09:00:00+08:00',
}
const DRAFT: DraftResponse = {
  graph: {
    nodes: [
      { id: 'start', type: 'start', data: {}, position: { x: 80, y: 200 } },
      { id: 'end', type: 'end', data: {}, position: { x: 640, y: 200 } },
    ],
    edges: [{ source: 'start', target: 'end', sourceHandle: null }],
  },
  updateTime: '2026-07-11T10:00:00+08:00',
}

function mountEditor() {
  return mount(WorkflowEditor, {
    global: {
      plugins: [ElementPlus],
      stubs: { NodePalette: true },
    },
  })
}

describe('WorkflowEditor', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    leaveGuard = null
    vi.mocked(getApp).mockResolvedValue(WF_APP)
    vi.mocked(getDraft).mockResolvedValue(DRAFT)
    useUserStore().user = { id: '7', username: 'bob', role: 'member' } // 默认 owner 本人
  })

  it('挂载：拉应用与草稿，画布收到转换后的 nodes/edges，工具栏显示应用名与保存时间', async () => {
    const w = mountEditor()
    await flushPromises()
    expect(getApp).toHaveBeenCalledWith('42')
    expect(getDraft).toHaveBeenCalledWith('42')
    expect(w.find('[data-test="vf-stub"]').exists()).toBe(true)
    const vf = w.findComponent({ name: 'VueFlow' })
    expect(vf.props('nodes')).toHaveLength(2)
    expect(vf.props('edges')).toHaveLength(1)
    expect(w.text()).toContain('工单分类器')
  })

  it('chat 应用误入 → 跳回应用列表', async () => {
    vi.mocked(getApp).mockResolvedValue({ ...WF_APP, type: 'chat' })
    mountEditor()
    await flushPromises()
    expect(routerPush).toHaveBeenCalledWith('/app')
  })

  it('owner 点保存 → saveDraft 提交草稿并提示成功', async () => {
    vi.mocked(saveDraft).mockResolvedValue({ ...DRAFT, updateTime: '2026-07-11T11:00:00+08:00' })
    const w = mountEditor()
    await flushPromises()
    await w.find('[data-test="wf-save"]').trigger('click')
    await flushPromises()
    expect(saveDraft).toHaveBeenCalledWith('42', expect.objectContaining({
      nodes: expect.arrayContaining([expect.objectContaining({ id: 'start' })]),
    }))
  })

  it('非 owner 非 admin：保存按钮禁用', async () => {
    useUserStore().user = { id: '999', username: 'eve', role: 'member' }
    const w = mountEditor()
    await flushPromises()
    expect(w.find('[data-test="wf-save"]').attributes('disabled')).toBeDefined()
  })

  it('admin 可保存他人应用', async () => {
    useUserStore().user = { id: '1', username: 'root', role: 'admin' }
    const w = mountEditor()
    await flushPromises()
    expect(w.find('[data-test="wf-save"]').attributes('disabled')).toBeUndefined()
  })

  it('离开守卫：不 dirty 直接放行', async () => {
    mountEditor()
    await flushPromises()
    expect(leaveGuard).not.toBeNull()
    expect(await leaveGuard!()).toBe(true)
  })
})
```

注意：`useUserStore().user` 的字段以 `stores/user.ts` 实际类型为准（与 `AppList.spec.ts` 的写法保持一致，如有 `role` 枚举差异照抄该文件）。

- [x] **Step 2: 跑测试确认红**

Run: `cd web && pnpm vitest run src/views/workflow/__tests__/WorkflowEditor.spec.ts`
Expected: FAIL——`WorkflowEditor.vue` 不存在。

- [x] **Step 3: 实现 WorkflowEditor.vue**

```vue
<script setup lang="ts">
import { computed, markRaw, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import { getApp } from '@/api/app'
import type { App } from '@/types/app'
import type { WorkflowNodeType } from '@/types/workflow'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/datetime'
import CanvasNode from './components/CanvasNode.vue'
import NodePalette from './components/NodePalette.vue'
import { useWorkflowGraph } from './composables/useWorkflowGraph'

/** 与 NodePalette 共用的拖拽数据键。 */
const DRAG_KEY = 'application/hify-node'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const appId = String(route.params.appId)

const app = ref<App | null>(null)
const graph = useWorkflowGraph(appId)
const { screenToFlowCoordinate } = useVueFlow()

// 六类节点共用一个渲染组件，type 原样透传（spec §3：类型零转换）
const nodeTypes = {
  start: markRaw(CanvasNode),
  llm: markRaw(CanvasNode),
  'knowledge-retrieval': markRaw(CanvasNode),
  condition: markRaw(CanvasNode),
  http: markRaw(CanvasNode),
  end: markRaw(CanvasNode),
}

/** 团队共享制：读全员，改仅 owner/Admin（与后端 10004 双保险，同 AppList.canModify）。 */
const canEdit = computed(
  () => userStore.isAdmin || app.value?.ownerId === userStore.user?.id,
)

onMounted(async () => {
  try {
    const loaded = await getApp(appId)
    if (loaded.type !== 'workflow') {
      router.push('/app')
      return
    }
    app.value = loaded
    await graph.load()
  } catch {
    /* 失败由 request 拦截器统一 toast */
  }
})

async function onSave() {
  try {
    await graph.save()
    ElMessage.success('已保存')
  } catch {
    /* 拦截器已 toast（含 18001 结构损坏兜底）*/
  }
}

/** Vue Flow 连线事件：写回自有 edges（受控模式）。 */
function onConnect(c: { source: string; target: string; sourceHandle?: string | null }) {
  graph.connect(c)
}

function onDrop(event: DragEvent) {
  const type = event.dataTransfer?.getData(DRAG_KEY) as WorkflowNodeType | ''
  if (!type || !canEdit.value) return
  const position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  graph.addNode(type, position)
}

onBeforeRouteLeave(async () => {
  if (!graph.dirty.value) return true
  try {
    await ElMessageBox.confirm('有未保存的修改，确定离开？', '离开确认', { type: 'warning' })
    return true
  } catch {
    return false
  }
})
</script>

<template>
  <div class="wf-editor">
    <div class="wf-editor__toolbar">
      <el-page-header @back="router.push('/app')">
        <template #content>
          <span class="wf-editor__name">{{ app?.name ?? '' }}</span>
          <el-tag size="small" class="wf-editor__tag">工作流</el-tag>
        </template>
      </el-page-header>
      <div class="wf-editor__actions">
        <span v-if="graph.savedAt.value" class="wf-editor__saved" data-test="wf-saved-at">
          上次保存 {{ formatDateTime(graph.savedAt.value) }}
        </span>
        <el-tooltip :disabled="canEdit" content="仅创建者或管理员可编辑" placement="bottom">
          <span>
            <el-button
              type="primary"
              data-test="wf-save"
              :disabled="!canEdit"
              :loading="graph.saving.value"
              @click="onSave"
              >保存</el-button
            >
          </span>
        </el-tooltip>
      </div>
    </div>

    <div class="wf-editor__body">
      <NodePalette />
      <div class="wf-editor__canvas" @dragover.prevent @drop="onDrop">
        <VueFlow
          v-model:nodes="graph.nodes.value"
          v-model:edges="graph.edges.value"
          :node-types="nodeTypes"
          @connect="onConnect"
        >
          <Background />
          <Controls />
        </VueFlow>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.wf-editor {
  display: flex;
  flex-direction: column;
  // 视觉初值待实测微调：占满主布局内容区高度
  height: calc(100vh - 140px);
}
.wf-editor__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: $spacing-md;
}
.wf-editor__name {
  font-weight: 600;
}
.wf-editor__tag {
  margin-left: $spacing-sm;
}
.wf-editor__actions {
  display: flex;
  align-items: center;
  gap: $spacing-md;
}
.wf-editor__saved {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.wf-editor__body {
  display: flex;
  flex: 1;
  min-height: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  overflow: hidden;
}
.wf-editor__canvas {
  flex: 1;
  min-width: 0;
}
</style>
```

**已知兼容点**：`v-model:nodes` / `v-model:edges` 是 @vue-flow/core 1.x 的受控绑定。若装出的版本对该写法报「unknown prop / 不回写」，等价替代（同为官方受控 API，逻辑不变）：改 `:nodes` + `:edges` 传入、`:apply-default="false"`、监听 `@nodes-change` / `@edges-change` 并用 `applyNodeChanges` / `applyEdgeChanges`（`@vue-flow/core` 导出）把变更写回 `graph.nodes` / `graph.edges`。测试不受影响（stub 只断言 `nodes`/`edges` props）。

- [x] **Step 4: 加路由**

`web/src/router/index.ts` 在 `/apps/:appId/chat` 路由对象之后插入：

```ts
  {
    path: '/apps/:appId/workflow',
    name: 'WorkflowEditor',
    component: () => import('@/views/workflow/WorkflowEditor.vue'),
    meta: { requiresAuth: true, title: '工作流编排' },
  },
```

- [x] **Step 5: 跑测试确认绿 + typecheck**

Run: `cd web && pnpm vitest run src/views/workflow/ && pnpm typecheck`
Expected: WorkflowEditor 6 passed（连同 Task 4/5/6 的用例全绿）；vue-tsc 无错（`nodeTypes` 若类型不匹配，用 `as NodeTypesObject`——从 `@vue-flow/core` 导入该类型——收窄，不用 any）。

- [x] **Step 6: 手动冒烟（dev 环境）**

Run: `cd web && pnpm dev`（后端与库已在本机跑着）
用已有 workflow 应用（Postman 建过的）访问 `http://localhost:5173/apps/<id>/workflow`：画布渲染节点与连线、可拖动、Controls 缩放可用。截图确认后关掉。

- [x] **Step 7: Commit**

```bash
git add web/src/views/workflow/WorkflowEditor.vue web/src/router/index.ts \
        web/src/views/workflow/__tests__/WorkflowEditor.spec.ts
git commit -m "feat(web/workflow): 画布编辑器页（受控 Vue Flow+拖拽投放+保存/离开守卫/权限）+ 路由"
```

---

### Task 8: 前端——AppList 支持创建 workflow 应用 + 编排入口

**Files:**
- Modify: `web/src/api/app.ts`（`createApp` 加 type 参数）
- Modify: `web/src/views/app/AppList.vue`
- Test: `web/src/api/__tests__/app.spec.ts`（createApp 用例扩展）
- Test: `web/src/views/app/__tests__/AppList.spec.ts`（追加用例）

**Interfaces:**
- Produces: `createApp(body: AppForm, type: AppType = 'chat')`——既有调用不带第二参行为不变。
- Consumes: Task 7 的路由 `/apps/:appId/workflow`。

- [x] **Step 1: 写失败测试（api 层）**

`web/src/api/__tests__/app.spec.ts` 的 `createApp → POST ...` 用例后追加：

```ts
  it('createApp 传 workflow → body 带 type=workflow', () => {
    createApp(FORM, 'workflow')
    expect(request.post).toHaveBeenCalledWith('/app/apps', { ...FORM, type: 'workflow' })
  })
```

- [x] **Step 2: 写失败测试（AppList）**

`web/src/views/app/__tests__/AppList.spec.ts` 顶部常量区追加：

```ts
const WF: App = { ...MINE, id: '8', name: '工单流', type: 'workflow' }
```

describe 内追加用例：

```ts
  it('workflow 行：类型列显示「工作流」、主按钮为编排并跳画布、无试聊', async () => {
    vi.mocked(listApps).mockResolvedValue(page([WF]))
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('工作流')
    expect(wrapper.find('[data-test="chat-8"]').exists()).toBe(false)
    await wrapper.find('[data-test="design-8"]').trigger('click')
    expect(routerPush).toHaveBeenCalledWith('/apps/8/workflow')
  })

  it('创建弹窗选「工作流」→ createApp 带 type=workflow，且不显示模型/知识库/提示词字段', async () => {
    vi.mocked(createApp).mockResolvedValue(WF)
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="create-open"]').trigger('click')
    // 选类型=工作流（radio 的 label 值即 AppType）
    const radio = wrapper.findComponent({ name: 'ElRadioGroup' })
    await radio.vm.$emit('update:modelValue', 'workflow')
    await flushPromises()
    expect(wrapper.find('[data-test="form-model"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="form-datasets"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="form-prompt"]').exists()).toBe(false)
    await wrapper.find('[data-test="form-name"] input').setValue('工单流')
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createApp).toHaveBeenCalledWith(expect.objectContaining({ name: '工单流' }), 'workflow')
  })

  it('编辑 workflow 应用：弹窗不显示模型/知识库/提示词字段', async () => {
    vi.mocked(listApps).mockResolvedValue(page([WF]))
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="edit-8"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="form-model"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="form-prompt"]').exists()).toBe(false)
  })
```

- [x] **Step 3: 跑测试确认红**

Run: `cd web && pnpm vitest run src/api/__tests__/app.spec.ts src/views/app/__tests__/AppList.spec.ts`
Expected: FAIL——新用例（`design-8` 不存在、createApp 第二参未实现等）。既有用例必须仍绿。

- [x] **Step 4: 改 api/app.ts**

`createApp` 替换为：

```ts
/** 新建。type 缺省 chat；画布轮起支持 workflow。后端：POST /api/v1/app/apps */
export function createApp(body: AppForm, type: AppType = 'chat') {
  return request.post<App>(BASE, { ...body, type })
}
```

- [x] **Step 5: 改 AppList.vue**

script 部分：

1. import 区把 `import type { App, AppForm } from '@/types/app'` 改为
   `import type { App, AppForm, AppType } from '@/types/app'`。
2. `openChat` 旁新增：

```ts
function openDesign(app: App) {
  router.push(`/apps/${app.id}/workflow`)
}
```

3. 表单区新增类型状态（`form` 定义之后）：

```ts
// 应用类型：创建时可选，编辑时锁定为行的既有类型（后端不支持改型）
const formType = ref<AppType>('chat')
```

4. `openCreate` 里重置项追加 `formType.value = 'chat'`；`openEdit` 里追加 `formType.value = row.type`。
5. `submitForm` 的创建分支 `await createApp({ ...form })` 改为 `await createApp({ ...form }, formType.value)`。

template 部分：

6. 类型列（现为写死的 `<el-tag>对话</el-tag>`）改为：

```html
        <el-table-column label="类型">
          <template #default="{ row }">
            <el-tag :type="(row as App).type === 'workflow' ? 'warning' : 'primary'">
              {{ (row as App).type === 'workflow' ? '工作流' : '对话' }}
            </el-tag>
          </template>
        </el-table-column>
```

7. 操作列里，「试聊」按钮整体改为按类型分流（原试聊按钮保持原样放进 chat 分支）：

```html
              <el-button
                v-if="(row as App).type === 'chat'"
                size="small"
                type="primary"
                :data-test="`chat-${(row as App).id}`"
                :disabled="!(row as App).modelUsable || (row as App).status === 'disabled'"
                @click="openChat(row as App)"
                >试聊</el-button
              >
              <el-button
                v-else
                size="small"
                type="primary"
                :data-test="`design-${(row as App).id}`"
                @click="openDesign(row as App)"
                >编排</el-button
              >
```

8. 弹窗「类型」表单项（现为 `<el-tag>对话应用</el-tag>`）改为：创建时 radio、编辑时锁定 tag：

```html
        <el-form-item label="类型">
          <el-radio-group v-if="editingId === null" v-model="formType" data-test="form-type">
            <el-radio value="chat">对话应用</el-radio>
            <el-radio value="workflow">工作流应用</el-radio>
          </el-radio-group>
          <el-tag v-else>{{ formType === 'workflow' ? '工作流应用' : '对话应用' }}</el-tag>
        </el-form-item>
```

9. 「模型」「关联知识库」「系统提示词」三个 `<el-form-item>` 分别加 `v-if="formType === 'chat'"`（workflow 应用无这些 chat 专属配置）。

- [x] **Step 6: 跑测试确认绿 + typecheck**

Run: `cd web && pnpm vitest run src/api/__tests__/app.spec.ts src/views/app/__tests__/AppList.spec.ts && pnpm typecheck`
Expected: 全绿（含既有用例无一变红）；vue-tsc 无错。

- [x] **Step 7: Commit**

```bash
git add web/src/api/app.ts web/src/api/__tests__/app.spec.ts \
        web/src/views/app/AppList.vue web/src/views/app/__tests__/AppList.spec.ts
git commit -m "feat(web/app): 创建应用支持工作流类型，列表提供编排入口"
```

---

### Task 9: 收口——全量回归 + self-check 入档

**Files:**
- Modify: `docs/self-check.md`（末尾追加本轮条目）

- [ ] **Step 1: 后端全量回归**

Run: `mvn -f server/pom.xml verify`
Expected: `Tests run: 586+, Failures: 0, Errors: 0`，退出码 0（以 surefire 汇总为准）。

- [ ] **Step 2: 前端全量回归**

Run: `cd web && pnpm test && pnpm typecheck && pnpm build && pnpm lint`
Expected: 四条命令全部退出码 0；vitest 通过数 = 既有 + 本轮新增（graphTransform 9 / useWorkflowGraph 5 / 组件 6 / 编辑器 6 / api 3 / AppList 3，约 +32）。

- [ ] **Step 3: E2E 冒烟不回归**

Run: `cd web && pnpm e2e`
Expected: 既有 golden-journey + smoke 全绿（本轮未动 chat 旅程所涉页面的 data-test 锚点；AppList 的 `chat-{id}`、`form-*`、`create-open`、`form-submit` 均保留）。

- [ ] **Step 4: 追加 self-check 条目**

在 `docs/self-check.md` 末尾追加（数字以实跑为准）：

```markdown
## 2026-07-XX Workflow 画布 C1（画布地基+保存）

- 本轮范围：后端 `GraphValidator` 拆 `validateBasics`（保存草稿底线校验）/`validateAndOrder`（运行全量校验，行为不变）；前端装 @vue-flow/core+background+controls，新增 `/apps/:appId/workflow` 画布页（受控 Vue Flow、左栏拖拽、连线、保存/离开守卫、非 owner 只读）、GraphDef↔VueFlow 纯转换层（边 id 确定性生成/网格兜底/往返保真/节点 id 自增）、AppList 支持创建 workflow 应用与编排入口。零迁移、零新错误码。
- 拍板决策（spec 入档）：三子轮 C1→C2→C3；保存校验放宽；左栏拖拽+右侧抽屉（抽屉归 C2）；手动保存不自动保存；预置 start/end 不预连线。
- 测试结果：（逐条填实跑命令与通过数）
- DoD 待人工验收（重启服务后）：spec §8 七条。
- 留账：C2 配置抽屉/未配齐标红；C3 运行调试；运行历史页推迟发布轮；E2E workflow 旅程推迟。
```

- [ ] **Step 5: Commit**

```bash
git add docs/self-check.md
git commit -m "docs: 画布 C1 自检入档"
```

- [ ] **Step 6: 人工验收提示（执行者输出给用户，不代跑）**

提醒用户：**重启后端服务**（后端代码有改动，重打包+换进程）后按 spec §8 七条验收：
1. 新建工作流应用 → 编排 → 预置 start/end；
2. 拖 llm 连 start→llm→end 保存 → 刷新原样恢复；
3. 半成品（不连线、llm 空配置）保存成功；
4. Postman W3a 集合存分支草稿 → 画布打开 true/false 边挂对出口；
5. 画布保存过的完整草稿 Postman 触发仍 succeeded；
6. 非 owner 账号：可看、保存禁用带 tooltip；
7. 触发未配完草稿 → 18001。

---

## Self-Review 记录

- **Spec 覆盖**：§2 后端→Task 1/2；§3 架构/依赖→Task 3-7；§4 转换规则→Task 4/5；§5 AppList/路由→Task 7 Step 4 + Task 8；§6 权限/错误→Task 7（canEdit/tooltip/拦截器）；§7 测试→各任务 TDD + Task 9；§8 DoD→Task 9 Step 6。spec 提到的 `useCan()` 现库并不存在（frontend-standards 的目标形态），按既有 `AppList.canModify` 内联手法落地，不新建抽象（rule-of-three）。
- **类型一致性**：`FlowNode/FlowEdge/edgeId/presetGraph/toFlow/fromFlow/nextNodeId`（Task 4 定义）与 Task 5/6/7 的引用逐一核对一致；`DRAG_KEY='application/hify-node'` 在 NodePalette 与 WorkflowEditor 中同值。
- **无占位符**：所有代码块完整可落盘；唯一的条件分支（v-model:nodes 兼容性）给出了确切替代 API 与不变的测试口径。
