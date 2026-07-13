# 代码执行节点 + Python 沙箱 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 workflow 引擎补上第 6 类节点「代码执行」，配套一个独立、网络隔离的 Python 沙箱容器执行不可信用户代码。

**Architecture:** 沙箱是独立容器（`python:3.12-slim` + 零依赖单文件 HTTP 服务），安全边界靠容器隔离（网络隔离 + 只读 + 资源硬限 + 执行超时），不靠 Python 语言层。server 通过内网 HTTP `SandboxClient` 提交 `{code, inputs}`、拿回 dict 输出。`CodeNodeExecutor` 复用既有 `NodeExecutor` 抽象，引擎主流程零改动（同 W2/W3b 加节点模式）。前端画布加节点类型 + 输入映射表单。

**Tech Stack:** Python 3.12 标准库（`http.server`/`subprocess`/`resource`）；Java 21 + Spring Boot（JDK `HttpClient` + Jackson）；Vue 3 + Element Plus + vitest。

## Global Constraints

- **仅标准库**：沙箱镜像不装 numpy/pandas 等第三方库。
- **安全靠容器不靠语言**：沙箱不做语言级隔离（不禁 import）；隔离靠 compose 加固（`read_only`/`cap_drop: ALL`/`no-new-privileges`/独立 `sandbox-net` 无外网、连不到 postgres/`mem_limit`/`cpus`）+ 子进程 CPU/内存 rlimit + 执行超时。
- **参数按字符串传**：`ctx.render()` 是字符串替换，映射进 `main()` 的参数值全是 `str`（与现有 HTTP/LLM 节点一致）；不为 code 节点单独搞类型化变量系统。
- **输出契约**：用户写 `def main(形参...): return {dict}`；返回 dict 的 key 即下游可 `{{codeId.key}}` 引用的输出变量。
- **外部调用必超时且外化**（CLAUDE.md）：沙箱调用连接/读取/执行三个超时全走 `hify.sandbox.*` 配置。
- **错误码**：`node_type` 是自由 text 列（V21 只 `status` 带 check），加节点类型**无需 Flyway 迁移**；沙箱不可达/超时复用通用段 `10008 DEPENDENCY_UNAVAILABLE`；用户代码运行失败用 workflow 段新码 `18002`（18001 已占 GRAPH_INVALID，18003 文档已占「已发布不可修改」）。
- **模块边界**：`SandboxClient`/`SandboxResult`/`SandboxProperties` 放 `infra.outbound`（tool 模块将来做 Agent 代码工具可复用）；infra 层禁止 import workflow 的 `WorkflowError`（沙箱级错误只用通用段）。
- **TDD**：每个可测单元先写失败测试再实现；前端测试放 `__tests__/`，用 vitest。

---

### Task 1: 沙箱服务（Python 单文件 + Dockerfile + 单测）

**Files:**
- Create: `sandbox/sandbox_server.py`
- Create: `sandbox/Dockerfile`
- Create: `sandbox/test_sandbox_server.py`

**Interfaces:**
- Produces: HTTP 服务 `POST /run`，请求 `{"code": str, "inputs": {str:str}, "timeoutMs": int}`，响应恒 HTTP 200，body `{"ok": true, "outputs": {...}}` 或 `{"ok": false, "error": str}`。`GET /health` 返回 200 `{"status":"ok"}`。
- Produces: 环境变量 `SANDBOX_PORT`(默认 8000)、`SANDBOX_MAX_OUTPUT_BYTES`(默认 65536)、`SANDBOX_CPU_SECONDS`(默认 5)、`SANDBOX_MEM_BYTES`(默认 268435456)。

- [ ] **Step 1: 写沙箱服务实现**

Create `sandbox/sandbox_server.py`:

```python
"""Hify 代码执行沙箱：零依赖单文件 HTTP 服务（标准库）。
唯一调用方是 server（内网 sandbox-net，不对外发布端口）；安全边界是容器，不是本进程。
每次 /run 起一个子进程执行用户代码：崩溃/死循环/超内存只炸子进程，本服务活着继续接单。"""
import json
import os
import resource
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("SANDBOX_PORT", "8000"))
MAX_OUTPUT_BYTES = int(os.environ.get("SANDBOX_MAX_OUTPUT_BYTES", "65536"))
CPU_SECONDS = int(os.environ.get("SANDBOX_CPU_SECONDS", "5"))
MEM_BYTES = int(os.environ.get("SANDBOX_MEM_BYTES", str(256 * 1024 * 1024)))

# 子进程里跑的 runner：加载用户模块 → main(**inputs) → 校验并打印 JSON。
# 一切异常都转成 {"ok": false, ...} 并 exit(0)，让父进程从 stdout 拿结构化结果。
RUNNER = r"""
import json, sys, importlib.util
code_path = sys.argv[1]
max_output = int(sys.argv[2])
spec = importlib.util.spec_from_file_location("user_code", code_path)
mod = importlib.util.module_from_spec(spec)
try:
    spec.loader.exec_module(mod)
except Exception as e:
    print(json.dumps({"ok": False, "error": f"代码加载失败：{type(e).__name__}: {e}"})); sys.exit(0)
if not hasattr(mod, "main") or not callable(mod.main):
    print(json.dumps({"ok": False, "error": "代码必须定义 main 函数"})); sys.exit(0)
try:
    inputs = json.loads(sys.stdin.read() or "{}")
except Exception as e:
    print(json.dumps({"ok": False, "error": f"输入解析失败：{e}"})); sys.exit(0)
try:
    result = mod.main(**inputs)
except Exception as e:
    print(json.dumps({"ok": False, "error": f"执行出错：{type(e).__name__}: {e}"})); sys.exit(0)
if not isinstance(result, dict):
    print(json.dumps({"ok": False, "error": f"main 必须返回 dict，实际 {type(result).__name__}"})); sys.exit(0)
try:
    payload = json.dumps({"ok": True, "outputs": result}, ensure_ascii=False)
except (TypeError, ValueError) as e:
    print(json.dumps({"ok": False, "error": f"返回值无法序列化为 JSON：{e}"})); sys.exit(0)
if len(payload.encode("utf-8")) > max_output:
    print(json.dumps({"ok": False, "error": f"输出超过上限 {max_output} 字节"})); sys.exit(0)
print(payload)
"""


def _apply_limits():
    """子进程 preexec：CPU 秒 + 地址空间硬限制（与容器 cpus/mem_limit 双保险）。"""
    resource.setrlimit(resource.RLIMIT_CPU, (CPU_SECONDS, CPU_SECONDS))
    resource.setrlimit(resource.RLIMIT_AS, (MEM_BYTES, MEM_BYTES))


def run_code(code: str, inputs: dict, timeout_ms: int) -> dict:
    """把用户代码写进 tmpfs，用隔离子进程执行，超时强杀。返回 {"ok":..., ...}。"""
    code_path = f"/tmp/user_{os.getpid()}_{id(code)}.py"
    with open(code_path, "w", encoding="utf-8") as f:
        f.write(code)
    try:
        proc = subprocess.run(
            [sys.executable, "-I", "-c", RUNNER, code_path, str(MAX_OUTPUT_BYTES)],
            input=json.dumps(inputs),
            capture_output=True,
            text=True,
            timeout=max(timeout_ms, 1) / 1000.0,
            preexec_fn=_apply_limits,
        )
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": f"执行超时（{timeout_ms}ms）"}
    finally:
        try:
            os.remove(code_path)
        except OSError:
            pass
    out = proc.stdout.strip()
    if not out:
        # 子进程无 stdout：多半被 rlimit 杀（SIGXCPU/OOM）或段错误
        err = proc.stderr.strip() or "执行被终止（超时或超内存）"
        return {"ok": False, "error": err[:500]}
    try:
        return json.loads(out.splitlines()[-1])
    except json.JSONDecodeError:
        return {"ok": False, "error": "沙箱内部错误：子进程输出非法"}


class Handler(BaseHTTPRequestHandler):
    def _send(self, status: int, obj: dict):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "ok"})
        else:
            self._send(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if self.path != "/run":
            self._send(404, {"ok": False, "error": "not found"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        try:
            req = json.loads(self.rfile.read(length) or "{}")
        except json.JSONDecodeError:
            self._send(200, {"ok": False, "error": "请求体非法 JSON"})
            return
        code = req.get("code")
        if not isinstance(code, str) or code.strip() == "":
            self._send(200, {"ok": False, "error": "code 不能为空"})
            return
        inputs = req.get("inputs") or {}
        timeout_ms = int(req.get("timeoutMs", 5000))
        self._send(200, run_code(code, inputs, timeout_ms))

    def log_message(self, *args):
        pass  # 静音默认 access log


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"sandbox listening on :{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 写单测（先跑，验证会失败前先写完实现——本 Task 实现与测试同批交付）**

Create `sandbox/test_sandbox_server.py`:

```python
import unittest
from sandbox_server import run_code


class RunCodeTest(unittest.TestCase):
    def test_正常返回dict(self):
        r = run_code("def main(text):\n    return {'count': len(text.split())}",
                     {"text": "hello world foo"}, 5000)
        self.assertTrue(r["ok"])
        self.assertEqual(r["outputs"], {"count": 3})

    def test_无入参(self):
        r = run_code("def main():\n    return {'v': 42}", {}, 5000)
        self.assertEqual(r["outputs"], {"v": 42})

    def test_缺main函数(self):
        r = run_code("x = 1", {}, 5000)
        self.assertFalse(r["ok"])
        self.assertIn("main", r["error"])

    def test_返回非dict(self):
        r = run_code("def main():\n    return 5", {}, 5000)
        self.assertFalse(r["ok"])
        self.assertIn("dict", r["error"])

    def test_代码抛异常(self):
        r = run_code("def main():\n    return {'v': 1/0}", {}, 5000)
        self.assertFalse(r["ok"])
        self.assertIn("ZeroDivisionError", r["error"])

    def test_超时被杀(self):
        r = run_code("def main():\n    while True:\n        pass", {}, 500)
        self.assertFalse(r["ok"])
        self.assertIn("超时", r["error"])

    def test_返回不可序列化(self):
        r = run_code("def main():\n    return {'f': lambda x: x}", {}, 5000)
        self.assertFalse(r["ok"])
        self.assertIn("序列化", r["error"])

    def test_加载语法错误(self):
        r = run_code("def main( :\n    pass", {}, 5000)
        self.assertFalse(r["ok"])
        self.assertIn("加载失败", r["error"])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 3: 跑单测确认全绿**

Run: `cd sandbox && python3 -m unittest test_sandbox_server -v`
Expected: 8 tests OK（`test_超时被杀` 用死循环，rlimit CPU 与 timeout 双兜底，约 0.5s 内返回）。

- [ ] **Step 4: 写 Dockerfile**

Create `sandbox/Dockerfile`:

```dockerfile
# Hify 代码执行沙箱：最小镜像，仅标准库，无 pip 安装。
FROM python:3.12-slim
WORKDIR /app
COPY sandbox_server.py .
# 以非 root 运行（compose 再叠 read_only/cap_drop/no-new-privileges）
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin sandbox
USER sandbox
EXPOSE 8000
CMD ["python", "-u", "sandbox_server.py"]
```

- [ ] **Step 5: 提交**

```bash
git add sandbox/
git commit -m "feat(sandbox): Python 代码执行沙箱服务（标准库单文件+子进程隔离+单测）"
```

---

### Task 2: docker-compose 接入沙箱容器

**Files:**
- Modify: `docker-compose.yml`

**Interfaces:**
- Produces: 服务名 `sandbox`，内网可达 `http://sandbox:8000`；`sandbox-net` 网络（无外网路由）。

- [ ] **Step 1: 加 sandbox 服务与网络**

在 `docker-compose.yml` 的 `services:` 下新增 `sandbox` 服务，并在文件末尾 `volumes:` 之前加 `networks:` 段。sandbox 加固照 deployment.md §2/§5：

```yaml
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
    # 本地开发（server 跑在宿主机，尚未进 compose）临时把沙箱端口映射到宿主机联调；
    # server 进 compose 后删掉此 ports、仅留 sandbox-net 内网可达（deployment.md §5：沙箱不对外发布端口）。
    ports:
      - "127.0.0.1:8000:8000"
```

在文件末尾新增网络定义（与既有 `volumes:` 平级）：

```yaml
networks:
  sandbox-net:
    driver: bridge
```

- [ ] **Step 2: 验证 compose 配置合法并起容器**

Run: `docker compose config >/dev/null && docker compose up -d --build sandbox`
Expected: 无报错；`docker compose ps` 里 sandbox 状态 `healthy`。

- [ ] **Step 3: 冒烟测试沙箱**

Run: `curl -s -X POST http://127.0.0.1:8000/run -d '{"code":"def main(x):\n    return {\"y\": int(x)+1}","inputs":{"x":"41"},"timeoutMs":5000}'`
Expected: `{"ok": true, "outputs": {"y": 42}}`

- [ ] **Step 4: 提交**

```bash
git add docker-compose.yml
git commit -m "chore(deploy): docker-compose 接入沙箱容器（加固+sandbox-net+健康检查）"
```

---

### Task 3: SandboxClient（infra 出站，内网调用沙箱）

**Files:**
- Create: `server/src/main/java/com/hify/infra/outbound/SandboxProperties.java`
- Create: `server/src/main/java/com/hify/infra/outbound/SandboxResult.java`
- Create: `server/src/main/java/com/hify/infra/outbound/SandboxClient.java`
- Modify: `server/src/main/resources/application.yml`（`hify:` 下新增 `sandbox:` 段）
- Test: `server/src/test/java/com/hify/infra/outbound/SandboxClientTest.java`

**Interfaces:**
- Produces: `SandboxResult(boolean ok, Map<String,Object> outputs, String error)` — record。
- Produces: `SandboxClient.run(String code, Map<String,String> inputs)` → `SandboxResult`。网络失败/超时/响应超限抛 `BizException(CommonError.DEPENDENCY_UNAVAILABLE, ...)`；沙箱正常应答（含 `ok:false`）如实返回。并发受信号量 `hify.sandbox.max-concurrency` 限制。
- Consumes: `com.hify.common.exception.BizException`、`CommonError.DEPENDENCY_UNAVAILABLE`、Jackson `ObjectMapper`（Spring bean）。

- [ ] **Step 1: 写 SandboxResult**

```java
package com.hify.infra.outbound;

import java.util.Map;

/** 沙箱执行结果：ok=true 时 outputs 为用户 main 返回的 dict；ok=false 时 error 为失败原因。 */
public record SandboxResult(boolean ok, Map<String, Object> outputs, String error) {
}
```

- [ ] **Step 2: 写 SandboxProperties**

```java
package com.hify.infra.outbound;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 沙箱调用配置（CLAUDE.md：外部调用必须有超时且外化）。workflow 代码节点与将来 tool 的 Agent 代码工具共用。 */
@Component
@ConfigurationProperties(prefix = "hify.sandbox")
public class SandboxProperties {

    /** 沙箱内网地址。 */
    private String baseUrl = "http://sandbox:8000";
    /** 连接沙箱超时（毫秒）。 */
    private int connectTimeoutMs = 1000;
    /** server 侧读超时（毫秒），须 = 沙箱执行超时 + 余量，保证沙箱先超时返回结构化错误。 */
    private int readTimeoutMs = 7000;
    /** 沙箱子进程执行超时（毫秒），随请求下发。 */
    private int execTimeoutMs = 5000;
    /** server 侧提交并发上限（信号量），防 Agent 级联把单沙箱容器打爆。 */
    private int maxConcurrency = 8;
    /** 沙箱响应体大小上限（字节），超出视为异常——防大响应撑爆 node_run jsonb 与内存。 */
    private int maxOutputBytes = 65536;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getExecTimeoutMs() { return execTimeoutMs; }
    public void setExecTimeoutMs(int execTimeoutMs) { this.execTimeoutMs = execTimeoutMs; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getMaxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(int maxOutputBytes) { this.maxOutputBytes = maxOutputBytes; }
}
```

- [ ] **Step 3: 写 SandboxClient**

```java
package com.hify.infra.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱内网调用（server→sandbox）。与 OutboundHttpClient 分开：目标是可信内网服务，
 * 不走 SSRF 校验（SsrfValidator 会把容器服务名当内网拦掉）。双超时 + 并发信号量。
 * 沙箱正常应答（含业务失败 ok:false）如实返回；只有网络/超时/响应超限/协议异常才抛 BizException。
 */
@Component
public class SandboxClient {

    private final SandboxProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final Semaphore semaphore;

    public SandboxClient(SandboxProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
        this.semaphore = new Semaphore(props.getMaxConcurrency());
    }

    /** 提交用户代码执行。inputs 值均为字符串（上游变量已渲染）。 */
    public SandboxResult run(String code, Map<String, String> inputs) {
        String reqBody;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", code);
            payload.put("inputs", inputs);
            payload.put("timeoutMs", props.getExecTimeoutMs());
            reqBody = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new BizException(CommonError.INTERNAL_ERROR, "沙箱请求序列化失败");
        }

        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(props.getReadTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "沙箱调用被中断");
        }
        if (!acquired) {
            throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "沙箱繁忙，请稍后再试");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(props.getBaseUrl() + "/run"))
                    .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.body().length > props.getMaxOutputBytes()) {
                throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "沙箱返回超出大小上限");
            }
            return objectMapper.readValue(resp.body(), SandboxResult.class);
        } catch (IOException e) {
            throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "沙箱调用失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "沙箱调用被中断");
        } finally {
            semaphore.release();
        }
    }
}
```

- [ ] **Step 4: 加 application.yml 配置**

在 `server/src/main/resources/application.yml` 的 `hify:` 段下（与 `knowledge:`/`provider:` 平级）新增：

```yaml
  sandbox:
    # 代码执行沙箱内网地址（生产走 .env 覆盖；本地联调临时指向映射端口）。
    base-url: ${HIFY_SANDBOX_BASE_URL:http://sandbox:8000}
    # 连接沙箱超时（毫秒）。
    connect-timeout-ms: ${HIFY_SANDBOX_CONNECT_TIMEOUT_MS:1000}
    # server 侧读超时：须 = exec-timeout-ms + 余量，保证沙箱先超时返回结构化错误。
    read-timeout-ms: ${HIFY_SANDBOX_READ_TIMEOUT_MS:7000}
    # 沙箱子进程执行超时（毫秒），随请求下发。
    exec-timeout-ms: ${HIFY_SANDBOX_EXEC_TIMEOUT_MS:5000}
    # server 侧提交并发上限（信号量），防 Agent 级联打爆单沙箱容器。
    max-concurrency: ${HIFY_SANDBOX_MAX_CONCURRENCY:8}
    # 沙箱响应体大小上限（字节），超出视为异常。
    max-output-bytes: ${HIFY_SANDBOX_MAX_OUTPUT_BYTES:65536}
```

- [ ] **Step 5: 写 SandboxClientTest（HttpServer 桩，照 OutboundHttpClientTest 模式）**

Create `server/src/test/java/com/hify/infra/outbound/SandboxClientTest.java`:

```java
package com.hify.infra.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxClientTest {

    private static HttpServer server;
    private static SandboxClient client;
    private static SandboxProperties props;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"slow\"")) {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                respond(ex, 200, "{\"ok\":true,\"outputs\":{}}");
            } else if (body.contains("\"boom\"")) {
                respond(ex, 200, "{\"ok\":false,\"error\":\"执行出错：NameError: x\"}");
            } else if (body.contains("\"big\"")) {
                respond(ex, 200, "{\"ok\":true,\"outputs\":{\"v\":\"" + "x".repeat(2000) + "\"}}");
            } else {
                respond(ex, 200, "{\"ok\":true,\"outputs\":{\"count\":3}}");
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        props = new SandboxProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setReadTimeoutMs(1000);       // /slow 睡 3s 必超时
        props.setMaxOutputBytes(500);       // big 响应 >500 字节必判超限
        client = new SandboxClient(props, new ObjectMapper());
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void 正常执行_返回outputs() {
        SandboxResult r = client.run("def main(text): return {'count': 3}", Map.of("text", "a b c"));
        assertTrue(r.ok());
        assertEquals(3, r.outputs().get("count"));
    }

    @Test
    void 用户代码失败_ok为false原样返回() {
        SandboxResult r = client.run("boom", Map.of());
        assertFalse(r.ok());
        assertTrue(r.error().contains("NameError"));
    }

    @Test
    void 读超时_抛依赖不可用() {
        BizException ex = assertThrows(BizException.class,
                () -> client.run("slow", Map.of()));
        assertEquals(10008, ex.errorCode().code());
    }

    @Test
    void 响应超上限_抛依赖不可用() {
        BizException ex = assertThrows(BizException.class,
                () -> client.run("big", Map.of()));
        assertEquals(10008, ex.errorCode().code());
    }
}
```

- [ ] **Step 6: 跑测试确认全绿**

Run: `cd server && ./mvnw -q test -Dtest=SandboxClientTest`
Expected: 4 tests 通过（注意 `-q` 会静音，判定看 exit code=0 与 surefire 报告，勿 grep BUILD SUCCESS）。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/hify/infra/outbound/Sandbox*.java \
        server/src/test/java/com/hify/infra/outbound/SandboxClientTest.java \
        server/src/main/resources/application.yml
git commit -m "feat(infra): SandboxClient 内网沙箱客户端（双超时+并发信号量+响应超限防护）"
```

---

### Task 4: NodeType.CODE + WorkflowError + CodeNodeExecutor

**Files:**
- Modify: `server/src/main/java/com/hify/workflow/constant/NodeType.java`
- Modify: `server/src/main/java/com/hify/workflow/constant/WorkflowError.java`
- Create: `server/src/main/java/com/hify/workflow/service/engine/CodeNodeExecutor.java`
- Test: `server/src/test/java/com/hify/workflow/service/engine/CodeNodeExecutorTest.java`

**Interfaces:**
- Consumes: `SandboxClient.run(String, Map<String,String>)` → `SandboxResult`（Task 3）；`RunContext.render`、`NodeResult`、`NodeExecutionException`、`GraphNode`。
- Produces: `CodeNodeExecutor implements NodeExecutor`，`type()` 返回 `"code"`；Spring 自动收集进 `WorkflowEngine` executors map（引擎零改动）。
- Produces: `NodeType.CODE`（value `"code"`）；`WorkflowError.CODE_EXECUTION_FAILED`（18002）。

- [ ] **Step 1: NodeType 加 CODE**

修改 `NodeType.java`，在 `HTTP("http"),` 与 `END("end");` 之间插入一行：

```java
    HTTP("http"),
    CODE("code"),
    END("end");
```

- [ ] **Step 2: WorkflowError 加 18002**

修改 `WorkflowError.java`，把 `GRAPH_INVALID` 那一行的分号改为逗号并追加新枚举值：

```java
    /** 图结构非法（环/断连/缺 start/end/未知类型/变量引用越界等，message 带具体原因）。 */
    GRAPH_INVALID(18001, HttpStatus.BAD_REQUEST, "工作流图结构非法"),

    /** 代码节点运行失败（用户代码抛异常/返回非 dict/沙箱执行超时等，message 带沙箱返回的原因）。 */
    CODE_EXECUTION_FAILED(18002, HttpStatus.BAD_REQUEST, "代码节点执行失败");
```

- [ ] **Step 3: 写失败测试**

Create `server/src/test/java/com/hify/workflow/service/engine/CodeNodeExecutorTest.java`:

```java
package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.infra.outbound.SandboxClient;
import com.hify.infra.outbound.SandboxResult;
import com.hify.workflow.dto.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeNodeExecutorTest {

    private final SandboxClient sandbox = mock(SandboxClient.class);
    private final CodeNodeExecutor executor = new CodeNodeExecutor(sandbox);

    private GraphNode node(String code, Map<String, Object> inputs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("inputs", inputs);
        return new GraphNode("code_1", "code", data);
    }

    @Test
    void type为code() {
        assertEquals("code", executor.type());
    }

    @Test
    void 渲染上游变量后调沙箱_输出进结果() {
        RunContext ctx = new RunContext(1L, 2L);
        ctx.putOutput("start", Map.of("question", "hello world"));
        when(sandbox.run(eq("def main(text): return {'n': 1}"), any()))
                .thenReturn(new SandboxResult(true, Map.of("n", 2), null));

        NodeResult r = executor.execute(
                node("def main(text): return {'n': 1}", Map.of("text", "{{start.question}}")), ctx);

        assertEquals("hello world", r.inputs().get("text"));  // 渲染后的实参落 inputs 快照
        assertEquals(2, r.outputs().get("n"));
    }

    @Test
    void 沙箱返回失败_抛NodeExecutionException且cause为Biz18002() {
        RunContext ctx = new RunContext(1L, 2L);
        when(sandbox.run(any(), any()))
                .thenReturn(new SandboxResult(false, null, "执行出错：NameError: x"));

        NodeExecutionException ex = assertThrows(NodeExecutionException.class,
                () -> executor.execute(node("def main(): return {}", Map.of()), ctx));
        assertTrue(ex.getCause() instanceof BizException);
        assertEquals(18002, ((BizException) ex.getCause()).errorCode().code());
        assertTrue(ex.getCause().getMessage().contains("NameError"));
    }

    @Test
    void 沙箱调用抛异常_包成NodeExecutionException带inputs快照() {
        RunContext ctx = new RunContext(1L, 2L);
        ctx.putOutput("start", Map.of("q", "hi"));
        when(sandbox.run(any(), any()))
                .thenThrow(new BizException(com.hify.common.exception.CommonError.DEPENDENCY_UNAVAILABLE, "沙箱繁忙"));

        NodeExecutionException ex = assertThrows(NodeExecutionException.class,
                () -> executor.execute(node("def main(q): return {}", Map.of("q", "{{start.q}}")), ctx));
        assertEquals("hi", ex.inputs().get("q"));
    }
}
```

- [ ] **Step 4: 跑测试确认失败**

Run: `cd server && ./mvnw -q test -Dtest=CodeNodeExecutorTest`
Expected: 编译失败（`CodeNodeExecutor` 尚不存在）。

- [ ] **Step 5: 写 CodeNodeExecutor**

Create `server/src/main/java/com/hify/workflow/service/engine/CodeNodeExecutor.java`:

```java
package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.infra.outbound.SandboxClient;
import com.hify.infra.outbound.SandboxResult;
import com.hify.workflow.constant.NodeType;
import com.hify.workflow.constant.WorkflowError;
import com.hify.workflow.dto.GraphNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代码执行节点：渲染 inputs 映射（值均为字符串，见 spec 决策 3）→ 提交沙箱执行用户 main(**inputs)
 * → 沙箱返回的 dict 直接作节点输出，下游 {{codeId.key}} 引用。
 * 用户代码运行失败（沙箱 ok:false）＝节点失败（18002，文案取沙箱返回原因）；
 * 沙箱不可达/超时由 SandboxClient 抛 10008。变量引用缺失走 render 抛出（同其他节点）。
 * 渲染在 try 外：引用非法则原样抛 IllegalStateException（引擎按通用节点异常处理，同 HttpNodeExecutor）。
 */
@Component
public class CodeNodeExecutor implements NodeExecutor {

    private final SandboxClient sandbox;

    public CodeNodeExecutor(SandboxClient sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String type() {
        return NodeType.CODE.value();
    }

    @Override
    public NodeResult execute(GraphNode node, RunContext ctx) {
        String code = String.valueOf(node.data().get("code"));   // validator 保证非空
        Object rawInputs = node.data().get("inputs");

        Map<String, String> args = new LinkedHashMap<>();
        if (rawInputs instanceof Map<?, ?> map) {                // validator 保证是 map（若存在）
            map.forEach((k, v) -> args.put(String.valueOf(k), ctx.render(String.valueOf(v))));
        }
        Map<String, Object> inputs = new LinkedHashMap<>(args);  // 快照落 node_run.inputs

        try {
            SandboxResult result = sandbox.run(code, args);
            if (!result.ok()) {
                throw new BizException(WorkflowError.CODE_EXECUTION_FAILED, result.error());
            }
            return new NodeResult(inputs, result.outputs());
        } catch (Exception e) {
            // 渲染已成功、执行才失败：渲染后的实参随异常带出，落 node_run.inputs 供排障（同 HTTP 节点模式）
            throw new NodeExecutionException(inputs, e);
        }
    }
}
```

- [ ] **Step 6: 跑测试确认全绿**

Run: `cd server && ./mvnw -q test -Dtest=CodeNodeExecutorTest`
Expected: 4 tests 通过。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/hify/workflow/constant/NodeType.java \
        server/src/main/java/com/hify/workflow/constant/WorkflowError.java \
        server/src/main/java/com/hify/workflow/service/engine/CodeNodeExecutor.java \
        server/src/test/java/com/hify/workflow/service/engine/CodeNodeExecutorTest.java
git commit -m "feat(workflow): 代码执行节点执行器（NodeType.CODE+18002+渲染入参调沙箱）"
```

---

### Task 5: GraphValidator 代码节点字段校验

**Files:**
- Modify: `server/src/main/java/com/hify/workflow/service/engine/GraphValidator.java`
- Test: 追加到既有 `server/src/test/java/com/hify/workflow/service/engine/GraphValidatorTest.java`

**Interfaces:**
- Consumes: `NodeType.CODE`（Task 4）。
- Produces: 保存时校验 code 节点：`code` 必填非空字符串；`inputs` 若存在须为对象（Map）。图级/引用拓扑序沿用既有逻辑。

- [ ] **Step 1: 写失败测试（追加到 GraphValidatorTest）**

`GraphValidatorTest` 已有 `validator` 字段（`@BeforeEach` 构造）、helper `start()`（声明 `query` 必填）、`end(String value)`，以及两参 `new GraphEdge(source, target)`。复用它们。引用校验只查被引用**节点 id**、不查字段名（`referencedNodeIds` 只取 `m.group(1)`），故 code 节点的动态输出 `{{code_1.result}}` 保存时恒通过、运行时 `render` 兜底。追加三个用例：

```java
    @Test
    void code节点缺code_报图非法() {
        GraphNode code = new GraphNode("code_1", "code", Map.of("inputs", Map.of("x", "{{start.query}}")));
        GraphDef graph = new GraphDef(List.of(start(), code, end("{{code_1.result}}")),
                List.of(new GraphEdge("start", "code_1"), new GraphEdge("code_1", "end")));
        BizException ex = assertThrows(BizException.class, () -> validator.validateAndOrder(graph));
        assertTrue(ex.getMessage().contains("code"));
    }

    @Test
    void code节点inputs非对象_报图非法() {
        GraphNode code = new GraphNode("code_1", "code",
                Map.of("code", "def main(): return {}", "inputs", "oops"));
        GraphDef graph = new GraphDef(List.of(start(), code, end("{{code_1.result}}")),
                List.of(new GraphEdge("start", "code_1"), new GraphEdge("code_1", "end")));
        BizException ex = assertThrows(BizException.class, () -> validator.validateAndOrder(graph));
        assertTrue(ex.getMessage().contains("inputs"));
    }

    @Test
    void code节点合法_通过并含在拓扑序() {
        GraphNode code = new GraphNode("code_1", "code", Map.of(
                "code", "def main(query): return {'result': query}",
                "inputs", Map.of("query", "{{start.query}}")));
        GraphDef graph = new GraphDef(List.of(start(), code, end("{{code_1.result}}")),
                List.of(new GraphEdge("start", "code_1"), new GraphEdge("code_1", "end")));
        List<GraphNode> ordered = validator.validateAndOrder(graph);
        assertTrue(ordered.stream().anyMatch(n -> "code_1".equals(n.id())));
    }
```

> import 已在文件中（`GraphDef`/`GraphEdge`/`GraphNode`/`List`/`Map`/`BizException`/junit 断言），勿重复添加。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && ./mvnw -q test -Dtest=GraphValidatorTest`
Expected: 新增用例失败（`code节点缺code` 目前不报错——校验器还没管 code 节点）。

- [ ] **Step 3: 加 code 节点校验分派与方法**

在 `GraphValidator.validateAndOrder` 的类型分派块（`NodeType.HTTP` 判断之后）追加：

```java
            if (NodeType.HTTP.value().equals(n.type())) {
                requireHttpFields(n);
            }
            if (NodeType.CODE.value().equals(n.type())) {
                requireCodeFields(n);
            }
```

在 `requireHttpFields` 方法之后新增：

```java
    /** code 节点字段校验；code 必填非空；inputs 若存在须为对象（值为模板，走既有引用拓扑序校验）。 */
    private void requireCodeFields(GraphNode n) {
        Object code = n.data() == null ? null : n.data().get("code");
        if (code == null || String.valueOf(code).isBlank()) {
            throw invalid("code 节点 " + n.id() + " 缺少 code");
        }
        Object inputs = n.data().get("inputs");
        if (inputs != null && !(inputs instanceof Map)) {
            throw invalid("code 节点 " + n.id() + " 的 inputs 必须是对象");
        }
    }
```

- [ ] **Step 4: 跑测试确认全绿**

Run: `cd server && ./mvnw -q test -Dtest=GraphValidatorTest`
Expected: 全部通过（含既有用例）。

- [ ] **Step 5: 跑一次 workflow 引擎与模块边界回归**

Run: `cd server && ./mvnw -q test -Dtest='*Workflow*,*NodeExecutor*,ModularityTests'`
Expected: 全绿（确认加节点未破坏引擎/模块边界；ModularityTests 校验 infra→workflow 无越界）。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/hify/workflow/service/engine/GraphValidator.java \
        server/src/test/java/com/hify/workflow/service/engine/GraphValidatorTest.java
git commit -m "feat(workflow): GraphValidator 校验代码节点字段（code 必填+inputs 类型）"
```

---

### Task 6: 前端类型与节点配置校验

**Files:**
- Modify: `web/src/types/workflow.ts`
- Modify: `web/src/views/workflow/composables/useNodeIssues.ts`
- Test: `web/src/views/workflow/composables/__tests__/useNodeIssues.spec.ts`（若无则新建）

**Interfaces:**
- Produces: `WorkflowNodeType` 增加 `'code'`；`CodeNodeData { code?: string; inputs?: Record<string,string> }`；`NodeDataMap.code`。
- Produces: `nodeIssues('code', data)` 在 `code` 空时返回 `['缺少代码']`。

- [ ] **Step 1: 改类型定义**

在 `web/src/types/workflow.ts`：

把 `WorkflowNodeType` 增加 `'code'`：

```typescript
export type WorkflowNodeType =
  | 'start' | 'llm' | 'knowledge-retrieval' | 'condition' | 'http' | 'code' | 'end'
```

在 `HttpNodeData` 之后新增：

```typescript
/** code 节点：code 为 Python 源（须含 def main）；inputs 为 形参名→模板 映射（值支持 {{nodeId.field}}）。 */
export interface CodeNodeData {
  code?: string
  inputs?: Record<string, string>
}
```

在 `NodeDataMap` 里 `http: HttpNodeData` 之后加一行：

```typescript
  http: HttpNodeData
  code: CodeNodeData
  end: EndNodeData
```

- [ ] **Step 2: 写失败测试**

在 `web/src/views/workflow/composables/__tests__/useNodeIssues.spec.ts` 追加（若文件不存在则新建含以下内容及必要 import `import { describe, it, expect } from 'vitest'` 与 `import { nodeIssues } from '../useNodeIssues'`）：

```typescript
describe('nodeIssues - code', () => {
  it('code 空 → 缺少代码', () => {
    expect(nodeIssues('code', {})).toContain('缺少代码')
    expect(nodeIssues('code', { code: '   ' })).toContain('缺少代码')
  })
  it('code 有内容 → 无问题', () => {
    expect(nodeIssues('code', { code: 'def main():\n    return {}' })).toEqual([])
  })
})
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd web && pnpm vitest run src/views/workflow/composables/__tests__/useNodeIssues.spec.ts`
Expected: `code 空 → 缺少代码` 失败（nodeIssues 还没处理 code 分支，返回 `[]`）。

- [ ] **Step 4: 加 code 分支**

在 `useNodeIssues.ts` 的 `nodeIssues` 里，`http` 分支之后加：

```typescript
  } else if (type === 'http') {
    if (blank(d.method) || !(HTTP_METHODS as readonly string[]).includes(String(d.method).toUpperCase())) {
      issues.push('缺少或非法的请求方法')
    }
    if (blank(d.url)) issues.push('缺少 URL')
  } else if (type === 'code') {
    if (blank(d.code)) issues.push('缺少代码')
  }
```

- [ ] **Step 5: 跑测试确认全绿**

Run: `cd web && pnpm vitest run src/views/workflow/composables/__tests__/useNodeIssues.spec.ts`
Expected: 全绿。

- [ ] **Step 6: 类型检查 + 提交**

```bash
cd web && pnpm vue-tsc --noEmit
git add web/src/types/workflow.ts web/src/views/workflow/composables/useNodeIssues.ts \
        web/src/views/workflow/composables/__tests__/useNodeIssues.spec.ts
git commit -m "feat(web/workflow): code 节点类型定义 + nodeIssues 缺代码校验"
```

---

### Task 7: CodeForm.vue 代码节点表单

**Files:**
- Create: `web/src/views/workflow/components/forms/CodeForm.vue`
- Test: `web/src/views/workflow/components/forms/__tests__/CodeForm.spec.ts`

**Interfaces:**
- Consumes: `CodeNodeData`（Task 6）；`useVarInsert`（`register/unregister/onFocus/insert`）。
- Produces: `<CodeForm :data :disabled @update>`，`emit('update', patch: Partial<CodeNodeData>)`；`defineExpose({ insertVar })`（供抽屉变量面板注入到当前聚焦的映射值输入框）。
- 契约同 HttpForm：props `{ data: CodeNodeData; disabled: boolean }`，emit `update: [patch: CodeNodeData]`。

- [ ] **Step 1: 写失败测试**

Create `web/src/views/workflow/components/forms/__tests__/CodeForm.spec.ts`:

```typescript
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import CodeForm from '../CodeForm.vue'

function mountForm(data = {}) {
  return mount(CodeForm, {
    props: { data, disabled: false },
    global: { plugins: [ElementPlus] },
  })
}

describe('CodeForm', () => {
  it('编辑代码框 → emit update 带 code', async () => {
    const w = mountForm({ code: '' })
    const ta = w.find('[data-test="code-source"] textarea')
    await ta.setValue('def main():\n    return {}')
    expect(w.emitted('update')?.at(-1)?.[0]).toMatchObject({ code: 'def main():\n    return {}' })
  })

  it('添加映射行并填形参名+值 → emit update 带 inputs', async () => {
    const w = mountForm({ code: 'def main(x): return {}', inputs: {} })
    await w.find('[data-test="code-input-add"]').trigger('click')
    const name = w.find('[data-test="code-input-name"] input')
    const value = w.find('[data-test="code-input-value"] input')
    await name.setValue('x')
    await value.setValue('{{start.q}}')
    expect(w.emitted('update')?.at(-1)?.[0]).toMatchObject({ inputs: { x: '{{start.q}}' } })
  })

  it('删除映射行 → emit update 里该键消失', async () => {
    const w = mountForm({ code: 'def main(x): return {}', inputs: { x: '{{start.q}}' } })
    await w.find('[data-test="code-input-remove"]').trigger('click')
    expect(w.emitted('update')?.at(-1)?.[0]).toMatchObject({ inputs: {} })
  })

  it('disabled 时表单只读', () => {
    const w = mount(CodeForm, {
      props: { data: { code: 'x' }, disabled: true },
      global: { plugins: [ElementPlus] },
    })
    expect(w.find('[data-test="form-code"]').attributes('class')).toBeDefined()
    expect(w.find('[data-test="code-source"] textarea').attributes('disabled')).toBeDefined()
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web && pnpm vitest run src/views/workflow/components/forms/__tests__/CodeForm.spec.ts`
Expected: 失败（CodeForm.vue 不存在，解析报错）。

- [ ] **Step 3: 写 CodeForm.vue（照 HttpForm 的行状态 + useVarInsert 模式）**

Create `web/src/views/workflow/components/forms/CodeForm.vue`:

```vue
<script setup lang="ts">
import { ref, watchEffect } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import type { CodeNodeData } from '@/types/workflow'
import { useVarInsert } from '../../composables/useVarInsert'

const props = defineProps<{ data: CodeNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: CodeNodeData] }>()

// inputs 本地行状态：name 编辑中允许空/重复，写回时过滤空 name、后写赢（同 HttpForm headers）。
// 依赖抽屉侧 :key="node.id" 保证切换节点时本组件重建、行状态不串。
const rows = ref<{ name: string; value: string }[]>(
  Object.entries(props.data.inputs ?? {}).map(([name, value]) => ({ name, value })),
)

function syncInputs() {
  const inputs: Record<string, string> = {}
  for (const r of rows.value) {
    if (r.name.trim() !== '') inputs[r.name.trim()] = r.value
  }
  emit('update', { inputs })
}
function addRow() {
  rows.value.push({ name: '', value: '' })
}
function removeRow(i: number) {
  rows.value.splice(i, 1)
  syncInputs()
}
function updateName(i: number, v: string) {
  rows.value[i].name = v
  syncInputs()
}
function updateValue(i: number, v: string) {
  rows.value[i].value = v
  syncInputs()
}

// 变量面板只注入到「当前聚焦的映射值输入框」；代码框本身不吃 {{}}（变量经形参进 main）。
const { register, unregister, onFocus, insert } = useVarInsert(() =>
  rows.value.length > 0 ? `mv_${rows.value.length - 1}` : 'none',
)
let registeredRows = 0
watchEffect(() => {
  const len = rows.value.length
  for (let i = 0; i < len; i++) {
    register(`mv_${i}`, {
      get: () => rows.value[i]?.value ?? '',
      set: (v) => {
        if (rows.value[i]) {
          rows.value[i].value = v
          syncInputs()
        }
      },
    })
  }
  for (let i = len; i < registeredRows; i++) unregister(`mv_${i}`)
  registeredRows = len
})
defineExpose({ insertVar: insert })
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-code">
    <el-form-item label="输入映射">
      <div v-for="(row, i) in rows" :key="i" class="code-form__row">
        <div data-test="code-input-name" class="code-form__name">
          <el-input
            :model-value="row.name"
            placeholder="形参名，如 text"
            @update:model-value="updateName(i, $event)"
          />
        </div>
        <div data-test="code-input-value" class="code-form__value">
          <el-input
            :model-value="row.value"
            placeholder="变量，如 {{start.question}}"
            @update:model-value="updateValue(i, $event)"
            @focusin="onFocus(`mv_${i}`)"
          />
        </div>
        <el-button data-test="code-input-remove" :icon="Delete" text @click="removeRow(i)" />
      </div>
      <el-button data-test="code-input-add" :icon="Plus" text type="primary" @click="addRow"
        >添加输入</el-button
      >
    </el-form-item>
    <el-form-item label="Python 代码" required>
      <div data-test="code-source" class="code-form__source">
        <el-input
          type="textarea"
          :rows="10"
          :input-style="{ fontFamily: 'monospace' }"
          :model-value="data.code ?? ''"
          placeholder="def main(形参...):&#10;    return {&quot;key&quot;: 值}"
          @update:model-value="emit('update', { code: $event })"
        />
      </div>
    </el-form-item>
    <div class="code-form__hint">
      写 <code>def main(形参)</code>，形参对应上面的输入映射；<code>return</code> 一个 dict，
      其 key 即下游可引用的输出变量（如 <code>{{ '{{code_1.key}}' }}</code>）。仅 Python 标准库。
    </div>
  </el-form>
</template>

<style scoped lang="scss">
[data-test="code-source"] {
  width: 100%;
}
.code-form__row {
  display: flex;
  gap: $spacing-sm;
  width: 100%;
  margin-bottom: $spacing-sm;
}
.code-form__name {
  width: 40%;
}
.code-form__value {
  flex: 1;
}
.code-form__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  code {
    background: var(--el-fill-color-light);
    padding: 0 4px;
    border-radius: 3px;
  }
}
</style>
```

- [ ] **Step 4: 跑测试确认全绿**

Run: `cd web && pnpm vitest run src/views/workflow/components/forms/__tests__/CodeForm.spec.ts`
Expected: 4 tests 通过。

- [ ] **Step 5: 提交**

```bash
git add web/src/views/workflow/components/forms/CodeForm.vue \
        web/src/views/workflow/components/forms/__tests__/CodeForm.spec.ts
git commit -m "feat(web/workflow): CodeForm 代码节点表单（输入映射行+等宽代码框+变量注入）"
```

---

### Task 8: 前端画布接线（调色板 / 画布节点 / 抽屉分派）

**Files:**
- Modify: `web/src/views/workflow/components/NodePalette.vue`
- Modify: `web/src/views/workflow/components/CanvasNode.vue`
- Modify: `web/src/views/workflow/components/NodeConfigDrawer.vue`
- Test: `web/src/views/workflow/components/__tests__/NodePalette.spec.ts`（追加）

**Interfaces:**
- Consumes: `CodeForm`（Task 7）；`WorkflowNodeType 'code'`（Task 6）。
- Produces: 调色板可拖出 code 节点；画布 code 节点显示图标+标签；抽屉按 `type==='code'` 渲染 CodeForm、标题「代码执行」。

- [ ] **Step 1: 调色板加 code 项**

在 `NodePalette.vue`：import 增加 `Cpu` 图标，`ITEMS` 加一项。

```typescript
import { ChatDotRound, Collection, Cpu, Link, Switch } from '@element-plus/icons-vue'
```

```typescript
const ITEMS: { type: WorkflowNodeType; label: string; icon: Component }[] = [
  { type: 'llm', label: 'LLM', icon: ChatDotRound },
  { type: 'knowledge-retrieval', label: '知识检索', icon: Collection },
  { type: 'condition', label: '条件分支', icon: Switch },
  { type: 'http', label: 'HTTP 请求', icon: Link },
  { type: 'code', label: '代码执行', icon: Cpu },
]
```

- [ ] **Step 2: 画布节点 META 加 code**

在 `CanvasNode.vue`：import 增加 `Cpu`，`META` 加一行。

```typescript
import {
  ChatDotRound, CircleCheck, CircleCheckFilled, CircleCloseFilled, Collection, Cpu, Link,
  ...
} from '@element-plus/icons-vue'
```

```typescript
  http: { label: 'HTTP 请求', icon: Link },
  code: { label: '代码执行', icon: Cpu },
  end: { label: '结束', icon: CircleCheck },
```

- [ ] **Step 3: 抽屉分派加 code**

在 `NodeConfigDrawer.vue`：import CodeForm，`FORMS`、`TITLES` 各加一项。

```typescript
import HttpForm from './forms/HttpForm.vue'
import CodeForm from './forms/CodeForm.vue'
import EndForm from './forms/EndForm.vue'
```

```typescript
const FORMS: Record<WorkflowNodeType, Component> = {
  ...
  http: HttpForm,
  code: CodeForm,
  end: EndForm,
}
const TITLES: Record<WorkflowNodeType, string> = {
  ...
  http: 'HTTP 请求',
  code: '代码执行',
  end: '结束',
}
```

- [ ] **Step 4: 追加调色板测试**

在 `web/src/views/workflow/components/__tests__/NodePalette.spec.ts` 追加一条（参照既有 http 项的断言写法）：

```typescript
  it('含代码执行节点可拖拽', () => {
    const w = mount(NodePalette, { global: { plugins: [ElementPlus] } })
    expect(w.find('[data-test="palette-code"]').exists()).toBe(true)
    expect(w.text()).toContain('代码执行')
  })
```

> 若既有测试文件的 import/mount helper 不同，沿用该文件既有风格，仅保证断言 `palette-code` 存在与文案。

- [ ] **Step 5: 跑前端四件套回归**

Run: `cd web && pnpm vitest run src/views/workflow && pnpm vue-tsc --noEmit && pnpm lint`
Expected: 全绿（含 NodePalette/CanvasNode/NodeConfigDrawer 既有测试，确认 `Record<WorkflowNodeType,...>` 全类型覆盖不漏 code）。

- [ ] **Step 6: 提交**

```bash
git add web/src/views/workflow/components/NodePalette.vue \
        web/src/views/workflow/components/CanvasNode.vue \
        web/src/views/workflow/components/NodeConfigDrawer.vue \
        web/src/views/workflow/components/__tests__/NodePalette.spec.ts
git commit -m "feat(web/workflow): 画布接入代码执行节点（调色板+画布节点+抽屉分派）"
```

---

### Task 9: 全量回归 + 容器联调验收

**Files:** 无（验收）

- [ ] **Step 1: 后端全量测试**

Run: `cd server && ./mvnw -q test`
Expected: 全绿（含 ModularityTests/ArchUnit 模块边界）。判定看 exit code，不 grep BUILD SUCCESS。

- [ ] **Step 2: 前端全量四件套**

Run: `cd web && pnpm vitest run && pnpm vue-tsc --noEmit && pnpm lint && pnpm build`
Expected: 全绿，build 成功。

- [ ] **Step 3: 起沙箱容器 + server，画布端到端验收**

```bash
docker compose up -d --build sandbox
# 起 server（本地方式，见项目既有启动脚本），登录后进工作流画布
```
手工验收清单：
- 拖一个「代码执行」节点，连 start→code→end；code 填 `def main(text):\n    return {"n": len(text.split())}`，输入映射 `text ← {{start.question}}`；end 输出 `{{code_1.n}}`
- 运行、传 question="a b c d" → 输出 n=4，节点徽章成功
- 死循环 `def main():\n    while True: pass` → 节点失败，错误含「超时」
- `def main():\n    import socket; socket.create_connection(("1.1.1.1",80),2); return {}` → 失败（网络隔离生效，连不通外网）

- [ ] **Step 4: 自检入档**

按 `self-check-per-step` 约定，把本轮自检结果追加到 `docs/self-check.md` 并提交。

```bash
git add docs/self-check.md
git commit -m "docs: 代码执行节点+沙箱 全量回归通过自检入档"
```

---

## 附：本轮完成后的留账（写入 memory，非本计划步骤）

- 沙箱语言级隔离弱（不禁 import），一期靠容器边界；对不可信用户开放或二期上 K8s 时加 gVisor/seccomp。
- 参数字符串化：若 code 节点成为数据管道主力，二期评估类型化变量传参。
- 无第三方库：真实需要 numpy/pandas 时改 sandbox/Dockerfile。
- 本地 compose 暂用 `ports` 映射沙箱端口联调；server 进 compose 后删该映射，仅留 sandbox-net。
