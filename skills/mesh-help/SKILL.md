---
name: mesh-help
description: Print a Session Mesh cheatsheet inside the current Claude session — every mesh command, what it does, who runs it (master vs worker), and the typical workflow. Use when the user asks what mesh commands exist, how the mesh works, or for a mesh cheatsheet / help / overview.
allowed-tools: []
---

Print the cheatsheet below to the user **verbatim** as your response (it is markdown; render it as-is). Do not run any tools, do not touch the mesh database, do not summarize — just output it. If the user asked about one specific command, print the whole sheet but call out the relevant row first.

---

# 🕸️  Session Mesh — Cheatsheet

The mesh lets one **master** session drive many **worker** sessions on the same machine over a shared SQLite store. Workers execute queued commands autonomously; the master dispatches and collects results.

### 🔧 Setup — run once per machine
| Command | Role | What it does |
|---|---|---|
| `/mesh-init` | anyone | Create the SQLite store (`~/.claude/session-mesh/mesh.db`). Idempotent. Run before anything else. |

### 👷 Worker — turn a session into a mesh worker
| Command | What it does |
|---|---|
| `/mesh-join` | **The one-liner.** Register this session under a free-form **label** **and** arm the doorbell watcher. Use this to become a worker. Pass a label (`/mesh-join docs-rewrite`) or you'll be asked for one. |
| `/mesh-arm` | Arm just the event-driven "doorbell" — a detached watcher that blocks until a command is queued, wakes the session once to run it, then re-arms. Zero tokens while idle, no terminal spam. |
| `/mesh-poll` | A single poll tick (heartbeat + claim + execute). The old loop-based worker: `/loop 1m /mesh-poll`. Prefer `/mesh-arm` instead. |

> To take a worker **offline**: dispatch it a `control stop`, or end the session.

### 🎛️  Master — drive the fleet
| Command | What it does |
|---|---|
| `/mesh-dispatch` | Queue a command for a worker. Target a **label** (e.g. `docs-rewrite`, `PMP-211`), a **session id**, or `*` for broadcast. Kinds: `shell` (run in worktree), `prompt` (carry out a task), `control` (`stop`). |
| `/mesh-await` | After dispatch, arm a watcher that blocks until the command completes, then wakes you once with the result. No polling, no spam. |
| `/mesh-collect` | One-shot pull of any finished results **right now** (no waiting). Marks them collected so you don't see them twice. |
| `/mesh-board` | Show the live board — every worker, its label, alive / stale / dead, current task, and recent commands. |

### 🛑 Fleet control — emergency switches
| Command | What it does |
|---|---|
| `/mesh-pause` | Emergency stop the whole fleet — workers keep heartbeating but stop claiming queued commands. |
| `/mesh-resume` | Lift the pause — workers resume claiming on their next poll. |

### 📋 Task manager — MCP tools (Layer 2)
The mesh is two layers: **transport** (the slash-commands above — reach & drive live sessions) and a **task manager** (a backlog independent of sessions). The task manager is exposed as **MCP tools** (from the plugin's `session-mesh` MCP server), so you call them by asking, e.g. *"add a task…", "spawn a worker for…", "ask the worker its status"* — not as `/slash` commands.

| Tool | What it does |
|---|---|
| `task_add` / `task_list` / `task_get` | Manage the backlog (tasks exist independent of any session). |
| `task_assign` / `task_unassign` | Link a session to a task (one active task per session; `force` to move it). |
| `task_spawn` | **Auto-spawn** a `claude --bg` worker for a task, join + assign + dispatch it. |
| `task_set_status` / `task_done` | Advance a task's lifecycle (todo → in_progress → blocked → done). |
| `mesh_ask` / `task_ask` | Ask a worker / a task's workers for **status or next move**; answers return via `/mesh-collect` or `/mesh-await`. |

(`mesh_board`, `mesh_dispatch`, `mesh_collect`, `mesh_pause`, `mesh_resume` also exist as MCP tools — same operations as the skills, typed.)

### 🔁 Typical flow
```
[once]     /mesh-init
[worker A] /mesh-join docs-rewrite            → registers under a label + arms, waits quietly
[master]   task_add "ship-oauth" "Ship OAuth" → backlog item (no session needed yet)
[master]   task_spawn ship-oauth <cwd>        → auto-spawns a worker, assigns + dispatches it
[master]   /mesh-board                         → sessions + backlog, who's on what
[master]   task_ask ship-oauth "status?"       → pull status/next-move from the worker
[master]   /mesh-await                          → get woken when the answer lands
[oops]     /mesh-pause  →  fix  →  /mesh-resume
```

**Roles at a glance:** `init` = setup · `join` / `arm` / `poll` = worker · `dispatch` / `await` / `collect` / `board` = master · `pause` / `resume` = fleet-wide · `task_*` = the master's project-management layer (MCP).
