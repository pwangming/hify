# Postman 接口验收集合

按轮次积累的**手动验收**集合（原 `docs/verify/` 已并入本目录）。
自动化验证看 `docs/self-check.md`（自检手册）与 `docs/e2e-guide.md`（Playwright E2E）；
这里的集合用于**手工点一遍接口**，尤其是 E2E 覆盖不到的失败路径与真实 LLM 调用。

## 通用用法

1. Postman → Import → 选中要跑的 `*.postman_collection.json`。
2. 打开 Collection Variables，至少改这几项：
   - `baseUrl`：默认 `http://localhost:8080`（容器化部署走 nginx 时改成 `http://localhost`）。
   - 账号密码（`adminPassword` / `password` 等）：填 `deploy/.env` 里的 `HIFY_ADMIN_PASSWORD`。
3. **按编号顺序执行**。前面的请求会把 `token` / `providerId` / `appId` 等写回集合变量，后面的请求直接复用。
4. 约定：Result 信封 `{code,message,data,traceId}`，成功 `code=200`；所有 id 一律是字符串。

> ⚠️ 验收前**必须重启 hify-server**——旧进程不会加载新代码（W2 轮实测踩过）。

## 集合清单

| 文件 | 轮次 | 覆盖内容 |
|---|---|---|
| `admin-user-management` | 地基 5 | `/api/v1/admin/identity/users` 7 接口 + 403/401/400/409/11003 负向 |
| `hify-provider` | provider 第 1 轮 | Admin 供应商 CRUD + 启用/禁用 |
| `hify-provider-c1` | provider C1 | 模型校验 Facade + 成员侧模型列表 + 禁用供应商连带失效（16002） |
| `hify-provider-c2` | provider C2 | `getChatClient` + 四层韧性 + admin 测试连通（12002/12003/10004）⚠️ 真实调用 LLM，需填可用凭证 |
| `hify-app` | app 第 1 轮 | 应用元数据 CRUD + 团队共享权限 + 启停（越权 403/10004） |
| `conversation-multi-turn` | conversation ④ | 成员族对话接口：发消息、拉历史、会话列表（多轮记忆） |
| `usage-quota` | usage ⑥ | 用量落库（daily_usage/llm_call_log）+ 入口配额拦截（14001/429） |
| `knowledge-k1` | knowledge K1 | 知识库 dataset CRUD 12 步；K2 的文档上传可在此基础上续加 form-data 请求 |
| `workflow-w1` | workflow W1 | 建应用 → 存草稿 → 同步触发 → 详情/历史 + 三条失败路径（18001/10001/模型不可用） |
| `workflow-w2` | workflow W2 | 知识检索节点 RAG 草稿的成功/失败路径 |
| `workflow-w3a` | workflow W3a | 条件分支命中/未命中两方向 + 文本进数字比较的坏草稿 |
| `workflow-w3b` | workflow W3b | HTTP 节点公网成功、404 分流、SSRF 拦截与脱敏 |

（文件名省略了统一后缀 `.postman_collection.json`。）
