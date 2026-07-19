# 前端警告清零 + 防回归守卫 设计

> 2026-07-19 · 状态：已拍板，待实现
> 起点 commit：`9ba6ad6`

## 1. 背景

CI 化小轮落地后发现前端 job 的 CI 日志有 **321080 行**，下载一次要好几分钟，
真出问题时排查会非常痛苦。

诊断过程中修正了一个误判：起初以为是 CI 特有现象，但本地 `pnpm test` 只有 10 行。
用 `npx vitest run --reporter=verbose` 在本地复现出 **321154 行 / 131 条 Vue warn**，
与 CI 的 321080 / 131 吻合。

**结论：警告一直都在，本地 TTY 下的 reporter 把它们折叠了，CI 非 TTY 全打出来。**
所以这不是 CI 的问题，是本地一直看不见的存量问题，且可以在本地闭环修复与验证。

## 2. 根因清单（本地全量精确计数）

| # | 现象 | 条数 | 根源 | 性质 |
|---|---|---|---|---|
| ① | `el-tag` 收到无效 `type=""` | **116**（58 次渲染 × 2 条警告） | `ProviderList.vue:24-27`，`PROTOCOL_TAG.openai = ''` | **生产代码** |
| ② | `injection "Symbol(router)" not found` | 14 | `ProviderList.spec.ts` 挂载时未提供 router | 测试卫生 |
| ③ | `Failed to resolve component: router-link` | 1 | `CallLogList.spec.ts` 未 stub router-link | 测试卫生 |
| ④ | `el-link` 的 `underline` boolean 已弃用 | 9（`ElementPlusError`，不计入 131 条 Vue warn） | `KnowledgeList.vue:146` `:underline="false"` | **生产代码** |

131 条 Vue warn 中 130 条来自 `ProviderList.spec.ts`，1 条来自 `CallLogList.spec.ts`。

**①和④是真的生产代码问题**，在真实浏览器控制台里同样会刷——不只是测试噪音。
所以本轮一半是修生产 bug，不只是"测试卫生"。

### 2.1 两个查证结论

- **①的修法零视觉变化**：Element Plus 的 `el-tag` 基类 `.el-tag` 设的
  `--el-tag-bg-color: var(--el-color-primary-light-9)` 与 `.el-tag--primary` **完全相同**，
  所以 `type=""` 当前渲染出来就是 primary 的样子。改成 `'primary'` 外观不变，
  纯粹是把非法值换成合法值（EP 2.9 的 `type` 校验器只接受
  `primary/success/info/warning/danger`，默认值 `primary`）。
- **④的新 API 是 `underline="never"`**。EP 仍接受 boolean，但会打弃用错误，3.0 移除。

## 3. 方案

### 3.1 修 4 处根因

| 文件 | 改动 |
|---|---|
| `web/src/views/admin/provider/ProviderList.vue` | `PROTOCOL_TAG.openai: '' → 'primary'`，类型 `'' \| 'success'` 收紧为 EP 合法值 |
| `web/src/views/knowledge/KnowledgeList.vue` | `:underline="false"` → `underline="never"` |
| `web/src/views/admin/provider/__tests__/ProviderList.spec.ts` | 挂载时提供 router |
| `web/src/views/admin/usage/__tests__/CallLogList.spec.ts` | stub `router-link` |

### 3.2 守卫 `web/vitest.setup.ts`

挂到 `vite.config.ts` 的 `test.setupFiles`。

- `beforeEach` 劫持 `console.warn` / `console.error`，**只收集**匹配 `[Vue warn]` 或
  `ElementPlusError` 的消息。
- `afterEach` 若收集到消息则让当前测试失败，打印消息全文 + 归属测试名。
- **不在 `console.warn` 里直接 throw**——会被 Vue 内部捕获吞掉，堆栈也会错位；
  收集后在 `afterEach` 断言，失败归属才准确。
- 导出 `allowConsoleMessage(pattern)` 供个别测试显式豁免，白名单显式可见。

**必须窄匹配**：生产代码 `src/api/request.ts:135` 有一处正当的
`console.error('[ApiError] ...')`，一刀切拦所有 `console.error` 会误伤错误路径的测试。

放在 `web/` 根（挨着 `vite.config.ts`）而非 `__tests__/`：它是基建不是测试，
且必须避开 `include: ['src/**/__tests__/**/*.{test,spec}.ts']` 的匹配。

### 3.3 执行顺序：先加守卫，让它红

**这是本轮的关键纪律，不是可选项。**

1. **先加守卫** → 跑 `pnpm test`，**预期红**，且红的正是 `ProviderList.spec` /
   `CallLogList.spec` / `KnowledgeList.spec` 三个文件。
2. 再逐个修 4 处根因 → 转绿。

顺序反过来（先修再加守卫）就无法区分「守卫真的在拦」与「守卫是个永不触发的死码」
——正是上一轮 SSE 失败计量那种空转的成因。**必须留下红灯实录。**

## 4. DoD

- `pnpm test` 全绿，**413 tests 一个不少**（守卫不能靠删测试或改弱断言来满足）
- `npx vitest run --reporter=verbose 2>&1 | grep -c "Vue warn"` == **0**
- verbose 日志行数从 **321154** 降到百行量级
- `pnpm typecheck` / `pnpm lint:check` EXIT=0
- **守卫的红灯实录**（§3.3 第 1 步）必须记录在案
- push 后核对 CI 前端 job 日志行数真的塌下去

## 5. 不做

- 不改 vitest reporter——警告清零后日志自然就小，压日志是藏病征
- 不清理其它类型的 console 输出（当前只有这 4 类）
- 不动 E2E

## 6. 影响文件清单

| 文件 | 动作 |
|---|---|
| `web/vitest.setup.ts` | 新增 |
| `web/vite.config.ts` | 加 `test.setupFiles` |
| `web/src/views/admin/provider/ProviderList.vue` | 改 `PROTOCOL_TAG` |
| `web/src/views/knowledge/KnowledgeList.vue` | 改 `underline` |
| `web/src/views/admin/provider/__tests__/ProviderList.spec.ts` | 提供 router |
| `web/src/views/admin/usage/__tests__/CallLogList.spec.ts` | stub router-link |
| `docs/architecture/frontend-standards.md` | 补测试警告零容忍的规矩 |
