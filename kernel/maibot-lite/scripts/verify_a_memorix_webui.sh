#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DASHBOARD_ROOT="$REPO_ROOT/dashboard"
OUTPUT_DIR="${MAIBOT_UI_SNAPSHOT_DIR:-$REPO_ROOT/tmp/ui-snapshots/a_memorix-electron}"
PYTHON_BIN="${MAIBOT_PYTHON_BIN:-$REPO_ROOT/../../.venv/bin/python}"
ELECTRON_BIN="${MAIBOT_ELECTRON_BIN:-$DASHBOARD_ROOT/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron}"
DRIVER_SCRIPT="$DASHBOARD_ROOT/scripts/a_memorix_electron_validate.cjs"
BACKEND_SCRIPT="$REPO_ROOT/scripts/run_a_memorix_webui_backend.py"
BACKEND_HOST="${MAIBOT_WEBUI_HOST:-127.0.0.1}"
BACKEND_PORT="${MAIBOT_WEBUI_PORT:-8001}"
DASHBOARD_HOST="${MAIBOT_DASHBOARD_HOST:-127.0.0.1}"
DASHBOARD_PORT="${MAIBOT_DASHBOARD_PORT:-7999}"
BACKEND_URL="http://${BACKEND_HOST}:${BACKEND_PORT}"
DASHBOARD_URL="http://${DASHBOARD_HOST}:${DASHBOARD_PORT}"
REUSE_SERVICES="${MAIBOT_UI_REUSE_SERVICES:-0}"

BACKEND_PID=""
DASHBOARD_PID=""

mkdir -p "$OUTPUT_DIR"

cleanup() {
  local exit_code=$?
  if [[ -n "$DASHBOARD_PID" ]] && kill -0 "$DASHBOARD_PID" >/dev/null 2>&1; then
    kill "$DASHBOARD_PID" >/dev/null 2>&1 || true
    wait "$DASHBOARD_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
    kill "$BACKEND_PID" >/dev/null 2>&1 || true
    wait "$BACKEND_PID" >/dev/null 2>&1 || true
  fi
  exit "$exit_code"
}

trap cleanup EXIT

wait_for_url() {
  local url="$1"
  local label="$2"
  local timeout="${3:-60}"
  local started_at
  started_at="$(date +%s)"
  while true; do
    if env -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY NO_PROXY=127.0.0.1,localhost \
      curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    if (( "$(date +%s)" - started_at >= timeout )); then
      echo "Timed out waiting for ${label}: ${url}" >&2
      return 1
    fi
    sleep 1
  done
}

if [[ "$REUSE_SERVICES" != "1" ]]; then
  if ! env -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY NO_PROXY=127.0.0.1,localhost \
    curl -fsS "${BACKEND_URL}/api/webui/health" >/dev/null 2>&1; then
    (
      cd "$REPO_ROOT"
      WEBUI_HOST="$BACKEND_HOST" WEBUI_PORT="$BACKEND_PORT" "$PYTHON_BIN" "$BACKEND_SCRIPT"
    ) >"$OUTPUT_DIR/backend.log" 2>&1 &
    BACKEND_PID="$!"
    wait_for_url "${BACKEND_URL}/api/webui/health" "MaiBot WebUI backend" 120
  fi

  if ! env -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY NO_PROXY=127.0.0.1,localhost \
    curl -fsS "${DASHBOARD_URL}/auth" >/dev/null 2>&1; then
    (
      cd "$DASHBOARD_ROOT"
      npm run dev -- --host "$DASHBOARD_HOST" --port "$DASHBOARD_PORT"
    ) >"$OUTPUT_DIR/dashboard.log" 2>&1 &
    DASHBOARD_PID="$!"
    wait_for_url "${DASHBOARD_URL}/auth" "dashboard dev server" 120
  fi
fi

env -u ELECTRON_RUN_AS_NODE \
  MAIBOT_DASHBOARD_URL="$DASHBOARD_URL" \
  MAIBOT_UI_SNAPSHOT_DIR="$OUTPUT_DIR" \
  "$ELECTRON_BIN" "$DRIVER_SCRIPT"
