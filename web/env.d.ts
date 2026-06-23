/// <reference types="vite/client" />

// 注入到前端的环境变量类型声明（仅 VITE_ 前缀），享受自动补全。
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string
  readonly VITE_API_TIMEOUT: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

// Vite define 注入的编译期常量（见 vite.config.ts）。
declare const __APP_VERSION__: string
