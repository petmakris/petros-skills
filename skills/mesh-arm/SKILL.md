---
name: mesh-arm
description: Arm this worker as an event-driven "doorbell" on the Session Mesh — a detached background shell watcher that heartbeats and BLOCKS until a command is queued for this session, WITHOUT waking the model, then wakes the session exactly once to execute it and re-arms. Use this INSTEAD of /mesh-join or /loop /mesh-poll to run a quiet worker that costs zero tokens while idle and never spams the terminal with poll ticks.
allowed-tools:
  - Bash
  - Read
  - Edit
  - Write
  - Grep
  - Glob
  - Skill
---

Run this worker as an event-driven doorbell instead of a polling loop. The heavy waiting happens in a **detached background shell** (heartbeat + claim, pure SQLite, zero model tokens). The model wakes only when a real command is claimed. This replaces the noisy `/loop 1m /mesh-poll` pattern.

Mechanism: `mesh_watch_worker` blocks until it claims a command, then exits. Claude Code re-invokes this session when a `run_in_background` command exits — so the watcher's exit IS the wake-up signal. There is no polling by the model.

**Mesh library.** The mesh code (`session-mesh/mesh.sh` + `schema.sql`) ships inside this plugin; runtime state (the DB and `live/`) stays machine-local under `~/.claude/session-mesh` and is never committed. Resolve the library path and reuse it. The Bash tool does **not** persist shell variables between calls, so include this `MESH=` assignment in the *same* Bash invocation as any `source "$MESH"` below (for a backgrounded command, set it at the top of that same blob):

```bash
MESH="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}/session-mesh/mesh.sh"
```

## A. Arm (or re-arm) the watcher

1. Resolve identity:
   - `cwd="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"`
   - `sid="$(source "$MESH" && mesh_session_id "$cwd")"`
   - If `sid` is empty, register first: run /mesh-register (or /mesh-join step 1) to create the session row, then re-resolve `sid`.
2. Launch the watcher **in the background** — this is the whole trick. Use the **Bash tool with `run_in_background: true`**:
   ```
   source "$MESH" && mesh_watch_worker "$sid"
   ```
   It heartbeats every few seconds (keeping the board green) and blocks until a command is claimed for this session, then prints `id|kind|payload` and exits.
3. End your turn with **NO prose**. Do not poll, do not comment, do not loop. The harness will re-invoke you when the background watcher exits (i.e. the instant a command arrives).

## B. On wake (the watcher exited → a command was claimed)

The background task's output is a single line: `id|kind|payload`. **Split on the FIRST two `|` only** — the payload may itself contain `|`.

4. Heartbeat immediately so the board stays green during execution:
   `source "$MESH" && mesh_heartbeat "$sid"`
5. Execute by `kind` (full autonomy — no approval gate):
   - `shell`: run `payload` as a shell command in `cwd`; capture combined output + exit code.
   - `prompt`: carry out `payload` as a task in this session using your normal tools; summarize what you did.
   - `control`: if `payload` is `stop`, record completion and **STOP — do NOT re-arm** (worker goes offline). Otherwise ignore.
6. Record the result:
   `source "$MESH" && mesh_complete "<id>" "<ok|fail>" "<short output/summary, tail-truncated>"`
7. Report in **ONE concise line** what ran and its result.
8. **RE-ARM:** unless the command was `control stop`, go back to step **A.2** — launch `mesh_watch_worker "$sid"` in the background again so the doorbell listens for the next command — then end the turn silently.

Note: while you execute a command (steps 4–7) the watcher is briefly not armed. That is fine — commands queue in the DB and are claimed the instant you re-arm. Nothing is lost.
