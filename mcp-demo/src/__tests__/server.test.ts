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

  it("zod 拒绝非法参数 → isError 工具结果", async () => {
    const client = await connectedClient();
    const result = await client.callTool({ name: "roll_dice", arguments: { sides: 1 } });
    expect(result.isError).toBe(true);
  });
});
