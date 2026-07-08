# 测试质量判断手册

> **本文回答一个问题**：怎么判断一批测试（尤其是 AI 生成的）到底有没有用？
> 与 `self-check.md` 不同——那里讲「怎么确认某个功能生效」，这里讲「怎么确认测试本身值钱」。
> 适用前后端：示例取自 `web/`，但三类病、7 把尺子、故意搞错的方法论对 `server/` 的 JUnit 测试同样适用。

## 为什么需要这份文档

测试条数多 ≠ 有信心。一套测试可能：测重了（冗余）、测漏了（遗漏）、或者**根本不报警**（假测试）。
AI 生成测试时这三种病都更易发生，因为 AI 倾向于写「看起来很全」的测试——名字漂亮、数量多，但断言可能是空的、永远成立、或只是把实现抄了一遍。

**一句话抓总**：覆盖率管「有没有测到」，故意搞错管「测得有没有用」。两个一起上，假测试无所遁形。

---

## 一、测试的三类病与诊断手段（检测难度递增）

### 病 1：多余 / 冗余（两个测试测同一件事）

**信号**
- 两个用例几乎一样的 setup，最后断言同一条路径。
- 同一规则在「纯函数层」和「组件/集成层」各测一遍。

**诊断**
- **删除实验**：把可疑用例注释掉，跑覆盖率看有没有下降。一行不掉 → 大概率多余。
- **共红实验**：把对应源码故意改坏一行，看「几个」用例一起变红。多个一起红 = 在重复覆盖同一逻辑。

> ⚠️ 重叠不一定是病。纯函数测「逻辑对不对」、组件测「接进 UI 后还对不对」，是**有意的两层防护**。
> 要砍的是「同一层、同样断言」的无脑重复，不是「分层覆盖」。

### 病 2：没覆盖到（有分支/场景被漏掉）

**信号**：源码里有 `if/else`、`try/catch`、`?:`、`??`、边界值，但测试只走了顺利路径（happy path）。

**诊断——覆盖率报告（最客观）**
```bash
cd web && pnpm test:coverage      # = vitest run --coverage
```
逐行/逐分支标出**没被执行**的代码。**`% Branch`（分支）列比 `% Stmts`（语句）列更值钱**——它查的是岔路有没有都走到。

**覆盖率的两个陷阱（务必记住）**
1. **行覆盖 100% ≠ 测够了**。覆盖率只证明「这行被**跑过**」，不证明「跑出来的结果被**断言过**」。所以覆盖率高只能排除「明显漏测」，不能证明「测得有效」。
2. **整体百分比会误导**。要分清「该测的」和「不该用单测测的」：占位组件、样式展示页、入口 `main.ts`、路由配置表、纯 DOM 模板——这些 0% 是正常的。盯**有逻辑**的文件（纯函数、store、守卫、service）是否够高。

### 病 3：根本没起作用（假测试——AI 重灾区，最隐蔽）

**信号**（扫一眼断言能发现一部分）
- 没有断言，或断言**永远成立**：`toBeDefined()` / `toBeTruthy()` / `not.toThrow()` / 只 `console.log` 不 `expect`。
- **把被测对象本身 mock 掉了**——测的是 mock 的假行为，不是真代码。
- 测试名说测 A，断言却在测 B。

**诊断——故意搞错（变异测试 mutation testing，唯一能证明测试真在守门的方法）**

把源码**故意改坏一行**，跑测试，它**必须变红**。覆盖率骗得过（跑过但没断言），这个骗不过——代码坏了还绿，就是假测试。
- 工业级工具：JS 用 Stryker，Java 用 PIT。对单人项目偏重。
- **朴素手动版**就是 `self-check.md` 里一直用的「故意搞错」一节：每个关键测试做一次即可。

---

## 二、AI 生成测试的 7 个特有坑（7 把尺子）

前面三类病是通用的，下面这些是 **AI 特别容易犯、且容易被忽略**的：

| # | 坑 | 为什么 AI 爱犯 | 怎么识别 |
|---|---|---|---|
| 1 | **同义反复（最该警惕）** | AI 看着**实现代码**生成测试，把实现「抄」成断言。代码有 bug，测试跟着错，还是绿的 | 期望值像从实现反推的，不是按需求**独立**算出来。自问：「实现若是错的，这断言会不会跟着一起错？」 |
| 2 | **测了 mock，没测真代码** | AI 爱 mock，断言退化成「这函数被调了几次」 | 断言全是 `toHaveBeenCalled`，没有对**真实返回值/副作用**的检查 |
| 3 | **弱断言偏好** | 为「让测试通过」挑最松的断言 | 满屏 `toBeDefined`/`toBeTruthy`，很少断言**具体值** |
| 4 | **只测快乐路径** | 默认想正常输入，系统性忽略边界 | 没有 空/null/超长/网络失败/异常 用例——**bug 恰恰住在边界** |
| 5 | **脆弱测试** | 断言绑死在 DOM 结构、文案措辞、CSS class、调用顺序、元素位置上 | 改个文字、挪个标签就红——红得没意义，维护成本高。用 `data-test` 选择器优于按位置/文案取元素 |
| 6 | **数量幻觉** | 多给几条让人有安全感 | 看的是「**行为种类**」覆盖，不是「测试条数」。20 条测同一 happy path = 1 条的信息量 |
| 7 | **测了框架本身** | 凑数量 | 断言的是第三方库/语言能力（「Vue 能渲染 div」「数组能 map」），不是**你写的逻辑** → 0 价值 |

**最该记 #1 同义反复**：它绕过前面所有检查——有断言、覆盖率够、甚至故意搞错也可能变红——但它验证的是「代码做了它自己做的事」，不是「代码做了**需求要它做的事**」。
防它只有一招：**写/审测试时，期望值从需求独立推导，别瞄着实现填**。

---

## 三、推荐的体检流程（成本由低到高）

| 步骤 | 命令/动作 | 查出哪种病 |
|---|---|---|
| 1. 肉眼扫断言 | 每个用例有没有真断言？会不会永远成立？ | 病 3 一半 + 尺子 #1#2#3#7 |
| 2. 跑覆盖率 | `pnpm test:coverage`，看红行、盯 Branch 列 | 病 2 + 尺子 #4 |
| 3. 故意搞错 | 关键路径改坏一行看变红 | 病 3 另一半（最值钱） |
| 4. 删除/共红实验 | 注释用例看覆盖率掉不掉 / 改坏看几条一起红 | 病 1 |

---

## 四、连库测试（Testcontainers）运行前提（K4 起）

继承 `com.hify.support.PgIntegrationTest` 的测试类会真起一个 `pgvector/pgvector:pg16` 容器跑全量 Flyway 迁移，**前提是本机 Docker 已启动**。

**WSL2 + Docker Desktop 的坑（2026-07-07 拍板）**：Docker Desktop 29.x 下 Testcontainers 会报
`Could not find a valid Docker environment`（socket 探测不到 + API 版本协商失败），症状是 `mvn test`
里所有连库测试报 `NoClassDefFound: PgIntegrationTest`（静态块起容器失败）。修法是把两项配置持久化到
**用户目录**（一次配置，此后裸 `mvn test` 即可，不用带环境变量或 `-D` 参数）：

```properties
# ~/.testcontainers.properties 追加
docker.host=unix:///var/run/docker.sock

# ~/.docker-java.properties 新建
api.version=1.54
```

`api.version` 取本机 `docker version --format '{{.Server.APIVersion}}'` 的值；Docker Desktop 大版本升级后若连库测试再挂，先核对并更新这个值。

---

## 五、E2E（Playwright）

> 2026-07-08 起有效，随 KB 黄金旅程一轮落地。**作为后续 workflow/agent 等旅程的标准模板**——新旅程复用本节的编排/桩/断言纪律，不重新发明。

### 5.1 定位

E2E 抓的是**前后端契约漂移 + 跨模块旅程回归**，不是功能细节（细节由 server 的 `mvn test` 单测/切片测试、web 的 vitest 组件测试兜底）。全部落在 `web/e2e/`，跑真前端 + 真后端 + 真库（独立 `hify_e2e`）+ 假 LLM 桩，唯一被替换的外部依赖是 LLM/embedding 供应商（换成确定性桩）。

### 5.2 本地编排：`pnpm e2e`

```
pnpm e2e
  └─ node e2e/support/reset-db.mjs   ① 确保 postgres 起着；drop+create database hify_e2e
  └─ playwright test                 ② webServer 数组拉起三进程，逐个等健康 URL 就绪：
        · 后端 cd ../server && mvn -q spring-boot:run -Dspring-boot.run.profiles=e2e
               等 http://localhost:8080/actuator/health
        · 桩   node e2e/stub/llm-stub.mjs
               等 http://localhost:8090/health
        · 前端 pnpm dev
               等 http://localhost:5173
        ③ 跑 e2e/**/*.spec.ts（golden-journey.spec.ts + smoke.spec.ts）
```

**顺序约束（硬性）**：库重置**必须早于后端启动**——后端启动会跑 Flyway 全量迁移 + `AdminBootstrapRunner` 按 `application-e2e.yml` 的 `hify.identity.bootstrap-admin` 种 admin 账号，晚了会种到旧库或迁移对不齐。因此重置放在 `pnpm e2e` 脚本里、**在 Playwright 进程之外**（`package.json`：`"e2e": "node e2e/support/reset-db.mjs && playwright test"`），不放 Playwright 的 `globalSetup`——`globalSetup` 与 `webServer` 的启动次序不保证先后，规避这个不确定性。

配套：`server/src/main/resources/application-e2e.yml`（`e2e` profile，数据源指向 `hify_e2e`，`bootstrap-admin` 给测试账密），后端每轮全新启动（干净库→迁移→种 admin），最稳但每轮多花 20~30s 启动时间；CI 化时再谈复用/加速。

### 5.3 假 LLM 桩（`web/e2e/stub/llm-stub.mjs`）

Node 原生 `http` 模块起服务，零新依赖，端口 **8090**，说 OpenAI 兼容协议（`baseUrl` 约定 `http://localhost:8090/v1`）：

| 接口 | 行为 |
|---|---|
| `POST /v1/embeddings` | 返回**固定 1024 维向量**（对齐 `vector(1024)`）。任意输入文本同一向量 → 问题与文档分段余弦相似度恒为 1 → 必过检索阈值。 |
| `POST /v1/chat/completions` | **SSE 流式**：按 OpenAI 流式分块协议吐一句写死答案 + 末尾 usage 块 + `data:[DONE]`。 |
| `GET /health` | 200，供 Playwright `webServer` 探活。 |

**铁律：桩必须保持「傻」**——只吐通用定值，不知道断言期望什么、**不含任何针对测试用例的特判分支**。这是让断言不失真的前提：一旦桩里出现「如果是这条 case 就返回 XXX」，断言实质上是在验证桩而不是验证产品代码，检索/落库/编排等真实链路的问题会被桩悄悄掩盖。

### 5.4 断言纪律：钉后端产出，不钉桩喂的值

断言必须落在**后端/DB 真实产出**的数据上，而不是桩返回的固定字面值：

- 引用来源断言查**上传的真实文件名** + `[0,100]` 区间内的分数（检索算出来的相似度换算值），**不**断言桩那句写死答案的文字内容。
- 选择器一律用 `data-test`，不用 DOM 位置/文案（同「7 坑 #5」脆弱测试的规则）。
- 断言用 Playwright web-first 自动等待（对断言本身重试），不用固定 `sleep`——固定等待会掩盖竞态、是 flaky 的头号来源。

### 5.5 DoD：三把反做假尺子

判定「E2E 做完整了」= 下面三把尺子全过，且尺子 1 的三处「故意搞错」都**实测**变红过（不是纸面推断）：

**尺子 1 · 故意搞错必须变红**（真改坏源码/桩/旅程 → 跑 → 确认在预期断言处变红 → 改回，`git status` 确认已还原）：
- 桩 `/v1/embeddings` 改返回正交/远离向量 → 相似度跌破阈值 → 引用卡片消失 → 断言在「msg-sources visible」处变红（证明检索 SQL + 阈值判断真在跑，不是摆设）。
- 后端消息落库时不写 `sources` 字段 → 刷新页面后引用消失、但当次会话实时展示不受影响 → 断言在「刷新后 msg-sources 仍可见」处变红，与上一条断点位置不同（证明 `message.sources` 真落库、history 读回真渲染，且 SSE 实时展示与持久化是两条独立链路）。
- 应用与知识库解绑（跳过绑定步骤）→ 编排层无 dataset 可检索 → 断言在「msg-sources visible」处变红（证明 app↔dataset 绑定关系与检索注入真的串联，不是前端凭空渲染）。

**尺子 2 · 覆盖面声明清楚（反遗漏）**：旅程要真穿多个模块（本轮 identity/provider/knowledge/app/conversation 五个），除种子账号登录外无一步用后门抄近路；明确写清本轮推迟了什么（负样本路径、CI、其它旅程），不藏着不说。

**尺子 3 · 桩的中立性**：§5.3 的「桩必须保持傻」本身也是一把尺子——评审时检查桩代码里有没有偷偷加的特判分支。

### 5.6 已知边界（本轮不做，留给后续 E2E 轮次）

- 只本地跑，CI/GitHub Actions 集成留下一轮。
- 只测 happy path，负样本（如「问无关问题→无引用/降级」）未覆盖，桩已可扩展，后续几乎零成本加。
- 单浏览器 chromium，无跨浏览器矩阵；无视觉回归/截图比对。
- workflow/agent 等其它旅程未覆盖——但本节的编排/桩/断言/DoD 套路直接复用，新增旅程只需新增 `*.spec.ts` + 按需扩桩。

## 附：`web/` 现有测试体检（2026-06-22，28 用例 / 8 文件）

总体**明显高于 AI 平均水平**：普遍用 `data-test` 选择器、断言查具体值、`beforeEach` 清状态（localStorage + pinia）避开测试污染。真正的小问题：

| 文件 | 结论 | 可改进点 |
|---|---|---|
| `menu.spec.ts` | 标杆（纯函数，输入→输出） | 覆盖率抓到第 28 行 `?? route.path` 兜底分支未测（菜单项无 title 时退回 path）→ 补 1 条 |
| `guard.spec.ts` | 标杆（6 分支全覆盖，断言查重定向对象） | — |
| `user.spec.ts` | 健康（断言查 token/localStorage 具体值） | `loadCurrentUser` 失败路径未在 store 层测（已在 guard ③b 兜底，属分层，可接受） |
| `LoginView.spec.ts` | 健康（验证了 token 写入、跳转等真实副作用，非纯查 mock） | ① `findAll('input')[0]/[1]` 按**位置**取输入框——本套最脆弱处（表单加字段/换序即错位），建议改 `data-test`；② loading 态/失败态未测 |
| `App.spec.ts` | 健康（用 logout 按钮有无判断布局，测行为非实现） | — |
| `DefaultLayout.spec.ts` | 基本健康 | 「Member 看不到 admin 项」与 `menu.spec.ts` 部分重叠（属有意的逻辑层+UI 层分层，保留或精简自定） |
| `errorViews.spec.ts` | 够用 | 只测了「点返回跳首页」，未断言页面显示对应错误码文案（403 页文案写成 404 不会红）→ 价值低，可选补 |
| `BlankLayout.spec.ts` | 价值偏低但无害 | 接近测 Vue 的 slot 能力（尺子 #7），保留无妨 |

**优先级**：① `menu` 兜底分支补测（覆盖率实锤遗漏）→ ② `LoginView` 输入框改 `data-test`（唯一明显脆弱点）。其余为可选优化。
