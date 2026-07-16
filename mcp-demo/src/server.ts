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
