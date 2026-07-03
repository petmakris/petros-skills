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
assert_eq "commands,mesh_meta,sessions,task_sessions,tasks," "$tables" "init creates tables"
mode=$(sqlite3 "$MESH_DB" "PRAGMA journal_mode;")
assert_eq "wal" "$mode" "init sets WAL"
cols=$(sqlite3 "$MESH_DB" "PRAGMA table_info(sessions);")
assert_eq "1" "$(printf "%s" "$cols" | grep -c '|label|')" "sessions has label column"
assert_eq "0" "$(printf "%s" "$cols" | grep -c '|ticket|')" "sessions has no ticket column"
paused=$(mesh_is_paused)
assert_eq "0" "$paused" "init seeds paused=0"
mesh_init  # idempotent
cnt=$(sqlite3 "$MESH_DB" "SELECT count(*) FROM mesh_meta WHERE key='paused';")
assert_eq "1" "$cnt" "init idempotent (no duplicate meta)"

# --- Task 2 ---
sid1=$(mesh_register "/tmp/wt/PMP-211" "PMP-211-foo" "PMP-211" 4242)
rows=$(sqlite3 "$MESH_DB" "SELECT count(*) FROM sessions;")
assert_eq "1" "$rows" "register inserts one row"
got_label=$(sqlite3 "$MESH_DB" "SELECT label FROM sessions WHERE cwd='/tmp/wt/PMP-211';")
assert_eq "PMP-211" "$got_label" "register stores label"
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
# a second session on another label, for broadcast + isolation tests
sidB=$(mesh_register "/tmp/wt/PMP-238" "PMP-238-bar" "PMP-238" 5555)
id1=$(mesh_dispatch "PMP-211" "shell" "git status" "master-1")
tgt=$(sqlite3 "$MESH_DB" "SELECT target FROM commands WHERE id=$id1;")
assert_eq "$sid1" "$tgt" "dispatch resolves label to session_id"
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

# --- Task 8: dispatch resolves non-standard label names (e.g. reporting-openapi) ---
sqlite3 "$MESH_DB" "DELETE FROM commands;"
sidR=$(mesh_register "/tmp/wt/reporting" "main" "reporting-openapi" $$)
cidR=$(mesh_dispatch "reporting-openapi" "shell" "echo hi" "master-1")
assert_eq "$sidR" "$(sqlite3 "$MESH_DB" "SELECT target FROM commands WHERE id=$cidR;")" "word-label resolves to session_id"

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

# --- Task 12: Layer 2 task manager (add/list/assign/one-active/status/done) ---
sqlite3 "$MESH_DB" "DELETE FROM tasks; DELETE FROM task_sessions;"
mesh_task_add "oauth" "Refactor OAuth" "make it nice" >/dev/null
assert_eq "todo" "$(sqlite3 "$MESH_DB" "SELECT status FROM tasks WHERE slug='oauth';")" "task_add creates a todo task"
mesh_task_add "oauth" "dup" >/dev/null 2>&1
assert_eq "1" "$?" "task_add rejects a duplicate slug"
assert_eq "1" "$(sqlite3 "$MESH_DB" "SELECT count(*) FROM tasks;")" "duplicate add did not insert a second row"
# task_list emits valid JSON containing the task
mesh_task_list | python3 -m json.tool >/dev/null 2>&1
assert_eq "0" "$?" "task_list emits valid JSON"
assert_eq "1" "$(mesh_task_list | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))')" "task_list returns one task"
assert_eq "0" "$(mesh_task_list done | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))')" "task_list status filter excludes non-matching"
# assign a real session (reuse sidR = reporting-openapi, alive from Task 8/9)
mesh_task_assign "oauth" "reporting-openapi" >/dev/null
assert_eq "$sidR" "$(sqlite3 "$MESH_DB" "SELECT session_id FROM task_sessions WHERE task_slug='oauth';")" "task_assign links resolved session"
assert_eq "1" "$(mesh_task_get oauth | python3 -c 'import sys,json; print(len(json.load(sys.stdin)[0]["sessions"]))')" "task_get embeds the assigned session"
# one active task per session: second task rejects the same session
mesh_task_add "docs" "Docs" >/dev/null
mesh_task_assign "docs" "reporting-openapi" >/dev/null 2>&1
assert_eq "2" "$?" "task_assign rejects a session already active elsewhere"
mesh_task_assign "docs" "reporting-openapi" lead force >/dev/null 2>&1
assert_eq "0" "$?" "task_assign force reassigns"
assert_eq "docs" "$(sqlite3 "$MESH_DB" "SELECT task_slug FROM task_sessions WHERE session_id='$sidR';")" "force moved the session to the new task"
# unassign
mesh_task_unassign "docs" "reporting-openapi"
assert_eq "0" "$(sqlite3 "$MESH_DB" "SELECT count(*) FROM task_sessions WHERE session_id='$sidR';")" "task_unassign removes the link"
# status validation + done
mesh_task_set_status "oauth" "bogus" >/dev/null 2>&1
assert_eq "1" "$?" "task_set_status rejects an invalid status"
mesh_task_set_status "oauth" "in_progress"
assert_eq "in_progress" "$(sqlite3 "$MESH_DB" "SELECT status FROM tasks WHERE slug='oauth';")" "task_set_status updates status"
mesh_task_done "oauth"
assert_eq "done" "$(sqlite3 "$MESH_DB" "SELECT status FROM tasks WHERE slug='oauth';")" "task_done marks the task done"
# Layer 1 stays task-agnostic: commands has no task column
assert_eq "0" "$(sqlite3 "$MESH_DB" "PRAGMA table_info(commands);" | grep -c '|task')" "commands table has no task_* column"

# --- Task 13: v2 -> v3 migration adds the task tables, preserves data ---
V2DB="$(mktemp -u).db"
sqlite3 "$V2DB" "CREATE TABLE sessions(session_id TEXT PRIMARY KEY, label TEXT, cwd TEXT NOT NULL UNIQUE, branch TEXT, pid INTEGER, status TEXT, current_task TEXT, started_at TEXT, last_seen TEXT);
CREATE TABLE commands(id INTEGER PRIMARY KEY);
CREATE TABLE mesh_meta(key TEXT PRIMARY KEY, value TEXT);
INSERT INTO mesh_meta VALUES('schema_version','2'),('paused','0');
INSERT INTO sessions VALUES('u1','keeper','/tmp/wt/keep','main',1,'idle',NULL,'t','t');"
( MESH_DB="$V2DB"; mesh_migrate )
assert_eq "3" "$(sqlite3 "$V2DB" "SELECT value FROM mesh_meta WHERE key='schema_version';")" "migrate bumps v2 -> v3"
assert_eq "tasks,task_sessions" "$(sqlite3 "$V2DB" "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('tasks','task_sessions') ORDER BY name DESC;" | paste -sd, -)" "migrate creates the task tables"
assert_eq "keeper" "$(sqlite3 "$V2DB" "SELECT label FROM sessions WHERE session_id='u1';")" "migrate preserves existing session data"
( MESH_DB="$V2DB"; mesh_migrate )   # idempotent
assert_eq "3" "$(sqlite3 "$V2DB" "SELECT value FROM mesh_meta WHERE key='schema_version';")" "migrate is idempotent"
rm -f "$V2DB" "$V2DB-wal" "$V2DB-shm"

echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
