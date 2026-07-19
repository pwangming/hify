# E2E 上 CI 设计

> 2026-07-19 · 状态：已拍板，待实现
> 起点 commit：`5f3083b`

## 1. 背景

CI 化小轮把后端与前端单测接上了 GitHub Actions，但 **E2E 明确留在了本地**——
一条旅程要编排 postgres + 后端(e2e profile) + 假 LLM 桩 + 前端 + mcp-demo，
量级是那一轮的数倍。本轮把这块补上。

E2E 是**唯一能自动抓前后端契约漂移**的手段（见 testing-standards §5.1 与
memory `frontend-testing-strategy`），只在本地手动跑等于经常不跑。

## 2. 现状

| 项 | 值 |
|---|---|
| 旅程数 | 4 条（`smoke` / `golden-journey` KB / `workflow-journey` / `agent-journey`），每文件 1 个 `test()` |
| 并发 | `fullyParallel: false`，`workers: 1`（串行） |
| 重试 | 未配置（= 0） |
| 超时 | 单测 60s，`expect` 15s，后端 webServer 启动 180s |
| trace | `on-first-retry` |
| 编排入口 | `pnpm e2e` = `node e2e/support/reset-db.mjs && playwright test` |

`reset-db.mjs` 做两件事：`docker compose up -d --wait postgres`，
然后两条 `docker compose exec -T postgres psql` 分别 drop/create `hify_e2e`。
**重置必须早于后端启动**（后端启动才跑 Flyway + 种 admin），所以它在 Playwright 进程之外。

## 3. 已拍板的两项决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 触发 | **每次 push 都跑，作为第三个并行 job** | CI 本就是告警式、不阻塞 push，所以"慢"不影响干活；backend/frontend 仍在 ~5 分钟给出快信号。且用户每轮直推 main，"只在 main 跑"约等于"每次都跑"，不构成真实区别 |
| 重试 | **`retries: process.env.CI ? 1 : 0`** | Playwright 把"重试后才过"单独标为 **flaky**、不混进 passed，所以重试不制造假绿；真坏了的代码重试一样红。云上机器性能波动导致的偶发超时不该半夜发告警 |

## 4. 方案

### 4.1 job `e2e`（并入现有 `.github/workflows/ci.yml`）

步骤：

1. `actions/checkout@v4`
2. `actions/setup-java@v4`（temurin 21，`cache: maven`）
3. `pnpm/action-setup@v4`（`package_json_file: web/package.json`）
4. `actions/setup-node@v4`（Node 24，`cache: pnpm`）
5. `pnpm install --frozen-lockfile`（`web/`）
6. `pnpm install --frozen-lockfile`（`mcp-demo/`）—— **最容易漏的一步**
7. `npx playwright install --with-deps chromium`
8. `pnpm e2e`
9. 失败时上传 `playwright-report/` 与 `test-results/`

### 4.2 三个关键取舍

- **postgres 走 docker compose，不用 GH Actions 的 `services:`**。
  `reset-db.mjs` 用的是 `docker compose exec postgres psql`；改用 `services:` 就得重写它。
  runner 自带 docker compose，compose 里 postgres 服务无 profile、凭证走默认值
  （`hify`/`hify`/`hify`）、不需要 `deploy/.env`，**`pnpm e2e` 零改动可用**。
- **不需要 sandbox 容器**。四条旅程都没有代码执行节点（已核）。所以是四进程不是五进程。
- **后端仍用 `mvn spring-boot:run`**。webServer 已这么配；改成预打包 jar 省不掉编译时间，
  白改配置还多一条跨 job 传产物的路径。

### 4.3 `playwright.config.ts` 改动

- `retries: process.env.CI ? 1 : 0`
- 后端 webServer 的 `timeout` **保持 180s 不变**。见 §4.5——编译被挪出了这个窗口。

### 4.4 失败产物

失败时上传 `playwright-report/`（HTML 报告）与 `test-results/`（trace、截图），保留 7 天。
现有 `trace: 'on-first-retry'` 配合 `retries: 1` 正好能抓到 trace——
否则 CI 上红了只剩一行报错，没法查。

### 4.5 后端预编译：把编译挪出 webServer 的超时窗口

在 `pnpm e2e` 之前插一步 `mvn -B -f server/pom.xml -DskipTests compile`。

*为什么*：`webServer` 里的 `mvn spring-boot:run` 在 CI 上要在 180s 窗口内完成
**冷编译 + 启动**。基线显示 Spring 本身只启动 6 秒，绝大部分时间是编译。预编译后：

- 超时窗口只剩纯启动，180s 从"可能不够"变成绰绰有余，**不必靠调大超时来掩盖**；
- **失败归属更准**：编译错误报在"编译"这一步，而不是伪装成
  `webServer timed out`——后者会让人往时序/端口方向瞎查。

## 5. DoD

- e2e job 绿，且从日志核出 **`4 passed`**（不看绿灯看数字）
- **故意搞红一次**（一次性分支，不污染 main），确认 e2e job 真会红、
  且 artifact 里真有 trace 文件
- 本地 `pnpm e2e` 仍然能跑——CI 适配不得破坏本地用法
- **不改任何旅程断言来迁就 CI**。某条旅程在 CI 上过不去时先查环境；
  确认是旅程自身的时序假设不成立才改，并在报告里说明理由

## 6. 本地基线（2026-07-19 实测，实现前）

`pnpm e2e` → **4 passed，测试 44.9s，总墙钟 47.7s，EXIT=0**。

| 旅程 | 耗时 |
|---|---|
| `agent-journey` | 7.2s |
| `golden-journey`（KB） | 9.8s |
| `smoke` | 0.5s |
| `workflow-journey` | 12.8s |

后端 `Started HifyApplication in 6.065 seconds`。

**这个基线有个前提必须记住**：后端是**热编译**（`target/classes` 因先前跑过
`mvn verify` 已存在），所以 47.7s 不含编译。CI 上是冷的，故有 §4.5 的预编译步骤。

**用途**：CI 上红灯时用它区分「旅程本身坏了」与「CI 环境问题」，少猜一层。

## 7. 不做

- 不并行化旅程（`workers` 仍为 1——四条旅程共用一个库，并行会互相踩数据）
- 不做跨浏览器矩阵（沿用 chromium 单浏览器）
- 不做分片加速
- 不纳入 sandbox / nginx 容器
- 不引入 E2E 专用的数据种子机制（沿用每轮全新起库）

## 8. 影响文件清单

| 文件 | 动作 |
|---|---|
| `.github/workflows/ci.yml` | 新增 `e2e` job |
| `web/playwright.config.ts` | CI 重试；按基线调后端启动超时 |
| `docs/architecture/testing-standards.md` | §5 补 CI 编排、§6.2 更新「不跑 E2E」的表述 |
