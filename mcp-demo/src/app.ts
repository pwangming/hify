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

  // 互操作修补：SDK 1.29 的 Hono 适配层（@hono/node-server buildOutgoingHttpHeaders）
  // 会给空 body 的 202 响应强行补 content-type: text/plain，而 Java MCP 客户端对 POST
  // 响应只认 json / event-stream / 无 content-type 三种，见 text/plain 直接断连。
  // 规范本义是 202 空响应不带 content-type——这里在写响应头前把它剥掉。
  const stripContentTypeOn202 = (res: Response): void => {
    const origWriteHead = res.writeHead.bind(res) as (...args: unknown[]) => Response;
    res.writeHead = ((statusCode: number, ...args: unknown[]) => {
      if (statusCode === 202) {
        res.removeHeader("content-type");
        for (const a of args) {
          if (a && typeof a === "object" && !Array.isArray(a)) {
            delete (a as Record<string, unknown>)["content-type"];
            delete (a as Record<string, unknown>)["Content-Type"];
          }
        }
      }
      return origWriteHead(statusCode, ...args);
    }) as typeof res.writeHead;
  };

  // 无状态模式：每请求新建 server+transport，用完即弃（sessionIdGenerator: undefined 即无会话）
  app.post("/mcp", auth, async (req: Request, res: Response) => {
    stripContentTypeOn202(res);
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
