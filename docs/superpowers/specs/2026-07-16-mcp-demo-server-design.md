# mcp-demo：自建第一个 MCP server（TypeScript）设计

> 日期：2026-07-16
> 前置：② Agent/tool 模块 T4a/T4b（MCP 客户端接入 + 白名单 + admin 页）已收官。
> 目标读者：实现计划（plans/）与执行者。

## 1. 目标与范围

**目标**：用 TypeScript 自建一个最小但完整的 MCP server（练手性质），跑通
「写 server → Hify admin 页注册 → 工具发现 → 应用勾选 → 聊天中 Agent 真调用」全链路，
把 MCP 协议的 server 侧学透。

**做**：
- 独立 pnpm 工程 `mcp-demo/`（hify 仓库内新顶层目录，与 `server/`、`web/` 平级、零代码依赖）
- 2 个无外部依赖的工具：`get_current_time`、`roll_dice`
- Bearer token 鉴权（练一遍 Hify T4b 鉴权头密文存储链路）
- vitest 单测 + 协议集成测（TDD，先写失败测试）

**不做**（YAGNI）：
- 数据库/外部 API 访问、resources/prompts 能力、stdio 传输
- 会话管理（无状态模式）、重试/熔断/日志框架
- Docker 化与 compose 集成（跑通后有需要再说）

## 2. 技术选型（已拍板）

| 项 | 选择 | 理由 |
|---|---|---|
| HTTP 层 | 官方 `@modelcontextprotocol/sdk` + Express + **无状态模式** | 官方标准搭法，示例文档最全；Hify 客户端用完即关（`McpToolDiscoverer` 中 `try (client)`），server 记会话是纯负担 |
| 传输 | Streamable HTTP（`POST /mcp`） | Hify `McpClientFactory` 默认传输；stdio Hify 连不上 |
| 参数校验 | zod | SDK 官方搭档，工具参数 schema 由它生成 |
| 运行方式 | `tsx` 直跑 TS 源码 | 练手项目不折腾构建产物 |
| 测试 | vitest + TDD | 沿用 web 端既有习惯 |
| 端口 | 3100 | 避开 5173(web)/8080(server) |
| 包管理 | pnpm（Node v24） | 与 web/ 一致 |

被否方案：B（SDK+原生 http）——手写解析路由、与官方示例对不上；C（fastmcp 框架）——协议细节被藏住，与练手目标相反，且引入不必要依赖。

## 3. 目录结构

```
mcp-demo/
├── package.json          # 运行依赖：@modelcontextprotocol/sdk、express、zod
│                         # 开发依赖：typescript、tsx、vitest、@types/express、@types/node
├── tsconfig.json
├── vitest.config.ts
├── README.md             # 启动方法 + Hify 注册步骤（含白名单配置）
└── src/
    ├── index.ts          # 入口：读环境变量 → app.listen(3100)，≈10 行
    ├── app.ts            # Express app 工厂：鉴权中间件 + POST /mcp 路由（不 listen，可测）
    ├── server.ts         # createMcpServer()：注册两个工具的 McpServer 工厂
    └── tools/
        ├── current-time.ts   # 工具 = 名字 + 描述 + zod 参数 + 纯函数处理器
        └── roll-dice.ts
```

设计意图：
- `index.ts`（占端口）与 `app.ts`（路由形状）分离 → 测试不占端口。
- 无状态模式 = 每个 `POST /mcp` 请求新建 McpServer + transport，用完即弃。

测试放 `src/**/__tests__/`（沿用 web 端约定）。

## 4. 工具定义

| 工具 | 参数（zod） | 返回 | Agent 调用动机 |
|---|---|---|---|
| `get_current_time` | `timezone`：IANA 时区名（如 `Asia/Shanghai`），可选，默认 `Asia/Shanghai` | 该时区当前时间字符串 | LLM 不知道现在几点 |
| `roll_dice` | `sides`：面数 int 2–1000 默认 6；`count`：次数 int 1–10 默认 1 | 各次点数 + 总和 | LLM 生成不了真随机数 |

- 描述用中文且写清「何时该用我」——它进 `tools/list` 快照（`McpToolSpec.McpTool.description`），是给模型看的调用依据。
- 两者均为毫秒级纯函数，天然满足 Hify 侧超时预算（连接 5s / 初始化 10s / 请求 30s）。
- 时区实现用 `Intl.DateTimeFormat`（Node 内置），非法时区名会抛错 → 走工具执行错误路径。

## 5. 鉴权与数据流

**鉴权**：Express 中间件校验 `Authorization: Bearer <token>`；token 从环境变量
`MCP_DEMO_TOKEN` 读，默认 `hify-demo-token`（练手够用）。缺失或不匹配 → **HTTP 401**，
不进 MCP 协议层。

```
注册/刷新： hify-server ──initialize + tools/list──▶ mcp-demo   （工具清单快照入库）
聊天：      Agent 决策 ──tools/call──▶ mcp-demo                 （毫秒级返回）
```

Hify 侧注册时在鉴权头里填 `Authorization: Bearer hify-demo-token`，
走 T4b 的 `AuthHeader.valueEnc` 密文存储链路。

## 6. 错误处理

| 场景 | server 行为 | Hify 侧表现 |
|---|---|---|
| 无/错 token | HTTP 401 | 13002「MCP 服务器连接或工具发现失败」，注册试连接即暴露 |
| 参数非法（如 sides=1） | zod 校验失败，SDK 回 JSON-RPC 错误 | Agent 看到原因，可修正重试 |
| 工具内部异常（如非法时区） | MCP 规范的 `isError: true` 结果 | Agent 拿到错误文本；进程不炸 |

## 7. 测试策略（vitest，先写失败测试）

1. **纯函数单测**（`tools/`）：时区参数生效；骰子每次点数 ∈ [1, sides]、数量=count、总和正确；非法参数被 zod 拒绝。
2. **协议集成测**：SDK `InMemoryTransport` 把官方 MCP 客户端直连 `createMcpServer()`，断言 `tools/list` 返回 2 个工具（名字/描述/schema 齐全）、`tools/call` 返回正确结果——不经 HTTP，快且稳。
3. **鉴权测**：对 `app.ts` 发 HTTP 请求：无 token / 错 token → 401；对 token → 非 401。

## 8. 验收清单（DoD，人工可复核）

1. `pnpm test` 全绿；
2. `pnpm dev` 启动，日志显示监听 3100；
3. hify `.env` 加 `HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS=localhost`（hify-server 若跑在 compose 内则再加 `host.docker.internal`，注册 url 相应换 host），重启 hify-server；
4. admin 工具页注册：类型 MCP、传输 streamable、url `http://localhost:3100/mcp`、鉴权头 `Authorization: Bearer hify-demo-token` → 试连接发现 2 个工具，保存后快照入库；
5. **反向验证**：故意填错 token 试连接 → 必须失败（证明鉴权真的在拦）；
6. 应用勾选两个工具，聊天问「现在几点了？掷 3 个骰子」→ Agent 真调用、轨迹可见调用与结果。

## 9. 配套小事

- CLAUDE.md「仓库布局」加一行 `mcp-demo/`。
- README.md 写清：`pnpm i && pnpm dev`、环境变量、Hify 注册四步（含白名单）。
