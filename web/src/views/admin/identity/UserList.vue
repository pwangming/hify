<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listUsers } from '@/api/admin/user'
import type { AdminUser } from '@/types/admin-user'

const users = ref<AdminUser[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    users.value = await listUsers()
  } finally {
    loading.value = false
  }
}
onMounted(load)
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
