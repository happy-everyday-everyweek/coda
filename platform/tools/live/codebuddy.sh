#!/usr/bin/env bash
# CodeBuddy：OpenAI 兼容 /chat/completions（CODEBUDDY_BASE_URL 覆盖端点）。
AGENT=codebuddy
source "$(dirname "$0")/lib.sh"
install_npm @tencent-ai/codebuddy-code
start_gateway
WORK="$(mktemp -d)"
mkdir -p "$OUT/home"
cd "$WORK"
HOME="$OUT/home" \
CODEBUDDY_BASE_URL="$GATEWAY_URL" \
CODEBUDDY_API_KEY=test-token \
CODEBUDDY_AUTH_TOKEN=test-token \
  timeout 180 codebuddy -p "$PROMPT" --output-format stream-json \
  </dev/null >"$OUT/transcript.jsonl" 2>"$OUT/stderr.log"
finish "$OUT/transcript.jsonl"
codebuddy --version >"$OUT/version.txt" 2>&1 || true
