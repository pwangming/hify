import { createApp } from "./app.js";

const port = Number(process.env.MCP_DEMO_PORT ?? 3100);
const token = process.env.MCP_DEMO_TOKEN ?? "hify-demo-token";

createApp(token).listen(port, () => {
  console.log(`mcp-demo listening on http://localhost:${port}/mcp`);
});
