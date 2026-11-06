#!/usr/bin/env bash

set -euo pipefail

MODE="${1:-pull}"
REMOTE_URL="${2:-https://github.com/A-Dawn/A_memorix.git}"
BRANCH="${3:-MaiBot_branch}"
PREFIX="src/A_memorix"

case "$MODE" in
  add)
    git subtree add --prefix="$PREFIX" "$REMOTE_URL" "$BRANCH" --squash
    ;;
  pull)
    git subtree pull --prefix="$PREFIX" "$REMOTE_URL" "$BRANCH" --squash
    ;;
  *)
    echo "Usage: $0 [add|pull] [remote_url] [branch]" >&2
    exit 2
    ;;
esac
