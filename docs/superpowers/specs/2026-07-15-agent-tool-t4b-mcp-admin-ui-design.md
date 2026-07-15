# T4b 设计：MCP 内网白名单（后端小改）+ MCP admin 注册页（前端）

> ② Agent/tool 模块第四（最后）子轮的**前端半 + 一个后端小 Task**。前置轮 T1/T2/T3a/T3b/T4a
> 均已合并 main（T4a 见 `2026-07-15-agent-tool-t4a-mcp-backend-design.md`）。
> 本轮完成后 ② 整个 Agent/tool 方向收尾。

## 1. 背景与目标

### 为什么要重议「禁内网」（T4a 决策 3 的既定演进路径被触发）

T4a 拍板「MCP 地址维持禁内网」后，用户明确 **会自建 MCP 服务器**——自建必然在内网/localhost，
会被 `SsrfValidator` 全部拒掉（10001）。现状变成「能连的（公网免费服务）不会真用，要用的（自建）连不上」。
这不是返工：`SsrfValidator` 类注释与 `deployment.md` §5 都预留了「真有需求时加白名单」，
T4a spec §9 也写明「连不上/要用内网 ⇒ 回头重议决策 3」。本轮就是那次重议的落地。

### T4b 目标

1. **后端**：MCP 出站的内网白名单（本文 §3），让自建 MCP 可注册、可调用。
2. **前端**：在 T3b 已有的 `/admin/tool` 页面上补齐 MCP 注册能力（本文 §4）——
   目前 `web/` 零 mcp 代码，MCP 注册只能 curl；T4a 后端接口已全部就绪、契约已定死。

### 非目标（明确排除）

- HTTP 请求节点、内置 HTTP 工具、OpenAPI 自定义工具的内网放开（见 §2 决策 1 威胁模型）。
- 白名单的 admin UI 在线编辑（方案 B，见 §2 决策 2；真有高频修改需求再议）。
- 网段（CIDR）粒度白名单（YAGNI：自建服务器是可数的几台）。
- 后端任何新 API、新表、新错误码——T4a 契约原样消费。
- 前端展示 MCP 工具的 inputSchema（后端 `McpToolView` 本就只回 toolName/description，UI 够用即可）。

## 2. 关键决策（已与用户逐条拍板，2026-07-15）

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| 1 | 白名单生效范围 | **仅 MCP 出站**；HTTP 节点、内置 HTTP 工具维持禁内网 | 威胁模型看「谁控制 URL」：MCP 地址仅 admin 注册（受信）；HTTP 节点 URL 任何成员可填且可拼运行时变量、内置 HTTP 工具的 URL 由模型决定（提示注入可操纵）——后两类放开等于把内网暴露给全团队甚至一段被注入的提示词 |
| 2 | 白名单存哪、谁能改 | **方案 A：yml 配置** `hify.tool.mcp.allowed-private-hosts`，运维改配置 + 重启生效 | ① 自建 MCP 是稳定基础设施，新增一台本来就是运维动作，顺手改配置零额外负担；用户既是 admin 又是运维，方案 B（system_setting + admin UI 在线改）的体验优势为零。② 模块边界：`SsrfValidator` 在 infra（只准依赖 common），`system_setting` 的 mapper 在 provider，tool 依赖白名单是「无」——「查 system_setting」真做要打穿模块边界（依赖反转或挪表），为低频操作付常驻复杂度不值。③ MCP 出站闸门本就收口在 tool 模块 `McpClientFactory`，它已有配置类 `McpProperties`（`hify.tool.mcp.*`），白名单放这里零边界问题、零新 API、零新表。方案 C（infra 全局白名单）被否：对成员/模型控制的 URL 也生效，安全面无谓变宽，违背决策 1 |
| 3 | 白名单粒度 | **精确 host 字符串**（域名或 IP 字面量），忽略大小写；不做 CIDR | `SsrfValidator.validate(host)` 收到的就是 host 字符串，精确匹配最简单、最不会错；代价是 `localhost` 与 `127.0.0.1` 算两个条目，配置时写实际注册用的那个（文档写明）。字符串匹配零额外 DNS 查询、零解析歧义 |
| 4 | 轮次切分 | **白名单与 T4b 合成一轮**：白名单作首个后端 Task，其余全是前端 | 方案 A 后端改动极小（一个配置项 + 一个 if + 测试），单独立轮的流程开销远大于其本身；且人工验收天然一体——验证白名单放行恰好需要连自建/本地 MCP，那也是注册页的验收路径 |
| 5 | 编辑 MCP 行时不提供「试连接」 | 编辑抽屉展示当前工具快照 + `discoveredAt`，不放试连接按钮 | 编辑时鉴权头值为空（明文永不回传的安全设计），试连接会不带凭据去连、对有鉴权的服务器必然假失败，徒增困惑；「保存」（PUT）本身就会在服务端用保留的旧密文重新发现，出错自然报 13002；「只想同步清单」有列表行的「刷新」按钮 |

## 3. 后端：MCP 内网白名单

### 3.1 配置（`application.yml`，沿用既有 `${ENV:default}` 风格）

```yaml
hify.tool.mcp:
  # MCP 内网白名单：命中的 host 跳过 SSRF 禁内网校验（仅 MCP 出站生效，T4b 决策 1/2）。
  # 精确 host 匹配（忽略大小写），逗号分隔；localhost 与 127.0.0.1 是两个条目。
  # 例：HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS=host.docker.internal,192.168.1.10
  allowed-private-hosts: ${HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS:}
```

- `McpProperties` 加字段 `private List<String> allowedPrivateHosts = List.of();`（+ getter/setter）。
- 逗号分隔字符串由 Spring Boot 宽松绑定转 `List<String>`；**默认空串须绑定为空列表**
  （行为与今天完全一致），用测试钉住。

### 3.2 判定逻辑（只动 `McpClientFactory`，`SsrfValidator` 一行不改）

`McpClientFactory.validate(url)` 现有校验顺序不变：URI 合法 → scheme 必须 http/https →
必须有 host。**只在最后一步**改为：

```java
if (!isAllowedPrivateHost(uri.getHost())) {
    ssrfValidator.validate(uri.getHost());
}
// isAllowedPrivateHost = allowedPrivateHosts 里任一条目 equalsIgnoreCase(host)
```

白名单**只豁免「内网禁区」这一条**；`followRedirects(NEVER)`、连接/请求/握手三重超时、
http/https 限定全部保留——即使白名单内的自建服务器被攻破回 302 指向云元数据，重定向禁令仍挡着。

### 3.3 测试（JUnit，扩展现有 `McpClientFactoryTest` 或就近新增）

1. host 命中白名单（含大小写不一致）→ 不调 `SsrfValidator`、正常建 client
   （可注入会对内网抛 10001 的真 `SsrfValidator` 验证「确实跳过了」）。
2. host 未命中 → 照旧被 `SsrfValidator` 拒（10001），行为与 T4a 一致。
3. 白名单为空（默认）→ 全部照旧拒。
4. `McpProperties` 绑定：空串环境变量 → 空列表；逗号分隔 → 多条目。

## 4. 前端：`/admin/tool` 页补 MCP（改 T3b 的 `ToolList.vue`，不开新页面、不加路由/菜单）

### 4.1 列表变化

- **「类型」列**从两种 tag 变三种：`内置`(info) / `自定义`(primary) / `MCP`(warning)，按 `source` 判定。
- **「操作数」列改名「操作/工具数」**：mcp 行显示工具数（后端 `ToolAdminResponse.operationCount`
  已按 T4a 约定填工具数，字段名不动）。
- **mcp 行操作列多一个「刷新」按钮**：调 `POST /api/v1/admin/tool/tools/{id}/refresh`
  （重新发现工具清单，凭据用库中密文，不用重填），成功 toast「已刷新，共 N 个工具」
  （N 取响应 `operationCount`）后重载列表。无需二次确认（非破坏性动作）。
  openapi / builtin 行不显示该按钮。

### 4.2 注册/编辑抽屉（复用现有 el-drawer，不另建组件文件；若 `ToolList.vue` 超 ~300 行则按
frontend-standards §5.5 把抽屉拆成 `views/admin/tool/components/ToolDrawer.vue`）

- **新建**时表单顶部加「类型」`el-radio-group`（`OpenAPI` / `MCP`，默认 OpenAPI——不改 T3b 既有心智）。
- **编辑**时类型锁定不可改（一条注册的 `source` 天生固定，后端也不支持换）。
- 选 **MCP** 时表单为：
  - 名称 / 描述（与 openapi 共用，规则不变：name ≤64、description ≤500）；
  - **服务器地址**（url 输入框，必填）；下方固定提示一行：
    「内网/localhost 地址默认被拒绝，需先在服务端配置 `hify.tool.mcp.allowed-private-hosts` 白名单」。
    前端**不做**内网判断（判不准，后端才是权威；10001/13002 由 request 拦截器统一 toast）；
  - **传输方式** `el-radio-group`：`streamable_http`（标「默认」）/ `sse`（标「兼容旧服务器」）；
  - **鉴权头**动态列表（完全复用现有交互；编辑时头名回填、值空占位「留空=不改」）；
  - **「试连接」按钮**（仅新建时显示，见 §2 决策 5）：调 preview（type=mcp，带 url/transport/authHeaders），
    成功列出发现的工具（toolName + description）并 toast「连接成功，发现 N 个工具」；失败（13002/10001）
    拦截器 toast，抽屉保持打开。
  - **编辑 mcp 行**：额外展示当前工具快照列表 + 「上次发现于 {discoveredAt}」
    （`new Date(discoveredAt).toLocaleString()` 展示；项目未引 dayjs，不为此引——frontend-standards §1 按需后引）。
- 提交前端预检：openapi 必须有 specText、mcp 必须有 url（后端 `@AssertTrue` 报错字段名是
  `payloadValid` 无法精准标红，T4a spec §5.2 已知局限，前端先拦一道即此意）。
- 提交 body：mcp 时带 `type:'mcp', url, transport, authHeaders`（不带 specText）；
  openapi 时**不传 type**（与 T3b 现状字节级一致，顺带回归验证后端「type 缺省=openapi」兼容路径）。

### 4.3 API 层与类型（签名改动与全部调用点同 Task，见 memory `plan-atomic-signature-boundary`）

`src/types/tool.ts`：
- `ToolForm` 加 `type: 'openapi' | 'mcp'`、`url: string`、`transport: string`；
- `ToolAdminDetail` 加 `url: string | null`、`transport: string | null`、`tools: McpToolItem[]`、
  `discoveredAt: string | null`（时间是 ISO-8601 string，api-standards §4）；
- `ToolPreview` 加 `tools: McpToolItem[]`、`baseUrl` 改 `string | null`（mcp 时后端回 null）；
- 新增 `McpToolItem { toolName: string; description: string }`（对齐后端 `McpToolView`）。

`src/api/admin/tool.ts`：
- `previewTool(specText: string)` **签名改为收对象** `previewTool(body: ToolPreviewBody)`
  （openapi 传 `{specText}`，mcp 传 `{type:'mcp', url, transport, authHeaders}`）。
  调用点已全量 grep（不带 `new` 前缀）：`views/admin/tool/ToolList.vue:121`、
  `views/admin/tool/__tests__/ToolList.spec.ts`（mock + 2 处断言）——与签名同 Task 改完。
- 新增 `refreshTool(id: string)` → `POST ${BASE}/${id}/refresh`，返回 `ToolAdminItem`。

### 4.4 消费的后端契约（T4a 已定死，本轮零后端接口改动）

| 接口 | mcp 语义 |
|---|---|
| `POST /tools` | `type='mcp'` + url/transport/authHeaders → 发现并落库 |
| `POST /tools/preview` | `type='mcp'` → 试连接并列工具，不落库 |
| `GET /tools/{id}` | mcp 行回 `url/transport/tools/discoveredAt/authHeaderNames`（永不回明文凭据） |
| `PUT /tools/{id}` | 全量更新 + 重新发现；鉴权头值留空=保留旧密文 |
| `POST /tools/{id}/refresh` | 重新发现，返回 `ToolAdminResponse` |
| `DELETE` / `enable` / `disable` | 对 mcp 行已放开（`assertNotBuiltin`），前端按钮逻辑不变 |
| 错误码 | `13002/400` 连接或发现失败；`10001/400` 参数/SSRF 拒绝；`10006/409` 重名 |

## 5. 测试

前端 vitest + TDD（新代码先写失败测试，测试在 `__tests__/`；沿用 T3b 摸出的 happy-dom 桩：
el-drawer 透传桩、`data-test` 直达内部 input）。最值钱的：

1. 新建抽屉切到 MCP → openapi 字段隐藏、url/transport/鉴权头/试连接出现；提交 body 含
   `type:'mcp'`、不含 specText。
2. openapi 提交 body **不含 type**（守 T3b 兼容路径）。
3. 试连接：`previewTool` 收到 mcp body，工具清单渲染。
4. 编辑 mcp 行：url/transport 回填、头名回填值空、快照与 discoveredAt 展示、无试连接按钮、类型不可改。
5. 列表：mcp 行 tag、刷新按钮只在 mcp 行、`refreshTool` 调用与成功 toast。
6. 后端见 §3.3。

回归判定：`mvn clean test` 全量（严禁 grep BUILD SUCCESS 判绿，见 memory）+ `pnpm vitest run` +
`pnpm build`（vue-tsc）。

## 6. 人工验收（两条线）

1. **公网线（回归 T4a + 新 UI）**：注册页填 DeepWiki `https://mcp.deepwiki.com/mcp`（streamable_http）
   → 试连接见 3 工具 → 保存 → 列表出现 MCP 行 → 刷新按钮可用 → Agent 应用勾选后试聊出
   `deepwiki__*` 工具轨迹。
2. **白名单线（本轮新增能力的端到端证据）**：本地起一个 MCP 服务器（可复用 T4a 测试桩思路或任一
   本地 MCP 实现）→ 不配白名单注册被拒 10001（提示语可见）→ `.env`/yml 加
   `HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS` → **重启 server（须重打包+换进程，见 memory
   `retrieval-threshold-tuned` 的重启坑）** → 注册成功 → Agent 调用成功。
3. 顺带回归：openapi 工具注册/编辑/预览在改版后的抽屉里仍正常（类型 radio 默认 OpenAPI）。

## 7. 需同步更新的架构文档（拍板结论入档）

- `deployment.md` §5：「内网白名单暂缓（机制预留：SsrfValidator 查 system_setting 放行）」改为
  已实现的 yml 方案，写明**仅对 MCP 生效** + 决策 1 的威胁模型理由（谁控制 URL）。
- `SsrfValidator` 类注释：末行「内网白名单为推迟项…查 system_setting 放行」已过时，改为指向
  「MCP 白名单在 tool 模块 `hify.tool.mcp.allowed-private-hosts`（T4b 决策 2），本类维持无差别禁内网」。
- CLAUDE.md 安全要点行「…统一过 SSRF 防护（禁内网与元数据地址…）」补半句
  「（MCP 可经 yml 白名单放行自建服务器，见 deployment.md §5）」。
- `deploy/.env.example`：补 `HIFY_TOOL_MCP_ALLOWED_PRIVATE_HOSTS` 示例（值留空）。

## 8. 风险与留账

| 项 | 说明 |
|---|---|
| 白名单改动要重启 | 方案 A 的固有代价，拍板接受（低频运维动作）；真变高频再升级方案 B，届时须先解模块边界（决策 2 已记录三条路） |
| 白名单 host 被攻破 | 豁免仅限禁内网一条，重定向禁令/超时仍在；且名单由运维手配，攻击面=已受信的自建机器 |
| OpenAPI 工具将来也要连内网 | 同款需求出现时把判定从 `McpClientFactory` 上提或复制到 openapi 出站处即可，配置键另立（如 `hify.tool.openapi.*`），本轮不预做 |
| 编辑抽屉无试连接 | 决策 5 的取舍；改了 url 想验证 → 保存时服务端重新发现，失败报 13002 且不落库（T4a update 语义） |
| `operationCount` 字段名带 openapi 味 | T4a 已拍板保留原名（改名弄坏 T3b 前端），前端列名改「操作/工具数」对冲 |
| T4b 完成后 ② 收尾 | 剩余方向见 memory `next-round-agent-tool`：①对外 API 仍推迟到有调用方 |
