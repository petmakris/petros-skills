# Session Mesh — design

Cross-session coordination framework for Petros's parallel Montblanc work. Lets independently-started Claude Code sessions register with a master session, report live status, and (Phase 2) receive and execute commands pushed by the master.

Personal tooling under `~/.claude`. Never committed to the montblanc repo.

## Problem

Petros runs several Claude Code sessions in parallel, one per ticket, each in its own git worktree. Claude Code sessions are isolated OS processes with no live channel between them. The earlier approach — markdown status files read by a manager session — works for durable notes but cannot give live liveness, cannot be written concurrently without corruption risk (single JSON file), and offers no way for a master to drive work in a child.

## Goals

- A **master** session sees a live roster of all active worker sessions: which ticket, which worktree, alive/stale, current task.
- **Workers** register themselves and heartbeat, with zero risk of store corruption under concurrent writes.
- The master can **push a command** to a specific worker (or broadcast) and read back the result — the worker executes it as itself, no tty injection.
- Skills are the entire interface; the transport is swappable.

## Non-goals

- Injecting input into a live interactive TUI the user is hand-typing in (unsupported; races keystrokes). Remote execution targets dedicated poll-loop workers.
- Real-time (<1s) push. 30s polling is acceptable; Redis is a documented later swap if not.
- A general-purpose distributed system. This is a personal, single-machine, single-user fleet.

## Architecture

### Store — SQLite (WAL)

Single file: `~/.claude/session-mesh/mesh.db`, opened in WAL mode (`PRAGMA journal_mode=WAL`). WAL allows many concurrent readers plus serialized writers, which removes the parallel-write corruption problem of a single JSON file. ACID, no server, queryable. Accessed by skills via the `sqlite3` CLI (ships with macOS — no dependency).

The store is deliberately hidden behind the skill surface so it can be replaced by Redis later (for instant push instead of polling) without changing how sessions or the user interact with the mesh.

### Schema

```sql
CREATE TABLE IF NOT EXISTS sessions (
  session_id   TEXT PRIMARY KEY,   -- Claude Code session UUID
  ticket       TEXT,               -- inferred from branch/worktree, e.g. PMP-211
  cwd          TEXT NOT NULL,       -- absolute worktree path
  branch       TEXT,
  pid          INTEGER,             -- OS pid, for liveness cross-check
  status       TEXT DEFAULT 'idle', -- idle | working | blocked | done
  current_task TEXT,                -- one-line "what am I doing now"
  started_at   TEXT NOT NULL,       -- ISO8601
  last_seen    TEXT NOT NULL        -- ISO8601, bumped every poll (heartbeat)
);

CREATE TABLE IF NOT EXISTS commands (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  target       TEXT NOT NULL,       -- session_id, or '*' for broadcast
  kind         TEXT NOT NULL,       -- shell | prompt | control
  payload      TEXT NOT NULL,       -- command/prompt text; for control: 'pause'|'resume'|'stop'
  state        TEXT DEFAULT 'pending', -- pending | running | done | error
  output       TEXT,                -- captured result / summary
  exit_state   TEXT,                -- ok | fail | skipped
  created_by   TEXT,                -- master session_id
  created_at   TEXT NOT NULL,
  picked_at    TEXT,
  done_at      TEXT
);

CREATE TABLE IF NOT EXISTS mesh_meta (
  key   TEXT PRIMARY KEY,
  value TEXT
);
-- mesh_meta holds 'paused' = '1'|'0' (global emergency stop) and 'schema_version'.
```

### Execution model — why no injection is needed

A worker runs `/loop 30s /mesh-poll`. Each loop tick is a genuine turn in that session. When `/mesh-poll` finds a `pending` command targeting this session, the session executes it *as itself* within that turn, then writes the result back. Claude Code queues the scheduled tick until the session is idle, so a poll never collides with the user typing. This is the core mechanism: remote execution = the child doing work on its own next tick, driven by a queue.

### Execution policy — full autonomy + global pause

Chosen policy: **full autonomy.** A worker executes whatever command it pops, unattended — no per-command approval gate. This matches a true master/slave fleet and is the point of the framework.

One safety valve that does **not** weaken autonomy: a global **pause switch** in `mesh_meta` (`paused=1`). While paused, `/mesh-poll` still heartbeats but does not pop or execute commands. `/mesh-pause` and `/mesh-resume` (master) flip it. This is an emergency stop for the whole fleet, not a gate on individual commands. A `control` command with payload `stop` also lets the master tell a specific worker to end its poll loop.

### Liveness

- `last_seen` bumped every poll → master computes staleness (e.g. `last_seen` older than 3× poll interval = stale).
- `pid` lets the master cross-check the OS process is actually alive (`kill -0 <pid>`), catching crashed sessions whose row lingers.

## Skill surface

All user-global in `~/.claude/skills/` (a project-level `.claude/skills` does not propagate across git worktrees, and workers live in separate worktrees — user-global guarantees every session sees them).

| Skill | Role | Behavior |
|---|---|---|
| `/mesh-init` | setup (once) | create `mesh.db`, set WAL, run schema DDL, seed `mesh_meta` |
| `/mesh-register` | worker | upsert this session's `sessions` row; infer `ticket`/`branch` from git, capture `cwd`/`pid`/`session_id`; set `started_at`/`last_seen` |
| `/mesh-poll` | worker | bump `last_seen`; if not paused, pop this session's `pending` commands FIFO, mark `running`, execute per kind, write `output`/`exit_state`/`done`. Meant to run under `/loop 30s /mesh-poll` |
| `/mesh-status` | worker | update this session's `status` + `current_task` |
| `/mesh-dispatch <target> <kind> "<payload>"` | master | insert a `commands` row (`target` = session_id/ticket/`*`) |
| `/mesh-board` | master | render live rollup: all sessions with alive/stale/dead + current_task, and recent command states |
| `/mesh-pause` / `/mesh-resume` | master | flip global `paused`; emergency stop / restart of the fleet |

`/mesh-dispatch` accepts a ticket (e.g. `PMP-211`) as target and resolves it to the current `session_id` for that ticket, so the user need not know UUIDs.

## Command kinds

- `shell` — run a shell command in the worker's worktree; capture stdout/exit.
- `prompt` — feed the payload to the worker as a task to carry out (full agent turn).
- `control` — `pause`/`resume` (usually via meta) or `stop` (worker ends its `/loop`).

## Phasing

- **Phase 1 (read-only federation):** `mesh-init`, `mesh-register`, `mesh-status`, `mesh-board`. Sessions register + heartbeat + report; master sees a live board. No remote execution, no risk. Immediately useful and replaces the file-mtime guessing.
- **Phase 2 (remote execution):** `mesh-poll`, `mesh-dispatch`, `mesh-pause`/`mesh-resume`, plus the `commands` table lifecycle. The master-drives-children capability, under full-autonomy + global pause.

## Relationship to the existing markdown board

The markdown item files under `~/.claude/montblanc-board/` remain the **durable human-facing per-ticket notes** (scope, open points, decisions). The DB is the **live runtime state** (who's alive, current task, command queue). `/mesh-board` reads the DB; the markdown `_INDEX.md` is optional and no longer the liveness source. No overlap in responsibility.

## Edge cases

- **Stale/crashed worker:** row present but `pid` dead or `last_seen` old → `/mesh-board` marks `dead`/`stale`; its `pending` commands are left untouched (a `control:stop` or manual cleanup clears them).
- **Broadcast (`*`):** each worker pops a per-session *copy* — implement by fanning a `*` command into per-session rows at dispatch time, so state is tracked per worker.
- **Duplicate execution:** `/mesh-poll` claims a command with an atomic `UPDATE ... SET state='running' WHERE id=? AND state='pending'` and only executes if the update affected a row — prevents two ticks double-running the same command.
- **DB missing:** any skill run before `/mesh-init` fails fast with "run /mesh-init first".
- **WAL leftovers:** `-wal`/`-shm` sidecar files are normal; never delete while a session is live.

## Open questions deferred to implementation

- Exact staleness threshold (default: 3× 30s = 90s).
- Whether `prompt`-kind results store full output or a summary (default: summary + truncated tail).
