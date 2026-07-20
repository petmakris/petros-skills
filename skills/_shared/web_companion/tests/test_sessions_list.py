# skills/_shared/web_companion/tests/test_sessions_list.py
from pathlib import Path
from skills._shared.web_companion.sessions import Registry
from skills._shared.web_companion.server import list_rows, session_row


def _session(reg, tmp_path, sid, slug, title, project, threads=0):
    base = tmp_path / sid
    ann = base / "annotations"; st = base / "state"
    ann.mkdir(parents=True); st.mkdir(parents=True)
    for i in range(threads):
        (ann / f"t{i}.json").write_text("{}")
    reg.register(sid, {"state_dir": st, "annotations_dir": ann, "_cwd": str(tmp_path)})
    reg.register_meta(sid, {"slug": slug, "title": title, "project": project, "created_at": 1})
    return st


def test_legacy_shape_with_cwd(tmp_path):
    r = Registry(tmp_path)
    _session(r, tmp_path, "260720-2-b", "s2", "Two", "p")
    rows = list_rows(r, str(tmp_path), "", now=1000)
    assert rows and set(rows[0]) == {"sid", "pr_ref", "title", "state_dir"}  # exact legacy keys


def test_all_sessions_extended_scope_all(tmp_path):
    r = Registry(tmp_path)
    _session(r, tmp_path, "260720-1-a", "s1", "One", "projA", threads=3)
    rows = list_rows(r, "", "all", now=1000)
    row = rows[0]
    assert row["slug"] == "s1" and row["project"] == "projA"
    assert row["comment_count"] == 3
    assert row["status"] in ("live", "idle", "done")


def test_status_live_when_heartbeat_fresh(tmp_path):
    r = Registry(tmp_path)
    st = _session(r, tmp_path, "260720-1-a", "s1", "One", "p")
    (st / "watcher_heartbeat").write_text("999")   # fresh vs now=1000 (age 1s)
    rows = list_rows(r, "", "all", now=1000)
    assert rows[0]["status"] == "live"


def test_neither_cwd_nor_scope_returns_empty(tmp_path):
    # handler turns this into the legacy 400; list_rows itself just yields []
    r = Registry(tmp_path)
    _session(r, tmp_path, "260720-1-a", "s1", "One", "p")
    assert list_rows(r, "", "", now=1000) == []


def test_scope_all_keeps_idle_with_stale_heartbeat(tmp_path):
    """Regression: a watcher stops beating the instant its Claude session
    ends, leaving a STALE (not absent) heartbeat file on disk. scope=all
    must still surface the workspace (as idle), unlike the legacy ?cwd=
    branch which correctly reaps by watcher age."""
    r = Registry(tmp_path)
    st = _session(r, tmp_path, "260720-1-a", "s1", "One", "p")
    now = 1_000_000
    stale_hb = now - 10_000   # far beyond REAP_AFTER (180s)
    hb_path = st / "watcher_heartbeat"
    hb_path.write_text(str(stale_hb))
    old_mtime = now - 10_000
    import os as _os
    _os.utime(hb_path, (old_mtime, old_mtime))

    rows = list_rows(r, "", "all", now=now)
    assert len(rows) == 1
    assert rows[0]["status"] == "idle"


def test_scope_all_excludes_beyond_retention(tmp_path, monkeypatch):
    """A row whose last activity is older than the retention window (env
    WEBCOMPANION_RETENTION_DAYS, default 7d) must not appear in scope=all."""
    monkeypatch.setenv("WEBCOMPANION_RETENTION_DAYS", "1")  # 86400s window
    r = Registry(tmp_path)
    st = _session(r, tmp_path, "260720-1-a", "s1", "One", "p")
    now = 1_000_000
    # No heartbeat file -> last_active falls back to meta.created_at (1),
    # which is far beyond the 1-day retention window relative to `now`.
    rows = list_rows(r, "", "all", now=now)
    assert rows == []
