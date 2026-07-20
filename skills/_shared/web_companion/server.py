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


LIVE_WINDOW = 10  # seconds; heartbeat fresher than this => "live"


def _read_hb(state_dir):
    p = Path(state_dir) / "watcher_heartbeat"
    if not p.exists():
        return None
    try:
        return int(p.read_text().strip())
    except (ValueError, OSError):
        return None


def _comment_count(dirs):
    ann = dirs.get("annotations_dir")
    if not ann:
        return 0
    ann = Path(ann)
    return sum(1 for _ in ann.glob("*.json")) if ann.is_dir() else 0


def _session_meta(registry, dirs, sid):
    """Legacy meta source: state_dir/meta.json (NOT registry meta), preserved
    byte-for-byte from the pre-scope=all behavior so interactive_review is
    unaffected."""
    meta = {}
    mp = Path(dirs["state_dir"]) / "meta.json"
    if mp.exists():
        try:
            meta = json.loads(mp.read_text())
        except json.JSONDecodeError:
            meta = {}
    return meta


def session_row(sid, dirs, meta, now, legacy=False):
    if legacy:
        return {"sid": sid, "pr_ref": meta.get("pr_ref", ""),
                "title": meta.get("title", ""), "state_dir": str(dirs["state_dir"])}
    hb = _read_hb(dirs["state_dir"])
    if _is_terminal(dirs):
        status = "done"
    elif hb is not None and (now - hb) < LIVE_WINDOW:
        status = "live"
    else:
        status = "idle"
    return {
        "sid": sid, "slug": meta.get("slug", sid),
        "title": meta.get("title", ""), "project": meta.get("project", ""),
        "pr_ref": meta.get("pr_ref", ""),
        "last_active": hb or meta.get("created_at", 0),
        "comment_count": _comment_count(dirs),
        "status": status, "state_dir": str(dirs["state_dir"]),
    }


def list_rows(registry, cwd, scope, now):
    if cwd:
        pairs = registry.find_by_cwd(cwd)
        out = []
        for sid, dirs in pairs:
            age = _watcher_age(dirs)
            if _is_terminal(dirs) or (age is not None and age > REAP_AFTER):
                continue
            out.append(session_row(sid, dirs, _session_meta(registry, dirs, sid), now, legacy=True))
        out.sort(key=lambda r: r["sid"], reverse=True)
        return out
    if scope != "all":
        return []          # handler renders the legacy 400 for this case
    # scope=all -> every registered session (live, idle, or done) within the
    # retention window. Unlike the legacy ?cwd= branch above, this must NOT
    # reap by watcher age: a watcher stops heartbeating the instant its
    # Claude session ends, leaving a STALE (but present) heartbeat file on
    # disk, so age-based reaping here would hide every idle-but-legitimate
    # workspace 180s after the session that created it exits — exactly the
    # set /annotate resume and the browser need to see. registry.list_all()
    # is already pruned to live on-disk dirs by rehydrate(), so the only
    # remaining gate is retention (same env/default the startup GC uses).
    retention_seconds = int(os.environ.get("WEBCOMPANION_RETENTION_DAYS", "7")) * 86400
    out = []
    for sid, dirs in registry.list_all():
        meta = registry.get_meta(sid)
        hb = _read_hb(dirs["state_dir"])
        last_active = hb or meta.get("created_at", 0)
        if now - last_active > retention_seconds:
            continue
        out.append(session_row(sid, dirs, meta, now))
    out.sort(key=lambda r: r["last_active"], reverse=True)
    return out


def create_or_attach(registry, skill_name, payload, cwd, mkdirs, on_create=None):
    """Return ({sid, slug, dirs, created}, created_bool).

    mkdirs(sid) -> dirs dict (response_dir/annotations_dir/state_dir/events_dir/
    consumed_dir), all created. Pure of HTTP; URL assembly is the caller's job.

    on_create(dirs), if given, runs on the CREATE path only — after dirs are
    made and `_cwd` is set, but BEFORE the session is registered/persisted.
    If it raises, the exception propagates and nothing is registered (no
    zombie session). Never called on the attach path (attach must not re-run
    a skill's per-session init, e.g. re-fetching a PR diff).
    """
    title = (payload.get("title") or "").strip()
    project = (payload.get("project") or Path(cwd).name).strip()
    explicit_slug = (payload.get("slug") or "").strip()
    want_attach = bool(payload.get("attach"))

    if want_attach:
        target_sid = None
        if explicit_slug:
            target_sid = registry.find_by_slug(explicit_slug)
        else:
            live = registry.find_by_cwd(cwd)
            live.sort(key=lambda kv: kv[0], reverse=True)
            target_sid = live[0][0] if live else None
        if target_sid is not None:
            dirs = registry.lookup(target_sid)
            if dirs and Path(dirs["state_dir"]).is_dir():
                meta = registry.get_meta(target_sid)
                return ({"sid": target_sid, "slug": meta.get("slug", target_sid),
                         "dirs": dirs, "created": False}, False)
            # dead sid (state_dir missing): free its slug before falling
            # through to create, so the intended slug is reused (no -2
            # bump) and no ghost registry entry lingers.
            registry.unregister(target_sid)
        # fall through to create (self-heal)

    sid = registry.make_sid()
    # Sanitize explicit slug through slugifier; fall back to make_slug if it becomes empty
    if explicit_slug:
        explicit_slug = registry._slugify(explicit_slug) or ""
    slug = explicit_slug or registry.make_slug(title, cwd)
    # if an explicit slug collides with a live one, dedup it too
    if explicit_slug and registry.find_by_slug(explicit_slug):
        slug = registry.make_slug(explicit_slug, cwd)
    dirs = mkdirs(sid)
    dirs["_cwd"] = str(cwd)
    if on_create is not None:
        on_create(dirs)
    registry.register(sid, dirs)
    registry.register_meta(sid, {
        "slug": slug, "title": title, "project": project,
        "created_at": int(time.time()),
    })
    registry.persist()
    return ({"sid": sid, "slug": slug, "dirs": dirs, "created": True}, True)


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
            key, rest = tail.split("/", 1)
            if not SID_RE.match(key):
                return None
            sid = registry.resolve(key)          # slug OR sid -> canonical sid
            if sid is None:
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
                static_serve.serve(self, "sessions.html", static_dirs)
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
                scope = (qs.get("scope") or [""])[0]
                if not cwd and scope != "all":
                    self._send_text(400, "missing cwd")   # legacy contract intact
                    return
                rows = list_rows(registry, cwd, scope, now=int(time.time()))
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
            def _mkdirs(sid):
                base = cwd / ".claude" / skill_name / sid
                response_dir = base / "response"
                annotations_dir = base / "annotations"
                state_dir = base / "state"
                events_dir = state_dir / "events"
                consumed_dir = state_dir / "consumed"
                for d in (response_dir, annotations_dir, state_dir, events_dir, consumed_dir):
                    d.mkdir(parents=True, exist_ok=True)
                return {
                    "response_dir": response_dir, "annotations_dir": annotations_dir,
                    "state_dir": state_dir, "events_dir": events_dir,
                    "consumed_dir": consumed_dir,
                }

            extra_holder = {}

            def _on_create(dirs):
                extra_holder['extra'] = handlers.create_session_extra(payload, dirs) or {}

            try:
                result, _created = create_or_attach(
                    registry, skill_name, payload, cwd, _mkdirs, on_create=_on_create)
            except Exception as e:
                self._send_text(500, f"session-init failed: {e}")
                return
            sid = result["sid"]; slug = result["slug"]; dirs = result["dirs"]
            extra = extra_holder.get('extra', {})
            port = server_holder['server'].server_address[1]
            self._send_json(200, {
                "sid": sid,
                "slug": slug,
                "created": _created,
                "url": f"http://{public_host}:{port}/s/{slug}/",
                # Always-secure-context loopback URL. Browser features that need
                # a secure context (e.g. voice dictation) work here but not over
                # the public_host URL when it's plain-HTTP.
                "localhost_url": f"http://localhost:{port}/s/{slug}/",
                "response_dir": str(dirs["response_dir"]),
                "annotations_dir": str(dirs["annotations_dir"]),
                "state_dir": str(dirs["state_dir"]),
                "events_dir": str(dirs["events_dir"]),
                "consumed_dir": str(dirs["consumed_dir"]),
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
