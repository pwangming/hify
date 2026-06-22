<script setup lang="ts">
import { useRouter } from 'vue-router'

// 403：已登录但角色无权访问（守卫第 ④ 步跳来）。
const router = useRouter()
</script>

<template>
  <el-result icon="warning" title="403" sub-title="抱歉，你没有访问该页面的权限">
    <template #extra>
      <el-button type="primary" data-test="home" @click="router.push('/')">返回首页</el-button>
    </template>
  </el-result>
</template>
