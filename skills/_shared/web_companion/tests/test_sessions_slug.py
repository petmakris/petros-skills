import json
from pathlib import Path

from skills._shared.web_companion.sessions import Registry


def _reg(tmp_path):
    return Registry(tmp_path)


def _dirs(tmp_path, name):
    d = tmp_path / name
    d.mkdir()
    return {"state_dir": d, "_cwd": str(tmp_path)}


def test_make_slug_basic(tmp_path):
    r = _reg(tmp_path)
    assert r.make_slug("Beautiful Flowchart Block!", str(tmp_path)) == "beautiful-flowchart-block"


def test_make_slug_empty_falls_back_to_project(tmp_path):
    r = _reg(tmp_path)
    slug = r.make_slug("", str(tmp_path))
    assert slug and __import__("re").match(r"^[a-zA-Z0-9_-]+$", slug)


def test_make_slug_dedup(tmp_path):
    r = _reg(tmp_path)
    r.register("s1", _dirs(tmp_path, "a"))
    r.register_meta("s1", {"slug": "auth-review"})
    assert r.make_slug("Auth Review", str(tmp_path)) == "auth-review-2"


def test_resolve_sid_and_slug(tmp_path):
    r = _reg(tmp_path)
    r.register("260720-1-abc", _dirs(tmp_path, "a"))
    r.register_meta("260720-1-abc", {"slug": "my-work"})
    assert r.resolve("260720-1-abc") == "260720-1-abc"
    assert r.resolve("my-work") == "260720-1-abc"
    assert r.resolve("nope") is None


def test_find_by_slug(tmp_path):
    r = _reg(tmp_path)
    r.register("s1", _dirs(tmp_path, "a"))
    r.register_meta("s1", {"slug": "x"})
    assert r.find_by_slug("x") == "s1"
    assert r.find_by_slug("y") is None


def test_meta_persist_and_rehydrate(tmp_path):
    r = _reg(tmp_path)
    r.register("s1", _dirs(tmp_path, "a"))
    r.register_meta("s1", {"slug": "keep", "title": "T", "project": "p", "created_at": 123})
    r.persist()
    assert (tmp_path / "sessions_meta.json").exists()
    r2 = Registry(tmp_path)
    r2.rehydrate()
    assert r2.resolve("keep") == "s1"
    assert r2.get_meta("s1")["title"] == "T"


def test_register_with_slug_atomic_dedup(tmp_path):
    """register_with_slug does the pick+insert under ONE lock, so two
    sessions with the same title base never collide on a slug (the old
    make_slug-then-register path had a check-then-act race here)."""
    r = _reg(tmp_path)
    dirs1 = _dirs(tmp_path, "a")
    dirs2 = _dirs(tmp_path, "b")

    slug1 = r.register_with_slug(
        "sid-1", dirs1, {"title": "Auth Review", "project": "p"}, str(tmp_path))
    slug2 = r.register_with_slug(
        "sid-2", dirs2, {"title": "Auth Review", "project": "p"}, str(tmp_path))

    assert slug1 == "auth-review"
    assert slug2 == "auth-review-2"
    assert r.resolve(slug1) == "sid-1"
    assert r.resolve(slug2) == "sid-2"
    assert set(sid for sid, _ in r.items()) == {"sid-1", "sid-2"}
    assert r.get_meta("sid-1")["title"] == "Auth Review"
    assert r.get_meta("sid-2")["title"] == "Auth Review"


def test_register_with_slug_sanitizes_explicit_slug(tmp_path):
    r = _reg(tmp_path)
    dirs1 = _dirs(tmp_path, "a")
    slug = r.register_with_slug(
        "sid-1", dirs1, {"title": "ignored", "project": "p"}, str(tmp_path),
        explicit_slug="My Cool Work!")
    assert slug == "my-cool-work"
    assert r.resolve("sid-1") == "sid-1"
    assert r.resolve("my-cool-work") == "sid-1"


def test_rehydrate_prunes_meta_without_live_dir(tmp_path):
    r = _reg(tmp_path)
    r.register("s1", _dirs(tmp_path, "a"))
    r.register_meta("s1", {"slug": "ghost"})
    r.persist()
    # remove the session dir so path-rehydrate drops s1
    import shutil; shutil.rmtree(tmp_path / "a")
    r2 = Registry(tmp_path)
    r2.rehydrate()
    assert r2.resolve("ghost") is None  # meta pruned with its dead sid
