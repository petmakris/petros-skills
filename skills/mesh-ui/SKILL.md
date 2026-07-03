---
name: mesh-ui
description: Launch the Session Mesh live web board — a localhost dashboard showing the worker roster, the task backlog as a kanban, and the recent-command feed, with buttons to add tasks, spawn workers, and pause/resume the fleet. Use when the user wants a visual UI for the mesh instead of the text board, or asks to open the mesh dashboard / web board.
allowed-tools:
  - Bash
---

Launch the Session Mesh live board and open it in the browser.

The board is a tiny stdlib HTTP server (`session-mesh/ui/server.py`) that serves one page and, on every poll, shells into `mesh.sh` — the same single source of truth the MCP tools use. It binds to **127.0.0.1 only** (it drives autonomous workers, so it must never be reachable off the machine) and self-inits the DB on start. Idle cost is nil; it holds no state.

**Resolve the plugin root** (the Bash tool does not persist variables between calls, so keep this in the *same* invocation as the launch):

```bash
ROOT="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}"
```

1. Pick the port: default **8787**, or honor `MESH_UI_PORT` if the user named one.
2. If something already answers on that port (`curl -s -o /dev/null http://127.0.0.1:$PORT/api/board`), it's already running — just `open http://127.0.0.1:$PORT/` and stop.
3. Otherwise launch it **detached** so this session isn't blocked, then open the browser:
   ```bash
   nohup uv run --script "$ROOT/session-mesh/ui/server.py" --no-open \
       >~/.claude/session-mesh/ui.log 2>&1 &
   sleep 2 && curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:${MESH_UI_PORT:-8787}/api/board
   open "http://127.0.0.1:${MESH_UI_PORT:-8787}/"
   ```
   A `200` means it's up. Prereq: `uv` on PATH (same as the MCP server). If `curl` shows nothing, read `~/.claude/session-mesh/ui.log` for the error.
4. Tell the user the URL, and that it refreshes ~every 1.5s. To stop it: `pkill -f session-mesh/ui/server.py`.

**What they'll see:** worker roster (alive/stale dots, current activity, cwd·pid), the task backlog as a Todo / In progress / Blocked / Done kanban with worker chips, and the last 20 commands with their state. Buttons: **+ Add task**, **Spawn worker**, **Pause/Resume fleet**, plus per-task **Done / Spawn / Unblock** on hover. Every button calls a whitelisted `mesh.sh` function — the UI is a front door, not a second implementation.
