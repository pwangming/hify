# 画布 C3 运行调试 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 画布内跑通「运行→输入表单→节点徽章→输入输出查看→最终输出」调试闭环（spec：`docs/superpowers/specs/2026-07-12-workflow-canvas-c3-design.md`）。

**Architecture:** 纯前端，零后端改动（run API W1 已备齐）。核心是 `useWorkflowRun` 状态机（dirty 先自动保存→POST runs→nodeRunMap 映射→图改即清空），结果经 provide/inject 进 CanvasNode 徽章、经 props 进抽屉「运行」tab 与工具栏状态条。

**Tech Stack:** Vue 3 `<script setup>` + TypeScript + Element Plus + Vue Flow + vitest（TDD，测试先行）。

## Global Constraints

- 写前端代码前必读 `docs/architecture/frontend-standards.md`；组件一律 Element Plus（含 icons-vue），禁止重造。
- TDD：每个 Task 先写失败测试再实现；测试放同级 `__tests__/`。
- 后端 DTO 的 Long 一律是字符串（api-standards）：`RunResponse.id / elapsedMs / NodeRunView.id` 等前端类型全用 `string`。
- 本轮**不改 server/ 任何文件**；最后回归跑 `mvn verify` 仅为确认没碰坏（surefire 报告判定，不 grep BUILD SUCCESS）。
- jsdom 两坑（C2 留账）：el-tag/el-tooltip 等 transition 根组件交互测试加 `global.stubs: { transition: false }`；聚焦用 `@focusin` 不用 `@focus`。
- 计划文件是执行账本：每个 Task 完成即勾选该 Task 全部 checkbox 并随代码一起提交；只许勾选，不许改计划内容。**最后的回归 Task（Task 10）也必须执行并勾选**。
- 前端命令全部在 `web/` 目录下执行；单文件测试：`pnpm test <相对路径>`。

---

### Task 1: 运行相关类型 + 专用超时 + runWorkflow API

**Files:**
- Modify: `web/src/types/workflow.ts`（文件末尾追加运行类型；`StartInputDecl` 加 `required`）
- Modify: `web/src/config/index.ts`（加 `workflowRunTimeoutMs`）
- Modify: `web/src/api/workflow.ts`（加 `runWorkflow`）
- Test: `web/src/api/__tests__/workflow.spec.ts`（追加用例）

**Interfaces:**
- Produces: `RunStatus`（`'running'|'succeeded'|'failed'|'skipped'`）、`NodeRunView`、`RunResponse`（见下方完整定义，后续所有 Task 依赖）；`StartInputDecl.required?: boolean`；`runWorkflow(appId: string, inputs: Record<string, unknown>): Promise<RunResponse>`；`config.workflowRunTimeoutMs = 300_000`。

- [x] **Step 1: 写失败测试**——`web/src/api/__tests__/workflow.spec.ts` 顶部 import 加 `runWorkflow` 与 `config`，describe 内追加：

```ts
// 顶部 import 区追加：
import { getDraft, runWorkflow, saveDraft } from '@/api/workflow'
import { config } from '@/config'

// describe('workflow api') 内追加：
  it('runWorkflow → POST /workflow/apps/{appId}/runs + inputs 信封 + 专用长超时', () => {
    runWorkflow('42', { city: '北京' })
    expect(request.post).toHaveBeenCalledWith(
      '/workflow/apps/42/runs',
      { inputs: { city: '北京' } },
      { timeout: config.workflowRunTimeoutMs },
    )
  })
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/api/__tests__/workflow.spec.ts`
Expected: FAIL（`runWorkflow` 未导出）

- [x] **Step 3: 实现**

`web/src/types/workflow.ts`——`StartInputDecl` 改为：

```ts
/** start 节点输入声明项。required 供运行前校验（后端 checkRequiredInputs 只认 required=true）。 */
export interface StartInputDecl {
  name: string
  required?: boolean
}
```

文件末尾追加（注释里写清 Long→string 口径）：

```ts
/** 运行/节点状态（对齐后端 RunStatus；同步执行，响应内只会出现三个终态）。 */
export type RunStatus = 'running' | 'succeeded' | 'failed' | 'skipped'

/** 节点运行记录（对齐后端 NodeRunView；Long 一律序列化为字符串，故 id/elapsedMs 是 string）。 */
export interface NodeRunView {
  id: string
  nodeId: string
  nodeType: string
  status: RunStatus
  inputs: Record<string, unknown> | null
  outputs: Record<string, unknown> | null
  errorMessage: string | null
  elapsedMs: string | null
  createTime: string
}

/** 一次运行的完整视图（对齐后端 RunResponse）。运行失败是 HTTP 200 + status=failed（W1 拍板）。 */
export interface RunResponse {
  id: string
  status: RunStatus
  inputs: Record<string, unknown> | null
  outputs: Record<string, unknown> | null
  errorMessage: string | null
  elapsedMs: string | null
  createTime: string
  nodeRuns: NodeRunView[]
}
```

`web/src/config/index.ts`——`llmTestTimeoutMs` 下一行加：

```ts
  // workflow 调试运行可串多个 LLM 节点，比单次试连接更久；前端超时须 ≥ 后端预算（同 llmTestTimeoutMs 教训）。
  workflowRunTimeoutMs: 300_000,
```

`web/src/api/workflow.ts`——import 区改为并追加函数：

```ts
import { request } from '@/api/request'
import { config } from '@/config'
import type { DraftResponse, GraphDef, RunResponse } from '@/types/workflow'
```

```ts
/** 同步触发一次运行，跑完才返回（含 nodeRuns 明细）。后端：POST .../runs */
export function runWorkflow(appId: string, inputs: Record<string, unknown>) {
  return request.post<RunResponse>(`${BASE}/${appId}/runs`, { inputs }, {
    timeout: config.workflowRunTimeoutMs,
  })
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/api/__tests__/workflow.spec.ts`
Expected: PASS（原有 2 例 + 新 1 例）

- [x] **Step 5: Commit**

```bash
git add web/src/types/workflow.ts web/src/config/index.ts web/src/api/workflow.ts web/src/api/__tests__/workflow.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c3-run-debug.md
git commit -m "feat(web/workflow): 运行类型+专用超时+runWorkflow API"
```

---

### Task 2: StartForm 补「必填」勾选

**Files:**
- Modify: `web/src/views/workflow/components/forms/StartForm.vue`
- Test: `web/src/views/workflow/components/forms/__tests__/StartForm.spec.ts`（追加用例）

**Interfaces:**
- Consumes: `StartInputDecl.required?: boolean`（Task 1）。
- Produces: StartForm 每行多一个 `data-test="start-input-required"` 的 el-checkbox；`update` 事件的 patch 里 inputs 行保留/写入 `required`。改名字不再丢 required（现状 `updateRow` 只写 `{ name }` 会丢字段，必须改成合并补丁）。

- [x] **Step 1: 写失败测试**——`StartForm.spec.ts` 追加（沿该文件既有 mount 写法；若已有本地 mount 辅助则复用）：

```ts
  it('勾选必填 → 写回 required=true 且保留名字', async () => {
    const w = mount(StartForm, {
      props: { data: { inputs: [{ name: 'city' }] }, disabled: false },
      global: { plugins: [ElementPlus] },
    })
    await w.find('[data-test="start-input-required"] input').setValue(true)
    expect(w.emitted('update')![0][0]).toEqual({ inputs: [{ name: 'city', required: true }] })
  })

  it('改名字 → 保留已有 required 标记', async () => {
    const w = mount(StartForm, {
      props: { data: { inputs: [{ name: 'city', required: true }] }, disabled: false },
      global: { plugins: [ElementPlus] },
    })
    await w.find('[data-test="start-input-name"] input').setValue('q')
    expect(w.emitted('update')![0][0]).toEqual({ inputs: [{ name: 'q', required: true }] })
  })
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/forms/__tests__/StartForm.spec.ts`
Expected: FAIL（找不到 `start-input-required`；改名字用例丢 required）

- [x] **Step 3: 实现**——`StartForm.vue` 的 `updateRow` 改为合并补丁，模板每行加勾选：

```ts
function updateRow(i: number, patch: Partial<StartInputDecl>) {
  emit('update', { inputs: rows().map((r, idx) => (idx === i ? { ...r, ...patch } : r)) })
}
```

模板中名字输入的写回改为 `@update:model-value="updateRow(i, { name: $event })"`；
删除按钮前插入（el-checkbox 的 update 值类型宽，收窄成 boolean）：

```html
<el-checkbox
  data-test="start-input-required"
  :model-value="row.required ?? false"
  label="必填"
  @update:model-value="updateRow(i, { required: $event === true })"
/>
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/forms/__tests__/StartForm.spec.ts`
Expected: PASS（既有用例 + 新 2 例全绿——既有「改名字」用例若断言了 `{ name }` 整行替换，按合并语义更新断言）

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/forms/StartForm.vue web/src/views/workflow/components/forms/__tests__/StartForm.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c3-run-debug.md
git commit -m "feat(web/workflow): StartForm 输入声明补必填勾选"
```

---

### Task 3: useWorkflowRun 运行状态机（核心）

**Files:**
- Create: `web/src/views/workflow/composables/useWorkflowRun.ts`
- Test: `web/src/views/workflow/composables/__tests__/useWorkflowRun.spec.ts`

**Interfaces:**
- Consumes: `runWorkflow`（Task 1）、`RunResponse / NodeRunView`（Task 1）。
- Produces:
  - `useWorkflowRun(appId: string, deps: { dirty: Readonly<Ref<boolean>>; canSave: Readonly<Ref<boolean>>; save: () => Promise<void> })` 返回 `{ running: Ref<boolean>, lastRun: Ref<RunResponse | null>, nodeRunMap: ComputedRef<Record<string, NodeRunView>>, lastInputs: Ref<Record<string, string>>, triggerRun(inputs: Record<string, string>): Promise<void> }`
  - `NODE_RUNS_KEY: InjectionKey<ComputedRef<Record<string, NodeRunView>>>`（CanvasNode 注入用）

- [x] **Step 1: 写失败测试**——`useWorkflowRun.spec.ts` 全文：

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { computed, nextTick, ref } from 'vue'
import { runWorkflow } from '@/api/workflow'
import { useWorkflowRun } from '@/views/workflow/composables/useWorkflowRun'
import type { NodeRunView, RunResponse } from '@/types/workflow'

vi.mock('@/api/workflow', () => ({ runWorkflow: vi.fn() }))

function nodeRun(nodeId: string, status: NodeRunView['status']): NodeRunView {
  return {
    id: '1', nodeId, nodeType: 'llm', status, inputs: {}, outputs: { text: 'hi' },
    errorMessage: null, elapsedMs: '3100', createTime: '2026-07-12T10:00:00+08:00',
  }
}
const RUN: RunResponse = {
  id: '9', status: 'succeeded', inputs: {}, outputs: { answer: 'ok' },
  errorMessage: null, elapsedMs: '3200', createTime: '2026-07-12T10:00:00+08:00',
  nodeRuns: [nodeRun('start', 'succeeded'), nodeRun('llm_1', 'succeeded')],
}

function setup(dirtyInit = false, canSave = true) {
  const dirty = ref(dirtyInit)
  const save = vi.fn(async () => { dirty.value = false })
  const run = useWorkflowRun('42', { dirty, canSave: computed(() => canSave), save })
  return { dirty, save, run }
}

describe('useWorkflowRun', () => {
  beforeEach(() => vi.clearAllMocks())

  it('非 dirty 直接运行：不调 save，结果写入 lastRun 与 nodeRunMap，记住 lastInputs', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN)
    const { save, run } = setup(false)
    await run.triggerRun({ city: '北京' })
    expect(save).not.toHaveBeenCalled()
    expect(runWorkflow).toHaveBeenCalledWith('42', { city: '北京' })
    expect(run.lastRun.value?.status).toBe('succeeded')
    expect(run.nodeRunMap.value['llm_1'].outputs).toEqual({ text: 'hi' })
    expect(run.lastInputs.value).toEqual({ city: '北京' })
  })

  it('dirty → 先自动保存再运行', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN)
    const { save, run } = setup(true)
    await run.triggerRun({})
    expect(save).toHaveBeenCalledTimes(1)
    // 保存在前、运行在后
    expect(save.mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(runWorkflow).mock.invocationCallOrder[0])
    expect(run.lastRun.value).not.toBeNull()
  })

  it('保存失败 → 中止运行（不发请求），running 复位', async () => {
    const dirty = ref(true)
    const save = vi.fn(async () => { throw new Error('10004') })
    const run = useWorkflowRun('42', { dirty, canSave: computed(() => true), save })
    await run.triggerRun({})
    expect(runWorkflow).not.toHaveBeenCalled()
    expect(run.running.value).toBe(false)
    expect(run.lastRun.value).toBeNull()
  })

  it('canSave=false（非 owner）且 dirty → 跳过保存直接运行，结果可见', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN)
    const { save, run } = setup(true, false)
    await run.triggerRun({})
    expect(save).not.toHaveBeenCalled()
    expect(run.lastRun.value?.status).toBe('succeeded')
  })

  it('HTTP 错误（运行未发生）→ 保留上一次结果', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN)
    const { run } = setup(false)
    await run.triggerRun({})
    vi.mocked(runWorkflow).mockRejectedValue(new Error('18001'))
    await run.triggerRun({})
    expect(run.lastRun.value?.id).toBe('9') // 旧结果还在
    expect(run.running.value).toBe(false)
  })

  it('图一改（dirty 置真）→ 结果清空', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN)
    const { dirty, run } = setup(false)
    await run.triggerRun({})
    expect(run.lastRun.value).not.toBeNull()
    dirty.value = true
    await nextTick()
    expect(run.lastRun.value).toBeNull()
    expect(run.nodeRunMap.value).toEqual({})
  })

  it('运行期间改图 → 响应到达后丢弃展示（结果映在新图上会误导）', async () => {
    const { dirty, run } = setup(false)
    let resolveRun!: (r: RunResponse) => void
    vi.mocked(runWorkflow).mockReturnValue(new Promise((r) => { resolveRun = r }) as never)
    const p = run.triggerRun({})
    expect(run.running.value).toBe(true)
    dirty.value = true // 运行中改图
    await nextTick()
    resolveRun(RUN)
    await p
    expect(run.lastRun.value).toBeNull()
  })
})
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/composables/__tests__/useWorkflowRun.spec.ts`
Expected: FAIL（模块不存在）

- [x] **Step 3: 实现**——`useWorkflowRun.ts` 全文：

```ts
import { computed, ref, watch, type ComputedRef, type InjectionKey, type Ref } from 'vue'
import { runWorkflow } from '@/api/workflow'
import type { NodeRunView, RunResponse } from '@/types/workflow'

/** CanvasNode 由 Vue Flow 实例化、无法直接传 props，节点运行映射走 provide/inject。 */
export const NODE_RUNS_KEY: InjectionKey<ComputedRef<Record<string, NodeRunView>>> =
  Symbol('wfNodeRuns')

/**
 * 运行调试状态机（spec §3/§4）：触发（dirty 先自动保存，「所见即所跑」）→ POST runs →
 * 结果映射；图一改（dirty）即清空——陈旧结果映在新图上误导排障。
 * HTTP 错误 = 运行未发生：保留旧结果（拦截器已 toast）；HTTP 200 + failed 是正常调试结果。
 */
export function useWorkflowRun(
  appId: string,
  deps: { dirty: Readonly<Ref<boolean>>; canSave: Readonly<Ref<boolean>>; save: () => Promise<void> },
) {
  const running = ref(false)
  const lastRun = ref<RunResponse | null>(null)
  /** 会话内记住上次运行输入，重跑预填。 */
  const lastInputs = ref<Record<string, string>>({})

  const nodeRunMap = computed<Record<string, NodeRunView>>(() =>
    Object.fromEntries((lastRun.value?.nodeRuns ?? []).map((nr) => [nr.nodeId, nr])),
  )

  // 世代号：图变一次 +1，在途运行的响应对不上号就丢弃（运行期间改图的边缘情况，spec §7 记账）
  let generation = 0
  watch(deps.dirty, (d) => {
    if (d) {
      lastRun.value = null
      generation++
    }
  })

  async function triggerRun(inputs: Record<string, string>) {
    running.value = true
    try {
      // 非 owner 只读（canSave=false）：不可能改配置，位置级 dirty 不影响执行语义，跳过保存
      if (deps.dirty.value && deps.canSave.value) await deps.save()
      lastInputs.value = inputs
      const gen = generation
      const resp = await runWorkflow(appId, inputs)
      if (gen === generation) lastRun.value = resp
    } catch {
      /* 保存失败中止运行 / HTTP 错误运行未发生：拦截器已 toast，这里只保状态干净 */
    } finally {
      running.value = false
    }
  }

  return { running, lastRun, nodeRunMap, lastInputs, triggerRun }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/composables/__tests__/useWorkflowRun.spec.ts`
Expected: PASS（7 例全绿）

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/composables/useWorkflowRun.ts web/src/views/workflow/composables/__tests__/useWorkflowRun.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c3-run-debug.md
git commit -m "feat(web/workflow): useWorkflowRun 运行状态机（自动保存/结果映射/dirty清空）"
```

---

### Task 4: RunInputsDialog 运行输入弹窗

**Files:**
- Create: `web/src/views/workflow/components/RunInputsDialog.vue`
- Test: `web/src/views/workflow/components/__tests__/RunInputsDialog.spec.ts`

**Interfaces:**
- Consumes: `StartInputDecl`（Task 1）。
- Produces: props `{ visible: boolean; decls: StartInputDecl[]; initial: Record<string, string> }`，emits `update:visible: [boolean]`、`submit: [Record<string, string>]`。必填项空值提交被拦并显示行内错误；每次打开按 decls 重建模型并用 initial 预填。

- [x] **Step 1: 写失败测试**——`RunInputsDialog.spec.ts` 全文（el-dialog 沿 NodeConfigDrawer 的本地 stub 手法）：

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'
import RunInputsDialog from '@/views/workflow/components/RunInputsDialog.vue'
import type { StartInputDecl } from '@/types/workflow'

const DialogStub = {
  name: 'ElDialog',
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<div v-if="modelValue" data-test="dialog"><slot /><div data-test="dialog-footer"><slot name="footer" /></div></div>',
}

const DECLS: StartInputDecl[] = [{ name: 'city', required: true }, { name: 'note' }]

function mountDialog(initial: Record<string, string> = {}) {
  return mount(RunInputsDialog, {
    props: { visible: true, decls: DECLS, initial },
    global: { plugins: [ElementPlus], stubs: { ElDialog: DialogStub, transition: false } },
  })
}

describe('RunInputsDialog', () => {
  it('按声明渲染输入项，预填上次值', async () => {
    const w = mountDialog({ city: '北京' })
    await nextTick()
    expect((w.find('[data-test="run-input-city"] input').element as HTMLInputElement).value).toBe('北京')
    expect(w.find('[data-test="run-input-note"] input').exists()).toBe(true)
  })

  it('必填项为空提交 → 拦截并行内报错，不发 submit', async () => {
    const w = mountDialog()
    await nextTick()
    await w.find('[data-test="run-submit"]').trigger('click')
    expect(w.emitted('submit')).toBeUndefined()
    expect(w.text()).toContain('必填项不能为空')
  })

  it('填齐必填提交 → emit submit(values)；非必填可空', async () => {
    const w = mountDialog()
    await nextTick()
    await w.find('[data-test="run-input-city"] input').setValue('上海')
    await w.find('[data-test="run-submit"]').trigger('click')
    expect(w.emitted('submit')![0][0]).toEqual({ city: '上海', note: '' })
  })

  it('取消 → update:visible false', async () => {
    const w = mountDialog()
    await nextTick()
    await w.find('[data-test="run-cancel"]').trigger('click')
    expect(w.emitted('update:visible')![0]).toEqual([false])
  })
})
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/__tests__/RunInputsDialog.spec.ts`
Expected: FAIL（组件不存在）

- [x] **Step 3: 实现**——`RunInputsDialog.vue` 全文：

```vue
<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { StartInputDecl } from '@/types/workflow'

const props = defineProps<{
  visible: boolean
  decls: StartInputDecl[]
  initial: Record<string, string>
}>()
const emit = defineEmits<{ 'update:visible': [v: boolean]; submit: [values: Record<string, string>] }>()

const model = reactive<Record<string, string>>({})
const missing = ref<string[]>([]) // 校验未过的必填字段名

// 每次打开按声明重建模型：上次输入预填（调试高频重跑），声明变了以声明为准
watch(
  () => props.visible,
  (v) => {
    if (!v) return
    missing.value = []
    Object.keys(model).forEach((k) => delete model[k])
    for (const d of props.decls) model[d.name] = props.initial[d.name] ?? ''
  },
  { immediate: true },
)

function onSubmit() {
  missing.value = props.decls
    .filter((d) => d.required && model[d.name].trim() === '')
    .map((d) => d.name)
  if (missing.value.length > 0) return
  emit('submit', { ...model })
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="运行输入"
    width="480"
    @update:model-value="emit('update:visible', $event)"
  >
    <el-form label-position="top" @submit.prevent>
      <el-form-item
        v-for="d in decls"
        :key="d.name"
        :label="d.name"
        :required="d.required ?? false"
        :error="missing.includes(d.name) ? '必填项不能为空' : ''"
      >
        <el-input v-model="model[d.name]" :data-test="`run-input-${d.name}`" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button data-test="run-cancel" @click="emit('update:visible', false)">取消</el-button>
      <el-button data-test="run-submit" type="primary" @click="onSubmit">运行</el-button>
    </template>
  </el-dialog>
</template>
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/__tests__/RunInputsDialog.spec.ts`
Expected: PASS（4 例全绿）

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/RunInputsDialog.vue web/src/views/workflow/components/__tests__/RunInputsDialog.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c3-run-debug.md
git commit -m "feat(web/workflow): 运行输入弹窗（必填校验+预填上次输入）"
```

---

### Task 5: RunStatusChip 工具栏状态条

**Files:**
- Create: `web/src/views/workflow/components/RunStatusChip.vue`
- Test: `web/src/views/workflow/components/__tests__/RunStatusChip.spec.ts`

**Interfaces:**
- Consumes: `RunResponse`（Task 1）。
- Produces: props `{ run: RunResponse | null }`。null 不渲染；succeeded 绿 tag「成功 X.Xs」、failed 红 tag「失败」；点击 popover 看最终输出 JSON / 整体错误。

- [x] **Step 1: 写失败测试**——`RunStatusChip.spec.ts` 全文（el-popover 本地 stub：reference/default 两 slot 都直出）：

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RunStatusChip from '@/views/workflow/components/RunStatusChip.vue'
import type { RunResponse } from '@/types/workflow'

// popover 的显隐由 Element Plus 负责（不测库）：stub 成 reference+content 常显，只测我们的内容
const PopoverStub = {
  name: 'ElPopover',
  template:
    '<div><slot name="reference" /><div data-test="chip-popover"><slot /></div></div>',
}

const BASE: RunResponse = {
  id: '9', status: 'succeeded', inputs: {}, outputs: { answer: '42' },
  errorMessage: null, elapsedMs: '3210', createTime: '2026-07-12T10:00:00+08:00', nodeRuns: [],
}

function mountChip(run: RunResponse | null) {
  return mount(RunStatusChip, {
    props: { run },
    global: { plugins: [ElementPlus], stubs: { ElPopover: PopoverStub, transition: false } },
  })
}

describe('RunStatusChip', () => {
  it('run 为 null 不渲染', () => {
    expect(mountChip(null).find('[data-test="run-chip"]').exists()).toBe(false)
  })

  it('成功：绿 tag 含耗时，popover 展示最终输出 JSON', () => {
    const w = mountChip(BASE)
    const chip = w.find('[data-test="run-chip"]')
    expect(chip.text()).toContain('成功')
    expect(chip.text()).toContain('3.2s')
    expect(w.find('[data-test="chip-popover"]').text()).toContain('"answer": "42"')
  })

  it('失败：红 tag，popover 展示整体错误信息', () => {
    const w = mountChip({ ...BASE, status: 'failed', outputs: null, errorMessage: '节点 llm_1 失败：模型不存在' })
    expect(w.find('[data-test="run-chip"]').text()).toContain('失败')
    expect(w.find('[data-test="chip-popover"]').text()).toContain('模型不存在')
  })
})
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/__tests__/RunStatusChip.spec.ts`
Expected: FAIL（组件不存在）

- [x] **Step 3: 实现**——`RunStatusChip.vue` 全文：

```vue
<script setup lang="ts">
import { computed } from 'vue'
import type { RunResponse } from '@/types/workflow'

const props = defineProps<{ run: RunResponse | null }>()

const ok = computed(() => props.run?.status === 'succeeded')
const elapsed = computed(() =>
  props.run?.elapsedMs != null ? `${(Number(props.run.elapsedMs) / 1000).toFixed(1)}s` : '',
)
const outputsJson = computed(() => JSON.stringify(props.run?.outputs ?? {}, null, 2))
</script>

<template>
  <el-popover v-if="run" placement="bottom-end" :width="360" trigger="click">
    <template #reference>
      <el-tag
        data-test="run-chip"
        class="run-chip"
        :type="ok ? 'success' : 'danger'"
        effect="light"
      >
        {{ ok ? `成功 ${elapsed}` : '失败' }}
      </el-tag>
    </template>
    <template v-if="ok">
      <div class="run-chip__title">最终输出</div>
      <pre class="run-chip__json" data-test="run-outputs">{{ outputsJson }}</pre>
    </template>
    <template v-else>
      <div class="run-chip__title">运行失败</div>
      <div class="run-chip__error" data-test="run-error">{{ run.errorMessage }}</div>
    </template>
  </el-popover>
</template>

<style scoped lang="scss">
.run-chip {
  cursor: pointer;
}
.run-chip__title {
  font-weight: 600;
  margin-bottom: $spacing-sm;
}
.run-chip__json {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.run-chip__error {
  color: var(--el-color-danger);
  font-size: 13px;
  word-break: break-all;
}
</style>
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/__tests__/RunStatusChip.spec.ts`
Expected: PASS（3 例全绿）

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/RunStatusChip.vue web/src/views/workflow/components/__tests__/RunStatusChip.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c3-run-debug.md
git commit -m "feat(web/workflow): 工具栏运行状态条（成功耗时/失败+popover 详情）"
```

---

### Task 6: CanvasNode 运行状态徽章

**Files:**
- Modify: `web/src/views/workflow/components/CanvasNode.vue`
- Test: `web/src/views/workflow/components/__tests__/CanvasNode.spec.ts`（追加用例）

**Interfaces:**
- Consumes: `NODE_RUNS_KEY`（Task 3）、`NodeRunView`（Task 1）。
- Produces: 注入 `NODE_RUNS_KEY` 后按本节点 status 渲染 `data-test="node-run-badge"` 徽章（class `canvas-node__run--succeeded/failed/skipped`）；无注入或本节点无记录则不渲染（既有测试零改动）。

- [x] **Step 1: 写失败测试**——`CanvasNode.spec.ts` 追加（沿该文件既有 mount 写法，provide 用 `global.provide`；`NODE_RUNS_KEY` 是 Symbol，provide 键直接用它）：

```ts
// 顶部追加 import：
import { computed } from 'vue'
import { NODE_RUNS_KEY } from '@/views/workflow/composables/useWorkflowRun'
import type { NodeRunView } from '@/types/workflow'

// describe 内追加：
  function nodeRun(status: NodeRunView['status']): NodeRunView {
    return {
      id: '1', nodeId: 'llm_1', nodeType: 'llm', status, inputs: {}, outputs: {},
      errorMessage: null, elapsedMs: '10', createTime: '2026-07-12T10:00:00+08:00',
    }
  }
  function mountWithRun(status: NodeRunView['status']) {
    return mount(CanvasNode, {
      props: { id: 'llm_1', type: 'llm', data: { modelId: '3', userPrompt: 'x' } },
      global: {
        plugins: [ElementPlus],
        provide: { [NODE_RUNS_KEY as symbol]: computed(() => ({ llm_1: nodeRun(status) })) },
      },
    })
  }

  it.each([['succeeded'], ['failed'], ['skipped']] as const)(
    '有本节点运行记录（%s）→ 渲染对应状态徽章',
    (status) => {
      const w = mountWithRun(status)
      const badge = w.find('[data-test="node-run-badge"]')
      expect(badge.exists()).toBe(true)
      expect(badge.classes()).toContain(`canvas-node__run--${status}`)
    },
  )

  it('无运行记录（未注入）→ 不渲染徽章', () => {
    const w = mount(CanvasNode, {
      props: { id: 'llm_1', type: 'llm', data: { modelId: '3', userPrompt: 'x' } },
      global: { plugins: [ElementPlus] },
    })
    expect(w.find('[data-test="node-run-badge"]').exists()).toBe(false)
  })
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/__tests__/CanvasNode.spec.ts`
Expected: FAIL（无徽章）

- [x] **Step 3: 实现**——`CanvasNode.vue`：

script 追加：

```ts
import { inject } from 'vue'
import { CircleCheckFilled, CircleCloseFilled, RemoveFilled } from '@element-plus/icons-vue'
import { NODE_RUNS_KEY } from '../composables/useWorkflowRun'
import type { NodeRunView } from '@/types/workflow'

// 运行状态徽章：编辑器 provide 的 nodeId→NodeRunView 映射；未注入（独立测试/复用场景）安全回落空
const nodeRuns = inject(NODE_RUNS_KEY, computed(() => ({}) as Record<string, NodeRunView>))
const runStatus = computed(() => nodeRuns.value[props.id]?.status ?? null)
const RUN_ICONS = {
  succeeded: CircleCheckFilled,
  failed: CircleCloseFilled,
  skipped: RemoveFilled,
  running: RemoveFilled, // 同步执行不会出现，仅为映射完备
} as const
```

template 在未配齐警示徽章后追加（左上角，避开右上角的 warn）：

```html
<span
  v-if="runStatus"
  class="canvas-node__run"
  :class="`canvas-node__run--${runStatus}`"
  data-test="node-run-badge"
>
  <el-icon><component :is="RUN_ICONS[runStatus]" /></el-icon>
</span>
```

style 追加：

```scss
.canvas-node__run {
  position: absolute;
  top: -8px;
  left: -8px;
  font-size: 16px;
  line-height: 1;
  background: var(--el-bg-color);
  border-radius: 50%;
}
.canvas-node__run--succeeded {
  color: var(--el-color-success);
}
.canvas-node__run--failed {
  color: var(--el-color-danger);
}
.canvas-node__run--skipped {
  color: var(--el-text-color-placeholder);
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/__tests__/CanvasNode.spec.ts`
Expected: PASS（既有用例 + 新 4 例全绿）

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/CanvasNode.vue web/src/views/workflow/components/__tests__/CanvasNode.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c3-run-debug.md
git commit -m "feat(web/workflow): 画布节点运行状态徽章（成功/失败/跳过）"
```

---

### Task 7: NodeRunPanel 节点运行详情

**Files:**
- Create: `web/src/views/workflow/components/NodeRunPanel.vue`
- Test: `web/src/views/workflow/components/__tests__/NodeRunPanel.spec.ts`

**Interfaces:**
- Consumes: `NodeRunView`（Task 1）。
- Produces: props `{ nodeRun: NodeRunView }`。succeeded：输入/输出 JSON + 耗时；failed：额外红色错误块；skipped：仅空态文案「未命中分支，已跳过」。

- [x] **Step 1: 写失败测试**——`NodeRunPanel.spec.ts` 全文：

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import NodeRunPanel from '@/views/workflow/components/NodeRunPanel.vue'
import type { NodeRunView } from '@/types/workflow'

function nodeRun(over: Partial<NodeRunView> = {}): NodeRunView {
  return {
    id: '1', nodeId: 'llm_1', nodeType: 'llm', status: 'succeeded',
    inputs: { prompt: '你好' }, outputs: { text: '答案' },
    errorMessage: null, elapsedMs: '1234', createTime: '2026-07-12T10:00:00+08:00',
    ...over,
  }
}

function mountPanel(nr: NodeRunView) {
  return mount(NodeRunPanel, { props: { nodeRun: nr }, global: { plugins: [ElementPlus] } })
}

describe('NodeRunPanel', () => {
  it('成功：展示输入/输出 JSON 与耗时', () => {
    const w = mountPanel(nodeRun())
    expect(w.find('[data-test="node-run-inputs"]').text()).toContain('"prompt": "你好"')
    expect(w.find('[data-test="node-run-outputs"]').text()).toContain('"text": "答案"')
    expect(w.text()).toContain('1.2s')
    expect(w.find('[data-test="node-run-error"]').exists()).toBe(false)
  })

  it('失败：额外展示错误信息', () => {
    const w = mountPanel(nodeRun({ status: 'failed', outputs: null, errorMessage: '模型不存在' }))
    expect(w.find('[data-test="node-run-error"]').text()).toContain('模型不存在')
  })

  it('skipped：仅空态文案，不渲染输入输出', () => {
    const w = mountPanel(nodeRun({ status: 'skipped', inputs: null, outputs: null, elapsedMs: '0' }))
    expect(w.text()).toContain('未命中分支，已跳过')
    expect(w.find('[data-test="node-run-inputs"]').exists()).toBe(false)
  })
})
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/__tests__/NodeRunPanel.spec.ts`
Expected: FAIL（组件不存在）

- [x] **Step 3: 实现**——`NodeRunPanel.vue` 全文：

```vue
<script setup lang="ts">
import { computed } from 'vue'
import type { NodeRunView } from '@/types/workflow'

const props = defineProps<{ nodeRun: NodeRunView }>()

const STATUS_LABEL: Record<string, string> = {
  succeeded: '成功',
  failed: '失败',
  skipped: '已跳过',
  running: '运行中',
}
const elapsed = computed(() =>
  props.nodeRun.elapsedMs != null ? `${(Number(props.nodeRun.elapsedMs) / 1000).toFixed(1)}s` : '-',
)
const fmt = (v: Record<string, unknown> | null) => JSON.stringify(v ?? {}, null, 2)
</script>

<template>
  <div class="node-run" data-test="node-run-panel">
    <template v-if="nodeRun.status === 'skipped'">
      <el-empty description="未命中分支，已跳过" :image-size="48" />
    </template>
    <template v-else>
      <div class="node-run__meta">
        <el-tag :type="nodeRun.status === 'succeeded' ? 'success' : 'danger'" effect="light">
          {{ STATUS_LABEL[nodeRun.status] ?? nodeRun.status }}
        </el-tag>
        <span class="node-run__elapsed">耗时 {{ elapsed }}</span>
      </div>
      <div v-if="nodeRun.errorMessage" class="node-run__error" data-test="node-run-error">
        {{ nodeRun.errorMessage }}
      </div>
      <div class="node-run__title">输入</div>
      <pre class="node-run__json" data-test="node-run-inputs">{{ fmt(nodeRun.inputs) }}</pre>
      <div class="node-run__title">输出</div>
      <pre class="node-run__json" data-test="node-run-outputs">{{ fmt(nodeRun.outputs) }}</pre>
    </template>
  </div>
</template>

<style scoped lang="scss">
.node-run__meta {
  display: flex;
  align-items: center;
  gap: $spacing-md;
  margin-bottom: $spacing-md;
}
.node-run__elapsed {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.node-run__error {
  margin-bottom: $spacing-md;
  padding: $spacing-sm;
  border-radius: 4px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  font-size: 13px;
  word-break: break-all;
}
.node-run__title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: $spacing-xs;
}
.node-run__json {
  margin: 0 0 $spacing-md;
  padding: $spacing-sm;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
```

（若项目 SCSS 变量无 `$spacing-xs`，用 `$spacing-sm` 替代——以 `web/src/styles` 现有变量为准，不新增变量。）

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/__tests__/NodeRunPanel.spec.ts`
Expected: PASS（3 例全绿）

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/NodeRunPanel.vue web/src/views/workflow/components/__tests__/NodeRunPanel.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c3-run-debug.md
git commit -m "feat(web/workflow): 节点运行详情面板（输入/输出/错误/skipped 空态）"
```

---

### Task 8: NodeConfigDrawer 加「配置 / 运行」双 tab

**Files:**
- Modify: `web/src/views/workflow/components/NodeConfigDrawer.vue`
- Test: `web/src/views/workflow/components/__tests__/NodeConfigDrawer.spec.ts`（追加用例）

**Interfaces:**
- Consumes: `NodeRunPanel`（Task 7）、`NodeRunView`（Task 1）。
- Produces: 新增可选 prop `nodeRun?: NodeRunView | null`（默认 null，既有调用零破坏）。nodeRun 为 null：行为与现状完全一致（无 tabs）；有 nodeRun：el-tabs「配置/运行」，打开与切换节点时默认落「运行」tab。

- [x] **Step 1: 写失败测试**——`NodeConfigDrawer.spec.ts` 追加：

```ts
// 顶部追加 import：
import type { NodeRunView } from '@/types/workflow'

// describe 内追加：
  const NODE_RUN: NodeRunView = {
    id: '1', nodeId: 'if_1', nodeType: 'condition', status: 'succeeded',
    inputs: { left: '3' }, outputs: { hit: true },
    errorMessage: null, elapsedMs: '5', createTime: '2026-07-12T10:00:00+08:00',
  }

  it('无 nodeRun：不渲染 tabs（现状不变）', async () => {
    const w = mountDrawer(NODES[2])
    await flushPromises()
    expect(w.find('[data-test="drawer-tabs"]').exists()).toBe(false)
    expect(w.find('[data-test="form-condition"]').exists()).toBe(true)
  })

  it('有 nodeRun：渲染双 tab 且默认落「运行」，运行面板拿到记录', async () => {
    const w = mount(NodeConfigDrawer, {
      props: { node: NODES[2], nodes: NODES, edges: EDGES, canEdit: true, nodeRun: NODE_RUN },
      global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub, transition: false } },
    })
    await flushPromises()
    expect(w.find('[data-test="drawer-tabs"]').exists()).toBe(true)
    expect(w.find('[data-test="node-run-panel"]').exists()).toBe(true)
    expect(w.find('[data-test="node-run-outputs"]').text()).toContain('"hit": true')
  })

  it('有 nodeRun 时切到「配置」tab 仍可编辑表单', async () => {
    const w = mount(NodeConfigDrawer, {
      props: { node: NODES[2], nodes: NODES, edges: EDGES, canEdit: true, nodeRun: NODE_RUN },
      global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub, transition: false } },
    })
    await flushPromises()
    // el-tabs 的页签按钮带 aria-controls；直接点第一个页签（配置）
    await w.findAll('.el-tabs__item')[0].trigger('click')
    expect(w.find('[data-test="form-condition"]').exists()).toBe(true)
  })
```

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/components/__tests__/NodeConfigDrawer.spec.ts`
Expected: FAIL（无 tabs / 无运行面板）

- [x] **Step 3: 实现**——`NodeConfigDrawer.vue`：

script 改动：

```ts
// import 区追加：
import { watch } from 'vue'
import type { NodeRunView } from '@/types/workflow'
import NodeRunPanel from './NodeRunPanel.vue'

// props 加 nodeRun（可选，既有调用零破坏）：
const props = withDefaults(
  defineProps<{
    node: FlowNode | null
    nodes: FlowNode[]
    edges: FlowEdge[]
    canEdit: boolean
    nodeRun?: NodeRunView | null
  }>(),
  { nodeRun: null },
)

// tab 状态：有运行记录时默认落「运行」（spec §2.2）；切换节点/记录时重置
const activeTab = ref<'config' | 'run'>('config')
watch(
  () => [props.node?.id, props.nodeRun] as const,
  () => {
    activeTab.value = props.nodeRun ? 'run' : 'config'
  },
  { immediate: true },
)
```

template：`<template v-if="node">` 内改为——无 nodeRun 保持原结构；有 nodeRun 包 el-tabs（表单与变量面板整体挪进「配置」pane，属性一字不动）：

```html
<template v-if="node">
  <el-tabs v-if="nodeRun" v-model="activeTab" data-test="drawer-tabs">
    <el-tab-pane label="配置" name="config">
      <component
        :is="FORMS[node.type]"
        :key="node.id"
        ref="formRef"
        :data="node.data"
        :disabled="!canEdit"
        @update="onUpdate"
      />
      <VariablePanel v-if="showVars" :vars="vars" :disabled="!canEdit" @insert="onInsert" />
    </el-tab-pane>
    <el-tab-pane label="运行" name="run">
      <NodeRunPanel :node-run="nodeRun" />
    </el-tab-pane>
  </el-tabs>
  <template v-else>
    <component
      :is="FORMS[node.type]"
      :key="node.id"
      ref="formRef"
      :data="node.data"
      :disabled="!canEdit"
      @update="onUpdate"
    />
    <VariablePanel v-if="showVars" :vars="vars" :disabled="!canEdit" @insert="onInsert" />
  </template>
</template>
```

（`ref` 已在 script 中：确认 `import { computed, ref, watch, type Component } from 'vue'`。）

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/components/__tests__/NodeConfigDrawer.spec.ts`
Expected: PASS（既有 6 例 + 新 3 例全绿）

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/components/NodeConfigDrawer.vue web/src/views/workflow/components/__tests__/NodeConfigDrawer.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c3-run-debug.md
git commit -m "feat(web/workflow): 节点抽屉加配置/运行双 tab"
```

---

### Task 9: WorkflowEditor 接线（运行按钮+弹窗+状态条+provide）

**Files:**
- Modify: `web/src/views/workflow/WorkflowEditor.vue`
- Test: `web/src/views/workflow/__tests__/WorkflowEditor.spec.ts`（改 mock 工厂 + 追加用例）

**Interfaces:**
- Consumes: `useWorkflowRun / NODE_RUNS_KEY`（Task 3）、`RunInputsDialog`（Task 4）、`RunStatusChip`（Task 5）、抽屉 `nodeRun` prop（Task 8）、`StartNodeData`（既有）。
- Produces: 工具栏 `data-test="wf-run"` 运行按钮（全员可用，运行权限团队共享）；运行中保存按钮禁用；`provide(NODE_RUNS_KEY, run.nodeRunMap)`。

- [x] **Step 1: 写失败测试**——`WorkflowEditor.spec.ts`：

mock 工厂加 `runWorkflow`（vi.mock 提升，工厂内直接加）：

```ts
vi.mock('@/api/workflow', () => ({ getDraft: vi.fn(), saveDraft: vi.fn(), runWorkflow: vi.fn() }))
```

顶部 import 追加：

```ts
import { getDraft, runWorkflow, saveDraft } from '@/api/workflow'
import type { DraftResponse, RunResponse } from '@/types/workflow'
```

describe 内追加常量与用例：

```ts
  const RUN_OK: RunResponse = {
    id: '9', status: 'succeeded', inputs: {}, outputs: { answer: 'ok' },
    errorMessage: null, elapsedMs: '3200', createTime: '2026-07-12T10:00:00+08:00',
    nodeRuns: [{
      id: '1', nodeId: 'start', nodeType: 'start', status: 'succeeded', inputs: {}, outputs: {},
      errorMessage: null, elapsedMs: '0', createTime: '2026-07-12T10:00:00+08:00',
    }],
  }

  it('start 无声明输入：点运行 → 不弹窗直接触发，完成后状态条出现、抽屉能拿到 nodeRun', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN_OK)
    const w = mountEditor()
    await flushPromises()
    await w.find('[data-test="wf-run"]').trigger('click')
    await flushPromises()
    expect(runWorkflow).toHaveBeenCalledWith('42', {})
    expect(w.find('[data-test="run-chip"]').exists()).toBe(true)
    const vf = w.findComponent({ name: 'VueFlow' })
    vf.vm.$emit('node-click', { node: { id: 'start' } })
    await nextTick()
    expect(w.findComponent({ name: 'NodeConfigDrawer' }).props('nodeRun'))
      .toMatchObject({ nodeId: 'start', status: 'succeeded' })
  })

  it('start 声明了输入：点运行 → 先弹输入表单，提交后带值触发', async () => {
    vi.mocked(getDraft).mockResolvedValue({
      ...DRAFT,
      graph: {
        ...DRAFT.graph,
        nodes: [
          { id: 'start', type: 'start', data: { inputs: [{ name: 'city', required: true }] }, position: { x: 80, y: 200 } },
          { id: 'end', type: 'end', data: {}, position: { x: 640, y: 200 } },
        ],
      },
    })
    vi.mocked(runWorkflow).mockResolvedValue(RUN_OK)
    const w = mountEditor()
    await flushPromises()
    await w.find('[data-test="wf-run"]').trigger('click')
    await nextTick()
    expect(runWorkflow).not.toHaveBeenCalled() // 先弹窗，不直接跑
    const dialog = w.findComponent({ name: 'RunInputsDialog' })
    expect(dialog.props('visible')).toBe(true)
    dialog.vm.$emit('submit', { city: '北京' })
    await flushPromises()
    expect(runWorkflow).toHaveBeenCalledWith('42', { city: '北京' })
  })

  it('dirty 时点运行 → 先自动保存再运行', async () => {
    vi.mocked(saveDraft).mockResolvedValue(DRAFT)
    vi.mocked(runWorkflow).mockResolvedValue(RUN_OK)
    const w = mountEditor()
    await flushPromises()
    const drawerC = w.findComponent({ name: 'NodeConfigDrawer' })
    const vf = w.findComponent({ name: 'VueFlow' })
    vf.vm.$emit('node-click', { node: { id: 'end' } })
    await nextTick()
    drawerC.vm.$emit('update', 'end', { outputs: [{ name: 'a', value: '{{start.q}}' }] }) // 制造 dirty
    await nextTick()
    await w.find('[data-test="wf-run"]').trigger('click')
    await flushPromises()
    expect(saveDraft).toHaveBeenCalledTimes(1)
    expect(runWorkflow).toHaveBeenCalledTimes(1)
  })

  it('图再改动 → 状态条清空（结果过期）', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN_OK)
    const w = mountEditor()
    await flushPromises()
    await w.find('[data-test="wf-run"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="run-chip"]').exists()).toBe(true)
    const vf = w.findComponent({ name: 'VueFlow' })
    vf.vm.$emit('node-click', { node: { id: 'end' } })
    await nextTick()
    w.findComponent({ name: 'NodeConfigDrawer' }).vm.$emit('update', 'end', { outputs: [{ name: 'x', value: 'y' }] })
    await flushPromises()
    expect(w.find('[data-test="run-chip"]').exists()).toBe(false)
  })

  it('非 owner 也能运行（运行权限全员）', async () => {
    useUserStore().user = { id: '999', username: 'eve', role: 'member' }
    vi.mocked(runWorkflow).mockResolvedValue(RUN_OK)
    const w = mountEditor()
    await flushPromises()
    expect(w.find('[data-test="wf-run"]').attributes('disabled')).toBeUndefined()
    await w.find('[data-test="wf-run"]').trigger('click')
    await flushPromises()
    expect(runWorkflow).toHaveBeenCalled()
  })
```

`mountEditor` 的 stubs 保持 `{ NodePalette: true, NodeConfigDrawer: true }` 不变——stub 组件也接收 props/emits，`findComponent({ name: 'RunInputsDialog' })` 需要它未被 stub，**不要把 RunInputsDialog / RunStatusChip 加进 stubs**。

- [x] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/workflow/__tests__/WorkflowEditor.spec.ts`
Expected: FAIL（找不到 `wf-run`）

- [x] **Step 3: 实现**——`WorkflowEditor.vue`：

script 追加：

```ts
// import 区追加：
import { provide } from 'vue'
import type { StartNodeData, WorkflowNodeType } from '@/types/workflow'
import RunInputsDialog from './components/RunInputsDialog.vue'
import RunStatusChip from './components/RunStatusChip.vue'
import { NODE_RUNS_KEY, useWorkflowRun } from './composables/useWorkflowRun'

// 追加位置注意：以下整块放在既有 selectedNode/onNodeClick 声明【之后】——
// run 引用 canEdit、selectedNodeRun 引用 selectedId，插得太靠前会 TDZ 报错。
const run = useWorkflowRun(appId, { dirty: graph.dirty, canSave: canEdit, save: graph.save })
provide(NODE_RUNS_KEY, run.nodeRunMap)

const runDialogVisible = ref(false)
/** start 声明的输入（去掉未命名的空行）。 */
const startDecls = computed(() => {
  const start = graph.nodes.value.find((n) => n.id === 'start')
  const data = (start?.data ?? {}) as StartNodeData
  return (data.inputs ?? []).filter((d) => d.name !== '')
})

function onRunClick() {
  if (startDecls.value.length > 0) {
    runDialogVisible.value = true
    return
  }
  run.triggerRun({})
}
function onRunSubmit(values: Record<string, string>) {
  runDialogVisible.value = false
  run.triggerRun(values)
}

/** 选中节点的本次运行记录（无运行/已清空为 null，抽屉退回单表单形态）。 */
const selectedNodeRun = computed(() =>
  selectedId.value ? (run.nodeRunMap.value[selectedId.value] ?? null) : null,
)
```

（注意：`canEdit` 定义在 `run` 之前——保持现有声明顺序即可满足。）

template 工具栏 actions 区改为（保存按钮加 `|| run.running.value` 禁用；运行按钮在保存左侧）：

```html
<div class="wf-editor__actions">
  <span v-if="graph.savedAt.value" class="wf-editor__saved" data-test="wf-saved-at">
    上次保存 {{ formatDateTime(graph.savedAt.value) }}
  </span>
  <RunStatusChip :run="run.lastRun.value" />
  <el-button
    data-test="wf-run"
    :loading="run.running.value"
    @click="onRunClick"
    >运行</el-button
  >
  <el-tooltip :disabled="canEdit" content="仅创建者或管理员可编辑" placement="bottom">
    <span>
      <el-button
        type="primary"
        data-test="wf-save"
        :disabled="!canEdit || run.running.value"
        :loading="graph.saving.value"
        @click="onSave"
        >保存</el-button
      >
    </span>
  </el-tooltip>
</div>
```

NodeConfigDrawer 加 prop、画布区末尾加弹窗：

```html
<NodeConfigDrawer
  :node="selectedNode"
  :nodes="graph.nodes.value"
  :edges="graph.edges.value"
  :can-edit="canEdit"
  :node-run="selectedNodeRun"
  @close="selectedId = null"
  @update="graph.updateNodeData"
/>
<RunInputsDialog
  v-model:visible="runDialogVisible"
  :decls="startDecls"
  :initial="run.lastInputs.value"
  @submit="onRunSubmit"
/>
```

- [x] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/workflow/__tests__/WorkflowEditor.spec.ts`
Expected: PASS（既有 9 例 + 新 5 例全绿）

- [x] **Step 5: Commit**

```bash
git add web/src/views/workflow/WorkflowEditor.vue web/src/views/workflow/__tests__/WorkflowEditor.spec.ts docs/superpowers/plans/2026-07-12-workflow-canvas-c3-run-debug.md
git commit -m "feat(web/workflow): 画布运行调试接线（运行按钮+输入弹窗+状态条+徽章注入）"
```

---

### Task 10: 全量回归（必须执行并勾选，不许只勾不跑）

**Files:**
- 无新文件；全量验证。

- [ ] **Step 1: 前端四件套**

Run（在 `web/`）：
```bash
pnpm test && pnpm typecheck && pnpm lint && pnpm build
```
Expected: 四个命令全部 exit 0。任何一个红都要修复后重跑（修复属于本 Task 范围）。

- [ ] **Step 2: 后端回归（确认零改动没碰坏）**

Run（在 `server/`）：
```bash
mvn verify; echo "EXIT=$?"; grep -h "Tests run" target/surefire-reports/*.txt | grep -v "Failures: 0, Errors: 0" || echo "NO-TEST-FAILURES"
```
Expected: `EXIT=0` 且输出 `NO-TEST-FAILURES`（判定看退出码 + surefire 报告聚合，**不 grep BUILD SUCCESS**）。

- [ ] **Step 3: 确认 server/ 无改动**

Run: `git status --porcelain server/`
Expected: 空输出（本轮零后端改动）。

- [ ] **Step 4: Commit（勾选本 Task 账本）**

```bash
git add docs/superpowers/plans/2026-07-12-workflow-canvas-c3-run-debug.md
git commit -m "chore(web/workflow): C3 全量回归通过（前端四件套+后端 verify）"
```
