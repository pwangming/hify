import 'vue-router'
import type { UserRole } from '@/types/user'

// 路由 meta 契约集中声明（增强 vue-router 的 RouteMeta，全工程获得类型与补全）。
declare module 'vue-router' {
  interface RouteMeta {
    /** 是否需登录。缺省视为需要（具体策略见守卫）。 */
    requiresAuth?: boolean
    /** 允许的角色；缺省 = 登录即可。写 ['admin'] = 仅 Admin。 */
    roles?: UserRole[]
    /** 页面标题（document.title）。 */
    title?: string
    /** 是否作为侧边菜单项展示（缺省 false）。 */
    menu?: boolean
    /** 使用的布局；缺省 default，'blank' 用于登录页等无壳页面。 */
    layout?: 'default' | 'blank'
  }
}
