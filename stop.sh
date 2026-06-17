#!/usr/bin/env bash
#
# ============================================================================
# Hify 本地停止脚本
# ----------------------------------------------------------------------------
# 按 .run/*.pid 找到前后端进程组，先发 SIGTERM 让其优雅退出（程序有机会收尾），
# 超时仍未退出再发 SIGKILL 强制结束。
#
# 用法：./stop.sh
# 可用环境变量覆盖：STOP_TIMEOUT(15 秒，等待优雅退出的最长秒数)
# ============================================================================

# 这里特意不开 -e：即便某个服务停止失败，也要继续尝试停另一个（用 rc 记录整体结果）。
# -u/pipefail 仍保留，防变量拼写错。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

RUN_DIR="$ROOT/.run"
STOP_TIMEOUT="${STOP_TIMEOUT:-15}"   # ${VAR:-默认}：超时秒数可由环境变量覆盖

# 输出辅助（[ -t 1 ] 为真才上色，详见 start.sh 注释）
if [ -t 1 ]; then C_RED='\033[0;31m'; C_GREEN='\033[0;32m'; C_BLUE='\033[0;34m'; C_YEL='\033[0;33m'; C_OFF='\033[0m'; else C_RED= C_GREEN= C_BLUE= C_YEL= C_OFF=; fi
info() { printf "${C_BLUE}==>${C_OFF} %s\n" "$*"; }
ok()   { printf "${C_GREEN}✓${C_OFF} %s\n" "$*"; }
warn() { printf "${C_YEL}!${C_OFF} %s\n" "$*"; }
err()  { printf "${C_RED}✗ %s${C_OFF}\n" "$*" >&2; }

# 向「进程组」发信号。pidfile 里存的是组长 PID（start.sh 用 setsid 保证），
# `kill -<信号> -<pid>` 的负号表示“发给整个进程组”，能连带杀掉子进程（vite/esbuild 等）。
# 万一不是组长（退化情况），就退回普通的单进程发送。
signal_group() {  # signal_group <信号 TERM|KILL> <pid>
  local sig="$1" pid="$2"
  kill -"$sig" "-$pid" 2>/dev/null || kill -"$sig" "$pid" 2>/dev/null || true
}

# 停止单个服务：读 pidfile → SIGTERM → 轮询等待 → 超时 SIGKILL → 清理 pidfile。
stop_one() {  # stop_one <名称> <pidfile>
  local name="$1" pf="$2" pid waited=0

  # 没有 pidfile：大概率没启动过，跳过即可。
  if [ ! -f "$pf" ]; then
    info "${name}：无 PID 文件，跳过（可能未启动）"
    return 0
  fi

  # pidfile 在但进程已经不在（上次异常退出留下的残留）：清掉文件就好。
  pid="$(cat "$pf" 2>/dev/null || true)"
  if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
    warn "${name}：进程不存在（PID=${pid:-空}），清理残留 PID 文件"
    rm -f "$pf"
    return 0
  fi

  # 第一步：礼貌地请它退出（SIGTERM 可被程序捕获，用于保存状态/关闭连接）。
  info "${name}：发送 SIGTERM（PID $pid，进程组）..."
  signal_group TERM "$pid"

  # 第二步：每秒检查一次是否退出，最多等 STOP_TIMEOUT 秒。
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge "$STOP_TIMEOUT" ]; then
      # 第三步：超时仍在 → SIGKILL 强杀（不可被捕获，必定终止）。
      warn "${name}：${STOP_TIMEOUT}s 内未退出，发送 SIGKILL"
      signal_group KILL "$pid"
      sleep 1
      break
    fi
    sleep 1
    waited=$((waited + 1))
  done

  # 兜底校验：连 KILL 都没杀掉（极少见，如僵尸/不可中断状态）就报错。
  if kill -0 "$pid" 2>/dev/null; then
    err "${name}：仍未停止（PID $pid），请手动检查"
    return 1
  fi
  ok "${name}：已停止"
  rm -f "$pf"
  return 0
}

info "停止 Hify（优雅停止超时 ${STOP_TIMEOUT}s）..."
rc=0
# 先停前端再停后端；`|| rc=1` 记录失败但不中断，确保两个都尝试停。
stop_one "前端" "$RUN_DIR/frontend.pid" || rc=1
stop_one "后端" "$RUN_DIR/backend.pid" || rc=1

[ "$rc" = 0 ] && ok "全部已停止。" || err "部分服务未能停止，见上方提示。"
exit "$rc"   # 把整体结果作为退出码返回（0=都成功），供调用方（如 make）判断
