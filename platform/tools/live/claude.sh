#!/usr/bin/env bash
# Claude Code：Anthropic Messages + stream-json。
AGENT=claude
source "$(dirname "$0")/lib.sh"
install_npm @anthropic-ai/claude-code
start_gateway
WORK="$(mktemp -d)"
mkdir -p "$OUT/home"
cd "$WORK"
HOME="$OUT/home" \
ANTHROPIC_BASE_URL="$GATEWAY_URL" \
ANTHROPIC_AUTH_TOKEN=test-token \
ANTHROPIC_MODEL=claude-sonnet-4-5 \
CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
  timeout 180 claude -p "$PROMPT" --output-format stream-json --verbose \
  >"$OUT/transcript.jsonl" 2>"$OUT/stderr.log"
finish "$OUT/transcript.jsonl"
claude --version >"$OUT/version.txt" 2>&1 || true

# 适配器级：用 platform/adapters/claude-stream-json 驱动真实 CLI
HOME="$OUT/home" node "$ROOT/platform/tools/live/adapter-smoke.mjs" \
  --agent claude --gateway "$GATEWAY_URL" --out "$OUT/adapter.json"
