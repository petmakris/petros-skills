# Task-Manager Mesh — design

Generalize the Session Mesh from ticket-specific coordination into a generic **task manager**. The master session becomes a task manager; each worker session is **one task**. Tasks are no longer assumed to be Jira tickets — they are free-form "task items." The master can also **auto-spawn** worker sessions instead of the user hand-starting each one.

Builds on the existing Session Mesh (`specs/2026-07-01-session-mesh-design.md`). Personal tooling under `~/.claude`. Never committed to any product repo.

## Problem

The mesh already works and is nearly identifier-agnostic under the hood (`_mesh_resolve_targets` matches any string). But two things keep it Jira-shaped and less versatile than it could be:

1. **Vocabulary and one real behavior are ticket-specific.** The `sessions.ticket` column, the word "ticket" throughout the skills, and — the only genuine coupling — `mesh-join` deriving the ticket from the branch via the regex `^[A-Z]+-[0-9]+`. Anything not named like a Jira ticket falls through to an awkward "ask the user" path.
2. **Every worker must be started by hand.** The user opens a Claude Code session in each worktree and types `/mesh-join`. This makes the "master" a viewer of sessions that already exist, not a manager that can *create* work. A backlog is pointless if you still hand-start every session to service it.

> **Update (2026-07-03):** the identifier de-Jira-ification below is **done**, with one change from this spec — the worker-handle column and vocabulary were finalized as **`label`**, not `task`. Rationale: the mesh is meant to grow beyond work-management, and `label` is neutral where `task` re-couples to it. So wherever this doc says the `ticket → task` rename, read it as `ticket → label` (schema v1→v2, `sessions.label`, `mesh-join` asks for a free-form label with no Jira regex). The **backlog** and **auto-spawn** goals below remain unbuilt future work.

## Goals

- ~~Rename the domain from **ticket → task**~~ → Rename the worker handle **ticket → label** everywhere (column, prose, skill descriptions). Drop the Jira regex; a label is free-form. **[done]**
- The master owns a **task backlog**: tasks can exist before any session, with a title, description, and lifecycle status.
- The master can **auto-spawn** a worker session for a task — no hand-starting — using a real, subscription-billed session.
- Preserve the existing doorbell/queue architecture and all its tested infra (arming, heartbeat, reaper, dispatch, collect/await).

## Hard constraints

- **Never use `claude -p` (print/headless mode).** It authenticates against a different (API) billing account, not the user's subscription plan. All workers — including auto-spawned ones — must be real interactive-class sessions driven only through the SQLite command queue (dispatch → claim), never `claude -p --resume`. This is *why* the doorbell mesh is kept rather than replaced by a master-drives-headless-workers model.
- Single machine, single user, personal tooling. No new runtime dependencies beyond `sqlite3` (already required) and the `claude` CLI (already present).

## Validation (spike, 2026-07-03)

The one load-bearing assumption — that the doorbell wake-on-background-exit still fires inside a `claude --bg` background agent — was proven end-to-end against an isolated `MESH_HOME`, with no impact on the live board:

- `claude --bg -n meshspike "run /mesh-join"` spawned instantly and returned a session id.
- The worker registered into the mesh and armed the doorbell (heartbeat advancing).
- A dispatched `shell` command woke the `--bg` agent in **~6s**, executed, and completed `ok`.
- A second dispatch confirmed **re-arm**.
- The spawned worker is `kind: "background"` in `claude agents --json` — the interactive-class background path, **not** the `--print`/`-p` path — so it stays on subscription billing.

Conclusion: auto-spawn on top of the existing mesh is viable and `-p`-free.

## Architecture — additive on the existing mesh

No rewrite. The doorbell, queue, reaper, and command lifecycle are unchanged. We add a backlog table, a spawn helper, and task-oriented skills; and we rename `ticket → task`.

### Schema changes

**Rename** the existing column (data-preserving), applied idempotently in `mesh_migrate` and reflected in `schema.sql` for fresh installs:

```sql
-- mesh_migrate: only when the old column exists and the new one does not
ALTER TABLE sessions RENAME COLUMN ticket TO task;
-- bump schema_version 1 -> 2
```

`sessions.task` continues to hold the per-worker label (now free-form, e.g. `reporting-openapi`, `fix-flaky-login`, or still `PMP-211` if the user chooses).

**New backlog table** — this is what makes the master a *manager*: tasks exist independently of sessions.

```sql
CREATE TABLE IF NOT EXISTS tasks (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  slug       TEXT NOT NULL UNIQUE,   -- short label; becomes sessions.task when spawned
  title      TEXT,                   -- one-line summary
  body       TEXT,                   -- description / instructions for the worker
  status     TEXT DEFAULT 'backlog', -- backlog | assigned | in_progress | blocked | done
  assignee   TEXT,                   -- worker session_id, NULL until spawned
  cwd        TEXT,                   -- worktree the task runs in (NULL until known)
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
```

Linkage: when a task is spawned, its worker registers with `sessions.task = tasks.slug`, and `tasks.assignee` is set to that worker's `session_id`. The `commands` table is unchanged — tasks describe *what* to do; commands remain the imperative dispatches.

### Auto-spawn — `mesh_spawn`

One new function in `mesh.sh`, plus the `/mesh-task-start` skill that calls it.

```
mesh_spawn <slug> <cwd> [initial_prompt]
  -> launches:  ( cd <cwd> && claude --bg -n <slug> "run /mesh-join" )
  -> returns the background agent id
```

- Uses `claude --bg` (real background session; **never** `-p`). `--tmux` is an optional variant for a visible, attachable pane; plain `--bg` is already attachable via `claude attach <id>`, so plain `--bg` is the default.
- Autonomy: the worker must not stall on permission prompts (no human at the keyboard). The spawn uses the session's configured autonomy; for unattended workers this means a bypass/auto permission mode consistent with how the user already runs mesh workers. (The reskin does not change the existing full-autonomy execution policy.)
- The worker runs `/mesh-join`, which registers (`sessions.task = slug`) and arms the doorbell exactly as a hand-started worker does. `/mesh-task-start` then sets `tasks.status = in_progress` and `tasks.assignee`.
- Worktree creation (if the task needs a fresh branch/worktree) is the caller's concern before spawn; `mesh_spawn` assumes `<cwd>` exists.

### `mesh-join` change (drop the Jira regex)

Task-label resolution, in order: explicit argument → sanitized branch name → cwd basename → ask the user. No `^[A-Z]+-[0-9]+` assumption. When spawned via `mesh_spawn`, the slug is passed explicitly so no derivation or prompt is needed.

## Skill surface

**Reskinned (ticket → task) with no behavior change:** `mesh-dispatch`, `mesh-board`, `mesh-collect`, `mesh-await`, `mesh-join`, plus README. Descriptions and prose swap "ticket" for "task"; `mesh-dispatch` still targets a label, a session id, or `*`.

**New task-manager skills:**

| Skill | Role | Behavior |
|---|---|---|
| `/mesh-task-add <slug> "<title/desc>"` | master | insert a `tasks` row in `backlog` (no session yet) |
| `/mesh-task-start <slug> [cwd]` | master | resolve/confirm the worktree, `mesh_spawn` a worker, set task `in_progress` + `assignee` — **the hand-start killer** |
| `/mesh-task-done <slug>` | master | mark the task `done` (and optionally dispatch `control stop` to its worker) |

`/mesh-board` gains a **backlog section** alongside the live-worker roster: tasks grouped by status (backlog / in_progress / blocked / done) with their assignee liveness. A standalone `/mesh-task-list` is not added — the board is the single view (YAGNI).

## Task lifecycle

```
backlog ──/mesh-task-start──> in_progress ──/mesh-task-done──> done
                                   │
                                   └── blocked (set manually / by worker) ──> in_progress
```

`assigned` exists as a transient state between "spawn requested" and "worker registered + armed"; once the worker is armed the task is `in_progress`.

## Migration & compatibility

- `mesh_migrate` performs the `ticket → task` rename only when `ticket` exists and `task` does not (introspect `PRAGMA table_info(sessions)`), so it is safe to run repeatedly and safe on an already-migrated DB. `schema_version` goes 1 → 2. The live DB currently holds one session (`reporting-openapi`) — the rename preserves it.
- `tasks` is `CREATE TABLE IF NOT EXISTS`, so it appears on next `mesh_init`/`mesh_migrate` without disturbing existing data.
- Existing hand-started workers keep working unchanged; auto-spawn is purely additive.

## Non-goals

- No `claude -p` / headless driving of workers (billing constraint; hard rule).
- No migration to the native `claude agents` system as the roster/registry. Native `--bg` is used *only* as the spawn mechanism feeding the existing mesh; whether to later lean harder on native agents is a separate, deferred decision.
- No cross-machine or multi-user support.
- No real-time (<1s) guarantees; the doorbell's near-instant wake is sufficient.

## Edge cases

- **Spawn where the worktree doesn't exist yet:** `/mesh-task-start` must create/select the worktree before `mesh_spawn`; a missing `cwd` is a fast failure, not a silent spawn in the wrong directory.
- **Slug collision:** `tasks.slug` is `UNIQUE`; `/mesh-task-add` rejects duplicates. A worker registering with a slug that already has a live session should surface a warning (two sessions claiming one task label).
- **Auto-spawned worker stalls on a permission prompt:** would show as a `blocked` background agent that never arms; `/mesh-board` should flag a task `in_progress` whose assignee never became live. Mitigated by spawning with the same autonomy the user already grants mesh workers.
- **Task marked done while its worker is still armed:** `/mesh-task-done` optionally dispatches `control stop`; otherwise the worker stays listening (harmless).
- **Reaper interaction:** unchanged. A dead auto-spawned worker's `running` command is re-queued exactly as for hand-started workers.

## Deferred to implementation

- Whether `/mesh-task-start` should own worktree creation (e.g. `git worktree add`) or require the caller to pass an existing `cwd` (default: require existing `cwd`, revisit if annoying).
- Whether `--bg` or `--bg --tmux` is the better spawn default for the user's workflow (both proven-equivalent for the doorbell; a preference call).
- Exact board layout for the backlog section.
