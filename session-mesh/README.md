# Session Mesh

Cross-session coordination for parallel Montblanc work. Master session sees a live board of workers; workers register, heartbeat, and (full autonomy) execute commands the master queues.

## Setup (once)
Run `/mesh-init` in any session.

## Master session (e.g. ~/projects/montblanc)
- `/mesh-board` — live roster + recent commands.
- `/mesh-dispatch <PMP-XXX|session_id|*> <shell|prompt|control> "<payload>"` — queue work.
- `/mesh-await` — arm a detached watcher that wakes this session once, the moment a dispatched command completes (event-driven, no polling).
- `/mesh-collect` — one-shot: pull any already-finished results right now, no waiting.
- `/mesh-pause` / `/mesh-resume` — emergency stop / restart.

## Worker session (a worktree)
- `/mesh-join` — the only command you type here: registers this session (via `mesh_register`), then arms the doorbell (`/mesh-arm`).
- `/mesh-arm` — event-driven worker: launches a detached background watcher (`mesh_watch_worker`) that heartbeats and blocks until a command is queued, then wakes the model **once** to execute it and re-arms. Zero tokens while idle; no terminal spam.
- `/mesh-poll` — legacy per-tick engine for the old `/loop 1m /mesh-poll` polling model. Superseded by `/mesh-arm`; kept for backward compat.

## Event-driven design (why no loop)
Polling woke the model every tick just to run a cheap SQLite check that almost always found nothing (~120 model wake-ups/hour while idle). The doorbell splits the two concerns: **detection** (heartbeat + claim / result-check) runs in plain shell, launched detached via the harness's background-bash; it BLOCKS until there is real work, then exits — and a background command exiting re-invokes the session model exactly once. So **execution** (the model) runs only when there is actually something to do. Idle cost: zero.

Caveat: while a worker executes a claimed command the watcher is briefly disarmed; commands queue in the DB and are claimed the instant it re-arms, so nothing is lost.

## Crash / context-clear recovery (the reaper)
A command that has been `claim`ed sits in `running`. If that worker then dies (crash, closed session) or has its context cleared, the command would otherwise be orphaned. `mesh_reap` returns such commands to `pending` so a healthy worker picks them up. It runs automatically inside `mesh_dispatch` and `mesh_board`, so recovery needs no daemon and no manual step.

Reap triggers (per `running` command):
- **worker process dead** (`kill -0 pid` fails) → re-queued immediately; zero false-positive risk (a dead process cannot be mid-execution).
- **lease expired** — the command has been `running` longer than `MESH_LEASE_SECS` (default **10800s = 3h**) even though the worker's process is alive → re-queued. This covers the "context cleared but process still up" case only. Because dead workers recover instantly regardless, the lease can safely be long; its only job is to bound the rare alive-but-abandoned case, and a long value protects legitimate long-running jobs from double-execution. Raise it further for jobs that legitimately run longer than 3h.

`mesh_complete` is guarded with `WHERE state='running'`: if a command was reaped and re-claimed elsewhere, a late completion from the original worker is a harmless no-op rather than clobbering the new run. Known limitation: a job that legitimately outruns the lease can be run twice (original + reaper's re-claim); the 3h default keeps this well clear of normal jobs, and prefer idempotent payloads for anything that could approach it.

## Store
SQLite WAL at `~/.claude/session-mesh/mesh.db`. All logic in `mesh.sh`; tests in `tests/test_mesh.sh` (`bash tests/test_mesh.sh`). Schema upgrades go in `mesh_migrate` (idempotent; run by `mesh_init`). The `commands.ack_at` column marks results the master has already collected.

## Notes
- Execution is full autonomy; the global pause is the only stop. Dispatch carefully.
- Do not remote-control a session you are hand-typing in; arm workers in dedicated worker sessions.
- Personal tooling under ~/.claude — never committed to the montblanc repo.
