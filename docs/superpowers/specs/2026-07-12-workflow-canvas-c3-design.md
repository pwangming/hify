# Workflow 画布 C3：运行调试（设计）

> 2026-07-12 brainstorm 定稿。画布轮（C1→C2→C3）收官子轮，对应 C1 spec §0 全景中的
> 「C3 运行调试」。**纯前端，零后端改动**——后端 run API（W1）已备齐 C3 所需全部契约。

## 0. 后端契约现状（只读依据，不改）

- `POST /api/v1/workflow/apps/{appId}/runs`：同步触发，body `{ inputs }` 可省；
  返回 `RunResponse`：`id/status/inputs/outputs/errorMessage/elapsedMs/createTime/nodeRuns[]`。
- `NodeRunView`（按执行顺序）：`id/nodeId/nodeType/status/inputs/outputs/errorMessage/elapsedMs/createTime`。
- 节点终态仅三个：`succeeded` / `failed` / `skipped`（同步执行，响应内无 running）。
- 两类失败的既有口径（W1 拍板 #7）：
  - **运行未发生** = HTTP 错误：18001 图不完整、PARAM_INVALID 缺必填输入、429 配额超限、CONFLICT 应用停用；
  - **运行发生但节点挂了** = HTTP 200 + `status=failed` + errorMessage。
- 缺必填输入的拦截：后端 `checkRequiredInputs` 只认 `start.data.inputs[].required == true`
  （C2 表单没暴露该开关，本轮补上，见 §2）。
- 运行权限全员（团队共享制），配额入口检查已有；前端无需权限判断。

## 1. 范围

**做**：
- 工具栏「运行」按钮：dirty 先自动保存 → start 有声明输入弹填写表单 → 同步触发运行
- 节点状态徽章：succeeded 绿✓ / failed 红✗ / skipped 灰（未命中分支）
- 配置抽屉加「配置 / 运行」双 tab：运行 tab 展示该节点本次输入/输出 JSON、错误信息、耗时；
  skipped 节点显示「未命中分支，已跳过」
- 工具栏状态条：「✓ 成功 + 耗时 / ✗ 失败」气泡，点击 popover 看最终输出 JSON / 整体错误
- StartForm 补「必填」开关（写 `data.inputs[].required`）；运行表单对必填项红星 + 非空校验

**不做**（YAGNI 或归后续轮）：
- 运行历史列表页（推迟到发布/对外 API 轮，C1 spec 已拍板）
- 运行取消（同步短跑；axios abort 只是客户端断开、后端照跑，做了也是假取消）
- SSE 逐节点推进动画（后端是同步接口，跑完才返回，无中间事件可推；真需要归 scaling-path 阶段 2）
- E2E workflow 旅程（testing-standards §5.6 既定口径继续推迟，留账）

## 2. 交互拍板（brainstorm 逐项确认）

1. **dirty 时点运行 → 自动保存再运行**：运行读的是库里已保存草稿，不保存会跑旧图而用户
   以为跑的是眼前这张——「所见即所跑」必须成立。保存失败则中止运行并 toast。
2. **结果载体 = 复用配置抽屉加 tab**：C2 已建立「点节点→右侧抽屉」心智，抽屉顶部加
   「配置 / 运行」Tab；有运行结果后点节点默认落在运行 tab，无结果时不渲染运行 tab（只有配置）。
3. **总结果入口 = 工具栏状态条**：运行完成后工具栏出现状态气泡（成功+耗时 / 失败），
   点击 popover 展示最终输出 JSON / 整体 errorMessage；失败时配合红徽章定位问题节点。
   不自动弹抽屉（打扰）、不只靠 toast（瞬时消失无常驻入口）。
4. **StartForm 补 required 开关**：后端已支持 required 拦截，只是 C2 表单没暴露；
   不补则画布声明的输入永远全部可不填，产品缺口。改动小：一行开关 + 运行表单校验。
5. **无声明输入直接跑**：start 未声明任何输入时点运行不弹窗；有声明才弹 el-dialog。
6. **运行表单预填上次输入**：表单值会话内记住（内存），重跑预填——调试场景高频重跑。

## 3. 运行链路细节

- **超时**：workflow 可串多个 LLM 节点，比单次试连接（130s）更长。`config/index.ts` 新增
  `workflowRunTimeoutMs = 300_000`，run 请求单独覆盖 axios timeout。沿用「前端超时 ≥
  后端预算」教训（conversation 同步端点踩过：客户端先断 → 看似失败实际后端还在跑）。
  后端无整轮预算上限（节点各自有超时），300s 对调试跑足够；超长链路超时属可接受边界。
- **HTTP 错误（运行未发生）**：走既有 axios 全局错误拦截器 toast；**保留上一次运行的
  徽章与状态条**（这次根本没跑，旧结果仍是最近一次真实运行）。
- **HTTP 200 + failed（运行发生）**：正常打徽章 + 状态条红 ✗——这正是调试要看的东西，
  不是异常分支。
- **结果生命周期**：结果只存内存，刷新即无（历史查询归发布轮）。**图一有修改（dirty）
  即清空徽章与状态条**——陈旧结果映在新图上误导排障。运行期间不锁画布；若运行中改图，
  响应回来同样因 dirty 直接丢弃展示（接受的边缘情况，此处记账）。
- **运行中 UI**：运行按钮 loading、保存按钮禁用（防跑一半图被换）；画布可看可选节点、
  可开抽屉看配置。

## 4. 前端架构（沿 frontend-standards 目录约定）

```
web/src/
├── types/workflow.ts        # +RunResponse / NodeRunView / RunRequest 类型（对齐后端 DTO，id 类 string）
├── api/workflow.ts          # +runWorkflow(appId, inputs)（timeout: workflowRunTimeoutMs）
├── config/index.ts          # +workflowRunTimeoutMs = 300_000
└── views/workflow/
    ├── WorkflowEditor.vue            # 工具栏加运行按钮+状态条；接线 useWorkflowRun
    ├── components/
    │   ├── RunInputsDialog.vue       # start 输入填写弹窗（必填红星+非空校验+预填上次值）
    │   ├── RunStatusChip.vue         # 状态条+popover（最终输出 JSON / 整体错误）
    │   ├── CanvasNode.vue            # 加状态徽章位（吃 nodeRunMap）
    │   ├── NodeConfigDrawer.vue      # 加「配置/运行」tab
    │   ├── NodeRunPanel.vue          # 运行 tab 内容（输入/输出/错误/耗时；skipped 空态文案）
    │   └── forms/StartForm.vue       # +「必填」开关（data.inputs[].required）
    └── composables/
        └── useWorkflowRun.ts         # 核心状态机（纯逻辑，单测重点）
```

`useWorkflowRun` 对外接口：
- 状态：`running`、`lastRun`（RunResponse | null）、`nodeRunMap`（nodeId→NodeRunView，
  徽章与抽屉运行 tab 共用一份映射）
- 动作：`triggerRun(inputs?)`（内部编排：dirty→先 saveDraft→再 run→写结果）、
  `clearResults()`（dirty 时由编辑器调用清空）

JSON 展示用 `<pre>` + `JSON.stringify(v, null, 2)`；组件全部 Element Plus 拼装
（el-dialog / el-popover / el-tabs / el-switch），不引新依赖。

## 5. 测试策略（TDD，vitest，测试先行）

- **useWorkflowRun 单测**（核心）：dirty→先保存再运行、保存失败中止不发 run、
  非 dirty 直接 run、结果写入 nodeRunMap、clearResults 清空、HTTP 错误保留旧结果、
  running 态翻转。
- **组件测试**：运行按钮 loading/保存按钮禁用联动、RunInputsDialog 必填校验+预填、
  无声明输入不弹窗直接跑、CanvasNode 按 status 渲染三色徽章、NodeConfigDrawer 双 tab
  切换与默认落点、NodeRunPanel 三态（成功/失败/skipped）、RunStatusChip popover 内容、
  StartForm required 开关读写。
- **既有 jsdom 坑复用**（C2 留账）：transition 根组件交互测试加
  `global.stubs: { transition: false }`；聚焦一律 `@focusin`；「测试过了但实现没动」的
  新用例用探针反证。
- **回归门槛**：前端 `pnpm test` / `typecheck` / `build` / `lint` 四件套全绿；
  后端零改动，仍跑一次 `mvn verify`（surefire 报告聚合判定，不 grep BUILD SUCCESS）确认没碰坏。

## 6. DoD（人工验收，全程不出画布）

1. 条件分支图跑「命中/未命中」两个方向：命中路径绿✓、未走分支灰 skipped，徽章正确。
2. 故意配错模型 ID → 运行返回 200 但 failed：失败节点红✗，点开抽屉运行 tab 看到错误
   信息，状态条 ✗ 可见整体错误。
3. start 声明必填输入 → 点运行弹表单，必填空提交被前端拦；填了跑通后 end 节点运行 tab
   与状态条 popover 能看到最终输出；重跑表单预填上次值。
4. 改一下图（dirty）→ 徽章与状态条立即清空；点运行自动保存后跑的是新图。
5. 未配完的草稿点运行 → 18001 toast，不产生新徽章（此前若跑过则旧结果原样保留，口径见 §3）。

## 7. 留账与已知边界

- 运行历史列表页、运行取消、SSE 节点推进动画、E2E：本轮不做（§1 已述）。
- 运行中改图 → 响应回来因 dirty 丢弃展示：接受的边缘情况（同步跑窗口短）。
- 超长链路（>300s）前端超时：可接受边界，真发生时调 `workflowRunTimeoutMs`。
- required 开关只有布尔，无输入类型/默认值声明（string 一把梭）：与后端现状对齐，YAGNI。
