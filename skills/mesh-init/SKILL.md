---
name: mesh-init
description: Initialize the Session Mesh SQLite database (once per machine). Use before any other mesh skill, or when a mesh skill reports the database is missing.
allowed-tools:
  - Bash
---

Initialize the Session Mesh store.

**Mesh library.** The mesh code (`session-mesh/mesh.sh` + `schema.sql`) ships inside this plugin; runtime state (the DB and `live/`) stays machine-local under `~/.claude/session-mesh` and is never committed. Resolve the library path and reuse it. The Bash tool does **not** persist shell variables between calls, so include this `MESH=` assignment in the *same* Bash invocation as any `source "$MESH"` below:

```bash
MESH="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}/session-mesh/mesh.sh"
```

1. Verify prerequisites: `sqlite3 --version` (must be >= 3.35 for RETURNING) and `command -v uuidgen shasum`.
2. Run: `source "$MESH" && mesh_init`.
3. Confirm: `sqlite3 ~/.claude/session-mesh/mesh.db "SELECT name FROM sqlite_master WHERE type='table';"` — expect `sessions`, `commands`, `mesh_meta`.
4. Report the DB path and that the mesh is ready. This is idempotent; safe to re-run.
