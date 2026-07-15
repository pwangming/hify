# T3b：OpenAPI 自定义工具 admin 注册表页（前端 + 2 处后端小改动）

> ② Agent/tool 模块第 3 子轮的后半。前置：T3a（OpenAPI 自定义工具后端，见
> `2026-07-14-agent-tool-t3a-openapi-backend-design.md`）已交付后端 CRUD/解析/执行/加密，
> 契约定死；本轮补前端注册表页，并做 2 处必要的后端补强。后续：T4=MCP 接入（独立轮）。

## 1. 背景与目标

T3a 交付了 OpenAPI 自定义工具的**后端全套**（`/api/v1/admin/tool/tools` CRUD + 启停、
swagger 解析、Model D 读时展开、鉴权头加密、执行走 OutboundHttpClient），但**没有任何界面**
（`web/src/views/admin/tool/` 只有 `.gitkeep`）。因此 T3a 无法做真实 server 端到端冒烟，
留账并入本轮。

本轮目标：让 admin 能在管理控制台**注册 / 预览 / 编辑 / 启停 / 删除**自定义工具，并把该工具
在 Agent 应用里勾选试聊，端到端验证整条链路。

**非目标**（YAGNI）：
- 不做工具分组、搜索、分页（一期工具数量少，全量列表足够）。
- 不做 spec 在线编辑器 / 语法高亮，纯文本框粘贴即可。
- 不解读 OpenAPI `security` 段自动生成鉴权（沿用 T3a：通用静态注入请求头）。
- 不给内置工具任何写操作入口（内置只读）。

## 2. 后端补强（2 个小改动）

T3a 契约总体够用，本轮只补两处**必要**改动，均对齐已有约定、不破坏已发布契约。

### 2.1 新增预览接口（只解析不落库）

注册前让 admin 先看到 spec 解析出的操作列表 / baseUrl，或立即看到解析报错，而不是「先落一条
废记录再去详情看」。

- 路由：`POST /api/v1/admin/tool/tools/preview`（集合级动作子资源，非 CRUD；一期不用 PATCH，
  动作走 POST，符合 api-standards）。`hasRole(ADMIN)` 由 `/api/v1/admin/**` 统一拦。
- 请求：`PreviewToolRequest{ @NotBlank String specText }`。
- 响应：`ToolPreviewResponse{ String baseUrl, List<OperationView> operations }`
  （复用 T3a 既有 `OperationView`）。
- 实现：`ToolAdminService.preview(specText)` 仅调 `OpenApiSpecParser.parse(specText)`，
  映射 baseUrl + operations 返回，**不写库、不加密**（预览不涉及鉴权头）。
- 错误：解析失败照旧抛 `ToolError.SPEC_PARSE_FAILED(13001/400)`，复用 T3a 逻辑。
- 协议层无业务逻辑，Service 方法**非 `@Transactional`**（纯读、无外部 IO 之外的解析）。

### 2.2 update 改「头值留空=保留原密文」

**问题**：详情接口 `ToolAdminDetailResponse` 只回 `authHeaderNames`（永不回明文/密文值）。
现状 `update → buildSpec(specText, authHeaders)` 用请求里的 authHeaders **整体重建**鉴权头。
admin 若只想改名字、不重填头值，PUT 会把原有鉴权头**清空**，一个能用的工具被编辑一下就废了。
前端拿不到明文值，无法「原样重发」，纯前端修不了。

**方案**：镜像 provider `apiKey` 已有约定「**留空=不改**」：
- `UpdateToolRequest.authHeaders` 中某项 `value` 为空白串 → 该头**保留旧记录里的 `valueEnc`**
  （按 `name` 匹配旧 spec 的 AuthHeader）；`value` 非空 → 重新 `cipher.encrypt`。
- 请求里**不出现**的头名 = 删除该头（全量替换语义仍在，只是「空值」表示保留）。
- create 不变（create 无「旧值」，空值即空值，交由 @NotBlank 在 AuthHeaderInput 上拦——
  注：现状 `AuthHeaderInput.value` 有 `@NotBlank`；本轮**放开 update 路径的空值**：
  见下「实现取舍」）。

**实现取舍**：`AuthHeaderInput.value` 现有 `@NotBlank`，会挡住 update 的「留空」。两个做法：
- (A) 去掉 `AuthHeaderInput.value` 的 `@NotBlank`，改由 service 分流：create 时空值报错
  （复用 13001 或 CommonError 参数校验段），update 时空值=保留。
- (B) 新增独立的 update 专用 header 输入类型（value 可空）。

**采纳 (A)**：少加类型、契约面更小；create 的空值在 service 里显式校验（错误码走通用参数段，
不新增码）。`buildSpec` 拆出 update 专用重载 `buildSpecForUpdate(specText, headers, oldSpec)`，
按 name 回填旧 `valueEnc`。

> 两处改动都**只增不改**已发布对外契约：preview 是新路由；update 的「空值语义」是放宽行为
> （原来空值被 @NotBlank 挡，现在空值=保留），不影响老调用方（老调用方本就传全量非空值）。

## 3. 前端页面（`web/src/views/admin/tool/`）

### 3.1 路由与菜单

`src/router/index.ts` 新增：

```
{
  path: '/admin/tool',
  name: 'ToolList',
  component: () => import('@/views/admin/tool/ToolList.vue'),
  meta: { requiresAuth: true, roles: ['admin'], title: '自定义工具',
          menu: true, icon: 'Connection', group: '管理控制台' },
}
```

菜单由路由 `meta.menu` 自动派生（见 menu.ts `buildMenu`），无需手维护。

### 3.2 ToolList.vue（一页搞定）

参考 `ProviderList.vue`（列表 + 抽屉，而非 dialog）。

**表格列**：
| 列 | 内容 |
|---|---|
| 名称 | `name` |
| 类型 | el-tag：`builtin`→「内置」(info)，`openapi`→「自定义」(primary) |
| 描述 | `description`（超长省略） |
| 操作数 | `operationCount ?? '—'`（内置为 null → 显示 `—`） |
| 状态 | `enabled` → el-tag「启用/停用」 |
| 操作 | **自定义行**：编辑 / 启用·停用 / 删除；**内置行**：无按钮（只读） |

右上角「注册工具」按钮 → 打开注册抽屉。

**加载**：`onMounted` 调 `listTools()`（admin），全量渲染。

### 3.3 注册/编辑抽屉（el-drawer，复用同一组件/同一文件内）

字段：
- 名称（`@Size(max=64)`）、描述（`@Size(max=500)`）。
- **spec 文本框**：`el-input type=textarea`，粘贴 OpenAPI（JSON/YAML）。
- **鉴权头**：动态行列表（名 + 值 + 删除），可「+ 添加请求头」。
- **「预览操作」按钮**：调 `previewTool({specText})` → 抽屉内渲染 `baseUrl` + 操作列表
  （opName / method / pathTemplate / description）；解析失败 toast 显示后端 message。
- 底部：取消 / 保存。

**新建流程**：填名/描述/spec/头 →（可选）预览 → 保存 `createTool(form)` → 关抽屉 → 刷新列表。

**编辑流程**：点「编辑」→ `getTool(id)` 回填 `name/description/rawSpec(→specText)`，
操作列表直接用详情返回的 `operations`；鉴权头用 `authHeaderNames` 预填**头名**，
**值框留空 + 占位提示「留空=不改」**（对应 2.2 后端语义）→ 保存 `updateTool(id, form)`。

**保存时头值组装**：form 里每行 `{name, value}` 原样发；编辑态未改的头 value 为空串
→ 后端保留原密文。

### 3.4 API 层 `src/api/admin/tool.ts`

`BASE = '/admin/tool/tools'`（baseURL 已含 `/api/v1`）。函数：
`listTools` (GET BASE) / `createTool` (POST) / `getTool` (GET /{id}) /
`updateTool` (PUT /{id}) / `removeTool` (DELETE /{id}) /
`enableTool` (POST /{id}/enable) / `disableTool` (POST /{id}/disable) /
`previewTool` (POST /preview)。

> 命名避免与 T2 已有 `src/api/tool.ts::listTools`（成员侧 GET /tool/tools）冲突：
> admin 侧放 `src/api/admin/tool.ts`，导入时按模块路径区分。

### 3.5 类型 `src/types/tool.ts`（在 T2 已有 `ToolOption` 基础上补）

- `ToolAdminItem`：对应 `ToolAdminResponse`（id/name/description/source/enabled/
  operationCount/ownerId/createTime/updateTime）。
- `ToolOperation`：对应 `OperationView`（opName/method/pathTemplate/description）。
- `ToolAdminDetail`：对应 `ToolAdminDetailResponse`（+ baseUrl/operations/authHeaderNames/rawSpec）。
- `AuthHeaderInput`：`{ name: string; value: string }`。
- `ToolForm`：`{ name; description; specText; authHeaders: AuthHeaderInput[] }`（对应 Create/Update）。
- `ToolPreview`：`{ baseUrl: string; operations: ToolOperation[] }`。

> Long 一律字符串（api-standards）：`id`/`ownerId` 前端按 `string` 接。

## 4. 错误处理

统一走 `api/request.ts` 的拦截器 + toast（前端标准）。关注码：
- 13001 SPEC_PARSE_FAILED：spec 写错 / 相对 baseUrl / servers 缺失 → toast 后端 message。
- 10006 重名、10005 不存在、10001 内置拒改：统一 toast（前端只读展示内置，正常不会触发 10001）。
- preview 与 save 的解析失败共用 13001，前端表现一致（toast + 不关抽屉，便于改）。

## 5. 测试（vitest + TDD，先写失败测试）

**前端 `web/src/views/admin/tool/__tests__/ToolList.spec.ts`**（API 层 mock）：
1. 列表渲染：内置行打「内置」标签且**无操作按钮**；自定义行有编辑/启停/删除；`operationCount`
   为 null 显示 `—`。
2. 注册流程：打开抽屉 → 填表 → 点预览 → 断言渲染出操作列表 → 保存 → 调 `createTool` + 刷新。
3. 预览失败：`previewTool` reject（13001）→ toast 出现、抽屉不关。
4. 编辑回填：`getTool` 返回详情 → 名称/描述/spec 回填、头名预填且**值框为空占位**；保存调
   `updateTool`。
5. 启停 / 删除：点按钮调对应 API 并刷新（删除有二次确认）。

**后端**（沿用 T3a 测试风格；`mvn clean test` 全量判定，见 [[mvn-quiet-verify-pitfall]]）：
1. `preview`：合法 spec → 返回 baseUrl + operations；非法 spec → 抛 13001。
2. `update` 留空语义：先 create 带 1 个头（有值）→ update 传同名头 value 空串 + 改 name →
   断言 name 变、该头 `valueEnc` **不变**（保留旧密文）；update 传新值 → 断言重新加密（值变）。
3. `update` 删头：update 不带某头名 → 断言该头消失。

## 6. 验收（端到端冒烟，合并 T3a 留账）

1. `mvn clean test` 全量绿（含 Modularity / LayerRules，tool 仍无 provider 依赖）+ 前端 vitest 绿。
2. 起服务（重打包换进程，见 [[retrieval-threshold-tuned]]）。
3. 管理台「自定义工具」→ 注册一个**公网** OpenAPI 工具（SSRF 禁内网，别用 httpbin/内网地址）。
4. 列表出现该工具；详情/编辑回填正确、**头名可见但头值不回明文**；改名不丢鉴权头。
5. 启用 → 到某 Agent 应用配置页勾选该工具 → 试聊触发调用 → 看 **tool_call 轨迹**（复用 T2 轨迹卡片）。
6. 停用 / 删除生效。

## 7. 拍板记录（本轮）

- 预览：**加后端 preview 接口**（非「先存再看详情」）——体验优先，改动小。
- 编辑鉴权头：**后端 update 改「值留空=不改」**（仿 provider apiKey），去 `AuthHeaderInput.value`
  的 `@NotBlank`、create 空值在 service 显式拦（方案 A）。
- 表单容器：**el-drawer**（非 dialog）——长表单 + spec 文本框 + 内嵌预览更从容；详情复用抽屉，
  不另建详情页。
- 内置工具行：**只读展示**（标签 + 状态，无任何操作按钮），回避「能否禁用内置工具」的产品问题。
- 菜单：`/admin/tool`，标题「自定义工具」，组「管理控制台」，图标 `Connection`。
