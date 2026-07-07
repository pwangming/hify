import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { config } from '@/config'

// 登录态 token 在 localStorage 的键。后续 stores/user.ts 持有读写，这里直接读，
// 避免 request ←→ store 循环依赖（见 frontend-standards.md 第 3.2、6 节）。
export const TOKEN_KEY = 'hify_token'

/** 参数校验失败（10001）时后端返回的字段错误项 */
export interface FieldError {
  field: string
  message: string
}

/** 后端统一响应信封 com.hify.common.Result<T>（见 api-standards.md 第 3 节） */
interface Result<T> {
  code: number
  message: string
  data: T
  traceId: string
}

/**
 * 类型化业务错误：拦截器在失败时抛出，调用方按 code 自行处理。
 * fieldErrors 仅在参数校验失败（10001）时存在。
 */
export class ApiError extends Error {
  constructor(
    readonly code: number,
    message: string,
    readonly traceId?: string,
    readonly fieldErrors?: FieldError[],
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 扩展请求配置：silent=true 时拦截器跳过全局 toast，由调用方自己 catch 处理 */
export interface RequestConfig extends AxiosRequestConfig {
  silent?: boolean
}

// 成功码：与后端 Result.ok 一致（api-standards.md 第 3、5 节，成功用 200）。
const SUCCESS_CODE = 200

// 通用错误码（见 api-standards.md 5.3）。业务码无需在此枚举，按"默认 toast"兜底处理。
const ERR_UNAUTHENTICATED = 10002 // 未认证 / 凭证无效
const ERR_TOKEN_EXPIRED = 10003 // 凭证已过期
const ERR_PARAM_INVALID = 10001 // 参数校验失败

const instance = axios.create({
  baseURL: config.apiBaseUrl,
  timeout: config.apiTimeout,
})

// 请求拦截器：注入 JWT
instance.interceptors.request.use((cfg) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    cfg.headers.Authorization = `Bearer ${token}`
  }
  return cfg
})

// 判断响应体是否为 Result 信封；非信封响应（防御性兜底，正常 /api/v1 接口都套 Result）原样放行
function isResult(data: unknown): data is Result<unknown> {
  return typeof data === 'object' && data !== null && typeof (data as Result<unknown>).code === 'number'
}

/**
 * 非 Result 失败的用户可见文案：一律中文，绝不透传 axios 的英文 error.message。
 * 断网时 axios 的 error.message 是非空英文 "Network Error"，会把中文兜底顶掉——正是断网英文 toast 的根因。
 * 无 response=网络层失败（断网/超时/DNS）；有 response 但非 Result=异常网关/非 JSON 错误体。
 */
export function fallbackErrorMessage(error: AxiosError): string {
  return error.response ? '服务器繁忙，请稍后重试' : '网络异常，请稍后重试'
}

// 清登录态并跳登录页（10002/10003 的统一出口；登录页就绪后由其承接）
function redirectToLogin() {
  localStorage.removeItem(TOKEN_KEY)
  if (!window.location.pathname.startsWith('/login')) {
    window.location.href = '/login'
  }
}

// 响应成功拦截器：解包 Result，只把 data 返回给调用方；非 Result（actuator）原样透传
instance.interceptors.response.use(
  (res) => {
    const body = res.data
    if (!isResult(body)) {
      return body // 防御：非信封响应原样透传
    }
    if (body.code === SUCCESS_CODE) {
      return body.data
    }
    // 失败的 Result 理论上伴随非 2xx 状态（走下方 error 分支）；此处防御性兜底
    throw new ApiError(body.code, body.message, body.traceId)
  },
  // 响应失败拦截器：HTTP 状态与 body.code 一致（见 api-standards.md 2.3/3），统一解析并分流
  (error: AxiosError) => {
    const body = error.response?.data
    const apiError = isResult(body)
      ? new ApiError(body.code, body.message, body.traceId, body.data as FieldError[] | undefined)
      : new ApiError(
          -1,
          fallbackErrorMessage(error),
          undefined,
        )

    const silent = (error.config as RequestConfig | undefined)?.silent

    switch (apiError.code) {
      case ERR_UNAUTHENTICATED:
      case ERR_TOKEN_EXPIRED:
        // 登录态失效的统一出口（见 frontend-standards.md 7.4）
        if (apiError.code === ERR_TOKEN_EXPIRED) {
          ElMessage.warning('登录已过期，请重新登录')
        }
        redirectToLogin()
        break
      case ERR_PARAM_INVALID:
        // 有字段错误数组交表单逐项标红；无字段错误时兜底 toast。
        if (!apiError.fieldErrors?.length && !silent) {
          ElMessage.error(apiError.message)
        }
        break
      default:
        if (!silent) {
          ElMessage.error(apiError.message)
        }
        // 系统级错误打印 traceId，便于凭它 grep 日志
        if (apiError.code < 0 || apiError.code === 10000) {
          console.error(`[ApiError] code=${apiError.code} traceId=${apiError.traceId ?? '-'}`, apiError.message)
        }
    }

    return Promise.reject(apiError)
  },
)

/**
 * 类型化请求入口。成功时 resolve 解包后的 data（T），失败时 reject ApiError。
 * 第二个泛型设为 T，告诉 axios 本实例的 resolve 值即 T（拦截器已解包信封）。
 */
export const request = {
  get: <T>(url: string, cfg?: RequestConfig) => instance.request<T, T>({ ...cfg, method: 'GET', url }),
  post: <T>(url: string, data?: unknown, cfg?: RequestConfig) =>
    instance.request<T, T>({ ...cfg, method: 'POST', url, data }),
  put: <T>(url: string, data?: unknown, cfg?: RequestConfig) =>
    instance.request<T, T>({ ...cfg, method: 'PUT', url, data }),
  delete: <T>(url: string, cfg?: RequestConfig) => instance.request<T, T>({ ...cfg, method: 'DELETE', url }),
}
