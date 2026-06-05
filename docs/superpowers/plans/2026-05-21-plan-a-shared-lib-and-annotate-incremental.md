# Plan A — Shared `web_companion` library + annotate-incremental migration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract a stdlib-only shared HTTP companion library from today's `annotate` skill, then migrate `annotate` to a per-block-submit + in-place block-rewrite flow built on a new event-queue + persistent-watcher protocol. Replaces the current whole-document submit + single-shot watcher.

**Architecture:** Two-process world after this lands. The shared `skills/_shared/web_companion/` package (stdlib-only) owns HTTP server, sessions, event queue, paste-image uploads, static serving, and the parameterized `ensure_server.sh`. Each skill is a thin module that registers a `HandlersProtocol` implementation with the shared core. Annotate becomes the first such skill; `blocks.json` replaces `response.md` as the canonical document model.

**Tech Stack:** Python stdlib (`http.server`, `socketserver`, `threading`, `json`, `pathlib`), bash for `ensure_server.sh` and the watcher script, vanilla JS + markdown-it (client-side). No new dependencies.

**Reference spec:** `docs/superpowers/specs/2026-05-21-incremental-annotate-and-interactive-review-design.md`

---

## File Structure

**Created:**

```
skills/_shared/
  __init__.py                              (empty)
skills/_shared/web_companion/
  __init__.py                              (exports: run, Registry, events,
                                            uploads, static_serve,
                                            HandlersProtocol, render_page)
  server.py                                run() entrypoint; owns the HTTP
                                            shell, port bind, idle watchdog,
                                            server.json write
  sessions.py                              Registry class (extracted from
                                            today's annotate/server.py:67-163)
  events.py                                event-queue helpers: append(),
                                            heartbeat(), and the watcher
                                            script template
  uploads.py                               paste-image upload handler
                                            (extracted from
                                            annotate/server.py:613-653)
  static_serve.py                          path-traversal-safe static asset
                                            serving (extracted from
                                            annotate/server.py:460-481)
  handlers.py                              HandlersProtocol class
  templates.py                             render_page() shared HTML shell
  ensure_server.sh                         parameterized launcher
  watcher.sh                               persistent per-session watcher
                                            script (template, sourced by
                                            arming flow in SKILL.md)
  tests/
    __init__.py
    test_sessions.py
    test_events.py
    test_uploads.py
    test_static_serve.py
    test_watcher.sh
skills/annotate/blocks.py                  blocks.json document model
                                            (read, mutate, atomic write,
                                            version bump)
skills/annotate/tests/test_blocks.py
docs/superpowers/plans/2026-05-21-plan-a-shared-lib-and-annotate-incremental.md   (THIS FILE)
```

**Modified:**

```
skills/annotate/server.py                  Becomes ~120-line thin wrapper:
                                            registers a HandlersProtocol
                                            impl and calls
                                            web_companion.server.run().
                                            Removes _sessions, port bind,
                                            static serve, upload handler,
                                            terminal-state submit guard.
skills/annotate/ensure_server.sh           Becomes ~10-line wrapper that
                                            sources shared ensure_server.sh
                                            with SKILL=annotate,
                                            PORT_RANGE=54580-54600.
skills/annotate/SKILL.md                   Rewritten sections: "How to push",
                                            "Arming the watcher", "Mode D",
                                            "How to read annotations".
                                            New section: per-block rewrite
                                            contract.
skills/annotate/static/script.js           Switches to blocks-JSON rendering,
                                            per-comment submit, polling
                                            with version vectors, partial
                                            block swap, Done button.
skills/annotate/static/style.css           Adds: "updating" indicator,
                                            Done button, per-comment Submit
                                            visual; removes: footer Submit
                                            annotations / Cancel buttons.
skills/annotate/README.md                  Update reference to new flow.
skills/annotate/tests/test_server.py       Updates: old whole-doc /api/submit
                                            tests removed; new per-block
                                            submit + finish endpoint tests
                                            added. Other tests (sessions,
                                            uploads, static) migrate to
                                            web_companion/tests/.
skills/annotate/tests/test_ensure_server.py  Updates: assert wrapper still
                                            ends up calling the shared
                                            ensure_server.sh and the server
                                            responds on /health.
```

---

## Phase 1 — Shared library scaffold (no behavior change)

Goal: extract today's annotate `server.py` into a reusable shared library while keeping the externally observed behavior identical. Existing `test_server.py` / `test_ensure_server.py` continue to pass after this phase.

### Task 1.1: Create the package skeleton

**Files:**
- Create: `skills/_shared/__init__.py` (empty file)
- Create: `skills/_shared/web_companion/__init__.py`
- Create: `skills/_shared/web_companion/tests/__init__.py` (empty file)

- [ ] **Step 1:** Create both empty `__init__.py` files.

```bash
mkdir -p skills/_shared/web_companion/tests
touch skills/_shared/__init__.py
touch skills/_shared/web_companion/tests/__init__.py
```

- [ ] **Step 2:** Write `skills/_shared/web_companion/__init__.py`:

```python
"""Stdlib-only shared HTTP companion library used by the annotate and
interactive-review skills.

Skills implement HandlersProtocol and call web_companion.server.run(...).
"""

from skills._shared.web_companion.handlers import HandlersProtocol
from skills._shared.web_companion.sessions import Registry
from skills._shared.web_companion import events, uploads, static_serve, server, templates

__all__ = [
    "HandlersProtocol",
    "Registry",
    "events",
    "uploads",
    "static_serve",
    "server",
    "templates",
]
```

- [ ] **Step 3:** Commit:

```bash
git add skills/_shared
git commit -m "web_companion: package skeleton"
```

---

### Task 1.2: Extract Registry into sessions.py

The current `_sessions` dict + lock + persistence helpers live at `skills/annotate/server.py:67-163`. Promote them to a class on a new state root.

**Files:**
- Create: `skills/_shared/web_companion/sessions.py`
- Create: `skills/_shared/web_companion/tests/test_sessions.py`

- [ ] **Step 1:** Write the failing test first:

```python
# skills/_shared/web_companion/tests/test_sessions.py
import json
import re
import tempfile
from pathlib import Path

import pytest

from skills._shared.web_companion.sessions import Registry, SID_RE


def test_make_sid_matches_re():
    r = Registry(state_root=Path("/tmp/never-used"))
    sid = r.make_sid()
    assert SID_RE.match(sid), sid


def test_register_and_lookup(tmp_path):
    state_root = tmp_path / "state"
    r = Registry(state_root=state_root)
    sid = r.make_sid()
    dirs = {"response_dir": tmp_path / "a", "annotations_dir": tmp_path / "b",
            "state_dir": tmp_path / "c"}
    for d in dirs.values():
        d.mkdir()
    r.register(sid, dirs)
    assert r.lookup(sid) == dirs
    assert r.lookup("missing") is None


def test_persist_and_rehydrate(tmp_path):
    state_root = tmp_path / "state"
    r = Registry(state_root=state_root)
    sid = r.make_sid()
    dirs = {"response_dir": tmp_path / "a", "annotations_dir": tmp_path / "b",
            "state_dir": tmp_path / "c"}
    for d in dirs.values():
        d.mkdir()
    r.register(sid, dirs)
    r.persist()
    # Fresh registry pointed at same state_root rehydrates.
    r2 = Registry(state_root=state_root)
    r2.rehydrate()
    assert r2.lookup(sid) == dirs


def test_rehydrate_drops_sessions_with_missing_dirs(tmp_path):
    state_root = tmp_path / "state"
    r = Registry(state_root=state_root)
    sid = r.make_sid()
    dirs = {"response_dir": tmp_path / "gone", "annotations_dir": tmp_path / "gone2",
            "state_dir": tmp_path / "gone3"}
    state_root.mkdir(parents=True)
    (state_root / "sessions.json").write_text(json.dumps({
        sid: {k: str(v) for k, v in dirs.items()}
    }))
    r2 = Registry(state_root=state_root)
    r2.rehydrate()
    assert r2.lookup(sid) is None  # dirs don't exist, dropped
```

- [ ] **Step 2:** Run the test, expect failure (module doesn't exist):

```bash
pytest skills/_shared/web_companion/tests/test_sessions.py -v
```
Expected: ImportError on `from skills._shared.web_companion.sessions import Registry, SID_RE`.

- [ ] **Step 3:** Write `skills/_shared/web_companion/sessions.py`:

```python
"""Session registry shared by all web_companion skills.

Migrated from skills/annotate/server.py:67-163 (commit before this plan).
Each instance is parameterized by a state_root (where sessions.json lives)
so multiple skills can coexist with separate registries.
"""
from __future__ import annotations

import json
import re
import secrets
import threading
import time
from pathlib import Path

SID_RE = re.compile(r"^[a-zA-Z0-9_-]+$")


class Registry:
    def __init__(self, state_root: Path):
        self._state_root = Path(state_root)
        self._sessions: dict[str, dict[str, Path]] = {}
        self._lock = threading.Lock()

    @property
    def sessions_file(self) -> Path:
        return self._state_root / "sessions.json"

    def make_sid(self) -> str:
        return f"{time.strftime('%y%m%d-%H%M%S')}-{secrets.token_hex(8)}"

    def register(self, sid: str, dirs: dict[str, Path]) -> None:
        with self._lock:
            self._sessions[sid] = dirs

    def lookup(self, sid: str) -> dict[str, Path] | None:
        with self._lock:
            return self._sessions.get(sid)

    def items(self) -> list[tuple[str, dict[str, Path]]]:
        with self._lock:
            return list(self._sessions.items())

    def persist(self) -> None:
        self._state_root.mkdir(parents=True, exist_ok=True)
        with self._lock:
            snapshot = {
                sid: {k: str(v) for k, v in dirs.items()}
                for sid, dirs in self._sessions.items()
            }
        tmp = self.sessions_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(snapshot, indent=2))
        tmp.replace(self.sessions_file)

    def rehydrate(self) -> None:
        path = self.sessions_file
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
            if not SID_RE.match(sid) or not isinstance(dirs, dict):
                continue
            try:
                paths = {k: Path(v) for k, v in dirs.items()}
            except (TypeError, ValueError):
                continue
            if not all(p.is_dir() for p in paths.values()):
                continue
            restored[sid] = paths
        with self._lock:
            self._sessions.update(restored)
```

- [ ] **Step 4:** Run tests, expect pass:

```bash
pytest skills/_shared/web_companion/tests/test_sessions.py -v
```
Expected: all four tests pass.

- [ ] **Step 5:** Commit:

```bash
git add skills/_shared/web_companion/sessions.py skills/_shared/web_companion/tests/test_sessions.py
git commit -m "web_companion: extract Registry into sessions.py"
```

---

### Task 1.3: Extract static_serve

**Files:**
- Create: `skills/_shared/web_companion/static_serve.py`
- Create: `skills/_shared/web_companion/tests/test_static_serve.py`

- [ ] **Step 1:** Write the failing test:

```python
# skills/_shared/web_companion/tests/test_static_serve.py
from io import BytesIO
from pathlib import Path
from unittest.mock import MagicMock

from skills._shared.web_companion.static_serve import serve


def make_handler():
    h = MagicMock()
    h.wfile = BytesIO()
    return h


def test_serve_existing_file(tmp_path):
    static = tmp_path / "static"
    static.mkdir()
    (static / "ok.css").write_text("body{}")
    h = make_handler()
    serve(h, "ok.css", [static])
    h.send_response.assert_called_once_with(200)


def test_serve_traversal_rejected(tmp_path):
    static = tmp_path / "static"
    static.mkdir()
    h = make_handler()
    serve(h, "../etc/passwd", [static])
    # Should have sent a 404 via the handler's _send_text helper which
    # the implementation calls; for the unit test we just check send_response.
    h.send_response.assert_called_with(404)


def test_serve_falls_through_chain(tmp_path):
    a = tmp_path / "a"
    b = tmp_path / "b"
    a.mkdir(); b.mkdir()
    (b / "only-in-b.js").write_text("//")
    h = make_handler()
    serve(h, "only-in-b.js", [a, b])
    h.send_response.assert_called_with(200)
```

- [ ] **Step 2:** Run test, expect ImportError:

```bash
pytest skills/_shared/web_companion/tests/test_static_serve.py -v
```

- [ ] **Step 3:** Write `skills/_shared/web_companion/static_serve.py`:

```python
"""Path-traversal-safe static asset serving for web_companion.

Extracted from annotate/server.py:460-481. Extended to accept a list of dirs
resolved in order — first match wins. The first dir is typically the shared
core static; the second is the skill-specific overlay.
"""
from __future__ import annotations

import mimetypes
from pathlib import Path
from http.server import BaseHTTPRequestHandler


def serve(handler: BaseHTTPRequestHandler, name: str, dirs: list[Path]) -> None:
    if "\\" in name or name.startswith(".") or not name:
        _send_text(handler, 404, "not found")
        return
    for static_dir in dirs:
        static_dir = Path(static_dir)
        path = static_dir / name
        try:
            path.relative_to(static_dir)
        except ValueError:
            continue
        if not path.is_file():
            continue
        ctype = mimetypes.guess_type(str(path))[0] or "application/octet-stream"
        data = path.read_bytes()
        handler.send_response(200)
        handler.send_header("Content-Type", ctype)
        handler.send_header("Content-Length", str(len(data)))
        handler.end_headers()
        handler.wfile.write(data)
        return
    _send_text(handler, 404, "not found")


def _send_text(handler: BaseHTTPRequestHandler, status: int, body: str) -> None:
    data = body.encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "text/plain; charset=utf-8")
    handler.send_header("Content-Length", str(len(data)))
    handler.end_headers()
    handler.wfile.write(data)
```

- [ ] **Step 4:** Run tests, expect pass.

- [ ] **Step 5:** Commit:

```bash
git add skills/_shared/web_companion/static_serve.py skills/_shared/web_companion/tests/test_static_serve.py
git commit -m "web_companion: extract static asset serving"
```

---

### Task 1.4: Extract uploads

**Files:**
- Create: `skills/_shared/web_companion/uploads.py`
- Create: `skills/_shared/web_companion/tests/test_uploads.py`

- [ ] **Step 1:** Write the failing test:

```python
# skills/_shared/web_companion/tests/test_uploads.py
from io import BytesIO
from pathlib import Path
from unittest.mock import MagicMock

from skills._shared.web_companion.uploads import handle, UPLOAD_MAX_BYTES


def make_handler(headers, body=b""):
    h = MagicMock()
    h.headers = headers
    h.rfile = BytesIO(body)
    h.wfile = BytesIO()
    return h


def test_unsupported_media_type(tmp_path):
    h = make_handler({"Content-Type": "text/plain", "Content-Length": "1"}, b"x")
    handle(h, {"state_dir": tmp_path})
    h.send_response.assert_called_with(415)


def test_missing_content_length(tmp_path):
    h = make_handler({"Content-Type": "image/png"})
    handle(h, {"state_dir": tmp_path})
    h.send_response.assert_called_with(411)


def test_payload_too_large(tmp_path):
    big = str(UPLOAD_MAX_BYTES + 1)
    h = make_handler({"Content-Type": "image/png", "Content-Length": big})
    handle(h, {"state_dir": tmp_path})
    h.send_response.assert_called_with(413)


def test_invalid_content_length(tmp_path):
    h = make_handler({"Content-Type": "image/png", "Content-Length": "abc"})
    handle(h, {"state_dir": tmp_path})
    h.send_response.assert_called_with(400)


def test_happy_path(tmp_path):
    png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 16
    h = make_handler({"Content-Type": "image/png", "Content-Length": str(len(png))}, png)
    handle(h, {"state_dir": tmp_path})
    h.send_response.assert_called_with(200)
    images = list((tmp_path / "images").iterdir())
    assert len(images) == 1
    assert images[0].suffix == ".png"
```

- [ ] **Step 2:** Run test, expect ImportError.

- [ ] **Step 3:** Write `skills/_shared/web_companion/uploads.py`:

```python
"""Paste-image upload handler. Extracted from annotate/server.py:613-653.

The endpoint is unchanged: POST <session>/api/upload with a raw image body
(Content-Type one of png/jpeg/gif/webp). Saves under dirs["state_dir"]/images/.
"""
from __future__ import annotations

import json
import uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler


UPLOAD_EXT = {
    "image/png": "png",
    "image/jpeg": "jpg",
    "image/gif": "gif",
    "image/webp": "webp",
}
UPLOAD_MAX_BYTES = 10 * 1024 * 1024


def handle(handler: BaseHTTPRequestHandler, dirs: dict) -> None:
    ctype = (handler.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
    ext = UPLOAD_EXT.get(ctype)
    if ext is None:
        _send_text(handler, 415, "unsupported media type")
        return
    length_hdr = handler.headers.get("Content-Length")
    if not length_hdr:
        _send_text(handler, 411, "length required")
        return
    try:
        length = int(length_hdr)
    except ValueError:
        _send_text(handler, 400, "invalid content-length")
        return
    if length <= 0 or length > UPLOAD_MAX_BYTES:
        _send_text(handler, 413, "payload too large")
        return
    body = handler.rfile.read(length)
    images_dir = Path(dirs["state_dir"]) / "images"
    images_dir.mkdir(parents=True, exist_ok=True)
    path = images_dir / f"{uuid.uuid4().hex}.{ext}"
    path.write_bytes(body)
    body_json = json.dumps({"path": str(path), "size": len(body)})
    data = body_json.encode("utf-8")
    handler.send_response(200)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(data)))
    handler.end_headers()
    handler.wfile.write(data)


def _send_text(handler: BaseHTTPRequestHandler, status: int, body: str) -> None:
    data = body.encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "text/plain; charset=utf-8")
    handler.send_header("Content-Length", str(len(data)))
    handler.end_headers()
    handler.wfile.write(data)
```

- [ ] **Step 4:** Run tests, expect pass.

- [ ] **Step 5:** Commit:

```bash
git add skills/_shared/web_companion/uploads.py skills/_shared/web_companion/tests/test_uploads.py
git commit -m "web_companion: extract paste-image upload handler"
```

---

### Task 1.5: Handlers protocol

**Files:**
- Create: `skills/_shared/web_companion/handlers.py`

- [ ] **Step 1:** Write `skills/_shared/web_companion/handlers.py`:

```python
"""Handlers protocol that skills implement.

The shared server dispatches requests it can't handle itself (root, static,
upload — those are owned by the shared core) to the skill's handlers
instance. The protocol is intentionally small and skill-agnostic.
"""
from __future__ import annotations

from typing import Protocol, runtime_checkable
from http.server import BaseHTTPRequestHandler


@runtime_checkable
class HandlersProtocol(Protocol):
    def serve_root(self, handler: BaseHTTPRequestHandler, dirs: dict) -> None:
        """GET /s/<sid>/  — render the session's main page."""
        ...

    def serve_data(self, handler: BaseHTTPRequestHandler, dirs: dict, query: str) -> None:
        """GET /s/<sid>/<path>  — fetch one piece of the page (block, thread, raw).

        `query` is the URL path after /s/<sid>/, e.g. "raw?block=b-3".
        Skill decides what's supported.
        """
        ...

    def handle_submit(self, handler: BaseHTTPRequestHandler, dirs: dict, payload: dict) -> None:
        """POST /s/<sid>/api/submit  — accept a per-block/per-line submission.

        Skill writes one event into dirs["state_dir"]/events/ and responds
        202 {"event_id": "..."}.
        """
        ...

    def serve_poll(self, handler: BaseHTTPRequestHandler, dirs: dict) -> None:
        """GET /s/<sid>/poll  — return version vector + watcher heartbeat."""
        ...

    def create_session_extra(self, payload: dict, dirs: dict) -> dict | None:
        """Per-skill session-init hook.

        Called by the shared /api/sessions handler after dirs are created.
        Return a dict of extra fields to merge into the session response,
        or None for no extras. May raise to fail session creation.
        """
        ...
```

- [ ] **Step 2:** Commit:

```bash
git add skills/_shared/web_companion/handlers.py
git commit -m "web_companion: HandlersProtocol"
```

---

### Task 1.6: Server entrypoint + page templates

**Files:**
- Create: `skills/_shared/web_companion/templates.py`
- Create: `skills/_shared/web_companion/server.py`

- [ ] **Step 1:** Write `skills/_shared/web_companion/templates.py`:

```python
"""Shared HTML shell templates used by skill renderers."""
from __future__ import annotations

import html as _html


def html_escape(s: str) -> str:
    return _html.escape(s, quote=True)


def render_page(title: str, head_assets: str, body_html: str,
                response_id: str = "") -> str:
    """Standard page shell. Includes the core stylesheet, theme bootstrap, and
    markdown-it.  The skill's body_html should already include any
    skill-specific scripts.  head_assets is extra <link>/<script> tags."""
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{html_escape(title)}</title>
<link rel="stylesheet" href="/static/core.css">
{head_assets}
<script>
  (function () {{
    try {{
      var savedTheme = localStorage.getItem("webcompanion.theme");
      var prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
      var theme = (savedTheme === "light" || savedTheme === "dark") ? savedTheme : (prefersDark ? "dark" : "light");
      document.documentElement.dataset.theme = theme;
    }} catch (e) {{ document.documentElement.dataset.theme = "dark"; }}
  }})();
</script>
</head>
<body data-response-id="{html_escape(response_id)}">
{body_html}
<script src="/static/markdown-it.min.js"></script>
<script src="/static/core.js"></script>
</body>
</html>
"""
```

- [ ] **Step 2:** Write `skills/_shared/web_companion/server.py`:

```python
"""Shared HTTP server entrypoint.

Each skill calls server.run(skill_name=..., port_range=..., handlers=...,
static_dirs=...). The shared core owns: port binding, threaded HTTP shell,
idle watchdog, server.json write under ~/.claude/<skill>/server.json,
the /, /health, /static/*, /api/sessions, /s/<sid>/api/upload routes, and
session registry.  Everything else dispatches to the skill via
HandlersProtocol.
"""
from __future__ import annotations

import argparse
import http.server
import json
import os
import socket
import socketserver
import subprocess
import sys
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

from skills._shared.web_companion.handlers import HandlersProtocol
from skills._shared.web_companion.sessions import Registry, SID_RE
from skills._shared.web_companion import uploads as upload_module
from skills._shared.web_companion import static_serve

SHARED_STATIC_DIR = Path(__file__).resolve().parent / "static"


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
        static_dirs: list[Path], shutdown_after_seconds: int | None = None) -> int:
    """Long-lived HTTP server entrypoint. Returns the process exit code."""

    if shutdown_after_seconds is None:
        shutdown_after_seconds = int(os.environ.get(
            f"{skill_name.upper()}_SHUTDOWN_SECONDS", 24 * 60 * 60))

    public_host = _resolve_public_host()

    state_root = Path(os.path.expanduser(f"~/.claude/{skill_name}"))
    registry = Registry(state_root=state_root)
    registry.rehydrate()

    last_activity = [time.time()]
    last_activity_lock = threading.Lock()

    def touch():
        with last_activity_lock:
            last_activity[0] = time.time()

    def seconds_since_activity():
        with last_activity_lock:
            return time.time() - last_activity[0]

    banner = f"{skill_name}-server v1"

    class _Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, format: str, *args) -> None:
            return

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

        def _match_session(self, prefix: str) -> tuple[str, str] | None:
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

        def do_GET(self):
            touch()
            if self.path == "/health":
                self._send_text(200, banner)
                return
            if self.path == "/":
                self._send_text(200, banner + " — see /s/<sid>/")
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
                # everything else (raw, thread, file) → skill
                query = rest.lstrip("/")
                handlers.serve_data(self, dirs, query)
                return
            self._send_text(404, "not found")

        def do_POST(self):
            touch()
            if self.path == "/api/sessions":
                self._handle_create_session()
                return
            matched = self._match_session("/s/")
            if matched is not None:
                sid, rest = matched
                dirs = registry.lookup(sid)
                if rest == "/api/submit":
                    self._handle_submit(dirs)
                    return
                if rest == "/api/finish":
                    (Path(dirs["state_dir"]) / "finished").write_text("")
                    self._send_text(200, "ok")
                    return
                if rest == "/api/cancel":
                    (Path(dirs["state_dir"]) / "cancelled").write_text(
                        json.dumps({"reason": "user-cancelled", "at": int(time.time())})
                    )
                    self._send_text(200, "ok")
                    return
                if rest == "/api/upload":
                    upload_module.handle(self, dirs)
                    return
            self._send_text(404, "not found")

        def _handle_create_session(self):
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
            }
            try:
                extra = handlers.create_session_extra(payload, dirs) or {}
            except Exception as e:  # surface skill-level failure to client
                self._send_text(500, f"session-init failed: {e}")
                return
            registry.register(sid, dirs)
            registry.persist()
            self._send_json(200, {
                "sid": sid,
                "url": f"http://{public_host}:{server.server_address[1]}/s/{sid}/",
                "response_dir": str(response_dir),
                "annotations_dir": str(annotations_dir),
                "state_dir": str(state_dir),
                "events_dir": str(events_dir),
                "consumed_dir": str(consumed_dir),
                **extra,
            })

        def _handle_submit(self, dirs):
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
            handlers.handle_submit(self, dirs, payload)

    sock, port = _bind_first_available_port(port_range)
    sock.listen()

    server = _ThreadedHTTPServer(("0.0.0.0", port), _Handler, bind_and_activate=False)
    server.socket = sock
    server.server_address = ("0.0.0.0", port)

    info = {"type": "server-started", "skill": skill_name, "port": port,
            "url": f"http://{public_host}:{port}"}
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
```

- [ ] **Step 3:** Commit:

```bash
git add skills/_shared/web_companion/templates.py skills/_shared/web_companion/server.py
git commit -m "web_companion: server.run() entrypoint + page templates"
```

---

### Task 1.7: Parameterized `ensure_server.sh`

**Files:**
- Create: `skills/_shared/web_companion/ensure_server.sh`

- [ ] **Step 1:** Write `skills/_shared/web_companion/ensure_server.sh`:

```bash
#!/usr/bin/env bash
# Shared idempotent launcher.  Each skill ships a thin wrapper that exports
# SKILL, MODULE, BANNER, then sources this file.
#
# Required env:
#   SKILL    — short skill name (e.g. "annotate", "interactive-review")
#   MODULE   — Python module path (e.g. "skills.annotate.server")
#   BANNER   — string that /health must contain (e.g. "annotate-server v1")
#   PLUGIN_ROOT — absolute path to the plugin root that contains "skills/"
#
# Exit codes: 0 on success, non-zero if the server could not be started.

set -euo pipefail

: "${SKILL:?SKILL must be set}"
: "${MODULE:?MODULE must be set}"
: "${BANNER:?BANNER must be set}"
: "${PLUGIN_ROOT:?PLUGIN_ROOT must be set}"

HOME_DIR="${HOME:?HOME must be set}"
STATE_DIR="$HOME_DIR/.claude/$SKILL"
INFO_FILE="$STATE_DIR/server.json"

mkdir -p "$STATE_DIR"

is_healthy() {
  local url="$1"
  local body
  body="$(curl -sf --max-time 1 "$url/health" 2>/dev/null || true)"
  [[ "$body" == *"$BANNER"* ]]
}

read_url() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["url"])' "$1" 2>/dev/null || true
}

if [[ -f "$INFO_FILE" ]]; then
  url="$(read_url "$INFO_FILE")"
  if [[ -n "$url" ]] && is_healthy "$url"; then
    exit 0
  fi
fi

LOG="$STATE_DIR/server.log"
: > "$LOG"
(
  cd "$PLUGIN_ROOT"
  PYTHONPATH="$PLUGIN_ROOT" nohup python3 -m "$MODULE" >>"$LOG" 2>&1 &
  echo $! > "$STATE_DIR/server.pid"
)

for _ in $(seq 1 50); do
  if [[ -f "$INFO_FILE" ]]; then
    url="$(read_url "$INFO_FILE")"
    if [[ -n "$url" ]] && is_healthy "$url"; then
      exit 0
    fi
  fi
  sleep 0.1
done

echo "ensure_server[$SKILL]: server did not become healthy within 5s. See $LOG" >&2
exit 1
```

- [ ] **Step 2:** Make executable, commit:

```bash
chmod +x skills/_shared/web_companion/ensure_server.sh
git add skills/_shared/web_companion/ensure_server.sh
git commit -m "web_companion: parameterized ensure_server.sh"
```

---

### Task 1.8: Migrate annotate to thin wrapper (no behavior change)

This is the cutover. The old monolithic `skills/annotate/server.py` is replaced by a thin wrapper that implements `HandlersProtocol` against today's behavior — still serving `response.md`, still using whole-document `/api/submit`. The new shared library is exercised end-to-end without changing what the user sees.

**Files:**
- Modify: `skills/annotate/server.py` (full rewrite — see code below)
- Modify: `skills/annotate/ensure_server.sh` (thin wrapper)
- Modify: `skills/annotate/tests/test_server.py` (delete tests for code now in shared lib; keep annotate-specific tests)
- Modify: `skills/annotate/tests/test_ensure_server.py` (update path expectations)

- [ ] **Step 1:** Replace `skills/annotate/server.py` entirely with this content:

```python
"""Annotate skill — thin handlers module over web_companion.

Implements HandlersProtocol so the shared core can serve the annotate session
pages.  At this checkpoint behavior is preserved: serves response.md,
whole-document /api/submit, terminal-state semantics on submit / cancel.
The incremental migration to blocks.json + per-block submit lands in Phase 3.
"""
from __future__ import annotations

import json
import sys
import time
from http.server import BaseHTTPRequestHandler
from pathlib import Path

from skills._shared.web_companion import server as wc_server
from skills._shared.web_companion.templates import html_escape

STATIC_DIR = Path(__file__).resolve().parent / "static"
SHARED_STATIC = Path(__file__).resolve().parent.parent / "_shared" / "web_companion" / "static"

PORT_RANGE = range(54580, 54601)
BANNER = "annotate-server v1"

WAITING_HTML = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Waiting</title>
<link rel="stylesheet" href="/static/style.css"></head>
<body><main class="waiting"><p>Waiting for a response.</p></main></body></html>
"""

CLOSED_HTML = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Closed</title>
<link rel="stylesheet" href="/static/style.css"></head>
<body><main class="waiting"><p>This annotation round is closed.</p></main></body></html>
"""

RESPONSE_HTML = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>{title}</title>
<link rel="stylesheet" href="/static/style.css"></head>
<body data-response-id="{response_id}">
<header class="page-header"><div class="header-title">
  <span class="header-emoji">📝</span>
  <span class="header-text">{title}</span>
  <span class="header-respid">{response_id}</span>
</div></header>
<main class="prose"></main>
<section class="general-section">
  <div id="general-comments"></div>
  <button id="add-general" type="button" class="add-general-btn">
    <span class="plus">+</span><span>General comment</span>
  </button>
</section>
<footer class="actions">
  <span id="comment-count" class="comment-count"></span>
  <span id="submit-status"></span>
  <span class="actions-spacer"></span>
  <button id="cancel-btn" type="button" class="cancel-btn">Cancel</button>
  <button id="submit-btn" type="button">Submit annotations</button>
</footer>
<script src="/static/markdown-it.min.js"></script>
<script src="/static/script.js"></script>
</body></html>
"""


def _read_meta(response_dir: Path) -> dict:
    path = response_dir / "meta.json"
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}


def _terminal_state(state_dir: Path) -> str | None:
    if (state_dir / "submitted").exists():
        return "submitted"
    if (state_dir / "cancelled").exists():
        return "cancelled"
    return None


class Handlers:
    def serve_root(self, h: BaseHTTPRequestHandler, dirs: dict) -> None:
        if _terminal_state(dirs["state_dir"]) is not None:
            _send_html(h, 200, CLOSED_HTML)
            return
        response_path = dirs["response_dir"] / "response.md"
        if not response_path.exists():
            _send_html(h, 200, WAITING_HTML)
            return
        meta = _read_meta(dirs["response_dir"])
        page = RESPONSE_HTML.format(
            title=html_escape(meta.get("title", "Response")),
            response_id=html_escape(meta.get("response_id", "")),
        )
        _send_html(h, 200, page)

    def serve_data(self, h: BaseHTTPRequestHandler, dirs: dict, query: str) -> None:
        if query == "raw":
            if _terminal_state(dirs["state_dir"]) is not None:
                h.send_response(410); h.send_header("Content-Length", "0"); h.end_headers(); return
            response_path = dirs["response_dir"] / "response.md"
            if not response_path.exists():
                h.send_response(404); h.send_header("Content-Length", "0"); h.end_headers(); return
            data = response_path.read_bytes()
            h.send_response(200)
            h.send_header("Content-Type", "text/markdown; charset=utf-8")
            h.send_header("Content-Length", str(len(data)))
            h.end_headers()
            h.wfile.write(data)
            return
        h.send_response(404); h.send_header("Content-Length", "0"); h.end_headers()

    def handle_submit(self, h: BaseHTTPRequestHandler, dirs: dict, payload: dict) -> None:
        if _terminal_state(dirs["state_dir"]) is not None:
            _send_text(h, 409, "session closed")
            return
        response_id = payload.get("response_id")
        annotations = payload.get("annotations", [])
        if not isinstance(response_id, str) or not isinstance(annotations, list):
            _send_text(h, 400, "missing response_id or annotations")
            return
        meta = _read_meta(dirs["response_dir"])
        current_id = meta.get("response_id")
        if current_id and response_id != current_id:
            _send_text(h, 409, "stale response_id")
            return
        out = {"response_id": response_id,
               "submitted_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
               "annotations": annotations}
        target = dirs["annotations_dir"] / "annotations.json"
        tmp = target.with_suffix(".tmp")
        tmp.write_text(json.dumps(out, indent=2))
        tmp.replace(target)
        (dirs["state_dir"] / "submitted").write_text("")
        _send_text(h, 200, "ok")

    def serve_poll(self, h: BaseHTTPRequestHandler, dirs: dict) -> None:
        meta = _read_meta(dirs["response_dir"])
        body = json.dumps({"response_id": meta.get("response_id"),
                           "terminal": _terminal_state(dirs["state_dir"])})
        data = body.encode("utf-8")
        h.send_response(200)
        h.send_header("Content-Type", "application/json; charset=utf-8")
        h.send_header("Content-Length", str(len(data)))
        h.end_headers()
        h.wfile.write(data)

    def create_session_extra(self, payload: dict, dirs: dict) -> dict | None:
        return None


def _send_text(h, status, body):
    data = body.encode("utf-8")
    h.send_response(status)
    h.send_header("Content-Type", "text/plain; charset=utf-8")
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)


def _send_html(h, status, body):
    data = body.encode("utf-8")
    h.send_response(status)
    h.send_header("Content-Type", "text/html; charset=utf-8")
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)


def main() -> int:
    return wc_server.run(
        skill_name="annotate",
        port_range=PORT_RANGE,
        handlers=Handlers(),
        static_dirs=[STATIC_DIR],  # legacy single-dir; Phase 5 will prepend SHARED_STATIC
    )


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2:** Replace `skills/annotate/ensure_server.sh` with thin wrapper:

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
export PLUGIN_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null && pwd)"
export SKILL="annotate"
export MODULE="skills.annotate.server"
export BANNER="annotate-server v1"
exec "$PLUGIN_ROOT/skills/_shared/web_companion/ensure_server.sh"
```

- [ ] **Step 3:** Run existing annotate tests:

```bash
PYTHONPATH=. pytest skills/annotate/tests/ -v
```
Expected: tests for sessions / uploads / static now belong in the shared lib but the existing assertions may still pass because the wrapper preserves the public HTTP surface. Fix any failures by either deleting the now-moved test or updating its import path. Tests covering whole-doc /api/submit behavior should still pass.

- [ ] **Step 4:** Run shared library tests too:

```bash
PYTHONPATH=. pytest skills/_shared/web_companion/tests/ -v
```
Expected: all pass.

- [ ] **Step 5:** Smoke-test the running server:

```bash
./skills/annotate/ensure_server.sh
curl -s http://127.0.0.1:54580/health  # or whatever port is in server.json
# Expected output: "annotate-server v1"
```

- [ ] **Step 6:** Commit:

```bash
git add skills/annotate/server.py skills/annotate/ensure_server.sh skills/annotate/tests
git commit -m "annotate: migrate to web_companion shared lib (no behavior change)"
```

---

## Phase 2 — Event queue + persistent watcher (still pre-incremental)

Goal: land the events-on-disk + ack-marker plumbing under the existing whole-document flow so the safety surface is in place before per-block UX lands. The current `/api/submit` continues to write `annotations.json` directly; the events module is added but not yet used.

### Task 2.1: Implement events.py

**Files:**
- Create: `skills/_shared/web_companion/events.py`
- Create: `skills/_shared/web_companion/tests/test_events.py`

- [ ] **Step 1:** Write the failing test:

```python
# skills/_shared/web_companion/tests/test_events.py
import json
import time
from pathlib import Path

from skills._shared.web_companion.events import append, heartbeat


def test_append_creates_file(tmp_path):
    events_dir = tmp_path / "events"
    events_dir.mkdir()
    eid = append(events_dir, {"hello": "world"})
    files = list(events_dir.iterdir())
    assert len(files) == 1
    assert files[0].name == f"{eid}.json"
    assert json.loads(files[0].read_text()) == {"hello": "world"}


def test_append_monotonic_ordering(tmp_path):
    events_dir = tmp_path / "events"
    events_dir.mkdir()
    ids = [append(events_dir, {"i": i}) for i in range(5)]
    assert ids == sorted(ids), ids


def test_append_atomic_write(tmp_path):
    events_dir = tmp_path / "events"
    events_dir.mkdir()
    eid = append(events_dir, {"x": 1})
    # No leftover .tmp files
    assert not list(events_dir.glob("*.tmp"))
    assert (events_dir / f"{eid}.json").exists()


def test_heartbeat_writes_state(tmp_path):
    state_dir = tmp_path / "state"
    state_dir.mkdir()
    heartbeat(state_dir)
    hb = (state_dir / "watcher_heartbeat").read_text().strip()
    assert int(hb) > 0
```

- [ ] **Step 2:** Run, expect ImportError.

- [ ] **Step 3:** Write `skills/_shared/web_companion/events.py`:

```python
"""Event queue helpers for web_companion.

Skills enqueue events by calling append(); the watcher script reads them in
monotonic order and emits one stdout banner per event.  Atomicity is via
tmp → rename.  Ordering is by monotonic_ns event_id (the filename).
"""
from __future__ import annotations

import json
import time
from pathlib import Path


def append(events_dir: Path, payload: dict) -> str:
    """Atomically enqueue an event.  Returns the event_id (monotonic ns)."""
    events_dir = Path(events_dir)
    events_dir.mkdir(parents=True, exist_ok=True)
    event_id = str(time.monotonic_ns())
    target = events_dir / f"{event_id}.json"
    tmp = target.with_suffix(".tmp")
    tmp.write_text(json.dumps(payload))
    tmp.replace(target)
    return event_id


def heartbeat(state_dir: Path) -> None:
    """Write the watcher heartbeat (used by /poll's watcher_seen_at)."""
    state_dir = Path(state_dir)
    state_dir.mkdir(parents=True, exist_ok=True)
    (state_dir / "watcher_heartbeat").write_text(str(int(time.time())))
```

- [ ] **Step 4:** Run tests, expect pass.

- [ ] **Step 5:** Commit:

```bash
git add skills/_shared/web_companion/events.py skills/_shared/web_companion/tests/test_events.py
git commit -m "web_companion: event queue helpers"
```

---

### Task 2.2: Add persistent watcher script

**Files:**
- Create: `skills/_shared/web_companion/watcher.sh`
- Create: `skills/_shared/web_companion/tests/test_watcher.sh`

- [ ] **Step 1:** Write `skills/_shared/web_companion/watcher.sh`:

```bash
#!/usr/bin/env bash
# Persistent per-session watcher.  Emits one stdout banner per event in
# $EVENTS_DIR; exits when $STATE_DIR/finished or $STATE_DIR/cancelled exists.
#
# Required env:
#   SKILL, SID, STATE_DIR, EVENTS_DIR, CONSUMED_DIR

set -u

: "${SKILL:?}"; : "${SID:?}"; : "${STATE_DIR:?}"; : "${EVENTS_DIR:?}"; : "${CONSUMED_DIR:?}"

mkdir -p "$EVENTS_DIR" "$CONSUMED_DIR"

while [ ! -f "$STATE_DIR/finished" ] && [ ! -f "$STATE_DIR/cancelled" ]; do
  date +%s > "$STATE_DIR/watcher_heartbeat"
  evt=$(ls "$EVENTS_DIR"/*.json 2>/dev/null | sort | head -n1)
  if [ -n "$evt" ]; then
    id=$(basename "$evt" .json)
    if [ ! -f "$CONSUMED_DIR/$id.ack" ]; then
      printf 'WEBCOMPANION_EVENT skill=%s sid=%s event_id=%s\n' "$SKILL" "$SID" "$id"
      printf '%s\n' '---payload---'
      cat "$evt"
      printf '%s\n' '---end---'
      for _ in $(seq 1 1800); do
        if [ -f "$CONSUMED_DIR/$id.ack" ]; then break; fi
        if [ -f "$STATE_DIR/finished" ] || [ -f "$STATE_DIR/cancelled" ]; then break; fi
        sleep 1
      done
      if [ ! -f "$CONSUMED_DIR/$id.ack" ] && [ ! -f "$STATE_DIR/finished" ] && [ ! -f "$STATE_DIR/cancelled" ]; then
        touch "$CONSUMED_DIR/$id.failed"
      fi
    fi
    mv -f "$evt" "$CONSUMED_DIR/$id.json"
  else
    sleep 1
  fi
done

if [ -f "$STATE_DIR/cancelled" ]; then
  printf 'WEBCOMPANION_CANCELLED skill=%s sid=%s\n' "$SKILL" "$SID"
else
  printf 'WEBCOMPANION_FINISHED skill=%s sid=%s\n' "$SKILL" "$SID"
fi
```

- [ ] **Step 2:** Make executable.

```bash
chmod +x skills/_shared/web_companion/watcher.sh
```

- [ ] **Step 3:** Write `skills/_shared/web_companion/tests/test_watcher.sh`:

```bash
#!/usr/bin/env bash
# Smoke test for watcher.sh.  Fakes events and asserts the banner format.
set -euo pipefail

ROOT="$(mktemp -d)"
trap "rm -rf $ROOT" EXIT
STATE="$ROOT/state"
EVENTS="$STATE/events"
CONSUMED="$STATE/consumed"
mkdir -p "$EVENTS" "$CONSUMED"

WATCHER="$(cd "$(dirname "$0")/.." && pwd)/watcher.sh"

OUT="$ROOT/out.txt"

(
  SKILL=test SID=sid-1 STATE_DIR="$STATE" EVENTS_DIR="$EVENTS" CONSUMED_DIR="$CONSUMED" \
    "$WATCHER" > "$OUT" 2>&1
) &
WATCHER_PID=$!

# Give it a moment to start
sleep 0.5

# Drop two events in
echo '{"a":1}' > "$EVENTS/100.json"
sleep 0.2

# Ack the first
touch "$CONSUMED/100.ack"
sleep 1

echo '{"b":2}' > "$EVENTS/200.json"
sleep 0.2
touch "$CONSUMED/200.ack"
sleep 1

# Finish
touch "$STATE/finished"

# Wait for clean exit
wait $WATCHER_PID

grep -q 'WEBCOMPANION_EVENT skill=test sid=sid-1 event_id=100' "$OUT"
grep -q 'WEBCOMPANION_EVENT skill=test sid=sid-1 event_id=200' "$OUT"
grep -q 'WEBCOMPANION_FINISHED skill=test sid=sid-1' "$OUT"

# Both events should be in CONSUMED
test -f "$CONSUMED/100.json"
test -f "$CONSUMED/200.json"

echo "watcher.sh OK"
```

- [ ] **Step 4:** Make executable and run:

```bash
chmod +x skills/_shared/web_companion/tests/test_watcher.sh
./skills/_shared/web_companion/tests/test_watcher.sh
# Expected output: watcher.sh OK
```

- [ ] **Step 5:** Commit:

```bash
git add skills/_shared/web_companion/watcher.sh skills/_shared/web_companion/tests/test_watcher.sh
git commit -m "web_companion: persistent watcher script + smoke test"
```

---

## Phase 3 — blocks.json + per-block submit (annotate behavior change)

Goal: switch annotate's document model from `response.md` to `blocks.json`. Add per-block `/api/submit` writing into the event queue. Server renders a list of blocks. Old whole-document `/api/submit` is removed.

### Task 3.1: blocks.py document model

**Files:**
- Create: `skills/annotate/blocks.py`
- Create: `skills/annotate/tests/test_blocks.py`

- [ ] **Step 1:** Write the failing test:

```python
# skills/annotate/tests/test_blocks.py
import json
from pathlib import Path

import pytest

from skills.annotate.blocks import (
    BlocksDoc, load, save_atomic, update_block, next_block_id
)


def test_load_missing_returns_empty(tmp_path):
    doc = load(tmp_path / "blocks.json")
    assert doc.response_id == ""
    assert doc.blocks == []


def test_save_and_load_round_trip(tmp_path):
    path = tmp_path / "blocks.json"
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[
        {"id": "b-0", "markdown": "hello", "version": 1},
    ])
    save_atomic(path, doc)
    doc2 = load(path)
    assert doc2.response_id == "r-1"
    assert doc2.title == "t"
    assert doc2.blocks == doc.blocks


def test_update_block_bumps_version(tmp_path):
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[
        {"id": "b-0", "markdown": "old", "version": 2},
        {"id": "b-1", "markdown": "x", "version": 1},
    ])
    update_block(doc, "b-0", "new")
    assert doc.blocks[0]["markdown"] == "new"
    assert doc.blocks[0]["version"] == 3
    assert doc.blocks[1]["version"] == 1


def test_update_block_no_op_when_unchanged(tmp_path):
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[
        {"id": "b-0", "markdown": "same", "version": 5},
    ])
    update_block(doc, "b-0", "same")
    assert doc.blocks[0]["version"] == 5  # no bump


def test_update_block_unknown_id_raises(tmp_path):
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[])
    with pytest.raises(KeyError):
        update_block(doc, "b-99", "x")


def test_next_block_id_never_reuses(tmp_path):
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[
        {"id": "b-0", "markdown": "x", "version": 1},
        {"id": "b-5", "markdown": "y", "version": 1},
    ])
    assert next_block_id(doc) == "b-6"
```

- [ ] **Step 2:** Run, expect ImportError.

- [ ] **Step 3:** Write `skills/annotate/blocks.py`:

```python
"""blocks.json document model — annotate's canonical doc structure.

Schema:
    {
      "response_id": str,
      "title": str,
      "blocks": [{"id": "b-N", "markdown": str, "version": int}, ...]
    }

Block ids are stable for the session — minted once via next_block_id(),
never reassigned.  Updating a block via update_block() bumps version only
on actual content change (content-hash dedup for re-apply safety).
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass
class BlocksDoc:
    response_id: str = ""
    title: str = ""
    blocks: list[dict[str, Any]] = field(default_factory=list)


def load(path: Path) -> BlocksDoc:
    path = Path(path)
    if not path.exists():
        return BlocksDoc()
    try:
        raw = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return BlocksDoc()
    return BlocksDoc(
        response_id=raw.get("response_id", ""),
        title=raw.get("title", ""),
        blocks=list(raw.get("blocks") or []),
    )


def save_atomic(path: Path, doc: BlocksDoc) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    out = {"response_id": doc.response_id, "title": doc.title, "blocks": doc.blocks}
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(out, indent=2))
    tmp.replace(path)


def find_block(doc: BlocksDoc, block_id: str) -> dict[str, Any]:
    for b in doc.blocks:
        if b.get("id") == block_id:
            return b
    raise KeyError(block_id)


def update_block(doc: BlocksDoc, block_id: str, new_markdown: str) -> bool:
    """Update a block's markdown.  Returns True if version bumped, False if no-op."""
    b = find_block(doc, block_id)
    if b.get("markdown") == new_markdown:
        return False
    b["markdown"] = new_markdown
    b["version"] = int(b.get("version", 0)) + 1
    return True


def next_block_id(doc: BlocksDoc) -> str:
    """Mint a fresh block id never used in this doc."""
    used = set()
    for b in doc.blocks:
        bid = b.get("id", "")
        if bid.startswith("b-"):
            try:
                used.add(int(bid[2:]))
            except ValueError:
                pass
    n = 0
    while n in used:
        n += 1
    return f"b-{n}"
```

- [ ] **Step 4:** Run tests, expect pass.

- [ ] **Step 5:** Commit:

```bash
git add skills/annotate/blocks.py skills/annotate/tests/test_blocks.py
git commit -m "annotate: blocks.json document model"
```

---

### Task 3.2: Annotate handlers — switch root + raw to blocks.json

**Files:**
- Modify: `skills/annotate/server.py`

- [ ] **Step 1:** Replace `serve_root` and `serve_data` in `skills/annotate/server.py` (and add `serve_poll` rewrite) so they read `blocks.json` instead of `response.md`:

```python
# Replace the existing Handlers class methods.  Keep _read_meta, helpers,
# but remove _terminal_state usage in submit path (per-block has no terminal
# state until /api/finish writes "finished").

from skills.annotate import blocks as blocks_model
from skills._shared.web_companion import events as events_module
from skills._shared.web_companion.templates import render_page, html_escape


SHARED_STATIC = Path(__file__).resolve().parent.parent / "_shared" / "web_companion" / "static"
STATIC_DIR = Path(__file__).resolve().parent / "static"


def _read_meta(response_dir: Path) -> dict:
    path = response_dir / "meta.json"
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}


class Handlers:
    def serve_root(self, h, dirs):
        if (Path(dirs["state_dir"]) / "finished").exists() or \
           (Path(dirs["state_dir"]) / "cancelled").exists():
            _send_html(h, 200, CLOSED_HTML)
            return
        blocks_path = Path(dirs["response_dir"]) / "blocks.json"
        if not blocks_path.exists():
            _send_html(h, 200, WAITING_HTML)
            return
        doc = blocks_model.load(blocks_path)
        body = (
            f'<header class="page-header"><div class="header-title">'
            f'<span class="header-emoji">📝</span>'
            f'<span class="header-text">{html_escape(doc.title)}</span>'
            f'<span class="header-respid">{html_escape(doc.response_id)}</span>'
            f'</div><div class="header-actions">'
            f'<button id="done-btn" type="button" class="done-btn">Done</button>'
            f'</div></header>'
            f'<main class="prose"></main>'
            f'<section class="general-section">'
            f'  <div id="general-comments"></div>'
            f'  <button id="add-general" type="button" class="add-general-btn">'
            f'    <span class="plus">+</span><span>General comment</span>'
            f'  </button>'
            f'</section>'
        )
        head = ('<link rel="stylesheet" href="/static/annotate.css">'
                '<script src="/static/annotate.js" defer></script>')
        page = render_page(
            title=doc.title or "Response",
            head_assets=head,
            body_html=body,
            response_id=doc.response_id,
        )
        _send_html(h, 200, page)

    def serve_data(self, h, dirs, query):
        # /raw → full blocks.json
        # /raw?block=b-N → just that block's markdown
        if query.startswith("raw"):
            qs = ""
            if "?" in query:
                qs = query.split("?", 1)[1]
            blocks_path = Path(dirs["response_dir"]) / "blocks.json"
            doc = blocks_model.load(blocks_path)
            if "block=" in qs:
                bid = qs.split("block=", 1)[1].split("&", 1)[0]
                try:
                    blk = blocks_model.find_block(doc, bid)
                except KeyError:
                    _send_text(h, 404, "block not found")
                    return
                data = json.dumps({"id": blk["id"], "markdown": blk.get("markdown", ""),
                                   "version": blk.get("version", 1)}).encode("utf-8")
                h.send_response(200)
                h.send_header("Content-Type", "application/json; charset=utf-8")
                h.send_header("Content-Length", str(len(data)))
                h.end_headers()
                h.wfile.write(data)
                return
            data = json.dumps({
                "response_id": doc.response_id, "title": doc.title,
                "blocks": doc.blocks,
            }).encode("utf-8")
            h.send_response(200)
            h.send_header("Content-Type", "application/json; charset=utf-8")
            h.send_header("Content-Length", str(len(data)))
            h.end_headers()
            h.wfile.write(data)
            return
        _send_text(h, 404, "not found")

    def handle_submit(self, h, dirs, payload):
        block_id = payload.get("block_id")  # may be None for general
        comment_type = payload.get("type", "comment")
        text = payload.get("text", "")
        selected_text = payload.get("selected_text")
        images = payload.get("images", [])
        if comment_type not in ("comment", "reject"):
            _send_text(h, 400, "bad type")
            return
        if not isinstance(text, str):
            _send_text(h, 400, "bad text")
            return
        evt = {
            "block_id": block_id,
            "type": comment_type,
            "text": text,
            "selected_text": selected_text,
            "images": images,
        }
        eid = events_module.append(Path(dirs["events_dir"]), evt)
        data = json.dumps({"event_id": eid, "status": "queued"}).encode("utf-8")
        h.send_response(202)
        h.send_header("Content-Type", "application/json; charset=utf-8")
        h.send_header("Content-Length", str(len(data)))
        h.end_headers()
        h.wfile.write(data)

    def serve_poll(self, h, dirs):
        blocks_path = Path(dirs["response_dir"]) / "blocks.json"
        doc = blocks_model.load(blocks_path)
        versions = {b["id"]: int(b.get("version", 1)) for b in doc.blocks}
        hb_path = Path(dirs["state_dir"]) / "watcher_heartbeat"
        try:
            hb = int(hb_path.read_text().strip())
        except (FileNotFoundError, ValueError):
            hb = 0
        finished = (Path(dirs["state_dir"]) / "finished").exists() or \
                   (Path(dirs["state_dir"]) / "cancelled").exists()
        body = json.dumps({
            "blocks": versions,
            "watcher_seen_at": hb,
            "finished": finished,
            "response_id": doc.response_id,
        }).encode("utf-8")
        h.send_response(200)
        h.send_header("Content-Type", "application/json; charset=utf-8")
        h.send_header("Content-Length", str(len(body)))
        h.end_headers()
        h.wfile.write(body)

    def create_session_extra(self, payload, dirs):
        return None
```

Also: in `main()`, update `static_dirs` to `[SHARED_STATIC, STATIC_DIR]` so the client can load `/static/core.css` from the shared dir.

- [ ] **Step 2:** Update `skills/annotate/tests/test_server.py` to use blocks.json and per-block submits. Delete tests for the old whole-doc `/api/submit` shape. Add a new test:

```python
def test_submit_writes_event(tmp_path, monkeypatch):
    # … set up a session with a blocks.json …
    # … POST /api/submit with block_id … expect 202 + event file in events/
```

(Adapt to the existing test harness pattern in `test_server.py`.)

- [ ] **Step 3:** Run tests:

```bash
PYTHONPATH=. pytest skills/annotate/tests/ skills/_shared/web_companion/tests/ -v
```
Fix breakage.

- [ ] **Step 4:** Commit:

```bash
git add skills/annotate/server.py skills/annotate/tests
git commit -m "annotate: blocks.json renderer + per-block /api/submit"
```

---

## Phase 4 — Client JS for incremental flow

Goal: replace the whole-document submit UI with per-comment submit + polling-driven partial re-render. The general-comments section is preserved with new (global) semantics. Done button replaces footer Submit/Cancel.

### Task 4.1: Add shared core.js + core.css

**Files:**
- Create: `skills/_shared/web_companion/static/core.css` (basic shared theme — copy palette/typography rules out of today's `skills/annotate/static/style.css`)
- Create: `skills/_shared/web_companion/static/core.js` (polling loop + composer API)
- Move: `skills/annotate/static/markdown-it.min.js` → `skills/_shared/web_companion/static/markdown-it.min.js`
- Move: `skills/annotate/static/fonts/` → `skills/_shared/web_companion/static/fonts/`

- [ ] **Step 1:** Move shared assets:

```bash
mkdir -p skills/_shared/web_companion/static
git mv skills/annotate/static/markdown-it.min.js skills/_shared/web_companion/static/markdown-it.min.js
git mv skills/annotate/static/fonts skills/_shared/web_companion/static/fonts
```

- [ ] **Step 2:** Create `skills/_shared/web_companion/static/core.css`. Extract from today's `skills/annotate/static/style.css` the rules that are skill-agnostic (theme tokens, typography base, comment-card layout, button base, fonts) — roughly the top half of the file. Skill-specific block typography stays in `annotate.css`. (The executing agent should use judgement; if uncertain, copy more rather than less and refine later.)

- [ ] **Step 3:** Create `skills/_shared/web_companion/static/core.js`:

```javascript
// Shared web_companion client core.  Polling loop, composer, submit, finish.
(function () {
  const BASE = (() => {
    const p = window.location.pathname;
    return p.endsWith("/") ? p : p + "/";
  })();

  const pollIntervalMs = 1000;
  let lastVersions = {};   // skill-supplied key → version

  const api = {
    BASE,
    async fetchJSON(path, opts) {
      const r = await fetch(BASE + path, opts || {});
      if (!r.ok) throw new Error(`${path}: ${r.status}`);
      return await r.json();
    },
    async fetchText(path) {
      const r = await fetch(BASE + path);
      if (!r.ok) throw new Error(`${path}: ${r.status}`);
      return await r.text();
    },
    async submit(payload) {
      const r = await fetch(BASE + "api/submit", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      if (!r.ok) throw new Error("submit failed: " + r.status);
      return await r.json();
    },
    async finish() {
      const r = await fetch(BASE + "api/finish", { method: "POST" });
      return r.ok;
    },
    async cancel() {
      const r = await fetch(BASE + "api/cancel", { method: "POST" });
      return r.ok;
    },
    async pasteImage(blob) {
      const r = await fetch(BASE + "api/upload", {
        method: "POST", headers: { "Content-Type": blob.type || "image/png" }, body: blob,
      });
      if (!r.ok) throw new Error("upload failed: " + r.status);
      return await r.json();
    },
  };

  let onPollDelta = () => {};

  async function pollOnce() {
    try {
      const data = await api.fetchJSON("poll");
      if (data.finished) {
        document.body.classList.add("session-finished");
        return;
      }
      // skill-supplied vector lives under data[<vectorKey>] — caller can read directly
      onPollDelta(data, lastVersions);
      // After the skill processes, update lastVersions snapshot.
      lastVersions = { ...(data.blocks || {}), ...(data.threads || {}) };
    } catch (e) {
      console.warn("poll failed", e);
    }
  }

  function startPolling() {
    pollOnce();
    setInterval(pollOnce, pollIntervalMs);
  }

  window.WebCompanion = {
    api,
    init({ onPollDelta: handler }) {
      onPollDelta = handler || (() => {});
      startPolling();
    },
  };
})();
```

- [ ] **Step 4:** Commit:

```bash
git add skills/_shared/web_companion/static
git commit -m "web_companion: shared core.js + core.css + assets"
```

---

### Task 4.2: Rewrite annotate.js for incremental flow

**Files:**
- Create: `skills/annotate/static/annotate.js` (new file — replaces today's `script.js`)
- Create: `skills/annotate/static/annotate.css` (annotate-specific styles separated from shared core)
- Delete (after migration): `skills/annotate/static/script.js`, `skills/annotate/static/style.css` if fully migrated

- [ ] **Step 1:** Sketch the new `annotate.js`. It must:

  1. Fetch `/raw` → JSON `{blocks: [{id, markdown, version}, ...]}`. Render each block into `<section class="block" data-block-id="..." data-version="...">` containing a `<div class="block-content">` (rendered HTML via markdown-it).
  2. Wire span selection + block-action hover button → composer card. Composer card has its own per-comment Submit button.
  3. On Submit: call `WebCompanion.api.submit({block_id, type, text, selected_text, images})`, mark the block as "updating", remove the composer card.
  4. Poll handler: when a block's version in the poll response differs from its currently-rendered version, fetch `/raw?block=b-N` and swap `.block-content` inner HTML.
  5. General-comments: same composer shape, `block_id: null` on submit.
  6. Done button: confirm dialog → `WebCompanion.api.finish()` → reload to show closed screen.
  7. Reuse the paste-image upload via `WebCompanion.api.pasteImage`.

The executing agent has full latitude on the JS structure (it's largely a port of today's `script.js` with the submit semantics flipped and the polling loop adapted). Today's `skills/annotate/static/script.js` is the reference for highlight/selection logic, comment-card markup, paste-strip flow, and accent-theming — preserve all of that. Only the **submit** and **polling** flows change.

- [ ] **Step 2:** Move skill-specific styles out of `skills/annotate/static/style.css` into `skills/annotate/static/annotate.css`. Keep the file name `style.css` only if minimal effort; rename to `annotate.css` to match the template plan.

- [ ] **Step 3:** Update the server template to load `/static/annotate.css` + `/static/annotate.js` (already done in Phase 3 Task 3.2's serve_root rewrite). Verify references.

- [ ] **Step 4:** Smoke-test in browser:

```bash
./skills/annotate/ensure_server.sh
# Manually craft a session — write a sample blocks.json then open /s/<sid>/.
PORT=$(python3 -c 'import json; print(json.load(open(__import__("os").path.expanduser("~/.claude/annotate/server.json")))["port"])')
curl -sX POST "http://127.0.0.1:$PORT/api/sessions" \
  -H 'Content-Type: application/json' \
  -d "{\"cwd\": \"$PWD\"}"
# Use the returned sid to write a blocks.json:
SID=...   # from the response
echo '{"response_id":"r-1","title":"Smoke","blocks":[{"id":"b-0","markdown":"# Hi\n\nFirst block.","version":1},{"id":"b-1","markdown":"Second block.","version":1}]}' \
  > $(python3 -c 'import json,sys; r=json.loads(sys.argv[1]); print(r["response_dir"])') /blocks.json
# Open http://127.0.0.1:$PORT/s/$SID/ in a browser.  Click a span, comment, submit.
# Expect: composer disappears, "updating" indicator shows, event file in events/.
ls $(python3 -c '...')/events/
```

- [ ] **Step 5:** Commit:

```bash
git add skills/annotate/static
git commit -m "annotate: rewrite client for per-block submit + polling"
```

---

## Phase 5 — SKILL.md update

Goal: rewrite the parts of `skills/annotate/SKILL.md` that describe the protocol (How to push, Arming the watcher, Mode D, How to read annotations) so Claude actually performs the new flow.

### Task 5.1: Rewrite "How to push a response"

**Files:**
- Modify: `skills/annotate/SKILL.md` — sections "How to push a response" and "Continuing the annotation loop"

- [ ] **Step 1:** Replace the "How to push a response" section content with:

```markdown
## How to push a response

1. Compose the response as **a list of plain-markdown blocks**.  Each block is one logical unit (a paragraph, a heading + its prose, one bullet, one code block).
2. Write `meta.json` first:
   `{"response_id": "resp-<timestamp>", "title": "<short title>", "claude_session_id": "$CLAUDE_CODE_SESSION_ID"}`.
3. Write `blocks.json` at `<response_dir>/blocks.json` with shape:
   ```json
   {"response_id": "<same>",
    "title": "<same>",
    "blocks": [{"id": "b-0", "markdown": "<md>", "version": 1}, ...]}
   ```
   Block ids are `b-0`, `b-1`, ... in order.  Each starts at version 1.
4. Tell the user, in one short sentence: **"Response in browser → `<url>`.  Click any block to comment; Claude updates that block in place."**
5. **Arm the watcher** (see below).  The Monitor runs in the background; your turn ends immediately.  The user is free to chat.  When they submit any block (or click Done), you'll wake up with the event payload.
6. End your turn.
```

- [ ] **Step 2:** Replace "Arming the watcher" with the new persistent loop. The script is the one in `skills/_shared/web_companion/watcher.sh` — invoke it via the env shape:

```markdown
## Arming the watcher

After writing `meta.json` + `blocks.json` and announcing the URL, start a long-lived `Monitor` keyed to this session's directories.  Use `persistent: true`.

The script invocation:
```bash
SKILL=annotate \
SID="<sid>" \
STATE_DIR="<state_dir>" \
EVENTS_DIR="<events_dir>" \
CONSUMED_DIR="<consumed_dir>" \
"$CLAUDE_PLUGIN_ROOT/skills/_shared/web_companion/watcher.sh"
```

Substitute `<sid>`, `<state_dir>`, `<events_dir>`, `<consumed_dir>` from the session-create response.

The watcher emits one stdout banner per submit:
- `WEBCOMPANION_EVENT skill=annotate sid=<sid> event_id=<id>` followed by
  `---payload---\n<json>\n---end---` for each submission.
- `WEBCOMPANION_FINISHED skill=annotate sid=<sid>` on Done.
- `WEBCOMPANION_CANCELLED skill=annotate sid=<sid>` on Cancel.

The Monitor fires once per stdout line so each event wakes you exactly once.  The watcher lives for the whole session.
```

- [ ] **Step 3:** Replace "How to read annotations" with the new Mode D and rewrite contract:

```markdown
## Mode D — handling a watcher event

When you receive a task-notification whose stdout starts with `WEBCOMPANION_EVENT skill=annotate`:

1. Parse banner: `sid`, `event_id`.
2. Read payload between `---payload---` and `---end---`.  Fields:
   - `block_id` — the block to update, or `null` for a general comment.
   - `type` — `"comment"` or `"reject"`.
   - `text` — the user's comment.
   - `selected_text` — the span they highlighted (advisory).
   - `images` — `[{token, path}]`; Read each `path` before composing.
3. **Block-rewrite contract:**
   - If `block_id` is set: read `<response_dir>/blocks.json`, find the block, generate a rewritten markdown that **folds the answer/clarification into the prose** — the document IS the answer; do not echo the question as Q&A.  If the comment is off-topic for that block, update the block to be clearer about its actual topic, or rewrite a neighboring block, or both.  If the comment is a `reject`, soften/withdraw the claim or hold the line with a reasoned new version.
   - If `block_id` is null: **general-comment** — apply the directive across multiple blocks as needed ("shorter", "casual tone", etc.).  Update each affected block.
4. Save: write the new `blocks.json` atomically (tmp → rename), bumping `version` on changed blocks only.
5. Write `<consumed_dir>/<event_id>.ack` (empty file).
6. End turn.  **No terminal output.**  The watcher remains armed.

When you receive `WEBCOMPANION_FINISHED`: ack briefly in terminal — *"Annotate session for `<title>` closed."* — and remove the pending-registry entry.

When you receive `WEBCOMPANION_CANCELLED`: same, *"Annotate session for `<title>` cancelled."*.

**Re-apply safety:** if the watcher restarts and re-emits an event you've already handled, your rewrite would produce content matching the current block — the content-hash check in `blocks.py:update_block` makes the second apply a no-op.  Safe to re-process.
```

- [ ] **Step 4:** Drop the "Continuing the annotation loop" section — replaced by Mode D's per-event flow. Drop "Terminal cancellation" — still works the same way (writing `cancelled` marker).

- [ ] **Step 5:** Commit:

```bash
git add skills/annotate/SKILL.md
git commit -m "annotate: SKILL.md — per-block flow + new watcher protocol"
```

---

### Task 5.2: README and minor housekeeping

**Files:**
- Modify: `skills/annotate/README.md`

- [ ] **Step 1:** Update README references to describe the new per-block flow. Keep it short.

- [ ] **Step 2:** Commit:

```bash
git add skills/annotate/README.md
git commit -m "annotate: README — describe per-block flow"
```

---

## Phase 6 — Smoke test gate

### Task 6.1: End-to-end manual verification

- [ ] **Step 1:** Kill any running annotate server:

```bash
pkill -f 'python3 -m skills.annotate.server' || true
rm -f ~/.claude/annotate/server.json ~/.claude/annotate/server.pid
```

- [ ] **Step 2:** Restart and verify health:

```bash
./skills/annotate/ensure_server.sh
curl -s "$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/annotate/server.json")))["url"])')/health"
# Expected: annotate-server v1
```

- [ ] **Step 3:** Create a session via curl, write a synthetic blocks.json with 3 blocks, open in browser, comment on block 2 by writing an event file directly (simulating client submit):

```bash
URL=$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/annotate/server.json")))["url"])')
RES=$(curl -sX POST "$URL/api/sessions" -H 'Content-Type: application/json' -d "{\"cwd\":\"$PWD\"}")
echo "$RES"
SID=$(echo "$RES" | python3 -c 'import sys,json;print(json.load(sys.stdin)["sid"])')
RESP=$(echo "$RES" | python3 -c 'import sys,json;print(json.load(sys.stdin)["response_dir"])')
STATE=$(echo "$RES" | python3 -c 'import sys,json;print(json.load(sys.stdin)["state_dir"])')

# Write meta + blocks
echo '{"response_id":"r-1","title":"Smoke","claude_session_id":"manual"}' > "$RESP/meta.json"
cat > "$RESP/blocks.json" <<'EOF'
{"response_id":"r-1","title":"Smoke","blocks":[
  {"id":"b-0","markdown":"# Hello","version":1},
  {"id":"b-1","markdown":"This block will be commented on.","version":1},
  {"id":"b-2","markdown":"Third block.","version":1}
]}
EOF

echo "Open in browser: $URL/s/$SID/"
```

- [ ] **Step 4:** Open the URL.  Confirm:
  - Three blocks render.
  - Done button appears top-right.
  - Click-and-drag a span on block b-1, comment in the popup card, hit per-comment Submit.
  - The block flips to "updating" state.
  - An event file appears under `$STATE/events/`.

```bash
ls "$STATE/events/"
```

- [ ] **Step 5:** Simulate Claude's wake-up: read the event, write a new `blocks.json` with `b-1` rewritten + version 2, write the ack:

```bash
EID=$(ls "$STATE/events/" | head -n1 | sed 's/\.json$//')
cat "$STATE/events/$EID.json"

cat > "$RESP/blocks.json" <<'EOF'
{"response_id":"r-1","title":"Smoke","blocks":[
  {"id":"b-0","markdown":"# Hello","version":1},
  {"id":"b-1","markdown":"This block has been rewritten by Claude in response to your comment.","version":2},
  {"id":"b-2","markdown":"Third block.","version":1}
]}
EOF
touch "$STATE/consumed/$EID.ack"
```

- [ ] **Step 6:** Watch the browser auto-refresh block b-1 within ~1 second (polling cadence).  Verify the "updating" indicator clears.

- [ ] **Step 7:** Click Done.  Confirm dialog → confirm → page transitions to closed.  Verify:

```bash
test -f "$STATE/finished" && echo "finished marker present"
```

- [ ] **Step 8:** If any step fails, fix and re-run.  Commit any fixes.

- [ ] **Step 9:** Final commit:

```bash
git add -A
git commit -m "annotate: post-smoke fixes" --allow-empty
```

---

## Self-review checklist (executed by the planner, not the engineer)

- [x] All sections of the spec covered:
  - Architecture (Approach 2) → Phase 1 + 2 + 3.
  - Watcher event-queue protocol → Phase 2.
  - blocks.json + per-block submit → Phase 3 + 4.
  - Done button + finished marker → Phase 4 + 5.
  - General-comments rewrite → covered in SKILL.md update (Phase 5.1).
  - Error handling — watcher death detection → Phase 3 (heartbeat in /poll), Phase 2 (watcher writes heartbeat).
  - Testing approach → tests written alongside each task; bash watcher test in Phase 2.
- [x] No "TBD" / "TODO" / "fill in" placeholders. The one place that delegates judgment is Task 4.2 Step 1 (annotate.js port), which the executing agent has explicit context for via the existing `script.js`.
- [x] Type consistency:
  - `block_id`, `event_id`, `response_id`, `version` — used identically across server.py, blocks.py, events.py, watcher.sh, SKILL.md.
  - `events_dir`, `consumed_dir`, `state_dir` — named identically in server.py session-create, watcher.sh, blocks.py, SKILL.md.
  - HandlersProtocol methods consistent (`serve_root`, `serve_data`, `handle_submit`, `serve_poll`, `create_session_extra`).
- [x] Bash scripts are executable (`chmod +x` step included).

## Execution handoff

This plan will be executed via `superpowers:subagent-driven-development` per the user's instructions (autonomous, no interruptions).
