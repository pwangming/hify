# 部署容器化收尾（单机 4 容器落地）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 deployment.md 的「单机 4 容器」形态——`docker compose --profile app up -d` 拉起 nginx / hify-server / sandbox / postgres 全套，`https://localhost` 跑通黄金旅程。

**Architecture:** compose profiles 区分两种形态（postgres/sandbox 永远启动；nginx/hify-server 标 `profiles: [app]`）；两个自建镜像均多阶段构建（编译在 Docker 内完成）；三网拓扑 frontend/backend/sandbox-net，server 是唯一三网枢纽；自签证书 volume 挂载不入镜像。

**Tech Stack:** Docker Compose（profiles / healthcheck / BuildKit 缓存挂载）、maven:3.9-eclipse-temurin-21 → eclipse-temurin:21-jre、node:24-slim + corepack/pnpm → nginx:stable-alpine、openssl 自签。

**Spec:** `docs/superpowers/specs/2026-07-16-deployment-containerization-design.md`（决策依据都在里面，冲突以 spec 为准）。

## Global Constraints

- 日常开发行为零变化：`docker compose up -d` 仍只起 postgres + sandbox；宿主机 mvn / vite dev 照常（application.yml 默认值不改）。
- sandbox 服务定义**只增 logging 锚点引用，其余零改动**（安全加固是既定拍板）。
- 证书与密钥不入库不入镜像：`deploy/nginx/certs/` 进 .gitignore；`deploy/.env` 已被现有 `.env` 规则忽略。
- 镜像构建跳过测试（`-DskipTests`）；测试在宿主机/CI 跑。
- postgres 端口收紧为 `127.0.0.1:5432:5432`（对 deployment.md 的有意偏差，注释必须写明生产 VM 删除此行）。
- 判定 mvn 结果看**退出码**，禁止 grep "BUILD SUCCESS"（`-q` 会静音）。
- 所有新文件注释用中文，风格对齐仓库现状。

---

### Task 1: server 镜像（Dockerfile + .dockerignore）

**Files:**
- Create: `server/Dockerfile`
- Create: `server/.dockerignore`

**Interfaces:**
- Produces: 本地镜像 `hify-server:dev`（Task 3 的 compose `build: ./server` 复用同一 Dockerfile）；运行时约定：监听 8080、`JAVA_OPTS` 环境变量注入 JVM 参数、jar 内含 Actuator `/actuator/health`（SecurityConfig 已放行 `/actuator/**`，已核实）。

- [ ] **Step 1: 写 `server/.dockerignore`**

```
# 构建上下文瘦身：镜像内用 maven 重新编译，宿主机产物不需要
target/
*.iml
```

- [ ] **Step 2: 写 `server/Dockerfile`**

```dockerfile
# 多阶段构建（spec 决策 2）：编译也在 Docker 里，镜像产出不依赖宿主机 JDK。
# 阶段1：maven 编译。BuildKit 缓存挂载 /root/.m2，依赖不变时重建只剩编译时间。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# 依赖单独成层：pom 不变不重下（多阶段"首次慢"的主要缓解）
RUN --mount=type=cache,target=/root/.m2 mvn -q dependency:go-offline
COPY src ./src
# 跳过测试：测试属于开发/CI 环节，构建镜像时重复跑只拖慢发布
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

# 阶段2：仅 JRE + jar，镜像小、攻击面小
FROM eclipse-temurin:21-jre
# healthcheck 探 Actuator 用；JRE 镜像无任何 HTTP 客户端
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# 非 root 运行（与 sandbox 同一安全姿态）
RUN useradd --system hify
USER hify
COPY --from=build /build/target/*.jar /app.jar
EXPOSE 8080
# JAVA_OPTS 由 compose 注入（如 -Xmx4g），本地 docker run 不传则用 JVM 默认
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
```

- [ ] **Step 3: 构建验证**

Run: `docker build -t hify-server:dev server/`
Expected: 成功出镜像（首次约 5-10 分钟，拉基础镜像+下依赖）。失败常见原因：网络拉不动镜像/依赖——重试或配镜像源，不要改 Dockerfile 结构。

- [ ] **Step 4: 运行层冒烟（不连库，只验 jar 与入口）**

Run: `docker run --rm hify-server:dev sh -c 'ls -la /app.jar && java -version' 2>&1 | tail -5`
Expected: 列出 /app.jar（约 100MB+）、`openjdk version "21`。

- [ ] **Step 5: Commit**

```bash
git add server/Dockerfile server/.dockerignore
git commit -m "feat(deploy): server 多阶段镜像（maven 编译层缓存挂载 + JRE21 非 root 运行层）"
```

---

### Task 2: nginx 镜像（前端产物 + nginx.conf + 自签证书脚本）

**Files:**
- Create: `deploy/nginx/Dockerfile`
- Create: `deploy/nginx/nginx.conf`
- Create: `deploy/nginx/gen-self-signed-cert.sh`
- Create: `.dockerignore`（仓库根——nginx 构建上下文=仓库根，必须瘦身）
- Modify: `web/package.json`（补 `packageManager` 字段，spec 自审发现缺失）
- Modify: `.gitignore`（排除证书目录）

**Interfaces:**
- Consumes: 无（独立于 Task 1）。
- Produces: 本地镜像 `hify-nginx:dev`（Task 3 compose 复用同一 Dockerfile）；运行时约定：容器内 80/443、证书从 `/etc/nginx/certs/hify.crt|hify.key` 读（volume 挂载）、反代目标写死服务名 `hify-server:8080`、80 端口 `/healthz` 返回 200 供 healthcheck。

- [ ] **Step 1: `web/package.json` 补 packageManager 字段**

在 `"private": true,` 一行后（或 scripts 之前的顶层位置）加：

```json
  "packageManager": "pnpm@11.4.0",
```

为什么：镜像内 `corepack enable` 按此字段锁定 pnpm 版本，与宿主机（11.4.0）一致，否则 `--frozen-lockfile` 可能因 pnpm 版本差异校验失败。

- [ ] **Step 2: 写仓库根 `.dockerignore`**

```
# nginx 镜像构建上下文=仓库根（deploy/nginx/Dockerfile 要同时拿 web/ 与 deploy/nginx/）。
# 不排除的话 .git/node_modules/target 全被送进构建上下文（GB 级）。
# 注意：此文件只作用于「上下文=仓库根」的构建；server 镜像上下文=server/，用 server/.dockerignore。
.git/
**/node_modules/
**/target/
**/dist/
docs/
scripts/
mcp-demo/
sandbox/
server/
deploy/nginx/certs/
.run/
```

- [ ] **Step 3: 写 `deploy/nginx/nginx.conf`**

```nginx
# Hify nginx（deployment.md §1/§2 落地）：TLS 终止、Vue 产物托管、/api /v1 反代、SSE 透传。
# 本文件被拷进镜像的 /etc/nginx/conf.d/default.conf；证书运行时 volume 挂载，不进镜像。

# 80：仅 healthcheck 探活 + 强制跳 https
server {
    listen 80;
    server_name _;

    # 容器 healthcheck 专用：不走 TLS、不产生业务日志
    location = /healthz {
        access_log off;
        return 200;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl;
    server_name _;

    # 自签/真实证书同一路径：换真证书只替换文件重启，镜像零重建
    ssl_certificate     /etc/nginx/certs/hify.crt;
    ssl_certificate_key /etc/nginx/certs/hify.key;

    # 对齐 server 的 multipart 50MB（application.yml）；nginx 默认 1m 会在门口 413 拦掉文档上传
    client_max_body_size 50m;

    root  /usr/share/nginx/html;
    index index.html;

    # Vue Router history 模式：深层路由刷新时磁盘无对应文件，兜底回 index.html 由前端路由接管
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 前端 API（含 SSE 流式端点）
    location /api/ {
        proxy_pass http://hify-server:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # SSE 命门：默认 buffering 会把流式攒成一次性输出（deployment.md §2「SSE 必配」）
        proxy_buffering off;
        # 对齐流式总时长上限
        proxy_read_timeout 600s;
    }

    # 应用对外 API（第 4 轮做后端，先把门开好）
    location /v1/ {
        proxy_pass http://hify-server:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

- [ ] **Step 4: 写 `deploy/nginx/Dockerfile`**

```dockerfile
# 多阶段：node 构建 Vue 产物 → 拷进 nginx。构建上下文必须是仓库根：
#   docker build -f deploy/nginx/Dockerfile .
# alpine 变体（对 deployment.md nginx:stable 的微调，spec 决策 4）：自带 busybox wget
# 可做 healthcheck（debian 版无任何 HTTP 工具），体积小一个量级，nginx 本体相同。
FROM node:24-slim AS build
# corepack 按 web/package.json 的 packageManager 字段锁定 pnpm 版本（与宿主机一致）
RUN corepack enable
WORKDIR /build
COPY web/package.json web/pnpm-lock.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
COPY web/ .
# vue-tsc 类型检查 + vite build，产物 → /build/dist（.env.production 的同源相对路径生效）
RUN pnpm build

FROM nginx:stable-alpine
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
```

- [ ] **Step 5: 写 `deploy/nginx/gen-self-signed-cert.sh` 并加执行位**

```bash
#!/usr/bin/env bash
# 生成本地自签证书（10 年期）到 deploy/nginx/certs/，供 nginx 容器 volume 挂载。
# 产物 gitignore 不入库；生产换真证书 = 用同名文件替换后重启 nginx 容器。
# 浏览器首次访问会提示"不安全"，手动信任一次即可（自签证书的正常现象）。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p certs
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
  -keyout certs/hify.key -out certs/hify.crt
echo "已生成: $(pwd)/certs/hify.crt 与 hify.key"
```

Run: `chmod +x deploy/nginx/gen-self-signed-cert.sh`

- [ ] **Step 6: `.gitignore` 追加证书目录**

在文件末尾追加：

```
# nginx 自签/真实证书（gen-self-signed-cert.sh 生成或运维放置），私钥严禁入库
deploy/nginx/certs/
```

- [ ] **Step 7: 构建镜像**

Run: `docker build -t hify-nginx:dev -f deploy/nginx/Dockerfile .`
Expected: 成功。传输的构建上下文应在几 MB 量级（看第一行 `transferring context` 输出；如果是几百 MB+，说明 `.dockerignore` 没生效，停下排查）。

- [ ] **Step 8: 单容器冒烟（不依赖 server）**

nginx 启动时要解析 `proxy_pass` 里的 `hify-server` 主机名，单跑会因解析失败退出；用 `--add-host` 造一个假解析绕过：

```bash
bash deploy/nginx/gen-self-signed-cert.sh
docker run --rm -d --name nginx-smoke \
  -p 127.0.0.1:8081:80 -p 127.0.0.1:8443:443 \
  --add-host hify-server:127.0.0.1 \
  -v "$PWD/deploy/nginx/certs:/etc/nginx/certs:ro" \
  hify-nginx:dev
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/healthz     # 期望 200
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' http://127.0.0.1:8081/   # 期望 301 https://...
curl -sk https://127.0.0.1:8443/ | grep -c '<div id="app">'               # 期望 1（SPA 壳）
curl -sk -o /dev/null -w '%{http_code}\n' https://127.0.0.1:8443/chat      # 期望 200（深层路由兜底）
docker stop nginx-smoke
```

- [ ] **Step 9: Commit**

```bash
git add deploy/nginx/ .dockerignore .gitignore web/package.json
git commit -m "feat(deploy): nginx 镜像（node 多阶段构建 Vue 产物）+ TLS/SSE/SPA 配置 + 自签证书脚本"
```

---

### Task 3: compose 全套编排 + 优雅停机配置 + 全栈拉起冒烟

**Files:**
- Modify: `docker-compose.yml`（整文件重写，见 Step 2）
- Modify: `server/src/main/resources/application.yml`（两处插入）
- Modify: `deploy/.env.example`（头部说明改写）

**Interfaces:**
- Consumes: Task 1 的 `server/Dockerfile`（`build: ./server`）、Task 2 的 `deploy/nginx/Dockerfile` 与证书脚本。
- Produces: `docker compose --profile app up -d` 可用的完整编排；Task 4 的验收在此形态上进行。

- [ ] **Step 1: application.yml 两处插入**

插入 1——在现有 `spring:` 块内、`application:` 小节之后加 `lifecycle:`（注意：`spring:` 顶层键已存在，不能新开一个，YAML 重复键会报错）：

```yaml
spring:
  application:
    name: hify-server

  # 优雅停机搭档（deployment.md §2）：SIGTERM 后各阶段最多等 50s，
  # 必须小于 compose 的 stop_grace_period(60s)，让应用先于 docker 强杀完成收尾。
  lifecycle:
    timeout-per-shutdown-phase: 50s
```

插入 2——在 `# 暴露健康检查端点，便于冒烟验证启动是否成功` 注释行之前，加新顶层块：

```yaml
# 优雅停机（deployment.md §2）：SIGTERM 后停止接新请求，等在途请求（含 workflow）收尾再退出。
# 「等待 workflow 收尾」的行为验证属第 2 轮（运维补账），本轮只让配置就位。
server:
  shutdown: graceful
```

- [ ] **Step 2: 整文件重写 `docker-compose.yml`**

```yaml
# Hify 单机部署编排（deployment.md「单机 4 容器」落地，设计见
# docs/superpowers/specs/2026-07-16-deployment-containerization-design.md）。
#
# 两种形态（compose profiles）：
#   日常开发：docker compose up -d                 → 仅 postgres + sandbox（server/前端跑宿主机）
#   全套形态：docker compose --profile app up -d   → nginx / hify-server / sandbox / postgres
#
# 全套形态的两个前置步骤（缺任一会启动失败并报清晰错误，不会静默错配）：
#   1) bash deploy/nginx/gen-self-signed-cert.sh   # 生成自签证书（gitignore 不入库）
#   2) cp deploy/.env.example deploy/.env          # server 敏感配置（生产务必改默认值）
#
# 常用：
#   docker compose ps                              # 等 STATUS 全部 healthy
#   docker compose --profile app up -d --build     # 代码变更后重建镜像
#   docker compose --profile app down              # 停全套（保留数据卷）；down -v 清空数据
#
# 凭证默认值仅供本地开发；生产改用 .env 注入（CLAUDE.md：密码走 .env，不入库不入镜像）。

services:
  postgres:
    # pgvector 官方镜像 = PostgreSQL 16 + 预装 pgvector 扩展
    image: pgvector/pgvector:pg16
    container_name: hify-postgres
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-hify}
      POSTGRES_USER: ${POSTGRES_USER:-hify}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-hify}
    ports:
      # 仅本机可连，供宿主机开发（mvn / 连库工具）。生产 VM 上线时删除此发布，
      # 即完全满足 deployment.md「只有 nginx 对外发布端口」（spec 拍板的有意偏差）。
      - "127.0.0.1:5432:5432"
    volumes:
      - hify-pgdata:/var/lib/postgresql/data
    networks: [backend]
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-hify} -d ${POSTGRES_DB:-hify}"]
      interval: 5s
      timeout: 3s
      retries: 10
    logging: &log-rotate   # 四服务共用：json-file 50m×5（deployment.md §4）
      driver: json-file
      options: { max-size: 50m, max-file: "5" }

  sandbox:
    build: ./sandbox
    container_name: hify-sandbox
    read_only: true
    cap_drop: [ALL]
    security_opt:
      - no-new-privileges:true
    networks:
      - sandbox-net
    mem_limit: 1g
    cpus: "1.0"
    tmpfs:
      - /tmp          # read_only 下给用户代码临时文件一块可写内存盘
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "python -c \"import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health')\""]
      interval: 10s
      timeout: 3s
      retries: 5
    logging: *log-rotate
    # 沙箱不对外发布端口（deployment.md §5）。sandbox-net 为 internal，宿主机无法访问沙箱——
    # 全套形态下 hify-server 容器经 sandbox-net 直连（生产形态）；宿主机开发跑 code 节点
    # 仍需 docker-compose.override.yml 临时放开。直连沙箱冒烟用 `docker compose exec sandbox`。

  hify-server:
    build: ./server
    container_name: hify-server
    profiles: [app]
    # 敏感项（JWT 密钥 / 加密主密钥 / admin 引导）走 env_file，不写在本文件
    env_file: deploy/.env
    environment:
      # 容器拓扑地址属部署形态非密钥，直接写这里；宿主机开发不受影响（application.yml 默认 localhost）
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-hify}?reWriteBatchedInserts=true
      JAVA_OPTS: "-Xmx4g"   # deployment.md：堆 4G（容器限 6g）
    networks: [frontend, backend, sandbox-net]   # 唯一三网皆通的枢纽
    depends_on:
      postgres:
        condition: service_healthy   # Flyway 启动迁移必须等库就绪
      sandbox:
        condition: service_started
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 3s
      retries: 10
      start_period: 60s   # 启动+Flyway 迁移的宽限期，期内失败不计数
    stop_grace_period: 60s   # 配合 server.shutdown=graceful（application.yml 50s < 60s）
    restart: unless-stopped
    mem_limit: 6g
    logging: *log-rotate

  nginx:
    build:
      context: .   # 上下文=仓库根：要同时拿 web/ 源码与 deploy/nginx/ 配置（.dockerignore 已瘦身）
      dockerfile: deploy/nginx/Dockerfile
    container_name: hify-nginx
    profiles: [app]
    ports:
      - "80:80"
      - "443:443"   # 全栈唯一对外发布端口的容器（postgres 的 127.0.0.1 除外，见其注释）
    volumes:
      - ./deploy/nginx/certs:/etc/nginx/certs:ro   # 证书不进镜像，换真证书只替换文件重启
    networks: [frontend]   # 只见 server，不见 postgres/sandbox
    depends_on:
      hify-server:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1/healthz"]
      interval: 10s
      timeout: 3s
      retries: 5
    restart: unless-stopped
    mem_limit: 256m
    logging: *log-rotate

networks:
  frontend:
    driver: bridge
  backend:
    driver: bridge
  # internal: true = 禁一切出网（deployment.md §5：sandbox 无外网路由）。
  # 代价：宿主机也访问不到沙箱，本地联调见 sandbox 服务注释。
  sandbox-net:
    driver: bridge
    internal: true

volumes:
  hify-pgdata:
```

- [ ] **Step 3: `deploy/.env.example` 头部说明改写**

把文件开头两行注释：

```
# Hify 环境变量样例。复制为 deploy/.env 并按需修改；真实 .env 不入库（见 .gitignore）。
# 当前阶段只用到数据库相关项；模型 API Key、JWT 主密钥等后续接入时再补。
```

替换为：

```
# Hify 环境变量样例。复制为 deploy/.env 并按需修改；真实 .env 不入库（见 .gitignore）。
# 两处消费：① docker-compose.yml 顶层变量插值（POSTGRES_* 需在仓库根 .env 或 shell 导出，
#   compose 默认只读仓库根 .env——本地开发用默认值即可不用管）；
#   ② 全套形态（--profile app）下 hify-server 服务经 env_file 整文件注入容器，
#   所以 `docker compose --profile app up` 前必须先 `cp deploy/.env.example deploy/.env`。
# 生产部署务必改掉所有 dev-only 默认值（JWT 密钥 / 主密钥 / admin 密码）。
```

- [ ] **Step 4: compose 语法与插值校验**

Run: `docker compose --profile app config -q && echo COMPOSE-OK`
Expected: 输出 `COMPOSE-OK`（无 warning 报错）。

- [ ] **Step 5: 前置准备 + 全栈拉起**

```bash
# 证书（Task 2 冒烟已生成过则跳过）；deploy/.env 若已存在则不要覆盖（可能有本地真实配置）
[ -f deploy/nginx/certs/hify.crt ] || bash deploy/nginx/gen-self-signed-cert.sh
[ -f deploy/.env ] || cp deploy/.env.example deploy/.env
docker compose --profile app up -d --build
```

Expected: 4 个容器依序启动（postgres → sandbox/server → nginx）。

- [ ] **Step 6: 等全部 healthy**

Run: `docker compose --profile app ps`（server 启动+迁移最多等 ~2 分钟，可重复执行观察）
Expected: hify-postgres / hify-sandbox / hify-server / hify-nginx 四行，STATUS 均含 `(healthy)`。
若 hify-server 起不来：`docker logs hify-server --tail 50` 排查（常见：deploy/.env 缺失、数据库连接串错）。

- [ ] **Step 7: 链路冒烟（宿主机 curl）**

```bash
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' http://localhost/          # 期望: 301 https://localhost/
curl -sk https://localhost/ | grep -c '<div id="app">'                              # 期望: 1
curl -sk -o /dev/null -w '%{http_code}\n' https://localhost/chat                    # 期望: 200（SPA 深层路由）
# 经 nginx 反代打真实后端（验 frontend 网络 + 反代 + server↔postgres）：
curl -sk -X POST https://localhost/api/v1/identity/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Qwer1234"}'
# 期望: {"code":0,...} 含 token。若 401：本地库 admin 密码与 .env.example 默认值不同，
# 用你实际的 admin 密码重试（账号早已在库，见 memory admin-account-seeded）。
```

- [ ] **Step 8: 回归日常开发形态**

```bash
docker compose --profile app down     # 停全套（数据卷保留）
docker compose up -d                  # 日常形态
docker compose ps                     # 期望：只有 hify-postgres 与 hify-sandbox 两行
docker compose --profile app up -d    # 恢复全套（镜像已建好，秒起），供 Task 4 验收
```

- [ ] **Step 9: Commit**

```bash
git add docker-compose.yml server/src/main/resources/application.yml deploy/.env.example
git commit -m "feat(deploy): compose 全套编排（profiles 双形态/三网拓扑/健康检查链）+ 优雅停机配置"
```

---

### Task 4: 文档更新 + 测试回归 + 黄金旅程人工验收

**Files:**
- Modify: `README.md`（「本地怎么跑」下新增全套形态小节）
- Modify: `docs/self-check.md`（追加本轮自检，格式沿用文件既有条目）

**Interfaces:**
- Consumes: Task 3 已拉起的全套形态。
- Produces: 无代码接口；本轮收尾。

- [ ] **Step 1: README「本地怎么跑」新增小节**

在 `### 3. 停止` 小节之后插入：

```markdown
### 4. 生产形态（4 容器全套，deployment.md 落地）

日常开发用不到；验收部署形态或演练上线时用。

```bash
# 前置（仅首次）：自签证书 + 敏感配置
bash deploy/nginx/gen-self-signed-cert.sh
cp deploy/.env.example deploy/.env        # 生产务必改掉 dev-only 默认值

docker compose --profile app up -d --build   # nginx / hify-server / sandbox / postgres
docker compose --profile app ps              # 等 4 个容器全部 healthy
# 浏览器访问 https://localhost（自签证书首次需手动信任）
docker compose --profile app down            # 停全套（数据卷保留）
```

要点：只有 nginx 对外发布 80/443（postgres 的 127.0.0.1:5432 是留给宿主机开发的，
生产 VM 上删掉）；证书与 deploy/.env 均不入库；SSE/上传体积等反代配置见 deploy/nginx/nginx.conf。
```

- [ ] **Step 2: 后端测试回归**

Run: `cd server && mvn -q verify; echo "exit=$?"`
Expected: `exit=0`（判退出码，不 grep 输出——`-q` 会静音 BUILD SUCCESS）。
本轮唯一后端代码改动是 application.yml 两段配置，理论零影响；若挂了先看是否环境问题（Testcontainers 需要 Docker 正常）。

- [ ] **Step 3: 前端测试回归**

Run: `pnpm -C web test; echo "exit=$?"`
Expected: `exit=0`（vitest run 全绿）。本轮前端只加了 packageManager 字段，理论零影响。

- [ ] **Step 4: 追加 docs/self-check.md**

沿用文件既有条目格式，追加「部署容器化收尾」一节：本轮做了什么（4 容器/profiles/自签 TLS/优雅停机配置就位）、验证方式（Task 3 冒烟 + 黄金旅程）、已知边界（优雅关闭行为验证留第 2 轮；生产 VM 需删 postgres 端口发布、换真证书、改 .env 默认值）。

- [ ] **Step 5: Commit**

```bash
git add README.md docs/self-check.md
git commit -m "docs(deploy): README 全套形态运行说明 + self-check 容器化轮自检"
```

- [ ] **Step 6: 黄金旅程人工验收（交给用户在浏览器完成，curl 覆盖不了的部分）**

引导用户在 `https://localhost`（全套形态运行中）逐项确认并回报：

1. 信任自签证书后 admin 登录成功；
2. 聊天应用对话，SSE 逐字流式正常（不是卡顿后一次全出——验 `proxy_buffering off`）；
3. 上传一个几 MB 的文档 → 分段/向量化完成 → 聊天出现引用来源（验 50m 上传限制与后台任务）；
4. 跑一个含**代码节点**的工作流成功（验 server↔sandbox 内网连通，宿主机形态此前做不到）；
5. 任意深层页面（应用详情/画布）直接 F5 刷新不 404；
6. `docker compose --profile app down` 再 `up -d`，登录后数据都在。

全部通过 = 本轮验收完成（spec 第 7 节清单闭环）。

---

## Self-Review 记录（写完计划后自查）

- **Spec 覆盖**：spec §1「做」的 9 项 → Task 1（server 镜像×2 文件）、Task 2（nginx 镜像/conf/证书脚本/根 .dockerignore/packageManager/.gitignore）、Task 3（compose/application.yml/.env.example）、Task 4（README/self-check）。spec §7 验收 8 条 → Task 3 Step 6-8 覆盖 1/2/6(部分)/7(部分)/8，Task 4 Step 6 覆盖 2-7 浏览器部分。无缺口。
- **占位符**：无 TBD/TODO；所有代码/配置/命令均完整给出。
- **一致性**：镜像内证书路径 `/etc/nginx/certs/hify.crt|key` = 脚本产物名 = compose 挂载路径 ✅；`hify-server:8080` 服务名 = compose 服务键名 ✅（healthcheck 用 curl，Task 1 已装）；`stop_grace_period: 60s` > `timeout-per-shutdown-phase: 50s` ✅；nginx healthcheck 用 wget = alpine 自带 ✅。
