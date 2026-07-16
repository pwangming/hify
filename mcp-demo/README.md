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

1. **白名单**：hify 根 `.env` 加 `HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS=localhost`
   （hify-server 跑在 docker compose 里则用 `host.docker.internal`，下面 url 的 host 同步换），重启 hify-server。
2. **注册**：admin 工具页 → 注册工具 → 类型 MCP → 传输 streamable →
   url `http://localhost:3100/mcp` → 鉴权头 `Authorization` = `Bearer hify-demo-token` → 试连接，
   应发现 2 个工具，保存。
3. **勾选**：目标应用的 Agent 配置里勾选这两个工具。
4. **验证**：聊天问「现在几点了？再掷 3 个骰子」，轨迹里应看到两次工具调用。

排障：试连接报 13002 先查三样——server 起了没、白名单配了没、token 对不对（本 server 鉴权失败回 401）。
