<script setup lang="ts">
import { useRouter } from 'vue-router'

// 404：路由未匹配（通配兜底路由跳来）。
const router = useRouter()
</script>

<template>
  <el-result icon="info" title="404" sub-title="抱歉，你访问的页面不存在">
    <template #extra>
      <el-button type="primary" data-test="home" @click="router.push('/')">返回首页</el-button>
    </template>
  </el-result>
</template>
