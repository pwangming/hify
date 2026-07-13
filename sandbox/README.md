# Hify 代码执行沙箱

工作流「代码执行」节点跑用户 Python 代码的地方。零依赖单文件 HTTP 服务
（`sandbox_server.py`，仅标准库），每次 `/run` 起一个隔离子进程执行，靠**容器边界**
（网络隔离 + 只读 + 资源硬限 + 执行超时）保证安全，不靠 Python 语言层。
设计见 `docs/architecture/deployment.md` 第 2、5 节。

---

## 启动 / 重建

```bash
cd /home/wang/playlab/hify
docker compose up -d --build sandbox      # 首次或改了代码/Dockerfile 时带 --build
docker compose ps                         # 等 STATUS 显示 healthy
```

停止：

```bash
docker compose stop sandbox               # 只停沙箱
# 或 docker compose down                  # 停全部（保留数据卷）
```

## 冒烟验证

沙箱在生产网络 `sandbox-net` 是 **internal（禁一切出网）**，宿主机默认**访问不到**它。
两种验证方式：

**A. 从容器内发起（任何时候都行，不依赖 override）**

```bash
docker compose exec -T sandbox python -c "
import urllib.request, json
def call(code, inputs, t=5000):
    body=json.dumps({'code':code,'inputs':inputs,'timeoutMs':t}).encode()
    r=urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8000/run',data=body,headers={'Content-Type':'application/json'}))
    return r.read().decode()
print('正常   :', call('def main(text):\n    return {\"n\": len(text.split())}', {'text':'a b c d'}))
print('超时   :', call('def main():\n    while True: pass', {}, 800))
print('网络隔离:', call('def main():\n    import socket; socket.create_connection((\"1.1.1.1\",80),2); return {}', {}))
"
# 期望：正常 n=4；超时 ok:false 含「执行超时」；网络隔离 ok:false 含「Network is unreachable」
```

**B. 从宿主机 curl（需 override，见下）**

```bash
curl -s http://127.0.0.1:8000/health
curl -s -X POST http://127.0.0.1:8000/run -H 'Content-Type: application/json' \
  -d '{"code":"def main(x):\n    return {\"y\": int(x)+1}","inputs":{"x":"41"},"timeoutMs":5000}'
```

## 本地端到端联调（宿主机 server + 界面里跑工作流）

宿主机上的 server 连不到 internal 网络里的沙箱。用 **`docker-compose.override.yml`**
（已 gitignore、docker compose 自动叠加）给沙箱额外挂一个非 internal 网络并发布端口：

```yaml
# docker-compose.override.yml —— 仅本地开发，勿提交、勿用于生产
services:
  sandbox:
    networks: [sandbox-net, sandbox-dev]
    ports: ["127.0.0.1:8000:8000"]
networks:
  sandbox-dev:
    driver: bridge
```

然后：

```bash
docker compose up -d --build sandbox                       # 让 override 生效
HIFY_SANDBOX_BASE_URL=http://127.0.0.1:8000 \
  mvn -f server/pom.xml spring-boot:run                     # server 指向发布出来的端口
cd web && pnpm dev                                         # 另开终端起前端
```

> ⚠️ override 的非 internal 网络会**恢复沙箱出网能力**——本地图方便可接受，切勿带进生产。
> 要验证生产隔离形态，临时把 `docker-compose.override.yml` 改个名再 `up` 即可。

## 单元测试（不需要 Docker）

```bash
cd sandbox && python3 -m unittest test_sandbox_server -v    # 8 个用例
```

## 协议速查

`POST /run`（响应恒 HTTP 200，成败看 body 的 `ok`）：

```
请求  { "code": "def main(形参...): ...return {dict}", "inputs": {"形参":"值"}, "timeoutMs": 5000 }
成功  { "ok": true,  "outputs": { ...main 返回的 dict... } }
失败  { "ok": false, "error": "执行超时（…）/ main 必须返回 dict / 执行出错：… 等" }
```

- `inputs` 的键必须和 `def main(...)` 的形参一一对应，多了少了都会 TypeError。
- 传进 `main` 的值都是字符串（上游变量渲染即字符串替换），需要数字自己 `int()`。
- 仅 Python 标准库；无 numpy/pandas 等第三方库。

配置项（server 侧）在 `application.yml` 的 `hify.sandbox.*`；沙箱侧资源上限走容器环境变量
（`SANDBOX_CPU_SECONDS` / `SANDBOX_MEM_BYTES` / `SANDBOX_MAX_OUTPUT_BYTES`）。
