# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp>=1.28"]
# ///
"""Smoke test for the MCP adapter: proves each tool wires to mesh.sh and that
reads round-trip as structured JSON. Run:  uv run --script test_server.py
(all state logic is covered by the bash suite; this checks the Python seam.)"""
import importlib.util
import os
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
os.environ["MESH_HOME"] = tempfile.mkdtemp(prefix="mesh_srv_")
os.environ["MESH_DB"] = os.path.join(os.environ["MESH_HOME"], "mesh.db")

spec = importlib.util.spec_from_file_location("mesh_server", HERE.parent / "mcp" / "server.py")
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)

P = F = 0
def check(cond, msg):
    global P, F
    if cond: P += 1
    else: F += 1; print(f"FAIL: {msg}")

# The @mcp.tool() decorator must return a directly-callable function.
check(callable(srv.task_add), "tool functions are callable")

srv._mesh("mesh_init")
check(srv.mesh_board()["sessions"] == [], "mesh_board returns empty sessions on a fresh DB")

srv.task_add("oauth", "Refactor OAuth", "make it nice")
tasks = srv.task_list()
check(len(tasks) == 1 and tasks[0]["slug"] == "oauth", "task_add + task_list round-trip")
check(srv.task_list("done") == [], "task_list status filter works")

# register a session (Layer 1) then assign it (Layer 2)
srv._mesh("mesh_register", "/tmp/wt/srvtest", "main", "srv-worker", str(os.getpid()))
res = srv.task_assign("oauth", "srv-worker")
check(res["session_id"], "task_assign resolves and returns a session_id")
got = srv.task_get("oauth")
check(got and len(got["sessions"]) == 1 and got["sessions"][0]["label"] == "srv-worker",
      "task_get embeds the assigned session")

# dispatch to the session, board reflects a command
d = srv.mesh_dispatch("srv-worker", "shell", "echo hi")
check(len(d["command_ids"]) == 1, "mesh_dispatch returns one command id")
check(len(srv.mesh_board()["commands"]) == 1, "mesh_board shows the queued command")

# error surfacing: assigning a non-existent session raises
try:
    srv.task_assign("oauth", "no-such-label")
    check(False, "task_assign should raise on an unknown session")
except ValueError:
    check(True, "task_assign raises ValueError on an unknown session")

srv.task_done("oauth")
check(srv.task_get("oauth")["status"] == "done", "task_done marks the task done")

# task_spawn: neutralize the real launcher (claude -> `true`) so nothing spawns;
# exercises the tool + the real cd/exec shell path, then times out to null.
os.environ["MESH_CLAUDE_BIN"] = "true"
srv.task_add("spawnme", "Spawn me")
sp = srv.task_spawn("spawnme", "/tmp", wait_secs=1)
check(sp["session_id"] is None and sp["status"] == "in_progress",
      "task_spawn returns in_progress + null session when no worker registers")

# Phase 3: board carries the backlog; ask queues a prompt to the worker.
board = srv.mesh_board()
check("tasks" in board and any(t["slug"] == "spawnme" for t in board["tasks"]),
      "mesh_board includes the tasks backlog")
ask = srv.mesh_ask("srv-worker", "what is your status?")
check(len(ask["command_ids"]) == 1, "mesh_ask queues one prompt command")

print(f"PASS={P} FAIL={F}")
sys.exit(1 if F else 0)
