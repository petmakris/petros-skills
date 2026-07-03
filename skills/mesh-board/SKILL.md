---
name: mesh-board
description: Show the live Session Mesh board from the master session — every registered worker, its label, whether it is alive or stale, its current task, and recent commands. Use when the user asks what sessions are running, mesh status, or the board.
allowed-tools:
  - Bash
---

Render the mesh board for the master.

**Mesh library.** The mesh code (`session-mesh/mesh.sh` + `schema.sql`) ships inside this plugin; runtime state (the DB and `live/`) stays machine-local under `~/.claude/session-mesh` and is never committed. Resolve the library path and reuse it. The Bash tool does **not** persist shell variables between calls, so include this `MESH=` assignment in the *same* Bash invocation as any `source "$MESH"` below:

```bash
MESH="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}/session-mesh/mesh.sh"
```

1. Run: `source "$MESH" && mesh_board`. If the DB is missing, tell the user to run /mesh-init.
2. The output is two tab-separated blocks: sessions (`label, alive|stale, status, current_task, cwd`) then recent commands (`id, target_label, kind, state`).
3. For each session, additionally cross-check the OS process: read its pid via `sqlite3 ~/.claude/session-mesh/mesh.db "SELECT label,pid FROM sessions;"` and mark a session **dead** if `kill -0 <pid>` fails (process gone) even when last_seen looks recent.
4. Present a clean markdown table to the user: Label | Liveness (alive/stale/dead) | Status | Current task, followed by a short list of the most recent commands and their states. Call out any stale/dead sessions explicitly.
5. Also show the **Layer-2 backlog**: run `source "$MESH" && mesh_task_list` (JSON array of tasks, each with `status` and assigned `sessions`). Render a second table — Task | Status | Workers — grouped/ordered by status (in_progress, blocked, todo, done). If there are no tasks, say the backlog is empty. This is the project-management view; the session table above is the transport view.
