<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { listProviders } from '@/api/admin/provider'
import {
  listModels,
  createModel,
  updateModel,
  deleteModel,
  enableModel,
  disableModel,
} from '@/api/admin/model'
import type { Provider, ProviderProtocol } from '@/types/provider'
import type { AiModel, ModelForm } from '@/types/model'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const NAME_MAX = 50
const KEY_MAX = 100

const PROTOCOL_LABEL: Record<ProviderProtocol, string> = {
  openai: 'OpenAI 兼容',
  anthropic: 'Anthropic',
}

const route = useRoute()
const router = useRouter()
const providerId = String(route.params.id)

const provider = ref<Provider | null>(null)
const models = ref<AiModel[]>([])
const loading = ref(false)

// Anthropic 协议不支持 embedding（后端 12001 兜底）；前端置灰该选项。
const embeddingDisabled = computed(() => provider.value?.protocol === 'anthropic')
const headerTitle = computed(() =>
  provider.value ? `${provider.value.name} · ${PROTOCOL_LABEL[provider.value.protocol]}` : '模型管理',
)

async function loadModels() {
  loading.value = true
  try {
    models.value = await listModels(providerId)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  // 后端这轮无「查单个供应商」端点：拉全量列表按 id 找。
  const all = await listProviders()
  const found = all.find((p) => p.id === providerId)
  if (!found) {
    router.replace('/404')
    return
  }
  provider.value = found
  await loadModels()
})

function goBack() {
  router.push('/admin/provider')
}

/** 危险操作二次确认；取消返回 false。 */
async function confirmDanger(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}

async function onEnable(row: AiModel) {
  try {
    await enableModel(row.id)
    ElMessage.success('已启用')
    await loadModels()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

async function onDisable(row: AiModel) {
  if (!(await confirmDanger(`确定禁用模型「${row.name}」？`, '禁用确认'))) return
  try {
    await disableModel(row.id)
    ElMessage.success('已禁用')
    await loadModels()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

async function onDelete(row: AiModel) {
  if (!(await confirmDanger(`确定删除模型「${row.name}」？此操作不可恢复。`, '删除确认'))) return
  try {
    await deleteModel(row.id)
    ElMessage.success('已删除')
    await loadModels()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

// —— 对话框（新增 / 编辑共用）——
const dialogVisible = ref(false)
const editingId = ref<string | null>(null) // null=新增，否则=编辑该 id
const formRef = ref<FormInstance>()
const form = reactive<ModelForm>({ type: 'chat', name: '', modelKey: '' })

const rules: FormRules<ModelForm> = {
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: NAME_MAX, message: `名称不超过 ${NAME_MAX} 个字符`, trigger: 'blur' },
  ],
  modelKey: [
    { required: true, message: '请输入模型标识', trigger: 'blur' },
    { max: KEY_MAX, message: `模型标识不超过 ${KEY_MAX} 个字符`, trigger: 'blur' },
  ],
}

function openCreate() {
  editingId.value = null
  form.type = 'chat'
  form.name = ''
  form.modelKey = ''
  dialogVisible.value = true
}

function openEdit(row: AiModel) {
  editingId.value = row.id
  form.type = row.type // 编辑时只读展示，不可改
  form.name = row.name
  form.modelKey = row.modelKey
  dialogVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 兜底：happy-dom 下 el-form.validate 对空必填会误判通过（见 ProviderList 同样处理）。
  if (!form.name || form.name.length > NAME_MAX) return
  if (!form.modelKey || form.modelKey.length > KEY_MAX) return

  try {
    if (editingId.value === null) {
      await createModel(providerId, { type: form.type, name: form.name, modelKey: form.modelKey })
      ElMessage.success('模型已创建')
    } else {
      await updateModel(editingId.value, { name: form.name, modelKey: form.modelKey })
      ElMessage.success('模型已更新')
    }
    dialogVisible.value = false
    await loadModels()
  } catch {
    /* 失败（如重名）由 request 拦截器统一 toast；弹窗保持打开让用户改 */
  }
}
</script>

<template>
  <div class="provider-detail">
    <PageHeader :title="headerTitle" description="管理该供应商下的模型">
      <el-button data-test="back" @click="goBack">← 返回</el-button>
      <el-button type="primary" data-test="model-create-open" @click="openCreate"
        >新增模型</el-button
      >
    </PageHeader>

    <ContentCard v-if="provider">
      <el-table v-loading="loading" :data="models" data-test="model-table">
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="modelKey" label="模型标识" />
        <el-table-column label="类型">
          <template #default="{ row }">
            <el-tag>{{ (row as AiModel).type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default="{ row }">
            <el-tag :type="(row as AiModel).status === 'enabled' ? 'success' : 'info'">
              {{ (row as AiModel).status === 'enabled' ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间">
          <template #default="{ row }">{{ formatDateTime((row as AiModel).createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <div class="provider-detail__ops">
              <el-button
                :data-test="`model-edit-${(row as AiModel).id}`"
                size="small"
                @click="openEdit(row as AiModel)"
                >编辑</el-button
              >
              <el-button
                v-if="(row as AiModel).status === 'enabled'"
                :data-test="`model-disable-${(row as AiModel).id}`"
                size="small"
                @click="onDisable(row as AiModel)"
                >禁用</el-button
              >
              <el-button
                v-else
                :data-test="`model-enable-${(row as AiModel).id}`"
                size="small"
                type="success"
                @click="onEnable(row as AiModel)"
                >启用</el-button
              >
              <el-button
                :data-test="`model-delete-${(row as AiModel).id}`"
                size="small"
                type="danger"
                @click="onDelete(row as AiModel)"
                >删除</el-button
              >
            </div>
          </template>
        </el-table-column>
      </el-table>
    </ContentCard>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增模型' : '编辑模型'"
      width="480"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="类型" prop="type">
          <!-- 新增：可选 radio（anthropic 下 embedding 置灰）。编辑：type 不可改，仅只读展示。 -->
          <template v-if="editingId === null">
            <el-radio-group v-model="form.type" data-test="form-type">
              <el-radio value="chat">chat</el-radio>
              <el-radio value="embedding" :disabled="embeddingDisabled">embedding</el-radio>
            </el-radio-group>
            <span v-if="embeddingDisabled" class="provider-detail__hint"
              >该协议不支持 embedding 模型</span
            >
          </template>
          <el-tag v-else data-test="form-type-readonly">{{ form.type }}</el-tag>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" data-test="form-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="模型标识" prop="modelKey">
          <el-input
            v-model="form.modelKey"
            data-test="form-modelkey"
            maxlength="100"
            placeholder="如 gpt-4o"
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
.provider-detail__ops {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
}

.provider-detail__hint {
  margin-left: $spacing-sm;
  font-size: $font-size-sm;
  color: $color-text-secondary;
}
</style>
