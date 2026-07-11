# CLAUDE.md

> **写代码前必读**：`docs/architecture/code-organization.md`（模块划分、分层、跨模块规则）、
> `docs/architecture/database-standards.md`（建表与 SQL 规范）
> 和 `docs/architecture/api-standards.md`（接口与错误码规范）。与文档冲突的代码视为错误。
> 写前端（`web/`）代码前必读 `docs/architecture/frontend-standards.md`（前端技术栈与规范）。
> 全部设计文档见下方「架构文档索引」。

## 行为指令

### 写代码时
- 每个功能用最简单直接的方式实现
- **设计/新增任何 API（路由、HTTP 方法、请求/响应结构、错误码）前，必须现场重读 `docs/architecture/api-standards.md` 并逐条核对，不得凭记忆**。常踩的坑：admin 路由是 `/api/v1/admin/<module>/**`（带模块段）、一期**不用 PATCH**（单字段开关用动作子资源 POST，如 `/enable`）、Long 一律序列化为字符串、错误码优先复用通用段。
- 不引入不必要的设计模式，除非我明确要求（架构文档已规定的除外：Facade、事件机制等必须遵守）
- 不做过度抽象
- 不引入技术栈以外的依赖，需要时先问我
- 所有外部调用必须有超时设置，超时值外化为配置
- 配置项外化到 application.yml，不硬编码；敏感配置（密钥/密码）走 `.env` 环境变量引用，不在 application.yml 写明文

### 改代码时
- 先理解相关模块的设计意图（读对应架构文档和既有代码，再动手）
- 不要为了新功能破坏已有接口契约（已发布的错误码与对外 API 只增不改，见 api-standards.md）
- 数据库变更只通过新增 Flyway 脚本实现，禁止修改已合并的旧迁移脚本
- 改完确保已有测试通过（含 ModularityTests / ArchUnit 模块边界测试）

### 不确定时
- 架构选择给我 2-3 个方案对比，我来拍板
- 规范没覆盖的情况，先问我，不要自己编规矩；拍板后的结论要补进对应架构文档，避免同一问题问两次

## 项目概述

### 产品定位
Hify 是 [Dify](https://dify.ai) 的简化版 AI Agent 平台，面向单个团队（20-50人）内部使用，由一人开发维护，本地化部署。目标是用最小的工程量覆盖"知识库问答"和"工作流自动化"两大核心场景，而非打造通用的多租户SaaS平台。

### 做什么
**应用类型**（仅2种，覆盖Dify的多种应用形态）
- 对话应用：聊天机器人 / 知识库问答 / Agent，支持多轮记忆
- 工作流应用：自动化任务编排，支持API触发

**Workflow引擎**：可视化画布，支持6类节点 —— LLM、知识检索、HTTP请求、代码执行(Python沙箱)、条件分支、Agent调用

**RAG**：文档上传 → 自动分段 → 向量化 → 向量检索，向量、文档元数据与原始文件（bytea）同存 PostgreSQL（pgvector），删除/更新有事务保证

**模型管理**：支持 OpenAI兼容协议（覆盖通义千问/Gemini/Ollama等）+ Anthropic协议两种通用接入方式，云端API为主

**Agent能力**：基于 Function Calling 的工具调用；内置 HTTP请求 / 代码执行 两个工具；支持 OpenAPI 规范注册自定义工具 与 MCP 工具接入（统一进 tool 模块的工具注册表，对 Agent 透明）

**管理控制台**（Admin专属）：模型供应商配置、用户与权限管理、应用全局视图、用量与成本看板、知识库全局视图、自定义工具注册表、系统设置（admin 接口分散在各模块，无独立 admin 模块）

**权限**：Admin / Member 两级角色，Admin手动创建账号；资源团队共享制——全员可见可用，修改/删除仅 owner 与 Admin，会话仅本人

**运维支撑**：调用日志与Token用量统计、应用对外API+Key

### 不做什么
- 插件市场 / 插件化运行时架构
- 内置工具市场（50+预置工具）
- 模型负载均衡与精细配额管理
- 外部知识库API对接
- 多工作空间 / 多租户隔离
- SSO（管理员手动建账号即可）
- Kubernetes / Terraform 等高级部署方式（一期；演进见 scaling-path.md）
- 多语言界面（i18n）
- 第三方可观测性平台集成（Langfuse等）
- 标注与反馈系统
- 本地模型推理（暂不使用 Ollama 等本地推理）

> 二期可考虑：混合检索+Rerank（PG 的 tsvector 全文检索 + pgvector，地基已就绪）、DSL导入导出、Embedding结果缓存、多副本扩容时引入 Redis 作共享缓存、工作流注册为 Agent 工具（经 tool 模块绕行，禁止 conversation→workflow 直接依赖）、多模态 embedding/图文检索（演进路径见 llm-resilience.md 第 6.2 节，触发条件=图文检索成为产品需求）

### 技术栈
**后端**：Spring Boot 3.x（Java 21 虚拟线程）+ Spring AI（多模型ChatClient/Tool Calling/EmbeddingModel；RAG 检索为手写 SQL+手动拼提示词，不用 VectorStore/Advisors，见 database-standards §2.1）+ Spring Modulith（模块边界校验）+ MyBatis-Plus + PostgreSQL 16 + pgvector（业务数据与向量一库通吃）+ Caffeine 进程内缓存（一期不引入 Redis）+ Resilience4j（LLM 调用韧性）

**前端**：Vue 3 + TypeScript（Composition API + `<script setup>`）+ Vite + pnpm（Node v24）+ Element Plus（管理后台/表单）+ Vue Flow（Workflow画布）+ Pinia（状态）+ Vue Router + axios + 原生 SCSS（详见 frontend-standards.md）

**容器化**：Docker + Docker Compose（单机 4 容器：nginx / hify-server / sandbox / postgres）

### 仓库布局
```
hify/
├── server/              # Spring Boot 模块化单体（包结构见 code-organization.md）
├── web/                 # Vue 3 前端（独立 pnpm 工程，同仓库；构建产物进 nginx 镜像）
├── deploy/              # nginx 配置、.env.example（真实 .env 不入库）
├── scripts/             # 手跑工具脚本，不进 CI（retrieval-eval/ 检索阈值评估集）
├── docker-compose.yml
└── docs/architecture/   # 设计文档（见下索引）
```

## 架构文档索引（docs/architecture/）

| 文档 | 内容 | 何时读 |
|---|---|---|
| `code-organization.md` | 10 个模块及依赖白名单、模块内分层、跨模块调用 7 条规则、Modulith/ArchUnit 强制校验 | **写任何代码之前** |
| `coding-standards.md` | Java 编码 20 条（命名/异常/日志/并发，按虚拟线程环境修正） | **写任何 Java 代码之前** |
| `database-standards.md` | 建表与字段约定、索引原则、pgvector 专项、大表分级、分页、连接池/超时、事务与锁、DDL 迁移安全、参数基线 | **建表/写 SQL 之前** |
| `api-standards.md` | 路由三族、RESTful 资源设计、Result 统一响应、空值/序列化、错误码段分配、SSE | **写 Controller/DTO 之前** |
| `frontend-standards.md` | 前端技术栈、目录结构、API 层封装、命名/组件、Pinia、路由权限、样式、环境变量、代码质量 | **写任何前端（web/）代码之前** |
| `testing-standards.md` | 测试质量判断：三类病（冗余/遗漏/假测试）、AI 测试 7 坑、覆盖率与故意搞错用法、体检流程 | 写测试或审查 AI 生成的测试之前 |
| `data-model.md` | 18 张表清单与关系、跨模块不建外键、刻意不存在的表 | 涉及新表/改表结构 |
| `llm-resilience.md` | LLM 调用的线程/超时/重试/熔断方案，provider 模块 ResilientChatModel 装饰器 | 动 provider 模块或任何外部调用 |
| `deployment.md` | 单机 4 容器架构、组件职责、五条请求流、运维矩阵、二期触发表 | 动部署/运维相关 |
| `scaling-path.md` | 50→数千人四阶段演进：触发条件/改什么/不改什么；全程不变清单 | 评估任何"为了扩展性"的提议之前 |
| `module-dependencies.svg`(.dot) | 模块依赖图（实线=代码依赖，虚线=事件流） | 配合 code-organization.md |
| `er-diagram.svg`(.dot) | ER 图（按模块分簇；实线=模块内FK，虚线=跨模块弱引用） | 配合 data-model.md |

图的重新生成：`npx -y @hpcc-js/wasm-graphviz-cli -T svg <name>.dot > <name>.svg`

## 部署与运维要点（详见 deployment.md / llm-resilience.md / scaling-path.md）

- **规模**：20-50人并发，单机 Docker Compose；QPS非瓶颈，真正约束是云端LLM供应商速率限制（RPM/TPM）和Agent级联调用的并发倍数
- **限流**：按供应商并发信号量（交互池/批量池分离）；按用户/应用每日Token配额，防异常Agent循环刷爆账单；配额只在 conversation/workflow 入口检查
- **PostgreSQL**：业务+向量同库，pg_dump 每日备份（保留14天，每月恢复演练）；Flyway 管理迁移；日志表按月分区
- **代码执行沙箱**：独立容器（不可信代码绝不进 server），网络隔离、CPU/内存硬限制 + 执行超时
- **安全**：敏感配置走 `.env` + `env_file` 不入库不入镜像（供应商 API Key 在库中加密，主密钥在 .env）；前置 Nginx 做 HTTPS；对外 API 不暴露自增主键；HTTP 节点/自定义工具/MCP 出站统一过 SSRF 防护（禁内网与元数据地址，见 deployment.md 第 5 节）
- **可用性**：Actuator + compose healthcheck/restart；优雅关闭等待 Workflow 收尾，启动时自愈重置非终态僵尸记录；`@Transactional` 内禁止 LLM/外部 IO（防连接池耗尽拖垮全站）
- **本地模型**：当前不使用，未来启用需独立规划GPU资源，与主服务分机部署
