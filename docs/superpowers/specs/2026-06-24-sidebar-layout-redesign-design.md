# 侧边栏与布局壳改造 · 设计文档

> 日期：2026-06-24
> 范围：`web/` 前端布局壳（深色侧边栏 + 顶栏）、两个复用组件（PageHeader / ContentCard），
> 并把已有实页（UserList、ProviderList）改用新结构。沿用方案 A「开发者工具风」设计 token，本次只新增侧栏深色一组 token，不动主色板。

## 1. 背景与目标

设计 token（`variables.scss`）与 Element Plus 主题覆盖（`index.scss`）已就绪，但布局壳简陋、各业务页套用程度参差不齐：

- 侧边栏：浅色、无图标、无折叠、Logo 朴素。
- 顶栏：仅右侧一个裸露的「退出」文字按钮。
- 业务页：无统一的「标题 + 描述 + 操作区」结构，内容不套白卡片；`UserList` 样式里散落魔法值（`16px/12px`、直接 `var(--el-*)`），违反「引 scss 变量、不写魔法值」规范。

**目标**：把布局壳改造成有科技感、克制不花哨的深色侧栏 + 清爽顶栏，并立一套「页面标题区 + 白卡片内容」的页面骨架约定，落到已有实页。

**非目标**：不重做方案 A 的视觉方向；不给空壳页（AppList / KnowledgeList，当前无功能）加功能——它们等建功能时自然套用本约定。

## 2. 设计 token 变更（`src/styles/variables.scss`）

新增「侧边栏深色主题」一组（集中管理，组件不写魔法值）：

```scss
// ── 侧边栏深色主题（与浅色内容区形成对比）──
$color-bg-dark:        #17181d;             // 侧栏底色：近黑带一丝冷调，非纯黑
$sidebar-hover-bg:     rgba(255, 255, 255, 0.10); // 菜单 hover 背景
$sidebar-active-bg:    rgba(94, 106, 210, 0.18);  // 选中背景：主色淡染，与 hover 区分
$sidebar-active-bar:   $color-primary;      // 选中态左侧 3px 主色竖线
$sidebar-text:         rgba(255, 255, 255, 0.72); // 菜单默认文字
$sidebar-text-active:  #ffffff;             // hover / 选中文字（提亮到纯白）
$sidebar-text-muted:   rgba(255, 255, 255, 0.38); // 副标题、版本号
```

设计取舍：

- **默认文字 72% 白而非纯白**：纯白在深底上偏刺眼，且 hover/选中再提亮时无对比变化。默认稍灰、激活变纯白，层次更清楚。（用户已确认）
- **hover 与 active 用不同色相区分**：hover 是中性白 10%，active 是主色淡染 18% + 左侧主色竖线，二者一眼可辨。
- **间距沿用现有 4 倍数尺度**，不新增 20px：页面边距 `$spacing-xl`(24px)、卡片内边距与元素间距 `$spacing-lg`(16px)。

内容区灰底沿用现有 `$color-bg-page`(#fafafa)——即用户口中的 `--color-bg-secondary`，不再造近似色。

## 3. 侧边栏（`DefaultLayout.vue`）

### 3.1 顶部 Logo 区
- **「Hify」**：主色渐变文字，`background: linear-gradient(135deg, #6e7bff, #a78bfa)` + `background-clip: text` + 透明文字色（靛蓝→紫，科技感）。
- 副标题 **`AI Agent Platform`**：`$sidebar-text-muted`、字号 `$font-size-xs`、轻微 `letter-spacing`。
- 折叠态：Logo 收成渐变「H」，副标题隐藏。

### 3.2 菜单项 + 图标
- 图标走路由 `meta.icon`（存**图标名字符串**，布局内用 registry 表解析为组件，路由表保持纯元数据，不在 router 里 import 组件）。
- 实际菜单项图标映射：

  | 菜单（meta.title） | 图标（@element-plus/icons-vue） |
  |---|---|
  | 知识库管理 | `Collection` |
  | 应用管理 | `Grid` |
  | 模型提供商管理 | `Setting` |
  | 用户管理 | `User` |
  | 样式预览（开发期） | `Brush` |

  > 对话菜单项当前不存在（对话页未建）；建后按用户约定补 `ChatDotRound`。

- 交互态（深色覆盖 Element Plus `el-menu`，经 `:deep()`）：
  - 默认：透明底 + `$sidebar-text`。
  - hover：背景 `$sidebar-hover-bg`、文字 `$sidebar-text-active`。
  - 选中：左 3px `$sidebar-active-bar` 竖线 + 背景 `$sidebar-active-bg` + 文字 `$sidebar-text-active`。

### 3.3 底部
- **折叠/展开按钮**：切换 `collapsed`，传给 `el-menu` 的 `:collapse`，侧栏宽 220px ↔ 64px（折叠只剩图标）。
- **版本号**：`v{__APP_VERSION__}`，`$sidebar-text-muted`；版本经 Vite `define` 从 `package.json` 注入，不硬编码。
- 折叠态**仅会话内存保持**（local `ref`，不持久化，刷新复位）——与规范「只持久化 token」一致。将来要刷新也记住，再引一个 UI store。

## 4. 顶栏（`DefaultLayout.vue` header）

- **左侧面包屑**：`el-breadcrumb`，从路由派生。路由扁平，故约定：
  - admin 页显示两级 `管理控制台 / 用户管理`；其余页单级 `知识库管理`。
  - 靠路由 `meta.group?: string` 实现：写了就作为第一级，不写则只显示页面 `title`。
- **右侧用户区**：`el-avatar`（无头像图，用**用户名首字母**占位）+ 用户名 + `el-dropdown`，把现「退出」收进下拉项（调用既有 `userStore.logout()`）。

## 5. 复用组件（`src/components/`）

全站重复的「页面标题区」与「白卡片」抽成两个业务无关组件：

### 5.1 `PageHeader.vue`
- props：`title: string`、`description?: string`。
- 默认插槽：右侧操作区（按钮等）。
- 渲染在灰底上，下方留 `$spacing-lg` 间距。

### 5.2 `ContentCard.vue`
- 白底（`$color-bg-card`）+ `$shadow-sm` + `$radius-md` + 内边距 `$spacing-lg`。
- 默认插槽放表格/表单内容。

页面统一结构：

```vue
<PageHeader title="用户管理" description="管理团队成员账号与角色">
  <el-button type="primary">新建用户</el-button>
</PageHeader>
<ContentCard>
  <el-table ... />
</ContentCard>
```

## 6. 受影响文件清单

| 文件 | 变更 |
|---|---|
| `src/styles/variables.scss` | 新增侧栏深色一组 token |
| `src/types/router.d.ts` | `meta` 加 `icon?: string`、`group?: string` |
| `src/router/index.ts` | 各菜单路由补 `icon`；admin 路由补 `group: '管理控制台'` |
| `src/router/menu.ts` | `MenuItem` 加 `icon`；`buildMenu` 带出 icon |
| `src/layouts/DefaultLayout.vue` | 深色侧栏 + Logo 区 + 图标菜单 + 折叠底栏 + 顶栏面包屑/用户区（主要工作量） |
| `src/components/PageHeader.vue` | 新增 |
| `src/components/ContentCard.vue` | 新增 |
| `src/views/admin/identity/UserList.vue` | 改用 PageHeader/ContentCard；清魔法值，统一引 scss 变量 |
| `src/views/admin/provider/ProviderList.vue` | 改用 PageHeader/ContentCard |
| `vite.config.ts` | `define: { __APP_VERSION__: JSON.stringify(pkg.version) }` |
| `src/config/index.ts` | 导出 `appVersion`（读 `__APP_VERSION__`） |
| `src/env.d.ts`（或 `*.d.ts`） | 声明全局常量 `__APP_VERSION__: string` |

## 7. 测试策略

- **纯逻辑先行（TDD）**：`buildMenu` 带出 `icon`、面包屑派生函数（若抽成纯函数 `buildBreadcrumb(route)`）先写/改 vitest 单测。
- **组件**：`PageHeader` 渲染 title/description/插槽、`ContentCard` 渲染插槽——写浅层渲染断言。
- **视觉部分**（侧栏深色、渐变、折叠态）靠 styleguide / 实页人工验收，不写脆弱的样式快照测试（呼应 testing-standards「不写假测试」）。

## 8. 验收口径

- 侧栏深色、Logo 渐变、图标齐全、hover/选中态符合 §3.2、折叠可用、版本号显示。
- 顶栏面包屑随路由变化、用户区头像+用户名+退出下拉可用。
- UserList / ProviderList 套用新骨架，内容在白卡片内、与灰底有层次，且 UserList 无魔法值。
- `pnpm lint`、`pnpm build`（含 `vue-tsc`）、`pnpm test` 通过。
