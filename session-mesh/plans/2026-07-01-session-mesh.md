# Session Mesh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a SQLite-backed framework of Claude Code skills that lets independently-started sessions register with a master, heartbeat live status, and (Phase 2) receive and autonomously execute commands the master pushes.

**Architecture:** All database logic lives in one tested shell library (`mesh.sh`) driven by the macOS `sqlite3` CLI in WAL mode. Skills under `~/.claude/skills/mesh-*/` are thin wrappers that call `mesh.sh` functions. Workers register + heartbeat; a `/loop 30s /mesh-poll` turn executes queued commands as the session itself (no tty injection). Full-autonomy execution with a global pause switch as an emergency stop.

**Tech Stack:** bash, `sqlite3` CLI (WAL, RETURNING), `uuidgen`, `shasum`, `date` — all stock macOS. No external dependencies.

## Global Constraints

- Platform: macOS; bash; stock `sqlite3` (require >= 3.35 for `RETURNING`), `uuidgen`, `shasum`, `date`.
- No external dependencies, no servers. SQLite file only.
- DB path: `~/.claude/session-mesh/mesh.db`; override for tests via `MESH_DB` env var.
- Library: `~/.claude/session-mesh/mesh.sh`. Schema: `~/.claude/session-mesh/schema.sql`. Per-session runtime id files: `~/.claude/session-mesh/live/<hash>.id`.
- Skills are user-global in `~/.claude/skills/mesh-*/SKILL.md` (project `.claude/skills` does not propagate across git worktrees).
- All timestamps ISO8601 UTC: `strftime('%Y-%m-%dT%H:%M:%SZ','now')` in SQL, `date -u +%Y-%m-%dT%H:%M:%SZ` in shell.
- Execution policy: full autonomy; global pause (`mesh_meta.paused`) is the only stop.
- This is personal tooling under `~/.claude`. NEVER commit to or modify the montblanc repo. `~/.claude` is not a git repo; "Commit" steps below run `git` inside `~/.claude/session-mesh/` only if the implementer has initialized one there — otherwise they are no-ops and may be skipped. Do not create a git repo in `~/.claude` without asking Petros.
- Poll interval: 30s. Staleness threshold: 90s (3× interval).

---

## File Structure

- Create: `~/.claude/session-mesh/schema.sql` — DDL + WAL pragma + meta seed.
- Create: `~/.claude/session-mesh/mesh.sh` — shell library; all DB operations. The tested core.
- Create: `~/.claude/session-mesh/tests/test_mesh.sh` — bash test harness against a temp DB.
- Create: `~/.claude/session-mesh/live/` — runtime dir for per-session id files (created by `mesh_register`).
- Create: `~/.claude/skills/mesh-init/SKILL.md`
- Create: `~/.claude/skills/mesh-register/SKILL.md`
- Create: `~/.claude/skills/mesh-status/SKILL.md`
- Create: `~/.claude/skills/mesh-board/SKILL.md`
- Create: `~/.claude/skills/mesh-dispatch/SKILL.md`
- Create: `~/.claude/skills/mesh-poll/SKILL.md`
- Create: `~/.claude/skills/mesh-pause/SKILL.md`
- Create: `~/.claude/skills/mesh-resume/SKILL.md`
- Create: `~/.claude/session-mesh/README.md` — operator guide + end-to-end smoke test.

`mesh.sh` public functions (identity/interfaces all tasks rely on):
- `mesh_init` → applies schema; idempotent.
- `mesh_register <cwd> <branch> <ticket> <pid>` → mints/reuses session_id for cwd, upserts row, writes live id file; **prints the session_id**.
- `mesh_session_id <cwd>` → prints the session_id recorded for cwd (empty if none).
- `mesh_heartbeat <session_id>` → bumps `last_seen`.
- `mesh_set_status <session_id> <status> <current_task>` → updates status line.
- `mesh_dispatch <target> <kind> <payload> <created_by>` → resolves target (ticket|`*`|session_id), inserts command row(s); **prints inserted id(s)**.
- `mesh_claim <session_id>` → atomically claims oldest pending command for the session; **prints `id|kind|payload`** or nothing.
- `mesh_complete <command_id> <exit_state> <output>` → marks command done with result.
- `mesh_is_paused` → prints `1` or `0`.
- `mesh_pause` / `mesh_resume` → set/clear pause.
- `mesh_board` → prints tab-separated session rows with computed liveness, then recent command rows.

---

### Task 1: Schema + `mesh_init`

**Files:**
- Create: `~/.claude/session-mesh/schema.sql`
- Create: `~/.claude/session-mesh/mesh.sh`
- Test: `~/.claude/session-mesh/tests/test_mesh.sh`

**Interfaces:**
- Consumes: nothing.
- Produces: `mesh_init` (idempotent DB bootstrap); the `_sql`, `_now`, `_esc` private helpers; `MESH_DB` resolution used by every later function.

- [ ] **Step 1: Write the failing test**

Create `~/.claude/session-mesh/tests/test_mesh.sh`:

```bash
#!/usr/bin/env bash
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export MESH_DB="$(mktemp -t mesh_test).db"
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
tables=$(sqlite3 "$MESH_DB" "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;" | tr '\n' ',')
assert_eq "commands,mesh_meta,sessions," "$tables" "init creates tables"
mode=$(sqlite3 "$MESH_DB" "PRAGMA journal_mode;")
assert_eq "wal" "$mode" "init sets WAL"
paused=$(mesh_is_paused)
assert_eq "0" "$paused" "init seeds paused=0"
mesh_init  # idempotent
cnt=$(sqlite3 "$MESH_DB" "SELECT count(*) FROM mesh_meta WHERE key='paused';")
assert_eq "1" "$cnt" "init idempotent (no duplicate meta)"

echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: FAIL — `mesh.sh: No such file` / `mesh_init: command not found`.

- [ ] **Step 3: Write `schema.sql`**

Create `~/.claude/session-mesh/schema.sql`:

```sql
PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS sessions (
  session_id   TEXT PRIMARY KEY,
  ticket       TEXT,
  cwd          TEXT NOT NULL UNIQUE,
  branch       TEXT,
  pid          INTEGER,
  status       TEXT DEFAULT 'idle',
  current_task TEXT,
  started_at   TEXT NOT NULL,
  last_seen    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS commands (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  target     TEXT NOT NULL,
  kind       TEXT NOT NULL,
  payload    TEXT NOT NULL,
  state      TEXT DEFAULT 'pending',
  output     TEXT,
  exit_state TEXT,
  created_by TEXT,
  created_at TEXT NOT NULL,
  picked_at  TEXT,
  done_at    TEXT
);

CREATE TABLE IF NOT EXISTS mesh_meta (key TEXT PRIMARY KEY, value TEXT);
INSERT OR IGNORE INTO mesh_meta(key,value) VALUES ('paused','0');
INSERT OR IGNORE INTO mesh_meta(key,value) VALUES ('schema_version','1');
```

- [ ] **Step 4: Write `mesh.sh` (helpers + `mesh_init` + `mesh_is_paused`)**

Create `~/.claude/session-mesh/mesh.sh`:

```bash
#!/usr/bin/env bash
# Session Mesh shell library. All SQLite operations live here.
: "${MESH_HOME:=$HOME/.claude/session-mesh}"
: "${MESH_DB:=$MESH_HOME/mesh.db}"

_sql() { sqlite3 "$MESH_DB" "$@"; }
_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
_esc() { printf "%s" "${1:-}" | sed "s/'/''/g"; }   # escape single quotes for SQL literals

mesh_init() {
  mkdir -p "$(dirname "$MESH_DB")" "$MESH_HOME/live"
  sqlite3 "$MESH_DB" < "$MESH_HOME/schema.sql"
}

mesh_is_paused() {
  local v; v="$(_sql "SELECT value FROM mesh_meta WHERE key='paused';")"
  printf "%s" "${v:-0}"
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: `PASS=4 FAIL=0`, exit 0.

- [ ] **Step 6: Commit**

```bash
cd ~/.claude/session-mesh && git add schema.sql mesh.sh tests/test_mesh.sh 2>/dev/null && git commit -m "feat(mesh): schema + init" 2>/dev/null || true
```

---

### Task 2: `mesh_register` + `mesh_session_id`

**Files:**
- Modify: `~/.claude/session-mesh/mesh.sh`
- Test: `~/.claude/session-mesh/tests/test_mesh.sh`

**Interfaces:**
- Consumes: `mesh_init`, `_sql`, `_now`, `_esc`.
- Produces: `mesh_register <cwd> <branch> <ticket> <pid>` (prints session_id; identity keyed by cwd; writes `live/<hash>.id`); `mesh_session_id <cwd>` (prints recorded session_id or empty).

- [ ] **Step 1: Write the failing test** — append before the final `echo "PASS=..."` line:

```bash
# --- Task 2 ---
sid1=$(mesh_register "/tmp/wt/PMP-211" "PMP-211-foo" "PMP-211" 4242)
rows=$(sqlite3 "$MESH_DB" "SELECT count(*) FROM sessions;")
assert_eq "1" "$rows" "register inserts one row"
got_ticket=$(sqlite3 "$MESH_DB" "SELECT ticket FROM sessions WHERE cwd='/tmp/wt/PMP-211';")
assert_eq "PMP-211" "$got_ticket" "register stores ticket"
assert_eq "$sid1" "$(mesh_session_id /tmp/wt/PMP-211)" "session_id round-trips by cwd"
# re-register same cwd: replaces, does not duplicate, keeps same identity semantics
sid2=$(mesh_register "/tmp/wt/PMP-211" "PMP-211-foo" "PMP-211" 9999)
rows2=$(sqlite3 "$MESH_DB" "SELECT count(*) FROM sessions;")
assert_eq "1" "$rows2" "re-register same cwd does not duplicate"
pid2=$(sqlite3 "$MESH_DB" "SELECT pid FROM sessions WHERE cwd='/tmp/wt/PMP-211';")
assert_eq "9999" "$pid2" "re-register updates pid"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: FAIL — `mesh_register: command not found`.

- [ ] **Step 3: Implement** — append to `mesh.sh`:

```bash
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: `PASS=9 FAIL=0`.

- [ ] **Step 5: Commit**

```bash
cd ~/.claude/session-mesh && git add mesh.sh tests/test_mesh.sh 2>/dev/null && git commit -m "feat(mesh): register + session_id" 2>/dev/null || true
```

---

### Task 3: `mesh_heartbeat` + `mesh_set_status`

**Files:**
- Modify: `~/.claude/session-mesh/mesh.sh`
- Test: `~/.claude/session-mesh/tests/test_mesh.sh`

**Interfaces:**
- Consumes: `mesh_register`, `_sql`, `_now`, `_esc`.
- Produces: `mesh_heartbeat <session_id>`; `mesh_set_status <session_id> <status> <current_task>`.

- [ ] **Step 1: Write the failing test** — append:

```bash
# --- Task 3 ---
mesh_set_status "$sid1" "working" "running FCIT suite"
st=$(sqlite3 "$MESH_DB" "SELECT status||'|'||current_task FROM sessions WHERE session_id='$sid1';")
assert_eq "working|running FCIT suite" "$st" "set_status updates status + task"
old=$(sqlite3 "$MESH_DB" "SELECT last_seen FROM sessions WHERE session_id='$sid1';")
sqlite3 "$MESH_DB" "UPDATE sessions SET last_seen='2000-01-01T00:00:00Z' WHERE session_id='$sid1';"
mesh_heartbeat "$sid1"
new=$(sqlite3 "$MESH_DB" "SELECT last_seen FROM sessions WHERE session_id='$sid1';")
assert_eq "1" "$([ "$new" != "2000-01-01T00:00:00Z" ] && echo 1 || echo 0)" "heartbeat bumps last_seen"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: FAIL — `mesh_set_status: command not found`.

- [ ] **Step 3: Implement** — append to `mesh.sh`:

```bash
mesh_heartbeat() { # session_id
  _sql "UPDATE sessions SET last_seen='$(_now)' WHERE session_id='$(_esc "$1")';"
}

mesh_set_status() { # session_id status current_task
  _sql "UPDATE sessions SET status='$(_esc "$2")', current_task='$(_esc "$3")', last_seen='$(_now)'
        WHERE session_id='$(_esc "$1")';"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: `PASS=11 FAIL=0`.

- [ ] **Step 5: Commit**

```bash
cd ~/.claude/session-mesh && git add mesh.sh tests/test_mesh.sh 2>/dev/null && git commit -m "feat(mesh): heartbeat + status" 2>/dev/null || true
```

---

### Task 4: `mesh_dispatch` (ticket / broadcast / session_id resolution)

**Files:**
- Modify: `~/.claude/session-mesh/mesh.sh`
- Test: `~/.claude/session-mesh/tests/test_mesh.sh`

**Interfaces:**
- Consumes: `mesh_register`, `_sql`, `_now`, `_esc`.
- Produces: `mesh_dispatch <target> <kind> <payload> <created_by>`. Resolution: target matching `^[A-Z]+-[0-9]+$` → all session_ids with that ticket; `*` → all sessions (one row each); otherwise literal session_id. Prints newline-separated inserted command ids.

- [ ] **Step 1: Write the failing test** — append:

```bash
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: FAIL — `mesh_dispatch: command not found`.

- [ ] **Step 3: Implement** — append to `mesh.sh`:

```bash
_mesh_resolve_targets() { # target -> newline-separated session_ids
  local t="$1"
  if [ "$t" = "*" ]; then
    _sql "SELECT session_id FROM sessions;"
  elif printf "%s" "$t" | grep -Eq '^[A-Z]+-[0-9]+$'; then
    _sql "SELECT session_id FROM sessions WHERE ticket='$(_esc "$t")';"
  else
    printf "%s\n" "$t"
  fi
}

mesh_dispatch() { # target kind payload created_by
  local target="$1" kind="$2" payload="$3" by="${4:-}" now sid
  now="$(_now)"
  _mesh_resolve_targets "$target" | while IFS= read -r sid; do
    [ -z "$sid" ] && continue
    _sql "INSERT INTO commands(target,kind,payload,state,created_by,created_at)
          VALUES ('$(_esc "$sid")','$(_esc "$kind")','$(_esc "$payload")','pending','$(_esc "$by")','$now');
          SELECT last_insert_rowid();"
  done
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: `PASS=14 FAIL=0`.

- [ ] **Step 5: Commit**

```bash
cd ~/.claude/session-mesh && git add mesh.sh tests/test_mesh.sh 2>/dev/null && git commit -m "feat(mesh): dispatch + target resolution" 2>/dev/null || true
```

---

### Task 5: `mesh_claim` (atomic) + `mesh_complete`

**Files:**
- Modify: `~/.claude/session-mesh/mesh.sh`
- Test: `~/.claude/session-mesh/tests/test_mesh.sh`

**Interfaces:**
- Consumes: `mesh_dispatch`, `_sql`, `_now`, `_esc`.
- Produces: `mesh_claim <session_id>` → prints `id|kind|payload` for exactly one claimed command (marks it `running`), empty if none; `mesh_complete <command_id> <exit_state> <output>` → marks `done`.

- [ ] **Step 1: Write the failing test** — append:

```bash
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: FAIL — `mesh_claim: command not found`.

- [ ] **Step 3: Implement** — append to `mesh.sh` (uses `BEGIN IMMEDIATE` + `RETURNING` so two concurrent pollers cannot claim the same row):

```bash
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
  _sql "UPDATE commands SET state='done', exit_state='$(_esc "$2")', output='$(_esc "$3")', done_at='$(_now)'
        WHERE id=$1;"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: `PASS=19 FAIL=0`.

- [ ] **Step 5: Commit**

```bash
cd ~/.claude/session-mesh && git add mesh.sh tests/test_mesh.sh 2>/dev/null && git commit -m "feat(mesh): atomic claim + complete" 2>/dev/null || true
```

---

### Task 6: `mesh_pause` / `mesh_resume`

**Files:**
- Modify: `~/.claude/session-mesh/mesh.sh`
- Test: `~/.claude/session-mesh/tests/test_mesh.sh`

**Interfaces:**
- Consumes: `mesh_init`, `mesh_is_paused`, `_sql`.
- Produces: `mesh_pause`; `mesh_resume` (both flip `mesh_meta.paused`).

- [ ] **Step 1: Write the failing test** — append:

```bash
# --- Task 6 ---
mesh_pause
assert_eq "1" "$(mesh_is_paused)" "pause sets paused=1"
mesh_resume
assert_eq "0" "$(mesh_is_paused)" "resume sets paused=0"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: FAIL — `mesh_pause: command not found`.

- [ ] **Step 3: Implement** — append to `mesh.sh`:

```bash
mesh_pause()  { _sql "UPDATE mesh_meta SET value='1' WHERE key='paused';"; }
mesh_resume() { _sql "UPDATE mesh_meta SET value='0' WHERE key='paused';"; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: `PASS=21 FAIL=0`.

- [ ] **Step 5: Commit**

```bash
cd ~/.claude/session-mesh && git add mesh.sh tests/test_mesh.sh 2>/dev/null && git commit -m "feat(mesh): pause/resume" 2>/dev/null || true
```

---

### Task 7: `mesh_board` (rollup + liveness)

**Files:**
- Modify: `~/.claude/session-mesh/mesh.sh`
- Test: `~/.claude/session-mesh/tests/test_mesh.sh`

**Interfaces:**
- Consumes: `mesh_register`, `mesh_heartbeat`, `_sql`, `_now`.
- Produces: `mesh_board` → prints one line per session: `ticket<TAB>liveness<TAB>status<TAB>current_task<TAB>cwd`, where liveness is `alive` if `last_seen` within 90s else `stale`. Then a blank line and recent commands: `id<TAB>target_ticket<TAB>kind<TAB>state`.

- [ ] **Step 1: Write the failing test** — append:

```bash
# --- Task 7 ---
board=$(mesh_board)
assert_eq "1" "$(printf "%s\n" "$board" | grep -c 'PMP-211')" "board lists PMP-211"
# force a stale session and assert it is flagged stale
sqlite3 "$MESH_DB" "UPDATE sessions SET last_seen='2000-01-01T00:00:00Z' WHERE session_id='$sidB';"
staleline=$(mesh_board | awk -F'\t' '$1=="PMP-238"{print $2}')
assert_eq "stale" "$staleline" "board flags old last_seen as stale"
mesh_heartbeat "$sidB"
freshline=$(mesh_board | awk -F'\t' '$1=="PMP-238"{print $2}')
assert_eq "alive" "$freshline" "heartbeat restores alive"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: FAIL — `mesh_board: command not found`.

- [ ] **Step 3: Implement** — append to `mesh.sh` (SQLite computes staleness against `now-90s`):

```bash
mesh_board() {
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: `PASS=24 FAIL=0`. This completes the tested core (`mesh.sh`).

- [ ] **Step 5: Commit**

```bash
cd ~/.claude/session-mesh && git add mesh.sh tests/test_mesh.sh 2>/dev/null && git commit -m "feat(mesh): board + liveness" 2>/dev/null || true
```

---

### Task 8: `/mesh-init` skill

**Files:**
- Create: `~/.claude/skills/mesh-init/SKILL.md`

**Interfaces:**
- Consumes: `mesh.sh:mesh_init`.
- Produces: user-invocable `/mesh-init`.

- [ ] **Step 1: Write the skill**

Create `~/.claude/skills/mesh-init/SKILL.md`:

```markdown
---
name: mesh-init
description: Initialize the Session Mesh SQLite database (once per machine). Use before any other mesh skill, or when a mesh skill reports the database is missing.
allowed-tools:
  - Bash
---

Initialize the Session Mesh store.

1. Verify prerequisites: `sqlite3 --version` (must be >= 3.35 for RETURNING) and `command -v uuidgen shasum`.
2. Run: `source ~/.claude/session-mesh/mesh.sh && mesh_init`.
3. Confirm: `sqlite3 ~/.claude/session-mesh/mesh.db "SELECT name FROM sqlite_master WHERE type='table';"` — expect `sessions`, `commands`, `mesh_meta`.
4. Report the DB path and that the mesh is ready. This is idempotent; safe to re-run.
```

- [ ] **Step 2: Verify (manual)**

Run in a shell:
```bash
rm -f /tmp/mesh_probe.db
MESH_DB=/tmp/mesh_probe.db bash -c 'source ~/.claude/session-mesh/mesh.sh && mesh_init && sqlite3 "$MESH_DB" "SELECT count(*) FROM mesh_meta;"'
```
Expected: prints `2`. Then `rm -f /tmp/mesh_probe.db*`.

- [ ] **Step 3: Commit**

```bash
cd ~/.claude && git add skills/mesh-init/SKILL.md 2>/dev/null && git commit -m "feat(mesh): /mesh-init skill" 2>/dev/null || true
```

---

### Task 9: `/mesh-register` skill

**Files:**
- Create: `~/.claude/skills/mesh-register/SKILL.md`

**Interfaces:**
- Consumes: `mesh.sh:mesh_register`.
- Produces: `/mesh-register` — a worker registers itself; infers ticket/branch from git.

- [ ] **Step 1: Write the skill**

Create `~/.claude/skills/mesh-register/SKILL.md`:

```markdown
---
name: mesh-register
description: Register this Claude Code session on the Session Mesh so a master session can see it. Use when starting work in a worktree, or when the user says to join/register the mesh. Run once per session, then start the poll loop with /loop 30s /mesh-poll if this session should accept master commands.
allowed-tools:
  - Bash
---

Register this session as a mesh worker.

1. Gather identity from the shell:
   - `cwd="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"`
   - `branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"`
   - `ticket="$(printf "%s" "$branch" | grep -oE '^[A-Z]+-[0-9]+' || true)"` (fallback: ask the user for the ticket)
   - `pid="$PPID"`
2. Register: `source ~/.claude/session-mesh/mesh.sh && mesh_register "$cwd" "$branch" "$ticket" "$pid"`.
   If it errors that the DB is missing, run /mesh-init first, then retry.
3. Report the ticket, cwd, and the printed session_id.
4. Remind the user: to let the master push commands here, start `/loop 30s /mesh-poll` in this session (full-autonomy execution).
```

- [ ] **Step 2: Verify (manual)**

```bash
cd ~/projects/montblanc && source ~/.claude/session-mesh/mesh.sh && mesh_register "$(pwd)" "master" "" "$$" && sqlite3 ~/.claude/session-mesh/mesh.db "SELECT ticket,cwd FROM sessions;"
```
Expected: a row for the montblanc cwd. (Clean up later via `mesh_board`.)

- [ ] **Step 3: Commit**

```bash
cd ~/.claude && git add skills/mesh-register/SKILL.md 2>/dev/null && git commit -m "feat(mesh): /mesh-register skill" 2>/dev/null || true
```

---

### Task 10: `/mesh-status` skill

**Files:**
- Create: `~/.claude/skills/mesh-status/SKILL.md`

**Interfaces:**
- Consumes: `mesh.sh:mesh_session_id`, `mesh_set_status`.
- Produces: `/mesh-status` — worker updates its status + current task.

- [ ] **Step 1: Write the skill**

Create `~/.claude/skills/mesh-status/SKILL.md`:

```markdown
---
name: mesh-status
description: Update this session's status and current-task line on the Session Mesh so the master board reflects what you are doing now. Use at meaningful checkpoints, or when the user asks to update mesh status.
allowed-tools:
  - Bash
---

Update this worker's mesh status.

1. Determine `cwd="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"` and `sid="$(source ~/.claude/session-mesh/mesh.sh && mesh_session_id "$cwd")"`.
   If `sid` is empty, run /mesh-register first.
2. Decide `status` (one of: idle | working | blocked | done) and a one-line `current_task` from the recent conversation + working tree. Prefer the user's words if given.
3. Apply: `source ~/.claude/session-mesh/mesh.sh && mesh_set_status "$sid" "<status>" "<current_task>"`.
4. Confirm the written status back to the user in one line.
```

- [ ] **Step 2: Verify (manual)**

```bash
source ~/.claude/session-mesh/mesh.sh; sid=$(mesh_session_id "$(cd ~/projects/montblanc && pwd)"); mesh_set_status "$sid" working "smoke test"; sqlite3 ~/.claude/session-mesh/mesh.db "SELECT status,current_task FROM sessions WHERE session_id='$sid';"
```
Expected: `working|smoke test`.

- [ ] **Step 3: Commit**

```bash
cd ~/.claude && git add skills/mesh-status/SKILL.md 2>/dev/null && git commit -m "feat(mesh): /mesh-status skill" 2>/dev/null || true
```

---

### Task 11: `/mesh-board` skill (Phase 1 complete)

**Files:**
- Create: `~/.claude/skills/mesh-board/SKILL.md`

**Interfaces:**
- Consumes: `mesh.sh:mesh_board`, plus `kill -0` for pid liveness.
- Produces: `/mesh-board` — master renders the live roster.

- [ ] **Step 1: Write the skill**

Create `~/.claude/skills/mesh-board/SKILL.md`:

```markdown
---
name: mesh-board
description: Show the live Session Mesh board from the master session — every registered worker, its ticket, whether it is alive or stale, its current task, and recent commands. Use when the user asks what sessions are running, mesh status, or the board.
allowed-tools:
  - Bash
---

Render the mesh board for the master.

1. Run: `source ~/.claude/session-mesh/mesh.sh && mesh_board`. If the DB is missing, tell the user to run /mesh-init.
2. The output is two tab-separated blocks: sessions (`ticket, alive|stale, status, current_task, cwd`) then recent commands (`id, target_ticket, kind, state`).
3. For each session, additionally cross-check the OS process: read its pid via `sqlite3 ~/.claude/session-mesh/mesh.db "SELECT ticket,pid FROM sessions;"` and mark a session **dead** if `kill -0 <pid>` fails (process gone) even when last_seen looks recent.
4. Present a clean markdown table to the user: Ticket | Liveness (alive/stale/dead) | Status | Current task, followed by a short list of the most recent commands and their states. Call out any stale/dead sessions explicitly.
```

- [ ] **Step 2: Verify (manual)**

```bash
source ~/.claude/session-mesh/mesh.sh && mesh_board
```
Expected: at least the montblanc session row from Task 9/10 appears with `alive`.

- [ ] **Step 3: Commit**

```bash
cd ~/.claude && git add skills/mesh-board/SKILL.md 2>/dev/null && git commit -m "feat(mesh): /mesh-board skill (phase 1 done)" 2>/dev/null || true
```

---

### Task 12: `/mesh-dispatch` skill

**Files:**
- Create: `~/.claude/skills/mesh-dispatch/SKILL.md`

**Interfaces:**
- Consumes: `mesh.sh:mesh_dispatch`.
- Produces: `/mesh-dispatch` — master queues a command for a worker/ticket/broadcast.

- [ ] **Step 1: Write the skill**

Create `~/.claude/skills/mesh-dispatch/SKILL.md`:

```markdown
---
name: mesh-dispatch
description: From the master session, queue a command for a worker session to execute autonomously on its next poll. Use when the user wants to tell another session (by ticket, session id, or all) to run something. Target a ticket like PMP-211, a session id, or * for broadcast.
allowed-tools:
  - Bash
---

Queue a command on the mesh for a worker.

1. Parse from the user request: `target` (a ticket e.g. PMP-211, a session_id, or `*`), `kind` (`shell` = run a shell command in the worker's worktree; `prompt` = have the worker carry out a task; `control` = `stop`), and `payload` (the command/prompt text).
2. Identify the master session id for provenance: `by="master:$(cd ~/projects/montblanc && git rev-parse --show-toplevel)"` (or any stable label).
3. Dispatch: `source ~/.claude/session-mesh/mesh.sh && mesh_dispatch "<target>" "<kind>" "<payload>" "<by>"`. It prints the new command id(s).
4. Confirm to the user: what was queued, for whom, and the command id(s). Note it runs on the worker's next poll (<=30s) under full autonomy, and that /mesh-board will show the result state.
5. If the target resolves to no session (nothing printed), warn the user that no live worker matches — they may need to register that session first.
```

- [ ] **Step 2: Verify (manual)**

```bash
source ~/.claude/session-mesh/mesh.sh; sid=$(mesh_session_id "$(cd ~/projects/montblanc && pwd)"); id=$(mesh_dispatch "$sid" shell "echo probe" "master:test"); sqlite3 ~/.claude/session-mesh/mesh.db "SELECT id,target,kind,payload,state FROM commands WHERE id=$id;"
```
Expected: a `pending` row with payload `echo probe`. Then `sqlite3 ~/.claude/session-mesh/mesh.db "DELETE FROM commands WHERE id=$id;"`.

- [ ] **Step 3: Commit**

```bash
cd ~/.claude && git add skills/mesh-dispatch/SKILL.md 2>/dev/null && git commit -m "feat(mesh): /mesh-dispatch skill" 2>/dev/null || true
```

---

### Task 13: `/mesh-poll` skill (worker execution loop)

**Files:**
- Create: `~/.claude/skills/mesh-poll/SKILL.md`

**Interfaces:**
- Consumes: `mesh.sh:mesh_session_id`, `mesh_heartbeat`, `mesh_is_paused`, `mesh_claim`, `mesh_complete`.
- Produces: `/mesh-poll` — one poll tick; intended to run under `/loop 30s /mesh-poll`.

- [ ] **Step 1: Write the skill**

Create `~/.claude/skills/mesh-poll/SKILL.md`:

```markdown
---
name: mesh-poll
description: One Session Mesh poll tick for a worker — heartbeat, then claim and execute any command the master queued for this session. Intended to run on a loop via /loop 30s /mesh-poll. Full-autonomy execution; respects the global pause switch.
allowed-tools:
  - Bash
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

Perform one poll tick for this worker.

1. Resolve identity: `cwd="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"`, `sid="$(source ~/.claude/session-mesh/mesh.sh && mesh_session_id "$cwd")"`. If empty, run /mesh-register first and stop this tick.
2. Heartbeat: `source ~/.claude/session-mesh/mesh.sh && mesh_heartbeat "$sid"`.
3. If `mesh_is_paused` prints `1`, report "mesh paused, skipping" and stop this tick (do not claim).
4. Claim one command: `claim="$(source ~/.claude/session-mesh/mesh.sh && mesh_claim "$sid")"`. If empty, report "no commands" and stop.
5. Parse `claim` as `id|kind|payload` (split on the FIRST two `|`; payload may contain `|`).
6. Execute by kind (full autonomy — no approval gate):
   - `shell`: run the payload as a shell command in `cwd`; capture combined output + exit code.
   - `prompt`: carry out the payload as a task in this session, using your normal tools; summarize what you did.
   - `control`: if payload is `stop`, record completion and do NOT reschedule the loop (end polling); otherwise ignore.
7. Record the result: `source ~/.claude/session-mesh/mesh.sh && mesh_complete "<id>" "<ok|fail>" "<short output/summary, tail-truncated>"`.
8. Report to the user which command ran and its result. If more commands may be pending, the next /loop tick will pick them up.
```

- [ ] **Step 2: Verify (manual)** — exercises claim→complete without the loop:

```bash
source ~/.claude/session-mesh/mesh.sh
sid=$(mesh_session_id "$(cd ~/projects/montblanc && pwd)")
id=$(mesh_dispatch "$sid" shell "echo mesh-ok" "master:test")
claim=$(mesh_claim "$sid"); echo "claimed: $claim"
mesh_complete "${claim%%|*}" ok "mesh-ok"
sqlite3 ~/.claude/session-mesh/mesh.db "SELECT state,exit_state,output FROM commands WHERE id=$id;"
```
Expected: `claimed: <id>|shell|echo mesh-ok` then `done|ok|mesh-ok`. Clean up: `sqlite3 ~/.claude/session-mesh/mesh.db "DELETE FROM commands WHERE id=$id;"`.

- [ ] **Step 3: Commit**

```bash
cd ~/.claude && git add skills/mesh-poll/SKILL.md 2>/dev/null && git commit -m "feat(mesh): /mesh-poll worker loop" 2>/dev/null || true
```

---

### Task 14: `/mesh-pause` + `/mesh-resume` skills

**Files:**
- Create: `~/.claude/skills/mesh-pause/SKILL.md`
- Create: `~/.claude/skills/mesh-resume/SKILL.md`

**Interfaces:**
- Consumes: `mesh.sh:mesh_pause`, `mesh_resume`, `mesh_is_paused`.
- Produces: `/mesh-pause`, `/mesh-resume` — master emergency stop / restart.

- [ ] **Step 1: Write both skills**

Create `~/.claude/skills/mesh-pause/SKILL.md`:

```markdown
---
name: mesh-pause
description: Emergency stop for the whole Session Mesh fleet — pause all workers so no queued command executes on the next poll. Use when a dispatch went wrong or you need everything to hold. Workers keep heartbeating but stop claiming.
allowed-tools:
  - Bash
---

Pause the fleet.

1. Run: `source ~/.claude/session-mesh/mesh.sh && mesh_pause && mesh_is_paused`.
2. Expect `1`. Confirm to the user that all workers will skip command execution (still heartbeat) until /mesh-resume.
```

Create `~/.claude/skills/mesh-resume/SKILL.md`:

```markdown
---
name: mesh-resume
description: Lift the Session Mesh global pause so workers resume claiming and executing queued commands on their next poll. Use after /mesh-pause when it is safe to continue.
allowed-tools:
  - Bash
---

Resume the fleet.

1. Run: `source ~/.claude/session-mesh/mesh.sh && mesh_resume && mesh_is_paused`.
2. Expect `0`. Confirm to the user that workers will resume executing queued commands on their next poll.
```

- [ ] **Step 2: Verify (manual)**

```bash
source ~/.claude/session-mesh/mesh.sh && mesh_pause && mesh_is_paused && mesh_resume && mesh_is_paused
```
Expected: prints `1` then `0`.

- [ ] **Step 3: Commit**

```bash
cd ~/.claude && git add skills/mesh-pause/SKILL.md skills/mesh-resume/SKILL.md 2>/dev/null && git commit -m "feat(mesh): /mesh-pause + /mesh-resume" 2>/dev/null || true
```

---

### Task 15: README + end-to-end smoke test

**Files:**
- Create: `~/.claude/session-mesh/README.md`

**Interfaces:**
- Consumes: all skills + `mesh.sh`.
- Produces: operator guide + a copy-paste smoke test proving register → dispatch → claim → complete → board end to end.

- [ ] **Step 1: Write the README**

Create `~/.claude/session-mesh/README.md`:

```markdown
# Session Mesh

Cross-session coordination for parallel Montblanc work. Master session sees a live board of workers; workers register, heartbeat, and (full autonomy) execute commands the master queues.

## Setup (once)
Run `/mesh-init` in any session.

## Master session (e.g. ~/projects/montblanc)
- `/mesh-board` — live roster + recent commands.
- `/mesh-dispatch <PMP-XXX|session_id|*> <shell|prompt|control> "<payload>"` — queue work.
- `/mesh-pause` / `/mesh-resume` — emergency stop / restart.

## Worker session (a ticket worktree)
- `/mesh-register` — join the mesh (infers ticket from branch).
- `/mesh-status` — update your current task.
- `/loop 30s /mesh-poll` — accept and execute master commands (<=30s latency).

## Store
SQLite WAL at `~/.claude/session-mesh/mesh.db`. All logic in `mesh.sh`; tests in `tests/test_mesh.sh` (`bash tests/test_mesh.sh`). Swappable for Redis behind the same skill surface if 30s polling is too slow.

## Notes
- Execution is full autonomy; the global pause is the only stop. Dispatch carefully.
- Do not remote-control a session you are hand-typing in; run poll loops in dedicated worker sessions.
- Personal tooling under ~/.claude — never committed to the montblanc repo.
```

- [ ] **Step 2: Run the full mesh.sh test suite**

Run: `bash ~/.claude/session-mesh/tests/test_mesh.sh`
Expected: `PASS=24 FAIL=0`, exit 0.

- [ ] **Step 3: End-to-end smoke test (real DB, isolated path)**

```bash
export MESH_DB=/tmp/mesh_e2e.db; rm -f /tmp/mesh_e2e.db*
source ~/.claude/session-mesh/mesh.sh
mesh_init
sid=$(mesh_register "/tmp/wt/PMP-999" "PMP-999-demo" "PMP-999" 1234)
id=$(mesh_dispatch "PMP-999" shell "echo e2e" "master:test")
claim=$(mesh_claim "$sid"); mesh_complete "${claim%%|*}" ok "e2e"
mesh_board
sqlite3 "$MESH_DB" "SELECT state,exit_state,output FROM commands WHERE id=$id;"
rm -f /tmp/mesh_e2e.db*; unset MESH_DB
```
Expected: board shows `PMP-999 alive`, and the command row is `done|ok|e2e`.

- [ ] **Step 4: Commit**

```bash
cd ~/.claude && git add session-mesh/README.md 2>/dev/null && git commit -m "docs(mesh): operator guide + smoke test" 2>/dev/null || true
```

---

## Self-Review

**Spec coverage:** SQLite WAL store (Task 1) ✓; schema three tables (Task 1) ✓; register + heartbeat (Tasks 2–3) ✓; status (Task 3/10) ✓; dispatch with ticket/broadcast/session resolution (Task 4/12) ✓; atomic claim / no-double-run (Task 5) ✓; complete/results folded into commands (Task 5) ✓; full-autonomy execution (Task 13) ✓; global pause switch (Tasks 6/14) ✓; liveness via last_seen + pid (Tasks 7/11) ✓; all 7+ skills user-global (Tasks 8–14) ✓; Phase 1 (init/register/status/board) then Phase 2 (dispatch/poll/pause) ✓; Redis-swap-behind-skills noted (README) ✓; markdown board relationship (spec) — DB is runtime, unaffected ✓. `control:stop` ends loop (Task 13) covers the spec's stop path.

**Placeholder scan:** No TBD/TODO; every code step has complete code; skill bodies are concrete. Manual-verify steps used for markdown skills (not auto-testable) but each gives exact commands + expected output.

**Type consistency:** `mesh_claim` output format `id|kind|payload` is produced in Task 5 and parsed in Task 13 identically. `mesh_register` prints session_id (Task 2), consumed by Task 9. `mesh_dispatch(target,kind,payload,by)` signature consistent across Tasks 4 and 12. `mesh_board` tab layout defined in Task 7 and consumed in Task 11. `mesh_is_paused` returns `0/1` in Task 1, used in Tasks 6/13/14. Consistent.
