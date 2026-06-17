<script setup lang="ts">
import { useRoute } from 'vue-router'

// 临时布局直接写在 App.vue（按本次需求）；后续可抽到 layouts/ 下。
// 菜单项与路由对应，el-menu 的 router 模式按 index(=路径) 自动导航。
const route = useRoute()
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="layout__aside">
      <div class="layout__logo">Hify</div>
      <el-menu router :default-active="route.path">
        <el-menu-item index="/knowledge">知识库管理</el-menu-item>
        <el-menu-item index="/app">应用管理</el-menu-item>
        <el-menu-item index="/admin/provider">模型提供商管理</el-menu-item>
      </el-menu>
    </el-aside>
    <el-main class="layout__main">
      <RouterView />
    </el-main>
  </el-container>
</template>

<style scoped lang="scss">
.layout {
  height: 100vh;

  &__aside {
    border-right: 1px solid var(--el-border-color);
  }

  &__logo {
    height: 56px;
    display: flex;
    align-items: center;
    padding: 0 20px;
    font-size: 18px;
    font-weight: 600;
  }

  &__main {
    background: var(--el-bg-color-page);
  }
}
</style>
