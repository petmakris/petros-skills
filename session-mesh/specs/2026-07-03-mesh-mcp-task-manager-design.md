# Mesh MCP + Task Manager — design

**Supersedes** `2026-07-03-task-manager-mesh-design.md` (keeps its intent; changes the identifier term to `label`, splits the design cleanly into two layers, and delivers it through an MCP server rather than more shell skills).

Personal tooling under `~/.claude`. Never committed to any product repo. The mesh *code* ships in the `petros-skills` plugin; the *state* (SQLite DB, `live/` watcher files, the MCP dep cache) stays machine-local.

## The core idea: two orthogonal layers

The previous design fused "a session" and "a task" into one row. They are orthogonal and must be separated:

- **Layer 1 — Mesh (transport).** Live Claude sessions that can be reached and driven: a session is addressable by a `label`, it heartbeats, it has a command inbox, the master can dispatch to it / collect results / (later) drive its conversation. **Layer 1 knows nothing about tasks.** This already exists (`mesh.sh` + SQLite, 33 tests) and is correct.
- **Layer 2 — Task Manager (app).** A to-do list that **lives in the shared store** and is **independent of sessions**. A task is *serviced by* 0..N sessions. Zero sessions = the master does it inline or via a subagent. It points at Layer 1 only by calling `mesh.dispatch`.

The two layers meet at exactly one seam: **Layer 2 acts on a session by calling `mesh.dispatch(session, kind=prompt, …)`.** `commands` never gains a `task_id`; `sessions` never gains a task foreign key.

## Delivery mechanism: one MCP server, the DB is the hub

- A **stdio MCP server** (Python, `mcp`/FastMCP) is declared by the plugin (`plugin.json` → `mcpServers`, path via `${CLAUDE_PLUGIN_ROOT}`). Every session — master or worker — spawns **its own** instance; all instances share the one SQLite DB. **The DB is the hub, not any process.** No daemon, no port, no session that "hosts" the mesh. "Master" is a role (the session that owns the task list), not a host.
- The server is a **thin protocol adapter**: each tool shells into a `mesh.sh` function and returns its output (JSON for reads via `sqlite3 -json`; an id/status for writes). **`mesh.sh` stays the single source of truth** for all state mutation, including the race-sensitive `claim`/`reap`/`resolve` logic that is already tested. No SQL is reimplemented in Python.
- **Dependencies: zero install.** The server file carries PEP 723 inline metadata (`dependencies = ["mcp>=1.28"]`) and is launched with `uv run --script`. `uv` resolves and caches the env on first spawn. Prereq: `uv` on PATH (checked by `/mesh-init`).
- **The doorbell is untouched.** `mesh-arm` / `mesh-await` remain background bash watchers (token-free wake). MCP can't replace them — a blocking MCP tool would hold the model turn open. MCP is for *acting* (dispatch/query/task CRUD); the doorbell is for *receiving*.

Why not reimplement in Python, or run a daemon? Reimplementation would fork the tested logic and the bash doorbell would drift from it. A daemon adds lifecycle/ports for no gain on a single machine — and if a live web board is ever wanted, an HTTP daemon can be added later *on top of the same DB* without disturbing this.

## Data model (schema v3)

Existing (Layer 1, unchanged except one demotion):
- **`sessions`** — `session_id` PK, `label`, `cwd`, `branch`, `pid`, `status`, `current_task`, `started_at`, `last_seen`. `current_task` becomes a **free-text display string only** (no foreign key) — the authoritative task↔session relationship lives in Layer 2.
- **`commands`** — unchanged, task-agnostic.

New (Layer 2):
- **`tasks`** — `slug` TEXT PK (human handle, e.g. `oauth-refactor`), `title`, `description`, `status` TEXT (`todo` | `in_progress` | `blocked` | `done`), `created_at`, `updated_at`.
- **`task_sessions`** — `task_slug` → tasks.slug, `session_id` → sessions.session_id, `role` (`lead` | `helper`, optional), `assigned_at`. `UNIQUE(task_slug, session_id)`. Many-to-many: one task → 0..N sessions.

Rule: **a session has at most one *active* task.** Enforced in `task_assign` (assigning a session already linked to another non-done task is rejected unless `--force`, which reassigns). The link table itself stays many-to-many for history and multi-helper tasks.

`schema_version` → `3`. `mesh_migrate` adds the two tables (`CREATE TABLE IF NOT EXISTS`) and bumps the version; idempotent, additive, disturbs nothing existing.

## MCP tool surface

Layer 1 — `mesh_*` (thin wrappers over existing functions):
- `mesh_board()` → JSON of sessions (+ liveness) and recent commands.
- `mesh_dispatch(target, kind, payload)` → command id(s). `target` = label | session_id | `*`.
- `mesh_collect()` → newly-done command results (id, label, exit_state, output), acked.
- `mesh_pause()` / `mesh_resume()`.
- (`register` stays a worker-side skill concern; not needed as a master MCP tool.)

Layer 2 — `task_*`:
- `task_add(slug, title, description?)` → creates a `todo` task.
- `task_list(status?)` → JSON backlog, optionally filtered; each task includes its assigned sessions.
- `task_get(slug)` → one task with assignments.
- `task_assign(slug, target, role?, force?)` → link a session (resolved by label/id) to a task; enforces one-active-per-session.
- `task_unassign(slug, target)`.
- `task_set_status(slug, status)`; `task_done(slug)` shorthand.
- `task_spawn(slug, cwd)` → **Phase 2**: auto-start a worker.

## Phases (build sequentially)

**Phase 1 — Foundations.** Schema v3 + `mesh_task_*` functions in `mesh.sh` (with bash tests). The Python MCP server + `mesh_*`/`task_*` tools (minus spawn). Plugin registration. `/mesh-init` checks `uv`. Docs. Outcome: master maintains a real task list and links sessions, via typed tools; skills + doorbell unchanged.

**Phase 2 — Auto-spawn.** `mesh_task_spawn`: launch a worker with `claude --bg` in a given cwd (never `claude -p`), register it, assign it to the task, set task `in_progress`. Master stops hand-starting workers. Expose as `task_spawn`.

**Phase 3 — Active project management.** `mesh_ask(target, question)` — dispatch a `prompt` asking a satellite for status / next move and correlate the reply; `/mesh-board` gains a **backlog view** grouped by status alongside the session view; a "drive conversation" dispatch helper. Richest/vaguest — detailed at the start of Phase 3.

## Testing

- Extend the bash suite (`tests/test_mesh.sh`) for `mesh_task_*` (add/list/assign one-active enforcement/status/done) and the v2→v3 migration.
- A Python smoke test that imports the server module and invokes each tool function against a temp DB, asserting JSON shape and that writes land in the DB.

## Compatibility

- Purely additive. Existing skills call `mesh.sh` directly and keep working. The MCP tools are a second, typed front door to the same store. Existing v2 DBs migrate to v3 on next `mesh-init`; fresh DBs are created at v3.
