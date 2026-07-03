---
name: mesh-resume
description: Lift the Session Mesh global pause so workers resume claiming and executing queued commands on their next poll. Use after /mesh-pause when it is safe to continue.
allowed-tools:
  - Bash
---

Resume the fleet.

**Mesh library.** The mesh code (`session-mesh/mesh.sh` + `schema.sql`) ships inside this plugin; runtime state (the DB and `live/`) stays machine-local under `~/.claude/session-mesh` and is never committed. Resolve the library path and reuse it. The Bash tool does **not** persist shell variables between calls, so include this `MESH=` assignment in the *same* Bash invocation as any `source "$MESH"` below:

```bash
MESH="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}/session-mesh/mesh.sh"
```

1. Run: `source "$MESH" && mesh_resume && mesh_is_paused`.
2. Expect `0`. Confirm to the user that workers will resume executing queued commands on their next poll.
