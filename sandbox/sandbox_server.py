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
