# Hify 部署架构（一期：50 人 / 单机 Docker Compose）

> 决策依据：50 人并发、一人维护。瓶颈不在 QPS 而在云端 LLM 的速率限制，单机即可支撑。
> 存储一库通吃：PostgreSQL 16 + pgvector 同时承担业务数据与向量（消灭双存储一致性问题，
> 一套备份）；缓存用进程内 Caffeine。Redis 与 K8s 列入二期触发条件（见第 6 节），当前不引入。

## 1. 组件总览

```
                        团队用户 / 第三方系统
                              │ HTTPS :443
                       ┌──────▼──────┐
                       │    nginx    │ ① TLS、静态资源(Vue产物)、反代
                       └──────┬──────┘
            /api/v1/**（前端） │ /v1/apps/**（应用对外API）
                       ┌──────▼──────┐    HTTPS 出网    ┌─────────────────┐
              ┌────────│ hify-server │────────────────▶│ 云端 LLM 供应商   │
              │  HTTP   └──────┬──────┘                 │ (OpenAI兼容/Claude)│
              │ (仅内网)        │                        └─────────────────┘
       ┌──────▼─────┐  ┌──────▼──────────┐
       │  sandbox   │  │ PostgreSQL 16   │
       │  代码执行    │  │ + pgvector      │
       └────────────┘  │ 业务数据 + 向量   │
                       └─────────────────┘
```

4 个容器，跑在一台 4C/16G 的虚拟机上，由 `docker-compose.yml` 编排。
只有 nginx 对外发布端口；其余组件在内部 network，靠服务名互相寻址。

| 容器 | 镜像 | 职责 | 资源限制 |
|---|---|---|---|
| nginx | nginx:stable | TLS 终止；托管 Vue 构建产物；`/api`、`/v1` 反代到 server；SSE 透传；上传体积限制 | 0.5C / 256M |
| hify-server | 自构建（JRE 21） | 全部业务逻辑（十个模块）；Flyway 启动迁移；Caffeine 进程内缓存；Actuator 健康检查 | 2C / 6G（堆 4G） |
| sandbox | 自建（python slim） | 工作流代码节点 / Agent 代码工具的 Python 执行；**唯一不可信代码场所** | 1C / 1G，强制 |
| postgres | pgvector/pgvector:pg16 | 业务数据（应用/会话/用量/用户）+ 知识库向量（chunk 元数据与 embedding 同表，HNSW 索引） | 1.5C / 6G |

## 2. 每个组件的职责边界

**nginx —— 唯一入口，零业务。**
- TLS 终止（证书挂载，或换 Caddy 自动签）；HTTP→HTTPS 跳转。
- `/` 托管 Vue 构建产物（web 容器化进 nginx 镜像，见 repo 布局）。
- `/api/v1/**`（前端调用）与 `/v1/apps/**`（应用对外 API）反代到 hify-server。
- SSE 必配：`proxy_buffering off`、`proxy_read_timeout 600s`（对齐流式总时长上限）。
- `client_max_body_size 50m`（知识库文档上传）。

**hify-server —— 无状态业务单体。**
- 全部持久状态在 PostgreSQL，自身可随时重启、未来可平行复制多份
  （Caffeine 是进程内缓存，只存可丢弃的热数据，多副本时各自独立或换 Redis）。
- JWT 鉴权（不依赖会话粘滞）；Flyway 在启动时跑 schema 迁移。
- 出网方向唯一：调云端 LLM API（provider 模块，带韧性层，见 llm-resilience.md）。
- 优雅停机：`server.shutdown=graceful` + compose `stop_grace_period: 60s`，
  等待进行中的 workflow 任务收尾。
- 启动自愈：启动时把停留在非终态的"进行中"记录（`kb_document` 解析中、`workflow_run`
  运行中等）统一置为 failed（用户可重试），杜绝崩溃（OOM / kill -9 / 断电）后永久卡死的
  僵尸状态。一期单副本直接全量重置；多副本后改由任务表租约判定（scaling-path 阶段 2）。
  所有状态机必须保证终态可达，禁止依赖人工 SQL 修数。

**sandbox —— 安全边界，独立容器是底线。**
- 不可信代码绝不在 server 进程/容器里执行。server 通过内网 HTTP 提交代码，拿回 stdout/结果。
- 加固：`read_only: true`、`cap_drop: ALL`、`security_opt: no-new-privileges`、
  独立 network **不连 postgres**、默认禁出网（业务确需 HTTP 由 server 的 HTTP 节点代劳）。
- 容器级 CPU/内存硬限制 + server 侧执行超时（双保险）；并发数由 server 信号量控制。

**PostgreSQL —— 唯一可信数据源，业务与向量一库通吃。**
- 所有业务表 + 知识库向量 + 文档原始文件（`kb_document` 的 bytea 列）：chunk 的元数据与 embedding 在同一张表
  （`kb_chunk(id, dataset_id, document_id, content, embedding vector(n), ...)`），
  删除文档/知识库是带事务的普通 `DELETE`，不存在双存储一致性问题。
- 向量索引用 HNSW（50 人规模的十万级 chunk 查询毫秒级）；检索 = SQL `WHERE`（按知识库过滤）+ 向量排序。
- 通过 Spring AI `VectorStore` 抽象访问向量，业务代码不感知 pgvector。
- `volume` 持久化；每日 `pg_dump` 到宿主机备份目录（host crontab），保留 14 天——
  一份备份同时覆盖业务数据、向量与文档原始文件，天然时间点一致。

## 3. 五条核心请求流

1. **管理后台/控制台页面**：浏览器 → nginx 静态文件（毫秒级，不经 server）。
2. **普通 API**（建应用、传文档、看报表）：浏览器 → nginx `/api/v1/**` → server → PostgreSQL → JSON 返回。
3. **对话（流式）**：浏览器 → nginx（SSE 透传）→ server：配额检查 → 记忆装配 → RAG 检索（pgvector）
   → 云端 LLM（流式）→ token 逐段经 SSE 回推浏览器 → 结束后事件落用量。全程虚拟线程挂起等待，不占平台线程。
4. **工作流 API 触发**：第三方系统 → nginx `/v1/apps/{appKey}/**` → server（API Key 校验 → 配额）
   → 工作流引擎逐节点执行（LLM 节点出网 / 检索节点查 pgvector / 代码节点调 sandbox / HTTP 节点出网）→ 返回或异步回调。
5. **文档入库**：上传 → 存文档元数据 → 后台虚拟线程任务：解析分段 → 调 embedding API（批量池）
   → chunk 与向量同事务写入 PostgreSQL → 更新状态，前端轮询进度。

## 4. 运维矩阵

| 关注点 | 做法 |
|---|---|
| 健康检查 | server: Actuator `/actuator/health` → compose `healthcheck`；全部容器 `restart: unless-stopped` |
| 崩溃恢复 | 启动自愈：非终态"进行中"记录置 failed（见第 2 节 server 职责）；配合 restart 策略实现无人值守拉起 |
| 密钥 | `.env` + `env_file`（JWT 密钥、DB 密码、LLM API Key 不入镜像不入库——供应商 Key 在 DB 中加密存储，加密主密钥在 .env） |
| 日志 | 容器 json-file driver + `max-size: 50m, max-file: 5`；server 内 logback 用**纯文本 pattern**（非 JSON），格式含 `%X{traceId}`，便于单机直接 grep 排障（一期不接 ELK/Loki 等采集系统，JSON 结构化无收益反增噪音；接入采集时再切结构化输出） |
| 备份 | host crontab：`pg_dump` 每日一次（业务+向量一份搞定），保留 14 天，**每月做一次恢复演练** |
| 监控 | 一期从简：Actuator + healthcheck + 日志。预留 `/actuator/prometheus`，需要看板时加 Prometheus+Grafana 两个容器即可 |
| 发布 | `docker compose pull && docker compose up -d`（server 优雅停机兜底）；镜像 tag 用 git sha，可秒回滚 |

## 5. 网络与安全边界

- 三个 compose network：`frontend`（nginx↔server）、`backend`（server↔postgres）、
  `sandbox-net`（server↔sandbox，且 sandbox 无外网路由）。postgres/sandbox 均不发布宿主机端口。
- 出网方向：仅 server 可出网——LLM 供应商域名 + HTTP 节点/OpenAPI 工具/MCP 的业务目标
  （后者无法静态白名单，由下条应用层防护管控）；其余容器禁出网。
- **SSRF 防护（HTTP 请求节点、OpenAPI 自定义工具、MCP 连接共用一套）**：
  出站请求统一收口在 `infra` 提供的出站 HTTP 客户端，tool/workflow 模块禁止自建客户端。
  发请求前对 **DNS 解析后的 IP** 做校验（防域名指回内网）：禁止 loopback、RFC1918 私网段、
  link-local `169.254.0.0/16`（云元数据）、容器服务名（postgres / sandbox 等）；
  3xx 重定向跟随后对新地址重新校验。确需访问内网地址时由 admin 在系统设置中加显式白名单。
  一期拍板（2026-07-11，W3b spec）：3xx 一律**不跟随**（status+Location 原样返回节点输出，
  彻底封死重定向绕过）；内网白名单**暂缓**（一期只调公网，机制预留：SsrfValidator 查
  system_setting 放行）；不做 DNS pinning（校验与连接间的 rebinding 窗口以一期威胁模型
  评估可接受，二期对外开放时收紧）。
- **MCP 连接的落地细节（2026-07-15，T4a spec）**：MCP **只支持远程 HTTP**
  （`streamable_http` 默认 / `sse` 兼容），**不支持 stdio**——stdio 要求在 server 容器内 spawn
  子进程执行第三方代码，与「不可信代码绝不进 server、代码执行进独立沙箱」的既定姿态冲突。
  MCP 出站不走 `OutboundHttpClient`（MCP SDK 自带 HTTP 客户端），改由 `tool` 模块的
  `McpClientFactory` 收口**同一套闸门**：建连前过 `SsrfValidator`（同款禁内网/元数据）、
  `followRedirects(NEVER)`（不设则远端一个 302 即可绕过 SSRF 校验）、连接/请求/握手三重超时
  外化于 `hify.tool.mcp.*`。MCP 服务器地址由 admin 注册，同样仅限公网可达。

## 6. 二期触发条件与演进路径

| 信号 | 动作 |
|---|---|
| 用户到数百~千、单机 CPU/内存吃紧 | server 无状态 → 第二台机器跑多副本，nginx upstream 轮询（架构已就绪，零代码改动） |
| server 多副本后各进程 Caffeine 缓存不一致造成困扰 / 需要分布式限流计数 | 引入 Redis（纯缓存身份，无持久化压力），Spring Cache 实现从 Caffeine 切 Redis |
| 向量数据 > 千万级 chunk、单库查询变慢 | pgvector 分区表 / 读写分离；再不够才考虑专用向量库 |
| 公司平台强制 / 需要多副本自愈与滚动发布 | 迁 K8s，映射关系：nginx→Ingress、server→Deployment+HPA、postgres→托管服务或 StatefulSet、sandbox→独立 Deployment + NetworkPolicy（最好加 gVisor runtimeClass）、.env→Secret、healthcheck→liveness/readiness。组件与职责完全不变 |
