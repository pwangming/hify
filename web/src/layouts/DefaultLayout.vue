<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useUserStore } from '@/stores/user'
import { buildMenu } from '@/router/menu'

// 后台主框架：左侧导航 + 顶栏（用户名/退出）+ 内容插槽。
// 内容用 <slot/>（不内置 RouterView）：由 App.vue 按路由选布局后塞入 <RouterView/>，
// 这样登录页可换 BlankLayout（见后续守卫/登录步骤）。
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { user } = storeToRefs(userStore)

// 菜单由路由表 + 当前角色派生：路由加一条带 meta.menu 的记录即自动出现，并按 meta.roles 过滤。
const menuItems = computed(() => buildMenu(router.options.routes, user.value?.role))

function handleLogout() {
  userStore.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="layout__aside">
      <div class="layout__logo">Hify</div>
      <el-menu router :default-active="route.path">
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          {{ item.title }}
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout__header">
        <div class="layout__user">
          <span class="layout__username">{{ user?.username }}</span>
          <el-button text data-test="logout" @click="handleLogout">退出</el-button>
        </div>
      </el-header>
      <el-main class="layout__main">
        <slot />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped lang="scss">
.layout {
  height: 100vh;

  &__aside {
    border-right: 1px solid $color-border;
    background: $color-bg-card;
  }

  &__logo {
    display: flex;
    align-items: center;
    height: 56px;
    padding: 0 $spacing-xl;
    font-size: $font-size-lg;
    font-weight: 600;
    color: $color-text-primary;
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    height: 56px;
    padding: 0 $spacing-xl;
    border-bottom: 1px solid $color-border;
    background: $color-bg-card;
  }

  &__user {
    display: flex;
    align-items: center;
    gap: $spacing-md;
  }

  &__username {
    color: $color-text-regular;
    font-size: $font-size-sm;
  }

  &__main {
    padding: $spacing-xl;
    background: $color-bg-page;
  }
}
</style>
