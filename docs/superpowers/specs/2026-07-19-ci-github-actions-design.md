# CI 化小轮设计（GitHub Actions）

> 2026-07-19 · 状态：已拍板，待实现
> 起点 commit：`0073848`

## 1. 背景与目标

项目至今没有任何 CI 配置。所有测试只在本地手动跑，护网完全依赖「记得跑」。
一人维护 + 直推 main 的工作流下，最真实的风险不是「合并了坏代码」，而是
**「我改了 A，B 挂了，而我不知道」**——上一轮（留账清理轮）终审抓出的三个真 bug
里，有两个正是这一类跨模块静默失效。

本轮目标：把已有的后端 + 前端测试套接上 GitHub Actions，push 后自动跑，红了告警。

## 2. 已拍板的四项决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 载体 | **GitHub Actions** | 仓库是 public，标准 runner 免费无额度上限；不占本机、不与本地 `java -jar` 抢端口 |
| 范围 | **后端全量（含 Testcontainers）+ 前端 typecheck/lint/vitest** | 连库测试在 GH runner 上反而比本地简单（自带 Docker，不需要 WSL 绕行配置），且它们是抓「功能空转」最值钱的一批；前端边际成本近乎为零 |
| 门禁 | **push 自动跑，红了邮件告警，不阻塞** | 保持现有直推 main 的习惯，零工作流改变。分支保护以后想加随时能加 |
| `*IT.java` | **改名为 `*Test.java`** | 见第 4 节 |

## 3. 基线实测（2026-07-19，实现前）

| 项 | 结果 |
|---|---|
| `mvn -B -f server/pom.xml verify` | 728 tests / 0 failures / **EXIT=0**，本地 59s（Docker 已热、镜像已缓存） |
| `pnpm test` | 413 tests / 60 files 全绿，17s |
| `npx eslint .`（不带 `--fix`） | EXIT=0，当前干净 |
| 工具链 | Java 21 / Spring Boot 3.5.6 / Node v24.16.0 / pnpm 11.4.0 |

**CI 不需要任何 secret**：`application.yml` 里每一个 `${VAR}` 都带 dev 默认值
（JWT secret、provider master-key 等都有 `dev-only-*` 兜底），测试跑不到真实外部服务。

## 4. 附带修复：两个从未执行的测试类

`server/src/test/java/com/hify/tool/mapper/ToolMapperIT.java` 与
`server/src/test/java/com/hify/app/mapper/AppToolRelMapperIT.java`
**从未在常规 `mvn test` / `mvn verify` 中被执行过**，各含 1 个 `@Test`。
（`docs/self-check.md:936` 记录 T2 轮曾用 `-Dtest=AppToolRelMapperIT` 显式点名跑过一次并通过——
`-Dtest=` 会绕开 include 过滤。所以它们不是「从没绿过」，而是**写完那天之后再没跑过**，
此后任何回归都是静默的。）

成因是两处叠加：

1. surefire 的默认 include 是 `**/Test*.java` / `**/*Test.java` / `**/*Tests.java` /
   `**/*TestCase.java`，**不匹配 `*IT.java`**；
2. `maven-failsafe-plugin` 只存在于 Spring Boot parent 的 `pluginManagement` 中，
   没有被声明进本项目的 `<build><plugins>`，因此 integration-test 阶段根本不执行。

证据：基线那次 `mvn verify` 产出 122 个 surefire 报告，其中**没有**这两个类，
且 `server/target/failsafe-reports/` 目录不存在。

**修法**：改名为 `ToolMapperTest` / `AppToolRelMapperTest`（文件名 + 类名）。
选改名而非绑定 failsafe，是因为项目里另外 17 个连库测试**全部**叫 `*Test`——
再引入一层「IT = 集成测试」的分层，名不副实，白多一块构建配置。

> 性质上这与上一轮的「观测列 INSERT 漏写三列致功能整轮空转」是同型问题：
> **看着有覆盖，实际是零**。铺 CI 的全部意义就是确保测试真的在跑，
> 同时放着两个静默跳过的类自相矛盾。

## 5. 实现方案

### 5.1 `.github/workflows/ci.yml`

单文件，两个**并行** job，互不依赖：

**job `backend`**
- `runs-on: ubuntu-latest`
- `actions/checkout@v4`
- `actions/setup-java@v4`：temurin / Java 21 / `cache: maven`
- `mvn -B -f server/pom.xml verify`
- Docker 由 runner 自带，Testcontainers 自动探测；无需任何额外配置或 secret

**job `frontend`**
- `runs-on: ubuntu-latest`，`working-directory: web`
- `actions/checkout@v4`
- `pnpm/action-setup@v4`（版本取自 `package.json` 的 `packageManager` 字段）
- `actions/setup-node@v4`：Node 24 / `cache: pnpm`
- `pnpm install --frozen-lockfile`
- `pnpm typecheck` → `pnpm lint:check` → `pnpm test`

**触发**：`on: push`（所有分支）+ `workflow_dispatch`。

预期耗时：backend 首次 5–8 分钟（下 Maven 依赖 + 拉 `pgvector/pgvector:pg16` 镜像），
带缓存 3–5 分钟；frontend 1–2 分钟。

### 5.2 `web/package.json`

新增 `"lint:check": "eslint ."`。现有 `lint` 是 `eslint . --fix`，**会改文件**，
CI 里用它等于让流水线篡改源码后再判断，必须用不带 `--fix` 的版本。

### 5.3 文档

- `docs/architecture/testing-standards.md` 新增「六、CI（GitHub Actions）」一节：
  跑什么 / 不跑什么、本地与 CI 的环境差异（见 5.4）、红了怎么排查。
- `README.md` 加 CI badge。

### 5.4 本地与 CI 的环境差异（必须记录）

本地 WSL2 + Docker Desktop 需要两个绕行配置才能跑 Testcontainers
（见 testing-standards 第四节）：`~/.testcontainers.properties` 的 `docker.host`、
`~/.docker-java.properties` 的 `api.version=1.54`。

这两个文件住在**家目录、不在仓库**，所以 CI 不受影响，也不需要它们——
GH runner 上 Testcontainers 自动探测即可。

反向的坑更重要：**绝不能把 `api.version` 固化进仓库**。它锁死的是本机 Docker Desktop
的 API 版本，一旦进仓库就会与 runner 的 Docker 版本冲突，把「本地能过」变成「云上必挂」。

## 6. DoD —— 三把反做假的尺子

本轮最容易自欺的失败模式是：**YAML 语法正确、绿灯亮了、但其实没跑测试**。
所以验收不看绿灯，看实录。

1. **数字对得上**：从 GitHub Actions 日志核对 backend job 输出
   `Tests run: 730`（728 + 改名后新跑起来的 2 个）、frontend job 输出 `Tests 413 passed`。
   绿灯本身不构成证据。
2. **故意搞红一次**：临时改坏一个断言并 push，确认 CI **变红**且收到告警邮件，再改回。
   这是唯一能证明它不是装饰的手段——同本文档「故意搞错用法」的那把尺子。
3. **改名的两个类真的出现在 surefire 报告里**，且总数从 728 升到 730。
   第 3 条尤其要盯：**从未跑过的测试转红是常态**，如果它们一次就绿，
   反而要怀疑是不是又没被 include 到，须回到报告逐个核名。

## 7. 明确不做

- **E2E 上 CI**——留下一轮。一条旅程要同时编排 PG + 后端 jar(e2e profile) + 前端 +
  假 LLM 桩 + mcp-demo 五个进程还要 reset-db，是本轮量级的数倍，塞进来会让轮次失控。
- 分支保护 / PR 流程（工作流改变，当前不划算）
- 覆盖率门槛（容易变成刷数字，且当前没有基线共识）
- `make ci`（已拍板只要云端一处）
- 多 OS / 多 JDK 矩阵、缓存配置之外的性能调优

## 8. 影响文件清单

| 文件 | 动作 |
|---|---|
| `.github/workflows/ci.yml` | 新增 |
| `web/package.json` | 加 `lint:check` 脚本 |
| `server/src/test/java/com/hify/tool/mapper/ToolMapperIT.java` | 改名 → `ToolMapperTest.java`（含类名） |
| `server/src/test/java/com/hify/app/mapper/AppToolRelMapperIT.java` | 改名 → `AppToolRelMapperTest.java`（含类名） |
| `docs/architecture/testing-standards.md` | 新增第六节 |
| `README.md` | 加 CI badge |
