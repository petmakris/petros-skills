---
name: mesh-join
description: Join the Session Mesh as a live worker in one command — register this session under a free-form label, then arm the event-driven background watcher (the "doorbell") so it accepts and autonomously executes the master session's commands. Quiet by design — costs zero tokens while idle and never spams the terminal with poll ticks. Use when the user wants this session to become an active mesh worker (register + arm).
allowed-tools:
  - Bash
  - Skill
---

Join the mesh as a live worker in one step: register, then arm the doorbell.

**Mesh library.** The mesh code (`session-mesh/mesh.sh` + `schema.sql`) ships inside this plugin; runtime state (the DB and `live/`) stays machine-local under `~/.claude/session-mesh` and is never committed. Resolve the library path and reuse it. The Bash tool does **not** persist shell variables between calls, so include this `MESH=` assignment in the *same* Bash invocation as any `source "$MESH"` below:

```bash
MESH="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}/session-mesh/mesh.sh"
```

1. Determine the worker **label** — a free-form handle the master uses to dispatch to and identify this worker. Any string works (`docs-rewrite`, `db-migration`, `reporting-openapi`, or a Jira key like `PMP-211`); there is no required format and it is **not** tied to Jira or a branch name.
   - If the user supplied a label with the invocation (e.g. `/mesh-join docs-rewrite`), use it verbatim.
   - Otherwise **ask the user** what to label this worker. Do not derive it from the branch or assume a Jira ticket — you may offer the repo/branch name as a hint, but let the user choose.

2. Register this session:
   - `cwd="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"`
   - `branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"`
   - `label="<the label from step 1>"`
   - `pid="$PPID"`
   - `source "$MESH" && mesh_register "$cwd" "$branch" "$label" "$pid"`
   If it errors that the DB is missing, run /mesh-init first, then retry.

3. Report the label, cwd, and the printed session_id. Warn the user plainly: from now on this session will execute whatever the master queues, autonomously (full autonomy). The master can `/mesh-pause` to halt the fleet; disarming the watcher (a `control stop` command, or ending the session) takes this worker offline.

4. Arm the doorbell by invoking the /mesh-arm skill. This launches a detached background watcher that heartbeats and blocks until a command is queued for this session — with no model wake-ups while idle — then wakes the session once per real command to execute it and re-arms. (This replaces the old `/loop 1m /mesh-poll` polling, which woke the model every minute and flooded the terminal.)

5. Confirm the worker is armed and listening. It will stay quiet until the master dispatches a command. To go offline, dispatch a `control stop` command to this worker (or end the session).
