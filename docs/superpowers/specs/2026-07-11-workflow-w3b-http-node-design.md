# Workflow W3b：HTTP 请求节点（设计）

> 2026-07-11 brainstorm 定稿。W3 的后半（前半 W3a 条件分支已合并）。两块交付：
> infra 出站 HTTP 地基（deployment.md §5 硬性要求，未来 OpenAPI 工具/MCP 共用）+
> workflow HTTP 节点。基于 W1 引擎与 W3a 条件分支（status 判断靠 condition 节点衔接）。

## 1. 范围与目标

**做**：
- `infra/outbound` 新子包：`SsrfValidator`（DNS 解析后 IP 校验）+ `OutboundHttpClient`
  （JDK HttpClient 封装，`Redirect.NEVER`，双超时）——出站请求统一收口，
  tool/workflow 模块禁止自建客户端（deployment.md §5）
- `NodeType` 新增 `HTTP("http")` + `HttpNodeExecutor`（纯加 executor，引擎零改动，同 W2 模式）
- `GraphValidator` 新增 http 节点字段校验；配置外化 `hify.outbound.http.*`
- 测试三层 + Postman 验收集合；deployment.md §5 补两条拍板结论

**不做**（YAGNI，均有预留）：
- 跟随重定向（用户拍板：3xx 原样输出 status + Location，彻底封死重定向绕过；
  真有需求时用户把 URL 换成终点即可）
- 内网白名单（用户拍板：一期只调公网；机制已预留——system_setting 表 + 校验器查表，
  真有内网需求时再加，约 1-2 任务量）
- DNS pinning（见 §3 已知边界）
- 认证管理/变量加密存储（headers 里写 token 属一期形态；密钥托管是后续话题）

**四个拍板决策**：非 2xx 不算节点失败（输出 status 交给 condition 节点判断，Dify 同款语义）；
不跟随重定向；白名单推迟；客户端用 JDK HttpClient（零新依赖、Redirect.NEVER 原生、虚拟线程友好）。

## 2. 节点定义

```json
{"id": "http_1", "type": "http", "data": {
  "method": "POST",
  "url": "https://api.example.com/tickets?uid={{start.uid}}",
  "headers": {"Authorization": "Bearer {{start.token}}"},
  "body": "{\"title\": \"{{llm_1.text}}\"}"
}}
```

| 字段 | 约束 | 校验时机 |
|---|---|---|
| `method` | 必填，白名单 `GET`/`POST`/`PUT`/`DELETE` | 保存时（GraphValidator） |
| `url` | 必填非空字符串，支持 `{{nodeId.field}}` 模板 | 必填保存时查；scheme（仅 http/https）运行时渲染后查 |
| `headers` | 可选，`Map<String,String>`，值支持模板 | 保存时查类型（若存在须为 map） |
| `body` | 可选字符串模板；GET/DELETE 忽略不发 | — |

**输出**：`{status: 200, body: "...", headers: {...}}`
- `status`：整数；非 2xx、3xx 均原样输出（节点成功）
- `body`：响应体字符串；超过 `max-response-bytes` 截断（防大响应撑爆 node_run jsonb 与内存）
- `headers`：响应头 map（值为逗号连接的字符串）；模板引用整个 map 是 toString 形态，
  主要供 node_run 排障查看（如 3xx 的 Location）

**inputs 快照与脱敏**：`{method, url(渲染后), headers(渲染后+脱敏), body(渲染后)}`。
敏感请求头落库前掩码为 `***`：`Authorization`、`Cookie`、`Proxy-Authorization`、
`X-Api-Key`（大小写不敏感匹配）。排障能看到发了哪些头，token 不进数据库。

## 3. infra 出站地基与执行流程

**SsrfValidator**（`infra/outbound`）：入参 host，`InetAddress.getAllByName` 解析全部 IP，
任一命中即抛 BizException（错误码按 api-standards 优先复用通用段——写实现计划时须现场重读
api-standards.md 错误码段核对后固定，不得凭记忆新开段）：
- 回环：`isLoopbackAddress`（127.x、::1）
- 私网：`isSiteLocalAddress`（10.0.0.0/8、172.16.0.0/12、192.168.0.0/16）
- link-local：`isLinkLocalAddress`（169.254.0.0/16 云元数据、fe80::/10）
- any/组播：`isAnyLocalAddress`、`isMulticastAddress`
- 容器服务名（postgres/sandbox）解析结果是容器网段私网 IP，被私网规则自动覆盖，无需名单

**OutboundHttpClient**（`infra/outbound`）：JDK HttpClient 单例（`Redirect.NEVER`、
connect-timeout），每请求 read-timeout；先过 SsrfValidator 再发请求；响应体按
max-response-bytes 截断。配置 `hify.outbound.http.*`：
`connect-timeout-ms`（默认 5000）、`read-timeout-ms`（默认 15000）、
`max-response-bytes`（默认 65536）——全部可被环境变量覆盖（CLAUDE.md：外部调用必须有超时且外化）。

**HttpNodeExecutor 执行流程**：
1. 渲染 url/headers/body（引用被跳过节点 → 空串，W3a 语义自动生效）
2. 解析 URL：scheme 非 http/https → 节点失败
3. `OutboundHttpClient` 发请求（内部先 SSRF 校验）
4. 输出 `{status, body, headers}`

**节点失败仅限**：SSRF 拦截、URL 非法、DNS 解析失败、连接/读取超时、网络 IO 错误——
均经 `NodeExecutionException(脱敏后 inputs, cause)` 落 node_run（W1/W2 同模式）。
拿到任何 HTTP 响应都算节点成功。节点重试 0（与全部节点一致）。

**已知边界（留账，不在一期修）**：不做 DNS pinning——校验与连接之间理论上存在
DNS rebinding 窗口（攻击者控制的域名两次解析返回不同 IP）。JDK HttpClient 不支持自定义
resolver，严格修复需绕到 socket 层。一期威胁模型为内网部署 + 20-50 受信团队成员，
工作流定义者即团队成员，风险可接受；二期若对外开放或接入不受信用户再收紧。

## 4. 测试与 DoD

**单测**：
- `SsrfValidator`：回环/私网三段/link-local/组播 逐一拦截；公网 IP 放行；
  域名解析出私网 IP 的场景（解析逻辑可注入或 mock）
- `HttpNodeExecutor`（mock OutboundHttpClient）：200 与 404 都成功输出 status/body、
  超时异常转 NodeExecutionException、敏感头脱敏断言、GET 忽略 body
- `GraphValidator`：method 白名单/缺 url/headers 非 map 反例 + 合法正例

**集成测试**：
- `OutboundHttpClient` 连测试内起的本地 HTTP 桩（JDK `com.sun.net.httpserver.HttpServer`，
  注入放行版 validator 绕过 127.0.0.1 拦截）：真实网络栈验证超时、响应截断、3xx 不跟随
- 工作流全链路（扩展 WorkflowRunFlowTest）：「start → http(本地桩) → condition(status==200)
  → llm(桩) → end」两方向

**DoD 手动验收**（Postman `workflow-w3b.postman_collection.json`，说明第一条：验收前重启服务）：
1. 调真实公网接口（如 https://httpbin.org/json）→ succeeded，body 进下游 LLM 提示词
2. 404 场景：status 落输出，condition 分流到兜底路
3. SSRF 拦截：url 填 `http://127.0.0.1:8080/actuator/health` → HTTP 200 但 run failed，
   node_run 可见拦截原因
4. 脱敏：headers 带 Authorization 触发一次，node_run.inputs 里该头为 `***`

**既有防线**：ModularityTests / ArchUnit（workflow→infra 全模块合法）/ 全量绿。

## 5. 交付物与后续

- infra：`outbound` 子包（SsrfValidator/OutboundHttpClient/配置类）+ 单测/集成测试
- workflow：NodeType.HTTP + HttpNodeExecutor + validator 规则 + 集成测试
- docs：Postman 集合、self-check 入档、deployment.md §5 补拍板（不跟随重定向；白名单推迟）
- **工作量预估**：6-7 任务、约 700-900 行

**后续轮次**：Vue Flow 画布（position/sourceHandle 已就绪，工作流六类节点已有五类，
可视化价值最大化）→ 发布机制 + 对外 API → 代码执行节点（依赖 sandbox 容器）→
Agent 节点（依赖 tool 模块）；另有留账：检索阈值调优（W3a 发现）、
内网白名单（本轮推迟项）。
