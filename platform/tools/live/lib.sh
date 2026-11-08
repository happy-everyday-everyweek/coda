#!/usr/bin/env bash
# 真实 Agent CLI ↔ 本地 LLM 网关的回合测试公共部分。
#
# 用法（由各 agent 脚本 source）：
#   AGENT=claude
#   source "$(dirname "$0")/lib.sh"
#   start_gateway
#   ...
#
# 输出目录：$LIVE_TURNS_DIR/<agent>（默认 <repo>/live-turns/<agent>）。
# 每个脚本必须留下 transcript 与 gateway.jsonl，CI 作为证据上传。

set -euo pipefail
: "${AGENT:?AGENT 未设置}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="${LIVE_TURNS_DIR:-$ROOT/live-turns}/$AGENT"
mkdir -p "$OUT"
PORT="${GATEWAY_PORT:-18080}"
GATEWAY_URL="http://127.0.0.1:$PORT"
PROMPT="${LIVE_PROMPT:-Reply with exactly CODA_GATEWAY_OK. Do not use tools.}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"

log() { echo "[$AGENT] $*" >&2; }

install_npm() {
  log "安装 $1"
  npm install -g --registry="$NPM_REGISTRY" "$1" >"$OUT/npm-install.log" 2>&1 || {
    cat "$OUT/npm-install.log" >&2
    log "安装失败：$1"
    return 1
  }
}

start_gateway() {
  log "启动网关 :$PORT"
  node "$ROOT/platform/tools/mock-llm-gateway.mjs" --port "$PORT" --log "$OUT/gateway.jsonl" \
    >"$OUT/gateway.out" 2>"$OUT/gateway.err" &
  GW_PID=$!
  trap 'kill "$GW_PID" 2>/dev/null || true' EXIT
  for _ in $(seq 1 100); do
    if curl -fsS "$GATEWAY_URL/health" >/dev/null 2>&1; then
      log "网关就绪"
      return 0
    fi
    sleep 0.1
  done
  log "网关启动超时"
  cat "$OUT/gateway.err" >&2 || true
  exit 1
}

finish() {
  local file="$1"
  if grep -q "CODA_GATEWAY_OK" "$file"; then
    log "PASS: 收到 CODA_GATEWAY_OK"
    echo "pass" >"$OUT/status.txt"
  else
    log "FAIL: 未在 $file 中找到 CODA_GATEWAY_OK"
    echo "fail" >"$OUT/status.txt"
    exit 1
  fi
}

# 有些 CLI（当前为 Crush 0.100.0）在 CI 非交互环境下把最终文本写进内部
# session 而不打到 stdout；此时以网关收到的真实请求 + 固定回复作为协议回合证据。
finish_gateway() {
  if grep -q '"reply":"CODA_GATEWAY_OK"' "$OUT/gateway.jsonl" 2>/dev/null; then
    log "PASS（网关日志）：收到 CODA_GATEWAY_OK"
    echo "pass-gateway" >"$OUT/status.txt"
  else
    log "FAIL: 网关日志中没有 CODA_GATEWAY_OK"
    echo "fail" >"$OUT/status.txt"
    exit 1
  fi
}
