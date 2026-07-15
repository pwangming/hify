<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listTools, removeTool, enableTool, disableTool } from '@/api/admin/tool'
import type { ToolAdminItem } from '@/types/tool'
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
</script>

<template>
  <div class="tool-list">
    <PageHeader title="自定义工具" description="注册 OpenAPI 自定义工具，供 Agent 应用调用">
      <el-button type="primary" data-test="create-open" disabled>注册工具</el-button>
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
              <el-button :data-test="`edit-${(row as ToolAdminItem).id}`" size="small" disabled>
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
