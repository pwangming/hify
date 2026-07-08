# E2E 地基 + KB 黄金旅程 — 设计文档

> 日期：2026-07-08
> 范围：前端测试基建（`web/e2e/`）+ 一份后端 E2E profile；**不碰 server 产品代码**（除按需补 `data-test`、新增 `application-e2e.yml`）。
> 背景：知识库问答支柱（K1~K4 + 引用来源）已完整合并 main。E2E 自 ③ 单轮聊天起就定为"等第一条完整产品旅程存在再做"（见记忆 frontend-testing-strategy），该触发条件早已满足。本轮在 KB 支柱做完的天然边界立起 E2E 地基。
> 一句话：本地起"真前端 + 真后端 + 真库 + 假 LLM 桩"的全栈，用 Playwright 跑一条穿透 5 个模块的黄金旅程（登录→配模型→建库→传文档→聊天→看引用），并用"故意搞错（变异测试）"证明它真在守门。

---

## 0. 决策摘要（brainstorm 拍板）

| # | 决策点 | 结论 | 理由 |
|---|---|---|---|
| 1 | 本轮范围 | **只做本地**，`pnpm e2e` 一条命令绿；CI 留下一轮 | CI 的前提是本地先稳；起停/等 ready/治 flaky 在本地调试比 CI 黑盒快十倍。分两轮各有可验收产出 |
| 2 | LLM/embedding 依赖 | **假 LLM 桩**，不真打 API | E2E 职责是抓"前后端契约漂移+旅程回归"，需要**确定性断言**；真 LLM 非确定、慢、要钱要密钥、flaky。真供应商连通性已由 provider 连接测试 + 每轮手动验收覆盖 |
| 3 | 全栈起停 | **Playwright 当总指挥**（webServer 拉后端+桩+前端，按健康 URL 逐个等 ready） | 等 ready 是 flaky 头号来源，Playwright 原生解决；一条命令即全部 |
| 4 | 数据隔离/重置 | **独立 `hify_e2e` 库**，每轮跑前重置一次，**只种 admin**，其余走 UI 现建 | 只碰专用库不碰 dev 真数据；只种 admin 最忠实（provider/knowledge 也进旅程）且避免在 seed 里复刻密钥加密 |
| 5 | 确定性策略 | **桩返回固定向量 + 固定答案**，测"命中+引用"happy path | 相似度=1 必过阈值、消除边界脆性；引用卡片来自后端检索、与桩答案解耦，断言天然稳 |

---

## 1. 架构与目录

新增集中在 `web/e2e/` + 一份后端 profile：

```
web/
  playwright.config.ts         # webServer 数组 + 单浏览器 chromium + 全局 use.baseURL
  package.json                 # devDep 加 @playwright/test；scripts 加 "e2e"
  e2e/
    golden-journey.spec.ts     # 唯一旅程用例
    stub/llm-stub.mjs          # 假 LLM 桩（Node 原生 http，零新语言/依赖）
    fixtures/kb-doc.txt        # 测试文档（内容已知、含一段可断言的原文）
    support/reset-db.mjs       # 跑前重置 hify_e2e（Playwright 之外）
server/src/main/resources/
  application-e2e.yml          # E2E profile：数据源指 hify_e2e、bootstrap-admin 账密、（可选）宽松配额
```

- E2E 全部落在前端 Node 工具链内（Playwright 本就是 Node）；桩用 Node 原生 http，不引额外运行时依赖。
- `web/e2e/**` 不进 vitest 的匹配范围（避免单测扫到 Playwright 用例），plan 核对 vitest include/exclude。

## 2. 假 LLM 桩（`web/e2e/stub/llm-stub.mjs`）

Node 原生 http 服务，端口 **8090**，说 OpenAI 兼容协议（baseUrl 约定 `http://localhost:8090/v1`，对齐修缮轮「ChatClientFactory 显式拼 `/chat/completions`+`/embeddings`、baseUrl 含版本段」）：

| 接口 | 行为 |
|---|---|
| `POST /v1/embeddings` | 返回**固定 1024 维向量**（对齐 V15 `vector(1024)`）。任意文本同一向量 → 问题与分段余弦相似度=1 → 必过 K4 阈值 0.3。响应体 `{data:[{embedding:[...1024...],index:0}],model,usage}` |
| `POST /v1/chat/completions` | **SSE 流式**：若干 `data:{choices:[{delta:{content:"…"}}]}\n\n` 分块吐一句写死答案，末尾带 usage 的块 + `data:[DONE]`。匹配前端 `sendStream` 走的 OpenAI 流式解析 |
| `GET /health` | 200，供 Playwright webServer 等 ready |

**桩的铁律：它是"傻"的**——只吐通用定值，**不知道断言期望什么、不含任何测试专用分支**。这是反做假协议（§5 尺子2）的前提：断言不能钉在桩喂的值上。

> 固定向量维度必须精确 1024，否则写入 kb_chunk 的 `vector(1024)` 会报维度不符——plan 首验此点。

## 3. 全栈起停编排 + 数据重置

`pnpm e2e` 分两段：**先重置、再交 Playwright**。

```
pnpm e2e
  └─ node e2e/support/reset-db.mjs      ① 确保 postgres 起着（docker compose up -d postgres，等 healthy）
  │                                     ② drop database if exists hify_e2e; create database hify_e2e;
  └─ playwright test                    ③ webServer 拉起三进程 + 逐个等健康 URL：
        · 后端  cd ../server && mvn -q spring-boot:run -Dspring-boot.run.profiles=e2e  等 http://localhost:8080/actuator/health
        · 桩    node e2e/stub/llm-stub.mjs                                                   等 http://localhost:8090/health
        · 前端  pnpm dev（vite:5173，其 /api 代理已指 localhost:8080）                        等 http://localhost:5173
        ④ 跑 golden-journey.spec.ts
```

**顺序约束（plan 必须守）**：库重置**必须早于后端启动**——后端启动才跑 Flyway 迁移 V1~V20 + `AdminBootstrapRunner` 按 `hify.identity.bootstrap-admin` 种 admin。故重置放 Playwright 之外的 wrapper，**不放 `globalSetup`**（规避 webServer 与 globalSetup 启动次序不确定）。

- 后端每轮**全新启动**（干净库 → 迁移 → 种 admin），最稳；JVM 启动约 20~30s，可接受。CI 化时再谈复用/加速（下一轮）。
- `application-e2e.yml` 关键项：`spring.datasource.url` 指 `hify_e2e`；`hify.identity.bootstrap-admin.username/password` 给测试 admin 账密；embedding/chat 无需在 yml 配供应商（旅程 UI 现建）。
- 端口占位：postgres 5432、后端 8080、桩 8090、前端 5173。plan 核对无冲突（尤其后端 8080 若已被 dev 后端占用，E2E 前需先停 dev 后端；写进使用说明）。

## 4. 黄金旅程（`golden-journey.spec.ts`）— 穿 5 模块

一条线性旅程，全程走 UI（除 admin 登录用种子账号）：

| 步 | 动作 | 穿过模块 | 关键断言 |
|---|---|---|---|
| 1 | 用种子 admin 登录 | identity | 进入后台 |
| 2 | 建供应商（OpenAI 兼容协议，baseUrl=`http://localhost:8090/v1`，key 填任意占位串） | provider | 供应商出现在列表 |
| 3 | 其下建**一个对话模型 + 一个 embedding 模型** | provider | 两模型可选 |
| 4 | 建知识库 | knowledge | 库出现 |
| 5 | 传 `fixtures/kb-doc.txt` → **等文档状态变 ready**（经桩 embedding 向量化入库） | knowledge | 文档 ready、分段数>0 |
| 6 | 建对话应用（选对话模型、绑该知识库） | app | 应用可对话 |
| 7 | 进聊天发一个问题 | conversation | 答案气泡显示桩固定答案；「参考来源(1)」出现；展开见 `kb-doc.txt` 文件名 + 一个 `[0,100]%` 分数 |
| 8 | **刷新页面** | conversation | 引用卡片**仍在**（证明 `message.sources` 真落库、history 读回渲染） |

- 步 8 是"最狠的断言"：逼着"上传→分段→embedding→入库→检索→落 message.sources→history 读回→渲染"整条链真通，端到端焊死上一轮的引用落库功能。
- 断言用 Playwright **web-first 自动等待**（对断言重试），**不用固定 sleep**（固定等待掩盖竞态、是脆性来源）。
- 选择器一律用 `data-test`。旅程触及页面若缺钩子，plan 定向补 `data-test`（不重构其它）：预计 provider（建供应商/建模型表单）、knowledge（建库/上传）、app（建应用/绑库表单）几处需补，conversation 已有钩子。

## 5. 完成的定义（DoD）+ 反做假协议 ★

依据 `testing-standards.md`（三类病 / 7 坑 / 故意搞错=唯一能证明测试守门的方法）。写成可勾选清单，plan 落实、验收核对。

**尺子1 · 故意搞错必须变红**（plan 含强制步骤：真改坏 → 确认断言红 → 改回）
- [ ] 桩 `/v1/embeddings` 改返回正交/远离向量（相似度跌破 0.3）→ 引用卡片消失 → 断言红（证明检索 SQL+阈值真在跑）
- [ ] 后端 `appendAssistant` 不写 `sources`（临时改坏）→ 步 8 刷新后引用消失 → 断言红（证明 message.sources 真落库）
- [ ] 应用与知识库解绑 → 无检索无引用 → 断言红（证明绑定+注入真串起）

**尺子2 · 断言钉在后端/DB 产出，不钉桩喂的值**
- [ ] 引用断言用**上传的真实文件名** + `[0,100]%` 数字分数；**不**断言桩那句固定答案的字面
- [ ] 桩保持"傻"：无任何针对断言的特判分支

**尺子3 · 覆盖面声明清楚（反遗漏）**
- [ ] 旅程真穿 identity/provider/knowledge/app/conversation 五模块，除 admin 登录外无一步用 seed 抄近路
- [ ] 推迟项写明：负样本「问无关→无引用/降级」（Q5 的 B）、CI、其它旅程

**判定"E2E 做完整了" = 本清单全勾 + 三次故意搞错全真变红。**

## 6. 范围 / 约束 / 不做

- **约束**：不碰 server 产品代码（仅按需补 `data-test`、新增 `application-e2e.yml`）；桩零密钥；E2E 仅跑 `hify_e2e`，绝不碰 dev 库；单浏览器 chromium；不用固定 sleep（web-first 断言）。
- **不做（声明，不藏）**：CI / GitHub Actions；负样本无引用/降级路径（桩已可扩，下轮几乎零成本）；workflow/agent 等其它旅程；跨浏览器矩阵；视觉回归/截图比对。
- **可能的定向改动**：给旅程触及的 provider/knowledge/app 视图补 `data-test`（服务本目标，不做无关重构）。

## 7. 文档入档
- `testing-standards.md` 新增「五、E2E（Playwright）」：起停编排、桩约定（OpenAI 兼容/固定向量/SSE）、DoD+反做假协议——作为后续模块（workflow/agent）E2E 的标准模板。
- `docs/self-check.md` 追加本轮"故意搞错"三项实测记录。

## 8. 验收口径
- `pnpm e2e` 本地一条命令跑绿（旅程 8 步全过，含刷新后引用仍在）。
- §5 DoD 清单全勾；三次故意搞错均实测变红并改回。
- 不新增运行时依赖（@playwright/test 为 devDependency；桩用 Node 原生）；vitest 单测（243）与 build 不受影响。
