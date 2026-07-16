# mcp-demo

Hify 的第一个自建 MCP server（TypeScript 练手工程）。提供两个工具：
`get_current_time`（查时区当前时间）、`roll_dice`（掷骰子真随机数）。

## 启动

```bash
pnpm install
pnpm dev        # 监听 http://localhost:3100/mcp
pnpm test       # vitest 全量测试
pnpm typecheck  # tsc --noEmit
```

环境变量（都有默认值，练手可不设）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `MCP_DEMO_PORT` | `3100` | 监听端口 |
| `MCP_DEMO_TOKEN` | `hify-demo-token` | Bearer 鉴权 token |

## 在 Hify 注册（四步）

1. **白名单**：本地 `make start` 跑法不加载任何 `.env`，变量用命令前缀传入并重启：
   `HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS=localhost make restart`（每次重启都要带，忘了=白名单为空）。
   compose 部署才写 `deploy/.env`，且 host 用 `host.docker.internal`，下面 url 的 host 同步换。
2. **注册**：admin 工具页 → 注册工具 → 类型 MCP → 传输 streamable →
   url `http://localhost:3100/mcp` → 鉴权头 `Authorization` = `Bearer hify-demo-token` → 试连接，
   应发现 2 个工具，保存。
3. **勾选**：目标应用的 Agent 配置里勾选这两个工具。
4. **验证**：聊天问「现在几点了？再掷 3 个骰子」，轨迹里应看到两次工具调用。

排障：试连接报 13002 先查三样——server 起了没、白名单配了没、token 对不对（本 server 鉴权失败回 401）。
判别线索：报 13002 而非 10001 说明白名单已放行；hify 的 `.run/backend.log` 里若见
`Server response ... Implementation[name=mcp-demo ...]` 说明握手已成功，问题在后续消息
（曾踩过一例：SDK 1.29 Hono 适配层给 202 空响应补 `text/plain`，Java 客户端断连，已在 `app.ts` 剥头修复并有回归测试锚定）。
