# Workflow W-Code：代码执行节点 + Python 沙箱（设计）

> 2026-07-13 brainstorm 定稿。画布 C1-C3 已完结，工作流补齐第 6 类节点。
> 两块交付：**独立 Python 沙箱容器**（deployment.md 规划的第 2 个容器，唯一不可信代码场所）
> + workflow **代码执行节点**。基于 W1 引擎与既有 NodeExecutor 抽象，引擎主流程零改动。
> 后端 + 画布一轮做完，沙箱容器同时加进本地 docker-compose 开发栈，验收闭环。

## 0. 安全模型（先立地基）

核心原则（deployment.md 第 2 节原文）：**不可信代码绝不在 server 进程/容器里执行**。
沙箱的安全边界是**容器本身**，不是 Python 语言层——试图在 Python 进程内做语言级隔离
（禁 import、改 builtins）是拦不住的，靠得住的只有：

1. 独立容器，`read_only` + `cap_drop: ALL` + `no-new-privileges`
2. 网络隔离：独立 `sandbox-net`，连不到 postgres，无外网路由（业务确需 HTTP 由 server 的 HTTP 节点代劳）
3. 容器级 CPU/内存硬限制 + 沙箱子进程执行超时（双保险）
4. server 侧并发信号量控制提交并发（防 Agent 级联把沙箱打爆）

所以本轮工作量约一半在 Docker 编排与沙箱服务上，代码执行器本身很薄。

## 1. 范围与目标

**做**：
- **沙箱容器**：`python:3.12-slim` + 零依赖单文件 `sandbox_server.py`（`http.server` 标准库）；
  加进 `docker-compose.yml` 本地栈（当前只有 postgres 一个服务），compose 加固项落地
- **`SandboxClient`**（`infra/outbound` 新增，与 `OutboundHttpClient` 并列）：server→沙箱的
  **内网** HTTP 客户端，JDK HttpClient 封装，双超时；tool 模块将来做 Agent 代码工具可直接复用
- **`NodeType` 新增 `CODE("code")` + `CodeNodeExecutor`**（纯加 executor，引擎零改动，同 W2/W3b 模式）
- **`GraphValidator`** 新增 code 节点字段校验；配置外化 `hify.sandbox.*`
- **前端**：`NodePalette` 加"代码执行"节点 + `CodeNodeForm.vue`（输入映射区 + textarea 代码框）
- 测试：后端三层 + 沙箱 unittest；前端 vitest 四件套；容器联调归验收

**不做**（YAGNI，均有预留或明确理由）：
- **第三方 Python 库**（numpy/pandas 等）：沙箱仅标准库。镜像最小、攻击面最小；
  数据清洗/格式转换类主流场景标准库够用。二期有真实需求再加（改 Dockerfile 即可）
- **类型化变量传参**：映射进 `main()` 的参数值全是字符串（见 §5 决策 3），与现有 HTTP/LLM 节点一致
- **代码编辑器语法高亮**：用 `el-input` textarea + 等宽字体，零新依赖（见 §5 决策 4）。
  内部工具写十几行胶水代码够用；不够再上 CodeMirror，升级成本不高
- **Flyway 迁移**：`workflow_node_run.node_type` 是自由 text 列（V21 只 `status` 带 check，
  node_type 无约束），新增节点类型无需改库。**写实现计划时须复核 V21 确认这一点**
- **沙箱出网**：沙箱容器禁出外网（deployment.md §5），本轮不给沙箱任何出站能力

## 2. 沙箱容器与协议

### 2.1 协议

server → `POST http://sandbox:8000/run`，请求体：
```json
{"code": "def main(text):\n    return {\"count\": len(text.split())}",
 "inputs": {"text": "hello world"},
 "timeoutMs": 5000}
```
沙箱响应（HTTP 200 恒定，成败看 body 的 `ok`）：
```json
{"ok": true,  "outputs": {"count": 2}}
{"ok": false, "error": "执行超时（5000ms）"}
{"ok": false, "error": "main 必须返回 dict，实际 <class 'int'>"}
{"ok": false, "error": "Traceback ... NameError: name 'foo' is not defined"}
```

### 2.2 沙箱执行方式

`sandbox_server.py`（`http.server.BaseHTTPRequestHandler`，单文件，只依赖标准库）：
1. 收到 `/run`，把用户 `code` 写进 `tmpfs` 临时文件
2. 用 **subprocess** 起子进程 `python -I -c <runner>`（isolated 模式，不读环境/site）：
   runner 里 `import` 用户模块 → `main(**inputs)` → `json.dumps(返回值)` 打到 stdout
3. 子进程 `preexec_fn` 里设 `resource` 的 `RLIMIT_CPU` / `RLIMIT_AS`（CPU 秒 + 地址空间硬限）
4. `subprocess.run(timeout=timeoutMs)` 超时 → 强杀子进程 → 返回 `ok:false`
5. 校验：`main` 必须存在、返回值必须是 dict、dict 必须 JSON 可序列化，否则 `ok:false`

**为什么子进程**：单次执行崩溃/死循环/OOM 只炸子进程，HTTP 服务本体活着继续接单。
沙箱自身不做鉴权（内网 `sandbox-net` 内不可达外部），server 是唯一调用方。

### 2.3 docker-compose 加固

```yaml
sandbox:
  build: ./sandbox          # 或独立 Dockerfile；单文件服务
  container_name: hify-sandbox
  read_only: true
  cap_drop: [ALL]
  security_opt: [no-new-privileges:true]
  networks: [sandbox-net]    # 独立网：连不到 postgres，无外网路由
  mem_limit: 1g
  cpus: "1.0"
  tmpfs: /tmp                # read_only 下给临时文件一块可写内存盘
  # 不发布宿主机端口（deployment.md §5：postgres/sandbox 均不对外）
```
本地开发栈：`docker-compose.yml` 新增 `sandbox` 服务 + `sandbox-net` 网络。
server 容器一期尚未进 compose（当前 server 跑在宿主机），本地联调时 sandbox 需临时映射端口
或 server 走 host 网络——**实现计划里明确本地连通方式**（不污染生产加固形态）。

## 3. 节点定义

```json
{"id": "code_1", "type": "code", "data": {
  "code": "def main(text, threshold):\n    n = len(text.split())\n    return {\"count\": n, \"long\": n > int(threshold)}",
  "inputs": {
    "text": "{{start.question}}",
    "threshold": "{{start.limit}}"
  }
}}
```

| 字段 | 约束 | 校验时机 |
|---|---|---|
| `code` | 必填非空字符串（Python 源，须含 `def main`） | 保存时查必填非空；`def main` 存在性运行时由沙箱兜底报错 |
| `inputs` | 可选，`Map<String,String>`（形参名 → `{{nodeId.field}}` 模板） | 保存时查类型（若存在须为 map）；模板引用合法性走既有拓扑序引用校验 |

**输出**：用户 `main()` 返回的 dict **原样**作为节点 outputs，下游用 `{{code_1.count}}` 引用。
key 由用户代码决定（不做静态声明——保存时无法静态求出返回 key，这是与其他节点的差异）。

**inputs 快照**：`{形参名: 渲染后的字符串值}`（落 `node_run.inputs` 供排障）。代码本身不落 inputs
（它在 graph 的 `node.data.code` 里已持久化，不重复存）。

## 4. 执行流程（CodeNodeExecutor）

```
execute(node, ctx):
  code = node.data.code
  rawInputs = node.data.inputs            // {形参名: 模板}
  args = {}
  for (name, template) in rawInputs:
      args[name] = ctx.render(template)   // 字符串替换（见决策 3）
  acquire(semaphore)                       // 并发闸，hify.sandbox.max-concurrency
  try:
      resp = sandboxClient.run(code, args, timeoutMs)   // 内网 HTTP，读超时 = 沙箱超时 + 余量
  finally:
      release(semaphore)
  if !resp.ok:
      throw NodeExecutionException(inputs=args, cause=BizException(错误码, resp.error))
  return NodeResult(inputs=args, outputs=resp.outputs)
```

**双超时**：`SandboxClient` HTTP 读超时（server 侧）设为**沙箱子进程 timeout + 余量**，
保证正常情况下沙箱先超时并返回结构化 `ok:false`，HTTP 读超时只兜网络异常兜底。两个值都外化配置。

**错误码**：沿用 workflow 段，写实现计划时**现场重读 api-standards.md 错误码段**核对后固定，
不得凭记忆新开段。沙箱返回的 `error` 文案作为节点失败原因落 `node_run`。

**引擎零改动**：`CodeNodeExecutor implements NodeExecutor`，Spring 自动收集进 `WorkflowEngine`
的 executors map（构造器注入 `List<NodeExecutor>`），与 W2/W3b 加节点方式完全一致。

## 5. 拍板决策

1. **输入输出契约 = main 函数返回 dict**：`def main(形参...): return {...}`。节点配置把上游变量
   映射到形参，返回 dict 的 key 即下游可引用的输出变量。Dify 同款，结构化、好校验。
2. **仅标准库**：沙箱不预装 numpy/pandas。攻击面/镜像体积最小，主流场景够用；二期按需加。
3. **参数按字符串传**：`ctx.render()` 是字符串替换，映射进 `main()` 的值全是 `str`（上游返回
   `{"count": 42}`，下游 code 拿到 `"42"`）。与现有 HTTP/LLM 节点一致；用户在代码里 `int()` 转换。
   **不**为 code 节点单独搞类型化变量系统（另一个大工程，YAGNI）。文档写明此限制。
4. **代码框用 el-input textarea**：零新前端依赖，等宽字体。不够用再上 CodeMirror 6。
5. **沙箱内 HTTP 服务用 Python 标准库单文件**：`http.server` + `subprocess`，镜像里连 pip 都不跑，
   攻击面与维护面最小；内网低并发场景性能足够。不引 FastAPI/uvicorn。
6. **沙箱进本地 compose**：否则验收时代码节点跑不起来只能纯 mock，体验不闭环。

## 6. 配置项（application.yml，`hify.sandbox.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `hify.sandbox.base-url` | `http://sandbox:8000` | 沙箱内网地址，走 `.env` 可覆盖 |
| `hify.sandbox.connect-timeout-ms` | 1000 | 连接沙箱超时 |
| `hify.sandbox.read-timeout-ms` | 7000 | server 侧读超时（= 沙箱 exec 超时 + 余量） |
| `hify.sandbox.exec-timeout-ms` | 5000 | 沙箱子进程执行超时（随请求下发，沙箱据此 kill） |
| `hify.sandbox.max-concurrency` | 8 | server 侧提交并发信号量 |
| `hify.sandbox.max-output-bytes` | 64KB | 沙箱返回 outputs 序列化上限（防大响应撑爆 node_run jsonb） |

沙箱侧的 CPU/内存 rlimit 由沙箱镜像/服务自身参数控制（与 compose `mem_limit`/`cpus` 双保险），
不经 server 下发（避免把资源策略暴露给调用方）。

## 7. 测试

**后端**：
- `CodeNodeExecutor` 单测（mock `SandboxClient`）：正常输出映射、变量渲染进 args、
  `ok:false` → NodeExecutionException 且 inputs 快照带渲染值、并发信号量释放（含异常路径）
- `GraphValidator` code 节点校验：`code` 必填非空、`inputs` 非 map 报错、引用拓扑序校验
- 引擎集成：一条含 code 节点的图端到端（mock SandboxClient），验证 outputs 进 RunContext 供下游引用
- **沙箱 `sandbox_server.py`**：Python 自带 `unittest`（正常 / 超时 / return 非 dict /
  代码抛异常 / main 缺失 / 不可序列化返回值）

**前端**（vitest + TDD，先写失败测试，放 `__tests__/`）：
- `CodeNodeForm`：输入映射行增删、代码框双向绑定、必填校验、变量引用面板联动

**验收**（真跑容器，不进 CI）：`docker compose up` 起 sandbox → 画布搭
start→code→end 图 → 运行 → 看输出映射正确；跑一个死循环验证超时；跑 `import socket` 连外网
验证网络隔离生效。

## 8. 已知边界（一期接受，二期收紧）

- **无第三方库**：纯标准库，复杂数据处理受限。触发条件=真实场景需要 numpy/pandas 时加。
- **参数字符串化**：见决策 3。二期若 code 节点成为数据管道主力，再评估类型化变量。
- **沙箱语言级隔离弱**：不禁 `import os` 等——安全靠容器边界不靠语言层（见 §0）。前提是
  容器加固到位（网络隔离 + 资源限 + read_only）。若 code 节点对不可信用户开放（一期只团队内部），
  二期需上 gVisor/seccomp（deployment.md 已提 K8s 阶段加 gVisor runtimeClass）。
- **本地 compose 无 server 容器**：server 一期跑宿主机，sandbox 联调连通方式在实现计划明确，
  不改动生产加固形态。
```