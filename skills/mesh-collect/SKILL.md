---
name: mesh-collect
description: From the master, pull any finished worker results right now (no waiting) — prints newly-completed command outputs and marks them collected so they are not shown again. Use for an instant one-shot check of the mesh inbox; use /mesh-await instead when you want to be woken the moment results land.
allowed-tools:
  - Bash
---

Do a single, synchronous pull of completed worker results. No blocking, no loop — just check the inbox once.

**Mesh library.** The mesh code (`session-mesh/mesh.sh` + `schema.sql`) ships inside this plugin; runtime state (the DB and `live/`) stays machine-local under `~/.claude/session-mesh` and is never committed. Resolve the library path and reuse it. The Bash tool does **not** persist shell variables between calls, so include this `MESH=` assignment in the *same* Bash invocation as any `source "$MESH"` below:

```bash
MESH="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}/session-mesh/mesh.sh"
```

1. Collect + acknowledge:
   ```
   source "$MESH" && mesh_collect
   ```
   Prints one line per newly-done command — `id <TAB> label <TAB> exit_state` — and marks them collected. **Empty output = nothing new to report.**
2. For each `id`, fetch the full text:
   ```
   source "$MESH" && mesh_cmd_output <id>
   ```
3. Present the results (which worker, ok/fail, output). If step 1 was empty, tell the user there is nothing new to collect.
