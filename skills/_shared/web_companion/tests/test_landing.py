"""Tests for the session-browser landing page served at `/`."""
import json
import threading
import urllib.error
import urllib.request
from pathlib import Path

import skills._shared.web_companion.server as server_mod
from skills._shared.web_companion.handlers import HandlersProtocol

TEST_PORT_RANGE = range(56100, 56120)


class _StubHandlers(HandlersProtocol):
    def serve_root(self, handler, dirs):
        handler._send_text(200, "root")

    def serve_poll(self, handler, dirs):
        handler._send_json(200, {"events": []})

    def serve_data(self, handler, dirs, query):
        handler._send_text(404, "not found")

    def handle_submit(self, handler, dirs, payload):
        handler._send_text(200, "ok")

    def create_session_extra(self, payload, dirs):
        return {}


def _start_server(tmp_path: Path, static_dirs):
    started = threading.Event()
    port_holder = {}
    original_write = server_mod.sys.stdout.write

    def _patched_write(s):
        if '"type": "server-started"' in s or '"type":"server-started"' in s:
            try:
                info = json.loads(s.strip())
                port_holder["port"] = info["port"]
            except Exception:
                pass
            started.set()
        return original_write(s)

    server_mod.sys.stdout.write = _patched_write
    t = threading.Thread(
        target=server_mod.run,
        kwargs=dict(
            skill_name="test_landing_skill",
            port_range=TEST_PORT_RANGE,
            handlers=_StubHandlers(),
            static_dirs=static_dirs,
            shutdown_after_seconds=300,
        ),
        daemon=True,
    )
    t.start()
    started.wait(timeout=5)
    server_mod.sys.stdout.write = original_write
    assert "port" in port_holder, "Server did not start in time"
    return port_holder["port"]


def _get(port, path):
    url = f"http://127.0.0.1:{port}{path}"
    try:
        with urllib.request.urlopen(url) as resp:
            return resp.status, resp.read().decode(), resp.headers.get("Content-Type", "")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(), ""


def test_root_route_serves_sessions_html_live(tmp_path, monkeypatch):
    """End-to-end: hit a real running server's `/` and confirm it returns the
    exact sessions.html bytes, not the old plain-text banner."""
    monkeypatch.setenv("HOME", str(tmp_path))
    static_dirs = [server_mod.SHARED_STATIC_DIR]
    port = _start_server(tmp_path, static_dirs)

    status, body, content_type = _get(port, "/")
    assert status == 200
    assert "text/html" in content_type
    expected = Path("skills/_shared/web_companion/static/sessions.html").read_text()
    assert body == expected


def test_landing_html_exists_and_references_api():
    p = Path("skills/_shared/web_companion/static/sessions.html")
    assert p.exists()
    html = p.read_text()
    assert "/api/sessions" in html
    assert "/s/" in html  # rows link to per-session route


def test_root_route_serves_sessions_html_via_static_serve():
    """Trace the `/` branch statically: it must call static_serve.serve with
    "sessions.html" against static_dirs, and that file must resolve to the
    real static file on disk (mirrors what /static/... does for other
    assets)."""
    import inspect

    source = inspect.getsource(server_mod)
    assert 'static_serve.serve(self, "sessions.html", static_dirs)' in source

    # SHARED_STATIC_DIR is the static root registered first by every caller
    # of server.run(), so "sessions.html" must live there.
    resolved = server_mod.SHARED_STATIC_DIR / "sessions.html"
    assert resolved.is_file()
    assert resolved.read_bytes() == Path(
        "skills/_shared/web_companion/static/sessions.html"
    ).read_bytes()
