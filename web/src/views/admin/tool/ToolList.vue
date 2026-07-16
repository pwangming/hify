<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listTools, removeTool, enableTool, disableTool, refreshTool } from '@/api/admin/tool'
import type { ToolAdminItem } from '@/types/tool'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'
import ToolDrawer from './components/ToolDrawer.vue'

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

function sourceLabel(row: ToolAdminItem): string {
  if (row.source === 'builtin') return '内置'
  return row.source === 'mcp' ? 'MCP' : '自定义'
}

function sourceTagType(row: ToolAdminItem): 'info' | 'primary' | 'warning' {
  if (row.source === 'builtin') return 'info'
  return row.source === 'mcp' ? 'warning' : 'primary'
}

// 刷新=重新发现 MCP 工具清单（凭据用库中密文），非破坏性动作，无需二次确认
const refreshingId = ref<string | null>(null)

async function onRefresh(row: ToolAdminItem) {
  refreshingId.value = row.id
  try {
    const updated = await refreshTool(row.id)
    ElMessage.success(`已刷新，共 ${updated.operationCount ?? 0} 个工具`)
    await load()
  } catch {
    /* 13002 等由 request 拦截器统一 toast */
  } finally {
    refreshingId.value = null
  }
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

// —— 注册 / 编辑抽屉（表单逻辑在 ToolDrawer 内）——
const drawerVisible = ref(false)
const editingRow = ref<ToolAdminItem | null>(null)

function openCreate() {
  editingRow.value = null
  drawerVisible.value = true
}

function openEdit(row: ToolAdminItem) {
  editingRow.value = row
  drawerVisible.value = true
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
            <el-tag :type="sourceTagType(row as ToolAdminItem)">
              {{ sourceLabel(row as ToolAdminItem) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="操作/工具数">
          <template #default="{ row }">{{ (row as ToolAdminItem).operationCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default="{ row }">
            <el-tag :type="(row as ToolAdminItem).enabled ? 'success' : 'info'">
              {{ (row as ToolAdminItem).enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="380">
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
              <el-button
                v-if="(row as ToolAdminItem).source === 'mcp'"
                :data-test="`refresh-${(row as ToolAdminItem).id}`"
                size="small"
                :loading="refreshingId === (row as ToolAdminItem).id"
                @click="onRefresh(row as ToolAdminItem)"
              >
                刷新
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

    <ToolDrawer v-model="drawerVisible" :editing="editingRow" @saved="load" />
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
</style>
