# E2E 旅程扩充：workflow + agent 黄金旅程 — 设计文档

> 日期：2026-07-18
> 范围：`web/e2e/`（桩扩展 + 两条新旅程）+ `application-e2e.yml`（MCP 白名单）+ 一处 server 产品代码（datasource fail-fast 守卫，还 E2E 地基轮的账）。
> 背景：管理控制台随用量看板轮收官，产品功能面只剩「应用对外 API+Key」（继续等真实调用方，2026-07-18 再次确认没有）。E2E 防回归网目前只有 KB 黄金旅程；workflow（W1-W3+画布 C1-C3+代码沙箱）与 agent（T1-T4b 含 MCP）两大支柱全靠单测+当轮人工验收，跨模块回归无网可依。
> 一句话：复用 E2E 地基（Playwright 总指挥 + 假 LLM 桩 + hify_e2e 独立库），把桩扩展出「同步 JSON + tool_calls」两种协议形状，新增 workflow（画布真拖拽搭 RAG 流并运行）与 agent（MCP 工具调用出轨迹）两条黄金旅程，并用变异测试证明它们真在守门。

---

## 0. 决策摘要（brainstorm 拍板，2026-07-18）

| # | 决策点 | 结论 | 理由 |
|---|---|---|---|
| 1 | 本轮方向 | **② E2E 扩充+留账清理**；① 对外 API 继续推迟 | 再次确认无真实调用方，①仍属假想需求；E2E 是剩余最大工程价值点 |
| 2 | 拆轮 | **拆两轮，E2E 先**（本 spec 只覆盖轮 1；轮 2=留账清理，见 §7） | 总量 10+ Task 超单轮体量；E2E 风险高（拖拽/MCP 编排）先啃碎，留账轮小而稳 |
| 3 | CI 化 | **不纳入**，继续只本地跑 | 项目无任何 CI 地基（后端单测 CI 都没有），CI 化应独立小轮从单测铺起 |
| 4 | 画布搭流方式 | **真拖拽**：`dispatchEvent` 手造 `DataTransfer` 模拟 HTML5 拖放；plan 首个 Task 做 spike，走不通降级需重新拍板 | E2E 的意义是模拟真人；画布拖拽是核心交互且 vitest 盖不住真浏览器 DnD；palette 仅有 `draggable`+`dragstart` 无点选备胎 |
| 5 | Agent 工具来源 | **复用 mcp-demo**（端口 3100，`pnpm dev` 起）+ `application-e2e.yml` 加 MCP 白名单放行 localhost | SsrfValidator 无差别禁内网不读配置（拍过板），HTTP/OpenAPI 工具打不到 localhost 桩；**MCP 白名单是唯一合法通道**（T4b 决策 2 既有机制，纯 yml 零产品代码）。mcp-demo 与 Hify 互操作已实测验证，自写 MCP 桩要手扛协议坑（前车之鉴：Hono 202 text/plain） |
| 6 | workflow 旅程图形态 | `开始→知识检索→LLM→结束` | RAG 工作流是产品两大场景的交叉点，一条旅程焊死 W1 引擎+W2 检索+画布；HTTP 节点（SSRF 禁内网）与代码节点（sandbox 编排复杂）明确不进旅程 |
| 7 | 桩扩展边界 | 只按**协议形状**分支（stream 与否、有无 role:tool），不看内容、无测试特判 | 守住地基轮「傻」铁律；两条链路后端都是同步 `.call()`（Agent 方案一=同步循环+SSE 爆发），现桩一律回 SSE 会解析失败，扩展是硬需求 |

---

## 1. 现状与差距（现场核实，2026-07-18）

- 桩 `web/e2e/stub/llm-stub.mjs`：`/v1/embeddings` 固定 1024 维向量；`/v1/chat/completions` **一律回 SSE**。
- `workflow/service/engine/LlmCaller.java:21` 与 `conversation/service/AgentChatService.java:107` 都是同步 `.call()`（发 `stream:false`）→ 现桩形状不兼容，**桩必须扩展**。
- 画布 palette（`NodePalette.vue`）：`draggable="true"` + `@dragstart`，HTML5 原生拖拽，无点击添加备胎。
- `SsrfValidator`：无差别禁内网、不读任何配置（类注释明示设计意图）；内网白名单仅存在于 MCP 出站（`hify.tool.mcp.allowed-private-hosts`）。
- mcp-demo：`McpServer(name: "mcp-demo")`，工具 `get_current_time` / `roll_dice`，`pnpm dev`（tsx）启动，与 Hify 互操作在 T4b/mcp-demo 轮实测通过。
- Hify MCP 发现层（`McpToolDiscoverer`）保留工具原名入注册表；**暴露给 LLM 的最终工具名 plan 阶段逐字核实**（桩的固定 tool_call 名必须与之一致）。
- data-test 基建：workflow 侧 `wf-run`/`wf-save`/`node-run-panel`/`node-run-inputs`/`node-run-outputs`/`palette-<type>` 等已就位；agent 轨迹侧 plan 阶段盘点，缺则按需补（地基轮先例：按需补 data-test 不算碰产品逻辑）。
- E2E 地基轮遗留 follow-up：`application-e2e.yml` 无 datasource 守卫——e2e profile 生效但 URL 不含 `hify_e2e` 时应启动即失败（防 reset-db 误伤真库），本轮补上。

## 2. 桩扩展（`web/e2e/stub/llm-stub.mjs`）

`/v1/chat/completions` 按协议形状三分支（判断顺序自上而下）：

1. **messages 含 `role:"tool"`** → 回**同步 JSON** 终答：固定文本（如「工具已调用完成，这是最终回答。」）+ 固定 usage。Agent 循环第二轮走到这。
2. **请求带非空 `tools` 数组** → 回**同步 JSON** tool_calls：固定调用一个工具（名=Hify 暴露的 mcp-demo `get_current_time` 最终名，args 固定 `{"timezone":"Asia/Shanghai"}`），`finish_reason:"tool_calls"`。Agent 循环第一轮走到这。
3. 其余按 `stream` 字段：`true` → SSE（现行为不变，KB 旅程零影响）；否则 → 同步 JSON 固定答案（workflow LLM 节点、同步聊天走到这）。

`/v1/embeddings` 不变。`llm-stub.selftest.mjs` 对三个新分支各补一条自测。

分支只看 `stream`/`tools`/`role` 三个协议字段的**有无**，不看取值内容——「傻」铁律保持：同形状请求永远同响应。

## 3. workflow 黄金旅程（`web/e2e/workflow-journey.spec.ts`）

前置数据全走 UI 现建、uniq 命名（沿用 KB 旅程模式，两条旅程数据互相独立）：

1. 登录 → 建供应商（baseUrl 指桩）→ 建 chat + embedding 模型 → 系统设置选 embedding 模型保存。
2. 建知识库 → 传 `kb-doc.txt` → 等状态「就绪」。
3. 建 workflow 应用 → 进画布：**拖拽**「知识检索」「LLM」节点入画布（开始/结束节点若默认预置则不拖，plan 核实），**连线**成 `开始→知识检索→LLM→结束`，填表单（检索节点绑库；LLM 节点选模型、提示词引用检索输出变量）→ 保存（断言 `wf-saved-at`）。
4. 运行（`wf-run`，填输入）→ 断言：运行成功态；输出含桩固定答案；点节点看 `node-run-panel` 的 inputs/outputs 非空（检索节点 outputs 含文档命中）。

拖拽实现：对 `palette-knowledge-retrieval` 等元素 `dispatchEvent('dragstart'…)` + 对画布落点 `dispatchEvent('drop', { dataTransfer })`，DataTransfer 用 `page.evaluateHandle` 现造。**plan 的 Task 1 = 拖拽 spike**（最小脚本验证节点能落画布），红了先停下重拍板（降级预案=API 播种图数据，UI 只做打开→运行→断言）。

## 4. Agent 黄金旅程（`web/e2e/agent-journey.spec.ts`）

编排：`playwright.config.ts` webServer 数组增加 mcp-demo（`pnpm dev`，健康等待策略 plan 核实——无 /health 则等端口/首包）；`application-e2e.yml` 增加 `hify.tool.mcp.allowed-private-hosts` 放行 localhost。

旅程：

1. 登录 → 建供应商/chat 模型（指桩）。
2. admin 工具注册表 → 接入 MCP 服务器（URL 指 `http://localhost:3100` 的实际路径，transport 按 mcp-demo 实况）→ 断言发现出 `get_current_time`（发现动作本身就验证了白名单生效 + MCP 握手互通）。
3. 建对话应用 → 开 Agent → 勾选该 MCP 工具。
4. 聊天发问 → 断言：**工具调用轨迹卡片**出现（含工具名）；**终答**出现（桩固定终答文本）。轨迹若落库（plan 核实 message 表结构）则加「刷新后轨迹仍在」断言，焊死落库链路。

## 5. fail-fast 守卫（唯一 server 产品代码改动）

e2e profile 激活且 datasource URL 不含 `hify_e2e` → 启动即抛异常失败。实现放 server 内以 `@Profile("e2e")` 类形式（具体挂载点 plan 定），并配一条后端测试。动机：`reset-db.mjs` 会 drop/recreate 目标库，配置漂移时必须在启动阶段拦住而不是删完才发现。

## 6. 反做假 DoD（判「测试完善」的唯一标准）

每条旅程至少两个变异、各断一次红，全过程记 `docs/self-check.md`：

| 旅程 | 变异 | 预期红 |
|---|---|---|
| workflow | 删图中一条必经边再运行 | 校验拦截或运行失败，旅程断言红 |
| workflow | 桩 embedding 改正交向量 | 检索节点 outputs 无命中，相关断言红 |
| agent | 应用不勾工具 | 轨迹卡片断言红（无工具调用） |
| agent | 停掉 mcp-demo 进程 | 出错误态而非假成功，终答/轨迹断言红 |

另外三条旅程（含既有 KB）全绿是回归底线：桩扩展绝不能把 KB 旅程改红。

## 7. 明确不做（本轮）

- **轮 2 留账清理**（下轮 brainstorm）：配额从 `daily_usage` 切 `usage_stat_daily` 并废弃旧表；`llm_call_log` 补耗时/成败观测列；日期组件全宽拉伸风源调查（timebox）。
- CI 化（独立小轮，从后端单测 CI 铺起）。
- ① 应用对外 API+Key（无调用方）。
- E2E 覆盖 HTTP 节点/代码执行节点/OpenAPI 自定义工具（SSRF 禁内网打不到桩、sandbox 编排复杂；不为测试破产品安全规矩）。
- 双 `/v1` 拼路径 bug——**已于 2026-07-07 修缮轮修复**，从留账清单划掉（本轮盘点纠错）。

## 8. 验收标准

1. `pnpm e2e` 一条命令三条旅程全绿（KB + workflow + agent）。
2. §6 变异测试全红实录入 `docs/self-check.md`。
3. fail-fast 守卫：后端测试绿 + 手动改错 URL 启动即失败实证。
4. 既有全量回归（server `mvn verify` 退出码 0、web vitest 全绿）不破。
5. 流程照旧：Codex 外部执行 → Claude 终审 → 人工验收 → push + memory 入档。
