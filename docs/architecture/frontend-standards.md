# Hify 前端规范

> 本文是前端（`web/`）工程的唯一权威规范。写前端代码前先读本文；与本文冲突的代码视为错误。
> 与后端契约相关的部分（Result、错误码、序列化）以 `api-standards.md` 为准，本文只描述前端如何对接。
>
> **本文每个选型都附「为什么」**——记录拍板时的权衡，避免日后重复纠结。规范没覆盖的情况先问、拍板后补回本文。

## 0. 总原则

沿用 CLAUDE.md 的工程哲学：**最简单直接、不过度抽象、不引不必要依赖**。
前端是面向团队内部（20-50 人、PC 端）的管理工具，不是公开 SaaS，许多"为了通用性/扩展性"的方案在这里是负担而非收益。下面每处"不做 X"基本都源于此。

---

## 1. 技术栈

| 项 | 选型 | 为什么 |
|---|---|---|
| 框架 / 语言 | Vue 3 + TypeScript | CLAUDE.md 已定 |
| API 风格 | Composition API + `<script setup>`，**不混用 Options API** | 统一一种写法，TS 类型推导最佳，团队心智单一 |
| 构建 | Vite | 快、Vue 官方推荐；本地开发用其 proxy 对接后端 |
| 包管理 | **pnpm** | 节省磁盘（硬链接）、安装快、依赖隔离严格 |
| Node | **v24**，用 `.nvmrc` + `package.json` 的 `engines` 锁死 | 一人维护，固定版本避免环境漂移 |
| UI 组件库 | Element Plus | CLAUDE.md 已定，后台/表单覆盖率高 |
| 画布 | Vue Flow | CLAUDE.md 已定，Workflow 可视化编辑 |
| 状态管理 | Pinia | Vue 官方推荐；跨页面共享状态（登录态等）绕不开，Pinia 是最轻的正解 |
| 路由 | Vue Router | 多页面后台必备 |
| HTTP | axios | 需要拦截器统一注入 JWT、解包 Result、处理错误码；fetch 自己包一层不如 axios 直接 |
| 样式 | Element Plus + 原生 SCSS（scoped），**不引 Tailwind** | 组件库覆盖 90%；Tailwind 属技术栈外依赖，收益不抵心智成本 |
| 质量 | ESLint + Prettier + vue-tsc | 标配 |

**按需后引（用到那个功能时再装，装前问一次）**，避免一上来堆依赖：

| 场景 | 候选库 |
|---|---|
| 用量与成本看板（图表） | ECharts / vue-echarts |
| 对话渲染 LLM 回复（Markdown） | markdown-it + 代码高亮（highlight.js / shiki） |
| 代码执行节点编辑 Python | CodeMirror / Monaco |
| 时间格式化 | dayjs |
| 后端 OpenAPI 生成 TS 类型 | openapi-typescript |

---

## 2. 目录结构

采用 Vue 经典的**按类型分层**约定式目录（不刻意对齐后端 10 模块——前后端关注点不同，强行对齐反而别扭）。

```
web/
├── public/                     # 不经构建处理的静态资源（favicon 等）
├── src/
│   ├── api/                    # 接口层
│   │   ├── request.ts          # axios 实例 + 拦截器（JWT / Result 解包 / 错误码）
│   │   ├── auth.ts             # 按模块拆分的请求函数
│   │   ├── knowledge.ts
│   │   └── ...
│   ├── assets/                 # 经构建处理的静态资源（图片、字体）
│   ├── components/             # 全局通用组件（业务无关，跨页面复用）
│   ├── composables/            # 组合式函数（useXxx）
│   ├── config/                 # 环境变量集中读取，导出类型化常量
│   ├── directives/             # 自定义指令
│   ├── layouts/                # 布局组件（后台主框架 / 空白布局）
│   ├── router/
│   │   ├── index.ts            # 集中式路由表
│   │   └── guard.ts            # 全局守卫（登录态 / 角色）
│   ├── stores/                 # Pinia（仅跨页面共享状态）
│   │   ├── user.ts
│   │   └── ...
│   ├── styles/                 # 全局 scss、Element Plus 主题变量、mixins
│   ├── types/                  # 全局 TS 类型（接口 DTO、通用声明）
│   ├── utils/                  # 纯工具函数（无副作用、无请求）
│   ├── views/                  # 页面组件（按业务分子目录）
│   │   ├── login/
│   │   ├── knowledge/
│   │   ├── conversation/
│   │   ├── workflow/
│   │   ├── app/
│   │   └── admin/              # 管理控制台页面
│   │       ├── provider/
│   │       ├── identity/
│   │       ├── tool/
│   │       └── usage/
│   ├── App.vue
│   ├── main.ts                 # 应用入口（挂载、注册插件）
│   └── env.d.ts                # 环境变量与 *.vue 类型声明
├── .env.development            # 开发环境变量（base url / 超时）
├── .env.production             # 生产环境变量
├── .nvmrc                      # 24
├── .prettierrc
├── eslint.config.js
├── index.html                  # Vite 入口 HTML
├── package.json
├── pnpm-lock.yaml
├── tsconfig.json
├── tsconfig.node.json
└── vite.config.ts
```

约定：

- **`views/` 是页面、`components/` 是复用组件**：页面级组件不进 `components/`；某页面私有的小组件就近放在该页面目录下（如 `views/workflow/components/`）。
- **`admin/` 收在 `views/admin/`**：管理控制台页面集中，配合 router 角色守卫。
  *为什么*：和后端"admin 接口分散在各模块"不同，前端按页面归类更直观，管理控制台是一块独立的导航区。
- **`types/` 集中放全局 DTO 类型**，`api/*.ts` 引用它。
- **`api/` 按模块拆文件**，每个文件一组请求函数，统一走 `request.ts` 的实例。

---

## 3. API 层封装

对接 `api-standards.md` 的契约。**封装的目标：业务代码只关心数据，不碰信封、不碰 HTTP 细节。**

### 3.1 Result 信封自动解包

`request<T>()` 在响应拦截器里处理 `Result<T>`：
- 成功（`code === 0`）→ **只把 `data` 返回**给调用方。业务代码 `const list = await getKnowledgeList()` 直接拿数据。
- 失败（`code !== 0`）→ 抛出类型化的 `ApiError(code, message, traceId, fieldErrors?)`。

*为什么*：信封是协议细节，不该污染每个调用点；解包后调用方代码干净，错误走异常通道统一处理。

### 3.2 JWT 注入（请求拦截器）

从 `useUserStore` / `localStorage` 取 token，注入 `Authorization: Bearer <jwt>`。登录、刷新接口除外。

### 3.3 错误统一处理（响应错误拦截器）

后端失败响应 **HTTP 状态码与 body 错误码一致**（如 429 + `14001`），axios 把非 2xx 当 error，统一在错误拦截器里解析 body 的 `{code, message, traceId}`，按错误码分流：

| 错误码 | 默认处理 |
|---|---|
| `10002` 未认证 / `10003` 凭证过期 | 清登录态 → 跳登录页（`10003` 提示"登录已过期"，区别于 `10002`） |
| `10001` 参数校验失败 | 把 `data`（字段错误数组）封进 `ApiError.fieldErrors` 抛给调用方，供表单逐项标红，**不弹全局 toast** |
| `10004/10005/10006/10007` 及各模块业务码（如 `14001`） | `ElMessage.error(message)` 全局提示 |
| `10000` 系统错误 / 5xx | 全局提示 + console 打印 `traceId`（用户报障凭它 grep 日志） |

**调用方可退出全局提示**：请求配置加 `{ silent: true }`，拦截器跳过全局 toast，由调用方自己 `catch` 处理。
*为什么用统一 `silent` 标志，而不是每个接口各写各的*：配额（`14001`）想弹专门的升级引导、重名（`10006`）想内联标红——这些"自定义错误处理"的需求用一个开关覆盖，避免在每个 api 函数里散落 try-catch 逻辑。

### 3.4 SSE 流式（对话、工作流进度）

**不走 axios，用 `fetch` + ReadableStream**，封一个 `useSSE` / `useChatStream` composable，统一解析后端约定的四种事件（`message` / `tool_call` / `error` / `done`，见 api-standards.md 3.3）。`error` 事件的 data 即 Result 失败结构，复用 `ApiError`。

*为什么不用原生 `EventSource`*：`EventSource` 只支持 GET、**无法携带 `Authorization` 头**（只能把 token 塞 query，不安全）；而对话是 POST 带消息体 + 需要 JWT，`EventSource` 基本不可用。`fetch` 流式能 POST、能带头、能自定义解析，是唯一合适方案。

### 3.5 超时

axios 默认超时（建议 30s）经 `.env` 注入（`VITE_API_TIMEOUT`），呼应 CLAUDE.md"外部调用必须有超时、外化配置"。SSE/LLM 流式不走 axios，不受此限。

### 3.6 id 与类型约定

- **所有 id、token 计数：TS 类型为 `string`，不转 number**。后端全局把 Long 序列化为 string（防 JS 2^53 精度丢失），前端原样当字符串用；确需算术时调用方自己 `Number()`。
- 时间字段：`string`（ISO-8601 含时区），展示时格式化（用到再引 dayjs）。
- DTO 类型集中放 `src/types/`，`api/*.ts` 的函数签名引用它。

---

## 4. 命名规范

| 对象 | 约定 | 示例 |
|---|---|---|
| 组件文件 / SFC | **PascalCase** | `KnowledgeList.vue`、`ChatMessage.vue` |
| 非组件 ts 文件 | **camelCase** | `request.ts`、`useChatStream.ts` |
| composable | `useXxx` | `useSSE.ts` → `export function useSSE()` |
| Pinia store | `useXxxStore`，文件 camelCase | `stores/user.ts` → `useUserStore` |
| 类型 / 接口 | **PascalCase，不加 `I` 前缀** | `interface Dataset`、`type ChatMessage` |
| 常量 | **UPPER_SNAKE_CASE** | `const MAX_PAGE_SIZE = 100` |
| 变量 / 函数 | camelCase | `const datasetList` |
| 模板里用组件 | PascalCase | `<KnowledgeList />` |
| 路由 name | PascalCase，与组件同名 | `name: 'KnowledgeList'` |
| 事件名（emits） | camelCase 声明，模板用 kebab | 声明 `update:modelValue`，模板 `@update:model-value` |

命名风格速记：
- **camelCase**（小驼峰）：首词小写，后续词首字母大写，无分隔——`datasetList`。
- **PascalCase**（大驼峰）：每个词首字母都大写——`KnowledgeList`。
- **kebab-case**（短横线）：全小写，`-` 分隔——`update:model-value`。
- **UPPER_SNAKE_CASE**：全大写，`_` 分隔，常量用——`MAX_PAGE_SIZE`。

---

## 5. 组件规范

1. **统一 `<script setup lang="ts">`**，不用 Options API、不用 `defineComponent` 普通 script。
2. **SFC 块顺序**：`<script setup>` → `<template>` → `<style scoped>`（脚本在前，符合 setup 心智）。
3. **props/emits 用基于类型的声明**，默认值用 `withDefaults`，**禁止运行时对象式声明**：
   ```ts
   const props = defineProps<{ datasetId: string; editable?: boolean }>()
   const emit = defineEmits<{ (e: 'save', id: string): void }>()
   ```
   *为什么*：类型式声明类型完整、与 TS 一致，对象式声明会丢类型。
4. **v-model 用 `defineModel<T>()`**（Vue 3.4+），不手写 `modelValue` + `update:modelValue`。新项目无历史包袱，直接用新写法。
5. **单组件不超 ~300 行**：超了拆子组件或抽 composable；逻辑复用走 composable，**不用 mixin**（mixin 来源不透明、易冲突）。
6. **props 只读**：不直接改 props；需要本地副本用 `computed` 或 `ref` 派生。
7. **列表渲染必带 `:key`**，用业务 id（string），不用 index。
8. **样式默认 `scoped`**；穿透子组件用 `:deep()`，不用废弃的 `::v-deep` / `>>>`。

---

## 6. 状态管理（Pinia）

**核心原则：store 只放跨页面、跨组件共享的状态。** 滥用 store 是前端最常见的过度设计。

**进 store：**
- 当前用户与登录态（`useUserStore`：user 信息、token、角色、`isAdmin`）。
- 全局基础字典：可用模型列表、供应商列表等多处复用、不常变的数据。
- 需要跨路由保持的 UI 状态（如侧边栏折叠）。

**不进 store（用组件自身 `ref`/`reactive`）：**
- 单页面的临时状态（表单值、弹窗开关、loading）。
- 列表页查询结果与分页——离开页面就该销毁，进 store 反而要操心清理。
- 父子组件之间的数据——用 props/emits。

**写法：**
- 统一 **setup 语法**：`defineStore('user', () => { ... })`，`ref` 当 state、`computed` 当 getter、函数当 action，与组件写法一致。
- 一个领域一个 store 文件，store id 与文件名一致（如 `'user'`）。

**持久化：**
- **只持久化 token，存 `localStorage`，store 初始化时读回**；其余 store 一律内存态，刷新即重置。
- *为什么不引持久化插件*（`pinia-plugin-persistedstate`）：需要持久化的只有 token 一处，手写两行读写即可，引插件是过度设计。将来若偏好项（主题、每页条数等）超过 2-3 处再评估。
- `localStorage`（永久，关浏览器还在）用于 token；`sessionStorage`（关标签页即清）暂无场景。

---

## 7. 路由与权限守卫

**前端守卫只是体验层（隐藏入口、提前拦截），不是安全边界——真正的鉴权在后端。** 前端拿到 `10004` 也要兜底（API 拦截器已覆盖）。两者不矛盾：前端拦是为了不让用户白点一个注定 403 的页面。

### 7.1 路由组织

- **集中式路由表**（`router/index.ts`），不用文件约定式自动路由。
  *为什么集中式*：页面就两位数、一人维护无 merge 冲突压力；权限元数据 `meta.roles` 写在集中表里**一眼能审查"哪些页面仅 Admin"**，安全可控；文件约定式要靠插件 + 分散在各文件的 `definePage`，反而难一览，且引额外依赖。
  *（文件约定式适用：页面几十上百且持续增长、多人协作、或已在 Nuxt 等框架内——Hify 都不符合。）*
- 用 `layouts/` 做嵌套：登录页用空白布局，其余挂在后台主框架布局下。
- 路由 `meta` 携带：`requiresAuth`（是否需登录）、`roles`（允许角色，不写=登录即可，写 `['admin']`=仅 Admin）、`title`。

### 7.2 全局前置守卫（`router/guard.ts`）

按序判断：
1. `requiresAuth === false` → 放行（登录页）。
2. 无 token → 重定向登录页，带 `redirect` 记住原目标。
3. 有 token 但 user store 未加载 → 先拉 `/me`（拿角色），失败（401/过期）→ 清登录态跳登录。
4. `meta.roles` 存在且当前角色不在其中 → 跳 403（或首页 + toast），不进入页面。
5. 通过 → 放行，设 `document.title`。

### 7.3 按钮级权限——用 composable `useCan()`

团队共享制：列表/使用全员可见；**改/删仅 owner 或 Admin**。这种元素级控制用 composable，不塞进路由 meta（路由是页面级，按钮是元素级）。

```vue
<script setup>
const { canEdit, canDelete } = useCan()
</script>
<template>
  <el-button v-if="canEdit(dataset)">编辑</el-button>
</template>
```
`canEdit` 逻辑：`isAdmin || resource.ownerId === currentUserId`。

*为什么用 `useCan()` 而不是 `v-perm` 指令*：Hify 的权限模型是**"角色 + owner 归属"**，不是细粒度权限码表。判断 owner 必须拿到具体资源对象——composable 能接收整个资源、有完整 TS 类型、能灵活接 `v-if`/`:disabled`/tooltip；指令传整个对象笨重、丢类型。`v-perm` 的价值在管理大量静态权限码（RBAC 码表），Hify 只有两种判断逻辑，用指令是杀鸡用牛刀、违背"不过度抽象"。

### 7.4 登录态失效的统一出口

只在 API 响应拦截器里处理（`10002/10003` → 清 store + 跳登录），守卫只负责"进页面前"检查。两者都调同一个 `logout()`，不写两套逻辑。

---

## 8. 样式规范

1. **作用域**：组件样式一律 `<style scoped lang="scss">`。全局样式只放 `src/styles/`（reset、Element Plus 主题变量覆盖、工具类、scss 变量与 mixin）。穿透用 `:deep()`，禁用 `::v-deep`/`>>>`。
2. **变量与主题**：颜色、间距、圆角、字号等设计变量集中在 `src/styles/variables.scss`，组件引用变量，**不写魔法值**（`#409EFF`、`16px` 散落即错）。Element Plus 主题色通过覆盖其 CSS 变量（`--el-color-primary` 等）统一调整，不逐组件改。
3. **全局注入**：`variables.scss` / `mixins.scss` 通过 Vite 的 `additionalData` 全局注入，组件内无需重复 `@use`。
   *为什么*：变量/mixin 全工程通用，集中注入省去每个文件重复导入。
4. **命名**：scoped 已隔离作用域，**不强制 BEM**；类名用 kebab-case、按用途语义命名（`.error-text` 而非 `.red-text`、`.box1`）。
5. **布局**：优先 Element Plus 布局组件（`el-row/el-col`、`el-space`）与组件自带间距；自定义布局用 flex/grid，间距用变量。
6. **响应式**：内部 PC 工具，**只保证桌面端，不做移动端适配**（产品定位决定）。
7. **图标**：统一 `@element-plus/icons-vue`，不混引其他图标库（确需再问）。
8. **暗黑模式**：**一期不做**（内部工具，省事；Element Plus 原生支持，将来要做再开）。
9. **禁止**：不堆行内 `style`（动态绑定单值除外）；不用 `!important`（覆盖第三方且无他法时例外，须加注释）；不引 CSS-in-JS / UnoCSS / Tailwind。

---

## 9. 环境变量与配置

CLAUDE.md：配置外化、不硬编码，敏感配置走 `.env`。

1. **Vite 机制**：只有 **`VITE_` 前缀**的变量会注入前端代码（`import.meta.env.VITE_XXX`），无前缀的不暴露。
   **前端 `.env` 绝不放任何密钥**——前端代码公开，打包后人人可见；供应商 API Key 等敏感配置全在后端（库中加密 + 后端 `.env` 主密钥）。
2. **文件分工**：

   | 文件 | 用途 | 入库 |
   |---|---|---|
   | `.env.development` | 开发环境变量（含公共默认值） | 是 |
   | `.env.production` | 生产环境构建变量（含公共默认值） | 是 |
   | `.env.local` / `.env.*.local` | 个人本地覆盖 | 否（`.gitignore`） |

   前端这些都不含密钥，故可入库；真正的密钥只在后端 `deploy/.env`（不入库）。
   **注意**：仓库根 `.gitignore` 全局忽略 `.env`（保护后端 `deploy/.env` 这类含密钥文件），因此前端**不使用 `web/.env`** 这个文件——公共默认值直接写进 `.env.development` / `.env.production`（这两个文件名不被 `.env` 规则匹配，正常入库）。不为了一个无密钥文件去削弱仓库级的安全兜底。
3. **base URL 用相对路径 `/api/v1`**：开发靠 Vite proxy 转发到 server，生产靠 nginx 反代，前端代码两端一致、零改动（同源部署，不开 CORS——见 api-standards.md 6）。
4. **Vite proxy（开发）** 配在 `vite.config.ts`，target（如 `http://localhost:8080`）用**非 `VITE_` 前缀**变量（经 `loadEnv` 读），不泄漏到前端产物：
   ```ts
   server: {
     proxy: {
       '/api': { target: env.SERVER_PROXY_TARGET, changeOrigin: true },
       '/v1':  { target: env.SERVER_PROXY_TARGET, changeOrigin: true },
     }
   }
   ```
5. **配置集中读取**：不在业务代码散落 `import.meta.env.VITE_XXX`，集中到 `src/config/index.ts` 读取并导出类型化常量（类型转换、改名都只在一处）。`env.d.ts` 给 `ImportMetaEnv` 补类型享受补全。
   ```ts
   export const config = {
     apiBaseUrl: import.meta.env.VITE_API_BASE_URL,
     apiTimeout: Number(import.meta.env.VITE_API_TIMEOUT),
   }
   ```

---

## 10. 代码质量

- **ESLint 9 flat config**（`eslint.config.js`）+ `eslint-plugin-vue` + `typescript-eslint`，配合 Prettier（`eslint-config-prettier` 关掉与格式冲突的规则）。
- **类型检查**：`vue-tsc --noEmit`，纳入构建与 CI（已挂在 `pnpm build` 前）。
- **提交前检查**：`web/` 提供 `lint-staged` 配置（只对暂存文件跑 lint + format），但**不在 `web/` 子包内安装 git 钩子**。
  *为什么不在 web 内装钩子*：`web/` 是 monorepo 子目录，`.git` 在仓库根（`hify/`）。simple-git-hooks/husky 这类工具应安装在 git 根、统一覆盖 `server/`（Java）与 `web/`（前端）。子包内安装会写错位置或与未来的根级钩子打架。**pre-commit 钩子待仓库根统一规划**（根钩子里 `cd web && pnpm exec lint-staged` 调用本包配置）。在此之前，提交前手动 `pnpm lint && pnpm format`，或依赖 CI 兜底。
- **格式**：交给 Prettier 统一，不在 ESLint 里重复定义格式规则，避免两套打架。
