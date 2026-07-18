# Hify 核心数据模型

> 表与代码模块一一对应——数据层是模块边界的投影。只列表与关系，字段在写 Flyway 脚本时展开。
> ER 图：`er-diagram.svg`（源文件 `er-diagram.dot`，重新生成：
> `npx -y @hpcc-js/wasm-graphviz-cli -T svg er-diagram.dot > er-diagram.svg`）。

## 1. 表清单（按模块，共 20 张）

| 模块 | 表 | 说明 |
|---|---|---|
| identity | `sys_user` | Admin/Member 用角色字段区分，不单独建角色表 |
| provider | `model_provider` | 供应商实例（协议、地址、加密 Key、韧性配置，见 llm-resilience.md 字段表） |
| | `ai_model` | 具体模型，区分 chat / embedding 类型；V26 加 `input_price`/`output_price`（元/百万 token，可空） |
| app | `app` | 应用，类型字段区分对话型/工作流型；Agent 配置是对话型 app 的 jsonb 配置 |
| | `app_api_key` | 应用对外 API Key |
| | `app_dataset_rel` | 应用 ↔ 知识库 多对多 |
| | `app_tool_rel` | 应用 ↔ 工具 多对多；T2 已落地 V24：`app_id/tool_id` + BaseEntity 字段，tool_id 跨模块弱引用，Agent 应用勾选启用哪些工具 |
| conversation | `conversation` | 会话 |
| | `message` | 消息；`sources jsonb` 存引用来源快照数组 `[{chunkId,documentId,documentName,score,preview}]`，随消息落库/删会话级联，未命中为 `[]`；Agent 工具调用轨迹存本表 jsonb，不单独建表 |
| knowledge | `dataset` | 知识库 |
| | `kb_document` | 文档元数据 + 原始文件（bytea 列；一库通吃，随 pg_dump 一份备齐） |
| | `kb_chunk` | 分段，embedding 向量列在本表（pgvector，HNSW 索引） |
| workflow | `workflow_def` | 画布定义（jsonb graph），带版本号，(app_id, version) 唯一 |
| | `workflow_run` | 运行实例（状态机；scaling-path.md 阶段 2 的 SKIP LOCKED 任务表即本表） |
| | `workflow_node_run` | 节点级执行日志 |
| tool | `tool` | 统一注册表；T1 已落地 V23：`name/description/source/enabled/spec/owner_id` + BaseEntity 字段，`source` 区分 builtin / openapi / mcp；内置工具 `spec/owner_id` 为空。`spec jsonb` 经 `kind` 字段承载两种形状（T4a 引入 `ToolSpec` 多态）：openapi 存 `baseUrl/operations/rawSpec`，mcp 存 `url/transport/tools 快照/discoveredAt`；两者的鉴权头都只存密文。V25 给存量 openapi 行补了 `kind` 标记 |
| usage | `llm_call_log` | 每次模型调用流水（监听 TokenUsedEvent 落库）；V26 加 `source` 列（conversation/workflow，历史 null） |
| | `daily_usage` | 用户×应用×天 聚合；配额检查只查本表，不扫流水 |
| | `usage_stat_daily` | 看板聚合：日×用户×应用×模型，UPSERT 累加；监听 TokenUsedEvent 与流水同事务双写 |
| 系统 | `system_setting` | admin 系统设置，KV |

## 2. 关系

```
model_provider 1──N ai_model
                        △ 弱引用
app ────────────────────┘ （对话型绑模型；workflow 的 LLM 节点在 graph jsonb 里引 model_id）
 ├── 1──N app_api_key
 ├── N──M dataset      （经 app_dataset_rel）
 ├── N──M tool         （经 app_tool_rel）
 └── 1──N workflow_def （版本链；app 另持 published_def_id 指向已发布版）
                └── 1──N workflow_run ── 1──N workflow_node_run

sys_user 1──N conversation N──1 app
              └── 1──N message

dataset 1──N kb_document 1──N kb_chunk（含向量列）

llm_call_log / daily_usage / usage_stat_daily ──▷ user_id、app_id、model_id 全部弱引用，无外键
```

## 3. 贯穿性规则

1. **跨模块只存 id，不建数据库外键。** 数据库级 FK 会把代码层守住的模块边界在存储层重新
   焊死（级联删除穿透模块；按角色拆部署/拆库时成为硬障碍）。引用完整性由目标模块的
   Facade 在写入时校验。**模块内部** FK 随意建（如 `kb_chunk → kb_document`），
   享受级联删除便利。ER 图中实线 = 模块内 FK，虚线 = 跨模块弱引用。
2. **日志类表（`llm_call_log`、`workflow_node_run`）从第一天按月分区。**
   只增不改的高水位表，几千人阶段年增千万行级；分区让清理旧数据 = drop partition。
   这是唯一需要提前做的容量设计，其余表到几千人也只是几十万行。
3. **资源可见性：团队共享制。** 共享资源 `app`、`dataset`、`tool` 带 `owner_id`
   （弱引用 `sys_user`）：全员可见、可使用，**修改/删除/发布仅 owner 与 Admin**；
   从属对象（`workflow_def`、`app_api_key` 等）随父资源判定。
   `conversation` / `message` 是个人数据，仅本人可见（Admin 控制台全局视图除外）。
   接口层判定规则见 api-standards.md 第 6 节。

## 4. 刻意不存在的表

| 没有的表 | 原因 |
|---|---|
| agent | Agent 是对话型 app 的配置形态，不是独立实体（CLAUDE.md"仅两种应用类型"的推论） |
| role / permission | 两级角色一个字段够用，SSO/RBAC 在"不做什么"清单里 |
| 消息队列表 | 阶段 2 的任务表职责由 workflow_run 状态机兼任 |
| 向量表 | 向量不是独立实体，是 kb_chunk 的一列——这是选 pgvector 的核心收益 |
| 文件表 / MinIO | 原始文件是 kb_document 的 bytea 列——与向量同理，文件不是独立实体；备份与多副本共享随库一并解决 |
| mcp_server | **T1 时曾规划、T4a 拍板废弃**。一个 MCP 服务器 = `tool` 表 1 行（`source=mcp`），连接配置与工具清单快照存 `spec jsonb`，注册表读时展开成 N 个 ToolCallback（Model D，与 OpenAPI 工具同构）。若拆成 `mcp_server 1──N tool`，就必须实现「远端工具增删改 → 本地行同步」，是纯复杂度来源；Model D 整条快照替换即可，天然免疫。代价是 per-app 只能勾整个服务器、不能勾单个工具——与 OpenAPI 工具现状一致，已接受 |
