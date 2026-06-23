<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listUsers } from '@/api/admin/user'
import type { AdminUser } from '@/types/admin-user'
import { useUserStore } from '@/stores/user'

const users = ref<AdminUser[]>([])
const loading = ref(false)
const userStore = useUserStore()

async function load() {
  loading.value = true
  try {
    users.value = await listUsers()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const enabledAdminCount = computed(
  () => users.value.filter((u) => u.role === 'admin' && u.status === 'enabled').length,
)
function isSelf(row: AdminUser): boolean {
  return row.id === userStore.user?.id
}
function isLastEnabledAdmin(row: AdminUser): boolean {
  return row.role === 'admin' && row.status === 'enabled' && enabledAdminCount.value <= 1
}
/** 危险操作（禁用/降级/删除）是否禁用 + 原因；null 表示放行。 */
function dangerDisabledReason(row: AdminUser): string | null {
  if (isSelf(row)) return '不能对自己做此操作'
  if (isLastEnabledAdmin(row)) return '需至少保留一个启用的管理员'
  return null
}

function onDisable(_row: AdminUser) {}
function onEnable(_row: AdminUser) {}
function onChangeRole(_row: AdminUser) {}
function onResetPassword(_row: AdminUser) {}
function onDelete(_row: AdminUser) {}
</script>

<template>
  <div class="user-list">
    <div class="user-list__header">
      <h2>用户管理</h2>
    </div>
    <el-table v-loading="loading" :data="users" data-test="user-table">
      <el-table-column prop="username" label="用户名" />
      <el-table-column label="角色">
        <template #default="{ row }">
          <el-tag :type="row.role === 'admin' ? 'danger' : 'info'">
            {{ row.role === 'admin' ? '管理员' : '成员' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态">
        <template #default="{ row }">
          <el-tag :type="row.status === 'enabled' ? 'success' : 'info'">
            {{ row.status === 'enabled' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" />
      <el-table-column label="操作" width="320">
        <template #default="{ row }">
          <el-tooltip
            v-if="dangerDisabledReason(row) && row.status === 'enabled'"
            :content="dangerDisabledReason(row)!"
          >
            <span>
              <el-button :data-test="`disable-${row.id}`" size="small" disabled>停用</el-button>
            </span>
          </el-tooltip>
          <el-button
            v-else-if="row.status === 'enabled'"
            :data-test="`disable-${row.id}`"
            size="small"
            @click="onDisable(row)"
          >停用</el-button>
          <el-button
            v-else
            :data-test="`enable-${row.id}`"
            size="small"
            type="success"
            @click="onEnable(row)"
          >启用</el-button>

          <el-button
            :data-test="`role-${row.id}`"
            size="small"
            :disabled="!!dangerDisabledReason(row)"
            @click="onChangeRole(row)"
          >{{ row.role === 'admin' ? '降为成员' : '升为管理员' }}</el-button>

          <el-button :data-test="`reset-${row.id}`" size="small" @click="onResetPassword(row)">重置密码</el-button>

          <el-button
            :data-test="`delete-${row.id}`"
            size="small"
            type="danger"
            :disabled="!!dangerDisabledReason(row)"
            @click="onDelete(row)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped lang="scss">
.user-list__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
</style>
