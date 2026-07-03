---
name: mesh-await
description: From the master, wait for worker results WITHOUT a polling loop — arm a detached background watcher that BLOCKS until a dispatched command completes, then wakes this session exactly once to surface the result. Use after /mesh-dispatch when you want to be notified the moment a worker finishes, with no terminal spam and zero tokens spent while waiting.
allowed-tools:
  - Bash
  - Skill
---

Arm a quiet, event-driven wait for outstanding worker results. The waiting happens in a **detached background shell** (pure SQLite, zero model tokens); the model wakes only when a result is actually ready. This is the master-side twin of /mesh-arm.

Mechanism: `mesh_watch_collect` blocks until at least one completed-but-uncollected command exists, then exits. Claude Code re-invokes this session when a `run_in_background` command exits — so the watcher's exit IS the wake-up.

**Mesh library.** The mesh code (`session-mesh/mesh.sh` + `schema.sql`) ships inside this plugin; runtime state (the DB and `live/`) stays machine-local under `~/.claude/session-mesh` and is never committed. Resolve the library path and reuse it. The Bash tool does **not** persist shell variables between calls, so include this `MESH=` assignment in the *same* Bash invocation as any `source "$MESH"` below (for a backgrounded command, set it at the top of that same blob):

```bash
MESH="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}/session-mesh/mesh.sh"
```

## A. Arm the result-watcher

1. Launch **in the background** (Bash tool with `run_in_background: true`):
   ```
   source "$MESH" && mesh_watch_collect
   ```
   This blocks until one or more dispatched commands finish, then exits.
2. End your turn with **no prose**. The harness re-invokes you when the watcher exits (a result landed).

## B. On wake (a result is ready)

3. Collect + acknowledge in one call:
   ```
   source "$MESH" && mesh_collect
   ```
   It prints one line per newly-done command — `id <TAB> ticket <TAB> exit_state` — and marks them collected so they are never shown twice.
4. For each `id`, fetch the full output to show the user:
   ```
   source "$MESH" && mesh_cmd_output <id>
   ```
5. Present the results to the user: which worker (ticket), ok/fail, and the output.
6. If commands are still outstanding (dispatched but not yet done) and you want to keep waiting, **re-arm** by going back to A.1. Otherwise stop.
