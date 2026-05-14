#!/usr/bin/env bash
# Idempotent launcher for the annotate web server.
#
# Behavior:
#  - If $HOME/.claude/annotate/server.json points at a process whose /health
#    returns the expected banner, exit 0 immediately (fast no-op path).
#  - Otherwise spawn `python3 -m skills.annotate.server` detached in the
#    background and wait until its /health banner answers, then exit 0.
#
# Exit codes: 0 on success, non-zero if the server could not be started.

set -euo pipefail

BANNER="annotate-server v1"
HOME_DIR="${HOME:?HOME must be set}"
STATE_DIR="$HOME_DIR/.claude/annotate"
INFO_FILE="$STATE_DIR/server.json"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
PLUGIN_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null && pwd)"

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
  PYTHONPATH="$PLUGIN_ROOT" nohup python3 -m skills.annotate.server >>"$LOG" 2>&1 &
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

echo "ensure_server: server did not become healthy within 5s. See $LOG" >&2
exit 1
