import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { authGuard } from './guard'

// 集中式路由表：页面与权限 meta 一处可览，便于审查（见 frontend-standards.md 第 7 节）。
// meta 约定：requiresAuth / roles / title / menu / layout（类型见 types/router.d.ts）。
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/chat' },
  {
    path: '/chat',
    name: 'ChatHome',
    component: () => import('@/views/conversation/ChatHome.vue'),
    meta: { requiresAuth: true, title: '对话', menu: true, icon: 'ChatDotRound' },
  },
  {
    path: '/knowledge',
    name: 'KnowledgeList',
    component: () => import('@/views/knowledge/KnowledgeList.vue'),
    meta: { requiresAuth: true, title: '知识库管理', menu: true, icon: 'Collection' },
  },
  {
    path: '/knowledge/:id',
    name: 'DatasetDetail',
    component: () => import('@/views/knowledge/DatasetDetail.vue'),
    meta: { requiresAuth: true, title: '知识库详情' },
  },
  {
    path: '/app',
    name: 'AppList',
    component: () => import('@/views/app/AppList.vue'),
    meta: { requiresAuth: true, title: '应用管理', menu: true, icon: 'Grid' },
  },
  {
    path: '/apps/:appId/chat',
    name: 'AppChat',
    component: () => import('@/views/conversation/ChatView.vue'),
    meta: { requiresAuth: true, title: '试聊' },
  },
  {
    path: '/admin/provider',
    name: 'ProviderList',
    component: () => import('@/views/admin/provider/ProviderList.vue'),
    meta: {
      requiresAuth: true,
      roles: ['admin'],
      title: '模型提供商管理',
      menu: true,
      icon: 'Setting',
      group: '管理控制台',
    },
  },
  {
    path: '/admin/provider/:id',
    name: 'ProviderDetail',
    component: () => import('@/views/admin/provider/ProviderDetail.vue'),
    meta: {
      requiresAuth: true,
      roles: ['admin'],
      title: '模型管理',
    },
  },
  {
    path: '/admin/identity',
    name: 'UserList',
    component: () => import('@/views/admin/identity/UserList.vue'),
    meta: {
      requiresAuth: true,
      roles: ['admin'],
      title: '用户管理',
      menu: true,
      icon: 'User',
      group: '管理控制台',
    },
  },
  // —— 样式预览（开发期视觉验收用，免登录、默认布局、进侧边菜单；定稿后可删）——
  {
    path: '/styleguide',
    name: 'Styleguide',
    component: () => import('@/views/styleguide/StyleguideView.vue'),
    meta: { requiresAuth: false, title: '样式预览', menu: true, icon: 'Brush' },
  },
  // —— 无壳页面（不需登录、用 BlankLayout）——
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { requiresAuth: false, title: '登录', layout: 'blank' },
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/error/ForbiddenView.vue'),
    meta: { requiresAuth: false, title: '无权限', layout: 'blank' },
  },
  // 通配兜底必须放最后，捕获所有未匹配路径
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/error/NotFoundView.vue'),
    meta: { requiresAuth: false, title: '页面不存在', layout: 'blank' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 全局前置守卫：登录态 / 角色拦截（规范 7.2，逻辑在 guard.ts）
router.beforeEach(authGuard)

// 导航确认后统一设标题（规范 7.2 第 ⑤ 步，放 afterEach 更贴近“已进入页面”语义）
router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · Hify` : 'Hify'
})

export default router
