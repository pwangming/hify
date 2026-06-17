import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

// 集中式路由表：页面与权限 meta 一处可览，便于审查（见 frontend-standards.md 第 7 节）。
// 业务路由后续在此登记；meta 约定：requiresAuth / roles / title。
const routes: RouteRecordRaw[] = []

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
