# mcp-demo 自建 MCP server 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `hify/mcp-demo/` 建一个 TypeScript MCP server（2 个练手工具 + Bearer 鉴权），可被 Hify admin 页注册、发现，并在聊天中被 Agent 真调用。

**Architecture:** 官方 `@modelcontextprotocol/sdk` + Express + Streamable HTTP **无状态模式**（每个 `POST /mcp` 请求新建 McpServer + transport，用完即弃）。`index.ts`（占端口）与 `app.ts`（路由形状）分离以便测试；工具是纯函数，与协议层解耦。

**Tech Stack:** TypeScript(ESM) / @modelcontextprotocol/sdk 1.x / Express 4 / zod 3 / tsx / vitest / pnpm(Node v24)

**Spec:** `docs/superpowers/specs/2026-07-16-mcp-demo-server-design.md`

## Global Constraints

- 工程根为 `mcp-demo/`（hify 仓库内顶层目录）；本计划所有 `pnpm` 命令在 `/home/wang/playlab/hify/mcp-demo/` 下执行。
- 依赖锁定：`zod` 用 **v3**（SDK 1.x 的搭档版本）、`express` 用 **4.x**（`@types/express@^4` 配套）。不加计划外依赖。
- ESM 工程：`package.json` 有 `"type": "module"`，**所有相对导入必须带 `.js` 后缀**（如 `from "./server.js"`），TS 源码也是——这是 NodeNext 模块解析的规则，漏了会在运行时报"模块找不到"。
- 端口 3100；token 从环境变量 `MCP_DEMO_TOKEN` 读，默认 `hify-demo-token`。
- 工具的 name/description 是给 LLM 看的：描述用中文、写清"何时该用我"。
- TDD：每步先写失败测试，跑过再实现。测试放 `src/**/__tests__/*.test.ts`。
- 每个 Task 结束：commit（scope 用 `mcp-demo`），并按项目惯例向 `docs/self-check.md` 追加该 Task 自检小节。
- 判定 vitest 结果看 `Test Files ... passed` 汇总行，别 grep 关键字。

---

### Task 1: 工程脚手架 + get_current_time 纯函数

**Files:**
- Create: `mcp-demo/package.json`
- Create: `mcp-demo/tsconfig.json`
- Create: `mcp-demo/vitest.config.ts`
- Create: `mcp-demo/src/tools/current-time.ts`
- Test: `mcp-demo/src/tools/__tests__/current-time.test.ts`

**Interfaces:**
- Consumes: 无（首个 Task）
- Produces: `getCurrentTime(timezone?: string): string`（非法时区抛 `RangeError`）；`currentTimeInputSchema: { timezone: z.ZodOptional<z.ZodString> }`（zod raw shape，供 Task 3 的 `registerTool` 用）

- [x] **Step 1: 初始化工程**

```bash
mkdir -p /home/wang/playlab/hify/mcp-demo/src/tools/__tests__
cd /home/wang/playlab/hify/mcp-demo
pnpm init
pnpm add @modelcontextprotocol/sdk express@^4 zod@^3
pnpm add -D typescript tsx vitest @types/express@^4 @types/node
```

然后把 `package.json` 改成（保留 pnpm 写入的 dependencies/devDependencies 版本号，其余字段以此为准）：

```json
{
  "name": "mcp-demo",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "tsx src/index.ts",
    "test": "vitest run",
    "typecheck": "tsc --noEmit"
  }
}
```

- [x] **Step 2: 写 tsconfig.json 和 vitest.config.ts**

`mcp-demo/tsconfig.json`：

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "strict": true,
    "skipLibCheck": true,
    "noEmit": true,
    "types": ["node"]
  },
  "include": ["src"]
}
```

`mcp-demo/vitest.config.ts`：

```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["src/**/__tests__/**/*.test.ts"],
  },
});
```

- [x] **Step 3: 写失败测试**

`mcp-demo/src/tools/__tests__/current-time.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import { z } from "zod";
import { currentTimeInputSchema, getCurrentTime } from "../current-time.js";

describe("getCurrentTime", () => {
  it("默认时区 Asia/Shanghai，返回含时区名与年份的字符串", () => {
    const s = getCurrentTime();
    expect(s).toContain("Asia/Shanghai");
    expect(s).toMatch(/\d{4}/);
  });

  it("指定 UTC 时区生效", () => {
    expect(getCurrentTime("UTC")).toContain("UTC");
  });

  it("非法时区名抛 RangeError（走工具执行错误路径）", () => {
    expect(() => getCurrentTime("Not/AZone")).toThrow(RangeError);
  });
});

describe("currentTimeInputSchema", () => {
  it("timezone 可选，空参数合法", () => {
    expect(z.object(currentTimeInputSchema).safeParse({}).success).toBe(true);
  });

  it("timezone 必须是字符串", () => {
    expect(z.object(currentTimeInputSchema).safeParse({ timezone: 8 }).success).toBe(false);
  });
});
```

- [x] **Step 4: 跑测试确认失败**

Run: `pnpm test`
Expected: FAIL，报 `Cannot find module '../current-time.js'`（或等价的模块解析错误）

- [x] **Step 5: 最小实现**

`mcp-demo/src/tools/current-time.ts`：

```ts
import { z } from "zod";

/** zod raw shape：registerTool 的 inputSchema 直接吃这个形状。 */
export const currentTimeInputSchema = {
  timezone: z
    .string()
    .describe("IANA 时区名，如 Asia/Shanghai、America/New_York；不传默认 Asia/Shanghai")
    .optional(),
};

export function getCurrentTime(timezone = "Asia/Shanghai"): string {
  // 非法时区名 Intl 会抛 RangeError，由协议层转成 isError 结果
  const fmt = new Intl.DateTimeFormat("zh-CN", {
    timeZone: timezone,
    dateStyle: "full",
    timeStyle: "medium",
  });
  return `${timezone} 当前时间：${fmt.format(new Date())}`;
}
```

- [x] **Step 6: 跑测试确认通过 + 类型检查**

Run: `pnpm test && pnpm typecheck`
Expected: vitest 汇总行 `Test Files  1 passed`，typecheck 无输出退出码 0

- [x] **Step 7: Commit + 自检**

```bash
cd /home/wang/playlab/hify
git add mcp-demo/ docs/self-check.md
git commit -m "feat(mcp-demo): 工程脚手架+get_current_time 纯函数（TDD）"
```

（提交前把 Task 1 自检小节追加进 `docs/self-check.md`。）

---

### Task 2: roll_dice 纯函数

**Files:**
- Create: `mcp-demo/src/tools/roll-dice.ts`
- Test: `mcp-demo/src/tools/__tests__/roll-dice.test.ts`

**Interfaces:**
- Consumes: 无
- Produces: `rollDice(sides?: number, count?: number): { rolls: number[]; total: number }`；`rollDiceInputSchema: { sides: ..., count: ... }`（zod raw shape，带默认值 sides=6/count=1，供 Task 3 用）

- [x] **Step 1: 写失败测试**

`mcp-demo/src/tools/__tests__/roll-dice.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import { z } from "zod";
import { rollDice, rollDiceInputSchema } from "../roll-dice.js";

describe("rollDice", () => {
  it("每次点数 ∈ [1, sides] 且为整数，数量=count（重复 200 次防侥幸）", () => {
    for (let i = 0; i < 200; i++) {
      const { rolls } = rollDice(6, 3);
      expect(rolls).toHaveLength(3);
      for (const r of rolls) {
        expect(Number.isInteger(r)).toBe(true);
        expect(r).toBeGreaterThanOrEqual(1);
        expect(r).toBeLessThanOrEqual(6);
      }
    }
  });

  it("total 等于各次点数之和", () => {
    const { rolls, total } = rollDice(20, 5);
    expect(total).toBe(rolls.reduce((a, b) => a + b, 0));
  });

  it("默认 6 面 1 次", () => {
    const { rolls } = rollDice();
    expect(rolls).toHaveLength(1);
    expect(rolls[0]).toBeGreaterThanOrEqual(1);
    expect(rolls[0]).toBeLessThanOrEqual(6);
  });
});

describe("rollDiceInputSchema", () => {
  it.each([
    [{ sides: 1 }],
    [{ sides: 1001 }],
    [{ sides: 6.5 }],
    [{ count: 0 }],
    [{ count: 11 }],
  ])("拒绝非法参数 %j", (bad) => {
    expect(z.object(rollDiceInputSchema).safeParse(bad).success).toBe(false);
  });

  it("空对象解析出默认值 sides=6 count=1", () => {
    expect(z.object(rollDiceInputSchema).parse({})).toEqual({ sides: 6, count: 1 });
  });
});
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test`
Expected: FAIL，报 `Cannot find module '../roll-dice.js'`

- [x] **Step 3: 最小实现**

`mcp-demo/src/tools/roll-dice.ts`：

```ts
import { z } from "zod";

/** zod raw shape：带默认值，协议层 parse 后处理器拿到的一定是数字。 */
export const rollDiceInputSchema = {
  sides: z.number().int().min(2).max(1000).default(6).describe("骰子面数，2-1000，默认 6"),
  count: z.number().int().min(1).max(10).default(1).describe("掷几次，1-10，默认 1"),
};

export function rollDice(sides = 6, count = 1): { rolls: number[]; total: number } {
  const rolls = Array.from({ length: count }, () => 1 + Math.floor(Math.random() * sides));
  const total = rolls.reduce((a, b) => a + b, 0);
  return { rolls, total };
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test && pnpm typecheck`
Expected: `Test Files  2 passed`，typecheck 退出码 0

- [x] **Step 5: Commit + 自检**

```bash
cd /home/wang/playlab/hify
git add mcp-demo/ docs/self-check.md
git commit -m "feat(mcp-demo): roll_dice 纯函数（TDD）"
```

---

### Task 3: createMcpServer 工厂 + 协议集成测试

**Files:**
- Create: `mcp-demo/src/server.ts`
- Test: `mcp-demo/src/__tests__/server.test.ts`

**Interfaces:**
- Consumes: Task 1 的 `getCurrentTime`/`currentTimeInputSchema`，Task 2 的 `rollDice`/`rollDiceInputSchema`
- Produces: `createMcpServer(): McpServer`（注册了 `get_current_time`、`roll_dice` 两个工具，供 Task 4 的每请求新建使用）

- [x] **Step 1: 写失败测试**

`mcp-demo/src/__tests__/server.test.ts`（用 SDK 的 InMemoryTransport 把官方客户端直连 server，不经 HTTP）：

```ts
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { describe, expect, it } from "vitest";
import { createMcpServer } from "../server.js";

async function connectedClient(): Promise<Client> {
  const server = createMcpServer();
  const client = new Client({ name: "test-client", version: "0.0.1" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
  return client;
}

function firstText(result: Awaited<ReturnType<Client["callTool"]>>): string {
  const content = result.content as Array<{ type: string; text?: string }>;
  expect(content[0]?.type).toBe("text");
  return content[0]?.text ?? "";
}

describe("tools/list", () => {
  it("返回 2 个工具，名字/描述/inputSchema 齐全", async () => {
    const client = await connectedClient();
    const { tools } = await client.listTools();
    expect(tools.map((t) => t.name).sort()).toEqual(["get_current_time", "roll_dice"]);
    for (const t of tools) {
      expect(t.description).toBeTruthy();
      expect(t.inputSchema).toMatchObject({ type: "object" });
    }
  });
});

describe("tools/call", () => {
  it("get_current_time 默认时区返回 Asia/Shanghai 时间", async () => {
    const client = await connectedClient();
    const result = await client.callTool({ name: "get_current_time", arguments: {} });
    expect(result.isError).toBeFalsy();
    expect(firstText(result)).toContain("Asia/Shanghai");
  });

  it("roll_dice 指定参数返回点数与总和", async () => {
    const client = await connectedClient();
    const result = await client.callTool({ name: "roll_dice", arguments: { sides: 20, count: 3 } });
    expect(result.isError).toBeFalsy();
    expect(firstText(result)).toMatch(/掷 3 次 20 面骰/);
  });

  it("roll_dice 空参数走默认值 6 面 1 次", async () => {
    const client = await connectedClient();
    const result = await client.callTool({ name: "roll_dice", arguments: {} });
    expect(firstText(result)).toMatch(/掷 1 次 6 面骰/);
  });

  it("非法时区 → isError 工具结果（不是协议错误，进程不炸）", async () => {
    const client = await connectedClient();
    const result = await client.callTool({ name: "get_current_time", arguments: { timezone: "Not/AZone" } });
    expect(result.isError).toBe(true);
  });

  it("zod 拒绝非法参数 → JSON-RPC InvalidParams 协议错误", async () => {
    const client = await connectedClient();
    await expect(client.callTool({ name: "roll_dice", arguments: { sides: 1 } })).rejects.toThrow();
  });
});
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test`
Expected: FAIL，报 `Cannot find module '../server.js'`

- [x] **Step 3: 最小实现**

`mcp-demo/src/server.ts`：

```ts
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { currentTimeInputSchema, getCurrentTime } from "./tools/current-time.js";
import { rollDice, rollDiceInputSchema } from "./tools/roll-dice.js";

/**
 * 每个 HTTP 请求新建一个实例（无状态模式），注册两个练手工具。
 * description 是给 LLM 看的调用依据：写清"何时该用我"。
 */
export function createMcpServer(): McpServer {
  const server = new McpServer({ name: "mcp-demo", version: "1.0.0" });

  server.registerTool(
    "get_current_time",
    {
      description: "查询指定时区的当前日期和时间。当用户问「现在几点」「今天几号/星期几」时使用。",
      inputSchema: currentTimeInputSchema,
    },
    async ({ timezone }) => ({
      content: [{ type: "text", text: getCurrentTime(timezone) }],
    }),
  );

  server.registerTool(
    "roll_dice",
    {
      description: "掷骰子，返回真随机点数。当用户要掷骰子、抽签、需要随机数时使用。",
      inputSchema: rollDiceInputSchema,
    },
    async ({ sides, count }) => {
      const { rolls, total } = rollDice(sides, count);
      return {
        content: [{ type: "text", text: `掷 ${count} 次 ${sides} 面骰：[${rolls.join(", ")}]，总和 ${total}` }],
      };
    },
  );

  return server;
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test && pnpm typecheck`
Expected: `Test Files  3 passed`，typecheck 退出码 0

- [x] **Step 5: Commit + 自检**

```bash
cd /home/wang/playlab/hify
git add mcp-demo/ docs/self-check.md
git commit -m "feat(mcp-demo): createMcpServer 注册两工具+InMemory 协议集成测试"
```

---

### Task 4: Express app（鉴权 + /mcp 路由）+ 入口 + README + CLAUDE.md

**Files:**
- Create: `mcp-demo/src/app.ts`
- Create: `mcp-demo/src/index.ts`
- Create: `mcp-demo/README.md`
- Modify: `/home/wang/playlab/hify/CLAUDE.md`（仓库布局代码块）
- Test: `mcp-demo/src/__tests__/app.test.ts`

**Interfaces:**
- Consumes: Task 3 的 `createMcpServer(): McpServer`
- Produces: `createApp(token: string): Express`（Express 应用，`POST /mcp` 带 Bearer 鉴权）；入口 `src/index.ts`（`pnpm dev` 监听 3100）

- [x] **Step 1: 写失败测试**

`mcp-demo/src/__tests__/app.test.ts`（真 HTTP：监听随机端口，用内置 fetch 打）：

```ts
import type { Server } from "node:http";
import type { AddressInfo } from "node:net";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createApp } from "../app.js";

let server: Server;
let base: string;

beforeAll(async () => {
  server = createApp("test-token").listen(0);
  await new Promise((r) => server.once("listening", r));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterAll(async () => {
  await new Promise((r) => server.close(r));
});

const initializeBody = JSON.stringify({
  jsonrpc: "2.0",
  id: 1,
  method: "initialize",
  params: { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "test", version: "0" } },
});

// Streamable HTTP 规范要求 Accept 同时含 json 和 event-stream，缺了 SDK 会回 406
const goodHeaders = {
  "content-type": "application/json",
  accept: "application/json, text/event-stream",
  authorization: "Bearer test-token",
};

describe("鉴权", () => {
  it("无 Authorization → 401", async () => {
    const res = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
      body: initializeBody,
    });
    expect(res.status).toBe(401);
  });

  it("错 token → 401", async () => {
    const res = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: { ...goodHeaders, authorization: "Bearer wrong" },
      body: initializeBody,
    });
    expect(res.status).toBe(401);
  });

  it("对 token → initialize 返回 200", async () => {
    const res = await fetch(`${base}/mcp`, { method: "POST", headers: goodHeaders, body: initializeBody });
    expect(res.status).toBe(200);
  });
});

describe("无状态模式不支持的方法", () => {
  it("GET /mcp → 405", async () => {
    const res = await fetch(`${base}/mcp`, { method: "GET", headers: goodHeaders });
    expect(res.status).toBe(405);
  });

  it("DELETE /mcp → 405", async () => {
    const res = await fetch(`${base}/mcp`, { method: "DELETE", headers: goodHeaders });
    expect(res.status).toBe(405);
  });
});
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test`
Expected: FAIL，报 `Cannot find module '../app.js'`

- [x] **Step 3: 最小实现 app.ts**

`mcp-demo/src/app.ts`：

```ts
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import express, { type Express, type NextFunction, type Request, type Response } from "express";
import { createMcpServer } from "./server.js";

export function createApp(token: string): Express {
  const app = express();
  app.use(express.json());

  const auth = (req: Request, res: Response, next: NextFunction): void => {
    if (req.headers.authorization !== `Bearer ${token}`) {
      res.status(401).json({ error: "unauthorized" });
      return;
    }
    next();
  };

  // 无状态模式：每请求新建 server+transport，用完即弃（sessionIdGenerator: undefined 即无会话）
  app.post("/mcp", auth, async (req: Request, res: Response) => {
    const server = createMcpServer();
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
    res.on("close", () => {
      void transport.close();
      void server.close();
    });
    try {
      await server.connect(transport);
      await transport.handleRequest(req, res, req.body);
    } catch (e) {
      if (!res.headersSent) {
        res.status(500).json({
          jsonrpc: "2.0",
          error: { code: -32603, message: `internal error: ${e instanceof Error ? e.message : String(e)}` },
          id: null,
        });
      }
    }
  });

  // 无会话就没有服务端推送(GET SSE)和会话终止(DELETE)，按官方无状态示例回 405
  const methodNotAllowed = (_req: Request, res: Response): void => {
    res.status(405).json({
      jsonrpc: "2.0",
      error: { code: -32000, message: "Method not allowed in stateless mode" },
      id: null,
    });
  };
  app.get("/mcp", auth, methodNotAllowed);
  app.delete("/mcp", auth, methodNotAllowed);

  return app;
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test && pnpm typecheck`
Expected: `Test Files  4 passed`，typecheck 退出码 0

- [x] **Step 5: 写入口 index.ts**

`mcp-demo/src/index.ts`：

```ts
import { createApp } from "./app.js";

const port = Number(process.env.MCP_DEMO_PORT ?? 3100);
const token = process.env.MCP_DEMO_TOKEN ?? "hify-demo-token";

createApp(token).listen(port, () => {
  console.log(`mcp-demo listening on http://localhost:${port}/mcp`);
});
```

- [x] **Step 6: 手动冒烟——启动并用 curl 打 initialize**

启动（后台或另开终端）：

```bash
cd /home/wang/playlab/hify/mcp-demo && pnpm dev
```

Expected: 打印 `mcp-demo listening on http://localhost:3100/mcp`

curl 冒烟：

```bash
curl -s -X POST http://localhost:3100/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Authorization: Bearer hify-demo-token' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'
```

Expected: SSE 响应，含 `"serverInfo":{"name":"mcp-demo","version":"1.0.0"}`。冒烟后停掉进程。

- [x] **Step 7: 写 README.md**

`mcp-demo/README.md`：

````markdown
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
````

- [x] **Step 8: CLAUDE.md 仓库布局加一行**

修改 `/home/wang/playlab/hify/CLAUDE.md` 仓库布局代码块，在 `├── web/` 行后加：

```
├── mcp-demo/            # 自建 MCP server 练手工程（TypeScript，独立 pnpm 工程，端口 3100）
```

- [x] **Step 9: 全量回归 + Commit + 自检**

```bash
cd /home/wang/playlab/hify/mcp-demo && pnpm test && pnpm typecheck
cd /home/wang/playlab/hify
git add mcp-demo/ CLAUDE.md docs/self-check.md
git commit -m "feat(mcp-demo): Express 鉴权+/mcp 无状态路由+入口+README；CLAUDE.md 布局收录"
```

Expected: `Test Files  4 passed`

---

### Task 5: 人工验收（全链路，按 spec §8 DoD）

**Files:** 无代码改动；hify 根 `.env` 加一行（不入库）。

**Interfaces:**
- Consumes: Task 4 的运行中 server（`pnpm dev`）+ Hify 既有 MCP 注册/调用链路
- Produces: 验收结论（追加到 `docs/self-check.md`）

- [ ] **Step 1: 配白名单并重启 hify-server**

hify 根 `.env` 追加（若 hify-server 在 compose 内跑，值用 `host.docker.internal`）：

```
HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS=localhost
```

重启 hify-server（重启=重新打包+换进程，确认新进程生效）。

- [ ] **Step 2: 启动 mcp-demo**

Run: `cd /home/wang/playlab/hify/mcp-demo && pnpm dev`
Expected: `mcp-demo listening on http://localhost:3100/mcp`

- [ ] **Step 3: admin 页注册 + 正向发现**

admin 工具页注册：类型 MCP、传输 streamable、url `http://localhost:3100/mcp`（compose 场景换 host）、鉴权头 `Authorization` = `Bearer hify-demo-token`。
Expected: 试连接发现 `get_current_time`、`roll_dice` 2 个工具（中文描述可见），保存成功，列表页有 MCP 标签、工具数=2。

- [ ] **Step 4: 反向验证——错 token 必须失败**

再开一个注册抽屉，同 url 但鉴权头值填 `Bearer wrong-token`，点试连接。
Expected: 报 13002「MCP 服务器连接或工具发现失败」（证明鉴权真的在拦）。不保存，关掉抽屉。

- [ ] **Step 5: 聊天真调用**

任一对话应用 → Agent 配置勾选两个工具 → 聊天输入「现在几点了？再帮我掷 3 个 20 面骰」。
Expected: 回答含当前时间和 3 个 ∈[1,20] 的点数；轨迹里可见两次工具调用及结果。

- [ ] **Step 6: 验收入档**

把 Task 5 验收结果（含反向验证）追加到 `docs/self-check.md`，commit：

```bash
cd /home/wang/playlab/hify
git add docs/self-check.md
git commit -m "docs(mcp-demo): 全链路人工验收入档（注册/发现/反向鉴权/Agent 真调用）"
```
