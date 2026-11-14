#!/usr/bin/env bash
# OpenCode：OpenAI Chat Completions 兼容端点。
AGENT=opencode
source "$(dirname "$0")/lib.sh"
install_npm opencode-ai
start_gateway
WORK="$(mktemp -d)"
mkdir -p "$OUT/config/opencode" "$OUT/home" "$OUT/data" "$OUT/cache"
cat >"$OUT/config/opencode/opencode.json" <<EOF
{
  "\$schema": "https://opencode.ai/config.json",
  "provider": {
    "coda": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Coda Gateway",
      "options": { "baseURL": "$GATEWAY_URL/v1", "apiKey": "test-token" },
      "models": { "coda-gateway": { "name": "Coda Gateway" } }
    }
  }
}
EOF
cd "$WORK"
HOME="$OUT/home" \
XDG_CONFIG_HOME="$OUT/config" \
XDG_DATA_HOME="$OUT/data" \
XDG_CACHE_HOME="$OUT/cache" \
  timeout 180 opencode run --model coda/coda-gateway "$PROMPT" \
  </dev/null >"$OUT/transcript.txt" 2>"$OUT/stderr.log"
finish "$OUT/transcript.txt"
opencode --version >"$OUT/version.txt" 2>&1 || true
