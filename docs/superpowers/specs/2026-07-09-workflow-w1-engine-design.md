# Workflow W1：执行引擎地基 + LLM 节点 — 设计文档

> 日期：2026-07-09
> 范围：纯后端（workflow 模块从空壳到可运行）；**前端零改动**，验收用 Postman/httpie。
> 背景：知识库问答支柱（K1~K4 + 引用 + E2E）已闭环，产品两大核心场景只剩「工作流自动化」。
> workflow 是全新模块（表、代码、错误码段 18xxx 均为首铺），拆多轮推进：W1 引擎地基 → 后续轮补节点/画布/发布与对外 API。
> 一句话：把「开始 → LLM → 结束」的最小工作流跑通——graph jsonb 定义、图校验、拓扑执行、
> 变量传递、run/node_run 状态机与日志、配额入口、僵尸自愈，全部落地并可实测。

---

## 0. 决策摘要（brainstorm 拍板）

| # | 决策点 | 结论 | 理由 |
|---|---|---|---|
| 1 | 验收形态 | **纯后端引擎**，API 实测验收；画布下轮 | 引擎是所有节点的地基；Vue Flow 画布本身就是一整轮的量 |
| 2 | 节点范围 | **只带 LLM 节点**（start/end 为引擎内建） | 最小可验证：一个节点就能练全图解析/变量传递/状态机/日志；其余 5 类每轮加一个执行器类即可接入 |
| 3 | 版本/发布 | **只有草稿**；`workflow_def.version` 字段预留（恒为 1），`app.published_def_id` 不加列 | 这轮没有对外 API 消费方，发布机制做了也验不到；PG 加列安全，后补不返工 |
| 4 | 运行接口 | **同步阻塞返回**（请求内跑完，返回完整 run） | 虚拟线程下阻塞廉价；异步/排队是 scaling-path 阶段 2 的事 |
| 5 | 引擎实现 | **方案 A：进程内顺序执行器**（拓扑排序 + NodeExecutor 接口 + 内存 RunContext） | 最简单直接；否决 B 任务表异步引擎（阶段 2 方案，现在过度设计）、C 第三方引擎库（技术栈外依赖） |
| 6 | 草稿资源路径 | **单例子资源** `GET/PUT /api/v1/workflow/apps/{appId}/draft` | 「每 app 一份草稿」天然体现在路径里，前端一步直达；**拍板结论补进 api-standards.md**（单例子资源用单数名词） |
| 7 | 运行失败语义 | 节点失败 → run `failed`，**HTTP 仍 200**，失败信息在 run 资源里 | 请求被正常受理并执行完，run 真实存在、日志可查；HTTP 错误码只留给前置失败（400/404/429） |

## 1. 数据模型（Flyway V21 起，3 张新表）

全部遵守 database-standards 模板（`bigint identity` 主键、`deleted` 软删、`timestamptz`、
枚举用 `text + check`）。跨模块引用（app_id、user_id）不建 FK，只建索引（data-model 规则）。

### 1.1 `workflow_def`（画布定义，草稿）

| 列 | 类型 | 说明 |
|---|---|---|
| `app_id` | bigint not null | 弱引用 app，建索引 |
| `version` | int not null default 1 | W1 恒为 1；发布轮启用版本链 |
| `graph` | jsonb not null | 整存整取，**不建 GIN**（database-standards §2.4） |

- 部分唯一索引：`(app_id, version) where deleted = false`。
- 列表场景禁 select graph 大列（本轮草稿读取是单条 GET，不受影响；写进 Mapper 注释提醒）。

### 1.2 `workflow_run`（运行实例）

| 列 | 类型 | 说明 |
|---|---|---|
| `app_id` / `def_id` / `user_id` | bigint not null | 弱引用；user_id=触发人 |
| `status` | text check in ('running','succeeded','failed') | 同步执行无 pending；未来加值只改 check |
| `inputs` / `outputs` | jsonb | 触发入参 / end 节点拼出的最终输出 |
| `error_message` | text | 失败原因（面向用户可读） |
| `elapsed_ms` | bigint | 总耗时 |

- 索引：`(status, create_time)`（database-standards §2.2 点名的标配，阶段 2 任务抢占直接受益）、
  `(app_id, create_time)`（按应用查历史）。
- autovacuum 调密（database-standards §8：状态机反复 UPDATE 的表）：
  `autovacuum_vacuum_scale_factor=0.05`、`autovacuum_analyze_scale_factor=0.02`，写进 V21+ 迁移。

### 1.3 `workflow_node_run`（节点执行日志，**第一天按月分区**）

| 列 | 类型 | 说明 |
|---|---|---|
| `run_id` | bigint not null | 建索引 |
| `node_id` | text not null | graph 里的节点 id（如 `llm_1`） |
| `node_type` | text not null | `start` / `llm` / `end` … |
| `status` | text check in ('running','succeeded','failed') | — |
| `inputs` / `outputs` | jsonb | 变量替换后的实际输入 / 节点输出 |
| `error_message` | text | — |
| `elapsed_ms` | bigint | — |

- 按 `create_time` RANGE 按月分区，主键 `(id, create_time)`；Flyway 初建 6 个月分区。
  **照抄 `llm_call_log`（V12）的既有模式**；workflow 模块自建每月定时任务建下月分区
  （复用 usage `PartitionMaintainer` 的写法，不跨模块共用类）。
- 日志表只增不改（终态一次 UPDATE 收尾），无需 autovacuum 调参。

### 1.4 graph jsonb 格式（对齐 Vue Flow，画布轮零转换）

```json
{
  "nodes": [
    { "id": "start", "type": "start", "data": { "inputs": [ { "name": "query", "required": true } ] } },
    { "id": "llm_1", "type": "llm",   "data": { "modelId": "3", "systemPrompt": "你是客服",
                                                 "userPrompt": "{{start.query}}" } },
    { "id": "end",   "type": "end",   "data": { "outputs": [ { "name": "answer", "value": "{{llm_1.text}}" } ] } }
  ],
  "edges": [ { "source": "start", "target": "llm_1" }, { "source": "llm_1", "target": "end" } ]
}
```

- 变量引用语法：`{{节点id.字段}}`，纯字符串替换；LLM 节点的输出字段固定叫 `text`。
- `modelId` 是站内自增 id 的字符串形态（Long→string 全局约定）。
- edges 结构预留 `sourceHandle`（条件分支轮用于区分 true/false 出口），W1 不解析。
- 节点 `data` 内字段 camelCase，未知字段忽略（与全局反序列化约定一致）。

## 2. 模块结构与引擎

```
server/src/main/java/com/hify/workflow/
├── api/                    # W1 无消费方，不提供 Facade（保留 package-info）
├── controller/WorkflowController.java
├── service/WorkflowDraftService.java        # 草稿读写 + 保存时图校验 + 权限（owner 或 Admin）
├── service/WorkflowRunService.java          # 触发编排：配额→读 def→校验→执行→落库
├── service/ZombieRunResetter.java           # 启动自愈：running → failed("服务重启中断")
├── service/engine/WorkflowEngine.java       # 拓扑排序 + 逐节点驱动 + RunContext 维护
├── service/engine/GraphValidator.java       # 图校验（保存/运行共用）
├── service/engine/RunContext.java           # 内存 Map<节点id, 输出>；变量替换在此
├── service/engine/NodeExecutor.java         # 接口：NodeResult execute(NodeContext)
├── service/engine/LlmNodeExecutor.java      # W1 唯一实现
├── entity/ mapper/ dto/ constant/ config/   # 按 code-organization 分层
```

- **NodeExecutor 是唯一抽象点**：按 `type` 注册（Map<String, NodeExecutor>，Spring 注入 List 构建）。
  后续 5 类节点各加一个实现类接入，不改引擎。start/end 是引擎内建逻辑（取入参/拼出参），不算执行器。
- 执行链路（一次 POST 触发）：
  `checkQuota（UsageFacade，入口查一次）→ 读草稿 def → GraphValidator → 拓扑排序 →
   插 workflow_run(running) → 逐节点：插 node_run(running) → 执行 → 更新终态
   → 更新 run 终态 + outputs → 返回`
- **LlmNodeExecutor**：`ProviderFacade.getChatClient(modelId)`（自带信号量/三层超时/熔断；
  同步触发用户在等，走交互池）→ call → 输出入 RunContext → 发 `TokenUsedEvent`（common 事件，
  usage 监听计量，与 conversation 同模式）。**节点重试固定 0**（llm-resilience：防重试风暴）。
- **事务纪律（红线）**：run/node_run 落库全是独立短事务；节点执行（LLM IO）永远在 `@Transactional` 之外。
- **僵尸自愈**：同步执行 ⇒ 启动时仍为 `running` 的 run 必是上次重启遗留；
  ApplicationReady 监听器统一置 `failed`（连带其 running 的 node_run），单条 update，幂等。
- 模块依赖（白名单内）：app（校验 app 存在且 type=workflow、owner 判权）、provider、usage、common。
  conversation/knowledge/tool 在白名单里但 W1 不用。

## 3. API（成员路由族 `/api/v1/workflow/**`，JWT，Result 信封）

| # | 接口 | 说明 | 权限 |
|---|---|---|---|
| 1 | `GET /api/v1/workflow/apps/{appId}/draft` | 读草稿（无草稿返回 `data: null`，200） | 全员 |
| 2 | `PUT /api/v1/workflow/apps/{appId}/draft` | 全量保存草稿（body `{"graph": {...}}`），保存前过 GraphValidator | owner 或 Admin |
| 3 | `POST /api/v1/workflow/apps/{appId}/runs` | 触发一次运行，body `{"inputs": {...}}`；同步返回完整 run（含 nodeRuns） | 全员 |
| 4 | `GET /api/v1/workflow/apps/{appId}/runs?cursor=&limit=` | 运行历史，**游标分页**（create_time+id keyset，不返回 total）；只返回摘要列，**不带 inputs/outputs 大列** | 全员 |
| 5 | `GET /api/v1/workflow/runs/{id}` | 运行详情 + 节点日志数组（run 有自己的 id，升顶级资源） | 全员 |

- 触发前置检查固定顺序：app 存在且 type=workflow（10005/404）→ 草稿存在（10005/404）
  → 配额 `UsageFacade.checkQuota`（14001/429）→ 图校验（18001/400）→ start 必填入参齐全（10001/400）。
- run 响应体（Long 全为 string；枚举小写）：
  `{ id, status, inputs, outputs, errorMessage, elapsedMs, createTime, nodeRuns: [ { nodeId, nodeType, status, inputs, outputs, errorMessage, elapsedMs } ] }`
- **错误码**：本轮仅新增 `18001/400` 工作流图结构非法（message 说明具体规则）。
  其余复用：10001 参数、10004 权限、10005 不存在、14001 配额。
- **规范修订**（拍板 #6）：api-standards.md §2.1 追加一条——
  「每个父资源至多一个的**单例子资源**，允许单数名词路径（如 `/apps/{id}/draft`），GET 读 / PUT 全量写，不用集合形态」。

## 4. 边界与错误处理

- **GraphValidator 规则**（不合法→18001，保存与运行共用）：
  恰好一个 start、一个 end；start→end 连通；无环（拓扑排序失败即环）；无孤儿节点；
  节点类型已注册；节点自身配置齐全（llm 节点必须含 `modelId` 与 `userPrompt`）；
  `{{x.y}}` 引用的节点存在且是**上游**节点；节点数 ≤ 上限（默认 50，外化配置）。
- **模型可用性不在保存时校验**（模型可能事后被停用，存前查一次没有意义）：
  运行时 `getChatClient` 拿不到可用模型 → 该节点 failed → run failed，错误原因写明「模型不可用」。
- **超时**：W1 不做引擎级节点超时；LLM 节点耗时上限由 ChatClient 三层超时兜底。
  引擎级节点超时预算等 HTTP/代码节点轮（出现不受 provider 韧性层保护的外部调用时）引入。
- **变量替换失败**（运行时引用字段缺失等）→ 该节点 failed → run failed，不重试。
- 同步接口耗时可能达分钟级（多 LLM 节点串联）：W1 客户端是 Postman 不受影响；
  画布轮前端接入时必须遵守「前端超时 ≥ 后端预算」（记忆 conversation-sync-llm-timeout-gotcha 的教训）。

## 5. 测试与验收 DoD

TDD（先写失败测试），三层：

1. **纯单测**（主力）：GraphValidator 逐条规则、拓扑排序、RunContext 变量替换、
   LlmNodeExecutor（mock ChatClient + 断言 TokenUsedEvent 发布）、
   引擎编排（传值正确；中途节点失败 → 后续不执行、run failed）。
2. **Testcontainers 集成**（K4 环境）：保存草稿 → 触发（mock ProviderFacade）→
   断言 run/node_run（含分区表）落库正确；僵尸自愈（预置 running → 监听器 → failed）。
3. **既有防线**：ModularityTests / ArchUnit 全绿（workflow 依赖仅白名单）。

**验收 DoD**（真实环境实测，反做假）：本地起服务，Postman/httpie 跑通
「建 workflow 应用 → PUT 草稿 → POST 触发（真模型或本地桩）→ succeeded + outputs →
查历史与节点日志」；失败路径各验一条：图非法 18001、节点失败返回 failed run（HTTP 200）。

## 6. 交付物与后续轮次

**W1 交付**：Flyway V21+（3 张表+分区+autovacuum 参数）、workflow 模块后端全量、测试三层、
api-standards.md 单例子资源条款、docs/self-check.md 自检记录。前端零改动。

**后续轮次展望**（每轮独立 brainstorm，此处只记方向）：
知识检索节点（RAG 工作流成型）→ 条件分支/HTTP 节点 → Vue Flow 画布 →
发布机制 + 对外 API（`/v1/apps/{appKey}/workflows/run`，app 加 published_def_id）→
代码执行节点（依赖 sandbox 容器）→ Agent 节点（依赖 tool 模块）。
