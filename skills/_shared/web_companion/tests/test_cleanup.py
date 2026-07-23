import importlib
import json
from pathlib import Path

cleanup = importlib.import_module("skills._shared.web_companion.cleanup")
sweep_state = cleanup.sweep_state

DAY = 86400
RETENTION = 7 * DAY


def _make_session(root: Path, cwd: Path, sid: str) -> dict:
    """Create an on-disk session dir tree and return its registry `dirs`."""
    base = cwd / ".claude" / "annotate" / sid
    state_dir = base / "state"
    for sub in ("response", "annotations", "state", "state/events", "state/consumed"):
        (base / sub).mkdir(parents=True, exist_ok=True)
    return {
        "response_dir": str(base / "response"),
        "annotations_dir": str(base / "annotations"),
        "state_dir": str(state_dir),
        "events_dir": str(state_dir / "events"),
        "consumed_dir": str(state_dir / "consumed"),
        "_cwd": str(cwd),
    }


def _write_sessions(state_root: Path, sessions: dict) -> None:
    state_root.mkdir(parents=True, exist_ok=True)
    (state_root / "sessions.json").write_text(json.dumps(sessions))


def _set_activity(dirs: dict, when: float) -> None:
    """Backdate every liveness signal to `when` — a truly dormant session has
    no file touched since it went quiet, so age the whole tree, not just one."""
    import os
    base = Path(dirs["state_dir"]).parent
    hb = Path(dirs["state_dir"]) / "watcher_heartbeat"
    hb.write_text("beat")
    for p in sorted(base.rglob("*"), reverse=True) + [base]:
        os.utime(p, (when, when))


def test_noop_when_state_root_missing(tmp_path):
    out = sweep_state(tmp_path / "nope", RETENTION, now=1_000_000.0)
    assert out == {"sessions_removed": 0, "pending_removed": 0, "files_removed": 0, "errors": 0}


def test_removes_dormant_session_and_prunes_registry(tmp_path):
    state_root = tmp_path / "home"
    cwd = tmp_path / "proj"
    dirs = _make_session(tmp_path, cwd, "old-sid")
    _write_sessions(state_root, {"old-sid": dirs})
    now = 1_000_000.0
    _set_activity(dirs, now - 8 * DAY)  # dormant

    out = sweep_state(state_root, RETENTION, now=now)

    assert out["sessions_removed"] == 1
    assert not Path(dirs["state_dir"]).parent.exists()
    assert json.loads((state_root / "sessions.json").read_text()) == {}


def test_keeps_recently_active_session(tmp_path):
    state_root = tmp_path / "home"
    cwd = tmp_path / "proj"
    dirs = _make_session(tmp_path, cwd, "fresh-sid")
    _write_sessions(state_root, {"fresh-sid": dirs})
    now = 1_000_000.0
    _set_activity(dirs, now - 1 * DAY)  # within window

    out = sweep_state(state_root, RETENTION, now=now)

    assert out["sessions_removed"] == 0
    assert Path(dirs["state_dir"]).parent.exists()
    assert "fresh-sid" in json.loads((state_root / "sessions.json").read_text())


def test_old_creation_but_live_heartbeat_is_kept(tmp_path):
    """A session created long ago but still polled must survive — liveness is
    the freshest signal, not the base dir's creation time."""
    import os
    state_root = tmp_path / "home"
    cwd = tmp_path / "proj"
    dirs = _make_session(tmp_path, cwd, "long-lived")
    _write_sessions(state_root, {"long-lived": dirs})
    now = 1_000_000.0
    base = Path(dirs["state_dir"]).parent
    os.utime(base, (now - 30 * DAY, now - 30 * DAY))       # created a month ago
    _set_activity(dirs, now - 60)                          # but beating right now

    out = sweep_state(state_root, RETENTION, now=now)

    assert out["sessions_removed"] == 0
    assert base.exists()


def test_prunes_registry_row_whose_dir_is_already_gone(tmp_path):
    state_root = tmp_path / "home"
    _write_sessions(state_root, {
        "ghost": {"state_dir": str(tmp_path / "proj/.claude/annotate/ghost/state")}
    })
    out = sweep_state(state_root, RETENTION, now=1_000_000.0)
    assert out["sessions_removed"] == 0
    assert json.loads((state_root / "sessions.json").read_text()) == {}


def test_removes_stale_pending_files_by_mtime(tmp_path):
    import os
    state_root = tmp_path / "home"
    state_root.mkdir(parents=True)
    now = 1_000_000.0
    old = state_root / "pending-old.json"
    fresh = state_root / "pending-fresh.json"
    old.write_text("{}")
    fresh.write_text("{}")
    os.utime(old, (now - 10 * DAY, now - 10 * DAY))
    os.utime(fresh, (now - 1 * DAY, now - 1 * DAY))

    out = sweep_state(state_root, RETENTION, now=now)

    assert out["pending_removed"] == 1
    assert not old.exists()
    assert fresh.exists()


def test_prune_globs_removes_ancillary_files(tmp_path):
    import os
    state_root = tmp_path / "home"
    (state_root / "statusline").mkdir(parents=True)
    now = 1_000_000.0
    old = state_root / "statusline" / "old.json"
    old.write_text("{}")
    os.utime(old, (now - 10 * DAY, now - 10 * DAY))

    out = sweep_state(state_root, RETENTION, now=now, extra_globs=("statusline/*.json",))

    assert out["files_removed"] == 1
    assert not old.exists()


def test_unparseable_sessions_file_is_left_alone(tmp_path):
    state_root = tmp_path / "home"
    state_root.mkdir(parents=True)
    (state_root / "sessions.json").write_text("not json{")
    out = sweep_state(state_root, RETENTION, now=1_000_000.0)
    assert out["errors"] == 0
    assert (state_root / "sessions.json").read_text() == "not json{"


def test_entry_without_state_dir_is_preserved(tmp_path):
    state_root = tmp_path / "home"
    _write_sessions(state_root, {"weird": {"_cwd": "/x"}})
    out = sweep_state(state_root, RETENTION, now=1_000_000.0)
    assert out["sessions_removed"] == 0
    assert "weird" in json.loads((state_root / "sessions.json").read_text())


def test_sweep_removes_dormant_unregistered_stray_dirs(tmp_path):
    import json, os, time
    from skills._shared.web_companion.cleanup import sweep_state
    state_root = tmp_path / "root"
    state_root.mkdir()
    project = tmp_path / "proj" / ".claude" / "annotate"
    reg_sid = "260101-000000-" + "a" * 16
    stray_sid = "260101-000001-" + "b" * 16
    now = time.time()
    for sid in (reg_sid, stray_sid):
        (project / sid / "state").mkdir(parents=True)
    old = now - 30 * 86400
    for sid in (reg_sid, stray_sid):
        base = project / sid
        os.utime(base / "state", (old, old))
        os.utime(base, (old, old))
    # registered session has fresh activity; stray has none
    hb = project / reg_sid / "state" / "watcher_heartbeat"
    hb.write_text(str(int(now)))
    (state_root / "sessions.json").write_text(json.dumps({
        reg_sid: {"state_dir": str(project / reg_sid / "state")}}))
    summary = sweep_state(state_root, 7 * 86400, now)
    assert (project / reg_sid).exists()
    assert not (project / stray_sid).exists()
    assert summary["sessions_removed"] == 1


def test_sweep_keeps_recent_stray_and_non_sid_dirs(tmp_path):
    import json, os, time
    from skills._shared.web_companion.cleanup import sweep_state
    state_root = tmp_path / "root"
    state_root.mkdir()
    project = tmp_path / "proj" / ".claude" / "annotate"
    reg_sid = "260101-000000-" + "a" * 16
    fresh_stray = "260101-000002-" + "c" * 16
    now = time.time()
    (project / reg_sid / "state").mkdir(parents=True)
    (project / fresh_stray / "state").mkdir(parents=True)
    (project / "not-a-sid").mkdir()
    old = now - 30 * 86400
    os.utime(project / "not-a-sid", (old, old))
    (project / reg_sid / "state" / "watcher_heartbeat").write_text(str(int(now)))
    (state_root / "sessions.json").write_text(json.dumps({
        reg_sid: {"state_dir": str(project / reg_sid / "state")}}))
    sweep_state(state_root, 7 * 86400, now)
    assert (project / fresh_stray).exists()
    assert (project / "not-a-sid").exists()


def test_sweep_prunes_meta_rows_for_removed_sessions(tmp_path):
    import json, time
    from skills._shared.web_companion.cleanup import sweep_state
    state_root = tmp_path / "root"
    state_root.mkdir()
    gone_sid = "260101-000000-" + "d" * 16
    (state_root / "sessions.json").write_text(json.dumps({
        gone_sid: {"state_dir": str(tmp_path / "missing" / gone_sid / "state")}}))
    (state_root / "sessions_meta.json").write_text(json.dumps({
        gone_sid: {"slug": "gone"}}))
    sweep_state(state_root, 7 * 86400, time.time())
    meta = json.loads((state_root / "sessions_meta.json").read_text())
    assert meta == {}
