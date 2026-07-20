from pathlib import Path
from skills._shared.web_companion.sessions import Registry


def test_route_key_resolves_slug_then_sid(tmp_path):
    r = Registry(tmp_path)
    d = tmp_path / "s"; d.mkdir()
    r.register("260720-1-abc", {"state_dir": d, "_cwd": str(tmp_path)})
    r.register_meta("260720-1-abc", {"slug": "pretty-name"})
    # A router that resolves first, then looks up, must find the session by slug.
    sid = r.resolve("pretty-name")
    assert sid == "260720-1-abc"
    assert r.lookup(sid) is not None
    # And still by raw sid (interactive_review path).
    assert r.lookup(r.resolve("260720-1-abc")) is not None
