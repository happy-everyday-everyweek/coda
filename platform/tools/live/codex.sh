#!/usr/bin/env bash
# Codex CLI：OpenAI Responses + JSONL 事件。
AGENT=codex
source "$(dirname "$0")/lib.sh"
install_npm @openai/codex
start_gateway
WORK="$(mktemp -d)"
CODEX_HOME="$OUT/codex-home"
mkdir -p "$CODEX_HOME"
cat >"$CODEX_HOME/config.toml" <<EOF
model = "gpt-5-codex"
model_provider = "coda"
[model_providers.coda]
name = "Coda Gateway"
base_url = "$GATEWAY_URL/v1"
env_key = "OPENAI_API_KEY"
wire_api = "responses"
EOF
cd "$WORK"
CODEX_HOME="$CODEX_HOME" HOME="$OUT/home" OPENAI_API_KEY=test-token \
  timeout 180 codex exec --json --skip-git-repo-check --sandbox danger-full-access "$PROMPT" \
  </dev/null >"$OUT/transcript.jsonl" 2>"$OUT/stderr.log"
finish "$OUT/transcript.jsonl"
codex --version >"$OUT/version.txt" 2>&1 || true
