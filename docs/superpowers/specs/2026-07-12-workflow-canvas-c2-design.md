# Workflow 画布 C2：六类节点配置抽屉（设计）

> 2026-07-12 brainstorm 定稿。画布轮三子轮之二（全景与子轮边界见
> `2026-07-11-workflow-canvas-c1-design.md` §0，全轮拍板不重开）。
> **后端零改动、零迁移、零新错误码**——本轮纯前端。

## 0. 范围与拍板

**做**：
- 右侧 `el-drawer` 配置抽屉 ×6 类节点表单（字段清单见 §3，权威来源=后端
  `GraphValidator.validateAndOrder` 各 `require*` 方法 + 各 executor 实际读取的字段）
- 未配齐节点标红：橙色叹号徽章 + tooltip 列缺失项（提示性，不阻断保存）
- `{{nodeId.field}}` 变量输入辅助：可引用变量面板，点击插入光标处
- `types/workflow.ts` 的 `GraphNode.data` 收窄为按类型判别的联合（C1 留账）

**不做**（YAGNI 或归后续）：
- 输入框内 `{{` 自动补全下拉（Dify 款）——工程量约为变量面板的 2-3 倍，面板已够用
- 图级校验提示（condition 出边数、连通性、变量引用拓扑序）——运行时后端报错兜底，C3 排障场景
- 节点自定义名称/描述、节点复制——无此需求
- E2E workflow 旅程——按 testing-standards §5.6 既定口径继续推迟，留账

**本轮拍板**（brainstorm 逐项确认）：
1. 变量辅助 = 可引用变量面板 + 点击插入（不做输入框内自动补全）。
2. 标红呈现 = 节点右上角橙色叹号徽章 + hover tooltip 列缺失项；不动边框（避免与
   Vue Flow 选中态高亮打架）。判定口径 = 严格镜像后端 `require*` 字段规则，实时计算；
   start/end 后端不强制故永不标红。
3. 表单修改**即时写回**节点 data（无确定按钮，关抽屉即生效）；写回只改画布内存态→dirty，
   落库仍靠 C1 的手动保存按钮。
4. 组件架构 = 方案 A：抽屉壳 + 六个按类型拆分的子表单组件（否决单个大组件 v-if 六段
   与 schema 配置驱动渲染器——前者单文件过大，后者过度抽象）。

**独立验收标准**（沿 C1 spec §0 原文）：纯画布从零配出 W3a 等价图，保存后 Postman
触发运行 succeeded。

## 1. 交互与数据流

- **打开**：单击画布节点 → `WorkflowEditor` 记录 `selectedNodeId` → 右侧 `el-drawer`
  打开（`modal=false` 不遮画布）；点画布空白/关闭按钮 → 关闭；切换选中节点抽屉内容直接切换。
- **写回**：表单控件绑定节点 `data`，emit 补丁 → `useWorkflowGraph` 新增
  `updateNodeData(id, patch)` 合并写回。走 composable 而非表单直改对象：写回集中一处、
  纯逻辑可单测，维持 C1「nodes/edges 是画布唯一数据源」口径。dirty 快照对比自动感知，
  保存/离开守卫零改动。
- **只读**：非 owner/Admin 抽屉照常打开（能看配置），所有控件 `disabled`——复用 C1
  的 `canEdit` 传入抽屉，与保存按钮同一双保险口径（后端 10004 兜底）。

**新文件**（沿 frontend-standards 目录约定）：

```
web/src/views/workflow/
├── components/
│   ├── NodeConfigDrawer.vue      # 抽屉壳：标题(类型+节点id)、按类型挂表单、变量面板、只读态
│   ├── VariablePanel.vue         # 可引用变量面板（点击插入）
│   └── forms/
│       ├── StartForm.vue / LlmForm.vue / KnowledgeForm.vue
│       └── ConditionForm.vue / HttpForm.vue / EndForm.vue
└── composables/
    ├── useNodeIssues.ts          # 未配齐判定纯函数（镜像后端 require* 规则）
    ├── useUpstreamVars.ts        # 可引用变量计算纯函数（沿入边反向遍历祖先）
    └── useVarInsert.ts           # 「最后聚焦字段 + 光标处插入」小 composable，各表单复用
```

## 2. 类型收窄（types/workflow.ts）

`GraphNode` 从 `data: Record<string, unknown>` 收窄为按 `type` 判别的联合；
**字段全部可选**（草稿允许半成品，必填是运行时语义）：

```ts
StartNodeData     { inputs?: { name: string }[] }          // 纯前端声明，后端不读
LlmNodeData       { modelId?: string; systemPrompt?: string; userPrompt?: string }
KnowledgeNodeData { datasetIds?: string[]; query?: string }
ConditionNodeData { left?: string; operator?: string; right?: string }
HttpNodeData      { method?: string; url?: string; headers?: Record<string, string>; body?: string }
EndNodeData       { outputs?: { name: string; value: string }[] }
```

- `modelId` / `datasetIds` 元素存**字符串**（api-standards：Long 序列化为字符串）。
- start 的 `inputs` 声明：引擎透传触发入参、不读 data，jsonb 原样保真——纯前端约定，
  用途 = 下游变量提示 + C3 运行表单预填。**零后端改动**。
- end 的 `outputs`：引擎按声明逐项渲染为最终输出；后端 validator 不强制（缺了运行输出空 map）。

## 3. 六类表单字段（权威来源：后端 require* + executor）

| 表单 | 控件 | 运行时必填（=标红项） |
|---|---|---|
| StartForm | `inputs` 行编辑（每行变量名 + 删除 + 底部「添加输入」）；名字限 `[\w-]+`（与后端变量正则一致，否则下游引用不上）；说明文案「运行时由触发方传入，声明后下游可引用」 | 无 |
| LlmForm | 模型 `el-select`（`listChatModels` + 失效模型禁用项兜底，照抄 AppList）、`systemPrompt` textarea（可选）、`userPrompt` textarea（变量✓） | `modelId`（数字）、`userPrompt` |
| KnowledgeForm | `datasetIds` el-select multiple（`listDatasets` + 已删除项禁用兜底，照抄 AppList）、`query` 输入框（变量✓） | `query`、`datasetIds` 非空 |
| ConditionForm | `left`（变量✓）、`operator` el-select（白名单 8 个：`== != > >= < <= contains notContains`）、`right`（变量✓） | 三元组全有且 operator 在白名单 |
| HttpForm | `method` el-select（GET/POST/PUT/DELETE，默认 GET）、`url`（变量✓）、`headers` key-value 行编辑（value 变量✓）、`body` textarea（可选，变量✓） | `method` 在白名单、`url` |
| EndForm | `outputs` 行编辑（每行 `name` + `value`，value 变量✓，典型 `{{llm_1.text}}`）；说明文案「运行的最终输出」 | 无 |

## 4. 变量面板（VariablePanel）

- **口径**：沿当前节点入边**反向遍历所有祖先**——与后端「只能引用拓扑序更早的节点」
  在已连线图上等价；未连线时空态「连线后这里会列出可引用的上游输出」。
  不列全图节点：列了也引用不了（运行时报「引用了未就绪的节点输出」），提示只提示合法项。
  遍历带 visited 防环（画布可画出环，运行时才报）。
- **内容**：每个祖先一组，列输出字段——start→声明的 inputs 名（未声明则组内提示）、
  llm→`text`、kb→`text`/`count`、condition→`result`、http→`status`/`body`/`headers`。
- **插入**：表单「支持变量」字段（§3 标「变量✓」）聚焦时记录为插入目标；点面板变量 →
  该字段光标处插入 `{{nodeId.field}}`。从未聚焦过 → 插入表单主字段
  （llm→userPrompt、kb→query、condition→left、http→url、end→最后一行 value，
  end 无行时自动新增一行再插入其 value）。逻辑收进 `useVarInsert`。

## 5. 标红（useNodeIssues）

纯函数：输入节点 → 输出缺失项文案数组。规则严格镜像后端（不自创）：

- llm：`modelId` 缺/非数字、`userPrompt` 空
- knowledge-retrieval：`query` 空、`datasetIds` 空
- condition：`left`/`operator`/`right` 任一空、operator 不在白名单
- http：`method` 不在白名单、`url` 空
- start / end：永远空数组

`CanvasNode` 接收 Vue Flow 透传的 `data` prop 自己算（写回即时响应）：有缺失 →
右上角橙色叹号徽章 + tooltip 列缺失项；配齐即消失。**只做字段级**，图级问题不提示。

## 6. 测试策略（TDD，vitest，测试放 __tests__/）

- **纯函数重点**：`useNodeIssues` 逐类型逐字段（含 modelId 非数字、operator 非法、
  data 为空对象）；`useUpstreamVars` 链式/分支/未连线/环防御；`updateNodeData` 合并语义。
- **组件**：各表单「渲染字段 + emit 补丁 + disabled 只读」；抽屉「按类型挂对表单」；
  `useVarInsert`「光标处插入/默认字段兜底」；CanvasNode 徽章按 issues 显隐。
- **集成**（WorkflowEditor 层）：点节点开抽屉 → 改字段 → 节点 data 更新且 dirty。
- 回归门槛：前端 `pnpm test` / `typecheck` / `build` / `lint` 全绿；
  后端零改动，`mvn verify` 仅回归确认（surefire 报告聚合判定，不 grep BUILD SUCCESS）。

## 7. C2 DoD（人工验收）

1. 纯画布从零配出 W3a 等价图（start→kb→condition→真假双分支 llm→end），保存后
   Postman 触发运行 `succeeded`。
2. 未配齐节点显示徽章 + tooltip 列缺失项，配齐即消失；不阻断保存（半成品保存后刷新，
   配置原样恢复）。
3. 变量面板列出的恰为祖先节点输出（未连线为空态），点击插入光标处。
4. 非 owner 打开他人画布抽屉：能看配置，全字段只读。
5. 模型/知识库失效项禁用兜底展示（不裸露数字 id）。

## 8. 留账与已知边界

- C3：运行调试面板（同步 run + 节点徽章 + 输入输出查看），start 的 `inputs` 声明
  在 C3 用于生成运行输入表单。
- 输入框内 `{{` 自动补全：二期候选，变量面板不够用时再议。
- 图级校验提示（出边数/连通性/引用拓扑序）：不做，运行时后端 18001 兜底。
- start `inputs` 声明与实际触发入参可能不一致（声明 city 实际传 town）：后端本就
  不校验触发入参，声明只是提示；C3 运行表单按声明生成后此偏差自然收敛。
- headers 的 key 重复：行编辑写回 `Record<string,string>` 时后写赢，不做重复提示。
