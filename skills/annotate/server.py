"""HTTP server for the petros-skills `annotate` skill.

Stdlib-only HTTP server that:
- Watches a response_dir for response.md + meta.json files written by Claude.
- Renders them as an interactive HTML page on `/`.
- Accepts annotations on `POST /api/submit` and writes annotations.json.
- Provides `/health` for liveness checks.
- Auto-shuts down after 30 minutes of inactivity.

Invocation:
    PYTHONPATH=<plugin-root> python3 -m skills.annotate.server --project-dir <project-root>
"""

import argparse
import html as _html
import http.server
import json
import mimetypes
import os
import re
import secrets
import socket
import socketserver
import subprocess
import sys
import threading
import time
from pathlib import Path

SHUTDOWN_AFTER_SECONDS = int(os.environ.get("ANNOTATE_SHUTDOWN_SECONDS", 24 * 60 * 60))
PORT_RANGE = range(54580, 54601)  # inclusive both ends
STATIC_DIR = Path(__file__).resolve().parent / "static"

# Hostname used in announced URLs. Resolved once at startup so the same value
# appears in server.json and every session URL. Tailscale MagicDNS lets the
# same short hostname (e.g. petross-macbook-pro) resolve from the Mac itself
# AND from any other tailnet device (phone, iPad), so one URL works for both
# local and remote use.
_PUBLIC_HOST = "127.0.0.1"


def _resolve_public_host() -> str:
    if env := os.environ.get("ANNOTATE_PUBLIC_HOST"):
        return env
    # Use the leading label of Self.DNSName (e.g. "my-laptop" from
    # "my-laptop.tail<id>.ts.net."). That's the MagicDNS short name and it
    # resolves identically on the local machine and any other tailnet device.
    # The OS device name (Self.HostName) often has spaces and smart quotes
    # and isn't DNS-safe.
    try:
        out = subprocess.check_output(
            ["tailscale", "status", "--json"], timeout=1, text=True,
            stderr=subprocess.DEVNULL,
        )
        dns_name = json.loads(out).get("Self", {}).get("DNSName") or ""
        short = dns_name.split(".", 1)[0]
        if short:
            return short
    except (subprocess.SubprocessError, FileNotFoundError, json.JSONDecodeError, ValueError):
        pass
    return "127.0.0.1"

_last_activity = time.time()
_last_activity_lock = threading.Lock()

_SID_RE = re.compile(r"^[a-zA-Z0-9_-]+$")
_sessions: dict[str, dict[str, Path]] = {}
_sessions_lock = threading.Lock()


def _sessions_file() -> Path:
    return Path(os.path.expanduser("~/.claude/annotate/sessions.json"))


def _make_sid() -> str:
    # Date-keyed slug: YYMMDD-HHMMSS-<hex16>. Sortable on disk, and 64 bits of
    # randomness makes the URL unguessable when the server is reachable on the
    # tailnet (anyone on the wifi could scan the port, but they still need the
    # SID to do anything with it).
    return f"{time.strftime('%y%m%d-%H%M%S')}-{secrets.token_hex(8)}"


def _persist_sessions() -> None:
    """Write _sessions to disk so a server restart can rehydrate them."""
    path = _sessions_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    with _sessions_lock:
        snapshot = {
            sid: {k: str(v) for k, v in dirs.items()}
            for sid, dirs in _sessions.items()
        }
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(snapshot, indent=2))
    tmp.replace(path)


def _rehydrate_sessions() -> None:
    """Populate _sessions from the on-disk snapshot, dropping any whose dirs are gone."""
    path = _sessions_file()
    if not path.exists():
        return
    try:
        snapshot = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return
    if not isinstance(snapshot, dict):
        return
    restored: dict[str, dict[str, Path]] = {}
    for sid, dirs in snapshot.items():
        if not _SID_RE.match(sid) or not isinstance(dirs, dict):
            continue
        try:
            response_dir = Path(dirs["response_dir"])
            annotations_dir = Path(dirs["annotations_dir"])
            state_dir = Path(dirs["state_dir"])
        except KeyError:
            continue
        if not (response_dir.is_dir() and annotations_dir.is_dir() and state_dir.is_dir()):
            continue
        restored[sid] = {
            "response_dir": response_dir,
            "annotations_dir": annotations_dir,
            "state_dir": state_dir,
        }
    with _sessions_lock:
        _sessions.update(restored)


def _session_is_pending(dirs: dict[str, Path]) -> bool:
    """A session is pending if Claude pushed a response but the user hasn't
    submitted or cancelled yet."""
    meta = dirs["response_dir"] / "meta.json"
    submitted = dirs["annotations_dir"] / "annotations.json"
    cancelled = dirs["state_dir"] / "cancelled"
    return meta.exists() and not submitted.exists() and not cancelled.exists()


def _any_session_pending() -> bool:
    with _sessions_lock:
        for dirs in _sessions.values():
            if _session_is_pending(dirs):
                return True
    return False


def _register_session(cwd: Path) -> dict:
    sid = _make_sid()
    base = cwd / ".claude" / "annotate" / sid
    response_dir = base / "response"
    annotations_dir = base / "annotations"
    state_dir = base / "state"
    for d in (response_dir, annotations_dir, state_dir):
        d.mkdir(parents=True, exist_ok=True)
    dirs = {
        "response_dir": response_dir,
        "annotations_dir": annotations_dir,
        "state_dir": state_dir,
    }
    with _sessions_lock:
        _sessions[sid] = dirs
    _persist_sessions()
    return {"sid": sid, **dirs}


def _touch() -> None:
    global _last_activity
    with _last_activity_lock:
        _last_activity = time.time()


def _seconds_since_activity() -> float:
    with _last_activity_lock:
        return time.time() - _last_activity

def html_escape(s: str) -> str:
    return _html.escape(s, quote=True)


def _read_meta(response_dir: Path) -> dict:
    path = response_dir / "meta.json"
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}


INDEX_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>annotate-server</title>
<link rel="stylesheet" href="/static/style.css">
</head>
<body data-theme="dark">
<header class="page-header">
  <div class="header-title">
    <span class="header-emoji">📝</span>
    <span class="header-text">annotate-server</span>
  </div>
</header>
<main class="prose">
<p>The server is running. Individual response pages live under <code>/s/&lt;session-id&gt;/</code>.</p>
{sessions_block}
</main>
</body>
</html>
"""


WAITING_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Waiting for a response</title>
<link rel="stylesheet" href="/static/style.css">
</head>
<body>
<main class="waiting">
  <p>Waiting for a response. Claude will push one shortly.</p>
</main>
</body>
</html>
"""

RESPONSE_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{title}</title>
<link rel="stylesheet" href="/static/style.css">
<link href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,400;12..96,500;12..96,600;12..96,700&display=swap" rel="stylesheet">
<script>
  (function () {{
    try {{
      var saved = localStorage.getItem("annotate.theme");
      var prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
      var theme = (saved === "light" || saved === "dark") ? saved : (prefersDark ? "dark" : "light");
      document.documentElement.dataset.theme = theme;
    }} catch (e) {{
      document.documentElement.dataset.theme = "dark";
    }}
  }})();
</script>
</head>
<body data-response-id="{response_id}">
<header class="page-header">
  <div class="header-title">
    <span class="header-emoji">📝</span>
    <span class="header-text">{title}</span>
    <span class="header-respid">{response_id}</span>
  </div>
  <div class="header-actions">
    <button id="theme-light" type="button" class="theme-btn" aria-label="Light theme">☀</button>
    <button id="theme-dark" type="button" class="theme-btn" aria-label="Dark theme">🌙</button>
  </div>
</header>
<main class="prose"></main>
<footer class="actions">
  <span id="comment-count" class="comment-count"></span>
  <span id="submit-status"></span>
  <span class="actions-spacer"></span>
  <button id="cancel-btn" type="button" class="cancel-btn">Cancel</button>
  <button id="submit-btn" type="button">Submit annotations</button>
</footer>
<script src="/static/markdown-it.min.js"></script>
<script src="/static/script.js"></script>
</body>
</html>
"""


def _bind_first_available_port() -> tuple[socket.socket, int]:
    """Bind to the first available port in PORT_RANGE. Raise OSError if all are taken."""
    for port in PORT_RANGE:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("0.0.0.0", port))
            return s, port
        except OSError:
            s.close()
            continue
    raise OSError(f"No free port in range {PORT_RANGE.start}-{PORT_RANGE.stop - 1}")


class AnnotateHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format: str, *args) -> None:  # silence default access log
        return

    def _match_session(self, prefix: str) -> tuple[str, str] | None:
        """If self.path starts with /s/<sid>/<rest>, return (sid, '/' + rest). Else None."""
        if not self.path.startswith(prefix):
            return None
        tail = self.path[len(prefix):]
        if "/" not in tail:
            return None
        sid, rest = tail.split("/", 1)
        if not _SID_RE.match(sid):
            return None
        with _sessions_lock:
            if sid not in _sessions:
                return None
        return sid, "/" + rest

    def do_GET(self) -> None:  # noqa: N802
        _touch()
        if self.path == "/health":
            self._send_text(200, "annotate-server v1")
            return
        if self.path == "/":
            self._serve_index()
            return
        if self.path.startswith("/static/"):
            self._serve_static(self.path[len("/static/"):])
            return
        matched = self._match_session("/s/")
        if matched is not None:
            sid, rest = matched
            with _sessions_lock:
                dirs = _sessions[sid]
            if rest == "/":
                self._serve_root(dirs)
                return
            if rest == "/raw":
                self._serve_raw(dirs)
                return
            if rest == "/poll":
                self._serve_poll(dirs)
                return
            self._send_text(404, "not found")
            return
        self._send_text(404, "not found")

    def _serve_index(self) -> None:
        with _sessions_lock:
            items = [(sid, dirs) for sid, dirs in _sessions.items()]
        rows = []
        for sid, dirs in sorted(items, reverse=True):
            meta = _read_meta(dirs["response_dir"])
            title = meta.get("title", "(waiting for response)")
            href = f"/s/{sid}/"
            rows.append(
                f'<li><a href="{href}">{html_escape(title)}</a> '
                f'<span class="header-respid">{html_escape(sid)}</span></li>'
            )
        if rows:
            sessions_block = "<ul>" + "".join(rows) + "</ul>"
        else:
            sessions_block = "<p><em>No active sessions yet.</em></p>"
        self._send_html(200, INDEX_HTML.format(sessions_block=sessions_block))

    def _send_text(self, status: int, body: str) -> None:
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _send_html(self, status: int, body: str) -> None:
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _serve_root(self, dirs: dict) -> None:
        state_dir = dirs["state_dir"]
        # Terminal-state markers (submit / cancel) take precedence over the
        # response file so a refresh after a terminal action shows the waiting
        # screen rather than the now-consumed response.
        if (state_dir / "submitted").exists() or (state_dir / "cancelled").exists():
            self._send_html(200, WAITING_HTML)
            return
        response_dir = dirs["response_dir"]
        response_path = response_dir / "response.md"
        if not response_path.exists():
            self._send_html(200, WAITING_HTML)
            return
        meta = _read_meta(response_dir)
        # The shell ships empty; the client fetches /raw and renders with markdown-it.
        page = RESPONSE_HTML.format(
            title=html_escape(meta.get("title", "Response")),
            response_id=html_escape(meta.get("response_id", "")),
        )
        self._send_html(200, page)

    def _serve_raw(self, dirs: dict) -> None:
        response_path = dirs["response_dir"] / "response.md"
        if not response_path.exists():
            self._send_text(404, "not found")
            return
        data = response_path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "text/markdown; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _serve_static(self, name: str) -> None:
        # Reject path traversal and absolute paths.
        if "\\" in name or name.startswith(".") or not name:
            self._send_text(404, "not found")
            return
        path = STATIC_DIR / name
        # Ensure path stays within STATIC_DIR (prevent ../ traversal)
        try:
            path.relative_to(STATIC_DIR)
        except ValueError:
            self._send_text(404, "not found")
            return
        if not path.is_file():
            self._send_text(404, "not found")
            return
        ctype = mimetypes.guess_type(str(path))[0] or "application/octet-stream"
        data = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _serve_poll(self, dirs: dict) -> None:
        meta = _read_meta(dirs["response_dir"])
        body = json.dumps({"response_id": meta.get("response_id")})
        data = body.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self) -> None:  # noqa: N802
        _touch()
        if self.path == "/api/sessions":
            self._handle_create_session()
            return
        matched = self._match_session("/s/")
        if matched is not None:
            sid, rest = matched
            with _sessions_lock:
                dirs = _sessions[sid]
            if rest == "/api/submit":
                self._handle_submit(dirs)
                return
            if rest == "/api/cancel":
                self._handle_cancel(dirs)
                return
            self._send_text(404, "not found")
            return
        self._send_text(404, "not found")

    def _handle_create_session(self) -> None:
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            self._send_text(400, "invalid json")
            return
        cwd_str = payload.get("cwd")
        if not isinstance(cwd_str, str) or not cwd_str:
            self._send_text(400, "missing cwd")
            return
        cwd = Path(cwd_str)
        if not cwd.is_absolute() or not cwd.is_dir():
            self._send_text(400, "cwd must be an absolute existing directory")
            return
        sess = _register_session(cwd)
        port = self.server.server_address[1]
        body = json.dumps({
            "sid": sess["sid"],
            "url": f"http://{_PUBLIC_HOST}:{port}/s/{sess['sid']}/",
            "response_dir": str(sess["response_dir"]),
            "annotations_dir": str(sess["annotations_dir"]),
            "state_dir": str(sess["state_dir"]),
        })
        data = body.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _handle_submit(self, dirs: dict) -> None:
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            self._send_text(400, "invalid json")
            return
        if not isinstance(payload, dict):
            self._send_text(400, "payload must be an object")
            return
        response_id = payload.get("response_id")
        annotations = payload.get("annotations", [])
        if not isinstance(response_id, str) or not isinstance(annotations, list):
            self._send_text(400, "missing response_id or annotations")
            return

        # response_id must match the currently-served response.
        meta = _read_meta(dirs["response_dir"])
        current_id = meta.get("response_id")
        if current_id and response_id != current_id:
            self._send_text(409, "stale response_id")
            return

        out = {
            "response_id": response_id,
            "submitted_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "annotations": annotations,
        }
        target = dirs["annotations_dir"] / "annotations.json"
        tmp = target.with_suffix(".tmp")
        tmp.write_text(json.dumps(out, indent=2))
        tmp.replace(target)
        # Mark the response as consumed so a page refresh shows the waiting
        # screen instead of re-rendering an already-submitted response.
        (dirs["state_dir"] / "submitted").write_text("")
        self._send_text(200, "ok")

    def _handle_cancel(self, dirs: dict) -> None:
        marker = dirs["state_dir"] / "cancelled"
        marker.write_text(
            json.dumps({"reason": "user-cancelled", "at": int(time.time())})
        )
        self._send_text(200, "ok")


class _ThreadedHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main(argv: list[str] | None = None) -> int:
    # No CLI args. The server is a long-lived singleton spawned by
    # ensure_server.sh; sessions are created via POST /api/sessions.
    argparse.ArgumentParser().parse_args(argv)

    global _PUBLIC_HOST
    _PUBLIC_HOST = _resolve_public_host()

    _rehydrate_sessions()

    sock, port = _bind_first_available_port()
    sock.listen()

    server = _ThreadedHTTPServer(("0.0.0.0", port), AnnotateHandler, bind_and_activate=False)
    server.socket = sock
    server.server_address = ("0.0.0.0", port)

    info = {
        "type": "server-started",
        "port": port,
        "url": f"http://{_PUBLIC_HOST}:{port}",
    }
    home_info_dir = Path(os.path.expanduser("~/.claude/annotate"))
    home_info_dir.mkdir(parents=True, exist_ok=True)
    (home_info_dir / "server.json").write_text(json.dumps(info))
    sys.stdout.write(json.dumps(info) + "\n")
    sys.stdout.flush()

    stop_event = threading.Event()

    def _watch_idle():
        while not stop_event.wait(1.0):
            if _seconds_since_activity() >= SHUTDOWN_AFTER_SECONDS:
                # Keep the server alive while a user is mid-annotation.
                if _any_session_pending():
                    continue
                threading.Thread(target=server.shutdown, daemon=True).start()
                return

    threading.Thread(target=_watch_idle, daemon=True).start()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop_event.set()
        server.shutdown()
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
