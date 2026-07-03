---
name: mesh-poll
description: One Session Mesh poll tick for a worker — heartbeat, then claim and execute any command the master queued for this session. Intended to run on a loop via /loop 1m /mesh-poll. Full-autonomy execution; respects the global pause switch.
allowed-tools:
  - Bash
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

Perform one poll tick for this worker.

**Stay quiet.** This runs every minute, so keep terminal noise to a minimum. On a no-op tick — no command claimed, or the mesh is paused — produce NO prose output at all (just run the bash and end the turn silently). Only write a report when a command was actually claimed and executed. Keep any such report to a single concise line.

**Mesh library.** The mesh code (`session-mesh/mesh.sh` + `schema.sql`) ships inside this plugin; runtime state (the DB and `live/`) stays machine-local under `~/.claude/session-mesh` and is never committed. Resolve the library path and reuse it. The Bash tool does **not** persist shell variables between calls, so include this `MESH=` assignment in the *same* Bash invocation as any `source "$MESH"` below:

```bash
MESH="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}/session-mesh/mesh.sh"
```

1. Resolve identity: `cwd="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"`, `sid="$(source "$MESH" && mesh_session_id "$cwd")"`. If empty, run /mesh-register first and stop this tick.
2. Heartbeat: `source "$MESH" && mesh_heartbeat "$sid"`.
3. If `mesh_is_paused` prints `1`, stop this tick silently (do not claim, do not report).
4. Claim one command: `claim="$(source "$MESH" && mesh_claim "$sid")"`. If empty, stop this tick silently (no output).
5. Parse `claim` as `id|kind|payload` (split on the FIRST two `|`; payload may contain `|`).
6. Execute by kind (full autonomy — no approval gate):
   - `shell`: run the payload as a shell command in `cwd`; capture combined output + exit code.
   - `prompt`: carry out the payload as a task in this session, using your normal tools; summarize what you did.
   - `control`: if payload is `stop`, record completion and do NOT reschedule the loop (end polling); otherwise ignore.
7. Record the result: `source "$MESH" && mesh_complete "<id>" "<ok|fail>" "<short output/summary, tail-truncated>"`.
8. Report — in ONE concise line — which command ran and its result. Any pending commands will be picked up by the next tick; do not add extra commentary.
