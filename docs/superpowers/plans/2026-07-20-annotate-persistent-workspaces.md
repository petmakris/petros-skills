# Annotate Persistent Named Workspaces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give annotate persistent, human-named workspaces that survive Claude-session exit — a slug alias over the canonical sid, create-or-attach so pushes reuse a workspace, a `/annotate resume` path, an all-projects browser landing page at `/`, and an advisory "N sessions attached" indicator.

**Architecture:** Additive changes to the shared `web_companion` (Registry gains a parallel metadata store + slug alias; the server gains create-or-attach and an extended session list; a static landing page renders the list) plus per-Claude-session heartbeats for the attached count, and annotate skill docs for reuse/resume. The canonical `sid` and every existing route/API shape are preserved so `interactive_review` is unaffected.

**Tech Stack:** Python 3 stdlib (`http.server`, `json`, `re`, `secrets`, `time`, `threading`, `pathlib`), pytest, vanilla JS/CSS, bash (watcher).

## Global Constraints

- **Backward compatibility is mandatory.** `interactive_review` depends on `GET /api/sessions?cwd=<path>` returning rows shaped `{sid, pr_ref, title, state_dir}`, and on routing `/s/<sid>/`. These MUST keep exact behavior. All new fields/params/bodies are additive and optional.
- **Registry rehydrate requires every value in a session's path-dict to be a live directory** (`sessions.py:82` `all(p.is_dir())`). Metadata (slug/title/project/created_at) MUST NOT go in that dict — it lives in a separate parallel store persisted to `sessions_meta.json`.
- `sid` stays canonical and immutable. `slug` is a **live-unique alias**; resolvable to sid; reassignable only after a workspace is GC'd.
- Slug charset must satisfy the existing `SID_RE = ^[a-zA-Z0-9_-]+$` so `/s/<slug>/` routes with no regex change.
- Pure-ish server code; atomic writes via the existing `write_text_atomic` (`web_companion/atomic.py`).
- Tests run from repo root: `python -m pytest <path> -v`. The shared suites live under `skills/_shared/web_companion/tests/` (or the repo's existing web_companion test dir — the implementer confirms the path from the first task).

---

### Task 1: Registry slug + parallel metadata store

**Files:**
- Modify: `skills/_shared/web_companion/sessions.py`
- Test: `skills/_shared/web_companion/tests/test_sessions_slug.py` (create; if that tests dir doesn't exist, mirror the location of the existing sessions/registry tests — find with `git ls-files | grep -i "web_companion.*test"`)

**Interfaces:**
- Consumes: existing `Registry` (`register`, `lookup`, `items`, `persist`, `rehydrate`, `write_text_atomic`).
- Produces on `Registry`:
  - `self._meta: dict[str, dict]` (sid → `{slug, title, project, created_at}`), guarded by the existing `self._lock`.
  - `sessions_meta_file` property → `state_root / "sessions_meta.json"`.
  - `make_slug(title: str, cwd: str) -> str` — slugified, deduped, charset-safe, never empty.
  - `register_meta(sid: str, meta: dict) -> None`.
  - `get_meta(sid: str) -> dict` (empty dict if none).
  - `resolve(key: str) -> str | None` — `key` if it's a known sid; else the sid whose live meta slug == key; else None.
  - `find_by_slug(slug: str) -> str | None`.
  - `list_all() -> list[tuple[str, dict]]` — `(sid, dirs)` for all registered sessions, newest-first by sid.
  - `persist()` also writes `sessions_meta.json`; `rehydrate()` also loads it (pruning meta whose sid is not in the restored path-registry).

- [ ] **Step 1: Write the failing tests**

```python
# skills/_shared/web_companion/tests/test_sessions_slug.py
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest skills/_shared/web_companion/tests/test_sessions_slug.py -v`
Expected: FAIL — `AttributeError: 'Registry' object has no attribute 'make_slug'`.

- [ ] **Step 3: Implement**

In `skills/_shared/web_companion/sessions.py`:

Add `import re` is already present. Extend `__init__`:

```python
    def __init__(self, state_root: Path):
        self._state_root = Path(state_root)
        self._sessions: dict[str, dict[str, Path]] = {}
        self._meta: dict[str, dict] = {}          # sid -> {slug,title,project,created_at}
        self._lock = threading.Lock()
        self._waiters: dict[str, threading.Event] = {}
```

Add these methods (place after `make_sid`):

```python
    @property
    def sessions_meta_file(self) -> Path:
        return self._state_root / "sessions_meta.json"

    @staticmethod
    def _slugify(text: str) -> str:
        s = re.sub(r"[^a-z0-9]+", "-", (text or "").lower()).strip("-")
        return s[:40].strip("-")

    def make_slug(self, title: str, cwd: str) -> str:
        base = self._slugify(title) or self._slugify(Path(cwd).name) or "session"
        with self._lock:
            taken = {m.get("slug") for m in self._meta.values() if m.get("slug")}
        if base not in taken:
            return base
        n = 2
        while f"{base}-{n}" in taken:
            n += 1
        return f"{base}-{n}"

    def register_meta(self, sid: str, meta: dict) -> None:
        with self._lock:
            self._meta[sid] = dict(meta)

    def get_meta(self, sid: str) -> dict:
        with self._lock:
            return dict(self._meta.get(sid, {}))

    def resolve(self, key: str) -> str | None:
        with self._lock:
            if key in self._sessions:
                return key
            for sid, m in self._meta.items():
                if m.get("slug") == key and sid in self._sessions:
                    return sid
        return None

    def find_by_slug(self, slug: str) -> str | None:
        return self.resolve(slug) if slug else None

    def list_all(self) -> list[tuple[str, dict]]:
        items = self.items()
        items.sort(key=lambda kv: kv[0], reverse=True)
        return items
```

Extend `persist()` to also write meta (add after the existing `write_text_atomic(self.sessions_file, ...)`):

```python
        with self._lock:
            meta_snapshot = {sid: dict(m) for sid, m in self._meta.items()}
        write_text_atomic(self.sessions_meta_file, json.dumps(meta_snapshot, indent=2))
```

Extend `rehydrate()` — after the existing path-restore `with self._lock: self._sessions.update(restored)`, load meta and prune to restored sids:

```python
        meta_path = self.sessions_meta_file
        if meta_path.exists():
            try:
                msnap = json.loads(meta_path.read_text())
            except (json.JSONDecodeError, OSError):
                msnap = {}
            if isinstance(msnap, dict):
                with self._lock:
                    live = set(self._sessions)
                    self._meta.update({
                        sid: m for sid, m in msnap.items()
                        if sid in live and isinstance(m, dict)
                    })
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest skills/_shared/web_companion/tests/test_sessions_slug.py -v`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add skills/_shared/web_companion/sessions.py skills/_shared/web_companion/tests/test_sessions_slug.py
git commit -m "feat(web_companion): registry slug alias + parallel metadata store"
```

---

### Task 2: Slug-aware routing

**Files:**
- Modify: `skills/_shared/web_companion/server.py` (`_match_session`, ~line 198-209)
- Test: `skills/_shared/web_companion/tests/test_route_resolve.py` (create) — a focused unit test of `registry.resolve` driving the same lookup `_match_session` uses (routing is inside a nested handler class that's awkward to instantiate; test the resolve contract that routing now depends on).

**Interfaces:**
- Consumes: `Registry.resolve` (Task 1).
- Produces: `_match_session` resolves either a sid or a slug to the canonical sid before `registry.lookup`, and returns that sid.

- [ ] **Step 1: Write the failing test**

```python
# skills/_shared/web_companion/tests/test_route_resolve.py
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python -m pytest skills/_shared/web_companion/tests/test_route_resolve.py -v`
Expected: PASS only after Task 1 landed (it exercises Task 1's `resolve`). If Task 1 is present this test passes immediately — its purpose is to lock the routing contract; the behavior change is in Step 3. (If you are doing strict RED, temporarily assert the OLD behavior first; otherwise proceed — the routing edit in Step 3 is what this task delivers.)

- [ ] **Step 3: Implement the routing change**

In `_match_session` (`server.py:198-209`), replace the sid-match/lookup block so a slug resolves to its sid:

```python
        def _match_session(self, prefix: str):
            if not self.path.startswith(prefix):
                return None
            tail = self.path[len(prefix):]
            if "/" not in tail:
                return None
            key, rest = tail.split("/", 1)
            if not SID_RE.match(key):
                return None
            sid = registry.resolve(key)          # slug OR sid -> canonical sid
            if sid is None:
                return None
            return sid, "/" + rest
```

(Downstream code already does `registry.lookup(sid)` on the returned sid — unchanged.)

- [ ] **Step 4: Verify**

Run: `python -m pytest skills/_shared/web_companion/tests/test_route_resolve.py skills/_shared/web_companion/tests/test_sessions_slug.py -v`
Expected: PASS. Then run any existing web_companion server/route tests to confirm no regression: `python -m pytest skills/_shared/web_companion/tests/ -q`.

- [ ] **Step 5: Commit**

```bash
git add skills/_shared/web_companion/server.py skills/_shared/web_companion/tests/test_route_resolve.py
git commit -m "feat(web_companion): resolve /s/<slug|sid>/ routes via registry alias"
```

---

### Task 3: Create-or-attach in POST /api/sessions

**Files:**
- Modify: `skills/_shared/web_companion/server.py` (`_handle_create_session`, ~line 341-398)
- Test: `skills/_shared/web_companion/tests/test_create_attach.py` (create) — drive `_handle_create_session` via a lightweight fake request, OR (simpler and preferred) extract the pure decision into a testable helper `create_or_attach(registry, skill_name, payload, make_dirs) -> dict` and unit-test that. Implement the helper and have `_handle_create_session` call it.

**Interfaces:**
- Consumes: `Registry` (`make_sid`, `make_slug`, `register`, `register_meta`, `persist`, `find_by_cwd`, `find_by_slug`, `get_meta`, `lookup`).
- Produces: `create_or_attach(registry, skill_name, payload, cwd, mkdirs) -> (result: dict, created: bool)`:
  - `payload` may include `title`, `project`, `slug`, `attach` (bool).
  - **Attach path** (`attach` true): if a live workspace resolves — by explicit `slug`, else the newest `find_by_cwd(cwd)` — AND its `state_dir` still exists, return that session's `{sid, slug, urls, dirs}` with `created=False`.
  - **Create path** (default, or attach with no live/again-missing dir): mint sid, compute `slug = payload["slug"] or make_slug(title, cwd)` (dedup via make_slug when not explicit; if explicit slug collides with a live one, suffix it too), mkdirs, `register` + `register_meta({slug,title,project,created_at})` + `persist`, return with `created=True`.
  - The returned dict's `url`/`localhost_url` use the **slug** (`/s/<slug>/`).

- [ ] **Step 1: Write the failing tests**

```python
# skills/_shared/web_companion/tests/test_create_attach.py
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
    assert res["url"].endswith("/s/auth-review/")   # url built by caller from slug; helper returns slug + sid
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
```

Note the `url` assertion: have the helper return `sid` + `slug` and let the HTTP layer build the URL; in the test, assert on a URL the helper also returns for convenience (`res["url"]` built from a passed `url_for(slug)` callable) OR assert on `res["slug"]` only. Keep the helper's return shape: `{"sid","slug","dirs": {...}, "created": bool}` and build URLs in `_handle_create_session`. Adjust the test's `url` assertion to read `res["slug"]` if you keep URLs out of the helper.

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest skills/_shared/web_companion/tests/test_create_attach.py -v`
Expected: FAIL — `ImportError: cannot import name 'create_or_attach'`.

- [ ] **Step 3: Implement the helper + wire it**

Add a module-level function in `server.py` (near the other module helpers, outside the handler class):

```python
def create_or_attach(registry, skill_name, payload, cwd, mkdirs):
    """Return ({sid, slug, dirs, created}, created_bool).

    mkdirs(sid) -> dirs dict (response_dir/annotations_dir/state_dir/events_dir/
    consumed_dir), all created. Pure of HTTP; URL assembly is the caller's job.
    """
    import time as _t
    title = (payload.get("title") or "").strip()
    project = (payload.get("project") or Path(cwd).name).strip()
    explicit_slug = (payload.get("slug") or "").strip()
    want_attach = bool(payload.get("attach"))

    if want_attach:
        target_sid = None
        if explicit_slug:
            target_sid = registry.find_by_slug(explicit_slug)
        else:
            live = registry.find_by_cwd(cwd)
            live.sort(key=lambda kv: kv[0], reverse=True)
            target_sid = live[0][0] if live else None
        if target_sid is not None:
            dirs = registry.lookup(target_sid)
            if dirs and Path(dirs["state_dir"]).is_dir():
                meta = registry.get_meta(target_sid)
                return ({"sid": target_sid, "slug": meta.get("slug", target_sid),
                         "dirs": dirs, "created": False}, False)
        # fall through to create (self-heal)

    sid = registry.make_sid()
    slug = explicit_slug or registry.make_slug(title, cwd)
    # if an explicit slug collides with a live one, dedup it too
    if explicit_slug and registry.find_by_slug(explicit_slug):
        slug = registry.make_slug(explicit_slug, cwd)
    dirs = mkdirs(sid)
    dirs["_cwd"] = str(cwd)
    registry.register(sid, dirs)
    registry.register_meta(sid, {
        "slug": slug, "title": title, "project": project,
        "created_at": int(_t.time()),
    })
    registry.persist()
    return ({"sid": sid, "slug": slug, "dirs": dirs, "created": True}, True)
```

Rewrite `_handle_create_session` to use it. Keep the cwd validation; replace the sid/dir/registry block (`server.py:359-398`) with:

```python
            def _mkdirs(sid):
                base = cwd / ".claude" / skill_name / sid
                response_dir = base / "response"
                annotations_dir = base / "annotations"
                state_dir = base / "state"
                events_dir = state_dir / "events"
                consumed_dir = state_dir / "consumed"
                for d in (response_dir, annotations_dir, state_dir, events_dir, consumed_dir):
                    d.mkdir(parents=True, exist_ok=True)
                return {
                    "response_dir": response_dir, "annotations_dir": annotations_dir,
                    "state_dir": state_dir, "events_dir": events_dir,
                    "consumed_dir": consumed_dir,
                }

            result, _created = create_or_attach(registry, skill_name, payload, cwd, _mkdirs)
            sid = result["sid"]; slug = result["slug"]; dirs = result["dirs"]
            try:
                extra = handlers.create_session_extra(payload, dirs) or {}
            except Exception as e:
                self._send_text(500, f"session-init failed: {e}")
                return
            port = server_holder['server'].server_address[1]
            self._send_json(200, {
                "sid": sid,
                "slug": slug,
                "created": _created,
                "url": f"http://{public_host}:{port}/s/{slug}/",
                "localhost_url": f"http://localhost:{port}/s/{slug}/",
                "response_dir": str(dirs["response_dir"]),
                "annotations_dir": str(dirs["annotations_dir"]),
                "state_dir": str(dirs["state_dir"]),
                "events_dir": str(dirs["events_dir"]),
                "consumed_dir": str(dirs["consumed_dir"]),
                **extra,
            })
```

(`create_session_extra` still runs on attach too; it's idempotent per the existing handlers — the implementer confirms annotate's `create_session_extra` tolerates an existing dir. If it does not, guard it to run only when `_created`.)

- [ ] **Step 4: Run tests + regression**

Run: `python -m pytest skills/_shared/web_companion/tests/test_create_attach.py -v`
Then: `python -m pytest skills/_shared/web_companion/tests/ -q`
Expected: PASS, no regression.

- [ ] **Step 5: Commit**

```bash
git add skills/_shared/web_companion/server.py skills/_shared/web_companion/tests/test_create_attach.py
git commit -m "feat(web_companion): create-or-attach sessions with slug URLs"
```

---

### Task 4: Extend GET /api/sessions (all-sessions + rich rows)

**Files:**
- Modify: `skills/_shared/web_companion/server.py` (the `/api/sessions` GET branch, ~line 260-299)
- Test: `skills/_shared/web_companion/tests/test_sessions_list.py` (create) — extract the row-building into a testable helper `session_row(sid, dirs, meta, now, legacy=False) -> dict` and a `list_rows(registry, cwd, scope, now) -> list[dict]`; unit-test both shapes.

**BACKWARD-COMPAT DECISION (revised):** The existing `test_get_sessions_route.py`
asserts `400 "missing cwd"` for missing/empty cwd. We KEEP that contract intact.
The browser's "all sessions" is a NEW explicit signal `?scope=all` — NOT an
overload of no-cwd. So: `?cwd=<path>` → legacy 200; `?scope=all` → extended 200;
neither → **400 unchanged**. Do NOT modify `test_get_sessions_route.py`.

**Interfaces:**
- Consumes: `Registry.list_all`, `find_by_cwd`, `get_meta`; existing `_watcher_age`, `_is_terminal`, `REAP_AFTER`.
- Produces:
  - `list_rows(registry, cwd, scope, now)`: `cwd` truthy → legacy filtered rows (exact `{sid, pr_ref, title, state_dir}` shape, newest-first) for interactive_review; else `scope == "all"` → ALL live rows in the **extended** shape; else → `[]` (the handler turns "neither cwd nor scope" into the existing 400 before calling, so this branch is just a safe default).
  - Extended row: `{sid, slug, title, project, last_active, comment_count, status, state_dir, pr_ref}`.
  - `status`: `"done"` if terminal (`finished`); `"live"` if `_watcher_age(dirs)` is fresh (< `LIVE_WINDOW=10`); else `"idle"`.
  - `comment_count`: number of thread files in `annotations_dir` (count `*.json` there; 0 if dir missing).
  - GET behavior: `?cwd=` unchanged (incl. its 400 on missing/empty cwd); `?scope=all` returns extended rows.

- [ ] **Step 1: Write the failing tests**

```python
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest skills/_shared/web_companion/tests/test_sessions_list.py -v`
Expected: FAIL — `ImportError: cannot import name 'list_rows'`.

- [ ] **Step 3: Implement helpers + wire the GET branch**

Add module-level constant and helpers in `server.py` (near `REAP_AFTER`):

```python
LIVE_WINDOW = 10  # seconds; heartbeat fresher than this => "live"


def _read_hb(state_dir):
    p = Path(state_dir) / "watcher_heartbeat"
    if not p.exists():
        return None
    try:
        return int(p.read_text().strip())
    except (ValueError, OSError):
        return None


def _comment_count(dirs):
    ann = dirs.get("annotations_dir")
    if not ann:
        return 0
    ann = Path(ann)
    return sum(1 for _ in ann.glob("*.json")) if ann.is_dir() else 0


def session_row(sid, dirs, meta, now, legacy=False):
    if legacy:
        return {"sid": sid, "pr_ref": meta.get("pr_ref", ""),
                "title": meta.get("title", ""), "state_dir": str(dirs["state_dir"])}
    hb = _read_hb(dirs["state_dir"])
    if _is_terminal(dirs):
        status = "done"
    elif hb is not None and (now - hb) < LIVE_WINDOW:
        status = "live"
    else:
        status = "idle"
    return {
        "sid": sid, "slug": meta.get("slug", sid),
        "title": meta.get("title", ""), "project": meta.get("project", ""),
        "pr_ref": meta.get("pr_ref", ""),
        "last_active": hb or meta.get("created_at", 0),
        "comment_count": _comment_count(dirs),
        "status": status, "state_dir": str(dirs["state_dir"]),
    }


def list_rows(registry, cwd, scope, now):
    if cwd:
        pairs = registry.find_by_cwd(cwd)
        out = []
        for sid, dirs in pairs:
            age = _watcher_age(dirs)
            if _is_terminal(dirs) or (age is not None and age > REAP_AFTER):
                continue
            out.append(session_row(sid, dirs, _session_meta(registry, dirs, sid), now, legacy=True))
        out.sort(key=lambda r: r["sid"], reverse=True)
        return out
    if scope != "all":
        return []          # handler renders the legacy 400 for this case
    # scope=all -> all live sessions, extended
    out = []
    for sid, dirs in registry.list_all():
        age = _watcher_age(dirs)
        if age is not None and age > REAP_AFTER and not _is_terminal(dirs):
            continue
        out.append(session_row(sid, dirs, registry.get_meta(sid), now))
    out.sort(key=lambda r: r["last_active"], reverse=True)
    return out
```

`_session_meta` for the legacy path must preserve today's behavior of reading `state_dir/meta.json` for `pr_ref`/`title` (the existing code reads meta.json, not the registry meta) — keep that exact source so interactive_review is byte-for-byte unchanged:

```python
def _session_meta(registry, dirs, sid):
    meta = {}
    mp = Path(dirs["state_dir"]) / "meta.json"
    if mp.exists():
        try:
            meta = json.loads(mp.read_text())
        except json.JSONDecodeError:
            meta = {}
    return meta
```

Replace the GET `/api/sessions` branch body (`server.py:260-299`) with — note
the 400-on-missing-cwd is **preserved**; only an explicit `?scope=all` yields the
all-sessions list:

```python
            if self.path.startswith("/api/sessions"):
                from urllib.parse import urlparse, parse_qs
                qs = parse_qs(urlparse(self.path).query)
                cwd = (qs.get("cwd") or [""])[0]
                scope = (qs.get("scope") or [""])[0]
                if not cwd and scope != "all":
                    self._send_text(400, "missing cwd")   # legacy contract intact
                    return
                rows = list_rows(registry, cwd, scope, now=int(time.time()))
                self._send_json(200, rows)
                return
```

This keeps `test_get_sessions_route.py` (missing/empty cwd → 400) green while the
browser fetches `/api/sessions?scope=all`. Do NOT modify that test.

- [ ] **Step 4: Run tests + regression**

Run: `python -m pytest skills/_shared/web_companion/tests/test_sessions_list.py -v`
Then: `python -m pytest skills/_shared/web_companion/tests/ -q` and any interactive_review tests: `python -m pytest -q -k sessions`.
Expected: PASS. The legacy-shape test guards interactive_review.

- [ ] **Step 5: Commit**

```bash
git add skills/_shared/web_companion/server.py skills/_shared/web_companion/tests/test_sessions_list.py
git commit -m "feat(web_companion): GET /api/sessions returns all sessions with rich rows"
```

---

### Task 5: Session browser landing page at `/`

**Files:**
- Create: `skills/_shared/web_companion/static/sessions.html`
- Modify: `skills/_shared/web_companion/server.py` (the `/` GET branch, ~line 240-242) to serve the static file
- Test: `skills/_shared/web_companion/tests/test_landing.py` (create) — assert `/`'s server branch serves the HTML file's bytes (unit via the static-serve path) and that the file references `/api/sessions`.

**Interfaces:**
- Consumes: `GET /api/sessions` (Task 4); the static-serve module (`static_serve`).
- Produces: `/` returns `text/html` = `sessions.html`; the page fetches `/api/sessions`, renders the flat list with client-side search + All/Live segment + project selector, rows linking to `/s/<slug>/`.

- [ ] **Step 1: Write the landing page**

Create `skills/_shared/web_companion/static/sessions.html` — a self-contained page (styles inline; fonts from `/static/fonts/…`, which the server already serves). Structure mirrors the approved mockup: header, search input, `All | Live` segment, a `<select>` project filter, and a `#list` container. A `<script>` fetches `/api/sessions`, stores rows, and re-renders on filter/search input. Each row: status dot + tag, `slug` (mono, accent, links to `/s/<slug>/`), `title` (dim), `project` tag, `comment_count` + relative `last_active`, `Open →`. Include an empty-state ("No workspaces yet"). Relative-time formatting from `last_active` (unix secs). Keep it dependency-free vanilla JS.

Key behaviors the JS must implement:
- `fetch('/api/sessions?scope=all').then(r=>r.json())` → `ALL = rows`. (The `?scope=all` signal is required — a bare `/api/sessions` with no cwd returns 400 by design.)
- Populate the project `<select>` from distinct `row.project`.
- `render()` filters `ALL` by: segment (`Live` → `status==='live'`), selected project (or all), and search text (matches slug/title/project, case-insensitive); sorts by `last_active` desc; injects row HTML.
- Row click / `Open →` → `location.href = '/s/' + encodeURIComponent(row.slug) + '/'`.

- [ ] **Step 2: Serve it from `/`**

Replace the `/` branch (`server.py:240-242`):

```python
            if self.path == "/":
                static_serve.serve(self, "sessions.html", static_dirs)
                return
```

Confirm `sessions.html` resolves through `static_dirs` (the web_companion `static/` dir is already registered — verify by checking how `/static/…` maps; if `static_serve.serve` expects a path relative to a static root, `"sessions.html"` at the web_companion static root is correct).

- [ ] **Step 3: Write the test**

```python
# skills/_shared/web_companion/tests/test_landing.py
from pathlib import Path


def test_landing_html_exists_and_references_api():
    p = Path("skills/_shared/web_companion/static/sessions.html")
    assert p.exists()
    html = p.read_text()
    assert "/api/sessions" in html
    assert "/s/" in html  # rows link to per-session route
```

- [ ] **Step 4: Manual browser smoke (controller runs live)**

Start the annotate server, create two sessions with slugs, open `http://localhost:<port>/`, confirm: both rows render with slug/title/project/status; search + Live filter + project select work; clicking a row opens `/s/<slug>/`. (Controller performs this with the running server + Playwright.)

Run: `python -m pytest skills/_shared/web_companion/tests/test_landing.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add skills/_shared/web_companion/static/sessions.html skills/_shared/web_companion/server.py skills/_shared/web_companion/tests/test_landing.py
git commit -m "feat(web_companion): session browser landing page at /"
```

---

### Task 6: Per-Claude-session heartbeats + attached count

**Files:**
- Modify: `skills/_shared/web_companion/watcher.sh` (write a per-claude-session heartbeat wherever it writes `watcher_heartbeat`)
- Modify: `skills/annotate/server.py` `serve_poll` (~line 386-441) to expose `attached`
- Modify: `skills/annotate/references/pushing.md` (the "Arming the watcher" env block) to pass `CLAUDE_SID`
- Test: `skills/_shared/web_companion/tests/test_attached_count.py` (create) — unit-test an extracted `attached_count(state_dir, now) -> int`.

**Interfaces:**
- Consumes: `state/watchers/<claude_sid>.hb` files.
- Produces: `attached_count(state_dir, now) -> int` = number of `state/watchers/*.hb` with mtime within `LIVE_WINDOW`; `serve_poll` payload gains `"attached": <n>`.

- [ ] **Step 1: Write the failing test**

```python
# skills/_shared/web_companion/tests/test_attached_count.py
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python -m pytest skills/_shared/web_companion/tests/test_attached_count.py -v`
Expected: FAIL — `ImportError: cannot import name 'attached_count'`.

- [ ] **Step 3: Implement**

In `skills/annotate/server.py`, add (near serve_poll) — reuse the shared `LIVE_WINDOW` constant if importable, else define `ATTACHED_WINDOW = 10`:

```python
def attached_count(state_dir, now):
    import os
    from pathlib import Path
    wdir = Path(state_dir) / "watchers"
    if not wdir.is_dir():
        return 0
    n = 0
    for f in wdir.glob("*.hb"):
        try:
            if now - int(os.path.getmtime(f)) < 10:
                n += 1
        except OSError:
            continue
    return n
```

In `serve_poll`, add to the response dict (alongside `busy`, ~line 441):

```python
            "attached": attached_count(state_dir, int(time.time())),
```

In `watcher.sh`, wherever it does `date +%s > "$STATE_DIR/watcher_heartbeat"` (lines ~15 and ~35), ALSO write the per-session heartbeat (guard on `CLAUDE_SID` being set):

```bash
  date +%s > "$STATE_DIR/watcher_heartbeat"
  if [ -n "${CLAUDE_SID:-}" ]; then
    mkdir -p "$STATE_DIR/watchers"
    date +%s > "$STATE_DIR/watchers/$CLAUDE_SID.hb"
  fi
```

In `pushing.md`'s watcher-arming env block, add `CLAUDE_SID="$CLAUDE_CODE_SESSION_ID"` to the exported env passed to `watcher.sh` (document it next to `SKILL`, `SID`, `STATE_DIR`, …).

- [ ] **Step 4: Run test + regression**

Run: `python -m pytest skills/_shared/web_companion/tests/test_attached_count.py -v`
Then: `python -m pytest skills/annotate/tests/ -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/server.py skills/_shared/web_companion/watcher.sh skills/annotate/references/pushing.md skills/_shared/web_companion/tests/test_attached_count.py
git commit -m "feat(web_companion): per-Claude-session heartbeats + attached count in poll"
```

---

### Task 7: "N sessions attached" indicator on the page

**Files:**
- Modify: `skills/annotate/static/script.js` (poll handler — read `attached` from the poll response)
- Modify: `skills/annotate/static/style.css` (a small pill style)
- Test: manual browser smoke (controller). No unit test — this is presentational and reads a field Task 6 already tests.

**Interfaces:**
- Consumes: `poll.attached` (Task 6).
- Produces: when `attached > 1`, show a pill "`N sessions attached`" in the page header/status area; hide when `<= 1`.

- [ ] **Step 1: Implement**

In `script.js`, find where the poll response is handled (search for `busy` or `watcher_age_s`). Add: locate/create a header status element `#attached-pill`; on each poll, if `data.attached > 1` set its text to `` `${data.attached} sessions attached` `` and show it, else hide it. Follow the file's existing DOM-update idiom (grep for how `busy`/status is surfaced today and mirror it).

In `style.css`, add:

```css
.attached-pill {
  display: none;
  font-size: 11px; font-weight: 600;
  color: #b45309;
  background: color-mix(in srgb, #b45309 12%, white);
  border: 1px solid color-mix(in srgb, #b45309 35%, white);
  border-radius: 999px; padding: 2px 10px; margin-left: 10px;
}
.attached-pill.show { display: inline-block; }
```

- [ ] **Step 2: Verify**

Run: `node --check skills/annotate/static/script.js`
Expected: no syntax error.
Controller runs the live two-session smoke: open the same workspace's `/s/<slug>/` while two watchers heartbeat; confirm the pill appears with "2 sessions attached" and disappears when one stops.

- [ ] **Step 3: Commit**

```bash
git add skills/annotate/static/script.js skills/annotate/static/style.css
git commit -m "feat(annotate): show advisory 'N sessions attached' pill"
```

---

### Task 8: Skill docs — reuse, resume, lifecycle

**Files:**
- Modify: `skills/annotate/references/pushing.md` — create-or-attach flow + per-conversation workspace marker
- Create: `skills/annotate/references/resuming.md` — `/annotate resume` contract
- Modify: `skills/annotate/SKILL.md` — a "Session lifecycle" section + the resume command in the command menu

**Interfaces:**
- Consumes: Task 3 API (`POST /api/sessions {title, project, slug, attach}` → `{sid, slug, created, url, …}`); Task 4 (`GET /api/sessions` for listing).
- Produces: authoring guidance so the skill reuses/resumes workspaces instead of always creating.

- [ ] **Step 1: Update `pushing.md`**

Change the session-create step. Read the current `curl -X POST /api/sessions` (line ~60-62) and the surrounding flow, then document:
- The **first** push of a conversation POSTs `{"cwd","title","project"}` (no `attach`), gets back `{sid, slug, url, created:true}`, and records `{"workspace":{"sid","slug"}}` into `~/.claude/annotate/pending-<CLAUDE_CODE_SESSION_ID>.json` (the file already tracks armed watchers — add this key).
- **Subsequent** pushes in the same conversation read that marker and POST `{"cwd","title","slug":<saved-slug>,"attach":true}` — reusing the same workspace (server returns the same sid; blocks.json in the same dir is updated).
- Announce the **slug URL** (`/s/<slug>/`), not the sid URL.
- Pass `title` (the work's short title) so the server can slugify it; optionally pass an explicit short `slug`.
- Show the concrete `curl` for both the create and the attach call.

- [ ] **Step 2: Create `resuming.md`**

Document `/annotate resume [<slug>]`:
- With `<slug>`: `GET /api/sessions` → find the row whose `slug` matches → set the conversation's workspace marker to that `{sid, slug}` → re-arm a watcher on its `state_dir` → announce its URL. Subsequent pushes attach to it.
- No arg: `GET /api/sessions?cwd=$PWD` → present the recent workspaces for this project (slug + title + last-active) as a short list, or point the user at the browser (`/`). 
- Unknown slug → tell the user it isn't a live workspace and point at `/annotate resume` / the browser; never crash.
- Note the auto-offer: when about to push in a cwd that already has a live workspace (via `GET /api/sessions?cwd`), offer resume-vs-new.

- [ ] **Step 3: Update `SKILL.md`**

Add a "Session lifecycle" section stating the real model (nohup singleton shared across Claude sessions, survives Claude exit, 24h idle-shutdown, 7-day retention, workspaces at `<cwd>/.claude/annotate/<slug-or-sid>/`, reopen via `/` browser or `/annotate resume <slug>`). Add `resume` to the command/trigger menu, mirroring the phrasing of existing entries. Grep first: `grep -n "resume\|lifecycle\|singleton\|pushing.md" skills/annotate/SKILL.md`.

- [ ] **Step 4: Verify accuracy**

Cross-check every documented field/endpoint against the implemented Task 3/4 API (`slug`, `attach`, `created`, the slug URL, `GET /api/sessions` shapes). Confirm the marker file key doesn't collide with existing keys in `pending-*.json` (read `hooks/progress_publish.py` / `handling-events.md` for the current schema).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/references/pushing.md skills/annotate/references/resuming.md skills/annotate/SKILL.md
git commit -m "docs(annotate): create-or-attach reuse, /annotate resume, session lifecycle"
```

---

### Task 9: End-to-end round-trip test

**Files:**
- Create: `skills/_shared/web_companion/tests/test_e2e_workspace_reuse.py`

**Interfaces:**
- Consumes: `create_or_attach`, `list_rows`, `Registry.resolve` (Tasks 1,3,4).
- Produces: a regression test of the whole identity lifecycle without HTTP — create a workspace, attach to it (same sid), resolve its slug, and confirm it appears in the all-sessions list with its slug/title.

- [ ] **Step 1: Write the test**

```python
# skills/_shared/web_companion/tests/test_e2e_workspace_reuse.py
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
    rows = list_rows(r, "", now=10_000)
    hit = [x for x in rows if x["slug"] == "persistent-workspaces"]
    assert hit and hit[0]["title"] == "Persistent Workspaces" and hit[0]["project"] == "petros-skills"
```

- [ ] **Step 2: Run it + full regression**

Run: `python -m pytest skills/_shared/web_companion/tests/test_e2e_workspace_reuse.py -v`
Then the full shared + annotate suites: `python -m pytest skills/_shared/web_companion/tests/ skills/annotate/tests/ -q`
Expected: PASS, no regression (interactive_review legacy shape guarded by Task 4's test).

- [ ] **Step 3: Commit**

```bash
git add skills/_shared/web_companion/tests/test_e2e_workspace_reuse.py
git commit -m "test(web_companion): e2e workspace create/attach/resolve/list round-trip"
```

---

## Self-Review

**Spec coverage:**
- Persistent named workspace / slug alias → Tasks 1 (registry), 2 (routing), 3 (create-or-attach). ✓
- Pushes reuse workspace (conversation marker) → Task 3 API + Task 8 docs. ✓
- `/annotate resume` → Task 8 (`resuming.md`) built on Task 4's list. ✓
- Browser at `/` (all-projects flat, search/Live/project filter) → Task 5 + Task 4's extended list. ✓
- Advisory "N sessions attached" → Task 6 (count) + Task 7 (pill). ✓
- Lifecycle docs → Task 8 (SKILL.md). ✓
- Backward compat (interactive_review `GET /api/sessions?cwd=` + `/s/<sid>/`) → Task 2 keeps sid routing; Task 4 `list_rows` legacy branch + its regression test. ✓
- Rehydrate `is_dir()` trap avoided → Task 1 parallel `sessions_meta.json`. ✓
- Self-heal attach when dir GC'd → Task 3 (`test_attach_self_heals_when_dir_gone`). ✓

**Placeholder scan:** No TBD/TODO. Each code step carries full code; the two presentational tasks (5 page JS, 7 pill) specify exact behaviors + the CSS, and name the grep to find the host idiom (unavoidable for "match the existing file" edits). Commands have expected output.

**Type consistency:** `create_or_attach(registry, skill_name, payload, cwd, mkdirs) -> (dict, bool)` consistent Tasks 3,9. `list_rows(registry, cwd, now)` / `session_row(...)` consistent Tasks 4,9. `Registry.resolve/make_slug/register_meta/get_meta/find_by_slug/list_all` consistent Tasks 1,2,3,4. `attached_count(state_dir, now)` consistent Task 6. Row shapes: legacy `{sid,pr_ref,title,state_dir}` vs extended (adds slug/project/last_active/comment_count/status) — asserted in Task 4 tests.

**Notes for the executor:**
- Task 1 Step 1: confirm the web_companion test directory path first (`git ls-files | grep -i web_companion | grep -i test`); put all new tests there.
- Task 3: verify annotate's `handlers.create_session_extra` is safe to call on an attach (existing dir). If not, guard it behind `_created`.
- Task 4: the legacy path MUST keep reading `state_dir/meta.json` for `pr_ref`/`title` (not the registry meta) so interactive_review is byte-identical.
- Task 5 & 7: read the real static-serve mapping and the real poll/DOM idiom before editing; the plan names the grep.
- Task 6: confirm `CLAUDE_CODE_SESSION_ID` is available where the watcher is armed (pushing.md arming block).
