# Session Mesh — testing handover

Resume point for **live** testing of the two-layer mesh + MCP task manager. The automated suites already pass; this doc covers what only a real, interactive run can verify (spawning real `claude --bg` workers), so a fresh session can pick up exactly here.

## Status snapshot (as of 2026-07-03)

Built and pushed to `main`, all three phases:

| Phase | Commit | What |
|---|---|---|
| Spec | `f527aeb` | design: two-layer mesh + MCP task manager |
| 1 — Foundations | `e3b80a6` | schema v3 (`tasks`,`task_sessions`) + `mesh_task_*` + MCP server (12 tools) + plugin registration |
| 2 — Auto-spawn | `19b9897` | `task_spawn` → `claude --bg`, join+assign+dispatch |
| 3 — Active PM | `3076b50` | `mesh_ask`/`task_ask`, backlog on the board (15 tools) |

**Automated tests — green:**
```bash
bash session-mesh/tests/test_mesh.sh        # engine + Layer-2 + migration   → 68/68
uv run --script session-mesh/tests/test_server.py   # MCP adapter            → 13/13
```

**Architecture** (full detail: `session-mesh/specs/2026-07-03-mesh-mcp-task-manager-design.md`):
- **Layer 1 — transport:** `sessions` + `commands`, doorbell, reaper. Task-agnostic. Exposed as `/mesh-*` skills and `mesh_*` MCP tools.
- **Layer 2 — task manager:** `tasks` + `task_sessions` (a backlog independent of sessions; one *active* task per session). Exposed as `task_*` MCP tools. Meets Layer 1 only via `mesh_dispatch`.
- **Delivery:** stdio MCP server `session-mesh/mcp/server.py` (Python/FastMCP), thin adapter over `mesh.sh` (single source of truth), `uv run --script` (no install). One instance per session over the shared DB — the DB is the hub. Self-inits on start.
- **Doorbell stays shell** (`/mesh-arm`, `/mesh-await`), not MCP — a blocking tool call would hold the model turn open.

## What still needs LIVE verification

Everything below is beyond what the automated suites cover, because it requires spawning **real** background Claude sessions. The one code path not exercised automatically is the actual `claude --bg` launch (isolated in `mesh_spawn_launch`; the suite stubs it via `MESH_CLAUDE_BIN`).

### Step 0 — make the MCP server live (REQUIRED FIRST)
The `session-mesh` MCP server is declared in `.claude-plugin/plugin.json`, but Claude Code only loads MCP servers at startup.
- [ ] Restart Claude Code / reload the `petros-skills` plugin.
- [ ] Verify the server registered: `/mcp` (interactive) or `claude mcp list` — expect `session-mesh` present.
- [ ] Verify the tools are callable: ask Claude to run `mesh_board` — expect JSON with `sessions`, `commands`, `tasks` keys. (15 tools total; `mesh_*` + `task_*`.)
- Prereq: `uv` on PATH (used to launch the server). Present on this machine.

### Step 1 — task backlog CRUD (no worker needed)
- [ ] `task_add` a throwaway task (e.g. slug `smoke-1`, title "Handover smoke").
- [ ] `task_list` shows it as `todo`; `/mesh-board` shows a backlog section.
- [ ] `task_set_status smoke-1 blocked` then `task_done smoke-1` — status advances.

### Step 2 — real auto-spawn (the headline unverified path)
- [ ] Pick a real throwaway worktree/dir as `cwd`.
- [ ] `task_add` a task, then `task_spawn <slug> <cwd>`.
- [ ] Confirm a background agent actually launched: `claude agents --json` (look for a session in that `cwd`).
- [ ] `/mesh-board`: the worker appears **alive**, the task is **in_progress**, an assignment exists, and a `prompt` command was dispatched to it.
- [ ] If the worker does NOT appear: the launch invocation is the thing to check — see **Tweak points** below.

### Step 3 — worker executes + reports back
- [ ] The spawned worker should claim the dispatched `prompt`, do it, and `mesh_complete`.
- [ ] Master side: `/mesh-await` (event-driven) or `mesh_collect` returns the worker's summary as the command output.

### Step 4 — ask round-trip (status / next move)
- [ ] `mesh_ask <label> "what's your status?"` (or `task_ask <slug> "next move?"`).
- [ ] `/mesh-await` → `mesh_collect`: the worker's concise answer comes back as output.

### Step 5 — relationship rules, live
- [ ] Assign a worker already on an active task to a second task → rejected; with `force` → it moves.
- [ ] Assign **two** workers to one task → `/mesh-board` shows both under that task.

### Step 6 — doorbell + fleet control unaffected
- [ ] A hand-started worker (`/mesh-join <label>` in its own session) still receives dispatches.
- [ ] `/mesh-pause` halts claiming; `/mesh-resume` restores it.

### Step 7 — teardown
- [ ] `task_done` the test tasks; stop spawned agents (`claude stop <id>`); the reaper re-queues anything a killed worker left running.

## Tweak points (where to fix if live testing fails)

- **`claude --bg` invocation** — `mesh_spawn_launch()` in `session-mesh/mesh.sh`. Currently:
  `cd <cwd> && claude --bg --plugin-dir <plugin-root> "/mesh-join <label>"`.
  This is the ONLY piece not covered by automated tests. Override the binary with `MESH_CLAUDE_BIN` to dry-run. Confirmed against docs (agent-view.md): `--bg` detaches, runs the slash command as first prompt, `--plugin-dir` gives it the mesh skills+MCP.
- **MCP server won't start** — run it by hand and watch stderr:
  `uv run --script session-mesh/mcp/server.py` (it will block on stdio; Ctrl-C). First run resolves `mcp` via uv (network).
- **A tool errors** — the message is the underlying `mesh.sh` stderr (the adapter re-raises it verbatim).
- **DB inspection** — `sqlite3 ~/.claude/session-mesh/mesh.db "SELECT * FROM tasks;"` etc. Schema is **v3**.

## Key files
```
session-mesh/mesh.sh                     # all engine + task logic (single source of truth)
session-mesh/schema.sql                  # v3 schema
session-mesh/mcp/server.py               # MCP adapter (15 tools)
session-mesh/tests/test_mesh.sh          # bash suite (68)
session-mesh/tests/test_server.py        # MCP smoke (13)
session-mesh/specs/2026-07-03-mesh-mcp-task-manager-design.md   # design
.claude-plugin/plugin.json               # mcpServers registration
skills/mesh-*/SKILL.md                   # transport skills (+ /mesh-help cheatsheet)
docs/atlas/session-mesh.html             # operator's manual
```
