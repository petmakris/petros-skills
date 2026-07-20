from pathlib import Path
from skills._shared.web_companion.sessions import Registry
from skills._shared.web_companion.server import create_or_attach


def _mkdirs(base):
    dirs = {}
    for name in ("response", "annotations", "state"):
        d = base / name; d.mkdir(parents=True, exist_ok=True); dirs[f"{name}_dir"] = d
    (base / "state" / "events").mkdir(parents=True, exist_ok=True)
    (base / "state" / "consumed").mkdir(parents=True, exist_ok=True)
    dirs["events_dir"] = base / "state" / "events"
    dirs["consumed_dir"] = base / "state" / "consumed"
    return dirs


def test_create_returns_slug_and_slug_url(tmp_path):
    r = Registry(tmp_path)
    res, created = create_or_attach(
        r, "annotate", {"title": "Auth Review", "project": "svc"}, str(tmp_path),
        lambda sid: _mkdirs(tmp_path / ".claude" / "annotate" / sid))
    assert created is True
    assert res["slug"] == "auth-review"
    assert r.get_meta(res["sid"])["title"] == "Auth Review"


def test_attach_returns_existing_session(tmp_path):
    r = Registry(tmp_path)
    res1, _ = create_or_attach(r, "annotate", {"title": "Work X"}, str(tmp_path),
        lambda sid: _mkdirs(tmp_path / ".claude" / "annotate" / sid))
    res2, created2 = create_or_attach(
        r, "annotate", {"title": "Work X", "slug": res1["slug"], "attach": True},
        str(tmp_path), lambda sid: _mkdirs(tmp_path / ".claude" / "annotate" / sid))
    assert created2 is False
    assert res2["sid"] == res1["sid"]               # same workspace, no new sid


def test_attach_self_heals_when_dir_gone(tmp_path):
    r = Registry(tmp_path)
    res1, _ = create_or_attach(r, "annotate", {"title": "Gone"}, str(tmp_path),
        lambda sid: _mkdirs(tmp_path / ".claude" / "annotate" / sid))
    import shutil; shutil.rmtree(r.lookup(res1["sid"])["state_dir"].parent)
    res2, created2 = create_or_attach(
        r, "annotate", {"title": "Gone", "slug": res1["slug"], "attach": True},
        str(tmp_path), lambda sid: _mkdirs(tmp_path / ".claude" / "annotate" / sid))
    assert created2 is True                          # fell back to create
    assert res2["slug"].startswith("gone")


def test_explicit_dirty_slug_is_sanitized(tmp_path):
    import re
    r = Registry(tmp_path)
    res, created = create_or_attach(
        r, "annotate", {"title": "x", "slug": "My Cool Work!"}, str(tmp_path),
        lambda sid: _mkdirs(tmp_path / ".claude" / "annotate" / sid))
    assert created is True
    assert res["slug"] == "my-cool-work"
    assert re.match(r"^[a-zA-Z0-9_-]+$", res["slug"])
