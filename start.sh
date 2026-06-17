#!/usr/bin/env bash
#
# ============================================================================
# Hify 本地一键启动脚本
# ----------------------------------------------------------------------------
# 流程：检查 PostgreSQL → 构建并后台启动后端 → 轮询健康检查 → 后台启动前端。
# 两个服务都以「独立进程组」后台运行，PID 写入 .run/*.pid，启动完成后脚本即退出。
# 停止用 ./stop.sh（按 PID 文件优雅停止）。查看日志：tail -f .run/backend.log /
# .run/frontend.log。任何一步失败立即停止并提示，并回收本次已拉起的进程。
#
# 用法：./start.sh
# 可用环境变量覆盖（写在命令前即可，如 `HEALTH_TIMEOUT=60 ./start.sh`）：
#   PG_HOST(localhost) PG_PORT(5432)
#   BACKEND_URL(http://localhost:8080) HEALTH_PATH(/api/v1/health) HEALTH_TIMEOUT(120 秒)
# ============================================================================

# set 是 bash 的“安全开关”，专业脚本标配，照抄即可：
#   -e          任何命令返回非 0（失败）就立即退出，不带病继续往下跑
#   -u          引用未定义的变量直接报错（防拼写错）
#   -o pipefail 管道 a | b 中任一段失败，整条管道就算失败（默认只看最后一段）
set -euo pipefail

# 计算脚本所在目录并切过去：无论从哪个目录调用 ./start.sh，后续相对路径都对。
# BASH_SOURCE[0] 是脚本自身路径，dirname 取目录，cd+pwd 转成绝对路径。
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# ---- 配置 ----
# 语法 ${VAR:-默认值}：若环境变量 VAR 没设置就用默认值。这样配置可被外部覆盖、又有合理默认。
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
HEALTH_PATH="${HEALTH_PATH:-/api/v1/health}"
HEALTH_URL="${BACKEND_URL}${HEALTH_PATH}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"   # 等后端就绪的最长秒数
POLL_INTERVAL=2                           # 健康检查轮询间隔（秒）

# 运行态文件统一放 .run/（已被 .gitignore 忽略）：PID 文件 + 日志
RUN_DIR="$ROOT/.run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"
BACKEND_LOG="$RUN_DIR/backend.log"
FRONTEND_LOG="$RUN_DIR/frontend.log"

# ---- 输出辅助 ----
# [ -t 1 ] 判断标准输出是否连着终端：是才用彩色转义码，重定向到文件时不污染日志。
if [ -t 1 ]; then C_RED='\033[0;31m'; C_GREEN='\033[0;32m'; C_BLUE='\033[0;34m'; C_OFF='\033[0m'; else C_RED= C_GREEN= C_BLUE= C_OFF=; fi
info() { printf "${C_BLUE}==>${C_OFF} %s\n" "$*"; }
ok()   { printf "${C_GREEN}✓${C_OFF} %s\n" "$*"; }
fail() { printf "${C_RED}✗ %s${C_OFF}\n" "$*" >&2; exit 1; }   # 打印红字并以 1 退出（触发下面的 trap）

# ---- 失败回收 ----
# trap：注册“脚本退出时要做的事”。这里实现“启动中途失败就回收本次已拉起的进程”，
# 避免留下半拉子状态。STARTED_OK=1 表示已成功启动完毕——此时不回收（服务要继续后台跑）。
STARTED_OK=0
cleanup_on_fail() {
  [ "$STARTED_OK" = 1 ] && return 0
  local pf pid
  for pf in "$FRONTEND_PID_FILE" "$BACKEND_PID_FILE"; do
    [ -f "$pf" ] || continue
    pid="$(cat "$pf" 2>/dev/null || true)"
    # kill -TERM "-$pid"：负号表示“发给整个进程组”（见下方 launch_group）；失败再退回单进程
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    fi
    rm -f "$pf"
  done
}
trap cleanup_on_fail EXIT   # EXIT：脚本以任何方式结束都会执行 cleanup_on_fail

# 判断某个 pidfile 对应的进程是否还活着。
# kill -0 <pid>：不真的发信号，只“探测”进程是否存在（存在返回 0），是检查存活的标准手法。
is_running() {
  local pf="$1" pid
  [ -f "$pf" ] || return 1
  pid="$(cat "$pf" 2>/dev/null || true)"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

# 在「独立进程组」里后台启动一个命令，并把组长 PID 写入 pidfile。
# 为什么要进程组：pnpm 会派生 vite，vite 又派生 esbuild/sass。只杀 pnpm 会留下孤儿进程；
# 把它们放进同一个新会话（setsid），停止时对“整组”发信号，就能一锅端。
# 关键技巧：setsid 起的会话首进程先 `echo $$`（自己的 PID）写入 pidfile，再 `exec` 成目标程序——
# exec 会用目标替换当前进程但 PID 不变，于是 pidfile 里就是“真正的组长 PID”。
# （注意：setsid 自身的 $! 不可靠，所以才用这种“自报 PID”的写法。）
launch_group() {  # 参数：<pidfile> <logfile> <shell命令，应以 exec <prog> 结尾>
  local pidfile="$1" logfile="$2" cmd="$3" i=0
  setsid bash -c "echo \$\$ > '$pidfile'; $cmd" >"$logfile" 2>&1 &
  # pidfile 是异步写出的，这里等它出现（最多 ~5s）。-s 判断文件存在且非空。
  while [ ! -s "$pidfile" ]; do
    i=$((i + 1)); [ "$i" -gt 50 ] && return 1
    sleep 0.1
  done
  return 0
}

# ---- 0. 依赖命令检查 ----
# command -v <cmd>：查命令是否在 PATH 中；缺了就早早报错，而不是跑到一半才挂。
for t in mvn java pnpm curl nc setsid; do
  command -v "$t" >/dev/null 2>&1 || fail "缺少命令：$t，请先安装后再运行。"
done

mkdir -p "$RUN_DIR"

# ---- 防重复启动 ----
# 已经在跑就别再起一份（会端口冲突）。这里把 STARTED_OK 置 1 是为了让 trap 不要去“回收”
# 那个本就在正常运行的服务。
if is_running "$BACKEND_PID_FILE" || is_running "$FRONTEND_PID_FILE"; then
  STARTED_OK=1
  fail "检测到服务已在运行（见 .run/*.pid）。请先执行 ./stop.sh。"
fi

# ---- 1. 检查 PostgreSQL ----
# nc -z 只做端口连通性探测（不发数据），-w 3 是 3 秒超时。探不通就提示先起数据库。
info "检查 PostgreSQL ${PG_HOST}:${PG_PORT} ..."
nc -z -w 3 "$PG_HOST" "$PG_PORT" 2>/dev/null || fail "PostgreSQL 不可用（${PG_HOST}:${PG_PORT}）。先启动数据库：docker compose up -d"
ok "PostgreSQL 可用"

# ---- 2. 构建后端 ----
# -B 批处理（无颜色/交互），-q 安静，-DskipTests 跳过测试（启动脚本求快；测试由 CI/make build 跑）。
info "构建后端（mvn package，跳过测试）..."
mvn -B -q -f "$ROOT/server/pom.xml" -DskipTests clean package || fail "后端构建失败，请查看上方 Maven 输出。"
# 取最新的可执行 jar：ls -t 按时间排序，排除 Spring Boot 重打包留下的 *.jar.original。
JAR="$(ls -t "$ROOT"/server/target/*.jar 2>/dev/null | grep -v '\.original$' | head -n1 || true)"
[ -n "$JAR" ] || fail "构建后未找到可执行 jar（server/target/*.jar）。"
ok "构建完成：$(basename "$JAR")"

# ---- 2b. 后台启动后端 ----
info "后台启动后端，日志 → ${BACKEND_LOG}"
launch_group "$BACKEND_PID_FILE" "$BACKEND_LOG" "exec java -jar '$JAR'" || fail "后端启动失败（未写出 PID）。"
BACKEND_PID="$(cat "$BACKEND_PID_FILE")"
ok "后端已启动 (PID $BACKEND_PID)"

# ---- 3. 轮询健康检查（HTTP 200）----
# 后端启动需要时间，这里循环 curl 健康检查，直到返回 200 或超时。
info "等待后端就绪（${HEALTH_URL}，最长 ${HEALTH_TIMEOUT}s）..."
deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))   # 截止时间戳 = 现在 + 超时秒数
while :; do
  # 后端进程若中途挂了，立刻失败并打印日志尾部，省得干等到超时。
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    printf "${C_RED}--- backend.log（末尾 30 行）---${C_OFF}\n" >&2; tail -n 30 "$BACKEND_LOG" >&2 || true
    fail "后端进程意外退出，未能就绪。"
  fi
  # -o /dev/null 丢弃响应体，-w '%{http_code}' 只输出 HTTP 状态码用于判断。
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$HEALTH_URL" || true)"
  [ "$code" = "200" ] && { ok "后端健康检查通过（HTTP 200）"; break; }
  if [ "$(date +%s)" -ge "$deadline" ]; then
    printf "${C_RED}--- backend.log（末尾 30 行）---${C_OFF}\n" >&2; tail -n 30 "$BACKEND_LOG" >&2 || true
    fail "等待后端健康检查超时（${HEALTH_TIMEOUT}s）。"
  fi
  sleep "$POLL_INTERVAL"
done

# ---- 4. 后台启动前端开发服务器 ----
if [ ! -d "$ROOT/web/node_modules" ]; then
  info "首次运行，安装前端依赖（pnpm install）..."
  ( cd "$ROOT/web" && pnpm install ) || fail "前端依赖安装失败。"
fi
info "后台启动前端开发服务器，日志 → ${FRONTEND_LOG}"
launch_group "$FRONTEND_PID_FILE" "$FRONTEND_LOG" "cd '$ROOT/web'; exec pnpm dev" || fail "前端启动失败（未写出 PID）。"
FRONTEND_PID="$(cat "$FRONTEND_PID_FILE")"
sleep 1   # 给 vite 一点启动时间，再确认进程没立刻崩
kill -0 "$FRONTEND_PID" 2>/dev/null || { tail -n 30 "$FRONTEND_LOG" >&2 || true; fail "前端进程启动后立即退出。"; }
ok "前端已启动 (PID $FRONTEND_PID)"

# 全部就绪：置 STARTED_OK=1，trap 便不再回收，服务留在后台继续运行；脚本打印汇总后退出。
STARTED_OK=1
printf "\n"
ok "Hify 已启动："
printf "    后端  PID %s  →  %s\n" "$BACKEND_PID" "$BACKEND_URL"
printf "    前端  PID %s  →  http://localhost:5173\n" "$FRONTEND_PID"
printf "    日志  tail -f .run/backend.log  |  tail -f .run/frontend.log\n"
printf "    停止  ./stop.sh\n"
