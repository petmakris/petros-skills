"""Shared HTTP server entrypoint.

Each skill calls server.run(skill_name=..., port_range=..., handlers=...,
static_dirs=...). The shared core owns: port binding, threaded HTTP shell,
idle watchdog, server.json write under ~/.claude/<skill>/server.json,
the /, /health, /static/*, /api/sessions, /s/<sid>/api/upload routes, and
session registry.  Everything else dispatches to the skill via
HandlersProtocol.
"""
from __future__ import annotations

import http.server
import json
import os
import socket
import socketserver
import subprocess
import sys
import threading
import time
import traceback
from pathlib import Path

from skills._shared.web_companion.handlers import HandlersProtocol
from skills._shared.web_companion.sessions import Registry, SID_RE
from skills._shared.web_companion import uploads as upload_module
from skills._shared.web_companion import static_serve
from skills._shared.web_companion import cleanup

SHARED_STATIC_DIR = Path(__file__).resolve().parent / "static"


def _is_terminal(dirs: dict) -> bool:
    """Return True if the session has a finished or cancelled marker."""
    state_dir = Path(dirs["state_dir"])
    return (state_dir / "finished").exists() or (state_dir / "cancelled").exists()


REAP_AFTER = 180  # seconds; a watcher silent longer than this is treated as dead


def _watcher_age(dirs: dict) -> int | None:
    """Seconds since the watcher last beat, or None if it never has.

    The heartbeat file holds an integer epoch second (written by watcher.sh
    every ~1s). Missing/empty/unparseable -> None, which callers treat as
    "live": a freshly-armed session has not written its first beat yet, so it
    must not be reaped.
    """
    hb_path = Path(dirs["state_dir"]) / "watcher_heartbeat"
    try:
        hb = int(hb_path.read_text().strip())
    except (FileNotFoundError, ValueError):
        return None
    return int(time.time()) - hb


def _resolve_public_host() -> str:
    if env := os.environ.get("WEBCOMPANION_PUBLIC_HOST"):
        return env
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


def _bind_first_available_port(port_range: range) -> tuple[socket.socket, int]:
    for port in port_range:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("0.0.0.0", port))
            return s, port
        except OSError:
            s.close()
            continue
    raise OSError(f"No free port in range {port_range.start}-{port_range.stop - 1}")


class _ThreadedHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def run(skill_name: str, port_range: range, handlers: HandlersProtocol,
        static_dirs: list[Path], shutdown_after_seconds: int | None = None,
        prune_globs: tuple[str, ...] = ()) -> int:
    """Long-lived HTTP server entrypoint. Returns the process exit code."""

    if shutdown_after_seconds is None:
        shutdown_after_seconds = int(os.environ.get(
            f"{skill_name.upper()}_SHUTDOWN_SECONDS", 24 * 60 * 60))

    public_host = _resolve_public_host()

    state_root = Path(os.path.expanduser(f"~/.claude/{skill_name}"))

    # Garbage-collect state left behind by past sessions before we rehydrate,
    # so dormant rows never get re-registered. Best-effort: a sweep failure
    # must never stop the server from starting.
    try:
        retention_days = int(os.environ.get("WEBCOMPANION_RETENTION_DAYS", "7"))
        gc = cleanup.sweep_state(
            state_root, retention_days * 86400, time.time(), extra_globs=prune_globs)
        if any(gc.values()):
            sys.stdout.write(json.dumps({"type": "cleanup", "skill": skill_name, **gc}) + "\n")
            sys.stdout.flush()
    except Exception:
        pass

    registry = Registry(state_root=state_root)
    registry.rehydrate()
    if hasattr(handlers, "set_registry"):
        handlers.set_registry(registry)

    last_activity = [time.time()]
    last_activity_lock = threading.Lock()

    def touch():
        with last_activity_lock:
            last_activity[0] = time.time()

    def seconds_since_activity():
        with last_activity_lock:
            return time.time() - last_activity[0]

    banner = f"{skill_name}-server v1"
    server_holder = {}

    class _Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, format: str, *args) -> None:
            # Log every HTTP request to the same server.log so we can
            # diagnose why a client's call appeared to go nowhere. Cheap,
            # and the only thing we lose is a tiny bit of log noise.
            try:
                line = f"{self.address_string()} - {format % args}"
                print(json.dumps({"type": "http", "line": line}), flush=True)
            except Exception:
                pass

        def _send_text(self, status: int, body: str):
            data = body.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _send_json(self, status: int, body_obj: dict):
            data = json.dumps(body_obj).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _content_length(self) -> int | None:
            """Parse Content-Length, rejecting missing/negative/non-integer.

            Returns the byte count, or None if the header is malformed. A
            negative length would make rfile.read(length) read to EOF
            (unbounded); a non-integer would raise inside int(). Callers
            treat None as a 400.
            """
            raw = self.headers.get("Content-Length", "0") or "0"
            try:
                n = int(raw)
            except ValueError:
                return None
            return n if n >= 0 else None

        def _read_body_text(self):
            """Read the request body as UTF-8 text.

            Returns (text, None) on success, or (None, error_message) when the
            Content-Length is missing/negative/non-integer or the body is not
            valid UTF-8 — both of which would otherwise raise and kill the
            worker thread with no response.
            """
            length = self._content_length()
            if length is None:
                return None, "invalid content-length"
            if length == 0:
                return "", None
            try:
                return self.rfile.read(length).decode("utf-8"), None
            except UnicodeDecodeError:
                return None, "body must be utf-8"

        def _match_session(self, prefix: str):
            if not self.path.startswith(prefix):
                return None
            tail = self.path[len(prefix):]
            if "/" not in tail:
                return None
            sid, rest = tail.split("/", 1)
            if not SID_RE.match(sid):
                return None
            if registry.lookup(sid) is None:
                return None
            return sid, "/" + rest

        def _safe_500(self):
            """Best-effort 500 when a handler raised. If headers were already
            sent the send_response will itself raise — swallow that so we never
            mask the original error with a secondary one, but always avoid
            leaving the worker thread dead with no response written."""
            try:
                self._send_text(500, "internal server error")
            except Exception:
                pass

        def do_GET(self):
            try:
                self._dispatch_get()
            except Exception:
                self.log_message("unhandled GET error: %s", traceback.format_exc())
                self._safe_500()

        def do_POST(self):
            try:
                self._dispatch_post()
            except Exception:
                self.log_message("unhandled POST error: %s", traceback.format_exc())
                self._safe_500()

        def _dispatch_get(self):
            touch()
            if self.path == "/health":
                self._send_text(200, banner)
                return
            if self.path == "/":
                self._send_text(200, banner + " - see /s/<sid>/")
                return
            if self.path.startswith("/static/"):
                static_serve.serve(self, self.path[len("/static/"):], static_dirs)
                return
            matched = self._match_session("/s/")
            if matched is not None:
                sid, rest = matched
                dirs = registry.lookup(sid)
                if rest == "/":
                    handlers.serve_root(self, dirs)
                    return
                if rest == "/poll":
                    handlers.serve_poll(self, dirs)
                    return
                query = rest.lstrip("/")
                dirs_with_sid = {**dirs, "_sid": sid}
                handlers.serve_data(self, dirs_with_sid, query)
                return
            if self.path.startswith("/api/sessions"):
                from urllib.parse import urlparse, parse_qs
                qs = parse_qs(urlparse(self.path).query)
                cwd = (qs.get("cwd") or [""])[0]
                if not cwd:
                    self._send_text(400, "missing cwd")
                    return
                rows = []
                for sid, dirs in registry.find_by_cwd(cwd):
                    # Skip terminated sessions and watcher-dead "zombies". A
                    # session that is cancelled/finished, OR whose watcher has
                    # been silent past REAP_AFTER, can't answer anything — so
                    # surfacing it makes the IDE attach to a dead review and
                    # accept input nobody reads. (rehydrate() after a server
                    # restart re-registers such sessions from disk; reaping them
                    # here is intentional — nothing re-arms their watcher.)
                    age = _watcher_age(dirs)
                    if _is_terminal(dirs) or (age is not None and age > REAP_AFTER):
                        continue
                    meta_path = Path(dirs["state_dir"]) / "meta.json"
                    meta = {}
                    if meta_path.exists():
                        try:
                            meta = json.loads(meta_path.read_text())
                        except json.JSONDecodeError:
                            meta = {}
                    rows.append({
                        "sid": sid,
                        "pr_ref": meta.get("pr_ref", ""),
                        "title": meta.get("title", ""),
                        "state_dir": str(dirs["state_dir"]),
                    })
                # Newest first. sids start with `YYMMDD-HHMMSS-...`, so a
                # descending lexical sort puts the most recent session at
                # index 0 — which is what clients that pick the first
                # session (e.g. the IDE plugin) want when several live
                # sessions share the same cwd.
                rows.sort(key=lambda r: r["sid"], reverse=True)
                self._send_json(200, rows)
                return
            self._send_text(404, "not found")

        def _dispatch_post(self):
            touch()
            if self.path == "/api/sessions":
                self._handle_create_session()
                return
            matched = self._match_session("/s/")
            if matched is not None:
                sid, rest = matched
                dirs = registry.lookup(sid)
                if rest == "/api/submit":
                    self._handle_submit(sid, dirs)
                    return
                if rest == "/api/finish":
                    (Path(dirs["state_dir"]) / "finished").write_text("")
                    self._send_text(200, "ok")
                    return
                if rest == "/api/cancel":
                    if _is_terminal(dirs):
                        self._send_text(409, "session closed")
                        return
                    (Path(dirs["state_dir"]) / "cancelled").write_text(
                        json.dumps({"reason": "user-cancelled", "at": int(time.time())})
                    )
                    self._send_text(200, "ok")
                    return
                if rest == "/api/upload":
                    if _is_terminal(dirs):
                        self._send_text(409, "session closed")
                        return
                    upload_module.handle(self, dirs)
                    return
                if rest == "/api/threads/delete":
                    if _is_terminal(dirs):
                        self._send_text(409, "session closed")
                        return
                    self._handle_thread_delete(sid, dirs)
                    return
            self._send_text(404, "not found")

        def _handle_create_session(self):
            raw, err = self._read_body_text()
            if err is not None:
                self._send_text(400, err)
                return
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
            sid = registry.make_sid()
            base = cwd / ".claude" / skill_name / sid
            response_dir = base / "response"
            annotations_dir = base / "annotations"
            state_dir = base / "state"
            events_dir = state_dir / "events"
            consumed_dir = state_dir / "consumed"
            for d in (response_dir, annotations_dir, state_dir, events_dir, consumed_dir):
                d.mkdir(parents=True, exist_ok=True)
            dirs = {
                "response_dir": response_dir,
                "annotations_dir": annotations_dir,
                "state_dir": state_dir,
                "events_dir": events_dir,
                "consumed_dir": consumed_dir,
                # metadata: cwd is captured so the IDE plugin can find this session by project root
                "_cwd": str(cwd),
            }
            try:
                extra = handlers.create_session_extra(payload, dirs) or {}
            except Exception as e:
                self._send_text(500, f"session-init failed: {e}")
                return
            registry.register(sid, dirs)
            registry.persist()
            port = server_holder['server'].server_address[1]
            self._send_json(200, {
                "sid": sid,
                "url": f"http://{public_host}:{port}/s/{sid}/",
                # Always-secure-context loopback URL. Browser features that need
                # a secure context (e.g. voice dictation) work here but not over
                # the public_host URL when it's plain-HTTP.
                "localhost_url": f"http://localhost:{port}/s/{sid}/",
                "response_dir": str(response_dir),
                "annotations_dir": str(annotations_dir),
                "state_dir": str(state_dir),
                "events_dir": str(events_dir),
                "consumed_dir": str(consumed_dir),
                **extra,
            })

        def _handle_submit(self, sid, dirs):
            raw, err = self._read_body_text()
            if err is not None:
                self._send_text(400, err)
                return
            try:
                payload = json.loads(raw) if raw else None
            except json.JSONDecodeError:
                self._send_text(400, "invalid json")
                return
            if not isinstance(payload, dict):
                self._send_text(400, "payload must be an object")
                return
            handlers.handle_submit(self, dirs, payload)
            registry.note_change(sid)

        def _handle_thread_delete(self, sid, dirs):
            if not hasattr(handlers, "handle_thread_delete"):
                self._send_text(404, "delete not supported by this skill")
                return
            raw, err = self._read_body_text()
            if err is not None:
                self._send_text(400, err)
                return
            try:
                payload = json.loads(raw) if raw else None
            except json.JSONDecodeError:
                self._send_text(400, "invalid json")
                return
            if not isinstance(payload, dict):
                self._send_text(400, "payload must be an object")
                return
            handlers.handle_thread_delete(self, dirs, payload)
            registry.note_change(sid)

    sock, port = _bind_first_available_port(port_range)
    sock.listen()

    server = _ThreadedHTTPServer(("0.0.0.0", port), _Handler, bind_and_activate=False)
    server.socket = sock
    server.server_address = ("0.0.0.0", port)
    server_holder['server'] = server

    info = {"type": "server-started", "skill": skill_name, "port": port,
            "url": f"http://{public_host}:{port}",
            "plugin_root": os.environ.get("PLUGIN_ROOT", "")}
    home_info_dir = Path(os.path.expanduser(f"~/.claude/{skill_name}"))
    home_info_dir.mkdir(parents=True, exist_ok=True)
    (home_info_dir / "server.json").write_text(json.dumps(info))
    sys.stdout.write(json.dumps(info) + "\n")
    sys.stdout.flush()

    stop_event = threading.Event()

    def _watch_idle():
        while not stop_event.wait(1.0):
            if seconds_since_activity() >= shutdown_after_seconds:
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
