# Hify

[Dify](https://dify.ai) 的简化版 AI Agent 平台，面向单团队（20-50 人）内部使用、本地化部署。
覆盖「知识库问答」与「工作流自动化」两大核心场景。设计文档见 [`docs/architecture/`](docs/architecture)。

技术栈：Spring Boot 3（Java 21）+ Spring AI + MyBatis-Plus + PostgreSQL 16（pgvector）/ Vue 3 + TypeScript + Element Plus。

---

## 本地怎么跑

### 前置依赖

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 后端运行/构建 |
| Maven | 3.9+ | 后端构建 |
| Node | 24 | 前端运行/构建（见 `web/.nvmrc`）|
| pnpm | 9+ | 前端包管理 |
| Docker + Compose | — | 跑本地 PostgreSQL |

### 1. 起数据库

业务数据与向量同库，用 Docker 起 PostgreSQL 16 + pgvector：

```bash
docker compose up -d        # 启动（后台）
docker compose ps           # 等 STATUS 显示 healthy
```

### 2. 一键启动

```bash
make start
```

它会依次：检查 PostgreSQL 可用 → 构建并后台启动后端 → 轮询健康检查通过 → 后台启动前端。
完成后访问：

- 前端：<http://localhost:5173>
- 后端健康检查：<http://localhost:8080/api/v1/health>

查看运行日志：

```bash
tail -f .run/backend.log     # 后端
tail -f .run/frontend.log    # 前端
```

### 3. 停止

```bash
make stop                    # 优雅停止前后端（超时强制结束）
```

> 前后端以后台进程运行，PID 记录在 `.run/*.pid`（已被 git 忽略）。`make stop` 据此停止。

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

---

## 常用命令（Makefile）

| 命令 | 作用 |
|---|---|
| `make`（或 `make help`） | 列出所有命令 |
| `make start` | 检查 DB → 构建并起后端 → 健康检查 → 起前端 |
| `make stop` | 优雅停止前后端（SIGTERM，超时 SIGKILL） |
| `make restart` | 先 stop 再 start |
| `make build` | 构建后端 jar + 前端 dist |
| `make clean` | 清理构建产物（`server/target`、`web/dist`、`dist`） |
| `make package` | 打包可分发 `dist/hify-<版本>.tar.gz`（jar + 前端产物 + 部署配置） |

`start.sh` / `stop.sh` 也可单独直接运行；常用参数可用环境变量覆盖，例如：

```bash
HEALTH_TIMEOUT=60 ./start.sh     # 健康检查最长等 60s
STOP_TIMEOUT=5 ./stop.sh         # 优雅停止最多等 5s 再强杀
```

脚本与 Makefile 内含逐行中文注释，可直接当作参考。

---

## 仓库布局

```
hify/
├── server/              # Spring Boot 模块化单体（后端）
├── web/                 # Vue 3 前端（独立 pnpm 工程）
├── deploy/              # nginx 配置、.env.example
├── docker-compose.yml   # 本地依赖（当前：postgres）
├── start.sh / stop.sh   # 本地启停脚本
├── Makefile             # 统一命令入口
├── docs/architecture/   # 架构设计文档（写代码前必读）
└── docs/self-check.md   # 本地自检手册（每块基础组件怎么验证做对了）
```

更多约定见仓库根的 `CLAUDE.md` 与 `docs/architecture/` 下各文档；
改完想自己验证有没有生效，见 [`docs/self-check.md`](docs/self-check.md)。
