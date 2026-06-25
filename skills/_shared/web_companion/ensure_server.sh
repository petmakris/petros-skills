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

healthy_now() {
  [[ -f "$INFO_FILE" ]] || return 1
  local url
  url="$(read_url "$INFO_FILE")"
  [[ -n "$url" ]] && is_healthy "$url"
}

# Fast path: server already up.
if healthy_now; then
  exit 0
fi

# Serialize startup so two concurrent invocations don't each spawn a server
# (which would bind different ports and split sessions). macOS ships no
# `flock`, so use an atomic, portable mkdir lock. A waiter polls health while
# the lock holder starts the server, and after ~12s of a stuck peer it proceeds
# best-effort rather than hanging.
LOCK_DIR="$STATE_DIR/.startup.lock"
have_lock=0
for _ in $(seq 1 120); do
  if mkdir "$LOCK_DIR" 2>/dev/null; then have_lock=1; break; fi
  if healthy_now; then exit 0; fi
  sleep 0.1
done
if [[ "$have_lock" == 1 ]]; then
  trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT
fi

# Double-checked locking: a peer may have started it while we waited.
if healthy_now; then
  exit 0
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
