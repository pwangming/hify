<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listTools, getTool, createTool, updateTool, removeTool, enableTool, disableTool, previewTool } from '@/api/admin/tool'
import type { ToolAdminItem, ToolForm, ToolOperation } from '@/types/tool'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const tools = ref<ToolAdminItem[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    tools.value = await listTools()
  } finally {
    loading.value = false
  }
}

onMounted(load)

function isBuiltin(row: ToolAdminItem): boolean {
  return row.source === 'builtin'
}

async function confirmDanger(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}

async function onEnable(row: ToolAdminItem) {
  try {
    await enableTool(row.id)
    ElMessage.success('已启用')
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

async function onDisable(row: ToolAdminItem) {
  if (!(await confirmDanger(`确定停用工具「${row.name}」？`, '停用确认'))) return
  try {
    await disableTool(row.id)
    ElMessage.success('已停用')
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

async function onDelete(row: ToolAdminItem) {
  if (!(await confirmDanger(`确定删除工具「${row.name}」？此操作不可恢复。`, '删除确认'))) return
  try {
    await removeTool(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

// —— 注册 / 编辑抽屉 ——
const drawerVisible = ref(false)
const editingId = ref<string | null>(null)
const submitting = ref(false)
const previewing = ref(false)
const previewOps = ref<ToolOperation[]>([])
const form = reactive<ToolForm>({ name: '', description: '', specText: '', authHeaders: [] })

function resetForm() {
  form.name = ''
  form.description = ''
  form.specText = ''
  form.authHeaders = []
  previewOps.value = []
}

function openCreate() {
  editingId.value = null
  resetForm()
  drawerVisible.value = true
}

async function openEdit(row: ToolAdminItem) {
  editingId.value = row.id
  resetForm()
  drawerVisible.value = true
  try {
    const detail = await getTool(row.id)
    form.name = detail.name
    form.description = detail.description
    form.specText = detail.rawSpec ?? ''
    form.authHeaders = detail.authHeaderNames.map((name) => ({ name, value: '' }))
    previewOps.value = detail.operations
  } catch {
    drawerVisible.value = false
  }
}

function addHeader() {
  form.authHeaders.push({ name: '', value: '' })
}

function removeHeader(i: number) {
  form.authHeaders.splice(i, 1)
}

async function onPreview() {
  if (!form.specText.trim()) {
    ElMessage.warning('请先粘贴 OpenAPI 文档')
    return
  }
  previewing.value = true
  try {
    const result = await previewTool(form.specText)
    previewOps.value = result.operations
    ElMessage.success(`解析成功，共 ${result.operations.length} 个操作`)
  } catch {
    /* 解析失败(13001)由拦截器 toast；抽屉保持打开 */
  } finally {
    previewing.value = false
  }
}

async function submitForm() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入名称')
    return
  }
  if (!form.specText.trim()) {
    ElMessage.warning('请粘贴 OpenAPI 文档')
    return
  }
  submitting.value = true
  try {
    if (editingId.value === null) {
      await createTool({ ...form, authHeaders: [...form.authHeaders] })
      ElMessage.success('工具已注册')
    } else {
      await updateTool(editingId.value, { ...form, authHeaders: [...form.authHeaders] })
      ElMessage.success('工具已更新')
    }
    drawerVisible.value = false
    await load()
  } catch {
    /* 失败(重名/解析)由拦截器 toast；抽屉保持打开让用户改 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="tool-list">
    <PageHeader title="自定义工具" description="注册 OpenAPI 自定义工具，供 Agent 应用调用">
      <el-button type="primary" data-test="create-open" @click="openCreate">注册工具</el-button>
    </PageHeader>

    <ContentCard>
      <el-table v-loading="loading" :data="tools" data-test="tool-table">
        <el-table-column prop="name" label="名称" />
        <el-table-column label="类型">
          <template #default="{ row }">
            <el-tag :type="isBuiltin(row as ToolAdminItem) ? 'info' : 'primary'">
              {{ isBuiltin(row as ToolAdminItem) ? '内置' : '自定义' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="操作数">
          <template #default="{ row }">{{ (row as ToolAdminItem).operationCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default="{ row }">
            <el-tag :type="(row as ToolAdminItem).enabled ? 'success' : 'info'">
              {{ (row as ToolAdminItem).enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="320">
          <template #default="{ row }">
            <div v-if="!isBuiltin(row as ToolAdminItem)" class="tool-list__ops">
              <el-button
                v-if="(row as ToolAdminItem).enabled"
                :data-test="`disable-${(row as ToolAdminItem).id}`"
                size="small"
                @click="onDisable(row as ToolAdminItem)"
              >
                停用
              </el-button>
              <el-button
                v-else
                :data-test="`enable-${(row as ToolAdminItem).id}`"
                size="small"
                type="success"
                @click="onEnable(row as ToolAdminItem)"
              >
                启用
              </el-button>
              <el-button :data-test="`edit-${(row as ToolAdminItem).id}`" size="small" @click="openEdit(row as ToolAdminItem)">
                编辑
              </el-button>
              <el-button
                :data-test="`delete-${(row as ToolAdminItem).id}`"
                size="small"
                type="danger"
                @click="onDelete(row as ToolAdminItem)"
              >
                删除
              </el-button>
            </div>
            <span v-else class="tool-list__builtin-hint">内置工具（只读）</span>
          </template>
        </el-table-column>
      </el-table>
    </ContentCard>

    <el-drawer
      v-model="drawerVisible"
      data-test="tool-drawer"
      :title="editingId === null ? '注册工具' : '编辑工具'"
      size="600px"
    >
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="form.name" data-test="form-name" maxlength="64" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" data-test="form-description" maxlength="500" />
        </el-form-item>
        <el-form-item label="OpenAPI">
          <el-input
            v-model="form.specText"
            data-test="form-spec"
            type="textarea"
            :rows="10"
            placeholder="粘贴 OpenAPI 3.0 文档（JSON 或 YAML）"
          />
        </el-form-item>
        <el-form-item label="鉴权头">
          <div class="tool-list__headers">
            <div v-for="(h, i) in form.authHeaders" :key="i" class="tool-list__header-row">
              <el-input v-model="h.name" placeholder="头名，如 X-API-Key" :data-test="`header-name-${i}`" />
              <el-input
                v-model="h.value"
                :placeholder="editingId === null ? '头值' : '留空=不改'"
                :data-test="`header-value-${i}`"
              />
              <el-button size="small" text type="danger" @click="removeHeader(i)">删除</el-button>
            </div>
            <el-button size="small" data-test="add-header" @click="addHeader">+ 添加请求头</el-button>
          </div>
        </el-form-item>
        <el-form-item>
          <el-button data-test="form-preview" :loading="previewing" @click="onPreview">预览操作</el-button>
        </el-form-item>
        <el-form-item v-if="previewOps.length" label="操作列表">
          <ul class="tool-list__ops-preview">
            <li v-for="op in previewOps" :key="op.opName" :data-test="`operation-${op.opName}`">
              <strong>{{ op.opName }}</strong>
              <el-tag size="small">{{ op.method }}</el-tag>
              <code>{{ op.pathTemplate }}</code>
              <span class="tool-list__op-desc">{{ op.description }}</span>
            </li>
          </ul>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" data-test="form-submit" :loading="submitting" @click="submitForm">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.tool-list__ops {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
}

.tool-list__builtin-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.tool-list__headers {
  width: 100%;
}

.tool-list__header-row {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  margin-bottom: $spacing-sm;
}

.tool-list__ops-preview {
  margin: 0;
  padding-left: 1.2em;

  li {
    line-height: 1.9;
  }

  .tool-list__op-desc {
    color: var(--el-text-color-secondary);
    margin-left: 6px;
  }
}
</style>
