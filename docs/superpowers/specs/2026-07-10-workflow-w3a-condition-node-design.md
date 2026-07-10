# Workflow W3a：条件分支节点（设计）

> 2026-07-10 brainstorm 定稿。W3 原计划「条件分支 + HTTP 节点」，经评估拆为两轮：
> W3a 条件分支（本篇，动引擎控制流），W3b HTTP 节点（infra 出站客户端 + SSRF，另行 brainstorm）。
> 基于 W1 引擎（specs/2026-07-09-workflow-w1-engine-design.md）与 W2 知识检索节点。

## 1. 范围与目标

**做**：
- `NodeType` 新增 `CONDITION("condition")` + `ConditionNodeExecutor`（单条比较求值）
- 引擎支持分支：拓扑序遍历 + 活边判定，未选中路径节点记 `skipped`
- `GraphEdge` 加 `sourceHandle` 字段（分支出口标记，本轮功能必需）
- `GraphNode` 加 `position` 字段（可空，引擎不读，防 jsonb 经 record 往返丢画布坐标）——**还清 W1 留账，画布轮零前置**
- Flyway V22：`workflow_node_run.status` check 约束加 `skipped` 值
- `GraphValidator` 新增 condition 相关校验；测试三层 + Postman 验收集合

**不做**（YAGNI，均有预留不返工）：
- AND/OR 条件组、多路 elif（串联二路节点可覆盖；data 结构升级时加字段即可）
- 变量聚合节点（「被跳过引用渲染空串」已覆盖汇合场景）
- 并行执行（scaling-path 触发条件未到；活边判定规则与未来队列驱动兼容）
- HTTP 节点（W3b）

## 2. 节点与边定义

**condition 节点**（graph jsonb）：

```json
{"id": "if_1", "type": "condition",
 "data": {"left": "{{kb.count}}", "operator": ">", "right": "0"}}
```

| 字段 | 约束 |
|---|---|
| `left` / `right` | 必填字符串，支持 `{{nodeId.field}}` 引用，运行时渲染 |
| `operator` | 必填，白名单：`==` `!=` `>` `>=` `<` `<=` `contains` `notContains` |

**求值语义**（渲染后两边都是字符串）：
- `==` / `!=`：字符串相等比较
- `>` `>=` `<` `<=`：两边必须可解析为数字（BigDecimal），任一边解析失败 → **节点失败**
  （明确报错优于静默字典序比较出诡异结果）
- `contains` / `notContains`：字符串包含
- 输出 `{result: true}` 或 `{result: false}`；inputs 落 `{left: 渲染后, operator, right: 渲染后}`
  ——排障时一眼看到实际比较的值

**边**（GraphEdge 加可空字段 `sourceHandle`）：

```json
{"source": "if_1", "target": "llm_a", "sourceHandle": "true"},
{"source": "if_1", "target": "llm_b", "sourceHandle": "false"}
```

**GraphNode 加可空字段 `position`**（`Map<String, Object>`，存 `{x, y}`；引擎与校验不读，
只保证 jsonb 往返不丢——W1 教训：record 反序列化会静默吃掉未知字段）。

**GraphValidator 新增规则**（保持纯函数，保存与运行共用）：
1. condition 节点 data 必填 `left`/`operator`/`right`，operator 在白名单内
2. condition 节点必须**恰好两条出边**，`sourceHandle` 恰为 `"true"` 与 `"false"` 各一条
3. 非 condition 节点的出边不得带 `sourceHandle`（防呆）
4. 既有校验（唯一 start/end、连通性、无环、变量引用拓扑序在前、类型字段校验）原样沿用

## 3. 执行模型与跳过语义

**活边判定**（引擎唯一新逻辑，用户拍板）：保留 W1 拓扑序遍历骨架，
执行前先判 `节点执行 ⇔ 存在活的入边`：

```
alive(start) = true
alive(n)     = ∃ 前驱 p：alive(p) ∧ 边 p→n 被选中
边被选中：p 非 condition ⇒ 恒选中；p 是 condition ⇒ sourceHandle 与求值结果一致
```

- 拓扑序保证判定时所有前驱状态已定，汇合点（多前驱、至少一条活入边）自然照常执行，零特殊处理
- 判定为不活 → `store.createSkippedNodeRun(...)` 落一条 `status=skipped` 记录（用户拍板：
  排障可见、画布轮可直接灰显），不执行、不进 RunContext
- 被跳过的 condition 节点不求值，其两条出边均不选中 → 跳过沿路径自然传播

**接口变化**（模块内部，无对外影响）：`WorkflowEngine.execute` 增加 edges 参数
（现签名只收拓扑序节点列表，活边判定需要边）；调用方 `WorkflowRunService` 同步修改。

**RunContext 跳过语义**（用户拍板：渲染空串）：
- 新增 skipped 标记（如 `markSkipped(nodeId)`）
- `render` 遇到**被跳过节点**的字段引用 → 替换为空字符串 `""`
- 节点**执行过**但引用的字段不存在 → 仍抛异常（真 bug 不能吞）
- 安全性论证：写错节点 id 保存时被 validator 拦截（引用必须存在且拓扑在前）；
  失败即停保证不存在「引用了失败节点」的场景——运行时「无输出」⇔「被跳过」，语义无歧义

**V22 迁移**：`workflow_node_run.status` check 约束放宽为
`('running','succeeded','failed','skipped')`（分区父表上 alter，子分区自动继承）。
V21 及更早迁移不动。skipped 记录的 inputs/outputs/error 均为 null，elapsed 为 0。

**不变量**：失败即停、节点重试 0、引擎类禁 `@Transactional`、落库走 store 短事务、
`RunResponse`/游标历史等对外契约只增不改（nodeRuns 的 status 新增 `skipped` 值属于「增」，
与 V21 建表注释预留的演进方式一致——「届时 check 加值即可」；前端当前未消费该字段，无兼容风险）。

## 4. 测试与 DoD

**单测**：
- 条件求值：8 个运算符正反例、数字运算符遇非数字 → NodeExecutionException、
  左右值变量渲染（含引用被跳过节点 → 空串参与比较）
- 活边判定：直线图全执行（W1 回归）、真/假两方向、汇合点单活边执行、
  连锁跳过（被跳过的 condition 后整条路 skipped）
- validator：新 3 条规则正反例（缺字段/非法 operator/出边数与 handle 不符/普通节点带 handle）
- jsonb 往返：GraphNode.position 与 GraphEdge.sourceHandle 存取不丢（扩展既有 RoundtripTest）

**集成测试**（Testcontainers，扩展 WorkflowRunFlowTest）：
「start → kb(桩) → condition(count>0) → llm_a / llm_b → end」，
命中与未命中两个方向各一条：断言走对路、另一路节点落 skipped 记录、汇合 end 输出正确。

**DoD 手动验收**（Postman 集合 `workflow-w3a.postman_collection.json`，
集合说明第一条写明**验收前必须重启服务**——W2 实测踩过旧进程坑）：
1. 真实分支工作流触发两个方向（改触发输入让 kb 命中/不命中），各 succeeded 且答案来自正确分支，
   运行详情可见另一分支节点 skipped
2. 失败路径：数字运算符左值渲染出非数字 → HTTP 200 但 run=failed，node_run 可见实际比较值

**既有防线**：ModularityTests / ArchUnit / 全量绿。

## 5. 交付物与后续

- workflow 模块：NodeType/ConditionNodeExecutor/引擎活边判定/RunContext 跳过语义/validator 规则
- V22 迁移、GraphNode.position + GraphEdge.sourceHandle、测试三层、Postman 集合、self-check 入档
- **工作量预估**：6-8 任务、约 800-1000 行（W2 两倍）

**后续轮次**：W3b HTTP 节点（infra 出站客户端 + DNS 解析后 IP 校验的 SSRF 防护，
deployment.md §5 硬性要求，独立 brainstorm）→ Vue Flow 画布（position 已就绪）→
发布机制 + 对外 API → 代码执行节点 → Agent 节点。
