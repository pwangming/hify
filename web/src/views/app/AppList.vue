<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useRouter } from 'vue-router'
import {
  listApps, createApp, updateApp, deleteApp, enableApp, disableApp,
} from '@/api/app'
import { listChatModels } from '@/api/provider'
import { listDatasets } from '@/api/knowledge'
import type { App, AppForm, AppType } from '@/types/app'
import type { ModelOption } from '@/types/model'
import type { Dataset } from '@/types/knowledge'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const NAME_MAX = 50
const DESC_MAX = 200

const userStore = useUserStore()
const router = useRouter()

function openChat(app: App) {
  router.push(`/apps/${app.id}/chat`)
}
function openDesign(app: App) {
  router.push(`/apps/${app.id}/workflow`)
}

const apps = ref<App[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)

/** 团队共享制：仅 owner 或 Admin 可改/删/启停（与后端 10004 双保险）。 */
function canModify(app: App): boolean {
  return userStore.isAdmin || app.ownerId === userStore.user?.id
}

async function load() {
  loading.value = true
  try {
    const res = await listApps({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    apps.value = res.list
    total.value = Number(res.total) // 后端以 string 下发
  } finally {
    loading.value = false
  }
}
onMounted(load)

function onSearch() {
  page.value = 1
  load()
}
function onPageChange(p: number) {
  page.value = p
  load()
}

async function confirmDanger(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}

async function runAction(action: () => Promise<unknown>, successMsg: string) {
  try {
    await action()
    ElMessage.success(successMsg)
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

async function onEnable(row: App) {
  await runAction(() => enableApp(row.id), '已启用')
}
async function onDisable(row: App) {
  if (!(await confirmDanger(`确定停用应用「${row.name}」？`, '停用确认'))) return
  await runAction(() => disableApp(row.id), '已停用')
}
async function onDelete(row: App) {
  if (!(await confirmDanger(`确定删除应用「${row.name}」？此操作不可恢复。`, '删除确认'))) return
  await runAction(() => deleteApp(row.id), '已删除')
}

// —— 创建 / 编辑弹窗（共用）——
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<AppForm>({
  name: '',
  description: '',
  modelId: null,
  config: { systemPrompt: '' },
  datasetIds: [],
})
// 应用类型：创建时可选，编辑时锁定为行的既有类型（后端不支持改型）
const formType = ref<AppType>('chat')

// 模型选择器选项（成员侧「可用」chat 模型），每次打开弹窗刷新一次。
const modelOptions = ref<ModelOption[]>([])
// 编辑时所选模型的展示名（用于该模型已失效、不在可用列表时的兜底展示）。
const editingModelName = ref<string | null>(null)
async function loadModelOptions() {
  try {
    modelOptions.value = await listChatModels()
  } catch {
    /* 失败由 request 拦截器统一 toast；下拉留空，不阻塞建应用 */
  }
}

/** 下拉选项：可用模型 + （编辑态）当前所选但已失效的模型作为禁用项，避免裸露 modelId 数字。 */
const selectOptions = computed(() => {
  const opts = modelOptions.value.map((m) => ({
    value: m.id,
    label: `${m.providerName} / ${m.name}`,
    disabled: false,
  }))
  if (form.modelId && !modelOptions.value.some((m) => m.id === form.modelId)) {
    opts.unshift({
      value: form.modelId,
      label: `${editingModelName.value ?? '已失效模型'}（已停用）`,
      disabled: true,
    })
  }
  return opts
})

// 知识库选项（团队共享全员可见），打开弹窗时刷新；一页 100 够内部团队用
const datasetOptions = ref<Dataset[]>([])
async function loadDatasetOptions() {
  try {
    const res = await listDatasets({ page: 1, size: 100 })
    datasetOptions.value = res.list
  } catch {
    /* 失败由 request 拦截器统一 toast；下拉留空 */
  }
}

/** 下拉选项：现存知识库 + （编辑态）已绑但已删除的库作禁用项，避免裸露 id（同模型下拉手法）。 */
const datasetSelectOptions = computed(() => {
  const opts = datasetOptions.value.map((d) => ({ value: d.id, label: d.name, disabled: false }))
  for (const id of form.datasetIds) {
    if (!datasetOptions.value.some((d) => d.id === id)) {
      opts.unshift({ value: id, label: '已删除的知识库', disabled: true })
    }
  }
  return opts
})

const rules: FormRules<AppForm> = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: NAME_MAX, message: `名称不超过 ${NAME_MAX} 个字符`, trigger: 'blur' },
  ],
}

function openCreate() {
  editingId.value = null
  formType.value = 'chat'
  form.name = ''
  form.description = ''
  form.modelId = null
  editingModelName.value = null
  form.config = { systemPrompt: '' }
  form.datasetIds = []
  dialogVisible.value = true
  loadModelOptions()
  loadDatasetOptions()
}
function openEdit(row: App) {
  editingId.value = row.id
  formType.value = row.type
  form.name = row.name
  form.description = row.description ?? ''
  form.modelId = row.modelId
  editingModelName.value = row.modelName
  form.config = { systemPrompt: row.config.systemPrompt ?? '' }
  form.datasetIds = [...row.datasetIds]
  dialogVisible.value = true
  loadModelOptions()
  loadDatasetOptions()
}

async function submitForm() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 兜底：happy-dom 下 el-form.validate 对空必填会误判通过（同 UserList/ProviderList）。
  if (!form.name || form.name.length > NAME_MAX) return
  if (form.description.length > DESC_MAX) return
  try {
    if (editingId.value === null) {
      await createApp({ ...form }, formType.value)
      ElMessage.success('应用已创建')
    } else {
      await updateApp(editingId.value, { ...form })
      ElMessage.success('应用已更新')
    }
    dialogVisible.value = false
    await load()
  } catch {
    /* 失败（如重名）由 request 拦截器统一 toast；弹窗保持打开 */
  }
}
</script>

<template>
  <div class="app-list">
    <PageHeader title="应用管理" description="团队共享：全员可见，编辑/删除仅创建者与管理员">
      <el-input
        v-model="keyword"
        data-test="search"
        placeholder="搜索应用名"
        clearable
        class="app-list__search"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-button type="primary" data-test="create-open" @click="openCreate">新建应用</el-button>
    </PageHeader>

    <ContentCard>
      <el-table v-loading="loading" :data="apps" data-test="app-table">
        <el-table-column prop="name" label="名称" />
        <el-table-column label="类型">
          <template #default="{ row }">
            <el-tag :type="(row as App).type === 'workflow' ? 'warning' : 'primary'">
              {{ (row as App).type === 'workflow' ? '工作流' : '对话' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="模型">
          <template #default="{ row }">
            <span v-if="(row as App).modelName"
              >{{ (row as App).modelName
              }}<span v-if="!(row as App).modelUsable" class="app-list__muted">（已停用）</span></span
            >
            <span v-else class="app-list__muted">未配置</span>
          </template>
        </el-table-column>
        <el-table-column label="归属">
          <template #default="{ row }">
            <el-tag :type="(row as App).ownerId === userStore.user?.id ? 'success' : 'info'">
              {{ (row as App).ownerId === userStore.user?.id ? '我创建' : '其他成员' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default="{ row }">
            <el-tag :type="(row as App).status === 'enabled' ? 'success' : 'info'">
              {{ (row as App).status === 'enabled' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间">
          <template #default="{ row }">{{ formatDateTime((row as App).createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="320">
          <template #default="{ row }">
            <div class="app-list__ops">
              <el-button
                v-if="(row as App).type === 'chat'"
                size="small"
                type="primary"
                :data-test="`chat-${(row as App).id}`"
                :disabled="!(row as App).modelUsable || (row as App).status === 'disabled'"
                @click="openChat(row as App)"
                >试聊</el-button
              >
              <el-button
                v-else
                size="small"
                type="primary"
                :data-test="`design-${(row as App).id}`"
                @click="openDesign(row as App)"
                >编排</el-button
              >
              <template v-if="canModify(row as App)">
                <el-button
                  v-if="(row as App).status === 'enabled'"
                  :data-test="`disable-${(row as App).id}`"
                  size="small"
                  @click="onDisable(row as App)"
                  >停用</el-button
                >
                <el-button
                  v-else
                  :data-test="`enable-${(row as App).id}`"
                  size="small"
                  type="success"
                  @click="onEnable(row as App)"
                  >启用</el-button
                >
                <el-button
                  :data-test="`edit-${(row as App).id}`"
                  size="small"
                  @click="openEdit(row as App)"
                  >编辑</el-button
                >
                <el-button
                  :data-test="`delete-${(row as App).id}`"
                  size="small"
                  type="danger"
                  @click="onDelete(row as App)"
                  >删除</el-button
                >
              </template>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="app-list__pager"
        layout="prev, pager, next, total"
        :total="total"
        :current-page="page"
        :page-size="size"
        @current-change="onPageChange"
      />
    </ContentCard>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建应用' : '编辑应用'"
      width="520"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="类型">
          <el-radio-group v-if="editingId === null" v-model="formType" data-test="form-type">
            <el-radio value="chat">对话应用</el-radio>
            <el-radio value="workflow">工作流应用</el-radio>
          </el-radio-group>
          <el-tag v-else>{{ formType === 'workflow' ? '工作流应用' : '对话应用' }}</el-tag>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" data-test="form-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" data-test="form-desc" maxlength="200" />
        </el-form-item>
        <el-form-item v-if="formType === 'chat'" label="模型">
          <el-select
            v-model="form.modelId"
            data-test="form-model"
            placeholder="选择对话模型（可选）"
            clearable
            class="app-list__model-select"
          >
            <el-option
              v-for="o in selectOptions"
              :key="o.value"
              :value="o.value"
              :label="o.label"
              :disabled="o.disabled"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="formType === 'chat'" label="关联知识库">
          <el-select
            v-model="form.datasetIds"
            data-test="form-datasets"
            multiple
            clearable
            placeholder="选择知识库（可选）"
            class="app-list__model-select"
          >
            <el-option
              v-for="o in datasetSelectOptions"
              :key="o.value"
              :value="o.value"
              :label="o.label"
              :disabled="o.disabled"
            />
          </el-select>
          <div class="app-list__hint">绑定后，该应用回答会参考所选知识库内容</div>
        </el-form-item>
        <el-form-item v-if="formType === 'chat'" label="系统提示词">
          <el-input
            v-model="form.config.systemPrompt"
            data-test="form-prompt"
            type="textarea"
            :rows="3"
            placeholder="给这个助手设定人设/职责（可选）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" data-test="form-submit" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.app-list__search {
  width: 220px;
}
.app-list__model-select {
  width: 100%;
}
.app-list__muted {
  color: var(--el-text-color-secondary);
}
.app-list__hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
  margin-top: 4px;
}
.app-list__ops {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
}
.app-list__pager {
  margin-top: $spacing-md;
  justify-content: flex-end;
}
</style>
