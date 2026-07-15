# T4a 设计：MCP 工具接入（后端）

> ② Agent/tool 模块第四（最后）子轮的**后端半**。前置轮：T1（tool 地基 + 2 内置工具 + 同步
> function-calling 闭环）、T2（app_tool_rel + per-app 工具配置 + 前端 Agent 配置页/轨迹 + 流式 Agent）、
> T3a（OpenAPI 自定义工具后端 + cipher 提 infra）、T3b（OpenAPI 自定义工具 admin 页）均已合并 main。
> 本轮只做后端；前端（admin 页加 MCP 类型 + 刷新按钮）拆到 **T4b**。

## 1. 背景与目标

### ② 的 4 子轮拆分（回顾）
- **T1**（已完成）：`tool` 表（V23）+ `ToolRegistry` + `ToolFacade`（暴露 Spring AI `ToolCallback`）+ 2 内置工具 + conversation 手动 tool-calling 同步循环。
- **T2**（已完成）：`app_tool_rel`（V24）+ per-app 工具配置 + 前端配置页/轨迹 + 流式 Agent（方案一）+ `GET /api/v1/tool/tools`。
- **T3**（已完成）：OpenAPI 自定义工具。T3a=后端（Model D + swagger-parser + cipher 提 infra + admin CRUD）、T3b=前端 admin 注册表页。
- **T4**（本轮，拆成两半）：MCP 工具接入。**T4a=后端**（本文档）、**T4b=前端 admin 页**。

### T4a 目标
让**管理员**填一个远程 MCP 服务器地址（+ 可选鉴权头），系统连过去发现它提供的工具，注册进 tool 统一注册表
（`source=mcp`）。Agent 用的时候，注册表把这条注册**展开成多个工具**（每个 MCP 工具一个），
对 Agent 与 conversation 编排层**完全透明**——复用 T2 已有的 `ToolFacade.getToolCallbacks(ids)`，
conversation/workflow **一行不用改**（与 T3a 同款收益）。

### 非目标（明确排除）
- 前端页面（T4b）。
- **stdio 传输**（本地子进程形态的 MCP 服务器）——见 §2 决策 1。
- **内网 MCP 服务器**——SSRF 防护按 deployment.md §5 一律禁内网，仅能连公网可达的 http/https，
  与 T3a 的自定义 API 姿态完全一致（§2 决策 3）。
- MCP 的 **resources / prompts** 能力——只接 `tools`（对 Agent 而言其余无用）。
- MCP 规范里的 **OAuth 授权流**——只做静态鉴权头注入，与 T3a 鉴权姿态一致。
- **多模态工具结果**——MCP 返回的图片/嵌入资源给占位说明（CLAUDE.md 既定范围：一期不做多模态）。
- 单独启停一条注册里的**某一个 MCP 工具**（Model D 下整条注册一起开关，与 T3a 一致）。
- 升级 Spring AI 版本（§2 决策 2）。

## 2. 关键决策（已与用户逐条拍板）

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| 1 | 传输方式 | **只支持远程 HTTP**（Streamable HTTP 为主，SSE 兼容），**不支持 stdio** | stdio 要求 server 容器内 spawn 子进程执行第三方代码 + 镜像装 Node/Python，正面推翻「不可信代码绝不进 server，代码执行进独立沙箱」的既定安全姿态（CLAUDE.md / deployment.md §5）；且 SSRF/超时对子进程无从施加 |
| 2 | 客户端依赖 | **直接依赖 `io.modelcontextprotocol.sdk:mcp:0.12.1` + 手写 `McpToolCallback`**；不引 `spring-ai-starter-mcp-client` / `spring-ai-mcp`；**Spring AI 版本不动** | 实测：Spring AI 1.0.1 锁 SDK 0.10.0，其中**没有** `HttpClientStreamableHttpTransport`（0.11.0+ 才有），只有已被 MCP 规范取代的 HTTP+SSE，很可能连不上任何现代远程服务器；starter 的自动配置是 **yml 驱动**，而我们是 **DB 驱动**，对我们无用；不引 spring-ai-mcp ⇒ 不存在「它编译期对 SDK 0.10、运行期塞 0.12」的二进制不兼容风险；SDK 依赖极轻（slf4j/jackson/reactor 项目已有 + 小的 json-schema-validator）；手写 ToolCallback 与既有 `BuiltinToolCallback`/`OpenApiToolCallback` 同款同契约（名字前缀、失败返文本不抛） |
| 3 | SSRF 姿态 | **维持禁内网**，与 T3a 完全一致 | 零新增安全面、零新增配置、与 deployment.md §5 明文一致。内网白名单留账给未来轮次（T3a 就是这么留的） |
| 4 | 数据模型 | **沿用 T3a 的 Model D：1 个 MCP 服务器 = `tool` 表 1 行，读时展开成 N 个 ToolCallback**；**零加表**，**废弃 data-model.md 里的 `mcp_server` 表规划** | 与 openapi 分支同构（ToolRegistry 只需一套心智模型）；`tool` 表的 `source` 约束早已含 `mcp`，`spec`/`owner_id` 现成；N 行方案必须实现「远端工具增删改 → 本地行同步」，是纯复杂度来源，Model D 天然免疫（整条快照替换）。代价：per-app 只能勾整个服务器（与 T3a 一致，已是既定非目标） |
| 5 | 发现时机 | **注册时发现并存快照 + admin 手动刷新** | `getToolCallbacks(ids)` 位于**每条消息发送的热路径**（ConversationService 同步与流式两处），实时发现 = 每句话多一次远程往返 + 远端故障拖垮整个对话（连带其他正常工具）+ 流式那处会阻塞 SSE 建流。快照使热路径**零网络开销**，且与 openapi「注册时解析、读时展开」同构 |
| 6 | 连接生命周期 | **每次工具调用建/关**（try-with-resources） | 无状态 ⇒ 无泄漏、无重连、无并发竞争，最容易做对；规模（20-50 人、工具调用低频）扛得住握手开销；真成瓶颈时加缓存是 `McpToolCallback` 内部换 client 来源的**局部改动，不影响任何对外契约** ⇒ 可安全推迟（YAGNI） |
| 7 | `spec` 一列两形状 | **引入普通 interface `ToolSpec` + Jackson 多态**（`kind` 字段）；`OpenApiToolSpec` 原地不动只加 implements | 保住类型安全（现有代码遍布 `spec.operations()`/`spec.baseUrl()`，改 JsonNode 手工解析是 bug 农场且大面积翻动 T3a）；**不用 `sealed`** 是因为无 JPMS 时 sealed 的实现类必须同包，而两个 spec 分处 `service/openapi` 与 `service/mcp` 子包，挪包会牵动 T3a 一堆 import，收益不抵成本 |
| 8 | 轮次粒度 | **拆 T4a 后端 + T4b 前端**，本轮只做 T4a | 与 T3 节奏一致；后端重（新协议 + 新 SDK + spec 多态重构 + 存量数据迁移），先用测试/curl 验通出错面小；**且「禁内网 ⇒ 必须有公网 MCP 服务器可连」这个验收风险会在 T4a 末尾暴露**，那时前端一行未写，损失最小、也来得及回头重议决策 3 |

## 3. 架构与数据流

### 3.1 新增组件（全在 `tool` 模块内，模块依赖白名单「无」不变）

| 组件 | 职责 |
|---|---|
| `service/ToolSpec` | 多态接口，`OpenApiToolSpec` / `McpToolSpec` 都实现 |
| `service/mcp/McpToolSpec` | `spec jsonb` 的 MCP 形状（连接配置 + 工具快照） |
| `service/mcp/McpClientFactory` | 造 `McpSyncClient`：SSRF 校验 → 选传输 → 注入鉴权头 → 禁重定向 → 双超时 |
| `service/mcp/McpToolDiscoverer` | 连远端 `listTools` 拿工具清单（注册 / 刷新 / 预览三处共用） |
| `service/mcp/McpToolCallback` | 实现 Spring AI `ToolCallback`；模型真调工具时才连远端 |
| `config/ToolSpecTypeHandler` | 取代 `OpenApiToolSpecTypeHandler`，按 `kind` 多态读写 |
| `config/McpProperties` | 超时外化（`hify.tool.mcp.*`） |

### 3.2 数据流 A —— 注册（admin，慢路径，允许连网）

```
admin 填 url + transport + 鉴权头(明文)
  → ToolAdminService.create（type=mcp 分支）
  → McpToolDiscoverer.discover(url, transport, 明文头)
        SsrfValidator.validate(host)          ← 内网直接拒（抛 10001，见 §7 错误码边界）
        建 client → initialize → listTools → close
  → tools[]（每个自带 name/description/inputSchema）
  → 鉴权头逐个加密 → 组装 McpToolSpec → 落 tool 表 1 行（source=mcp, owner=当前 admin）
```

### 3.3 数据流 B —— 执行（Agent，热路径，零额外网络开销）

```
ConversationService（零改动）
  → ToolFacade.getToolCallbacks(ids)
  → ToolRegistry.buildCallbacks() 第三分支 source=mcp
        解密鉴权头 → 按 spec.tools[] 快照建 N 个 McpToolCallback   ← 不连网
  → 模型决定调用某工具
  → McpToolCallback.call(argsJson)
        建 client → initialize → callTool → 文本化 → close
```

两个关键性质：
- **`McpToolCallback` 持有「连接配置 + 这一个工具的 name/schema」，不是活着的连接**——保持与
  `BuiltinToolCallback`/`OpenApiToolCallback` 一致的**无状态契约**，注册表可随时造/丢，无人需要负责关连接。
- **MCP 工具自带的 `inputSchema` 语义上就是标准 JSON Schema**，与 Spring AI `ToolDefinition` 所需的
  JSON Schema 字符串**语义对齐**，比 T3a 省事得多（那边要把 path/query/body 参数合并拼出一份 schema）。
  但**不是零转换**：SDK 的 `McpSchema.Tool.inputSchema()` 返回的是**类型化的 `JsonSchema` record**，
  须序列化成字符串（一行 `mapper.writeValueAsString(...)`），在**注册/刷新时**做掉、存进快照。
  - **序列化必须用带 `NON_NULL` 的私有 `ObjectMapper`，不要注入 Spring 的那个**：`JsonSchema` 有
    `defs`/`definitions`/`additionalProperties` 等可空字段，而 infra 全局 Jackson 按 api-standards §4
    配的是 `JsonInclude.ALWAYS`（那条是给**对外 JSON 响应**定的），用它会把
    `"defs":null,"definitions":null` 之类塞进**发给模型**的工具 schema 里，纯属噪声。
    既有 `OpenApiToolSpecTypeHandler` 用私有 `new ObjectMapper()` 已是同款先例。

工具命名沿用 T3a 已验证规则：`sanitize(注册名) + "__" + MCP工具名`；注册名唯一性由 `tool_name_uq` 保证，
故跨注册不撞名。

**两个 `description` 不要搞混**（与 T3a 的 openapi 行同款语义）：
- `tool` 行的 `description`（admin 填）= **这条注册**的说明，只给 admin 页/成员配置页的人看；
- `spec.tools[].description`（MCP 远端给）= **每个工具**的说明，进 `ToolDefinition`，**是给模型看的**，
  直接影响模型是否/如何调用。

⇒ `McpToolCallback` 的 `ToolDefinition` 一律取 `spec.tools[].description`，**绝不取 `tool` 行的 description**。

## 4. 数据模型（复用 tool 表，零加表）

### 4.1 `ToolSpec` 多态

```java
// tool/service/ToolSpec.java —— 放在 openapi/ 与 mcp/ 两个子包的父包
@JsonTypeInfo(use = NAME, include = PROPERTY, property = "kind",
              defaultImpl = OpenApiToolSpec.class)   // 老数据无 kind 时的兜底
@JsonSubTypes({ @Type(value = OpenApiToolSpec.class, name = "openapi"),
                @Type(value = McpToolSpec.class,     name = "mcp") })
public interface ToolSpec {}
```

- `OpenApiToolSpec` **原地不动**，只加 `implements ToolSpec`。
- `Tool.spec` 类型：`OpenApiToolSpec` → `ToolSpec`；`OpenApiToolSpecTypeHandler` → `ToolSpecTypeHandler`。
- T3a 既有代码适配：`ToolAdminService` / `ToolRegistry` 里用到 `spec.operations()`/`spec.baseUrl()` 处
  按 source 分支后 cast（或 `instanceof` 模式匹配）。

### 4.2 存量数据双保险

本地库已有 openapi 行，其 jsonb **没有 `kind`**：
1. `defaultImpl = OpenApiToolSpec.class` 让老数据仍能读出 ⇒ 保证**不炸**；
2. **新增 Flyway V25** 补齐标记，让数据自洽，不长期依赖隐性兜底：

```sql
-- V25：给存量 openapi 行的 spec 补 kind 标记（配合 ToolSpec 多态）
update tool set spec = spec || '{"kind":"openapi"}'::jsonb
 where source = 'openapi' and spec is not null and not jsonb_exists(spec, 'kind');
```

> 用 `jsonb_exists(spec,'kind')` 而非 `spec ? 'kind'`：`?` 在 JDBC 语境与占位符同形，绕开省得踩坑。
> 不修改任何已合并的旧迁移（CLAUDE.md 铁律）。

### 4.3 `McpToolSpec` 形状

```jsonc
{
  "kind": "mcp",
  "url": "https://mcp.example.com/mcp",
  "transport": "streamable_http",              // streamable_http | sse
  "authHeaders": [ { "name": "Authorization", "valueEnc": "<AES-GCM 密文>" } ],
  "tools": [
    { "toolName": "search_docs", "description": "给模型看的说明",
      "inputSchema": "{\"type\":\"object\",...}" }
  ],
  "discoveredAt": "2026-07-15T10:00:00+08:00"
}
```

- 凭据**只存密文**，任何响应 DTO 不回明文（对齐 provider 与 T3a）。
- `discoveredAt`：既然走快照，**滞后必须对用户可见**——admin 页显示「上次刷新于 X」，而不是让人猜清单多旧。

## 5. Admin 接口（复用 `/api/v1/admin/tool/tools`，不新开路由）

由 `SecurityConfig` 的 `hasRole("ADMIN")` 统一拦 `/api/v1/admin/**`。协议层无业务逻辑、无 `@Transactional`、
不注入 Mapper。一期不用 PATCH，动作走子资源 POST。

| 方法 | 路径 | 本轮变化 |
|---|---|---|
| POST | `/tools` | 按 `type` 分派；mcp → 发现工具 + 落库 |
| POST | `/tools/preview` | 按 `type` 分派；mcp → 测试连接 + 列出工具（不落库） |
| GET | `/tools` | 不变（mcp 行自然出现） |
| GET | `/tools/{id}` | 详情扩展：mcp 行回 `url/transport/tools/discoveredAt` |
| PUT | `/tools/{id}` | mcp 行全量更新（重新发现）；鉴权头留空=保留旧密文（沿用 T3b 拍板） |
| DELETE | `/tools/{id}` | **对 mcp 放开** |
| POST | `/tools/{id}/refresh` | **新增**：重新发现工具清单，保留鉴权头密文 |
| POST | `/tools/{id}/enable`·`/disable` | 不变 |

### 5.1 必须修改的既有代码（勿漏）

`ToolAdminService` 现有守卫 `assertOpenApi(row, "修改")` 会把 mcp 行一并拒掉，**改为
`assertNotBuiltin(row, ...)`** ——规则本意一直是「**内置**工具不可删改」，只是 T3a 时除 builtin 外只有
openapi，两种写法碰巧等价。

### 5.2 请求 DTO：并集字段 + 条件必填

**关键约束：不能弄坏 T3b 已上线的前端** ⇒ `type` 缺省即 `openapi`。

```java
public record CreateToolRequest(
        @NotBlank String name,
        @NotBlank String description,
        String type,           // null | "openapi" | "mcp"；null → openapi（兼容 T3b 老前端）
        String specText,       // openapi 必填
        String url,            // mcp 必填
        String transport,      // mcp：缺省 streamable_http
        List<AuthHeaderInput> authHeaders) {

    @AssertTrue(message = "OpenAPI 工具须提供 specText；MCP 工具须提供 url")
    public boolean isPayloadValid() { /* 按 type 分派 */ }
}
```

`UpdateToolRequest` / `PreviewToolRequest` 同样扩展（`specText` 的 `@NotBlank` 移除，改由 `@AssertTrue` 条件判定）。

**已知局限（接受）**：`@AssertTrue` 报错字段名为 `payloadValid`，前端无法在 `specText`/`url` 输入框精准标红。
仍选它是因为 api-standards §4 明确要求「校验注解只写在 DTO 上」；瑕疵只影响标红位置不影响文案，
T4b 可在前端按 `type` 先拦一道。

### 5.3 响应 DTO：只加字段不改老字段

（api-standards §1：新增字段不算破坏性变更；§4：集合永不为 null、Long 序列化为字符串）

- `ToolAdminDetailResponse` 加 `url` / `transport` / `tools: List<McpToolView>` / `discoveredAt`
  （openapi 行为 null / `[]`）。
- `ToolPreviewResponse` 加 `tools: List<McpToolView>`（openapi 时 `[]`）。
- 新增 `McpToolView { toolName, description }`（不含 inputSchema，UI 够用即可，与 `OperationView` 同款克制）。
- `ToolAdminResponse.operationCount` **保留原名**，mcp 行填工具数。改名会弄坏 T3b 前端，不值得；
  名字略带 openapi 味，在字段注释里写清含义。

## 6. 执行契约与安全

### 6.1 `McpClientFactory`：安全闸门收口一处

```java
McpSyncClient create(String url, String transport, Map<String,String> plainHeaders) {
    // 1) 协议白名单：仅 http/https（否则 10001）
    // 2) ssrfValidator.validate(host)   ← DNS 解析后逐 IP 校验，内网/回环/元数据一律拒
    // 3) 选传输：默认 streamable_http（HttpClientStreamableHttpTransport），可选 sse
    //    customizeClient : followRedirects(NEVER) + connectTimeout
    //    customizeRequest: 逐个注入解密后的鉴权头
    // 4) McpClient.sync(t).requestTimeout(..).initializationTimeout(..).clientInfo(hify)
}
```

**`followRedirects(NEVER)` 不是可选项，是必须主动设的闸门**：不设的话，远端回一个 302 指向
`http://169.254.169.254/`（云元数据）即可整个绕过建连前的 SSRF 校验。

**已知局限（诚实记录，非本轮新增）**：只能在建连前校验 DNS 解析结果，SDK 的 HttpClient 真连时会再解析一次
DNS ⇒ 理论上存在 DNS rebinding 窗口。此局限**与现有 `OutboundHttpClient` 完全一致**
（`SsrfValidator` 类注释已写明「不做 DNS pinning，一期威胁模型可接受」），是既有姿态的延续，
本轮不提高也不降低标准。

超时**全部外化**（CLAUDE.md：所有外部调用必须有超时且超时值外化）：

```yaml
hify.tool.mcp:
  connect-timeout-ms: 5000
  request-timeout-ms: 30000
  initialization-timeout-ms: 10000
```

### 6.2 `McpToolCallback.call()`

```java
try (McpSyncClient client = factory.create(...)) {   // AutoCloseable，用完必关
    client.initialize();
    CallToolResult r = client.callTool(new CallToolRequest(toolName, args));
    return textOf(r);
} catch (Exception e) {
    return "MCP 工具调用失败：" + e.getMessage();      // 绝不抛，不中断 Agent 循环
}
```

结果文本化规则：
- 取文本类型内容拼接；
- 非文本类型（图片/嵌入资源）给**占位说明**（一期不做多模态）；
- 远端 `isError=true` 时在文本前缀点明，让模型知道调用失败可换思路，而非把错误当正常结果。

### 6.3 `ToolRegistry` 第三分支

`buildCallbacks()` 加 `source=mcp` 分支，与 openapi 分支同构：解密 `authHeaders` → 按 `spec.tools[]` 快照
展开成 N 个 `McpToolCallback`。`getBuiltinToolCallbacks()` 与 `filterEnabledIds()` 无需改动。
`ToolRegistry` 需注入 `McpClientFactory`（构造 `McpToolCallback` 用）。

现成 `ToolService.listEnabled()`（成员族 `GET /api/v1/tool/tools`）**不改**：mcp 注册作为一个 enabled 行
自然出现在成员配置页可选项里，`ToolView` 已带 `source`。

## 7. 错误码

**只新增一个 tool 段码**（`tool/constant/ToolError`）：

- **`13002 / 400` —— MCP 服务器连接或工具发现失败**

为什么 400 而非 503：该错**只发生在 admin 注册/刷新/预览时**，本质是「admin 填错地址或凭据」= 用户输入问题，
而非「我们依赖的服务挂了」；与 `13001`（OpenAPI 解析失败 / 400）同构。
**工具执行期**连不上远端按 `ToolCallback` 契约返回错误文本而不抛 ⇒ 「执行期 503」场景根本不存在，无需发码。

其余复用通用段：`10001` 参数校验失败、`10005` 不存在、`10006` 重名。

### 7.1 `10001` 与 `13002` 的边界（避免实现时二选一犹豫）

| 场景 | 错误码 | 谁抛 |
|---|---|---|
| url 非 http/https、缺主机名 | `10001` | `McpClientFactory` 自己校验 |
| url 指向内网/回环/元数据地址、域名无法解析 | `10001` | **`SsrfValidator` 原样抛出**，不要包装成 13002 |
| 地址可达性/协议层失败：连不上、握手超时、鉴权被拒（401/403）、返回非法 MCP 响应、`listTools` 失败 | `13002` | `McpToolDiscoverer` 捕获 SDK 异常后抛 |

即：**「请求本身就不合法」→ 10001；「请求合法但那头没给我们想要的东西」→ 13002。**
`SsrfValidator` 抛的 `BizException(10001)` 属于前者，`McpToolDiscoverer` 的 catch **不得**把它吞掉重包
（否则内网拒绝会被误报成「连接失败」，admin 看不出真实原因）。

## 8. 测试

后端 JUnit（vitest 是 T4b 的事）。最值钱的三类：

1. **存量兼容回归（本轮最重要）**：一段**没有 `kind` 字段**的老 openapi JSON 必须仍能反序列化成
   `OpenApiToolSpec` —— 直接守着「上线不炸本地已有数据」。
2. **本地最小 MCP 桩做端到端**：用 JDK 自带 `com.sun.net.httpserver.HttpServer` 手写最小 MCP 服务器桩，
   响应 `initialize` / `tools/list` / `tools/call` 三个 JSON-RPC 方法。Streamable HTTP 的最小实现即
   「POST 进来、JSON 回去」，无需 SSE，工作量可控。据此让 `McpToolDiscoverer` 与 `McpToolCallback`
   **真连一次、真调一次**，而非对着 mock 自说自话（testing-standards：反假测试）。
   - 桩在 localhost = 内网会被 SSRF 拒 ⇒ 测试注入放行的 `SsrfValidator`
     （其包私有构造器本就接受自定义解析函数，是设计好的测试缝）。
3. **失败路径**：桩返回 500 / 超时 / 非法 JSON → `McpToolCallback` 必须**返回错误文本而非抛异常**。

其余：
- `McpClientFactory`：内网 url 被拒；transport 选择正确；鉴权头注入。
- `ToolRegistry`：builtin + openapi + mcp 三种混合展开、命名前缀防撞。
- `ToolSpecTypeHandler`：mcp JSON ↔ `McpToolSpec` 往返带 `kind`。
- `ToolAdminService`：mcp create（发现+加密+落库）、refresh（保留密文）、update 留空保留密文、
  delete 对 mcp 放行、builtin 仍拒删改、13002。
- `ModularityTests` / ArchUnit（`LayerRules`）仍绿——核对 tool 依赖白名单未变、DTO 不 import entity。

## 9. 验收路径（本轮最大未验证风险）

决策 3（禁内网）意味着验收**必须**指向公网可达的 MCP 服务器。候选为 DeepWiki / Context7 一类公开托管服务，
**但其此刻可用性与所用传输无法凭记忆保证**。因此 plan 里安排**独立前置 Task 实测**：后端一跑通就先拿真实
服务器连一次。

**若实测一个都连不上** ⇒ 明确信号：**回头重议决策 3（内网放行）**。拆 T4a/T4b 的价值正在于此——
那时前端一行未写，损失最小。

## 10. 落地顺序（详见 plan）

TDD，Codex 外部执行 + 终审：
1. `ToolSpec` 多态接口 + `ToolSpecTypeHandler` + Flyway V25 + `Tool` 实体改类型 + T3a 代码适配
   （**先做，隔离存量数据风险**；跑通 T3a 现有全部测试）。
2. SDK 0.12.1 依赖引入 + `McpProperties` + `McpClientFactory` + 测试（含 SSRF 拒内网、禁重定向）。
3. 最小 MCP 桩（测试基建）+ `McpToolSpec` + `McpToolDiscoverer` + 测试。
4. `McpToolCallback` + 测试（含失败路径返文本不抛）。
5. `ToolRegistry` mcp 分支 + 测试（三种 source 混合）。
6. `ToolError` 13002 + `ToolAdminService` mcp 分支（create/update/refresh/delete 放开）+ 测试。
7. `AdminToolController` refresh 端点 + DTO 扩展 + 集成校验。
8. **实测真实公网 MCP 服务器**（§9 风险验证）。
9. 回归（全量 `mvn clean test` 含 Modularity/ArchUnit）+ 文档更新 + self-check 入档。

## 11. 需同步更新的架构文档（CLAUDE.md：拍板结论要补进文档）

- `data-model.md`：**删除 `mcp_server` 表规划**与 ER 中 `mcp_server 1──N tool` 关系，改为说明
  MCP 走 Model D（`tool` 1 行 + `spec jsonb` 快照）；`tool` 行的 spec 说明补 mcp 形状。
- `api-standards.md` §5：tool 段补 `13002`（若该文档维护模块段示例清单）。
- `code-organization.md`：tool 模块职责一栏已含「MCP 接入」，无需改。
- `deployment.md` §5：MCP 出站过 SSRF 的表述与本轮实现一致，无需改；如需可补「MCP 仅支持远程 HTTP、
  不支持 stdio」一句。

## 12. 风险与留账

| 项 | 说明 |
|---|---|
| 公网 MCP 服务器可达性 | 本轮最大风险，§9 有专门验证 Task；连不上则回头重议决策 3 |
| DNS rebinding | 建连前校验的固有局限，与 `OutboundHttpClient` 现状一致，**非新增风险** |
| 快照滞后 | 远端新增工具需手动刷新；`discoveredAt` 让滞后可见 |
| per-app 粒度 | 只能勾整个 MCP 服务器，不能勾单个工具（与 T3a 一致） |
| 只接 tools | resources / prompts 明确不接 |
| 只做静态鉴权头 | MCP 的 OAuth 授权流不做（与 T3a 一致） |
| 依赖共存 | 不引 spring-ai-mcp ⇒ 无二进制冲突；plan 需核对 `dependency:tree` 中 reactor/jackson 版本由 Spring Boot BOM 统一收口 |
| SSE 传输保留 | 老协议，保留为兼容选项；若实践中无人用，未来可删 |
