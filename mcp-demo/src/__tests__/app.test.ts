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

describe("通知类 POST（无 id 的 JSON-RPC 消息）", () => {
  // 互操作修补的回归锚点：SDK 1.29 的 Hono 适配层会给空 body 202 补默认
  // content-type: text/plain，Hify 的 Java MCP 客户端见到非 json/event-stream 会断连。
  // 规范本义就是 202 空响应，不该有 content-type。
  it("notifications/initialized → 202 且不带 content-type", async () => {
    const res = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: goodHeaders,
      body: JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }),
    });
    expect(res.status).toBe(202);
    expect(res.headers.get("content-type")).toBeNull();
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
