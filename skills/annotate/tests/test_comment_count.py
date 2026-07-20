"""Unit tests for annotate's Handlers.comment_count.

comment_count must read the REAL comment sources — events_dir (still-queued)
and consumed_dir (processed acks) — deduped by stem, not the dead
annotations_dir (see references/pushing.md: "no longer used").
"""
from skills.annotate.server import Handlers


def _dirs(tmp_path):
    events_dir = tmp_path / "events"
    consumed_dir = tmp_path / "consumed"
    events_dir.mkdir()
    consumed_dir.mkdir()
    return {"events_dir": events_dir, "consumed_dir": consumed_dir}


def test_comment_count_zero_when_empty(tmp_path):
    h = Handlers()
    assert h.comment_count(_dirs(tmp_path)) == 0


def test_comment_count_counts_queued_events(tmp_path):
    dirs = _dirs(tmp_path)
    (dirs["events_dir"] / "e1.json").write_text("{}")
    (dirs["events_dir"] / "e2.json").write_text("{}")
    h = Handlers()
    assert h.comment_count(dirs) == 2


def test_comment_count_counts_consumed_acks(tmp_path):
    dirs = _dirs(tmp_path)
    (dirs["consumed_dir"] / "e1.ack").write_text("")
    h = Handlers()
    assert h.comment_count(dirs) == 1


def test_comment_count_dedups_event_seen_in_both_queued_and_consumed(tmp_path):
    """An event that is both still-present in events_dir and already acked
    in consumed_dir (a brief race window) must count once, not twice."""
    dirs = _dirs(tmp_path)
    (dirs["events_dir"] / "e1.json").write_text("{}")
    (dirs["events_dir"] / "e2.json").write_text("{}")
    (dirs["consumed_dir"] / "e1.ack").write_text("")
    (dirs["consumed_dir"] / "e3.ack").write_text("")
    h = Handlers()
    assert h.comment_count(dirs) == 3  # e1, e2, e3


def test_comment_count_missing_dirs_is_zero(tmp_path):
    h = Handlers()
    missing = {
        "events_dir": tmp_path / "no-events",
        "consumed_dir": tmp_path / "no-consumed",
    }
    assert h.comment_count(missing) == 0


def test_comment_count_ignores_dead_annotations_dir(tmp_path):
    """Even if annotations_dir is present in dirs (legacy key still passed
    through for other callers), comment_count must not look at it."""
    dirs = _dirs(tmp_path)
    annotations_dir = tmp_path / "annotations"
    annotations_dir.mkdir()
    (annotations_dir / "stale.json").write_text("{}")
    dirs["annotations_dir"] = annotations_dir
    h = Handlers()
    assert h.comment_count(dirs) == 0
