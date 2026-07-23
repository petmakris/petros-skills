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

expected_fp() {
  # Must mirror server.code_fingerprint() so a running server built from a
  # different tree state (old install dir after a plugin update, or edited
  # files) reads as outdated and gets restarted.
  python3 - "$PLUGIN_ROOT" <<'PY'
import hashlib, sys
from pathlib import Path
root = Path(sys.argv[1]) / "skills"
h = hashlib.sha1()
for p in sorted(list(root.rglob("*.py")) + list(root.rglob("*.sh"))):
    if "__pycache__" in p.parts or "tests" in p.parts:
        continue
    try:
        st = p.stat()
    except OSError:
        continue
    h.update(f"{p.relative_to(root)}:{st.st_mtime_ns}:{st.st_size}".encode())
print(h.hexdigest()[:12])
PY
}

is_healthy() {
  local url="$1"
  local body
  body="$(curl -sf --max-time 1 "$url/health" 2>/dev/null || true)"
  [[ "$body" == *"$BANNER"* ]] || return 1
  # Old servers (pre-fingerprint) send no fp= token: treat them as outdated
  # so they get replaced by a fingerprinted build exactly once.
  local fp
  fp="$(sed -n 's/.*fp=\([0-9a-f]*\).*/\1/p' <<<"$body")"
  [[ -n "$fp" && "$fp" == "$(expected_fp)" ]]
}

kill_recorded_server() {
  # Best-effort: only kill the recorded pid when it is still our module,
  # never an unrelated process that recycled the pid.
  local pid_file="$STATE_DIR/server.pid"
  [[ -f "$pid_file" ]] || return 0
  local pid
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  [[ -n "$pid" ]] || return 0
  if ps -p "$pid" -o command= 2>/dev/null | grep -q "$MODULE"; then
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      ps -p "$pid" >/dev/null 2>&1 || break
      sleep 0.1
    done
  fi
  rm -f "$pid_file"
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
break_stale_lock() {
  # A SIGKILLed lock holder skips its EXIT trap and leaves the lock forever;
  # without this, every future waiter times out and proceeds WITHOUT the
  # lock — two servers, split registries. Age-break: a legitimate startup
  # holds the lock for ~5s max, so >60s means a dead holder.
  local age now mt
  now="$(date +%s)"
  mt="$(stat -f %m "$LOCK_DIR" 2>/dev/null || stat -c %Y "$LOCK_DIR" 2>/dev/null || echo "$now")"
  age=$((now - mt))
  if [[ "$age" -gt 60 ]]; then
    rm -rf "$LOCK_DIR" 2>/dev/null || true
  fi
}
have_lock=0
for _ in $(seq 1 120); do
  if mkdir "$LOCK_DIR" 2>/dev/null; then have_lock=1; break; fi
  if healthy_now; then exit 0; fi
  break_stale_lock
  sleep 0.1
done
if [[ "$have_lock" == 1 ]]; then
  trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT
fi

# Double-checked locking: a peer may have started it while we waited.
if healthy_now; then
  exit 0
fi

# An unhealthy answer can still be a LIVE server running outdated code
# (banner matches, fingerprint doesn't). It holds a port and server.json —
# stop it before starting the replacement.
kill_recorded_server

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
