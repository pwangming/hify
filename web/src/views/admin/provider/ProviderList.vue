<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  listProviders,
  createProvider,
  updateProvider,
  enableProvider,
  disableProvider,
  deleteProvider,
} from '@/api/admin/provider'
import type { Provider, ProviderForm, ProviderType } from '@/types/provider'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const NAME_MAX = 50

// 类型 → 展示标签 / el-tag 颜色
const TYPE_LABEL: Record<ProviderType, string> = {
  openai: 'OpenAI',
  claude: 'Claude',
  gemini: 'Gemini',
  ollama: 'Ollama',
}
const TYPE_TAG: Record<ProviderType, '' | 'success' | 'warning' | 'info'> = {
  openai: '',
  claude: 'success',
  gemini: 'warning',
  ollama: 'info',
}

const providers = ref<Provider[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    providers.value = await listProviders()
  } finally {
    loading.value = false
  }
}
onMounted(load)

/** 危险操作二次确认；取消返回 false。 */
async function confirmDanger(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}

async function onEnable(row: Provider) {
  try {
    await enableProvider(row.id)
    ElMessage.success('已启用')
    await load()
  } catch {
    /* 已由 request 拦截器统一处理（mock 期无网络错误） */
  }
}

async function onDisable(row: Provider) {
  if (!(await confirmDanger(`确定禁用提供商「${row.name}」？`, '禁用确认'))) return
  try {
    await disableProvider(row.id)
    ElMessage.success('已禁用')
    await load()
  } catch {
    /* 已由 request 拦截器统一处理（mock 期无网络错误） */
  }
}

async function onDelete(row: Provider) {
  if (!(await confirmDanger(`确定删除提供商「${row.name}」？此操作不可恢复。`, '删除确认'))) return
  try {
    await deleteProvider(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 已由 request 拦截器统一处理（mock 期无网络错误） */
  }
}

// —— 对话框（新增 / 编辑共用）——
const dialogVisible = ref(false)
const editingId = ref<string | null>(null) // null=新增，否则=编辑该 id
const formRef = ref<FormInstance>()
const form = reactive<ProviderForm>({ name: '', type: 'openai', apiKey: '', baseUrl: '' })

const rules: FormRules<ProviderForm> = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: NAME_MAX, message: `名称不超过 ${NAME_MAX} 个字符`, trigger: 'blur' },
  ],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  baseUrl: [
    { required: true, message: '请输入 Base URL', trigger: 'blur' },
    { pattern: /^https?:\/\//, message: 'Base URL 需以 http:// 或 https:// 开头', trigger: 'blur' },
  ],
}

function openCreate() {
  editingId.value = null
  form.name = ''
  form.type = 'openai'
  form.apiKey = ''
  form.baseUrl = ''
  dialogVisible.value = true
}

function openEdit(row: Provider) {
  editingId.value = row.id
  form.name = row.name
  form.type = row.type
  form.apiKey = '' // 不回显密钥；留空表示不修改
  form.baseUrl = row.baseUrl
  dialogVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 兜底：happy-dom 下 el-form.validate 对空必填会误判通过（见 UserList 同样处理）。
  if (!form.name || form.name.length > NAME_MAX) return
  if (!form.type) return
  if (!/^https?:\/\//.test(form.baseUrl)) return
  // 新增时 apiKey 必填；编辑可空（表示不修改）
  if (editingId.value === null && !form.apiKey) return

  if (editingId.value === null) {
    await createProvider({ ...form })
    ElMessage.success('提供商已创建')
  } else {
    await updateProvider(editingId.value, { ...form })
    ElMessage.success('提供商已更新')
  }
  dialogVisible.value = false
  await load()
}
</script>

<template>
  <div class="provider-list">
    <PageHeader title="模型提供商管理" description="配置模型供应商接入信息">
      <el-button type="primary" data-test="create-open" @click="openCreate">新增提供商</el-button>
    </PageHeader>

    <ContentCard>
      <el-table v-loading="loading" :data="providers" data-test="provider-table">
        <el-table-column prop="name" label="名称" />
        <el-table-column label="类型">
          <template #default="{ row }">
            <el-tag :type="TYPE_TAG[(row as Provider).type]">{{
              TYPE_LABEL[(row as Provider).type]
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="Base URL" />
        <el-table-column label="状态">
          <template #default="{ row }">
            <el-tag :type="(row as Provider).status === 'enabled' ? 'success' : 'info'">
              {{ (row as Provider).status === 'enabled' ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间">
          <template #default="{ row }">{{ formatDateTime((row as Provider).createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <div class="provider-list__ops">
              <el-button
                v-if="(row as Provider).status === 'enabled'"
                :data-test="`disable-${(row as Provider).id}`"
                size="small"
                @click="onDisable(row as Provider)"
                >禁用</el-button
              >
              <el-button
                v-else
                :data-test="`enable-${(row as Provider).id}`"
                size="small"
                type="success"
                @click="onEnable(row as Provider)"
                >启用</el-button
              >
              <el-button
                :data-test="`edit-${(row as Provider).id}`"
                size="small"
                @click="openEdit(row as Provider)"
                >编辑</el-button
              >
              <el-button
                :data-test="`delete-${(row as Provider).id}`"
                size="small"
                type="danger"
                @click="onDelete(row as Provider)"
                >删除</el-button
              >
            </div>
          </template>
        </el-table-column>
      </el-table>
    </ContentCard>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增提供商' : '编辑提供商'"
      width="480"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" data-test="form-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" data-test="form-type">
            <el-option label="OpenAI" value="openai" />
            <el-option label="Claude" value="claude" />
            <el-option label="Gemini" value="gemini" />
            <el-option label="Ollama" value="ollama" />
          </el-select>
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input
            v-model="form.apiKey"
            type="password"
            data-test="form-apikey"
            :placeholder="editingId === null ? '请输入 API Key' : '留空表示不修改'"
          />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" data-test="form-baseurl" placeholder="https://..." />
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
.provider-list__ops {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
}
</style>
