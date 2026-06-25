"""Integration tests for GET /api/sessions?cwd= route in web_companion server."""
import json
import threading
import time
import urllib.parse
import urllib.request
import urllib.error
from pathlib import Path

import pytest

import skills._shared.web_companion.server as server_mod
from skills._shared.web_companion.handlers import HandlersProtocol
from skills._shared.web_companion.sessions import Registry


# ---------------------------------------------------------------------------
# Minimal stub handlers so server.run() can start without a real skill
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# Helper: start server in a background thread, yield port, stop on teardown
# ---------------------------------------------------------------------------

TEST_PORT_RANGE = range(56000, 56020)


def _start_server(tmp_path: Path):
    """Start server.run() in a daemon thread. Returns (port, registry_holder)."""
    skill_name = "test_skill"
    state_root = tmp_path / ".claude" / skill_name
    state_root.mkdir(parents=True, exist_ok=True)

    # We need access to the registry AFTER it's created inside server.run().
    # Inject a hook by monkey-patching Registry.__init__ briefly.
    registry_holder = {}
    original_init = Registry.__init__

    def _capture_init(self, state_root):
        original_init(self, state_root)
        registry_holder["registry"] = self

    Registry.__init__ = _capture_init

    started = threading.Event()
    port_holder = {}
    original_write = server_mod.sys.stdout.write

    import io
    buf = io.StringIO()

    def _patched_write(s):
        buf.write(s)
        # Detect the server-started JSON line
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
            skill_name=skill_name,
            port_range=TEST_PORT_RANGE,
            handlers=_StubHandlers(),
            static_dirs=[],
            shutdown_after_seconds=300,
        ),
        daemon=True,
    )
    t.start()

    started.wait(timeout=5)
    Registry.__init__ = original_init
    server_mod.sys.stdout.write = original_write

    assert "port" in port_holder, "Server did not start in time"
    return port_holder["port"], registry_holder["registry"]


def _get(port, path):
    url = f"http://127.0.0.1:{port}{path}"
    try:
        with urllib.request.urlopen(url) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

def test_get_sessions_missing_cwd(tmp_path):
    port, _ = _start_server(tmp_path)
    status, body = _get(port, "/api/sessions")
    assert status == 400
    assert "missing cwd" in body


def test_get_sessions_empty_cwd(tmp_path):
    port, _ = _start_server(tmp_path)
    status, body = _get(port, "/api/sessions?cwd=")
    assert status == 400
    assert "missing cwd" in body


def test_get_sessions_no_matching_sessions(tmp_path):
    port, _ = _start_server(tmp_path)
    status, body = _get(port, "/api/sessions?cwd=/nonexistent/path")
    assert status == 200
    data = json.loads(body)
    assert data == []


def test_get_sessions_returns_matching_sessions(tmp_path):
    port, registry = _start_server(tmp_path)

    cwd_a = str(tmp_path / "proj_a")
    cwd_b = str(tmp_path / "proj_b")

    state_a1 = tmp_path / "s_a1" / "state"
    state_a2 = tmp_path / "s_a2" / "state"
    state_b = tmp_path / "s_b" / "state"
    for d in (state_a1, state_a2, state_b):
        d.mkdir(parents=True)

    sid_a1 = registry.make_sid()
    sid_a2 = registry.make_sid()
    sid_b = registry.make_sid()
    registry.register(sid_a1, {"state_dir": state_a1, "_cwd": cwd_a})
    registry.register(sid_a2, {"state_dir": state_a2, "_cwd": cwd_a})
    registry.register(sid_b, {"state_dir": state_b, "_cwd": cwd_b})

    status, body = _get(port, f"/api/sessions?cwd={urllib.parse.quote(cwd_a)}")
    assert status == 200
    data = json.loads(body)
    assert len(data) == 2
    returned_sids = {row["sid"] for row in data}
    assert returned_sids == {sid_a1, sid_a2}
    for row in data:
        assert row["pr_ref"] == ""
        assert row["title"] == ""
        assert "state_dir" in row


def _write_heartbeat(state_dir: Path, age_seconds: int):
    """Write a watcher_heartbeat whose epoch contents are `age_seconds` old."""
    (state_dir / "watcher_heartbeat").write_text(str(int(time.time()) - age_seconds))


def test_get_sessions_reaps_watcher_dead_session(tmp_path):
    """A session whose watcher has been silent past REAP_AFTER is hidden."""
    port, registry = _start_server(tmp_path)
    cwd = str(tmp_path / "proj_reap")

    fresh = tmp_path / "s_fresh" / "state"
    dead = tmp_path / "s_dead" / "state"
    never = tmp_path / "s_never" / "state"
    for d in (fresh, dead, never):
        d.mkdir(parents=True)

    _write_heartbeat(fresh, age_seconds=2)                       # LIVE
    _write_heartbeat(dead, age_seconds=server_mod.REAP_AFTER + 60)  # dead -> reaped
    # `never` has no heartbeat file at all -> age unknown -> NOT reaped

    sid_fresh = registry.make_sid()
    sid_dead = registry.make_sid()
    sid_never = registry.make_sid()
    registry.register(sid_fresh, {"state_dir": fresh, "_cwd": cwd})
    registry.register(sid_dead, {"state_dir": dead, "_cwd": cwd})
    registry.register(sid_never, {"state_dir": never, "_cwd": cwd})

    status, body = _get(port, f"/api/sessions?cwd={urllib.parse.quote(cwd)}")
    assert status == 200
    returned = {row["sid"] for row in json.loads(body)}
    assert sid_dead not in returned, "watcher-dead session must be reaped"
    assert returned == {sid_fresh, sid_never}


def test_get_sessions_newest_first_after_reap(tmp_path):
    """Surviving sessions keep the newest-first (sid-descending) ordering."""
    port, registry = _start_server(tmp_path)
    cwd = str(tmp_path / "proj_order")

    older = tmp_path / "s_older" / "state"
    newer = tmp_path / "s_newer" / "state"
    for d in (older, newer):
        d.mkdir(parents=True)
        _write_heartbeat(d, age_seconds=1)

    sid_older = registry.make_sid()
    sid_newer = registry.make_sid()
    # sids are time-ordered; ensure newer > older lexically
    lo, hi = sorted([sid_older, sid_newer])
    registry.register(lo, {"state_dir": older, "_cwd": cwd})
    registry.register(hi, {"state_dir": newer, "_cwd": cwd})

    status, body = _get(port, f"/api/sessions?cwd={urllib.parse.quote(cwd)}")
    data = json.loads(body)
    assert [row["sid"] for row in data] == [hi, lo]


def test_get_sessions_reads_meta_json(tmp_path):
    port, registry = _start_server(tmp_path)

    cwd = str(tmp_path / "proj_meta")
    state_dir = tmp_path / "s_meta" / "state"
    state_dir.mkdir(parents=True)

    meta = {"pr_ref": "pr/123", "title": "My cool PR"}
    (state_dir / "meta.json").write_text(json.dumps(meta))

    sid = registry.make_sid()
    registry.register(sid, {"state_dir": state_dir, "_cwd": cwd})

    status, body = _get(port, f"/api/sessions?cwd={urllib.parse.quote(cwd)}")
    assert status == 200
    data = json.loads(body)
    assert len(data) == 1
    assert data[0]["pr_ref"] == "pr/123"
    assert data[0]["title"] == "My cool PR"
    assert data[0]["sid"] == sid
