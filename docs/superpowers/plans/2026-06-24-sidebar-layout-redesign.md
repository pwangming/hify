# 侧边栏与布局壳改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Hify 前端布局壳改造成深色侧边栏 + 清爽顶栏，并立「页面标题区 + 白卡片」骨架约定，落到已有实页。

**Architecture:** 沿用方案 A「开发者工具风」设计 token，新增一组侧栏深色 token；侧栏深色背景靠 `:deep()` 覆盖 Element Plus `el-menu`；菜单图标由路由 `meta.icon` 经布局内 registry 解析；面包屑由路由 `meta.group/title` 经纯函数 `buildBreadcrumb` 派生；抽 `PageHeader`/`ContentCard` 两个复用组件。

**Tech Stack:** Vue 3 + `<script setup lang="ts">` + Element Plus + 原生 SCSS（scoped）+ vitest/@vue/test-utils。

## Global Constraints

- 设计变量集中 `src/styles/variables.scss`，组件**引 scss 变量、不写魔法值**（颜色/间距/圆角/字号）。
- 间距沿用现有 4 倍数尺度：页面边距 `$spacing-xl`(24px)、卡片内边距与元素间距 `$spacing-lg`(16px)。**不新增 20px。**
- 统一 `<script setup lang="ts">`；props 用基于类型的声明；样式 `scoped lang="scss"`；穿透用 `:deep()`，禁 `::v-deep`/`>>>`。
- 图标统一 `@element-plus/icons-vue`。
- 所有 id 为 string；折叠态**不持久化**（local ref，刷新复位）。
- 版本号经 Vite `define` 从 `package.json` 注入 `__APP_VERSION__`，**不硬编码**。
- 保留所有现有 `data-test` 属性与 `共 N 个用户` 文本，确保既有测试不回归。
- 每步小步提交；纯逻辑先写失败测试（TDD）；视觉部分靠人工验收，不写脆弱样式快照。

---

## File Structure

| 文件 | 责任 |
|---|---|
| `web/src/styles/variables.scss` | 新增侧栏深色 token（修改） |
| `web/vite.config.ts` | `define` 注入 `__APP_VERSION__`（修改） |
| `web/env.d.ts` | 声明全局常量 `__APP_VERSION__`（新建） |
| `web/src/config/index.ts` | 导出 `appVersion`（修改） |
| `web/src/types/router.d.ts` | `meta` 加 `icon?` / `group?`（修改） |
| `web/src/router/index.ts` | 菜单路由补 `icon`，admin 路由补 `group`（修改） |
| `web/src/router/menu.ts` | `MenuItem.icon` + `buildMenu` 带出 + 新增纯函数 `buildBreadcrumb`（修改） |
| `web/src/router/__tests__/menu.spec.ts` | 补 icon / breadcrumb 测试（修改） |
| `web/src/components/PageHeader.vue` | 页面标题区组件（新建） |
| `web/src/components/__tests__/PageHeader.spec.ts` | （新建） |
| `web/src/components/ContentCard.vue` | 白卡片容器组件（新建） |
| `web/src/components/__tests__/ContentCard.spec.ts` | （新建） |
| `web/src/layouts/DefaultLayout.vue` | 深色侧栏 + 顶栏（重写） |
| `web/src/layouts/__tests__/DefaultLayout.spec.ts` | 退出改为下拉触发（修改） |
| `web/src/views/admin/identity/UserList.vue` | 套 PageHeader/ContentCard，清魔法值（修改） |
| `web/src/views/admin/provider/ProviderList.vue` | 套 PageHeader/ContentCard（修改） |

所有命令在 `web/` 目录下执行。

---

## Task 1: 设计 token + 版本号注入管线

**Files:**
- Modify: `web/src/styles/variables.scss`
- Modify: `web/vite.config.ts`
- Create: `web/env.d.ts`
- Modify: `web/src/config/index.ts`

**Interfaces:**
- Produces: scss 变量 `$color-bg-dark` / `$sidebar-hover-bg` / `$sidebar-active-bg` / `$sidebar-active-bar` / `$sidebar-text` / `$sidebar-text-active` / `$sidebar-text-muted`；全局常量 `__APP_VERSION__: string`；`config.appVersion: string`。

- [ ] **Step 1: 加侧栏深色 token**

在 `web/src/styles/variables.scss` 末尾（`z-index` 段之后）追加：

```scss
// ============================================================
// 侧边栏深色主题（与浅色内容区形成对比）—— 仅侧栏使用
// ============================================================
$color-bg-dark:        #17181d;                   // 侧栏底色：近黑带一丝冷调，非纯黑
$sidebar-hover-bg:     rgba(255, 255, 255, 0.10); // 菜单 hover 背景
$sidebar-active-bg:    rgba(94, 106, 210, 0.18);  // 选中背景：主色淡染，与 hover 区分
$sidebar-active-bar:   $color-primary;            // 选中态左侧 3px 主色竖线
$sidebar-text:         rgba(255, 255, 255, 0.72); // 菜单默认文字
$sidebar-text-active:  #ffffff;                   // hover / 选中文字（提亮到纯白）
$sidebar-text-muted:   rgba(255, 255, 255, 0.38); // 副标题、版本号
```

- [ ] **Step 2: Vite 注入版本号**

修改 `web/vite.config.ts`。在文件顶部 import 段后加读取 package.json：

```ts
import { readFileSync } from 'node:fs'
```

在 `defineConfig(({ mode }) => {` 内、`const env = loadEnv(...)` 之后加：

```ts
  const pkg = JSON.parse(
    readFileSync(new URL('./package.json', import.meta.url), 'utf-8'),
  ) as { version: string }
```

在返回的配置对象里（与 `plugins`/`resolve` 同级）加一项：

```ts
    define: {
      __APP_VERSION__: JSON.stringify(pkg.version),
    },
```

- [ ] **Step 3: 声明全局常量类型**

新建 `web/env.d.ts`：

```ts
/// <reference types="vite/client" />

// Vite define 注入的编译期常量（见 vite.config.ts）。
declare const __APP_VERSION__: string
```

> `tsconfig.json` 的 `include` 已含 `"env.d.ts"`，无需改 tsconfig。

- [ ] **Step 4: config 导出 appVersion**

修改 `web/src/config/index.ts`，在 `config` 对象里加一行：

```ts
export const config = {
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL,
  apiTimeout: Number(import.meta.env.VITE_API_TIMEOUT),
  appVersion: __APP_VERSION__,
}
```

- [ ] **Step 5: 验证类型与构建不破**

Run: `pnpm exec vue-tsc --noEmit`
Expected: 无错误（exit 0）。

- [ ] **Step 6: Commit**

```bash
git add web/src/styles/variables.scss web/vite.config.ts web/env.d.ts web/src/config/index.ts
git commit -m "前端：新增侧栏深色 token + 版本号经 Vite define 注入"
```

---

## Task 2: 路由 meta 图标/分组 + 菜单与面包屑派生

**Files:**
- Modify: `web/src/types/router.d.ts`
- Modify: `web/src/router/menu.ts`
- Modify: `web/src/router/index.ts`
- Test: `web/src/router/__tests__/menu.spec.ts`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `interface MenuItem { path: string; title: string; icon?: string }`
  - `buildMenu(routes, role): MenuItem[]`（现签名不变，结果多带 `icon`）
  - `buildBreadcrumb(route: { meta: { group?: string; title?: string } }): string[]`
  - `RouteMeta` 新增 `icon?: string`、`group?: string`

- [ ] **Step 1: 写失败测试（menu icon + breadcrumb）**

修改 `web/src/router/__tests__/menu.spec.ts`：
1. 顶部 import 增加 `buildBreadcrumb`：

```ts
import { buildMenu, isRoleAllowed, buildBreadcrumb } from '@/router/menu'
```

2. 在 `describe('buildMenu', ...)` 的 routes 里给 `/knowledge` 那条补 `icon`，并新增一条断言 icon 带出。把 `/knowledge` 行改成：

```ts
    { path: '/knowledge', meta: { menu: true, title: '知识库管理', icon: 'Collection' } },
```

并在「只收 meta.menu...」用例的期望里，把 `/knowledge` 项改为带 icon、其余项保持（buildMenu 对无 icon 的项 `icon` 为 `undefined`）：

```ts
    expect(items).toEqual([
      { path: '/knowledge', title: '知识库管理', icon: 'Collection' },
      { path: '/app', title: '应用管理', icon: undefined },
      { path: '/admin/provider', title: '模型提供商管理', icon: undefined },
    ])
```

3. 文件末尾追加 buildBreadcrumb 用例：

```ts
describe('buildBreadcrumb', () => {
  it('有 group + title → 两级面包屑', () => {
    expect(buildBreadcrumb({ meta: { group: '管理控制台', title: '用户管理' } })).toEqual([
      '管理控制台',
      '用户管理',
    ])
  })

  it('仅 title → 单级', () => {
    expect(buildBreadcrumb({ meta: { title: '知识库管理' } })).toEqual(['知识库管理'])
  })

  it('meta 为空 → 空数组', () => {
    expect(buildBreadcrumb({ meta: {} })).toEqual([])
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `pnpm exec vitest run src/router/__tests__/menu.spec.ts`
Expected: FAIL —— `buildBreadcrumb is not a function`，且 buildMenu 用例因缺 `icon` 字段不匹配。

- [ ] **Step 3: 实现 menu.ts**

修改 `web/src/router/menu.ts`：

1. `MenuItem` 接口加 `icon`：

```ts
export interface MenuItem {
  path: string
  title: string
  icon?: string
}
```

2. `buildMenu` 的 `.map` 带出 icon：

```ts
    .map((route) => ({
      path: route.path,
      title: route.meta?.title ?? route.path,
      icon: route.meta?.icon,
    }))
```

3. 文件末尾新增纯函数（不依赖 vue-router 运行时类型，便于单测）：

```ts
/**
 * 由路由 meta 派生面包屑：meta.group（可选，作第一级）+ meta.title（页面名）。
 * 路由扁平，故最多两级；缺省项不进面包屑。
 */
export function buildBreadcrumb(route: {
  meta: { group?: string; title?: string }
}): string[] {
  const crumbs: string[] = []
  if (route.meta.group) crumbs.push(route.meta.group)
  if (route.meta.title) crumbs.push(route.meta.title)
  return crumbs
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `pnpm exec vitest run src/router/__tests__/menu.spec.ts`
Expected: PASS（全部用例绿）。

- [ ] **Step 5: 扩展 RouteMeta 类型**

修改 `web/src/types/router.d.ts`，在 `interface RouteMeta { ... }` 内补两个字段（放 `menu?` 之后）：

```ts
    /** 侧边菜单图标名（@element-plus/icons-vue 的组件名，如 'Collection'）。 */
    icon?: string
    /** 面包屑第一级分组名（如 '管理控制台'）；缺省则面包屑只显示 title。 */
    group?: string
```

- [ ] **Step 6: 路由表补 icon/group**

修改 `web/src/router/index.ts`，按下表更新各菜单路由的 `meta`（只改 meta，其余不动）：

- `/knowledge`：`meta: { requiresAuth: true, title: '知识库管理', menu: true, icon: 'Collection' }`
- `/app`：`meta: { requiresAuth: true, title: '应用管理', menu: true, icon: 'Grid' }`
- `/admin/provider`：`meta: { requiresAuth: true, roles: ['admin'], title: '模型提供商管理', menu: true, icon: 'Setting', group: '管理控制台' }`
- `/admin/identity`：`meta: { requiresAuth: true, roles: ['admin'], title: '用户管理', menu: true, icon: 'User', group: '管理控制台' }`
- `/styleguide`：`meta: { requiresAuth: false, title: '样式预览', menu: true, icon: 'Brush' }`

- [ ] **Step 7: 全量测试 + 类型检查**

Run: `pnpm exec vitest run src/router && pnpm exec vue-tsc --noEmit`
Expected: PASS，类型无错。

- [ ] **Step 8: Commit**

```bash
git add web/src/types/router.d.ts web/src/router/menu.ts web/src/router/index.ts web/src/router/__tests__/menu.spec.ts
git commit -m "前端：路由 meta 增 icon/group，菜单带出图标 + 新增 buildBreadcrumb"
```

---

## Task 3: PageHeader 组件

**Files:**
- Create: `web/src/components/PageHeader.vue`
- Test: `web/src/components/__tests__/PageHeader.spec.ts`

**Interfaces:**
- Produces: `<PageHeader title="..." :description="..."><slot 操作区/></PageHeader>`，props `{ title: string; description?: string }`，默认插槽为右侧操作区。

- [ ] **Step 1: 写失败测试**

新建 `web/src/components/__tests__/PageHeader.spec.ts`：

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PageHeader from '@/components/PageHeader.vue'

describe('PageHeader', () => {
  it('渲染标题与描述', () => {
    const wrapper = mount(PageHeader, {
      props: { title: '用户管理', description: '管理团队成员账号与角色' },
    })
    expect(wrapper.text()).toContain('用户管理')
    expect(wrapper.text()).toContain('管理团队成员账号与角色')
  })

  it('无 description 时不渲染描述节点', () => {
    const wrapper = mount(PageHeader, { props: { title: '应用管理' } })
    expect(wrapper.find('.page-header__desc').exists()).toBe(false)
  })

  it('默认插槽渲染到操作区', () => {
    const wrapper = mount(PageHeader, {
      props: { title: 'X' },
      slots: { default: '<button class="act">新建</button>' },
    })
    expect(wrapper.find('.page-header__actions .act').exists()).toBe(true)
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `pnpm exec vitest run src/components/__tests__/PageHeader.spec.ts`
Expected: FAIL —— 无法解析 `@/components/PageHeader.vue`。

- [ ] **Step 3: 实现组件**

新建 `web/src/components/PageHeader.vue`：

```vue
<script setup lang="ts">
// 页面标题区：标题 + 可选描述 + 右侧操作区（默认插槽）。渲染在内容区灰底上。
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <div class="page-header">
    <div class="page-header__text">
      <h2 class="page-header__title">{{ title }}</h2>
      <p v-if="description" class="page-header__desc">{{ description }}</p>
    </div>
    <div class="page-header__actions">
      <slot />
    </div>
  </div>
</template>

<style scoped lang="scss">
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: $spacing-lg;

  &__title {
    margin: 0;
    font-size: $font-size-xl;
    font-weight: 600;
    color: $color-text-primary;
  }

  &__desc {
    margin: $spacing-xs 0 0;
    font-size: $font-size-sm;
    color: $color-text-secondary;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: $spacing-md;
  }
}
</style>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `pnpm exec vitest run src/components/__tests__/PageHeader.spec.ts`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add web/src/components/PageHeader.vue web/src/components/__tests__/PageHeader.spec.ts
git commit -m "前端：新增 PageHeader 页面标题区复用组件"
```

---

## Task 4: ContentCard 组件

**Files:**
- Create: `web/src/components/ContentCard.vue`
- Test: `web/src/components/__tests__/ContentCard.spec.ts`

**Interfaces:**
- Produces: `<ContentCard><slot 内容/></ContentCard>`，白底 + 轻阴影 + 圆角的卡片容器，默认插槽放表格/表单。

- [ ] **Step 1: 写失败测试**

新建 `web/src/components/__tests__/ContentCard.spec.ts`：

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ContentCard from '@/components/ContentCard.vue'

describe('ContentCard', () => {
  it('渲染默认插槽内容并带卡片根类', () => {
    const wrapper = mount(ContentCard, {
      slots: { default: '<div class="inner">表格</div>' },
    })
    expect(wrapper.find('.content-card').exists()).toBe(true)
    expect(wrapper.find('.content-card .inner').exists()).toBe(true)
    expect(wrapper.text()).toContain('表格')
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `pnpm exec vitest run src/components/__tests__/ContentCard.spec.ts`
Expected: FAIL —— 无法解析 `@/components/ContentCard.vue`。

- [ ] **Step 3: 实现组件**

新建 `web/src/components/ContentCard.vue`：

```vue
<script setup lang="ts">
// 白卡片容器：白底 + 轻阴影 + 圆角，包表格/表单，与灰底拉开层次。
</script>

<template>
  <div class="content-card">
    <slot />
  </div>
</template>

<style scoped lang="scss">
.content-card {
  padding: $spacing-lg;
  background: $color-bg-card;
  border: 1px solid $color-border;
  border-radius: $radius-md;
  box-shadow: $shadow-sm;
}
</style>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `pnpm exec vitest run src/components/__tests__/ContentCard.spec.ts`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add web/src/components/ContentCard.vue web/src/components/__tests__/ContentCard.spec.ts
git commit -m "前端：新增 ContentCard 白卡片容器复用组件"
```

---

## Task 5: DefaultLayout 重写（深色侧栏 + 顶栏）

**Files:**
- Modify: `web/src/layouts/DefaultLayout.vue`
- Test: `web/src/layouts/__tests__/DefaultLayout.spec.ts`

**Interfaces:**
- Consumes: `buildMenu`、`buildBreadcrumb`（Task 2）、`config.appVersion`（Task 1）、`useUserStore`。
- Produces: 布局壳；顶栏退出入口改为用户下拉项（`data-test="user-menu"` 触发，`data-test="logout"` 点击退出）；折叠按钮 `data-test="collapse"`。

- [ ] **Step 1: 先改既有 DefaultLayout 测试（退出经下拉触发）**

修改 `web/src/layouts/__tests__/DefaultLayout.spec.ts` 中「点击退出」用例，把单击改为先开下拉再点退出项：

```ts
  it('点击退出：清登录态（token + localStorage）并跳转登录页', async () => {
    const store = useUserStore()
    store.setToken('jwt')
    store.user = { id: '1', username: 'alice', role: 'admin' }
    const router = makeRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(DefaultLayout, {
      global: { plugins: [router, ElementPlus] },
    })

    await wrapper.get('[data-test="user-menu"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="logout"]').trigger('click')
    await flushPromises()

    expect(store.token).toBeNull()
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
  })
```

其余用例（用户名展示、菜单 Member/Admin）保持不变。

- [ ] **Step 2: 运行测试确认失败**

Run: `pnpm exec vitest run src/layouts/__tests__/DefaultLayout.spec.ts`
Expected: FAIL —— 找不到 `[data-test="user-menu"]`（旧布局没有该结构）。

- [ ] **Step 3: 重写 DefaultLayout.vue**

整文件替换 `web/src/layouts/DefaultLayout.vue` 为：

```vue
<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import {
  Collection,
  Grid,
  Setting,
  User,
  Brush,
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
const iconMap: Record<string, Component> = { Collection, Grid, Setting, User, Brush }

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
```

- [ ] **Step 4: 运行 DefaultLayout 测试确认通过**

Run: `pnpm exec vitest run src/layouts/__tests__/DefaultLayout.spec.ts`
Expected: PASS（用户名展示、退出经下拉、菜单 Member/Admin 全绿）。

> 若退出用例因下拉 popper 未在测试环境打开而失败：确认 `el-dropdown` 已带 `:teleported="false"` 与 `trigger="click"`；必要时在两次 `trigger('click')` 之间多加一次 `await flushPromises()`。不要改动「清 token + 跳转」断言本身。

- [ ] **Step 5: 类型检查**

Run: `pnpm exec vue-tsc --noEmit`
Expected: 无错误。

- [ ] **Step 6: Commit**

```bash
git add web/src/layouts/DefaultLayout.vue web/src/layouts/__tests__/DefaultLayout.spec.ts
git commit -m "前端：DefaultLayout 改造为深色侧栏（Logo 渐变/图标菜单/折叠）+ 顶栏面包屑与用户下拉"
```

---

## Task 6: UserList 套用 PageHeader/ContentCard 并清魔法值

**Files:**
- Modify: `web/src/views/admin/identity/UserList.vue`

**Interfaces:**
- Consumes: `PageHeader`、`ContentCard`（Task 3/4）。
- Produces: 无新接口；保留全部 `data-test` 与 `共 N 个用户` 文本。

- [ ] **Step 1: 引入组件**

修改 `web/src/views/admin/identity/UserList.vue` 的 `<script setup>`，在 import 段加：

```ts
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'
```

- [ ] **Step 2: 替换模板头部与卡片包裹**

把 `<template>` 内最外层 `<div class="user-list">` 的内容改成：用 `PageHeader`（标题 + 动态描述含用户数 + 操作区放搜索框与新建按钮）替换原 `user-list__header`，并用 `ContentCard` 包裹表格。`el-dialog` 留在 `ContentCard` 外、`user-list` 内。即把原 145–253 行结构调整为：

```vue
  <div class="user-list">
    <PageHeader title="用户管理" :description="`共 ${users.length} 个用户`">
      <el-input
        v-model="search"
        data-test="search"
        placeholder="搜索用户名"
        clearable
        class="user-list__search"
      />
      <el-button type="primary" data-test="create-open" @click="openCreate">新建用户</el-button>
    </PageHeader>

    <ContentCard>
      <el-table v-loading="loading" :data="filteredUsers" data-test="user-table">
        <!-- 表格列保持原样，不改动 -->
      </el-table>
    </ContentCard>

    <el-dialog v-model="dialogVisible" title="新建用户" width="480">
      <!-- 对话框保持原样，不改动 -->
    </el-dialog>
  </div>
```

> 表格列（`el-table-column ...`）与对话框内容**原样保留**，仅把 `<el-table>` 用 `<ContentCard>` 包起来、把原 `user-list__header` 整块换成 `<PageHeader>`。所有 `data-test` 不变。

- [ ] **Step 3: 精简样式，清魔法值**

把 `<style scoped lang="scss">` 整块替换为（仅保留搜索框宽度，其余间距/标题样式已由 PageHeader 承担）：

```scss
<style scoped lang="scss">
.user-list__search {
  width: 220px;
}
</style>
```

- [ ] **Step 4: 运行 UserList 测试确认通过**

Run: `pnpm exec vitest run src/views/admin/identity/__tests__/UserList.spec.ts`
Expected: PASS（含 `共 2 个用户` 文本断言、各 `data-test` 交互）。

- [ ] **Step 5: 类型检查**

Run: `pnpm exec vue-tsc --noEmit`
Expected: 无错误。

- [ ] **Step 6: Commit**

```bash
git add web/src/views/admin/identity/UserList.vue
git commit -m "前端：UserList 套用 PageHeader/ContentCard 骨架并清除样式魔法值"
```

---

## Task 7: ProviderList 套用 PageHeader/ContentCard

**Files:**
- Modify: `web/src/views/admin/provider/ProviderList.vue`

**Interfaces:**
- Consumes: `PageHeader`、`ContentCard`。
- Produces: 无。

- [ ] **Step 1: 改造组件**

整文件替换 `web/src/views/admin/provider/ProviderList.vue` 为：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getHealth } from '@/api/health'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

// 页面加载时探后端：HTTP 200 即成功（见 api-standards.md 第 1 节）。
// silent:true 让本页自行展示结果，不弹拦截器的全局 toast。
const connected = ref<boolean | null>(null)
const text = ref('')

onMounted(async () => {
  try {
    const body = await getHealth({ silent: true })
    connected.value = true
    text.value = `后端已连接：${body}`
  } catch {
    connected.value = false
    text.value = '后端未连接'
  }
})
</script>

<template>
  <div>
    <PageHeader title="模型提供商管理" description="配置模型供应商接入与连通性状态" />
    <ContentCard>
      <p v-if="connected !== null" :class="connected ? 'status--ok' : 'status--err'">
        {{ text }}
      </p>
    </ContentCard>
  </div>
</template>

<style scoped lang="scss">
.status--ok {
  color: $color-success;
}

.status--err {
  color: $color-danger;
}
</style>
```

- [ ] **Step 2: 类型检查 + 全量测试**

Run: `pnpm exec vue-tsc --noEmit && pnpm exec vitest run`
Expected: 类型无错；全部测试 PASS。

- [ ] **Step 3: Commit**

```bash
git add web/src/views/admin/provider/ProviderList.vue
git commit -m "前端：ProviderList 套用 PageHeader/ContentCard 骨架"
```

---

## Task 8: 全量验证（lint + 构建 + 测试）

**Files:** 无（仅校验）。

- [ ] **Step 1: Lint**

Run: `pnpm lint`
Expected: 无 error（warning 视既有基线）。

- [ ] **Step 2: 构建（含 vue-tsc）**

Run: `pnpm build`
Expected: 构建成功，无类型错误。

- [ ] **Step 3: 全量测试**

Run: `pnpm test -- --run` （或 `pnpm exec vitest run`）
Expected: 全部 PASS。

- [ ] **Step 4: 人工验收（交给用户）**

启动 `pnpm dev`，登录后逐项核对验收口径（设计文档 §8）：深色侧栏 / Logo 渐变 / 图标齐全 / hover·选中态 / 折叠 / 版本号；顶栏面包屑随路由变化、头像+用户名+退出下拉；UserList·ProviderList 白卡片层次。**此步由用户验证，不自动判定完成。**

---

## Self-Review 记录

- **Spec 覆盖**：§2 token→Task1；§3 侧栏（Logo/图标/折叠/版本）→Task1+2+5；§4 顶栏（面包屑/用户区）→Task2+5；§5 组件→Task3+4；§6 文件清单逐项有任务；§7 测试策略（纯逻辑 TDD + 组件浅渲染 + 视觉人工）已分布于各任务；§8 验收→Task8 Step4。无遗漏。
- **占位符扫描**：无 TBD/TODO；每个代码步给出完整代码。
- **类型一致**：`MenuItem.icon`、`buildBreadcrumb` 签名、`config.appVersion`、`__APP_VERSION__`、`data-test="user-menu"/"logout"/"collapse"` 在定义处与消费处一致。
