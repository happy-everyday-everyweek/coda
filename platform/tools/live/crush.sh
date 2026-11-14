#!/usr/bin/env bash
# Crush：OpenAI 兼容 provider（crush.json）。
AGENT=crush
source "$(dirname "$0")/lib.sh"
install_npm @charmland/crush
start_gateway
WORK="$(mktemp -d)"
mkdir -p "$OUT/home/.config/crush" "$OUT/home/.local/share"
cat >"$OUT/home/.config/crush/crush.json" <<CONFIG
{
  "\$schema": "https://charm.land/crush.json",
  "providers": {
    "coda": {
      "type": "openai",
      "base_url": "$GATEWAY_URL/v1",
      "api_key": "test-token",
      "models": [
        { "id": "coda-gateway", "name": "Coda Gateway", "context_window": 131072, "default_max_tokens": 8192 }
      ]
    }
  }
}
CONFIG
cd "$WORK"
HOME="$OUT/home" XDG_CONFIG_HOME="$OUT/home/.config" XDG_DATA_HOME="$OUT/home/.local/share" timeout 180 crush run -m coda/coda-gateway "$PROMPT" \
  </dev/null >"$OUT/transcript.txt" 2>"$OUT/stderr.log"
if grep -q "CODA_GATEWAY_OK" "$OUT/transcript.txt" 2>/dev/null; then
  finish "$OUT/transcript.txt"
else
  log "stdout 无标记，回退到网关日志判定"
  finish_gateway
fi
crush --version >"$OUT/version.txt" 2>&1 || true
