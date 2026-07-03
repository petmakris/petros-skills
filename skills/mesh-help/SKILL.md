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
| `/mesh-join` | **The one-liner.** Register this session **and** arm the doorbell watcher. Use this to become a worker. |
| `/mesh-arm` | Arm just the event-driven "doorbell" — a detached watcher that blocks until a command is queued, wakes the session once to run it, then re-arms. Zero tokens while idle, no terminal spam. |
| `/mesh-poll` | A single poll tick (heartbeat + claim + execute). The old loop-based worker: `/loop 1m /mesh-poll`. Prefer `/mesh-arm` instead. |

> To take a worker **offline**: dispatch it a `control stop`, or end the session.

### 🎛️  Master — drive the fleet
| Command | What it does |
|---|---|
| `/mesh-dispatch` | Queue a command for a worker. Target a **ticket** (e.g. `PMP-211`), a **session id**, or `*` for broadcast. Kinds: `shell` (run in worktree), `prompt` (carry out a task), `control` (`stop`). |
| `/mesh-await` | After dispatch, arm a watcher that blocks until the command completes, then wakes you once with the result. No polling, no spam. |
| `/mesh-collect` | One-shot pull of any finished results **right now** (no waiting). Marks them collected so you don't see them twice. |
| `/mesh-board` | Show the live board — every worker, its ticket, alive / stale / dead, current task, and recent commands. |

### 🛑 Fleet control — emergency switches
| Command | What it does |
|---|---|
| `/mesh-pause` | Emergency stop the whole fleet — workers keep heartbeating but stop claiming queued commands. |
| `/mesh-resume` | Lift the pause — workers resume claiming on their next poll. |

### 🔁 Typical flow
```
[once]     /mesh-init
[worker A] /mesh-join                         → registers + arms, waits quietly
[worker B] /mesh-join
[master]   /mesh-board                         → see who's alive
[master]   /mesh-dispatch PMP-211 "run tests"  → queue work for a worker
[master]   /mesh-await                          → get woken when it finishes
           …or /mesh-collect                    → grab results on demand
[oops]     /mesh-pause  →  fix  →  /mesh-resume
```

**Roles at a glance:** `init` = setup · `join` / `arm` / `poll` = worker · `dispatch` / `await` / `collect` / `board` = master · `pause` / `resume` = fleet-wide.
