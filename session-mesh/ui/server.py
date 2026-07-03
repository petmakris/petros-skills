# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Session Mesh — live web board.

A tiny localhost server that turns the mesh into a dashboard. It holds no
state: every read/write shells into a mesh.sh function (the single source of
truth), exactly like the MCP server does. Serves one page (index.html) that
polls /api/board a couple times a second and renders the fleet, the task
backlog, and the recent-command feed — with buttons that call /api/action.

Bind is 127.0.0.1 only: the board drives autonomous workers, so it must never
be reachable off the machine. Run with:

    uv run --script session-mesh/ui/server.py        # opens the browser
    MESH_UI_PORT=9000 uv run --script .../server.py   # custom port
    uv run --script .../server.py --no-open           # don't open a browser
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
MESH_LIB = str(HERE.parent / "mesh.sh")          # session-mesh/mesh.sh
INDEX = HERE / "index.html"

# source mesh.sh, then run: <fn> <args...>. Args pass via argv (never
# interpolated into the script), so there is no shell-quoting hazard.
_RUNNER = 'source "$1"; shift; "$@"'

# Only these ops are callable from the browser, each mapped to a mesh.sh
# function and a fixed positional-arg order. A hard whitelist keeps the POST
# surface from becoming "run any shell function".
ACTIONS: dict[str, tuple[str, tuple[str, ...]]] = {
    "pause":           ("mesh_pause",           ()),
    "resume":          ("mesh_resume",          ()),
    "task_add":        ("mesh_task_add",        ("slug", "title", "description")),
    "task_done":       ("mesh_task_done",       ("slug",)),
    "task_set_status": ("mesh_task_set_status", ("slug", "status")),
    "task_spawn":      ("mesh_task_spawn",      ("slug", "cwd", "label", "wait_secs")),
    "task_unassign":   ("mesh_task_unassign",   ("slug", "target")),
    "dispatch":        ("mesh_dispatch",        ("target", "kind", "payload", "created_by")),
    "ask":             ("mesh_ask",             ("target", "question")),
}


class MeshError(Exception):
    """Carries the mesh.sh stderr verbatim so the UI can surface it."""


def mesh(fn: str, *args: str) -> str:
    """Call a mesh.sh function, return stdout. Raise MeshError on nonzero exit."""
    proc = subprocess.run(
        ["bash", "-c", _RUNNER, "mesh", MESH_LIB, fn, *[str(a) for a in args]],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise MeshError(proc.stderr.strip() or f"{fn} failed (exit {proc.returncode})")
    return proc.stdout


def board() -> dict:
    """The full board snapshot, plus the fleet-wide paused flag merged in."""
    data = json.loads(mesh("mesh_board_json"))
    data["paused"] = mesh("mesh_is_paused").strip() == "1"
    return data


class Handler(BaseHTTPRequestHandler):
    server_version = "MeshBoard/1.0"

    def _send(self, code: int, body: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, obj) -> None:
        self._send(code, json.dumps(obj).encode(), "application/json")

    def do_GET(self) -> None:
        path = self.path.split("?", 1)[0]
        if path in ("/", "/index.html"):
            try:
                self._send(200, INDEX.read_bytes(), "text/html; charset=utf-8")
            except OSError:
                self._send(500, b"index.html missing", "text/plain")
        elif path == "/api/board":
            try:
                self._json(200, board())
            except MeshError as e:
                self._json(500, {"error": str(e)})
        else:
            self._send(404, b"not found", "text/plain")

    def do_POST(self) -> None:
        if self.path.split("?", 1)[0] != "/api/action":
            self._send(404, b"not found", "text/plain")
            return
        try:
            n = int(self.headers.get("Content-Length", 0))
            payload = json.loads(self.rfile.read(n) or b"{}")
            op = payload.get("op")
            if op not in ACTIONS:
                self._json(400, {"error": f"unknown op: {op!r}"})
                return
            fn, params = ACTIONS[op]
            args = [str(payload.get(p, "")) for p in params]
            out = mesh(fn, *args).strip()
            # Some ops (task_spawn) return JSON; pass it through when it parses.
            try:
                result = json.loads(out)
            except (ValueError, TypeError):
                result = out
            self._json(200, {"ok": True, "op": op, "result": result})
        except MeshError as e:
            self._json(400, {"ok": False, "error": str(e)})
        except (ValueError, TypeError) as e:
            self._json(400, {"ok": False, "error": f"bad request: {e}"})

    def log_message(self, *_):  # keep the terminal quiet
        pass


def main() -> None:
    port = int(os.environ.get("MESH_UI_PORT", "8787"))
    open_browser = "--no-open" not in sys.argv[1:]
    mesh("mesh_init")  # self-init / migrate, like the MCP server
    url = f"http://127.0.0.1:{port}/"
    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"Session Mesh board → {url}  (Ctrl-C to stop)")
    if open_browser:
        try:
            webbrowser.open(url)
        except Exception:
            pass
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped.")
        httpd.server_close()


if __name__ == "__main__":
    main()
