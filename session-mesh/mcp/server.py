# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp>=1.28"]
# ///
"""Session Mesh MCP server — a thin, typed front door over mesh.sh.

Every tool shells into a mesh.sh function and returns its output: JSON for
reads (shaped by SQLite's json_* functions in mesh.sh), an id/slug/status for
writes. All state logic — including the race-sensitive claim/reap/resolve — and
all SQL live in mesh.sh, the single source of truth. This process holds no
state; each session spawns its own instance and they share the one SQLite DB.

Layer 1 tools (mesh_*) drive live sessions. Layer 2 tools (task_*) manage a
task backlog that is independent of sessions and points at them only via
mesh_dispatch. Run with:  uv run --script server.py
"""
from __future__ import annotations

import json
import subprocess
from pathlib import Path

from mcp.server.fastmcp import FastMCP

# mesh.sh sits one directory up from this file (session-mesh/mesh.sh).
MESH_LIB = str(Path(__file__).resolve().parent.parent / "mesh.sh")

# source mesh.sh, then run: <fn> <args...>  — args pass via argv, never
# interpolated into the script, so there is no shell-quoting hazard.
_RUNNER = 'source "$1"; shift; "$@"'

mcp = FastMCP("session-mesh")


def _mesh(fn: str, *args: str) -> str:
    """Call a mesh.sh function and return its stdout. Raise on nonzero exit."""
    proc = subprocess.run(
        ["bash", "-c", _RUNNER, "mesh", MESH_LIB, fn, *[str(a) for a in args]],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        msg = proc.stderr.strip() or f"{fn} failed (exit {proc.returncode})"
        raise ValueError(msg)
    return proc.stdout


# --------------------------------------------------------------------------
# Layer 1 — mesh (transport). Reach and drive live sessions.
# --------------------------------------------------------------------------

@mcp.tool()
def mesh_board() -> dict:
    """Live mesh snapshot: every session (with alive/stale liveness, label,
    status, current_task, pid, cwd) and the 20 most recent commands."""
    return json.loads(_mesh("mesh_board_json"))


@mcp.tool()
def mesh_dispatch(target: str, kind: str, payload: str) -> dict:
    """Queue a command for a worker to run autonomously.

    target: a worker label, a session_id, or '*' for broadcast.
    kind:   'shell' (run in the worker's cwd) | 'prompt' (have it carry out a
            task) | 'control' (payload 'stop' disarms the worker).
    Returns the queued command id(s)."""
    out = _mesh("mesh_dispatch", target, kind, payload, "mcp").strip()
    ids = [int(t) for t in out.split() if t.isdigit()]
    return {"command_ids": ids}


@mcp.tool()
def mesh_collect() -> list:
    """Pull newly-finished command results (id, label, exit_state, output) and
    acknowledge them so they are not returned again. Empty list = nothing new."""
    return json.loads(_mesh("mesh_collect_json"))


@mcp.tool()
def mesh_pause() -> str:
    """Pause the whole fleet — workers keep heartbeating but stop claiming
    queued commands until resumed."""
    _mesh("mesh_pause")
    return "paused"


@mcp.tool()
def mesh_resume() -> str:
    """Lift the fleet-wide pause; workers resume claiming on their next poll."""
    _mesh("mesh_resume")
    return "resumed"


# --------------------------------------------------------------------------
# Layer 2 — task manager (app). A backlog independent of sessions.
# --------------------------------------------------------------------------

@mcp.tool()
def task_add(slug: str, title: str, description: str = "") -> dict:
    """Add a task to the backlog (status 'todo'). slug is a human handle used
    to reference the task, e.g. 'oauth-refactor'."""
    _mesh("mesh_task_add", slug, title, description)
    return {"slug": slug, "status": "todo"}


@mcp.tool()
def task_list(status: str = "") -> list:
    """List backlog tasks, each with its assigned sessions. Optional status
    filter: todo | in_progress | blocked | done."""
    return json.loads(_mesh("mesh_task_list", status))


@mcp.tool()
def task_get(slug: str) -> dict | None:
    """Get one task (with its assigned sessions), or null if no such slug."""
    arr = json.loads(_mesh("mesh_task_get", slug))
    return arr[0] if arr else None


@mcp.tool()
def task_assign(slug: str, target: str, role: str = "lead", force: bool = False) -> dict:
    """Assign a session (by label or session_id) to a task. A session may hold
    only one active (non-done) task; pass force=true to move it from another."""
    sid = _mesh("mesh_task_assign", slug, target, role, "force" if force else "").strip()
    return {"task": slug, "session_id": sid}


@mcp.tool()
def task_unassign(slug: str, target: str) -> str:
    """Remove a session's assignment from a task."""
    _mesh("mesh_task_unassign", slug, target)
    return "unassigned"


@mcp.tool()
def task_set_status(slug: str, status: str) -> dict:
    """Set a task's status: todo | in_progress | blocked | done."""
    _mesh("mesh_task_set_status", slug, status)
    return {"slug": slug, "status": status}


@mcp.tool()
def task_done(slug: str) -> dict:
    """Mark a task done (shorthand for status 'done')."""
    _mesh("mesh_task_done", slug)
    return {"slug": slug, "status": "done"}


if __name__ == "__main__":
    # Ensure the store exists / is migrated to the current schema. Idempotent
    # and cheap, so every session that spawns this server is self-sufficient —
    # no separate /mesh-init step, and existing DBs get upgraded in place.
    _mesh("mesh_init")
    mcp.run()
