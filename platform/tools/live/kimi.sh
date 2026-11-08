#!/usr/bin/env bash
# Kimi Code CLI：通过自定义 registry 导入本地 provider。
AGENT=kimi
source "$(dirname "$0")/lib.sh"
install_npm @moonshot-ai/kimi-code
start_gateway
WORK="$(mktemp -d)"
KIMI_HOME="$OUT/kimi-home"
mkdir -p "$KIMI_HOME"
HOME="$OUT/home" KIMI_CODE_HOME="$KIMI_HOME" \
  kimi provider add "$GATEWAY_URL/api.json" >"$OUT/provider-add.log" 2>&1
CONFIG="$KIMI_HOME/config.toml"
python3 - "$CONFIG" <<'PY'
import sys
from pathlib import Path
path = Path(sys.argv[1])
text = path.read_text()
text = text.replace('api_key = ""', 'api_key = "test-token"', 1)
if "default_model" not in text:
    text = 'default_model = "coda/coda-gateway"\n' + text
path.write_text(text)
PY
cd "$WORK"
HOME="$OUT/home" KIMI_CODE_HOME="$KIMI_HOME" \
  timeout 180 kimi -p "$PROMPT" --output-format stream-json \
  </dev/null >"$OUT/transcript.jsonl" 2>"$OUT/stderr.log"
finish "$OUT/transcript.jsonl"
kimi --version >"$OUT/version.txt" 2>&1 || true

# 适配器级：用 platform/adapters/kimi-headless 驱动真实 CLI
HOME="$OUT/home" node "$ROOT/platform/tools/live/adapter-smoke.mjs" \
  --agent kimi --gateway "$GATEWAY_URL" --kimi-home "$KIMI_HOME" --out "$OUT/adapter.json"
