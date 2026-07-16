# 部署容器化收尾（单机 4 容器落地）设计

> 日期：2026-07-16
> 前置：deployment.md 已完整拍板目标形态（4 容器 / 3 网络 / nginx 职责 / 运维矩阵），本轮照图施工。
> 现状：compose 只有 postgres + sandbox；server 跑宿主机 mvn，前端跑 vite dev；deploy/.env.example 已存在；
> application.yml 全部配置已参数化为 `${ENV:默认值}`；前端 API 走同源相对路径 `/api/v1`（生产反代零改动）。
> 目标读者：实现计划（plans/）与执行者。

## 1. 目标与范围

**目标**：把 deployment.md 描述的「单机 4 容器」形态在本机落地——
`docker compose --profile app up -d` 一条命令拉起 nginx / hify-server / sandbox / postgres 全套，
浏览器走 `https://localhost` 完成黄金旅程（登录 → 聊天 SSE → 传文档 → 引用来源 → 含代码节点的工作流）。

**做**：
- `server/Dockerfile` + `server/.dockerignore`（多阶段：maven 编译 → JRE21 运行）
- 仓库根 `.dockerignore`（nginx 镜像构建上下文=仓库根，必须排除 `.git`、`web/node_modules`、`server/target` 等，否则上下文以 GB 计）
- `deploy/nginx/Dockerfile`（多阶段：node24+pnpm 构建 web 产物 → nginx:stable-alpine）
- `deploy/nginx/nginx.conf`（TLS、SPA 兜底、`/api` `/v1` 反代、SSE 透传、50m 上传、/healthz）
- `deploy/nginx/gen-self-signed-cert.sh`（自签证书生成，证书 gitignore 不入库不入镜像）
- `docker-compose.yml` 增 `hify-server`、`nginx` 服务（`profiles: [app]`）与 `frontend`/`backend` 网络
- `application.yml` 加 `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 50s`
- `deploy/.env.example` 增补容器化说明；`.gitignore` 排除 `deploy/nginx/certs/`
- `web/package.json` 加 `"packageManager": "pnpm@11.4.0"`（镜像内 corepack 与宿主机锁同一版本，自审时发现此字段缺失）
- README / docs/self-check.md 更新运行说明

**不做**（留给后续轮次 / YAGNI）：
- pg_dump 备份 crontab、日志表按月分区、优雅关闭等待 workflow 收尾的**行为验证**（第 2 轮）
- 镜像 tag 用 git sha 与发布流程（`compose pull && up`）——真有远程镜像仓库时再做
- 真实证书 / Caddy 自动签、真实 VM 部署演练
- CI 构建镜像；Prometheus/Grafana 监控容器
- server 多副本、Redis（scaling-path 二期触发条件未到）

**验收标准**（已拍板）：本机（WSL2）全容器形态跑通黄金旅程，见第 7 节清单。

## 2. 关键决策（已拍板）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 开发/生产形态共存 | **compose profiles**：postgres/sandbox 不标 profile（永远启动），nginx/hify-server 标 `profiles: [app]` | 一个文件零重复；日常 `docker compose up -d` 行为与现状完全一致。被否：两个 compose 文件（postgres/sandbox 定义抄两遍，改一处忘一处）；base+override（override 表达不了"减服务"，还得混用 profiles） |
| 镜像构建 | **多阶段构建**（编译也在 Docker 里） | 任何装了 Docker 的机器一条命令出镜像，不依赖宿主机 JDK/Node 版本；BuildKit 缓存挂载缓解重建耗时。被否：宿主机编译+拷产物（产物内容取决于宿主机环境，将来 VM 还要装全套工具链） |
| HTTPS | **自签证书脚本**（openssl 一条命令，产物 gitignore），nginx 443 + 80→301 跳转 | 打通完整 TLS 链路，换真证书只替换文件零改配置。被否：mkcert（WSL2 下 Windows 侧还要导信任链，超出"打通"目标）；先只 HTTP（HTTPS 配置的坑留到以后踩） |
| nginx 基础镜像 | `nginx:stable-alpine`（deployment.md 写的是 nginx:stable 的微调） | alpine 自带 busybox wget 可做 healthcheck（debian 版无任何 HTTP 工具），体积小一个量级，nginx 本体相同 |
| postgres 5432 端口 | 保留发布但收紧为 `127.0.0.1:5432`（对 deployment.md「只有 nginx 发布端口」的**有意偏差**） | 宿主机开发（mvn / 连库工具）依赖它；真实 VM 上线时删掉这一行即可，compose 注释写明 |

## 3. 文件与目录

```
hify/
├── docker-compose.yml            # 改：+hify-server +nginx（profiles: [app]）+frontend/backend 网络
├── .dockerignore                 # 新：nginx 构建上下文瘦身（.git/node_modules/target 等）
├── .gitignore                    # 改：+deploy/nginx/certs/
├── server/
│   ├── Dockerfile                # 新：多阶段 maven → JRE21
│   ├── .dockerignore             # 新：排除 target/ 等
│   └── src/main/resources/application.yml   # 改：graceful shutdown 两行
└── deploy/
    ├── .env.example              # 改：增补容器化说明（compose env_file 用 deploy/.env）
    └── nginx/
        ├── Dockerfile            # 新：多阶段 node/pnpm → nginx:stable-alpine（构建上下文=仓库根）
        ├── nginx.conf            # 新：完整 server 配置
        ├── gen-self-signed-cert.sh   # 新：自签证书生成
        └── certs/                # 运行时生成，gitignore
```

## 4. server 镜像与服务定义

**`server/Dockerfile`**：

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# 依赖单独成层：pom 不变不重下（多阶段"首次慢"的主要缓解）
RUN --mount=type=cache,target=/root/.m2 mvn -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
# healthcheck 探 Actuator 用；JRE 镜像无任何 HTTP 客户端
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system hify
USER hify
COPY --from=build /build/target/*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
```

要点：镜像构建跳过测试（测试属于开发/CI 环节）；非 root 运行；`JAVA_OPTS` 由 compose 注入堆参数。

**compose 服务**（核心字段，锚点 `&log-rotate` 四服务共用）：

```yaml
hify-server:
  build: ./server
  container_name: hify-server
  profiles: [app]
  env_file: deploy/.env            # 敏感项：JWT 密钥 / 加密主密钥 / admin 引导
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-hify}?reWriteBatchedInserts=true
    JAVA_OPTS: -Xmx4g              # deployment.md：堆 4G
  networks: [frontend, backend, sandbox-net]   # 唯一三网皆通的枢纽
  depends_on:
    postgres: { condition: service_healthy }   # Flyway 启动迁移须等库就绪
    sandbox: { condition: service_started }
  healthcheck:
    test: ["CMD", "curl", "-fsS", "http://localhost:8080/actuator/health"]
    interval: 10s
    timeout: 3s
    retries: 10
    start_period: 60s              # 启动+迁移的宽限期
  stop_grace_period: 60s
  restart: unless-stopped
  mem_limit: 6g
  logging: &log-rotate
    driver: json-file
    options: { max-size: 50m, max-file: "5" }
```

配置分工原则：**容器拓扑地址**（`postgres:5432` 服务名、sandbox 默认 `http://sandbox:8000` 已是容器形态）写 compose `environment`；**密钥**走 `env_file: deploy/.env`。宿主机 mvn 开发不受影响（application.yml 默认值仍是 localhost）。

**application.yml 增补**：

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 50s   # < compose stop_grace_period 60s，应用先于强杀收尾
```

注：本轮只让配置就位；「优雅关闭等待 workflow 收尾」的行为验证属第 2 轮，不进本轮验收。

## 5. nginx 镜像与配置

**`deploy/nginx/Dockerfile`**（compose 里 `build: { context: ., dockerfile: deploy/nginx/Dockerfile }`，
上下文必须是仓库根——要同时拿到 `web/` 与 `deploy/nginx/`）：

```dockerfile
FROM node:24-slim AS build
RUN corepack enable                # 用仓库锁定的 pnpm 版本
WORKDIR /build
COPY web/package.json web/pnpm-lock.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
COPY web/ .
RUN pnpm build

FROM nginx:stable-alpine
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
```

**`nginx.conf` 关键配置与理由**：

| 配置 | 理由 |
|---|---|
| `listen 80`：`location = /healthz { return 200; }` + 其余 301 跳 https | healthcheck 走 80 免 TLS；用户流量强制加密 |
| `listen 443 ssl`；证书路径 `/etc/nginx/certs/`（volume 挂载 `deploy/nginx/certs:ro`） | 证书不进镜像，换真证书只替换文件 |
| `location / { try_files $uri $uri/ /index.html; }` | Vue Router history 模式，深层路由刷新须兜底回 index.html |
| `location /api/ { proxy_pass http://hify-server:8080; proxy_buffering off; proxy_read_timeout 600s; proxy_http_version 1.1; proxy_set_header Connection ""; }` | **SSE 命门**：默认 buffering 会把流式攒成一次性输出；600s 对齐流式总时长上限；HTTP/1.1 长连接 |
| `location /v1/ { proxy_pass ... }` | 应用对外 API 预留（第 4 轮做后端，先开门） |
| `client_max_body_size 50m` | 对齐 multipart 50MB；nginx 默认 1m 会在门口 413 拦掉文档上传 |
| 常规 `proxy_set_header`（Host / X-Real-IP / X-Forwarded-For / X-Forwarded-Proto） | server 侧取真实来源与协议 |

**compose 服务**：

```yaml
nginx:
  build: { context: ., dockerfile: deploy/nginx/Dockerfile }
  container_name: hify-nginx
  profiles: [app]
  ports: ["80:80", "443:443"]      # 全栈唯一对外发布端口的容器（postgres 的 127.0.0.1 除外，见决策表）
  volumes:
    - ./deploy/nginx/certs:/etc/nginx/certs:ro
  networks: [frontend]
  depends_on:
    hify-server: { condition: service_healthy }
  healthcheck:
    test: ["CMD", "wget", "-qO-", "http://127.0.0.1/healthz"]
    interval: 10s
    timeout: 3s
    retries: 5
  restart: unless-stopped
  mem_limit: 256m
  logging: *log-rotate
```

**`gen-self-signed-cert.sh`**：一条 openssl 命令，10 年期，`CN=localhost`，
SAN 含 `DNS:localhost` 与 `IP:127.0.0.1`，输出 `certs/hify.crt` / `certs/hify.key`。
未生成证书或未建 `deploy/.env` 就 `--profile app up` 会得到明确的启动失败报错（不会静默错配），
两个前置步骤写进 compose 头部注释与 README。

## 6. 网络拓扑

```
宿主机端口:  80/443 → nginx        127.0.0.1:5432 → postgres（开发保留，VM 上线删）
frontend    : nginx ↔ hify-server        （nginx 不见库与沙箱）
backend     : hify-server ↔ postgres      （postgres 不出网）
sandbox-net : hify-server ↔ sandbox       （internal，现状不动）
```

postgres 服务加入 `backend` 网络并把端口发布改为 `127.0.0.1:5432:5432`；sandbox 服务定义零改动。

## 7. 验收清单（黄金旅程，全程容器形态）

1. `bash deploy/nginx/gen-self-signed-cert.sh`；`cp deploy/.env.example deploy/.env`；
   `docker compose --profile app up -d --build` → 4 容器全部 healthy；
2. `http://localhost` 自动 301 → `https://localhost`，信任自签证书后 admin 登录成功；
3. 聊天应用对话，SSE 逐字流式正常（验 buffering off）；
4. 上传几 MB 的文档 → 分段/向量化完成 → 聊天出现引用来源（验 50m 上传与后台任务）；
5. 含**代码节点**的工作流跑通（验 server↔sandbox 内网连通——宿主机形态此前做不到）；
6. 深层路由（如应用详情页）直接刷新不 404（验 SPA 兜底）；
7. `--profile app down` 后再 up，数据仍在（volume 持久化）；
8. 回归开发形态：`docker compose up -d` 只起 postgres+sandbox，宿主机 mvn + vite dev 照常。

## 8. 测试策略与风险

**测试**：基础设施轮，不新增自动化测试（Dockerfile/nginx.conf 无单元测试落点，第 7 节验收即测试）；
已有后端/前端测试套件必须保持全绿（本轮代码改动仅 application.yml 两行，风险极低）。

**风险与对策**：

| 风险 | 对策 |
|---|---|
| 首次构建慢（拉基础镜像+全量依赖） | 一次性成本；缓存挂载让后续重建只剩编译时间 |
| WSL2 内存吃紧（6g+6g 限额） | mem_limit 是上限非预留，实际占用远低；吃紧再调 |
| 自签证书浏览器报警 | 预期行为，验收步骤写明手动信任一次 |
| pnpm 版本不匹配导致 frozen-lockfile 失败 | 本轮给 web/package.json 补 `packageManager: pnpm@11.4.0`（现状缺失），corepack 据此锁版本 |
| E2E（Playwright）/日常开发受影响 | 全部跑在宿主机开发形态，本轮对其零改动 |
| 容器内出网不走宿主机代理（2026-07-17 实证：DNS 返回 fake-IP，Maven Central/Docker Hub 直连超时） | Docker 基础镜像走 DaoCloud 等国内源；Maven 依赖用构建专用 `server/maven-settings.xml` 走阿里云镜像（`mvn -s` 引用，不放 /root/.m2 以免被缓存挂载遮盖；宿主机 ~/.m2 不受影响） |
