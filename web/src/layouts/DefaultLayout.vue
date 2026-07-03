<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import {
  ChatDotRound,
  Collection,
  Grid,
  Setting,
  User,
  Brush,
  Tools,
  Fold,
  Expand,
  SwitchButton,
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { buildMenu, buildBreadcrumb } from '@/router/menu'
import { config } from '@/config'

// 后台主框架：深色侧栏（Logo + 图标菜单 + 折叠底栏）+ 顶栏（面包屑 + 用户下拉）+ 内容插槽。
// 内容用 <slot/>，由 App.vue 按路由选布局后塞入 <RouterView/>。
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { user } = storeToRefs(userStore)

// 折叠态仅会话内存保持（不持久化，刷新复位）。
const collapsed = ref(false)

// meta.icon 字符串 → 图标组件。路由表保持纯元数据，组件名解析集中在此。
const iconMap: Record<string, Component> = { ChatDotRound, Collection, Grid, Setting, User, Brush, Tools }

const menuItems = computed(() => buildMenu(router.options.routes, user.value?.role))
const breadcrumb = computed(() => buildBreadcrumb(route))
const avatarText = computed(() => user.value?.username?.charAt(0).toUpperCase() ?? '?')

function handleLogout() {
  userStore.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '220px'" class="layout__aside">
      <div class="layout__brand" :class="{ 'layout__brand--collapsed': collapsed }">
        <span class="layout__logo">{{ collapsed ? 'H' : 'Hify' }}</span>
        <span v-if="!collapsed" class="layout__tagline">AI Agent Platform</span>
      </div>

      <el-menu
        router
        :collapse="collapsed"
        :default-active="route.path"
        class="layout__menu"
      >
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon v-if="item.icon && iconMap[item.icon]">
            <component :is="iconMap[item.icon]" />
          </el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>

      <div class="layout__footer">
        <el-button
          text
          class="layout__collapse"
          data-test="collapse"
          @click="collapsed = !collapsed"
        >
          <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
          <span v-if="!collapsed">收起</span>
        </el-button>
        <span v-if="!collapsed" class="layout__version">v{{ config.appVersion }}</span>
      </div>
    </el-aside>

    <el-container>
      <el-header class="layout__header">
        <el-breadcrumb class="layout__breadcrumb" separator="/">
          <el-breadcrumb-item v-for="(crumb, i) in breadcrumb" :key="i">
            {{ crumb }}
          </el-breadcrumb-item>
        </el-breadcrumb>

        <el-dropdown trigger="click" :teleported="false" @command="handleLogout">
          <span class="layout__user" data-test="user-menu">
            <el-avatar :size="28" class="layout__avatar">{{ avatarText }}</el-avatar>
            <span class="layout__username">{{ user?.username }}</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item data-test="logout" command="logout">
                <el-icon><SwitchButton /></el-icon>
                退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
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
    display: flex;
    flex-direction: column;
    background: $color-bg-dark;
    overflow: hidden;
    transition: width $transition-base $ease-standard;
  }

  &__brand {
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    justify-content: center;
    height: 64px;
    padding: 0 $spacing-xl;

    &--collapsed {
      align-items: center;
      padding: 0;
    }
  }

  &__logo {
    font-size: $font-size-lg;
    font-weight: 700;
    letter-spacing: 0.5px;
    background: linear-gradient(135deg, #6e7bff, #a78bfa);
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
  }

  &__tagline {
    margin-top: 2px;
    font-size: $font-size-xs;
    letter-spacing: 0.5px;
    color: $sidebar-text-muted;
  }

  &__menu {
    flex: 1;
    overflow-y: auto;
  }

  &__footer {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 48px;
    padding: 0 $spacing-md;
    border-top: 1px solid rgba(255, 255, 255, 0.08);
  }

  &__collapse {
    color: $sidebar-text;

    &:hover {
      color: $sidebar-text-active;
      background: transparent;
    }
  }

  &__version {
    font-family: $font-family-mono;
    font-size: $font-size-xs;
    color: $sidebar-text-muted;
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 56px;
    padding: 0 $spacing-xl;
    border-bottom: 1px solid $color-border;
    background: $color-bg-card;
  }

  &__user {
    display: flex;
    align-items: center;
    gap: $spacing-sm;
    cursor: pointer;
    outline: none;
  }

  &__avatar {
    background: $color-primary;
    color: #fff;
    font-size: $font-size-sm;
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

// 深色侧栏内覆盖 Element Plus 菜单（穿透子组件）
:deep(.layout__menu) {
  background: transparent;
  border-right: none;

  .el-menu-item {
    position: relative;
    color: $sidebar-text;

    .el-icon {
      color: inherit;
    }

    &:hover {
      background: $sidebar-hover-bg;
      color: $sidebar-text-active;
    }

    &.is-active {
      color: $sidebar-text-active;
      background: $sidebar-active-bg;

      &::before {
        content: '';
        position: absolute;
        top: 0;
        bottom: 0;
        left: 0;
        width: 3px;
        background: $sidebar-active-bar;
      }
    }
  }
}
</style>
