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


def test_on_create_failure_registers_nothing(tmp_path):
    """If on_create raises (e.g. a skill's create_session_extra fails on a
    bad PR ref / gh error), nothing must be registered — no zombie session
    left behind for a failed init."""
    r = Registry(tmp_path)

    def _boom(dirs):
        raise ValueError("gh pr fetch failed")

    try:
        create_or_attach(
            r, "annotate", {"title": "Bad PR"}, str(tmp_path),
            lambda sid: _mkdirs(tmp_path / ".claude" / "annotate" / sid),
            on_create=_boom)
        assert False, "expected ValueError to propagate"
    except ValueError:
        pass

    assert r.items() == []
    assert r.resolve("bad-pr") is None


def test_attach_selfheal_reuses_same_slug(tmp_path):
    """When a resolved attach target's state_dir is gone, the self-heal
    create must reuse the ORIGINAL slug (not bump to -2), and the old dead
    sid must no longer resolve."""
    r = Registry(tmp_path)
    res1, _ = create_or_attach(r, "annotate", {"title": "Same Slug"}, str(tmp_path),
        lambda sid: _mkdirs(tmp_path / ".claude" / "annotate" / sid))
    slug = res1["slug"]
    old_sid = res1["sid"]

    import shutil
    shutil.rmtree(r.lookup(old_sid)["state_dir"].parent)

    res2, created2 = create_or_attach(
        r, "annotate", {"title": "Same Slug", "slug": slug, "attach": True},
        str(tmp_path), lambda sid: _mkdirs(tmp_path / ".claude" / "annotate" / sid))

    assert created2 is True
    assert res2["slug"] == slug                 # exact reuse, no "-2" bump
    assert r.resolve(slug) == res2["sid"]        # slug now points at the NEW sid
    assert r.resolve(old_sid) is None            # old sid is gone from the registry

def test_create_with_supersede_cancels_prior_sessions_of_same_claude_session(tmp_path):
    r = Registry(tmp_path)
    mk = lambda sid: _mkdirs(tmp_path / ".claude" / "annotate" / sid)
    res1, _ = create_or_attach(
        r, "annotate", {"title": "A", "claude_session_id": "cs-1"}, str(tmp_path),
        mk, supersede=True)
    res2, _ = create_or_attach(
        r, "annotate", {"title": "B", "claude_session_id": "cs-1"}, str(tmp_path),
        mk, supersede=True)
    old_state = r.lookup(res1["sid"])["state_dir"]
    new_state = r.lookup(res2["sid"])["state_dir"]
    assert (old_state / "cancelled").exists()
    assert not (new_state / "cancelled").exists()


def test_supersede_leaves_other_claude_sessions_alone(tmp_path):
    r = Registry(tmp_path)
    mk = lambda sid: _mkdirs(tmp_path / ".claude" / "annotate" / sid)
    res_other, _ = create_or_attach(
        r, "annotate", {"title": "Other", "claude_session_id": "cs-other"},
        str(tmp_path), mk, supersede=True)
    create_or_attach(
        r, "annotate", {"title": "Mine", "claude_session_id": "cs-mine"},
        str(tmp_path), mk, supersede=True)
    assert not (r.lookup(res_other["sid"])["state_dir"] / "cancelled").exists()


def test_supersede_disabled_leaves_everything_alone(tmp_path):
    r = Registry(tmp_path)
    mk = lambda sid: _mkdirs(tmp_path / ".claude" / "annotate" / sid)
    res1, _ = create_or_attach(
        r, "annotate", {"title": "A", "claude_session_id": "cs-1"}, str(tmp_path), mk)
    create_or_attach(
        r, "annotate", {"title": "B", "claude_session_id": "cs-1"}, str(tmp_path), mk)
    assert not (r.lookup(res1["sid"])["state_dir"] / "cancelled").exists()


def test_failed_on_create_removes_orphan_tree(tmp_path):
    r = Registry(tmp_path)
    made = {}
    def mk(sid):
        dirs = _mkdirs(tmp_path / ".claude" / "annotate" / sid)
        made["base"] = tmp_path / ".claude" / "annotate" / sid
        return dirs
    def boom(dirs):
        raise RuntimeError("gh failed")
    import pytest as _pytest
    with _pytest.raises(RuntimeError):
        create_or_attach(r, "annotate", {"title": "X"}, str(tmp_path), mk, on_create=boom)
    assert not made["base"].exists()
    assert r.items() == []
