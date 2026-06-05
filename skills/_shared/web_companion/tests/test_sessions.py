import json
import importlib
import re
import tempfile
from pathlib import Path

import pytest

sessions_mod = importlib.import_module("skills._shared.web_companion.sessions")
Registry = sessions_mod.Registry
SID_RE = sessions_mod.SID_RE


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
    assert r2.lookup(sid) is None


def test_find_by_cwd_returns_only_matching_sessions(tmp_path):
    from skills._shared.web_companion.sessions import Registry
    reg = Registry(state_root=tmp_path / "reg")
    s1 = reg.make_sid()
    s2 = reg.make_sid()
    s3 = reg.make_sid()
    reg.register(s1, {"state_dir": tmp_path / "a/state", "_cwd": "/proj/a"})
    reg.register(s2, {"state_dir": tmp_path / "b/state", "_cwd": "/proj/b"})
    reg.register(s3, {"state_dir": tmp_path / "a2/state", "_cwd": "/proj/a"})
    matches = reg.find_by_cwd("/proj/a")
    sids = {sid for sid, _ in matches}
    assert sids == {s1, s3}


def test_find_by_cwd_returns_empty_when_no_match(tmp_path):
    from skills._shared.web_companion.sessions import Registry
    reg = Registry(state_root=tmp_path / "reg")
    reg.register(reg.make_sid(), {"state_dir": tmp_path / "x/state", "_cwd": "/proj/x"})
    assert reg.find_by_cwd("/proj/missing") == []


def test_find_by_cwd_works_after_rehydrate(tmp_path):
    """Regression: _cwd must survive persistence and round-trip as a string."""
    from skills._shared.web_companion.sessions import Registry
    reg1 = Registry(state_root=tmp_path)
    sid = reg1.make_sid()
    # All dirs entries must be real directories for rehydrate to keep the row.
    state_dir = tmp_path / "s1" / "state"
    state_dir.mkdir(parents=True)
    reg1.register(sid, {"state_dir": state_dir, "_cwd": str(tmp_path)})
    reg1.persist()

    # New Registry instance reads the same persisted state.
    reg2 = Registry(state_root=tmp_path)
    reg2.rehydrate()
    matches = reg2.find_by_cwd(str(tmp_path))
    sids = {sid_ for sid_, _ in matches}
    assert sid in sids, f"_cwd lookup failed after rehydrate; got {matches}"


def test_waiter_returns_same_event_for_same_sid(tmp_path):
    r = Registry(state_root=tmp_path / "reg")
    sid = r.make_sid()
    ev1 = r.waiter(sid)
    ev2 = r.waiter(sid)
    assert ev1 is ev2


def test_waiter_returns_different_events_for_different_sids(tmp_path):
    r = Registry(state_root=tmp_path / "reg")
    sid1 = r.make_sid()
    import time
    time.sleep(0.01)
    sid2 = r.make_sid()
    ev1 = r.waiter(sid1)
    ev2 = r.waiter(sid2)
    assert ev1 is not ev2


def test_note_change_wakes_waiter(tmp_path):
    """note_change must set then clear the event so a waiting thread unblocks."""
    import threading
    r = Registry(state_root=tmp_path / "reg")
    sid = r.make_sid()
    ev = r.waiter(sid)
    results = []

    def waiter_thread():
        woke = ev.wait(timeout=2)
        results.append(woke)

    t = threading.Thread(target=waiter_thread)
    t.start()
    import time
    time.sleep(0.05)  # Let the thread start waiting
    r.note_change(sid)
    t.join(timeout=3)
    assert results == [True], f"waiter did not wake: {results}"


def test_note_change_clears_event_after_set(tmp_path):
    """After note_change the event should be cleared so new waiters block."""
    r = Registry(state_root=tmp_path / "reg")
    sid = r.make_sid()
    r.note_change(sid)
    ev = r.waiter(sid)
    # The event should be cleared now; wait with zero timeout should return False
    assert not ev.wait(timeout=0)
