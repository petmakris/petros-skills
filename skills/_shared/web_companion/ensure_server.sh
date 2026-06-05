#!/usr/bin/env bash
# Shared idempotent launcher.  Each skill ships a thin wrapper that exports
# SKILL, MODULE, BANNER, then sources this file.
#
# Required env:
#   SKILL    — short skill name (e.g. "annotate", "interactive-review")
#   MODULE   — Python module path (e.g. "skills.annotate.server")
#   BANNER   — string that /health must contain (e.g. "annotate-server v1")
#   PLUGIN_ROOT — absolute path to the plugin root that contains "skills/"
#
# Exit codes: 0 on success, non-zero if the server could not be started.

set -euo pipefail

: "${SKILL:?SKILL must be set}"
: "${MODULE:?MODULE must be set}"
: "${BANNER:?BANNER must be set}"
: "${PLUGIN_ROOT:?PLUGIN_ROOT must be set}"

HOME_DIR="${HOME:?HOME must be set}"
STATE_DIR="$HOME_DIR/.claude/$SKILL"
INFO_FILE="$STATE_DIR/server.json"

mkdir -p "$STATE_DIR"

is_healthy() {
  local url="$1"
  local body
  body="$(curl -sf --max-time 1 "$url/health" 2>/dev/null || true)"
  [[ "$body" == *"$BANNER"* ]]
}

read_url() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["url"])' "$1" 2>/dev/null || true
}

if [[ -f "$INFO_FILE" ]]; then
  url="$(read_url "$INFO_FILE")"
  if [[ -n "$url" ]] && is_healthy "$url"; then
    exit 0
  fi
fi

LOG="$STATE_DIR/server.log"
: > "$LOG"
(
  cd "$PLUGIN_ROOT"
  PYTHONPATH="$PLUGIN_ROOT" nohup python3 -m "$MODULE" >>"$LOG" 2>&1 &
  echo $! > "$STATE_DIR/server.pid"
)

for _ in $(seq 1 50); do
  if [[ -f "$INFO_FILE" ]]; then
    url="$(read_url "$INFO_FILE")"
    if [[ -n "$url" ]] && is_healthy "$url"; then
      exit 0
    fi
  fi
  sleep 0.1
done

echo "ensure_server[$SKILL]: server did not become healthy within 5s. See $LOG" >&2
exit 1
