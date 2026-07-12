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
