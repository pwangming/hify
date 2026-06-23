import type { UserRole } from './user'

/** admin 用户管理视图，对应后端 UserView。id 为 string（Long 序列化防精度丢失）。 */
export interface AdminUser {
  id: string
  username: string
  role: UserRole
  status: 'enabled' | 'disabled'
  createTime: string
}

/** 新建用户请求体。校验规则对齐后端 CreateUserRequest。 */
export interface CreateUserRequest {
  username: string
  password: string
  role: UserRole
}
