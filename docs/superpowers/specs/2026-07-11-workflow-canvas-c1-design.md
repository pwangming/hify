# Workflow 画布 C1：画布地基 + 保存（设计）

> 2026-07-11 brainstorm 定稿。画布轮（Vue Flow）三子轮之首。本文含**画布轮全景**（三子轮边界，
> 全轮拍板）+ **C1 详细设计**；C2/C3 到各自子轮再写详细 spec（沿 knowledge K1 先例）。
> 后端 workflow API 已就绪（W1~W3b），本轮后端仅一处小改（放宽草稿保存校验），零迁移。

## 0. 画布轮全景（三子轮，全轮拍板）

| 子轮 | 交付 | 独立验收标准 |
|---|---|---|
| **C1 画布地基+保存**（本 spec） | 后端放宽草稿校验；装 Vue Flow；AppList 支持创建 workflow 应用 + 画布路由；画布：加载渲染、左侧栏拖节点、连线/删除、保存。节点暂不可配置（新节点 data 为空） | 拖出 start→llm→end 保存，刷新后节点位置/连线原样恢复；Postman 存过的 W3a 分支草稿在画布正确打开（true/false 边挂对出口） |
| **C2 节点配置面板** | 右侧 el-drawer ×6 类节点表单（start 输入声明 / llm 模型+提示词 / kb 数据集多选+query / condition 三元组 / http method+url+headers+body / end 输出声明）；未配齐节点标红（提示不阻断保存） | 纯画布从零配出 W3a 等价图，保存后 Postman 触发运行 succeeded |
| **C3 运行调试** | 画布内「运行」→ start 输入表单 → 同步触发 → 节点状态徽章（成功/失败/跳过）→ 点节点看本次输入/输出/错误 → 展示最终输出 | 命中/未命中两方向在画布跑通且徽章正确；故意配错模型 → 失败节点红标可排障，全程不出画布 |

**切分逻辑**：每刀切在前后端契约点——C1 交付「图的往返保真」（存进去=取出来），
C2 交付「图的内容合法」（配出来的图后端认），C3 交付「图的执行可见」（run 结果映射回画布）。

**全轮拍板决策**：
1. 范围 = 编排 + 保存 + 运行调试（没有运行调试的画布只是更贵的 JSON 编辑器）；运行历史列表页**不做**，推迟到发布/对外 API 轮。
2. 草稿保存放宽为结构底线校验，完整校验后移到触发运行时（符合「草稿」语义，半成品可保存）。
3. 交互范式：左侧节点栏拖拽入画布 + 右侧抽屉配置（Dify 同款心智，Vue Flow 对拖拽投放有现成支持）。

## 1. C1 范围与目标

**做**：
- 后端：`GraphValidator` 拆两层，`saveDraft` 只跑底线校验（详见 §2）
- 前端：装 `@vue-flow/core` + `@vue-flow/background` + `@vue-flow/controls`（用户拍板，三个都是 Vue Flow 官方生态包）
- AppList 改造：创建弹窗加应用类型 radio（对话/工作流）；workflow 行主按钮「编排」→ 画布路由
- 画布页 `/apps/:appId/workflow`：加载草稿渲染、拖拽加节点、连线/删线/删节点、手动保存、上次保存时间、非 owner 只读

**不做**（YAGNI 或归后续子轮）：
- 节点配置表单（C2）、提示性校验/未配齐标红（C2）、运行/调试（C3）
- 自动保存（拍板：手动保存按钮 + 离开确认；单人编辑冲突概率低，省防抖/冲突心智）
- 自动布局算法（无 position 的老草稿用简单网格兜底，不引 dagre/elk）
- 连线合法性阻断（后端运行时校验兜底，前端提示归 C2）
- E2E workflow 旅程（按 testing-standards §5.6 既定口径继续推迟，留账）

**C1 拍板决策**：手动保存不自动保存；预置 start/end 不预连线（显式优于隐式）；
Vue Flow 附属包装 background + controls；新节点 data 为空对象。

## 2. 后端改动：放宽草稿保存校验

**动机**：现状 `PUT draft` 与触发运行共用 `validateAndOrder` 全量校验，画布半成品
（llm 未配 modelId、图暂时不连通）无法保存，关页即丢工作。「草稿」应可存半成品。

`GraphValidator` 拆两层：

| 方法 | 用途 | 校验内容 |
|---|---|---|
| `validateBasics(graph)`（新增） | 保存草稿 | 图非空、节点数 ≤ 上限、节点 id 非空且不重、节点类型合法、边引用的节点必须存在（数据完整性底线——存进去还能读出来） |
| `validateAndOrder(graph)`（现有） | 触发运行 | 内部先跑 `validateBasics`，再做全量：恰好一个 start/end、各类型必填字段、condition 出边规则、连通性、无环、变量引用拓扑序。行为不变 |

- `WorkflowDraftService.saveDraft`：`validateAndOrder` → `validateBasics`；权限检查不动（写 owner/Admin）。
- 错误码 **18001 不变**（已发布契约只增不改），只是完整校验的触发时机从保存后移到运行。
- 测试调整：既有「保存非法图报 18001」用例改为「保存成功 + 触发运行时报 18001」；
  新增「半成品（缺 modelId / 无 end / 不连通）可保存且读回一致」用例。
- 零迁移、零新错误码、零 API 变更（路由/方法/请求响应结构均不动）。

## 3. 前端架构

**新文件**（沿 frontend-standards 目录约定）：

```
web/src/
├── types/workflow.ts               # GraphDef / GraphNode / GraphEdge / DraftResponse
├── api/workflow.ts                 # getDraft / saveDraft（C3 再加 run 系列）
└── views/workflow/
    ├── WorkflowEditor.vue          # 画布页（路由 /apps/:appId/workflow，主布局内）
    ├── components/
    │   ├── NodePalette.vue         # 左侧节点栏：llm/知识检索/条件/http 四类可拖（无 start/end）
    │   └── CanvasNode.vue          # 通用自定义节点：类型图标+标签+节点id，按类型渲染连接点
    └── composables/
        └── useWorkflowGraph.ts     # 核心转换层 + 加载/保存/dirty（纯逻辑，单测重点）
```

**类型约定**（types/workflow.ts，对齐后端 DTO）：id 类 string；`GraphNode.data` 为
`Record<string, unknown>`（C2 再收窄成按类型的联合）；`position` 可空 `{x,y}`。

**Vue Flow 集成**：后端 `node.type` 六个值**原样**作 Vue Flow 节点类型，`:node-types`
把六个类型都映射到 `CanvasNode`（类型零转换，组件内按 type 呈现差异）。连接点规则：

| 节点类型 | target（入） | source（出） |
|---|---|---|
| start | 无 | 1 |
| end | 1 | 无 |
| condition | 1 | 2（handle id 固定 `"true"` / `"false"`，对应 `GraphEdge.sourceHandle`） |
| 其余 | 1 | 1 |

## 4. 转换层规则（useWorkflowGraph）

- **加载**：`GET draft` → `toFlow()`。草稿为 null（新应用）→ 预置 start/end 两节点（不预连线，位置左右分开）。
- **边 id**：后端不存边 id，Vue Flow 必需 → 按 `e-{source}-{sourceHandle ?? '_'}-{target}` 生成；
  `fromFlow()` 保存时剥掉 id 与 targetHandle（后端 `GraphEdge` 只有 source/target/sourceHandle）。
- **position 兜底**：老草稿（API 手拼）节点无 position → 按数组序排简单网格（横向为主，具体间距是实现细节）；一旦保存即回写真实坐标。
- **start/end 硬约定**：id 字面固定 `"start"` / `"end"`（后端 `GraphValidator` 连通性校验从
  这两个 id 出发，是既有约定）；两节点 `deletable: false`，左侧栏不提供 → 天然满足「恰好一个」。
- **节点 id 生成**：类型前缀 + 自增——`llm_N` / `kb_N` / `if_N` / `http_N`（与既有测试、
  Postman 集合命名风格一致），N = 画布内同前缀最大序号 + 1（只保证与现存节点不冲突，不追溯已删除的历史 id）。
- **新节点 data**：空对象 `{}`（放宽校验后可保存；C2 抽屉来填）。
- **dirty**：nodes/edges 相对上次保存快照有变更即 dirty；保存成功清除；离开路由时 dirty → 确认弹窗。

## 5. AppList 改造与路由

- 创建弹窗加「应用类型」radio（对话 / 工作流，Element Plus）：选工作流时隐藏模型/系统提示词/
  知识库绑定字段（那些是 chat 的 config），`api/app.ts` 的 `createApp` 不再写死 `type:'chat'`。
  后端创建接口 W1 起已支持 `type=workflow`，零后端改动。
- 列表行为：workflow 应用主按钮「编排」→ `/apps/:appId/workflow`；chat 应用行为不变（「对话」）。
  编辑弹窗对 workflow 应用只暴露名称/描述（config 是 chat 形态，不适用）。
- 路由：`{ path: '/apps/:appId/workflow', name: 'WorkflowEditor', meta: { requiresAuth: true, title: '工作流编排' } }`，挂主布局下，全员可进（团队共享制：读全员）。

## 6. 权限与错误处理

- **权限**：读全员；保存仅 owner/Admin（后端已实现，10004）。前端 `useCan()` 判断：
  非 owner 非 Admin → 保存按钮 disabled + tooltip「仅创建者或管理员可编辑」；后端 10004 兜底。
- **加载失败**：应用不存在/非 workflow 型（10005）→ 走全局错误拦截器 toast，页面留空态。
- **保存失败**：结构底线校验不过（18001，正常画布操作不会触发，防御 API 并发改）→ 全局 toast；
  网络错误沿用既有 axios 拦截器口径。保存中按钮 loading 防重复提交。
- **上次保存时间**：取 `DraftResponse.updateTime` 展示于工具栏。

## 7. 测试策略（TDD，vitest）

- **转换层纯函数单测**（核心）：toFlow/fromFlow 往返保真（含 position/sourceHandle）、
  边 id 生成与剥离、targetHandle 剥离、无 position 网格兜底、空草稿预置 start/end、
  节点 id 自增防冲突（含 `llm_1` 已存在时生成 `llm_2`）。
- **组件测试**：编辑器加载后 nodes/edges 传入画布、保存按钮调 PUT、dirty 态与离开守卫、
  非 owner 保存禁用、palette 拖出的 dataTransfer 内容。Vue Flow 在 jsdom 下若渲染不稳 →
  stub `<VueFlow>` 断言 props（测我们的逻辑，不测库）。
- **后端**：`GraphValidatorTest` 按拆层调整 + 半成品可保存/运行时 18001 的集成用例
  （`WorkflowRunFlowTest` 或 draft 专项）。
- 回归门槛：后端 `mvn verify` 全绿（surefire 报告聚合判定，不 grep BUILD SUCCESS）；
  前端 `pnpm test` / `typecheck` / `build` / `lint` 全绿。

## 8. C1 DoD（人工验收，改后端后需重启服务）

1. AppList 新建「工作流」应用 → 行按钮「编排」进画布 → 预置 start/end。
2. 拖 llm 入画布 → 连 start→llm→end → 保存 → 刷新页面：节点位置/连线原样恢复。
3. 半成品可存：只拖节点不连线、llm 不配任何字段 → 保存成功（不报 18001）。
4. Postman 用 W3a 集合存分支草稿 → 画布打开：condition 两条出边分别挂在 true/false 出口。
5. 画布保存的图（配齐 data 的既有草稿，未动配置）→ Postman 触发运行仍 succeeded（往返不破坏可运行的图）。
6. 非 owner 账号进他人画布：能看、保存按钮禁用带 tooltip。
7. 触发运行一个未配完的草稿 → 报 18001（完整校验后移到运行时生效；与 W1 既有口径一致，图非法是 HTTP 错误响应，非「HTTP 200 + run failed」）。

## 9. 留账与已知边界

- C2：六类节点配置抽屉 + 未配齐标红 + 变量引用 `{{nodeId.field}}` 的输入辅助。
- C3：运行调试面板（同步 run + 节点徽章 + 输入输出查看）。
- 运行历史列表页：推迟到发布/对外 API 轮。
- 自动保存、自动布局算法、E2E workflow 旅程：本轮均不做（上文已述）。
- 边 id 不入库：后端 `GraphEdge` 无 id 字段是既定契约，前端确定性生成即可，无需后端加字段。
- 并发编辑：PUT 全量覆盖、后写赢，无乐观锁——20-50 人内部工具 + 写权限仅 owner/Admin，接受；
  多人协作编辑是二期之外话题。
