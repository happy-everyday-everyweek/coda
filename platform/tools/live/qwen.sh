#!/usr/bin/env bash
# Qwen Code：OpenAI 兼容 + stream-json。
AGENT=qwen
source "$(dirname "$0")/lib.sh"
install_npm @qwen-code/qwen-code
start_gateway
WORK="$(mktemp -d)"
mkdir -p "$OUT/home"
cd "$WORK"
HOME="$OUT/home" \
OPENAI_API_KEY=test-token \
OPENAI_BASE_URL="$GATEWAY_URL/v1" \
OPENAI_MODEL=coda-gateway \
  timeout 180 qwen -p "$PROMPT" -o stream-json \
  </dev/null >"$OUT/transcript.jsonl" 2>"$OUT/stderr.log"
finish "$OUT/transcript.jsonl"
qwen --version >"$OUT/version.txt" 2>&1 || true
