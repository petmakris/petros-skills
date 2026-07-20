from pathlib import Path
from skills._shared.web_companion.sessions import Registry
from skills._shared.web_companion.server import create_or_attach, list_rows


def _mkdirs_factory(root):
    def mk(sid):
        base = root / ".claude" / "annotate" / sid
        dirs = {}
        for name in ("response", "annotations", "state"):
            d = base / name; d.mkdir(parents=True, exist_ok=True); dirs[f"{name}_dir"] = d
        (base / "state" / "events").mkdir(parents=True, exist_ok=True)
        (base / "state" / "consumed").mkdir(parents=True, exist_ok=True)
        dirs["events_dir"] = base / "state" / "events"
        dirs["consumed_dir"] = base / "state" / "consumed"
        return dirs
    return mk


def test_create_attach_resolve_list(tmp_path):
    r = Registry(tmp_path)
    mk = _mkdirs_factory(tmp_path)
    r1, c1 = create_or_attach(r, "annotate", {"title": "Persistent Workspaces", "project": "petros-skills"}, str(tmp_path), mk)
    assert c1 and r1["slug"] == "persistent-workspaces"

    # Second push attaches — same workspace, no new sid.
    r2, c2 = create_or_attach(r, "annotate", {"title": "Persistent Workspaces", "slug": r1["slug"], "attach": True}, str(tmp_path), mk)
    assert not c2 and r2["sid"] == r1["sid"]

    # Slug resolves to the canonical sid.
    assert r.resolve("persistent-workspaces") == r1["sid"]

    # Appears in the all-sessions list with its metadata.
    rows = list_rows(r, "", "all", now=10_000)
    hit = [x for x in rows if x["slug"] == "persistent-workspaces"]
    assert hit and hit[0]["title"] == "Persistent Workspaces" and hit[0]["project"] == "petros-skills"
