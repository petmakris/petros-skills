---
name: mesh-pause
description: Emergency stop for the whole Session Mesh fleet — pause all workers so no queued command executes on the next poll. Use when a dispatch went wrong or you need everything to hold. Workers keep heartbeating but stop claiming.
allowed-tools:
  - Bash
---

Pause the fleet.

**Mesh library.** The mesh code (`session-mesh/mesh.sh` + `schema.sql`) ships inside this plugin; runtime state (the DB and `live/`) stays machine-local under `~/.claude/session-mesh` and is never committed. Resolve the library path and reuse it. The Bash tool does **not** persist shell variables between calls, so include this `MESH=` assignment in the *same* Bash invocation as any `source "$MESH"` below:

```bash
MESH="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}/session-mesh/mesh.sh"
```

1. Run: `source "$MESH" && mesh_pause && mesh_is_paused`.
2. Expect `1`. Confirm to the user that all workers will skip command execution (still heartbeat) until /mesh-resume.
