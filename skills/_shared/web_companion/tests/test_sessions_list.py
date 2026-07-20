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
