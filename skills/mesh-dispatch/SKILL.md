---
name: mesh-dispatch
description: From the master session, queue a command for a worker session to execute autonomously on its next poll. Use when the user wants to tell another session (by ticket, session id, or all) to run something. Target a ticket like PMP-211, a session id, or * for broadcast.
allowed-tools:
  - Bash
---

Queue a command on the mesh for a worker.

**Mesh library.** The mesh code (`session-mesh/mesh.sh` + `schema.sql`) ships inside this plugin; runtime state (the DB and `live/`) stays machine-local under `~/.claude/session-mesh` and is never committed. Resolve the library path and reuse it. The Bash tool does **not** persist shell variables between calls, so include this `MESH=` assignment in the *same* Bash invocation as any `source "$MESH"` below:

```bash
MESH="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}/session-mesh/mesh.sh"
```

1. Parse from the user request: `target` (a ticket e.g. PMP-211, a session_id, or `*`), `kind` (`shell` = run a shell command in the worker's worktree; `prompt` = have the worker carry out a task; `control` = `stop`), and `payload` (the command/prompt text).
2. Identify the master session id for provenance: `by="master:$(cd ~/projects/montblanc && git rev-parse --show-toplevel)"` (or any stable label).
3. Dispatch: `source "$MESH" && mesh_dispatch "<target>" "<kind>" "<payload>" "<by>"`. It prints the new command id(s).
4. Confirm to the user: what was queued, for whom, and the command id(s). Note it runs on the worker's next poll (<=30s) under full autonomy, and that /mesh-board will show the result state.
5. If the target resolves to no session (nothing printed), warn the user that no live worker matches — they may need to register that session first.
