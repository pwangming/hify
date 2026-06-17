# Hify 接口规范（RESTful / 统一响应 / 空值 / 错误码）

> 写任何 Controller、定义任何 Request/Response 之前必读。与本文冲突的接口视为错误。
> 配套：分层与路由归属见 `code-organization.md` 第 2 节；分页 SQL 见 `database-standards.md` 第 4 节；
> 对外标识不用自增主键见 `database-standards.md` 第 1.3 节。

## 1. 路由三族

全部 HTTP 接口只允许落在以下三个前缀下（nginx 反代规则与此一致，见 deployment.md）：

| 前缀 | 调用方 | 认证 | 示例 |
|---|---|---|---|
| `/api/v1/<module>/**` | 前端（Member + Admin） | JWT：`Authorization: Bearer <jwt>` | `/api/v1/knowledge/datasets` |
| `/api/v1/admin/<module>/**` | 前端（仅 Admin） | JWT + 角色校验 | `/api/v1/admin/provider/providers` |
| `/v1/apps/{appKey}/**` | 第三方系统（应用对外 API） | App API Key：`Authorization: Bearer <token>` | `/v1/apps/{appKey}/workflows/run` |

- `<module>` 即 code-organization.md 第 1 节的模块名；admin 接口写在各模块 `controller/` 下，没有独立 admin 模块。
- 对外 API 仅 conversation、workflow 两个模块有。`{appKey}` 是应用的公开随机标识（非自增 id），
  仅定位应用；身份由 Header 中的 API Key token 证明，token 必须属于该 app，否则 401。
- 版本策略：版本号只在路径（`v1`）。**新增字段不算破坏性变更**（客户端必须容忍未知字段）；
  改字段语义、删字段、改错误码含义才升 v2。一期不会有 v2。
- **唯一的模块无关系统端点**：`GET /api/v1/health`（不带 `<module>` 段），供前端与负载均衡探活，
  无需认证。它仍返回统一 `Result`（成功码 200、`data` 为纯文本提示如 `Hify is running`，见第 3 节）；
  路由是三族规则的例外，实现挂在某个模块的 controller 下（当前在 app 模块）。除此之外不再开第二个模块无关端点；容器/运维探活另有
  Spring Boot Actuator 的 `/actuator/health`（不对外、不套 Result，见 deployment.md）。

## 2. RESTful 资源设计

### 2.1 URL 规则

- 资源名用**复数名词、kebab-case**：`/datasets`、`/api-keys`、`/mcp-servers`。禁止动词式 URL（`/getDataset`）。
- 层级表达从属，**嵌套不超过两级**：`/datasets/{id}/documents` 可以；
  `/datasets/{id}/documents/{docId}/chunks/{chunkId}` 不行——子资源有了自己的 id 就升为顶级：`/documents/{docId}/chunks`。
- 无法映射为 CRUD 的动作，用**子资源 POST**：`POST /apps/{id}/publish`、`POST /workflow/runs/{id}/stop`、
  `POST /datasets/{id}/retrieve`（检索测试）。动作名用动词原形，一个接口一个动作。
- 查询/过滤/分页一律 query 参数：`GET /api/v1/app/apps?type=chat&keyword=客服&page=1&size=20`。

### 2.2 方法语义

| 方法 | 语义 | 约定 |
|---|---|---|
| GET | 读取，无副作用 | 禁止 GET 改状态 |
| POST | 创建资源 / 执行动作 | 创建成功返回完整资源（含 id），不用 201+Location 那套（统一 200 信封） |
| PUT | **全量**更新 | 客户端必须传完整对象，未传字段视为置空 |
| DELETE | 删除（软删，`deleted=true`） | 幂等：删除不存在的资源也返回成功 |

**不使用 PATCH。** 部分更新存在"null=置空 还是 null=不改"的歧义，一期从简：更新一律 PUT 全量。
个别确需单字段开关的场景（如启停应用），用动作子资源（`POST /apps/{id}/enable`）。

### 2.3 HTTP 状态码

状态码表达**错误类别**，body 里的业务错误码表达**具体原因**（见第 5 节），两者必须一致：

| 状态码 | 场景 |
|---|---|
| 200 | 成功（含创建、删除；不区分 201/204，简化前端处理） |
| 400 | 参数校验失败、请求体格式错误 |
| 401 | 未登录、JWT/API Key 无效或过期 |
| 403 | 已登录但权限不足（Member 调 admin 接口、访问他人私有资源） |
| 404 | 资源不存在（含已软删）；**禁止用 404 掩盖 403 之外，也禁止用 200+错误码表示不存在** |
| 409 | 状态冲突（重名、重复发布、并发修改） |
| 429 | 触发限流或 Token 配额耗尽 |
| 500 | 未预期异常（兜底） |
| 503 | 依赖不可用（LLM 供应商熔断中、sandbox 不可达） |

## 3. 统一响应 `Result<T>`

所有 JSON 接口（三族路由全部，含对外 API）返回 `com.hify.common.Result<T>` 信封；**唯一例外是 SSE**（见 3.3）。

```java
// common 包，record 不可变
public record Result<T>(int code, String message, T data, String traceId) {
    public static <T> Result<T> ok(T data) { return new Result<>(200, "success", data, MDC.get("traceId")); }
    public static <T> Result<T> fail(ErrorCode ec, String message) { ... }
}
```

```json
// 成功
{ "code": 200, "message": "success", "data": { "id": "42", "name": "客服知识库" }, "traceId": "a1b2c3" }

// 成功但无数据（删除、动作类）
{ "code": 200, "message": "success", "data": null, "traceId": "a1b2c3" }

// 失败（HTTP 429）
{ "code": 14001, "message": "今日 Token 配额已用尽", "data": null, "traceId": "a1b2c3" }
```

- Controller 只组装成功响应；失败一律抛 `BizException`，由 `infra` 全局异常处理器统一转信封，
  **Controller 禁止 try-catch、禁止手写失败 Result**（code-organization.md 已有此规则，此处重申）。
- `traceId` 始终返回（取自 MDC，同时写响应头 `X-Trace-Id`），用户报障时凭它 grep 日志。
- 参数校验失败（400/10001）时，`data` 携带字段错误数组，供表单逐项标红：
  ```json
  { "code": 10001, "message": "参数校验失败", "data": [ { "field": "name", "message": "不能为空" } ], "traceId": "..." }
  ```

### 3.1 列表响应（两种分页，对应 database-standards.md 第 4 节）

```json
// 页码分页（管理后台列表）—— data 固定为 PageResult 结构
{ "code": 200, "message": "success", "data": {
    "list": [ ... ], "total": 134, "page": 1, "size": 20 }, "traceId": "..." }

// 游标分页（消息流、运行日志、对外 API）—— 不返回 total
{ "code": 200, "message": "success", "data": {
    "list": [ ... ], "nextCursor": "MTcxOC4uLg", "hasMore": true }, "traceId": "..." }
```

- 页码分页入参：`page`（从 1 起，默认 1）、`size`（默认 20，最大 100）；`page × size > 10000` 直接报 10001。
  高水位表关 count 时 `total` 返回 `-1`，前端不显示总页数。
- 游标分页入参：`cursor`（首页不传）、`limit`(默认 20，最大 100)。`nextCursor` 是排序键
  （create_time + id）的 Base64 编码，**对客户端不透明**，客户端只能原样回传，禁止自行构造。
  `hasMore=false` 时 `nextCursor` 为 null。database-standards.md 中的 `next_cursor` 即此字段（JSON 用 camelCase）。

### 3.2 列表为空

空列表是成功：`code=200`，`list: []`。禁止用 404 或错误码表示"查到 0 条"。

### 3.3 SSE 流式响应（对话、工作流运行进度）

`Content-Type: text/event-stream`，不套 Result 信封。事件类型固定四种：

```
event: message        // 增量内容
data: {"delta": "你好，"}

event: tool_call      // Agent 工具调用轨迹（可选）
data: {"toolName": "http_request", "status": "running"}

event: error          // 流中途出错：data 即 Result 失败结构（同样的 code/message/traceId）
data: {"code": 12002, "message": "模型供应商调用超时", "traceId": "..."}

event: done           // 正常结束，携带本次统计与落库 id
data: {"messageId": "98", "usage": {"promptTokens": 320, "completionTokens": 180}}
```

- 连接建立前的错误（401、配额 429）走普通 JSON 信封；连接建立后的错误走 `error` 事件，之后关流。
- nginx 侧 `proxy_buffering off` 已在 deployment.md 约定；server 每 15s 发 `: ping` 注释行保活。

## 4. 序列化与空值规范

由 `infra` 的 Jackson 全局配置统一实现，业务代码不做局部覆盖（禁止在 DTO 上散落 `@JsonInclude`/`@JsonFormat`）。

| 规则 | 约定 | 原因 |
|---|---|---|
| 字段命名 | JSON 一律 camelCase（Java 默认，零配置） | DB snake_case 与 JSON camelCase 的映射在 Entity↔DTO 转换层完成 |
| null 字段 | **照常输出，不省略**（`JsonInclude.ALWAYS`） | 前端 TS 类型稳定，字段不会"时有时无" |
| 集合/数组 | **永不为 null**，空就是 `[]`（DTO 字段初始化为空集合） | 前端免去 `?.map` 防御 |
| 对象字段 | 可为 null，表示"无"（如 `publishedDef: null` = 未发布） | — |
| 字符串 | "无值"用 null，**禁止用 `""` 表示无**；入参全局 trim，trim 后空串按 null 处理 | 消灭 null/空串双轨 |
| Long/long | **全局序列化为 JSON string**（`"id": "42"`，token 数同样） | JS Number 2^53 精度；一刀切无例外，前端需要算术时自行 Number() |
| 时间 | ISO-8601 含时区偏移：`"2026-06-12T10:30:00+08:00"`；入参同格式 | 与 `timestamptz` 对齐；禁用时间戳数字 |
| 枚举 | 小写字符串，与 DB check 约束值完全一致（`"pending"`、`"chat"`） | 一处定义（entity 枚举），DB/JSON 不做两套词表 |
| 未知字段 | 反序列化遇到未知字段**忽略不报错** | 客户端可比服务端新 |
| id 暴露 | 站内接口（`/api/v1/**`）可返回自增 id（string 形态）；**对外 API（`/v1/apps/**`）只出现随机标识**（appKey、对外会话 id 等） | database-standards.md 第 1.3 节，防枚举遍历 |

入参校验：必填字符串用 `@NotBlank`（空串/纯空白不合法），必填对象/数字用 `@NotNull`，
集合入参缺失与 `[]` 等价。校验注解只写在 `dto/XxxRequest` 上，service 不重复校验 Web 层语义。

## 5. 错误码体系

### 5.1 结构

5 位整数 `MMXXX`：前 2 位是模块段，后 3 位模块内自增。**成功不属于 MMXXX，单独用 `200` 表示**
（与 HTTP 成功状态同源；失败时 body 业务码仍是 5 位 MMXXX，与 HTTP 状态分属两套）。
错误码与 HTTP 状态码**在枚举定义处一次绑定**，全局异常处理器据此设置响应状态，业务代码不碰 HTTP 状态。

```java
// common 包：通用段。各模块的码定义在各自 constant/ 下的枚举，实现同一接口
public interface ErrorCode { int code(); HttpStatus status(); String defaultMessage(); }
```

### 5.2 模块段分配（新增模块时在此登记）

| 段 | 模块 | 段 | 模块 |
|---|---|---|---|
| 10xxx | 通用（common/infra） | 15xxx | knowledge |
| 11xxx | identity | 16xxx | app |
| 12xxx | provider | 17xxx | conversation |
| 13xxx | tool | 18xxx | workflow |
| 14xxx | usage | | |

### 5.3 通用段预定义（10xxx，全模块复用，禁止各模块重复发明）

| code | HTTP | 含义 |
|---|---|---|
| 10000 | 500 | 系统内部错误（兜底，message 固定"系统繁忙"，细节只进日志不出响应） |
| 10001 | 400 | 参数校验失败 |
| 10002 | 401 | 未认证 / 凭证无效 |
| 10003 | 401 | 凭证已过期（前端据此触发重新登录，区别于 10002） |
| 10004 | 403 | 权限不足 |
| 10005 | 404 | 资源不存在 |
| 10006 | 409 | 资源冲突（重名等） |
| 10007 | 429 | 请求过于频繁（限流） |
| 10008 | 503 | 依赖服务不可用 |

模块段示例（完整清单在各模块枚举里维护，不在本文重复）：
`14001/429` 今日 Token 配额耗尽；`12002/503` 供应商调用超时或熔断中；
`18003/409` 工作流定义已发布不可修改；`15004/400` 文档格式不支持。

### 5.4 使用规则

1. **优先复用通用段**：资源不存在抛 `BizException(CommonError.NOT_FOUND, "知识库不存在")`，
   不要为每个模块各发明一个"xx 不存在"。模块段只放**该模块特有**的业务语义（配额、熔断、状态机冲突）。
2. message 面向**最终用户**可读，不含堆栈、SQL、内部类名；排障细节带 traceId 写日志。
3. 错误码一旦对外发布**只增不改不删**——前端与第三方会硬编码判断（如 10003 触发刷新登录、14001 提示配额）。
4. 全局异常处理器（`infra`）的兜底链：`BizException` → 按枚举转信封；
   `MethodArgumentNotValidException` → 10001 + 字段数组；其余 `Exception` → 10000，**原始异常只打日志**。

## 6. 安全与杂项

- CORS：同源部署（nginx 统一入口），不开放跨域；本地开发用 Vite proxy，不在 server 配 CORS。
- 资源可见性（团队共享制，data-model.md 第 3 节）：GET/使用类接口全员放行；
  PUT/DELETE/动作类接口校验"owner 或 Admin"，不满足返回 10004/403；
  conversation 族接口固定按当前用户过滤（`where user_id = 当前用户`），
  跨用户视图只存在于 `/api/v1/admin/**`。
- 上传：multipart，单文件 ≤ 50MB（与 nginx `client_max_body_size` 对齐），超限返回 10001。
- 对外 API 的限流与配额：进入 `/v1/apps/**` 先校验 API Key（401）→ 配额检查（14001/429）→ 业务，
  顺序固定（code-organization.md 第 4.4 条：配额只在 conversation/workflow 入口查）。
- 幂等：一期不提供 `Idempotency-Key`；对外工作流触发由调用方自行去重，列入二期观察项。
