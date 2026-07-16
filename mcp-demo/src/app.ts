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
