#!/usr/bin/env bash
# Session Mesh shell library. All SQLite operations live here.
#
# Code (this script + schema.sql) ships inside the petros-skills plugin.
# State (the DB + live/ watcher files) is machine-local under MESH_HOME and
# is never committed. MESH_CODE_DIR resolves to wherever this script lives so
# mesh_init can find schema.sql alongside it, independent of MESH_HOME.
# Resolve the path to THIS script across shells: bash populates BASH_SOURCE,
# zsh leaves it empty and puts the sourced-file path in $0 (FUNCTION_ARGZERO).
_mesh_src="${BASH_SOURCE[0]:-$0}"
MESH_CODE_DIR="$(cd -- "$(dirname -- "$_mesh_src")" && pwd)"
unset _mesh_src
: "${MESH_HOME:=$HOME/.claude/session-mesh}"
: "${MESH_DB:=$MESH_HOME/mesh.db}"
: "${MESH_LEASE_SECS:=10800}"   # 3h: a still-alive worker's 'running' command older than this is presumed abandoned (dead workers recover instantly regardless)

_sql() { sqlite3 "$MESH_DB" "$@"; }
_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
_esc() { printf "%s" "${1:-}" | sed "s/'/''/g"; }   # escape single quotes for SQL literals

mesh_init() {
  mkdir -p "$(dirname "$MESH_DB")" "$MESH_HOME/live"
  sqlite3 "$MESH_DB" < "$MESH_CODE_DIR/schema.sql"
  mesh_migrate
}

mesh_migrate() {  # idempotent schema upgrades; safe to run repeatedly
  _sql "ALTER TABLE commands ADD COLUMN ack_at TEXT;" 2>/dev/null || true
}

mesh_is_paused() {
  local v; v="$(_sql "SELECT value FROM mesh_meta WHERE key='paused';")"
  printf "%s" "${v:-0}"
}

_mesh_live_file() { printf "%s/live/%s.id" "$MESH_HOME" "$(printf "%s" "$1" | shasum | cut -c1-16)"; }

mesh_session_id() { # cwd
  local f; f="$(_mesh_live_file "$1")"
  [ -f "$f" ] && cat "$f" || true
}

mesh_register() { # cwd branch ticket pid
  local cwd="$1" branch="${2:-}" ticket="${3:-}" pid="${4:-}"
  local f sid now; f="$(_mesh_live_file "$cwd")"; now="$(_now)"
  if [ -f "$f" ]; then sid="$(cat "$f")"; else sid="$(uuidgen)"; fi
  mkdir -p "$MESH_HOME/live"; printf "%s" "$sid" > "$f"
  _sql <<SQL
DELETE FROM sessions WHERE cwd='$(_esc "$cwd")';
INSERT INTO sessions(session_id,ticket,cwd,branch,pid,status,current_task,started_at,last_seen)
VALUES ('$(_esc "$sid")','$(_esc "$ticket")','$(_esc "$cwd")','$(_esc "$branch")',
        ${pid:-NULL},'idle',NULL,'$now','$now');
SQL
  printf "%s" "$sid"
}

mesh_heartbeat() { # session_id
  _sql "UPDATE sessions SET last_seen='$(_now)' WHERE session_id='$(_esc "$1")';"
}

_mesh_resolve_targets() { # target -> newline-separated session_ids
  local t="$1"
  if [ "$t" = "*" ]; then
    _sql "SELECT session_id FROM sessions;"
    return
  fi
  # Match a live session by ticket OR by session_id (robust to any ticket
  # naming, e.g. "reporting-openapi" as well as "PMP-211"). Only fall back to
  # treating the target as a literal session_id when nothing matches.
  local rows
  rows="$(_sql "SELECT session_id FROM sessions WHERE ticket='$(_esc "$t")' OR session_id='$(_esc "$t")';")"
  if [ -n "$rows" ]; then printf "%s\n" "$rows"; else printf "%s\n" "$t"; fi
}

_mesh_pid_alive() { # pid -> returns 0 if the process is alive
  local p="$1"
  [ -n "$p" ] && [ "$p" != "NULL" ] && kill -0 "$p" 2>/dev/null
}

mesh_reap() { # re-queue 'running' commands whose worker is dead or whose lease expired
  local rows reaped="" id pid age
  rows="$(_sql "SELECT c.id||'|'||
                       COALESCE((SELECT s.pid FROM sessions s WHERE s.session_id=c.target),'')||'|'||
                       CAST((julianday('now')-julianday(c.picked_at))*86400 AS INTEGER)
                FROM commands c WHERE c.state='running';")"
  [ -z "$rows" ] && return 0
  while IFS='|' read -r id pid age; do
    [ -z "$id" ] && continue
    if _mesh_pid_alive "$pid"; then
      # worker alive: only reap if it has held the command past the lease
      { [ -n "$age" ] && [ "$age" -gt "$MESH_LEASE_SECS" ]; } || continue
    fi
    # dead worker, or alive-but-lease-expired -> return the command to the queue
    _sql "UPDATE commands SET state='pending', picked_at=NULL WHERE id=$id AND state='running';"
    reaped="$reaped $id"
  done <<EOF
$rows
EOF
  reaped="$(printf "%s" "$reaped" | sed 's/^ *//')"
  [ -n "$reaped" ] && printf "%s\n" "$reaped"
  return 0
}

mesh_dispatch() { # target kind payload created_by
  local target="$1" kind="$2" payload="$3" by="${4:-}" now sid
  mesh_reap >/dev/null 2>&1   # recover orphaned work before queuing more
  now="$(_now)"
  _mesh_resolve_targets "$target" | while IFS= read -r sid; do
    [ -z "$sid" ] && continue
    _sql "INSERT INTO commands(target,kind,payload,state,created_by,created_at)
          VALUES ('$(_esc "$sid")','$(_esc "$kind")','$(_esc "$payload")','pending','$(_esc "$by")','$now');
          SELECT last_insert_rowid();"
  done
}

mesh_claim() { # session_id -> "id|kind|payload" or empty
  local sid; sid="$(_esc "$1")"
  _sql <<SQL
BEGIN IMMEDIATE;
UPDATE commands SET state='running', picked_at='$(_now)'
WHERE id=(SELECT id FROM commands WHERE target='$sid' AND state='pending' ORDER BY id LIMIT 1)
RETURNING id||'|'||kind||'|'||payload;
COMMIT;
SQL
}

mesh_complete() { # command_id exit_state output
  # Guard on state='running': if this command was reaped back to 'pending'
  # (worker presumed dead) and re-claimed elsewhere, a late completion by the
  # original worker becomes a no-op instead of clobbering the new run.
  _sql "UPDATE commands SET state='done', exit_state='$(_esc "$2")', output='$(_esc "$3")', done_at='$(_now)'
        WHERE id=$1 AND state='running';"
}

mesh_pause()  { _sql "UPDATE mesh_meta SET value='1' WHERE key='paused';"; }
mesh_resume() { _sql "UPDATE mesh_meta SET value='0' WHERE key='paused';"; }

mesh_board() {
  mesh_reap >/dev/null 2>&1   # recover orphaned work so the board reflects truth
  _sql -separator $'\t' <<SQL
SELECT ticket,
       CASE WHEN last_seen >= strftime('%Y-%m-%dT%H:%M:%SZ','now','-90 seconds')
            THEN 'alive' ELSE 'stale' END,
       status, COALESCE(current_task,''), cwd
FROM sessions ORDER BY ticket;
SQL
  printf "\n"
  _sql -separator $'\t' <<SQL
SELECT c.id,
       COALESCE((SELECT ticket FROM sessions s WHERE s.session_id=c.target), c.target),
       c.kind, c.state
FROM commands c ORDER BY c.id DESC LIMIT 20;
SQL
}

# ---------------------------------------------------------------------------
# Tier 1: event-driven background watchers.
# The heavy waiting (heartbeat + claim / result-check) runs in plain shell,
# launched detached via the harness's background-bash. It BLOCKS until there
# is real work, then exits — which re-invokes the session model exactly once.
# No model tokens are spent while idle; nothing pollutes the session.
# ---------------------------------------------------------------------------

mesh_watch_worker() { # sid [interval] -> on claim prints "id|kind|payload" and exits 0
  local sid="$1" interval="${2:-5}"
  [ -z "$sid" ] && { echo "mesh_watch_worker: missing sid" >&2; return 2; }
  while :; do
    mesh_heartbeat "$sid"
    if [ "$(mesh_is_paused)" != "1" ]; then
      local claim; claim="$(mesh_claim "$sid")"
      if [ -n "$claim" ]; then printf '%s\n' "$claim"; return 0; fi
    fi
    sleep "$interval"
  done
}

mesh_watch_collect() { # [interval] -> exits 0 as soon as >=1 unacked done command exists
  local interval="${1:-5}"
  while :; do
    local n; n="$(_sql "SELECT COUNT(*) FROM commands WHERE state='done' AND ack_at IS NULL;")"
    [ "${n:-0}" -gt 0 ] && return 0
    sleep "$interval"
  done
}

mesh_collect() { # print "id \t ticket \t exit_state" per unacked-done command, then ack them
  local rows ids
  rows="$(_sql -separator $'\t' <<SQL
SELECT c.id,
       COALESCE((SELECT ticket FROM sessions s WHERE s.session_id=c.target), c.target),
       COALESCE(c.exit_state,'')
FROM commands c WHERE c.state='done' AND c.ack_at IS NULL ORDER BY c.id;
SQL
)"
  [ -z "$rows" ] && return 0
  printf '%s\n' "$rows"
  ids="$(printf '%s\n' "$rows" | cut -f1 | paste -sd, -)"
  _sql "UPDATE commands SET ack_at='$(_now)' WHERE id IN ($ids);"
}

mesh_cmd_state()  { _sql "SELECT state FROM commands WHERE id=$1;"; }
mesh_cmd_output() { _sql "SELECT COALESCE(output,'') FROM commands WHERE id=$1;"; }
