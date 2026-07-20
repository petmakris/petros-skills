import os, time
from skills.annotate.server import attached_count


def test_attached_counts_fresh_only(tmp_path):
    w = tmp_path / "watchers"; w.mkdir()
    now = int(time.time())
    fresh = w / "sessA.hb"; fresh.write_text(str(now))
    stale = w / "sessB.hb"; stale.write_text("1")
    os.utime(stale, (now - 999, now - 999))
    assert attached_count(tmp_path, now) == 1


def test_attached_zero_when_no_dir(tmp_path):
    assert attached_count(tmp_path, int(time.time())) == 0
