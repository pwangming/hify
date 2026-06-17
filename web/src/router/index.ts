import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

// 集中式路由表：页面与权限 meta 一处可览，便于审查（见 frontend-standards.md 第 7 节）。
// meta 约定：requiresAuth / roles / title。
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/knowledge' },
  {
    path: '/knowledge',
    name: 'KnowledgeList',
    component: () => import('@/views/knowledge/KnowledgeList.vue'),
    meta: { requiresAuth: true, title: '知识库管理' },
  },
  {
    path: '/app',
    name: 'AppList',
    component: () => import('@/views/app/AppList.vue'),
    meta: { requiresAuth: true, title: '应用管理' },
  },
  {
    path: '/admin/provider',
    name: 'ProviderList',
    component: () => import('@/views/admin/provider/ProviderList.vue'),
    meta: { requiresAuth: true, roles: ['admin'], title: '模型提供商管理' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
