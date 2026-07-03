#!/usr/bin/env bash
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export MESH_DB="$(mktemp /tmp/mesh_test.XXXXXX).db"
export MESH_HOME="$HERE/.."
# shellcheck source=/dev/null
source "$HERE/../mesh.sh"

PASS=0; FAIL=0
assert_eq() { # expected actual msg
  if [ "$1" = "$2" ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); echo "FAIL: $3 (expected [$1] got [$2])"; fi
}
cleanup() { rm -f "$MESH_DB" "$MESH_DB-wal" "$MESH_DB-shm"; }
trap cleanup EXIT

# --- Task 1 ---
mesh_init
tables=$(sqlite3 "$MESH_DB" "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;" | tr '\n' ',')
assert_eq "commands,mesh_meta,sessions," "$tables" "init creates tables"
mode=$(sqlite3 "$MESH_DB" "PRAGMA journal_mode;")
assert_eq "wal" "$mode" "init sets WAL"
paused=$(mesh_is_paused)
assert_eq "0" "$paused" "init seeds paused=0"
mesh_init  # idempotent
cnt=$(sqlite3 "$MESH_DB" "SELECT count(*) FROM mesh_meta WHERE key='paused';")
assert_eq "1" "$cnt" "init idempotent (no duplicate meta)"

# --- Task 2 ---
sid1=$(mesh_register "/tmp/wt/PMP-211" "PMP-211-foo" "PMP-211" 4242)
rows=$(sqlite3 "$MESH_DB" "SELECT count(*) FROM sessions;")
assert_eq "1" "$rows" "register inserts one row"
got_ticket=$(sqlite3 "$MESH_DB" "SELECT ticket FROM sessions WHERE cwd='/tmp/wt/PMP-211';")
assert_eq "PMP-211" "$got_ticket" "register stores ticket"
assert_eq "$sid1" "$(mesh_session_id /tmp/wt/PMP-211)" "session_id round-trips by cwd"
# re-register same cwd: replaces, does not duplicate, keeps same identity semantics.
# Use an alive pid ($$) so the reaper (auto-run on dispatch/board) treats sid1 as
# a live worker and leaves its in-flight command running.
sid2=$(mesh_register "/tmp/wt/PMP-211" "PMP-211-foo" "PMP-211" $$)
rows2=$(sqlite3 "$MESH_DB" "SELECT count(*) FROM sessions;")
assert_eq "1" "$rows2" "re-register same cwd does not duplicate"
pid2=$(sqlite3 "$MESH_DB" "SELECT pid FROM sessions WHERE cwd='/tmp/wt/PMP-211';")
assert_eq "$$" "$pid2" "re-register updates pid"

# --- Task 3 (heartbeat only; mesh_set_status removed during cleanup) ---
sqlite3 "$MESH_DB" "UPDATE sessions SET last_seen='2000-01-01T00:00:00Z' WHERE session_id='$sid1';"
mesh_heartbeat "$sid1"
new=$(sqlite3 "$MESH_DB" "SELECT last_seen FROM sessions WHERE session_id='$sid1';")
assert_eq "1" "$([ "$new" != "2000-01-01T00:00:00Z" ] && echo 1 || echo 0)" "heartbeat bumps last_seen"

# --- Task 4 ---
# a second session on another ticket, for broadcast + isolation tests
sidB=$(mesh_register "/tmp/wt/PMP-238" "PMP-238-bar" "PMP-238" 5555)
id1=$(mesh_dispatch "PMP-211" "shell" "git status" "master-1")
tgt=$(sqlite3 "$MESH_DB" "SELECT target FROM commands WHERE id=$id1;")
assert_eq "$sid1" "$tgt" "dispatch resolves ticket to session_id"
stt=$(sqlite3 "$MESH_DB" "SELECT state||'|'||kind FROM commands WHERE id=$id1;")
assert_eq "pending|shell" "$stt" "dispatched command is pending"
before=$(sqlite3 "$MESH_DB" "SELECT count(*) FROM commands;")
mesh_dispatch "*" "prompt" "report status" "master-1" >/dev/null
after=$(sqlite3 "$MESH_DB" "SELECT count(*) FROM commands;")
assert_eq "2" "$((after-before))" "broadcast fans out one row per session"

# --- Task 5 ---
# clear queue, enqueue exactly one for sid1
sqlite3 "$MESH_DB" "DELETE FROM commands;"
cid=$(mesh_dispatch "PMP-211" "shell" "echo hi" "master-1")
claim1=$(mesh_claim "$sid1")
assert_eq "$cid|shell|echo hi" "$claim1" "claim returns id|kind|payload"
rstate=$(sqlite3 "$MESH_DB" "SELECT state FROM commands WHERE id=$cid;")
assert_eq "running" "$rstate" "claim marks running"
claim2=$(mesh_claim "$sid1")
assert_eq "" "$claim2" "second claim finds nothing (no double-run)"
# isolation: a command for sidB is not claimable by sid1
mesh_dispatch "PMP-238" "shell" "echo other" "master-1" >/dev/null
claim3=$(mesh_claim "$sid1")
assert_eq "" "$claim3" "claim respects target isolation"
mesh_complete "$cid" "ok" "hi"
done_row=$(sqlite3 "$MESH_DB" "SELECT state||'|'||exit_state||'|'||output FROM commands WHERE id=$cid;")
assert_eq "done|ok|hi" "$done_row" "complete records state/exit/output"

# --- Task 6 ---
mesh_pause
assert_eq "1" "$(mesh_is_paused)" "pause sets paused=1"
mesh_resume
assert_eq "0" "$(mesh_is_paused)" "resume sets paused=0"

# --- Task 7 ---
board=$(mesh_board)
assert_eq "1" "$(printf "%s\n" "$board" | awk '/^$/{exit}1' | grep -c 'PMP-211')" "board lists PMP-211"
# force a stale session and assert it is flagged stale
sqlite3 "$MESH_DB" "UPDATE sessions SET last_seen='2000-01-01T00:00:00Z' WHERE session_id='$sidB';"
staleline=$(mesh_board | awk -F'\t' '$1=="PMP-238"{print $2}')
assert_eq "stale" "$staleline" "board flags old last_seen as stale"
mesh_heartbeat "$sidB"
freshline=$(mesh_board | awk -F'\t' '$1=="PMP-238"{print $2}')
assert_eq "alive" "$freshline" "heartbeat restores alive"

# --- Task 8: dispatch resolves non-standard ticket names (e.g. reporting-openapi) ---
sqlite3 "$MESH_DB" "DELETE FROM commands;"
sidR=$(mesh_register "/tmp/wt/reporting" "main" "reporting-openapi" $$)
cidR=$(mesh_dispatch "reporting-openapi" "shell" "echo hi" "master-1")
assert_eq "$sidR" "$(sqlite3 "$MESH_DB" "SELECT target FROM commands WHERE id=$cidR;")" "word-ticket resolves to session_id"

# --- Task 9: collect + ack (master inbox) ---
sqlite3 "$MESH_DB" "DELETE FROM commands;"
cidC=$(mesh_dispatch "reporting-openapi" "shell" "echo x" "master-1")
mesh_claim "$sidR" >/dev/null
mesh_complete "$cidC" "ok" "the output"
assert_eq "1" "$(mesh_collect | grep -c 'reporting-openapi')" "collect shows a completed command"
assert_eq "the output" "$(mesh_cmd_output "$cidC")" "cmd_output returns the output"
assert_eq "" "$(mesh_collect)" "collect does not re-show an acked command"

# --- Task 10: reaper recovers a dead worker's running command; complete is guarded ---
sqlite3 "$MESH_DB" "DELETE FROM commands;"
sleep 0.1 & deadpid=$!; wait "$deadpid" 2>/dev/null   # guaranteed-dead pid
sidD=$(mesh_register "/tmp/wt/dead" "main" "DEAD-1" "$deadpid")
cidD=$(mesh_dispatch "DEAD-1" "shell" "echo work" "master-1")
sqlite3 "$MESH_DB" "UPDATE commands SET state='running', picked_at='$(_now)' WHERE id=$cidD;"
mesh_reap >/dev/null
assert_eq "pending" "$(mesh_cmd_state "$cidD")" "reaper re-queues a dead worker's running command"
mesh_complete "$cidD" "ok" "late"   # command is 'pending' now -> must be a no-op
assert_eq "pending" "$(mesh_cmd_state "$cidD")" "complete is guarded to state='running'"

# --- Task 11: alive worker within lease left running; past lease reaped ---
sqlite3 "$MESH_DB" "DELETE FROM commands;"
sidL=$(mesh_register "/tmp/wt/live" "main" "LIVE-1" $$)
cidL=$(mesh_dispatch "LIVE-1" "shell" "echo work" "master-1")
sqlite3 "$MESH_DB" "UPDATE commands SET state='running', picked_at='$(_now)' WHERE id=$cidL;"
mesh_reap >/dev/null
assert_eq "running" "$(mesh_cmd_state "$cidL")" "alive worker within lease is left running"
MESH_LEASE_SECS=1
sqlite3 "$MESH_DB" "UPDATE commands SET picked_at='2000-01-01T00:00:00Z' WHERE id=$cidL;"
mesh_reap >/dev/null
assert_eq "pending" "$(mesh_cmd_state "$cidL")" "alive worker past lease is reaped"
MESH_LEASE_SECS=900

echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
