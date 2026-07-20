# Hify

[![CI](https://github.com/pwangming/hify/actions/workflows/ci.yml/badge.svg)](https://github.com/pwangming/hify/actions/workflows/ci.yml)

[Dify](https://dify.ai) 的简化版 AI Agent 平台，面向单团队（20-50 人）内部使用、本地化部署。
覆盖「知识库问答」与「工作流自动化」两大核心场景。设计文档见 [`docs/architecture/`](docs/architecture)。

技术栈：Spring Boot 3（Java 21）+ Spring AI + MyBatis-Plus + PostgreSQL 16（pgvector）/ Vue 3 + TypeScript + Element Plus。

## 功能一览

- **对话应用**：多轮记忆、SSE 流式输出、知识库引用来源卡片；可开启 Agent 模式（见下）
- **Agent 工具调用**：基于 Function Calling；内置 HTTP 请求/代码执行两个工具，支持按 OpenAPI 规范注册自定义工具、接入远程 MCP 服务器（统一工具注册表，按应用勾选）
- **知识库（RAG）**：文档上传 → 自动分段 → 向量化 → 检索，向量与业务数据同存 PostgreSQL（pgvector），删改有事务保证
- **工作流应用**：可视化画布编排，节点：LLM / 知识检索 / 条件分支 / HTTP 请求 / 代码执行（独立 Python 沙箱容器）
- **模型管理**：OpenAI 兼容协议（通义千问/Gemini/Ollama 等）+ Anthropic 协议两种接入；供应商 API Key 库内加密存储
- **管理控制台**（Admin）：供应商/模型配置、用户管理、自定义工具与 MCP 注册表、系统设置
- **权限**：Admin / Member 两级；资源团队共享（改删仅 owner 与 Admin），会话仅本人
- **配额**：按用户每日 Token 上限，防 Agent 异常循环刷爆账单

---

## 本地怎么跑

### 前置依赖

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 后端运行/构建 |
| Maven | 3.9+ | 后端构建 |
| Node | 24 | 前端运行/构建（见 `web/.nvmrc`）|
| pnpm | 11.4.0 | 前端包管理（`web/package.json` 的 `packageManager` 锁定，`corepack enable` 即可）|
| Docker + Compose | — | 跑基础容器（PostgreSQL、代码沙箱）；全套形态还构建 server/nginx 镜像 |

### 1. 起基础容器

PostgreSQL 16 + pgvector（业务数据与向量同库）和 Python 代码沙箱：

```bash
docker compose up -d        # 启动（后台）：postgres + sandbox
docker compose ps           # 等 STATUS 显示 healthy
```

> 沙箱在 internal 网络内，宿主机开发形态下 server 访问不到它（跑 code 节点
> 需临时 override 或直接用下面的全套形态），详见 docker-compose.yml 内注释。

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

### 首次使用（空库第一次跑）

1. **admin 账号**：空库首次启动时按 `.env` 的 `HIFY_ADMIN_USERNAME` / `HIFY_ADMIN_PASSWORD`
   自动创建一次（不配则跳过并告警）；样例见 `deploy/.env.example`。之后的账号由 admin 在后台创建。
2. **配置模型供应商**：用 admin 登录 → 管理后台「模型供应商」→ 新建供应商（OpenAI 兼容 /
   Anthropic 协议，填 Base URL + API Key）→ 添加模型 → 「试连接」验证连通。**不配模型，对话和
   向量化都无法使用**，这是第一件事。
3. **建应用**：回到工作台新建对话应用或工作流应用，绑定模型即可开聊/编排。

### 4. 生产形态（4 容器全套，deployment.md 落地）

日常开发用不到；验收部署形态或演练上线时用。

```bash
# 前置（仅首次）：自签证书 + 敏感配置
bash deploy/nginx/gen-self-signed-cert.sh
cp deploy/.env.example deploy/.env        # 生产务必改掉 dev-only 默认值

make app-up      # = docker compose --profile app up -d --build（4 容器）
make app-ps      # 等 4 个容器全部 healthy
# 浏览器访问 https://localhost（自签证书首次需手动信任）
make app-down    # 停全套（数据卷保留）
```

> 两种形态别同时跑：全套形态运行时再 `make start`，会有两个 server 实例连同一个库
> （启动自愈互相重置对方的运行中记录），start.sh 检测到会提醒。
> 本地若存在 `docker-compose.override.yml`（联调用，已 gitignore），compose 会自动叠加它
> ——验证纯生产隔离形态（沙箱零出网）时先把它临时改名。

要点：只有 nginx 对外发布 80/443（postgres 的 127.0.0.1:5432 是留给宿主机开发的，
生产 VM 上删掉）；证书与 deploy/.env 均不入库；SSE/上传体积等反代配置见 deploy/nginx/nginx.conf。

**重启口径**（容器形态下最常踩的坑）：

| 场景 | 命令 | 为什么 |
|---|---|---|
| 没改任何东西，纯重启 | `docker compose --profile app restart` | 原地重启，秒级 |
| 改了代码 / nginx.conf | `docker compose --profile app up -d --build` | 代码烤在镜像里，光 restart 跑的还是旧镜像 |
| 改了 deploy/.env | `--profile app down` 再 `up -d` | 环境变量在容器创建时注入，restart 不重读 |
| 换证书 | `--profile app restart nginx` | 证书是 volume 挂载，不进镜像 |

⚠️ `down -v` 会连数据卷一起删（数据库清空），日常永远不要带 `-v`。

---

## 常用命令（Makefile）

| 命令 | 形态 | 作用 |
|---|---|---|
| `make`（或 `make help`） | — | 列出所有命令 |
| `make start` | 宿主机开发 | 检查 DB → 构建并起后端 → 健康检查 → 起前端（若检测到容器版 server 在跑会提醒，防两个实例连同一库互相干扰） |
| `make stop` | 宿主机开发 | 优雅停止前后端（SIGTERM，超时 SIGKILL） |
| `make restart` | 宿主机开发 | 先 stop 再 start |
| `make app-up` | 全套容器 | 构建镜像并拉起 4 容器（= `docker compose --profile app up -d --build`） |
| `make app-down` | 全套容器 | 停止全套（数据卷保留） |
| `make app-ps` | 全套容器 | 查看 4 容器状态 |
| `make build` | — | 构建后端 jar + 前端 dist |
| `make clean` | — | 清理构建产物（`server/target`、`web/dist`、`dist`） |
| `make package` | — | 打包可分发 `dist/hify-<版本>.tar.gz`（jar + 前端产物 + 部署配置**白名单**——真实 `.env` 与 TLS 私钥绝不进包） |

`start.sh` / `stop.sh` 也可单独直接运行；常用参数可用环境变量覆盖，例如：

```bash
HEALTH_TIMEOUT=60 ./start.sh     # 健康检查最长等 60s
STOP_TIMEOUT=5 ./stop.sh         # 优雅停止最多等 5s 再强杀
```

脚本与 Makefile 内含逐行中文注释，可直接当作参考。

---

## 测试

```bash
cd server && mvn verify      # 后端：单测 + 集成测（Testcontainers，需 Docker）+ 模块边界（ArchUnit/Modulith）
pnpm -C web test             # 前端：vitest 单测/组件测
pnpm -C web typecheck        # 前端：vue-tsc 类型检查
pnpm -C web e2e              # E2E：Playwright + 假 LLM 桩（详见 docs/e2e-guide.md）
```

测试怎么算写得好，见 `docs/architecture/testing-standards.md`。

---

## 仓库布局

```
hify/
├── server/              # Spring Boot 模块化单体（后端，含 Dockerfile）
├── web/                 # Vue 3 前端（独立 pnpm 工程；构建产物进 nginx 镜像）
├── sandbox/             # Python 代码执行沙箱（独立容器，唯一跑不可信代码的地方）
├── mcp-demo/            # 自建 MCP server 练手工程（TypeScript，端口 3100）
├── deploy/              # nginx 镜像与配置、自签证书脚本、.env.example（真实 .env 不入库）
├── scripts/             # 手跑工具脚本（检索阈值评估集等），不进 CI
├── docker-compose.yml   # 双形态编排：默认 postgres+sandbox；--profile app 全套 4 容器
├── start.sh / stop.sh   # 宿主机开发形态的启停脚本
├── Makefile             # 统一命令入口
└── docs/                # 文档（导航见 docs/README.md）：architecture 规范、
                         # self-check.md 自检手册、postman 接口验收集合、superpowers 历史设计
```

更多约定见仓库根的 `CLAUDE.md` 与 [`docs/README.md`](docs/README.md) 的导航；
改完想自己验证有没有生效，见 [`docs/self-check.md`](docs/self-check.md)。
