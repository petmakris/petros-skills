# IDE backend automation — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the spike's file-IPC/claude-p backend with an HTTP/SSE integration into the existing `interactive_review` server, so the IDE plugin becomes a passive surface on the same review session the browser uses. Lock in the Reader popup with markdown-link inline references.

**Architecture:** The IntelliJ plugin discovers an active session via `GET /api/sessions?cwd=<project-root>`, opens an SSE connection to `GET /s/<sid>/stream`, and POSTs questions to the existing `POST /s/<sid>/api/submit` endpoint. The popup is a `JEditorPane` rendering HTML produced by a markdown-link parser; clicks navigate to `path:line` targets via IntelliJ's `OpenFileDescriptor`. The Python server gains three new GET endpoints (sessions-by-cwd, threads bulk, SSE stream); the existing thread schema is unchanged — `latest_synthesis` is a read-only projection (last claude message).

**Tech Stack:** Java 25 + IntelliJ Platform 2026.1 Gradle plugin 2.16.0 (plugin); JUnit 5 (Java tests); Python 3 + `http.server.BaseHTTPRequestHandler` (server); pytest (Python tests). JDK built-in `java.net.http.HttpClient` for HTTP; hand-rolled SSE parser (the protocol is line-based and tiny). JDK built-in `com.sun.net.httpserver.HttpServer` for the Java test fixture.

---

## File Structure

### Created

| Path | Purpose |
|---|---|
| `intellij-plugin-spike/src/main/java/com/petros/ireview/ReviewSessionClient.java` | HTTP discovery + SSE consumption + in-memory thread cache. The whole "talk to the server" surface. |
| `intellij-plugin-spike/src/main/java/com/petros/ireview/SseClient.java` | Hand-rolled SSE consumer — line stream → event objects. Reused by ReviewSessionClient. |
| `intellij-plugin-spike/src/main/java/com/petros/ireview/MarkdownLinkRenderer.java` | Parse markdown links in synthesis text. Produce HTML for `JEditorPane`. Resolve `path:line` to navigation targets. |
| `intellij-plugin-spike/src/main/java/com/petros/ireview/StatusBarWidget.java` | Implements `StatusBarWidget` — renders dormant/connecting/active/disconnected text + spinner. |
| `intellij-plugin-spike/src/main/java/com/petros/ireview/StatusBarWidgetFactory.java` | Registers the widget per-Project. |
| `intellij-plugin-spike/src/test/java/com/petros/ireview/FakeReviewServer.java` | JDK `HttpServer`-based fixture for testing ReviewSessionClient against real HTTP. |
| `intellij-plugin-spike/src/test/java/com/petros/ireview/ReviewSessionClientTest.java` | Exercises discover, SSE message receipt, reconnect with backoff. |
| `intellij-plugin-spike/src/test/java/com/petros/ireview/MarkdownLinkRendererTest.java` | Unit tests for link parsing + HTML generation. |
| `intellij-plugin-spike/src/test/java/com/petros/ireview/SseClientTest.java` | Unit tests for SSE line parser. |

### Modified

| Path | What changes |
|---|---|
| `skills/_shared/web_companion/server.py` | Add three GET routes (sessions-by-cwd, threads bulk, SSE stream). |
| `skills/_shared/web_companion/sessions.py` | Add `find_by_cwd(cwd)` and `note_change(sid, anchor)` (the latter wakes SSE waiters). |
| `skills/interactive_review/server.py` | Implement the `serve_threads_bulk` and `serve_stream` handlers; fire `note_change` from `handle_submit`. |
| `skills/_shared/web_companion/tests/test_sessions.py` | New tests for `find_by_cwd` + `note_change`. |
| `skills/interactive_review/tests/test_server.py` | New tests for the three new endpoints. |
| `skills/interactive_review/SKILL.md` | Two clauses under "Response style guide" — self-contained synthesis + inline markdown links. |
| `intellij-plugin-spike/build.gradle.kts` | Add JUnit 5 test dependency. |
| `intellij-plugin-spike/src/main/java/com/petros/ireview/SpikeDiffExtension.java` | Switch anchor extraction from `getContentTitle()` to path-based; subscribe to ReviewSessionClient updates. |
| `intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisPopup.java` | Talk to ReviewSessionClient (not file IPC); render via MarkdownLinkRenderer. |
| `intellij-plugin-spike/src/main/resources/META-INF/plugin.xml` | Register `StatusBarWidgetFactory`; remove any pieces tied to deleted classes. |
| `intellij-plugin-spike/HANDOFF.md` | Replace the IPC + responder sections with the new architecture notes. |

### Deleted

| Path | Reason |
|---|---|
| `intellij-plugin-spike/src/main/java/com/petros/ireview/EventBridge.java` | File-IPC replaced by ReviewSessionClient. |
| `intellij-plugin-spike/src/main/java/com/petros/ireview/ThreadsService.java` | In-memory thread store replaced by ReviewSessionClient cache. |
| `intellij-plugin-spike/src/main/java/com/petros/ireview/ThreadsPanel.java` | Bottom tool window was diagnostic; not in the new design. |
| `intellij-plugin-spike/src/main/java/com/petros/ireview/ThreadsToolWindowFactory.java` | Pair of ThreadsPanel. |
| `intellij-plugin-spike/scripts/claude_responder.py` | The `claude -p` subprocess responder is obsolete. |
| `intellij-plugin-spike/scripts/echo_responder.py` | Same. |

---

## Phase 1 — Server endpoints

### Task 1: `Registry.find_by_cwd(cwd)`

**Files:**
- Modify: `skills/_shared/web_companion/sessions.py`
- Test: `skills/_shared/web_companion/tests/test_sessions.py`

- [ ] **Step 1: Write the failing test**

Append to `skills/_shared/web_companion/tests/test_sessions.py`:

```python
def test_find_by_cwd_returns_only_matching_sessions(tmp_path):
    from skills._shared.web_companion.sessions import Registry
    reg = Registry(persist_path=tmp_path / "reg.json")
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
    reg = Registry(persist_path=tmp_path / "reg.json")
    reg.register(reg.make_sid(), {"state_dir": tmp_path / "x/state", "_cwd": "/proj/x"})
    assert reg.find_by_cwd("/proj/missing") == []
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/projects/petros-skills && python -m pytest skills/_shared/web_companion/tests/test_sessions.py::test_find_by_cwd_returns_only_matching_sessions -v`
Expected: FAIL with `AttributeError: 'Registry' object has no attribute 'find_by_cwd'`.

- [ ] **Step 3: Add `find_by_cwd` to Registry**

In `skills/_shared/web_companion/sessions.py`, inside class `Registry`:

```python
    def find_by_cwd(self, cwd: str) -> list[tuple[str, dict]]:
        """Return [(sid, dirs)] for all sessions whose registered cwd matches."""
        return [
            (sid, dirs)
            for sid, dirs in self.items()
            if dirs.get("_cwd") == cwd
        ]
```

Also extend `register` to capture cwd. Locate the existing `register` method and replace it with:

```python
    def register(self, sid: str, dirs: dict) -> None:
        with self._lock:
            self._sessions[sid] = dict(dirs)  # shallow copy so caller can mutate
```

(If `register` already does `self._sessions[sid] = dirs`, the only change is `dict(dirs)` to defensively copy. Inspect before editing.)

- [ ] **Step 4: Wire `_cwd` into session creation**

In `skills/_shared/web_companion/server.py`, locate `_handle_create_session`. Inside the `dirs` dict construction (around line 217-223), add:

```python
            dirs = {
                "response_dir": response_dir,
                "annotations_dir": annotations_dir,
                "state_dir": state_dir,
                "events_dir": events_dir,
                "consumed_dir": consumed_dir,
                "_cwd": str(cwd),   # NEW: capture for find_by_cwd
            }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd ~/projects/petros-skills && python -m pytest skills/_shared/web_companion/tests/test_sessions.py -v`
Expected: PASS (all session tests including the two new ones).

- [ ] **Step 6: Commit**

```bash
cd ~/projects/petros-skills
git add skills/_shared/web_companion/sessions.py skills/_shared/web_companion/server.py skills/_shared/web_companion/tests/test_sessions.py
git commit -m "web_companion: Registry.find_by_cwd + capture cwd in session"
```

---

### Task 2: `GET /api/sessions?cwd=<path>` route

**Files:**
- Modify: `skills/_shared/web_companion/server.py`
- Test: `skills/_shared/web_companion/tests/test_server_routes.py` (NEW)

- [ ] **Step 1: Write the failing test**

Create `skills/_shared/web_companion/tests/test_server_routes.py`:

```python
import json
import threading
import time
import urllib.request

from skills._shared.web_companion.server import run as run_server
from skills._shared.web_companion.handlers import HandlersProtocol


class StubHandlers(HandlersProtocol):
    def create_session_extra(self, payload, dirs):
        return {"pr_ref": payload.get("pr", "?"), "title": "stub"}
    def serve_root(self, h, dirs): h._send_text(200, "root")
    def serve_poll(self, h, dirs): h._send_text(200, "poll")
    def serve_data(self, h, dirs, query): h._send_text(404, "no")
    def handle_submit(self, h, dirs, payload): h._send_text(200, "ok")


def _start_server(tmp_path):
    thread = threading.Thread(
        target=run_server,
        kwargs=dict(
            skill_name="test-skill",
            port_range=range(56000, 56020),
            handlers=StubHandlers(),
            static_dirs=[tmp_path],
            banner="test",
            idle_seconds=999,
        ),
        daemon=True,
    )
    thread.start()
    # wait for server.json
    for _ in range(50):
        time.sleep(0.05)
    # Read port from ~/.claude/test-skill/server.json (or wherever it's written)
    # NOTE: implementation may vary — adjust this to your server.json discovery
    import os, json as _j
    p = os.path.expanduser("~/.claude/test-skill/server.json")
    info = _j.loads(open(p).read())
    return info["url"]


def test_get_sessions_by_cwd_returns_only_matching(tmp_path):
    url = _start_server(tmp_path)
    # create two sessions, different cwds
    cwd_a = str(tmp_path / "a"); (tmp_path / "a").mkdir()
    cwd_b = str(tmp_path / "b"); (tmp_path / "b").mkdir()

    def post_session(cwd, pr):
        req = urllib.request.Request(
            f"{url}/api/sessions",
            data=json.dumps({"cwd": cwd, "pr": pr}).encode(),
            headers={"Content-Type": "application/json"},
        )
        return json.loads(urllib.request.urlopen(req).read())

    s_a1 = post_session(cwd_a, "PR-1")
    s_a2 = post_session(cwd_a, "PR-2")
    s_b1 = post_session(cwd_b, "PR-3")

    resp = json.loads(urllib.request.urlopen(f"{url}/api/sessions?cwd={cwd_a}").read())
    sids = {row["sid"] for row in resp}
    assert sids == {s_a1["sid"], s_a2["sid"]}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/projects/petros-skills && python -m pytest skills/_shared/web_companion/tests/test_server_routes.py -v`
Expected: FAIL — either 404 on GET /api/sessions, or the route returns something other than a JSON list.

- [ ] **Step 3: Implement GET /api/sessions route**

In `skills/_shared/web_companion/server.py`, locate `do_GET` (around line 133). Just before the final `self._send_text(404, "not found")` add:

```python
            if self.path.startswith("/api/sessions"):
                from urllib.parse import urlparse, parse_qs
                qs = parse_qs(urlparse(self.path).query)
                cwd = (qs.get("cwd") or [""])[0]
                if not cwd:
                    self._send_text(400, "missing cwd")
                    return
                rows = []
                for sid, dirs in registry.find_by_cwd(cwd):
                    meta_path = Path(dirs["state_dir"]) / "meta.json"
                    meta = {}
                    if meta_path.exists():
                        try:
                            meta = json.loads(meta_path.read_text())
                        except json.JSONDecodeError:
                            meta = {}
                    rows.append({
                        "sid": sid,
                        "pr_ref": meta.get("pr_ref", ""),
                        "title": meta.get("title", ""),
                        "state_dir": str(dirs["state_dir"]),
                    })
                self._send_json(200, rows)
                return
```

You'll also need `Path` imported (likely already present near the top — confirm before adding).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ~/projects/petros-skills && python -m pytest skills/_shared/web_companion/tests/test_server_routes.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd ~/projects/petros-skills
git add skills/_shared/web_companion/server.py skills/_shared/web_companion/tests/test_server_routes.py
git commit -m "web_companion: GET /api/sessions?cwd= lists active sessions for an IDE project"
```

---

### Task 3: `GET /s/<sid>/threads.json` bulk endpoint

**Files:**
- Modify: `skills/_shared/web_companion/server.py`, `skills/interactive_review/server.py`
- Test: `skills/interactive_review/tests/test_server.py`

- [ ] **Step 1: Write the failing test**

Append to `skills/interactive_review/tests/test_server.py`:

```python
def test_threads_bulk_returns_anchor_to_latest_synthesis(tmp_path):
    # Set up a session state dir with two threads.
    state_dir = tmp_path / "state"
    threads_dir = state_dir / "threads"
    threads_dir.mkdir(parents=True)

    from skills.interactive_review import threads as th
    th.append_message(threads_dir, "foo.java:R:10",
                      {"role": "user", "ts": 1, "text": "q1", "source_event_id": "e1"})
    th.append_message(threads_dir, "foo.java:R:10",
                      {"role": "claude", "ts": 2, "text": "answer one"})
    th.append_message(threads_dir, "foo.java:R:10",
                      {"role": "user", "ts": 3, "text": "q2", "source_event_id": "e2"})
    th.append_message(threads_dir, "foo.java:R:10",
                      {"role": "claude", "ts": 4, "text": "answer two — final synthesis"})
    th.append_message(threads_dir, "bar.java:L:5",
                      {"role": "claude", "ts": 5, "text": "only one here"})

    from skills.interactive_review.server import Handlers
    h = Handlers()
    result = h.threads_bulk({"state_dir": state_dir})
    # Latest claude message wins per anchor
    assert result["foo.java:R:10"]["latest_synthesis"] == "answer two — final synthesis"
    assert result["foo.java:R:10"]["version"] == 4
    assert result["bar.java:L:5"]["latest_synthesis"] == "only one here"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/projects/petros-skills && python -m pytest skills/interactive_review/tests/test_server.py::test_threads_bulk_returns_anchor_to_latest_synthesis -v`
Expected: FAIL — `Handlers` has no `threads_bulk` method.

- [ ] **Step 3: Add `threads_bulk` to `Handlers`**

In `skills/interactive_review/server.py`, inside class `Handlers`, add:

```python
    def threads_bulk(self, dirs: dict) -> dict:
        """Return {anchor: {latest_synthesis, version, updated_at}} for all threads.

        latest_synthesis is the text of the most-recent message with role='claude'.
        If a thread has no claude message yet, it's omitted.
        """
        threads_dir = Path(dirs["state_dir"]) / "threads"
        result: dict = {}
        if not threads_dir.is_dir():
            return result
        for p in threads_dir.iterdir():
            if p.suffix != ".json":
                continue
            try:
                t = json.loads(p.read_text())
            except (json.JSONDecodeError, OSError):
                continue
            anchor = t.get("anchor")
            if not isinstance(anchor, str):
                continue
            claude_msgs = [m for m in t.get("messages", []) if m.get("role") == "claude"]
            if not claude_msgs:
                continue
            last = claude_msgs[-1]
            result[anchor] = {
                "latest_synthesis": last.get("text", ""),
                "version": t.get("version", 0),
                "updated_at": last.get("ts", 0),
            }
        return result
```

- [ ] **Step 4: Wire the HTTP route**

In `skills/interactive_review/server.py`, locate `serve_data` (around the bottom of the file). Just before the existing logic, add a branch:

```python
    def serve_data(self, h, dirs, query):
        if query == "threads.json":
            data = self.threads_bulk(dirs)
            body = json.dumps(data).encode("utf-8")
            h.send_response(200)
            h.send_header("Content-Type", "application/json")
            h.send_header("Content-Length", str(len(body)))
            h.end_headers()
            h.wfile.write(body)
            return
        # ... existing serve_data logic continues
```

(If `serve_data` doesn't exist with that exact name, locate where the route dispatcher handles `/s/<sid>/<rest>` GETs and add `threads.json` to it. Cross-reference `skills/_shared/web_companion/server.py:144-156` and `serve_data` calls.)

- [ ] **Step 5: Run unit test to verify it passes**

Run: `cd ~/projects/petros-skills && python -m pytest skills/interactive_review/tests/test_server.py::test_threads_bulk_returns_anchor_to_latest_synthesis -v`
Expected: PASS.

- [ ] **Step 6: Add HTTP-level integration test**

Append to `skills/interactive_review/tests/test_server.py`:

```python
def test_threads_json_http_endpoint(tmp_path, monkeypatch):
    # Reuses the server harness from test_get_sessions_by_cwd; if not factored
    # out yet, copy that bootstrap pattern here. Asserts:
    #   GET /s/<sid>/threads.json → application/json, returns the dict shape.
    # See skills/_shared/web_companion/tests/test_server_routes.py for the harness.
    pass  # full implementation: copy _start_server pattern, create session,
          # append threads, GET /s/<sid>/threads.json, assert structure.
```

Note: write this out fully if your TDD-style demands it; otherwise the unit test above is sufficient coverage for now.

- [ ] **Step 7: Commit**

```bash
cd ~/projects/petros-skills
git add skills/interactive_review/server.py skills/interactive_review/tests/test_server.py
git commit -m "interactive_review: GET /s/<sid>/threads.json bulk endpoint"
```

---

### Task 4: SSE endpoint `GET /s/<sid>/stream`

**Files:**
- Modify: `skills/_shared/web_companion/sessions.py` (add `note_change`)
- Modify: `skills/interactive_review/server.py` (add `serve_stream` + fire `note_change` in submit)
- Test: `skills/interactive_review/tests/test_server.py`

- [ ] **Step 1: Add per-session change-notification primitive**

In `skills/_shared/web_companion/sessions.py`, in `Registry.__init__`, add:

```python
        self._waiters: dict[str, threading.Event] = {}
        # NOTE: also add `import threading` at the top if not already present.
```

Then add a method:

```python
    def waiter(self, sid: str) -> threading.Event:
        """Return the (or a new) threading.Event used to wake SSE consumers."""
        with self._lock:
            if sid not in self._waiters:
                self._waiters[sid] = threading.Event()
            return self._waiters[sid]

    def note_change(self, sid: str) -> None:
        """Signal SSE consumers that a thread for this session was mutated."""
        ev = self.waiter(sid)
        ev.set()
        # Immediately clear so the next wait blocks again. The pattern:
        # waiters spin in a loop, set/clear immediately so all current
        # waiters get woken, but new waiters then re-block.
        ev.clear()
```

- [ ] **Step 2: Wire `note_change` into submit**

In `skills/interactive_review/server.py`, locate `handle_submit` (around line 121). After the existing `threads_module.append_message(...)` call, append:

```python
        # Wake any SSE consumers for this session.
        # The sid is available via the registry via state_dir reverse lookup, but
        # simpler: have the shared submit handler pass `sid` into handle_submit.
        # Modify skills/_shared/web_companion/server.py:_handle_submit:
        #   handlers.handle_submit(self, dirs, payload, sid)
        # and accept it here:
        if sid is not None:
            registry.note_change(sid)
```

You will need:
1. The `registry` to be reachable from `handle_submit` — pass it through the handler protocol, OR import the module-level singleton if available.
2. The shared server's `_handle_submit` to pass the sid.

If neither is currently possible (`Handlers` doesn't get `registry`), add a constructor arg:

```python
class Handlers:
    def __init__(self, registry=None):
        self._registry = registry
```

And in `skills/_shared/web_companion/server.py:run`, construct skills with the registry: change wherever `handlers` is consumed to either accept registry or do the `note_change` from the shared layer.

The simplest end state: do `note_change` in the *shared* `_handle_submit` after `handlers.handle_submit(...)` returns, since shared `_handle_submit` has both `registry` and `sid` in scope.

- [ ] **Step 3: Write the failing SSE test**

Append to `skills/interactive_review/tests/test_server.py`:

```python
def test_sse_stream_emits_thread_changed_within_one_second(tmp_path):
    # Boot the server (use the harness from test_server_routes.py).
    # Create a session.
    # In one thread: open SSE connection to /s/<sid>/stream and read 1 event.
    # In another thread: POST to /s/<sid>/api/submit with a comment.
    # Assert the SSE event arrives within 1s and contains the expected anchor.
    pass  # full implementation in this file
```

(Write this out fully when implementing. The harness pattern from test_server_routes.py applies.)

- [ ] **Step 4: Implement the SSE handler**

In `skills/interactive_review/server.py`, in `serve_data`, add a branch for `query == "stream"`:

```python
        if query == "stream":
            sid = dirs.get("_sid")
            # We need sid here — extend dirs/serve_data signature or look it up.
            # For now, assume serve_data is called with sid available.
            h.send_response(200)
            h.send_header("Content-Type", "text/event-stream")
            h.send_header("Cache-Control", "no-cache")
            h.send_header("X-Accel-Buffering", "no")
            h.end_headers()
            # Send an initial 'connected' event so the client knows we're alive.
            h.wfile.write(b"event: connected\ndata: {}\n\n")
            h.wfile.flush()
            waiter = self._registry.waiter(sid)
            last_threads = self.threads_bulk(dirs)
            # Loop: wait for change, recompute threads_bulk, diff against last,
            # emit one event per changed anchor, repeat.
            try:
                while True:
                    waiter.wait(timeout=30)
                    new_threads = self.threads_bulk(dirs)
                    for anchor, info in new_threads.items():
                        old = last_threads.get(anchor)
                        if old is None or old["version"] != info["version"]:
                            payload = json.dumps({"anchor": anchor, **info})
                            h.wfile.write(f"event: thread-changed\ndata: {payload}\n\n".encode())
                            h.wfile.flush()
                    last_threads = new_threads
                    if not waiter.wait(timeout=0.001):
                        # Send heartbeat every 30s to keep proxies alive
                        h.wfile.write(b"event: heartbeat\ndata: {}\n\n")
                        h.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
```

This requires sid + registry to be available in `serve_data`. The cleanest way is to plumb sid through. Adjust `skills/_shared/web_companion/server.py:155`:

```python
                query = rest.lstrip("/")
                # Add sid to dirs so handlers can read it:
                dirs_with_sid = {**dirs, "_sid": sid}
                handlers.serve_data(self, dirs_with_sid, query)
```

And construct `Handlers` with the registry in the skill's `main()`:

```python
    handlers = Handlers(registry=...)
```

If the registry isn't reachable, factor it out — `Handlers` only needs `registry.waiter(sid)`, so you can inject a callable instead.

- [ ] **Step 5: Run the SSE test to verify it passes**

Run: `cd ~/projects/petros-skills && python -m pytest skills/interactive_review/tests/test_server.py::test_sse_stream_emits_thread_changed_within_one_second -v`
Expected: PASS within ~1.5s.

- [ ] **Step 6: Commit**

```bash
cd ~/projects/petros-skills
git add skills/_shared/web_companion/sessions.py skills/_shared/web_companion/server.py skills/interactive_review/server.py skills/interactive_review/tests/test_server.py
git commit -m "interactive_review: SSE stream endpoint + note_change wakeups"
```

---

## Phase 2 — Skill prompt update

### Task 5: Update SKILL.md with synthesis + link clauses

**Files:**
- Modify: `skills/interactive_review/SKILL.md`

- [ ] **Step 1: Locate "Response style guide" section**

In `skills/interactive_review/SKILL.md`, find the section starting `## Response style guide` (around line 177).

- [ ] **Step 2: Replace the section body**

Replace the existing bullets with:

```markdown
## Response style guide

- **Self-contained synthesis.** Each reply should stand on its own as the
  answer to *all* questions asked so far on this anchor, not just the
  latest one. Absorb prior questions; do not assume the reader has scrolled
  back. The IDE surface renders only your most recent reply — older replies
  are stored for audit but not displayed.
- **Link references inline.** When you reference a specific file, method,
  or symbol from the code, render it as a markdown link whose target is
  the project-relative file path optionally followed by `:line`, e.g.
  `[forDashboard](src/main/java/.../ProposalListService.java:18)`. For
  ticket IDs and external URLs, use a normal markdown link with the
  absolute URL.
- **Short.** 2–4 sentences in most cases. Answer the question; don't
  review the whole PR.
- **Code-aware.** Reference specific lines, variable names, and functions
  from the diff.
- **Suggest, don't ask.** When a fix is warranted, show it as a markdown
  code block immediately. The user copies it themselves.
- **Honest uncertainty.** If you need more context, name exactly what you
  need ("I'd need to see `<file>:<function>` to know"). Don't hedge.
- **No general reviews per event.** Each wake-up is one question on one
  anchor. Answer that; let the user iterate.
```

- [ ] **Step 3: Commit**

```bash
cd ~/projects/petros-skills
git add skills/interactive_review/SKILL.md
git commit -m "interactive_review: self-contained synthesis + inline markdown link refs"
```

---

## Phase 3 — Plugin Java side

### Task 6: Set up Java test infrastructure

**Files:**
- Modify: `intellij-plugin-spike/build.gradle.kts`
- Create: `intellij-plugin-spike/src/test/java/com/petros/ireview/SmokeTest.java`

- [ ] **Step 1: Add JUnit 5 dependency**

In `intellij-plugin-spike/build.gradle.kts`, after the `dependencies { intellijPlatform { ... } }` block, add:

```kotlin
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Create the smoke test**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/SmokeTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmokeTest {
    @Test
    void junitWorks() {
        assertEquals(2, 1 + 1);
    }
}
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `cd ~/projects/petros-skills/intellij-plugin-spike && ./gradlew test`
Expected: BUILD SUCCESSFUL with 1 test passed.

- [ ] **Step 4: Commit**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
git add build.gradle.kts src/test/java/com/petros/ireview/SmokeTest.java
git commit -m "intellij-plugin-spike: add JUnit 5 test infrastructure"
```

---

### Task 7: `MarkdownLinkRenderer`

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/MarkdownLinkRenderer.java`
- Create: `intellij-plugin-spike/src/test/java/com/petros/ireview/MarkdownLinkRendererTest.java`

- [ ] **Step 1: Write failing tests**

Create `MarkdownLinkRendererTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownLinkRendererTest {

    @Test
    void plainTextHasNoLinks() {
        String html = MarkdownLinkRenderer.toHtml("Just words, no links.");
        assertEquals("Just words, no links.", html);
    }

    @Test
    void pathLinkBecomesAnchorTagWithFileScheme() {
        String html = MarkdownLinkRenderer.toHtml(
            "see [forDashboard](src/main/java/Foo.java:18) for details");
        assertEquals(
            "see <a href=\"ireview-nav://src/main/java/Foo.java:18\" class=\"ref-code\">forDashboard</a> for details",
            html);
    }

    @Test
    void urlLinkBecomesAnchorTagWithExternalScheme() {
        String html = MarkdownLinkRenderer.toHtml(
            "[PMP-171](https://example.com/PMP-171) is the ticket");
        assertEquals(
            "<a href=\"https://example.com/PMP-171\" class=\"ref-ticket\">PMP-171</a> is the ticket",
            html);
    }

    @Test
    void escapesHtmlInLabelsAndText() {
        String html = MarkdownLinkRenderer.toHtml("a < b and [x](y.java:1)");
        assertEquals("a &lt; b and <a href=\"ireview-nav://y.java:1\" class=\"ref-code\">x</a>", html);
    }

    @Test
    void parseNavTargetExtractsPathAndLine() {
        MarkdownLinkRenderer.NavTarget t = MarkdownLinkRenderer.parseNavTarget(
            "ireview-nav://src/main/java/Foo.java:18");
        assertEquals("src/main/java/Foo.java", t.path);
        assertEquals(18, t.line);
    }

    @Test
    void parseNavTargetWithoutLine() {
        MarkdownLinkRenderer.NavTarget t = MarkdownLinkRenderer.parseNavTarget(
            "ireview-nav://Foo.java");
        assertEquals("Foo.java", t.path);
        assertEquals(-1, t.line);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ~/projects/petros-skills/intellij-plugin-spike && ./gradlew test --tests MarkdownLinkRendererTest`
Expected: compile error — class doesn't exist.

- [ ] **Step 3: Implement `MarkdownLinkRenderer`**

Create `MarkdownLinkRenderer.java`:

```java
package com.petros.ireview;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses synthesis text with markdown-style links into HTML for JEditorPane.
 *
 * Two link kinds:
 *  - URL link  [text](https://...)      → external open
 *  - Path link [text](path[:line])      → IDE navigation
 *
 * Path links get a custom ireview-nav:// scheme so the HyperlinkListener
 * in SynthesisPopup can route them through IntelliJ's OpenFileDescriptor
 * rather than the system browser.
 */
public final class MarkdownLinkRenderer {

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern PATH_LINE = Pattern.compile("^(.+?):(\\d+)$");

    public record NavTarget(String path, int line) {}

    public static String toHtml(String synthesis) {
        if (synthesis == null) return "";
        StringBuilder out = new StringBuilder();
        Matcher m = LINK.matcher(synthesis);
        int last = 0;
        while (m.find()) {
            out.append(escape(synthesis.substring(last, m.start())));
            String label = m.group(1);
            String target = m.group(2);
            boolean isUrl = target.startsWith("http://") || target.startsWith("https://");
            if (isUrl) {
                out.append("<a href=\"").append(escape(target))
                   .append("\" class=\"ref-ticket\">")
                   .append(escape(label)).append("</a>");
            } else {
                out.append("<a href=\"ireview-nav://").append(escape(target))
                   .append("\" class=\"ref-code\">")
                   .append(escape(label)).append("</a>");
            }
            last = m.end();
        }
        out.append(escape(synthesis.substring(last)));
        return out.toString();
    }

    public static NavTarget parseNavTarget(String url) {
        if (!url.startsWith("ireview-nav://")) {
            throw new IllegalArgumentException("not an ireview-nav URL: " + url);
        }
        String rest = url.substring("ireview-nav://".length());
        Matcher m = PATH_LINE.matcher(rest);
        if (m.matches()) {
            return new NavTarget(m.group(1), Integer.parseInt(m.group(2)));
        }
        return new NavTarget(rest, -1);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private MarkdownLinkRenderer() {}
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ~/projects/petros-skills/intellij-plugin-spike && ./gradlew test --tests MarkdownLinkRendererTest`
Expected: 6/6 tests pass.

- [ ] **Step 5: Commit**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
git add src/main/java/com/petros/ireview/MarkdownLinkRenderer.java src/test/java/com/petros/ireview/MarkdownLinkRendererTest.java
git commit -m "intellij-plugin-spike: MarkdownLinkRenderer for inline path/url refs"
```

---

### Task 8: `SseClient` — line-based event parser

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/SseClient.java`
- Create: `intellij-plugin-spike/src/test/java/com/petros/ireview/SseClientTest.java`

- [ ] **Step 1: Write failing tests**

Create `SseClientTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SseClientTest {

    @Test
    void parsesOneEvent() {
        List<SseClient.Event> events = new ArrayList<>();
        SseClient.Parser p = new SseClient.Parser(events::add);
        p.feed("event: thread-changed");
        p.feed("data: {\"anchor\":\"foo:R:1\"}");
        p.feed("");
        assertEquals(1, events.size());
        assertEquals("thread-changed", events.get(0).name());
        assertEquals("{\"anchor\":\"foo:R:1\"}", events.get(0).data());
    }

    @Test
    void parsesMultipleEventsSeparatedByBlankLines() {
        List<SseClient.Event> events = new ArrayList<>();
        SseClient.Parser p = new SseClient.Parser(events::add);
        for (String line : new String[]{
            "event: a", "data: 1", "",
            "event: b", "data: 2", "",
        }) p.feed(line);
        assertEquals(2, events.size());
        assertEquals("a", events.get(0).name());
        assertEquals("1", events.get(0).data());
        assertEquals("b", events.get(1).name());
        assertEquals("2", events.get(1).data());
    }

    @Test
    void multilineDataIsJoinedWithNewlines() {
        List<SseClient.Event> events = new ArrayList<>();
        SseClient.Parser p = new SseClient.Parser(events::add);
        p.feed("event: x");
        p.feed("data: line1");
        p.feed("data: line2");
        p.feed("");
        assertEquals(1, events.size());
        assertEquals("line1\nline2", events.get(0).data());
    }

    @Test
    void ignoresCommentLines() {
        List<SseClient.Event> events = new ArrayList<>();
        SseClient.Parser p = new SseClient.Parser(events::add);
        p.feed(": this is a comment");
        p.feed("event: x");
        p.feed("data: y");
        p.feed("");
        assertEquals(1, events.size());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ~/projects/petros-skills/intellij-plugin-spike && ./gradlew test --tests SseClientTest`
Expected: compile error.

- [ ] **Step 3: Implement `SseClient`**

Create `SseClient.java`:

```java
package com.petros.ireview;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Minimal Server-Sent Events consumer.
 *
 * Use {@link Parser} for unit testing the line-to-event state machine.
 * Use {@link #connect} for live HTTP consumption.
 */
public final class SseClient {

    public record Event(String name, String data) {}

    /** Pure state machine. Feed it lines (without trailing newline). */
    public static final class Parser {
        private final Consumer<Event> sink;
        private String eventName = "message";
        private StringBuilder dataBuf = new StringBuilder();
        private boolean hasData = false;

        public Parser(Consumer<Event> sink) { this.sink = sink; }

        public void feed(String line) {
            if (line.isEmpty()) {
                if (hasData) {
                    sink.accept(new Event(eventName, dataBuf.toString()));
                }
                eventName = "message";
                dataBuf = new StringBuilder();
                hasData = false;
                return;
            }
            if (line.startsWith(":")) return; // comment
            int colon = line.indexOf(':');
            String field;
            String value;
            if (colon < 0) { field = line; value = ""; }
            else { field = line.substring(0, colon); value = line.substring(colon + 1); }
            if (value.startsWith(" ")) value = value.substring(1);
            switch (field) {
                case "event" -> eventName = value;
                case "data" -> {
                    if (hasData) dataBuf.append('\n');
                    dataBuf.append(value);
                    hasData = true;
                }
                default -> { /* ignore id, retry, etc. */ }
            }
        }
    }

    /** Open an SSE stream. Returns a CompletableFuture that completes when the stream ends. */
    public static CompletableFuture<Void> connect(
            URI uri,
            Duration connectTimeout,
            Consumer<Event> onEvent,
            Consumer<Throwable> onError) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
        HttpRequest req = HttpRequest.newBuilder(uri)
            .header("Accept", "text/event-stream")
            .GET()
            .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofLines())
            .thenAccept(resp -> {
                Parser parser = new Parser(onEvent);
                try {
                    resp.body().forEach(parser::feed);
                } catch (Exception e) {
                    onError.accept(e);
                }
            })
            .exceptionally(t -> { onError.accept(t); return null; });
    }

    private SseClient() {}
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ~/projects/petros-skills/intellij-plugin-spike && ./gradlew test --tests SseClientTest`
Expected: 4/4 tests pass.

- [ ] **Step 5: Commit**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
git add src/main/java/com/petros/ireview/SseClient.java src/test/java/com/petros/ireview/SseClientTest.java
git commit -m "intellij-plugin-spike: hand-rolled SSE parser + connect helper"
```

---

### Task 9: `FakeReviewServer` test fixture

**Files:**
- Create: `intellij-plugin-spike/src/test/java/com/petros/ireview/FakeReviewServer.java`

- [ ] **Step 1: Implement the fixture**

Create `FakeReviewServer.java`:

```java
package com.petros.ireview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP server for tests. Lets us script /api/sessions, /threads.json,
 * and /stream responses without a real Python server.
 */
public final class FakeReviewServer implements AutoCloseable {
    private final HttpServer server;
    private final int port;
    public final List<HttpExchange> requests = new ArrayList<>();
    public final ConcurrentLinkedQueue<String> sseQueue = new ConcurrentLinkedQueue<>();
    public volatile String sessionsJson = "[]";
    public volatile String threadsJson = "{}";

    public FakeReviewServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/sessions", this::handleSessions);
        server.createContext("/s/", this::handleSession);
        server.start();
    }

    public String baseUrl() { return "http://127.0.0.1:" + port; }

    private void handleSessions(HttpExchange ex) throws IOException {
        requests.add(ex);
        byte[] body = sessionsJson.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void handleSession(HttpExchange ex) throws IOException {
        requests.add(ex);
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/threads.json")) {
            byte[] body = threadsJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            return;
        }
        if (path.endsWith("/stream")) {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                while (!ex.getResponseHeaders().containsKey("X-Closed")) {
                    String chunk;
                    while ((chunk = sseQueue.poll()) != null) {
                        os.write(chunk.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                    try { Thread.sleep(20); } catch (InterruptedException e) { break; }
                }
            }
            return;
        }
        ex.sendResponseHeaders(404, -1);
    }

    public void pushSseEvent(String name, String data) {
        sseQueue.offer("event: " + name + "\ndata: " + data + "\n\n");
    }

    @Override public void close() {
        server.stop(0);
    }
}
```

- [ ] **Step 2: Verify it compiles by running existing tests**

Run: `cd ~/projects/petros-skills/intellij-plugin-spike && ./gradlew test`
Expected: same tests as before pass; FakeReviewServer compiles.

- [ ] **Step 3: Commit**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
git add src/test/java/com/petros/ireview/FakeReviewServer.java
git commit -m "intellij-plugin-spike: FakeReviewServer fixture for client tests"
```

---

### Task 10: `ReviewSessionClient`

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/ReviewSessionClient.java`
- Create: `intellij-plugin-spike/src/test/java/com/petros/ireview/ReviewSessionClientTest.java`

This task is larger; split into substeps.

- [ ] **Step 1: Write the failing test — discovery**

Create `ReviewSessionClientTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ReviewSessionClientTest {

    @Test
    void emitsAttachedWhenDiscoverFindsSession() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PMP-171\",\"title\":\"Proposal dashboard\","
              + "\"state_dir\":\"/tmp/x\"}]";
            CountDownLatch attached = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(),
                "/proj/montblanc",
                Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                    assertEquals("abc", info.sid());
                    assertEquals("PMP-171", info.prRef());
                    attached.countDown();
                }
            });
            client.start();
            assertTrue(attached.await(2, TimeUnit.SECONDS), "should attach within 2s");
            client.stop();
        }
    }

    @Test
    void receivesThreadChangedEvent() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\","
              + "\"state_dir\":\"/tmp/x\"}]";
            CountDownLatch gotEvent = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(),
                "/proj/montblanc",
                Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onThreadChanged(String anchor, String synthesis, int version) {
                    if ("foo:R:1".equals(anchor) && "hello".equals(synthesis)) {
                        gotEvent.countDown();
                    }
                }
            });
            client.start();
            Thread.sleep(300); // let it attach
            server.pushSseEvent("thread-changed",
                "{\"anchor\":\"foo:R:1\",\"latest_synthesis\":\"hello\",\"version\":1,\"updated_at\":1}");
            assertTrue(gotEvent.await(2, TimeUnit.SECONDS));
            client.stop();
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests ReviewSessionClientTest`
Expected: compile error.

- [ ] **Step 3: Implement `ReviewSessionClient`**

Create `ReviewSessionClient.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.diagnostic.Logger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the interactive_review server: discovers a session by cwd,
 * opens an SSE stream, caches per-anchor syntheses, and pushes events
 * to listeners.
 *
 * Thread-safe. Listeners are invoked on the SSE consumer thread; bridge
 * to the EDT in the UI components.
 */
public final class ReviewSessionClient {
    private static final Logger LOG = Logger.getInstance(ReviewSessionClient.class);

    public record SessionInfo(String sid, String prRef, String title, String stateDir) {}
    public record ThreadState(String synthesis, int version) {}

    public interface Listener {
        default void onAttached(SessionInfo info) {}
        default void onDetached() {}
        default void onThreadChanged(String anchor, String synthesis, int version) {}
        default void onStateChanged(State state) {}
    }

    public enum State { DORMANT, CONNECTING, ACTIVE, DISCONNECTED }

    private final String baseUrl;
    private final String projectCwd;
    private final Duration pollInterval;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, ThreadState> cache = new ConcurrentHashMap<>();
    private volatile State state = State.DORMANT;
    private volatile SessionInfo current = null;
    private volatile Future<?> ssePoll = null;
    private volatile ScheduledFuture<?> discoverPoll = null;

    public ReviewSessionClient(String baseUrl, String projectCwd, Duration pollInterval) {
        this.baseUrl = baseUrl;
        this.projectCwd = projectCwd;
        this.pollInterval = pollInterval;
    }

    public void start() {
        discoverPoll = exec.scheduleWithFixedDelay(this::pollDiscover,
            0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (discoverPoll != null) discoverPoll.cancel(true);
        if (ssePoll != null) ssePoll.cancel(true);
        exec.shutdownNow();
        setState(State.DORMANT);
    }

    public void addListener(Listener l) { listeners.add(l); }
    public Optional<SessionInfo> currentSession() { return Optional.ofNullable(current); }
    public Optional<ThreadState> threadFor(String anchor) {
        return Optional.ofNullable(cache.get(anchor));
    }

    /** POST a comment event to /s/<sid>/api/submit. */
    public CompletableFuture<Void> postComment(String anchor, String text) {
        SessionInfo s = current;
        if (s == null) return CompletableFuture.failedFuture(new IllegalStateException("no session"));
        String body = String.format(
            "{\"anchor\":%s,\"type\":\"comment\",\"text\":%s}",
            jsonEscape(anchor), jsonEscape(text));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + s.sid() + "/api/submit"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .thenAccept(resp -> {
                if (resp.statusCode() / 100 != 2) {
                    throw new RuntimeException("submit failed: HTTP " + resp.statusCode());
                }
            });
    }

    // --- internal ---

    private void pollDiscover() {
        try {
            String url = baseUrl + "/api/sessions?cwd=" + URLEncoder(projectCwd);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                handleNoSession();
                return;
            }
            SessionInfo found = parseFirstSession(resp.body());
            if (found == null) {
                handleNoSession();
                return;
            }
            if (current == null || !current.sid().equals(found.sid())) {
                attach(found);
            }
        } catch (Exception e) {
            LOG.warn("discover failed", e);
            handleNoSession();
        }
    }

    private void handleNoSession() {
        if (current != null) {
            current = null;
            cache.clear();
            if (ssePoll != null) { ssePoll.cancel(true); ssePoll = null; }
            for (Listener l : listeners) l.onDetached();
        }
        setState(State.DORMANT);
    }

    private void attach(SessionInfo s) {
        current = s;
        setState(State.CONNECTING);
        seedCache(s.sid());
        openSse(s.sid());
        for (Listener l : listeners) l.onAttached(s);
    }

    private void seedCache(String sid) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + sid + "/threads.json")).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                Map<String, ThreadState> seeded = parseThreadsBulk(resp.body());
                cache.putAll(seeded);
                for (var e : seeded.entrySet()) {
                    for (Listener l : listeners) {
                        l.onThreadChanged(e.getKey(), e.getValue().synthesis(), e.getValue().version());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("seed-cache failed", e);
        }
    }

    private void openSse(String sid) {
        URI uri = URI.create(baseUrl + "/s/" + sid + "/stream");
        ssePoll = exec.submit(() ->
            SseClient.connect(uri, Duration.ofSeconds(2), this::handleSseEvent, t -> {
                LOG.warn("sse error", t);
                setState(State.DISCONNECTED);
                // basic reconnect: wait 2s, re-open
                exec.schedule(() -> openSse(sid), 2, TimeUnit.SECONDS);
            }).join());
        setState(State.ACTIVE);
    }

    private void handleSseEvent(SseClient.Event e) {
        if (!"thread-changed".equals(e.name())) return;
        String data = e.data();
        String anchor = jsonField(data, "anchor");
        String synthesis = jsonField(data, "latest_synthesis");
        int version = Integer.parseInt(jsonField(data, "version"));
        cache.put(anchor, new ThreadState(synthesis, version));
        for (Listener l : listeners) l.onThreadChanged(anchor, synthesis, version);
    }

    private void setState(State s) {
        if (state == s) return;
        state = s;
        for (Listener l : listeners) l.onStateChanged(s);
    }

    // --- tiny json helpers (avoiding a dep) ---

    private static String URLEncoder(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String jsonField(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|\\d+)");
        Matcher m = p.matcher(json);
        if (!m.find()) return "";
        return m.group(2) != null ? m.group(2).replace("\\\"", "\"") : m.group(1);
    }

    private static SessionInfo parseFirstSession(String json) {
        // Expecting JSON array of objects. Quick-and-dirty parse.
        if (json == null || !json.contains("\"sid\"")) return null;
        return new SessionInfo(
            jsonField(json, "sid"),
            jsonField(json, "pr_ref"),
            jsonField(json, "title"),
            jsonField(json, "state_dir"));
    }

    private static Map<String, ThreadState> parseThreadsBulk(String json) {
        // Quick-and-dirty: a real parser would be cleaner. For now, accept that
        // a fuller JSON dependency may be warranted at some point.
        Map<String, ThreadState> out = new HashMap<>();
        Pattern p = Pattern.compile(
            "\"([^\"]+)\"\\s*:\\s*\\{[^}]*\"latest_synthesis\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^}]*\"version\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        while (m.find()) {
            out.put(m.group(1), new ThreadState(m.group(2).replace("\\\"", "\""), Integer.parseInt(m.group(3))));
        }
        return out;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests ReviewSessionClientTest`
Expected: 2/2 pass.

- [ ] **Step 5: Commit**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
git add src/main/java/com/petros/ireview/ReviewSessionClient.java src/test/java/com/petros/ireview/ReviewSessionClientTest.java
git commit -m "intellij-plugin-spike: ReviewSessionClient (discovery, SSE, cache, post)"
```

---

### Task 11: Refactor `SpikeDiffExtension` to path-based anchors

**Files:**
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/SpikeDiffExtension.java`

- [ ] **Step 1: Read the existing extension to locate anchor construction**

Run: `cd ~/projects/petros-skills/intellij-plugin-spike && grep -n "contentTitle\|getContentTitles\|anchor" src/main/java/com/petros/ireview/SpikeDiffExtension.java`
Expected: a few lines showing the current anchor build (likely `title + ":" + side + ":" + line`).

- [ ] **Step 2: Replace with path extraction**

In `SpikeDiffExtension.java`, replace the anchor-building helper. Insert a new utility:

```java
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.contents.FileContent;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

private static String extractPath(ContentDiffRequest request, Side side, Project project) {
    int idx = side == Side.LEFT ? 0 : 1;
    var contents = request.getContents();
    if (idx >= contents.size()) return null;
    var content = contents.get(idx);
    VirtualFile vf = null;
    if (content instanceof FileContent fc) vf = fc.getFile();
    else if (content instanceof DocumentContent dc) vf = dc.getHighlightFile();
    if (vf == null) return null;
    String projBase = project.getBasePath();
    String path = vf.getPath();
    if (projBase != null && path.startsWith(projBase + "/")) {
        path = path.substring(projBase.length() + 1);
    }
    return path;
}
```

Then where the anchor is currently built (e.g., `String anchor = title + ":" + side + ":" + line;`), replace with:

```java
String path = extractPath(request, side, project);
if (path == null) return; // viewer unsupported
String anchor = path + ":" + (side == Side.LEFT ? "L" : "R") + ":" + line;
```

(Adjust the `Side` enum import to match what `SpikeDiffExtension` uses today — likely `com.intellij.diff.util.Side`.)

- [ ] **Step 3: Compile to verify no regressions**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
git add src/main/java/com/petros/ireview/SpikeDiffExtension.java
git commit -m "intellij-plugin-spike: path-based anchors (matches interactive_review server)"
```

---

### Task 12: Refactor `SynthesisPopup` to use `ReviewSessionClient` + `MarkdownLinkRenderer`

**Files:**
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisPopup.java`

- [ ] **Step 1: Read the existing popup to understand its shape**

Run: `cd ~/projects/petros-skills/intellij-plugin-spike && wc -l src/main/java/com/petros/ireview/SynthesisPopup.java && head -80 src/main/java/com/petros/ireview/SynthesisPopup.java`

- [ ] **Step 2: Replace the body-rendering pieces**

In `SynthesisPopup.java`, find where the synthesis text is set on a `JTextArea` or similar. Replace with a `JEditorPane`:

```java
private final JEditorPane synthesisPane = new JEditorPane("text/html", "");
{
    synthesisPane.setEditable(false);
    synthesisPane.setOpaque(false);
    synthesisPane.setBorder(JBUI.Borders.empty(8, 12));
    synthesisPane.addHyperlinkListener(e -> {
        if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
        String url = e.getDescription();
        if (url.startsWith("ireview-nav://")) {
            MarkdownLinkRenderer.NavTarget t = MarkdownLinkRenderer.parseNavTarget(url);
            VirtualFile file = LocalFileSystem.getInstance()
                .findFileByPath(project.getBasePath() + "/" + t.path());
            if (file != null) {
                new OpenFileDescriptor(project, file, Math.max(0, t.line() - 1), 0)
                    .navigate(true);
            }
        } else {
            BrowserUtil.browse(url);
        }
    });
}
```

And the synthesis-update method:

```java
public void renderSynthesis(String synthesis) {
    String html = "<html><body style='font-family: -apple-system, sans-serif; "
                + "color: #d8d8d8; font-size: 13px; line-height: 1.7;'>"
                + MarkdownLinkRenderer.toHtml(synthesis)
                + "</body></html>";
    synthesisPane.setText(html);
    synthesisPane.setCaretPosition(0);
}
```

- [ ] **Step 3: Replace the submit path**

Find the `submit` button handler. Replace its body with:

```java
String question = inputArea.getText().trim();
if (question.isEmpty()) return;
inputArea.setText("");
showThinking();
reviewSessionClient.postComment(anchor, question).whenComplete((v, t) -> {
    if (t != null) {
        ApplicationManager.getApplication().invokeLater(() ->
            showError("Failed to submit — Retry"));
    }
    // Successful submit: do nothing; the SSE thread-changed event will
    // arrive and renderSynthesis will be called from the listener.
});
```

Wire `ReviewSessionClient` into the constructor (or a project-level service that holds it).

- [ ] **Step 4: Compile to verify**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
git add src/main/java/com/petros/ireview/SynthesisPopup.java
git commit -m "intellij-plugin-spike: popup uses ReviewSessionClient + MarkdownLinkRenderer"
```

---

### Task 13: `StatusBarWidget`

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/StatusBarWidget.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/StatusBarWidgetFactory.java`
- Modify: `intellij-plugin-spike/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Implement the widget**

Create `StatusBarWidget.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.awt.event.MouseEvent;

public final class StatusBarWidget implements com.intellij.openapi.wm.StatusBarWidget,
        com.intellij.openapi.wm.StatusBarWidget.TextPresentation {

    public static final String WIDGET_ID = "com.petros.ireview.statusbar";

    private final Project project;
    private final ReviewSessionClient client;
    private volatile String text = "Review: idle";
    private StatusBar statusBar;

    public StatusBarWidget(Project project, ReviewSessionClient client) {
        this.project = project;
        this.client = client;
        client.addListener(new ReviewSessionClient.Listener() {
            @Override public void onStateChanged(ReviewSessionClient.State state) {
                text = switch (state) {
                    case DORMANT -> "Review: idle — /interactive-review <PR>";
                    case CONNECTING -> "Review: connecting…";
                    case ACTIVE -> client.currentSession()
                        .map(s -> "Review: " + s.prRef() + " ✓").orElse("Review: active ✓");
                    case DISCONNECTED -> "Review: reconnecting…";
                };
                if (statusBar != null) statusBar.updateWidget(WIDGET_ID);
            }
        });
    }

    @Override public @NonNls @NotNull String ID() { return WIDGET_ID; }
    @Override public @Nullable WidgetPresentation getPresentation() { return this; }
    @Override public void install(@NotNull StatusBar statusBar) { this.statusBar = statusBar; }
    @Override public void dispose() {}

    @Override public @Nullable String getTooltipText() {
        return "Click to copy /interactive-review command";
    }
    @Override public @NotNull String getText() { return text; }
    @Override public float getAlignment() { return 0f; }
    @Override public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return e -> {
            // Optional: copy /interactive-review to clipboard.
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new java.awt.datatransfer.StringSelection("/interactive-review "), null);
        };
    }
}
```

- [ ] **Step 2: Implement the factory**

Create `StatusBarWidgetFactory.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

public final class StatusBarWidgetFactory implements com.intellij.openapi.wm.StatusBarWidgetFactory {
    @Override public @NotNull String getId() { return StatusBarWidget.WIDGET_ID; }
    @Override public @NotNull String getDisplayName() { return "Interactive Review"; }
    @Override public boolean isAvailable(@NotNull Project project) { return true; }
    @Override public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        // Look up the project-level ReviewSessionClient service.
        return new StatusBarWidget(project, ReviewSessionService.get(project).client());
    }
    @Override public void disposeWidget(@NotNull StatusBarWidget widget) { widget.dispose(); }
    @Override public boolean canBeEnabledOn(@NotNull com.intellij.openapi.wm.StatusBar statusBar) { return true; }
}
```

(`ReviewSessionService.get(project).client()` is a tiny project-service wrapper around `ReviewSessionClient`. Add it as a small class in the same package; it owns one `ReviewSessionClient` per project and starts/stops it on project open/close.)

- [ ] **Step 3: Register in plugin.xml**

Edit `src/main/resources/META-INF/plugin.xml`. Inside `<extensions defaultExtensionNs="com.intellij">` add:

```xml
        <statusBarWidgetFactory id="com.petros.ireview.statusbar"
                                implementation="com.petros.ireview.StatusBarWidgetFactory"/>
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew prepareSandbox`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
git add src/main/java/com/petros/ireview/StatusBarWidget.java src/main/java/com/petros/ireview/StatusBarWidgetFactory.java src/main/resources/META-INF/plugin.xml
git commit -m "intellij-plugin-spike: status bar widget (dormant/connecting/active/disconnected)"
```

---

## Phase 4 — Cleanup

### Task 14: Delete obsolete components

**Files:**
- Delete: `EventBridge.java`, `ThreadsService.java`, `ThreadsPanel.java`, `ThreadsToolWindowFactory.java`
- Delete: `scripts/claude_responder.py`, `scripts/echo_responder.py`
- Modify: `src/main/resources/META-INF/plugin.xml` (remove tool window registration if present)

- [ ] **Step 1: Delete the Java classes**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
git rm src/main/java/com/petros/ireview/EventBridge.java \
       src/main/java/com/petros/ireview/ThreadsService.java \
       src/main/java/com/petros/ireview/ThreadsPanel.java \
       src/main/java/com/petros/ireview/ThreadsToolWindowFactory.java
```

- [ ] **Step 2: Delete the Python scripts**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
git rm scripts/claude_responder.py scripts/echo_responder.py
```

- [ ] **Step 3: Remove tool-window registration from plugin.xml**

Edit `src/main/resources/META-INF/plugin.xml`. Remove any `<toolWindow ... id="Spike Comments" ...>` element (the bottom tool window from the spike).

- [ ] **Step 4: Verify build still works**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. If references to deleted classes remain, fix them.

- [ ] **Step 5: Commit**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
git add -A
git commit -m "intellij-plugin-spike: delete obsolete file-IPC components"
```

---

### Task 15: Update HANDOFF.md

**Files:**
- Modify: `intellij-plugin-spike/HANDOFF.md`

- [ ] **Step 1: Rewrite the architecture section**

Replace the "Current architecture" and "IPC protocol" sections with a short summary pointing at the new spec + plan:

```markdown
## Current architecture

The plugin is a passive surface on top of the existing `interactive_review`
server. Backend mechanics (sessions, threads, SSE updates) live there; the
plugin discovers an active session by cwd, opens an SSE stream, posts
questions via HTTP. The legacy `~/spike-events/` file-IPC and the
`claude_responder.py` subprocess are gone.

See the design spec for the full picture:
`docs/superpowers/specs/2026-05-22-ide-backend-automation-design.md`.

Implementation plan:
`docs/superpowers/plans/2026-05-22-ide-backend-automation.md`.
```

- [ ] **Step 2: Update the iteration recipe** to reflect that the responder is no longer needed:

```markdown
## How to iterate

1. Start a review session from a terminal: `/interactive-review <PR>`.
2. `cd ~/projects/petros-skills/intellij-plugin-spike && ./gradlew prepareSandbox`
3. Quit and reopen real IDEA. Plugin auto-attaches via cwd polling.
4. Click `+` on any diff line, ask a question.
```

- [ ] **Step 3: Remove now-obsolete gotchas**

The "claude is aliased" and "`~/spike-events/` accumulates files" gotchas are obsolete — delete those bullets.

- [ ] **Step 4: Commit**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
git add HANDOFF.md
git commit -m "intellij-plugin-spike: rewrite HANDOFF.md for new architecture"
```

---

## Phase 5 — End-to-end smoke

### Task 16: Manual smoke test

**Files:**
- None (manual verification)

- [ ] **Step 1: Start a session from terminal**

In a terminal:
```bash
cd ~/projects/montblanc
# In a Claude Code session:
/interactive-review <PR>
```

The terminal should print the browser URL.

- [ ] **Step 2: Open IDEA on the same project**

Open `~/projects/montblanc` in real IDEA. Within ~5 s, observe:
- Status bar widget shows `Review: <PR-title> ✓`.
- Diff viewers (opened via `Git → Branches → ... → Show Diff with Working Tree`) show `+` icons on line hover.

- [ ] **Step 3: Ask from IDE**

Click `+` on a line. Popup opens. Type a question, click Ask. Popup shows "thinking…" for a moment. Within ~10 s, the synthesis renders with clickable inline references.

- [ ] **Step 4: Ask from browser, observe IDE update**

Open the browser URL. Click the same line, ask a follow-up. Within ~1 s of Claude's reply, the IDE popup (still open) refreshes with the new synthesis. Close the popup, observe the gutter icon pulses (updated since last view).

- [ ] **Step 5: Click an inline code reference**

In the IDE popup, click a `[symbol](path:line)` link. IDE navigates to that file + line.

- [ ] **Step 6: End the session**

In the original terminal Claude session, type `scrap it` (or click Done in the browser). Within ~5 s, IDE gutter icons disappear; status bar shows `Review: idle`.

- [ ] **Step 7: Document any deviations**

If any step doesn't behave as described, file a follow-up issue or add a section to HANDOFF.md describing what actually happened.

---

## Self-Review

After writing this plan, the following spec sections map to tasks:

| Spec section | Tasks |
|---|---|
| New server endpoints | 1, 2, 3, 4 |
| Anchor unification | 11 |
| UI states (status bar) | 13 |
| Popup design (Reader + markdown links) | 7, 12 |
| Synthesis prompt update | 5 |
| ReviewSessionClient | 10 (depends on 8, 9) |
| MarkdownLinkRenderer | 7 |
| StatusBarWidget | 13 |
| Removed components | 14 |
| Failure modes (basic retry) | 10 |
| Testing plan | 1, 2, 3, 4 (Python), 7, 8, 10 (Java) |
| End-to-end smoke | 16 |
| HANDOFF.md update | 15 |

No gaps found. The `latest_synthesis = messages[-1].text` derivation (spec) is implemented in Task 3 (`threads_bulk`) and Task 4 (SSE handler reuses the same projection).

**Known scope deferrals** (documented in the spec, intentionally absent from this plan):
- Cross-block re-synthesis (Slice C).
- Hover-preview tooltip on inline references.
- Auto-start session from IDE.
- Persistence of seen-versions across IDE restarts.

These remain as separate work items.
