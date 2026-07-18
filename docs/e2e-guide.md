# E2E 测试使用指南（Playwright）

> 面向使用者的操作手册。设计原理/标准见 `docs/architecture/testing-standards.md` 第五节；
> 本文只讲**怎么用、常用命令、出问题怎么办**。

## 1. E2E 是什么，和单元测试有什么不同

- **单元测试**（`pnpm vitest` / 后端 `mvn test`）：只测一小块，其它依赖全用假的（mock）。快，但**测不到前后端拼在一起会不会对不上**。
- **E2E（端到端）**：起一套**真前端 + 真后端 + 真数据库**，用一个真浏览器把一整条产品流程点一遍（登录→配模型→建库→传文档→聊天→看引用）。这是唯一能自动抓到「前后端接口漂移 / 整条流程回归」的测试。
- 唯一"假"的东西是 **LLM**：用一个本地假桩顶替，不打真 API（见第 6 节）。

## 2. 跑之前的前提（不满足会卡在起服务那步）

1. **Docker 起着**（数据库容器要用）。
2. **端口 8080 / 5173 / 8090 / 3100 都空着**——也就是**别同时开着 dev 后端和 dev 前端**。E2E 会自己起后端(8080)、前端(5173)、假 LLM 桩(8090)和 mcp-demo(3100)。首次运行前请在 `mcp-demo` 目录执行一次 `pnpm install`。
3. 首次运行会下载 Chromium 浏览器（约 100MB，一次性）。第一次起后端 JVM 也可能慢（几分钟，之后就快了）。

## 3. 最常用的命令

> 全部在 `web/` 目录下执行（`cd web`）。

| 我想做什么 | 命令 |
|---|---|
| **跑全部 E2E，看过没过** | `pnpm e2e` |
| 事后逐步回放 + 快照（点 ▶ 运行，再点 Actions） | `pnpm e2e:ui` |
| **看真浏览器实时跑一遍**（会快速闪过 ~12s） | `node e2e/support/reset-db.mjs && pnpm exec playwright test golden-journey.spec.ts --headed` |
| **你控制、一步步单步**（最适合"看它怎么操作"） | `node e2e/support/reset-db.mjs && pnpm exec playwright test golden-journey.spec.ts --debug` |
| **生成可打开的 HTML 报告 + 轨迹** | `node e2e/support/reset-db.mjs && pnpm exec playwright test golden-journey.spec.ts --reporter=html --trace on` |
| 打开上一次生成的 HTML 报告 | `pnpm exec playwright show-report` |
| 只回放某次的轨迹 | `pnpm exec playwright show-trace <trace.zip 路径>` |

**⚠️ 传参数的坑**：只有 `pnpm e2e` 和 `pnpm e2e:ui` 这两个现成脚本能直接用。想带 `--headed`、`--debug`、`--reporter` 这类**参数**时，**不要**写 `pnpm e2e -- --headed`——pnpm 会把参数错当成文件名，参数被忽略（浏览器不会弹）。正确做法是**绕过脚本直接调 playwright**（先手动跑一次 `node e2e/support/reset-db.mjs` 重置库，再 `pnpm exec playwright test ...`），如上表。

## 4. 一次 `pnpm e2e` 到底发生了什么

```
pnpm e2e
  ├─ 1) node e2e/support/reset-db.mjs   → 起 postgres 容器，drop+recreate 独立库 hify_e2e（不碰你的 dev 库 hify）
  └─ 2) playwright test                  → 依次起三样并等它们就绪：
         · 后端  mvn spring-boot:run（e2e 配置，连 hify_e2e）  等 /actuator/health
         · 假LLM桩 node e2e/stub/llm-stub.mjs                  等 :8090/health
         · 前端  pnpm dev（vite:5173）                          等首页
      → 起齐后，用无头 Chromium 跑 e2e/*.spec.ts
```

- 数据库用的是**独立的 `hify_e2e`**，每次跑前清空重建——所以 E2E **绝不会动你 dev 库里的真数据**，也不用担心跑脏。
- 后端启动时会自动建迁移表 + 种一个测试 admin 账号（`admin` / `e2e-admin-123`），登录才成立。

## 5. 看结果 / HTML 报告 / 轨迹回放

- 终端最后一行 `N passed` = 过了；有失败会红字指出断在哪一行。
- **HTML 报告**（用第 3 节的 `--reporter=html --trace on` 生成后）：
  ```
  cd web && pnpm exec playwright show-report
  ```
  浏览器打开 → 点那条测试 → 看到**每一步的动作列表**；点步骤旁的 **Trace 缩略图**进 **Trace Viewer**，拖时间轴就能逐帧看**当时浏览器画面 + DOM 前后快照 + 网络请求**。这是最直观的"看它一步步操作"，可暂停可回放。
  （这个命令会起个本地服务并挂着，看完按 **Ctrl+C** 停。）
- 报告和轨迹产物在 `web/playwright-report/`、`web/test-results/`，都已在 `.gitignore` 里，不会误提交。

## 6. 假 LLM 桩：为什么不打真 API

`web/e2e/stub/llm-stub.mjs` 是个本地小服务，冒充 OpenAI 兼容协议：`/v1/embeddings` 永远返回同一个固定 1024 维向量，`/v1/chat/completions` 永远流式返回同一句固定答案。测试里那个"供应商"的 baseUrl 就填它（`http://localhost:8090/v1`）。

为什么这么做：E2E 要能**每次结果一模一样**才能断言。真 LLM 每次回答都不同、还要钱要密钥、会超时——没法稳定断言。真供应商能不能连通，另有 provider 的"连接测试"功能和你每轮手动验收在管，E2E 不接这活。

## 7. 黄金旅程测了什么

一条流程穿过 5 个模块：**登录 → 建供应商（指向假桩）→ 建对话/embedding 模型 → 设系统 embedding 模型 → 建知识库 → 传文档等向量化就绪 → 建对话应用并绑库 → 聊天 → 断言引用卡片显示上传的文件名 + 分数 → 刷新页面断言引用还在**。最后"刷新还在"证明引用真落库了（不是前端演的）。

本轮另有两条黄金旅程：`workflow-journey.spec.ts` 覆盖画布拖拽、连线、节点配置、运行输出与检索命中；`agent-journey.spec.ts` 覆盖 MCP 注册发现、Agent 工具调用轨迹、终答及刷新持久化。Agent 旅程的工具行名必须固定为 `mcpdemo`，绕过 reset-db 直跑两次会因重名失败。

LLM 桩按协议形状分三支：带 `role:tool` 返回 Agent 终答，带 `tools` 返回固定 tool_calls，`stream:true` 返回 SSE，否则返回同步 JSON；桩保持傻，不按具体测试内容特判。

## 8. 怎么确认它"没做假"（自检）

一个永远绿的假测试也会绿。判据是**故意搞坏，它必须变红**。最快的一次自检：
把 `e2e/golden-journey.spec.ts` 里 `expect(card).toContainText('kb-doc.txt')` 的文件名改成一个不存在的名字，`pnpm e2e` → **必须红**（断在这行）→ 说明断言真在读后端返回的真实值。**验完改回来**。
（更狠的三次「改坏检索/落库/绑定各看一次红」已做过并记录在 `docs/self-check.md`。）

## 9. 常见问题排查

| 现象 | 原因 / 解决 |
|---|---|
| 卡在起服务 / 端口报错 `EADDRINUSE` | 8080/5173/8090 被占。**关掉 dev 后端/前端**，或上次的 `pnpm e2e:ui`/`--debug` 窗口没关（关掉它；必要时结束占端口的进程）。 |
| `pnpm e2e:ui` 打开了窗口但"没动静" | UI 模式**不会自动跑**，左侧点测试的 **▶** 才开始；它给的是事后快照回放，不是实时浏览器。 |
| 加了 `--headed` 却没看到浏览器 | 多半写成了 `pnpm e2e -- --headed`（无效）。用第 3 节的直调写法：`node e2e/support/reset-db.mjs && pnpm exec playwright test golden-journey.spec.ts --headed`。 |
| 首次特别慢 / 后端起不来 | 首次 Chromium 下载 + 首次 mvn 起 JVM 会慢（几分钟），属正常，第二次就快。确认 Docker 起着。 |
| 报 `DROP DATABASE cannot run inside a transaction` 之类 | 一般是端口/连接残留；关掉残留进程重跑。 |
| 数据库会不会被弄脏 | 不会。E2E 只用独立库 `hify_e2e`，每次跑前重建；dev 库 `hify` 从不被 E2E 触碰。 |

## 10. 目录地图

```
web/
  playwright.config.ts          # 起停编排配置（三个 webServer + 单浏览器）
  e2e/
    golden-journey.spec.ts       # 黄金旅程
    smoke.spec.ts                # 登录冒烟
    stub/llm-stub.mjs            # 假 LLM 桩
    fixtures/kb-doc.txt          # 测试用文档
    support/reset-db.mjs         # 跑前重置 hify_e2e
  playwright-report/ test-results/  # 报告与轨迹产物（gitignore）
server/src/main/resources/application-e2e.yml   # 后端 E2E 配置（连 hify_e2e、种 admin）
```

## 11. 目前不做的（范围）

- **CI（GitHub Actions 自动跑）**：留待后续，现在只在本地跑。
- **负样本**（问无关问题→无引用）、其它模块（workflow/agent）的旅程、跨浏览器：后续按需扩，桩和地基已可复用。
