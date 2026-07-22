# /walkthrough Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `/walkthrough <question>` — a skill that answers a codebase question as an ordered sequence of anchored steps, plus an IntelliJ plugin extension that walks the user through them with two mutually exclusive renderers.

**Architecture:** A Python handlers module (`skills/walkthrough/server.py`) over the existing `_shared/web_companion` server, storing `steps.json` plus one thread per step. The IntelliJ plugin discovers the session by project `cwd`, polls/streams changes, and renders steps either as a right-hand tool window (mode A "rail") or an editor inlay plus floating HUD (mode B "inline"). Claude generates the steps once, then stays armed via `watcher.sh` to answer per-step questions in place.

**Tech Stack:** Python 3.11+ (stdlib only, `pytest`), Java 25 / IntelliJ Platform Gradle plugin 2.16, Gson, commonmark, JUnit 5. Swing for all interactive UI (JCEF's JS→Java bridge is dead under IU-261).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-22-walkthrough-design.md`. Every decision there is binding.
- Skill directory: `skills/walkthrough/`. Plugin code: `intellij-plugin-spike/src/main/java/com/petros/ireview/`, package `com.petros.ireview`, class prefix `Walkthrough`.
- Port range: `range(54660, 54681)` — free (annotate 54580–54600, interactive-review 54620–54640).
- Skill name string is `walkthrough` everywhere (server state root `~/.claude/walkthrough/`, watcher `SKILL=walkthrough`).
- Thread anchors are `step:<id>` where `<id>` is a positive integer. No other anchor form is valid.
- Steps are frozen after generation: `steps.json` is written exactly once per session. Only threads change afterwards.
- Never modify existing review classes (`ReviewSessionClient`, `AnnotationsPanel`, `ReviewSessionService`, …). Reuse by call, not by edit. `FakeReviewServer` is the one exception — it is extended in Task 6.
- Python imports use the absolute `skills.…` package path (the repo root is `PYTHONPATH`), matching `skills/interactive_review/server.py`.
- Run Python tests from the repo root: `python3 -m pytest skills/walkthrough/tests/ -v`.
- Run Java tests from `intellij-plugin-spike/`: `./gradlew test`.
- Commit after every task. Branch `feat/walkthrough-skill` already exists and holds the spec.

## File Structure

**Created — Python**

| File | Responsibility |
| --- | --- |
| `skills/walkthrough/__init__.py` | Package marker (empty). |
| `skills/walkthrough/steps.py` | `steps.json` schema, validation, atomic write/read, anchor encode/decode. No HTTP. |
| `skills/walkthrough/server.py` | `Handlers` implementing `HandlersProtocol`: root page, data endpoints, SSE, submit, poll, session init. |
| `skills/walkthrough/ensure_server.sh` | Thin exec into `_shared/web_companion/ensure_server.sh` with skill env. |
| `skills/walkthrough/SKILL.md` | The skill itself: invocation, generation contract, watcher protocol, response style. |
| `skills/walkthrough/README.md` | Short operator note: what the skill is, where state lives, how to run tests. |
| `skills/walkthrough/tests/__init__.py` | Empty. |
| `skills/walkthrough/tests/test_steps.py` | Schema + atomic IO tests. |
| `skills/walkthrough/tests/test_server.py` | Handler tests against fake dirs. |

**Created — Java** (all in `intellij-plugin-spike/src/main/java/com/petros/ireview/`)

| File | Responsibility |
| --- | --- |
| `WalkthroughStep.java` | Immutable step record + `Role` enum. |
| `WalkthroughDoc.java` | Parsed `steps.json`: question, kind, generatedTs, steps. Includes the JSON parser. |
| `WalkthroughSessionClient.java` | HTTP/SSE client: discovery by cwd, steps fetch, thread cache, ask submit, liveness. |
| `WalkthroughController.java` | Step index, next/prev/jump, mode, listener fan-out. No IDE types except through `Navigator`. |
| `WalkthroughNavigator.java` | `Navigator` interface + IDE implementation (open file, scroll, highlight) using `AnchorResolver`. |
| `WalkthroughService.java` | Project service: owns client + controller, wires them, disposes. |
| `WalkthroughPanel.java` | Mode A renderer — tool window content. |
| `WalkthroughToolWindowFactory.java` | Registers the "Walkthrough" tool window. |
| `WalkthroughInlay.java` | Mode B renderer — editor inlay under the active anchor. |
| `WalkthroughHud.java` | Mode B floating position/keys bar. |
| `WalkthroughGutter.java` | Step badges in the gutter of any open file that holds an anchor. |
| `WalkthroughModeWidget.java` + `WalkthroughModeWidgetFactory.java` | Status-bar mode toggle. |
| `WalkthroughActions.java` | Next / Previous / Ask / Toggle-mode actions. |

**Created — Java tests** (`intellij-plugin-spike/src/test/java/com/petros/ireview/`)

`WalkthroughDocTest.java`, `WalkthroughSessionClientTest.java`, `WalkthroughControllerTest.java`, `WalkthroughStepAnchorTest.java`.

**Modified**

| File | Change |
| --- | --- |
| `intellij-plugin-spike/src/test/java/com/petros/ireview/FakeReviewServer.java` | Add `stepsJson` field + `/steps.json` route (Task 6). |
| `intellij-plugin-spike/src/main/resources/META-INF/plugin.xml` | Register service, tool window, widget factory, editor listener, actions (Tasks 8–11). |

---

## Task 1: `steps.py` — schema, validation, atomic IO

**Files:**
- Create: `skills/walkthrough/__init__.py`, `skills/walkthrough/steps.py`
- Create: `skills/walkthrough/tests/__init__.py`, `skills/walkthrough/tests/test_steps.py`

**Interfaces:**
- Consumes: `skills._shared.web_companion.atomic.write_text_atomic(path, text)` (existing).
- Produces:
  - `STEP_ROLES: frozenset[str]` = `{"context", "seam", "edit-site"}`
  - `validate(doc: dict) -> list[str]` — returns human-readable errors, empty list when valid.
  - `write_steps(state_dir: Path, doc: dict) -> None` — validates then writes `<state_dir>/steps.json` atomically. Raises `ValueError` joined on `"; "` when invalid.
  - `load_steps(state_dir: Path) -> dict | None` — `None` when absent or unparseable.
  - `generated_ts(state_dir: Path) -> int` — `0` when absent.
  - `step_anchor(step_id: int) -> str` → `"step:<id>"`
  - `valid_anchor(anchor: str) -> bool`
  - `anchor_step_id(anchor: str) -> int | None`

- [ ] **Step 1: Write the failing tests**

Create `skills/walkthrough/tests/__init__.py` (empty) and `skills/walkthrough/tests/test_steps.py`:

```python
import json

import pytest

from skills.walkthrough import steps as steps_module


def good_doc():
    return {
        "question": "how to add a precondition on share",
        "kind": "explain",
        "generated_ts": 1784720471,
        "steps": [
            {"id": 1, "title": "Where sharing starts", "file": "src/Api.java",
             "line": 42, "snippet": "return service.share(id);",
             "role": "context", "markdown": "The REST entry point."},
            {"id": 2, "title": "The precondition gate", "file": "src/Engine.java",
             "line": 114, "snippet": "var failures = preconditions.evaluate(p);",
             "role": "seam", "markdown": "Every Precondition bean runs here."},
        ],
    }


def test_valid_doc_has_no_errors():
    assert steps_module.validate(good_doc()) == []


def test_rejects_missing_snippet():
    doc = good_doc()
    del doc["steps"][0]["snippet"]
    errors = steps_module.validate(doc)
    assert any("snippet" in e for e in errors)


def test_rejects_blank_snippet():
    doc = good_doc()
    doc["steps"][0]["snippet"] = "   "
    assert any("snippet" in e for e in steps_module.validate(doc))


def test_rejects_non_positive_line():
    doc = good_doc()
    doc["steps"][1]["line"] = 0
    assert any("line" in e for e in steps_module.validate(doc))


def test_rejects_unknown_role():
    doc = good_doc()
    doc["steps"][0]["role"] = "wishful"
    assert any("role" in e for e in steps_module.validate(doc))


def test_rejects_duplicate_ids():
    doc = good_doc()
    doc["steps"][1]["id"] = 1
    assert any("duplicate" in e for e in steps_module.validate(doc))


def test_rejects_absolute_or_escaping_paths():
    doc = good_doc()
    doc["steps"][0]["file"] = "/etc/passwd"
    assert any("file" in e for e in steps_module.validate(doc))
    doc["steps"][0]["file"] = "../secrets.txt"
    assert any("file" in e for e in steps_module.validate(doc))


def test_rejects_empty_step_list():
    doc = good_doc()
    doc["steps"] = []
    assert any("at least" in e for e in steps_module.validate(doc))


def test_rejects_bad_kind():
    doc = good_doc()
    doc["kind"] = "vibes"
    assert any("kind" in e for e in steps_module.validate(doc))


def test_write_then_load_round_trip(tmp_path):
    steps_module.write_steps(tmp_path, good_doc())
    assert json.loads((tmp_path / "steps.json").read_text())["steps"][1]["id"] == 2
    loaded = steps_module.load_steps(tmp_path)
    assert loaded["question"] == "how to add a precondition on share"
    assert len(loaded["steps"]) == 2


def test_write_rejects_invalid_doc(tmp_path):
    doc = good_doc()
    doc["steps"][0]["snippet"] = ""
    with pytest.raises(ValueError):
        steps_module.write_steps(tmp_path, doc)
    assert not (tmp_path / "steps.json").exists()


def test_load_returns_none_when_absent(tmp_path):
    assert steps_module.load_steps(tmp_path) is None


def test_load_returns_none_on_garbage(tmp_path):
    (tmp_path / "steps.json").write_text("{not json")
    assert steps_module.load_steps(tmp_path) is None


def test_generated_ts(tmp_path):
    assert steps_module.generated_ts(tmp_path) == 0
    steps_module.write_steps(tmp_path, good_doc())
    assert steps_module.generated_ts(tmp_path) == 1784720471


def test_anchor_helpers():
    assert steps_module.step_anchor(3) == "step:3"
    assert steps_module.valid_anchor("step:3")
    assert not steps_module.valid_anchor("step:0")
    assert not steps_module.valid_anchor("src/x.java:R:12")
    assert not steps_module.valid_anchor("step:abc")
    assert steps_module.anchor_step_id("step:7") == 7
    assert steps_module.anchor_step_id("nope") is None
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest skills/walkthrough/tests/test_steps.py -v`
Expected: collection error — `ModuleNotFoundError: No module named 'skills.walkthrough'`.

- [ ] **Step 3: Write the implementation**

Create `skills/walkthrough/__init__.py` (empty file) and `skills/walkthrough/steps.py`:

```python
"""steps.json persistence for the walkthrough skill.

One document per session at <state_dir>/steps.json:

    {
      "question": "...",          # the user's question, verbatim
      "kind": "explain"|"diff",
      "generated_ts": <int>,       # epoch seconds, bumped only on (re)generation
      "steps": [
        {"id": 1, "title": "...", "file": "rel/path.java", "line": 42,
         "snippet": "<verbatim line text>", "role": "context|seam|edit-site",
         "markdown": "..."}
      ]
    }

Steps are frozen once written — only per-step threads change afterwards. The
document is written atomically so a reader never sees a half-written file.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from skills._shared.web_companion.atomic import write_text_atomic

STEP_ROLES = frozenset({"context", "seam", "edit-site"})
DOC_KINDS = frozenset({"explain", "diff"})
MIN_STEPS = 1        # server-side floor; the 5-12 range is a SKILL.md rule
MAX_STEPS = 24       # hard ceiling — a runaway generator is a bug, not a tour

_ANCHOR_RE = re.compile(r"^step:([1-9]\d*)$")
STEPS_FILE = "steps.json"


def validate(doc: dict) -> list[str]:
    """Return a list of human-readable problems; empty means valid."""
    errors: list[str] = []
    if not isinstance(doc, dict):
        return ["document must be an object"]
    if not isinstance(doc.get("question"), str) or not doc["question"].strip():
        errors.append("question must be a non-empty string")
    if doc.get("kind") not in DOC_KINDS:
        errors.append(f"kind must be one of {sorted(DOC_KINDS)}")
    if not isinstance(doc.get("generated_ts"), int) or doc["generated_ts"] <= 0:
        errors.append("generated_ts must be a positive integer")
    steps = doc.get("steps")
    if not isinstance(steps, list):
        return errors + ["steps must be a list"]
    if len(steps) < MIN_STEPS:
        errors.append(f"steps must contain at least {MIN_STEPS} step")
    if len(steps) > MAX_STEPS:
        errors.append(f"steps must contain at most {MAX_STEPS} steps")
    seen_ids: set[int] = set()
    for i, s in enumerate(steps):
        where = f"step[{i}]"
        if not isinstance(s, dict):
            errors.append(f"{where} must be an object")
            continue
        sid = s.get("id")
        if not isinstance(sid, int) or isinstance(sid, bool) or sid < 1:
            errors.append(f"{where} id must be a positive integer")
        elif sid in seen_ids:
            errors.append(f"{where} duplicate id {sid}")
        else:
            seen_ids.add(sid)
        title = s.get("title")
        if not isinstance(title, str) or not title.strip():
            errors.append(f"{where} title must be a non-empty string")
        errors.extend(_file_errors(where, s.get("file")))
        line = s.get("line")
        if not isinstance(line, int) or isinstance(line, bool) or line < 1:
            errors.append(f"{where} line must be a positive integer")
        snippet = s.get("snippet")
        if not isinstance(snippet, str) or not snippet.strip():
            errors.append(f"{where} snippet must be non-empty (it anchors the step)")
        if s.get("role") not in STEP_ROLES:
            errors.append(f"{where} role must be one of {sorted(STEP_ROLES)}")
        markdown = s.get("markdown")
        if not isinstance(markdown, str) or not markdown.strip():
            errors.append(f"{where} markdown must be a non-empty string")
    return errors


def _file_errors(where: str, file: object) -> list[str]:
    if not isinstance(file, str) or not file.strip():
        return [f"{where} file must be a non-empty project-relative path"]
    if file.startswith("/"):
        return [f"{where} file must be project-relative, not absolute"]
    parts = file.split("/")
    if any(p in ("", ".", "..") for p in parts):
        return [f"{where} file must not contain empty or dot path segments"]
    return []


def write_steps(state_dir: Path, doc: dict) -> None:
    """Validate and atomically write <state_dir>/steps.json. Raises ValueError."""
    errors = validate(doc)
    if errors:
        raise ValueError("; ".join(errors))
    write_text_atomic(Path(state_dir) / STEPS_FILE, json.dumps(doc, indent=2))


def load_steps(state_dir: Path) -> dict | None:
    p = Path(state_dir) / STEPS_FILE
    try:
        doc = json.loads(p.read_text())
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return None
    return doc if isinstance(doc, dict) else None


def generated_ts(state_dir: Path) -> int:
    doc = load_steps(state_dir)
    if not doc:
        return 0
    ts = doc.get("generated_ts")
    return ts if isinstance(ts, int) and not isinstance(ts, bool) else 0


def step_anchor(step_id: int) -> str:
    return f"step:{step_id}"


def valid_anchor(anchor: str) -> bool:
    return isinstance(anchor, str) and _ANCHOR_RE.match(anchor) is not None


def anchor_step_id(anchor: str) -> int | None:
    m = _ANCHOR_RE.match(anchor) if isinstance(anchor, str) else None
    return int(m.group(1)) if m else None
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest skills/walkthrough/tests/test_steps.py -v`
Expected: 14 passed.

- [ ] **Step 5: Commit**

```bash
git add skills/walkthrough/__init__.py skills/walkthrough/steps.py skills/walkthrough/tests/
git commit -m "feat(walkthrough): steps.json schema, validation and atomic IO"
```

---
## Task 2: `server.py` — root page, data endpoints, poll, session init

**Files:**
- Create: `skills/walkthrough/server.py`
- Create: `skills/walkthrough/tests/test_server.py`

**Interfaces:**
- Consumes: `skills.walkthrough.steps` (Task 1); `skills._shared.web_companion.server as wc_server` (`REAP_AFTER`, `run`); `skills.interactive_review.threads as threads_module` (`load`, `append_message`, `list_versions`, `delete`) — imported, never copied.
- Produces:
  - `class Handlers` with `set_registry`, `serve_root`, `serve_data`, `serve_poll`, `create_session_extra`, `comment_count` (SSE + submit land in Task 3).
  - `threads_bulk(dirs) -> dict[anchor, {latest_synthesis, version, updated_at, title, question}]`
  - `PORT_RANGE = range(54660, 54681)`, `BANNER = "walkthrough-server v1"`
  - Module-level `_send_text/_send_html/_send_json` helpers (same shape as interactive_review).

- [ ] **Step 1: Write the failing tests**

Create `skills/walkthrough/tests/test_server.py`:

```python
import json
import time
from io import BytesIO
from unittest.mock import MagicMock

from skills.walkthrough.server import Handlers
from skills.walkthrough import steps as steps_module
from skills.interactive_review import threads as threads_module


def make_handler(body=b""):
    h = MagicMock()
    h.rfile = BytesIO(body)
    h.wfile = BytesIO()
    h.headers = {}
    return h


def make_dirs(tmp_path):
    state_dir = tmp_path / "state"
    events_dir = state_dir / "events"
    consumed_dir = state_dir / "consumed"
    threads_dir = state_dir / "threads"
    response_dir = tmp_path / "response"
    for d in (state_dir, events_dir, consumed_dir, threads_dir, response_dir):
        d.mkdir(parents=True, exist_ok=True)
    return {
        "state_dir": state_dir,
        "events_dir": events_dir,
        "consumed_dir": consumed_dir,
        "response_dir": response_dir,
        "annotations_dir": response_dir,
        "_cwd": str(tmp_path),
    }


def doc():
    return {
        "question": "how sharing is gated",
        "kind": "explain",
        "generated_ts": 1784720471,
        "steps": [
            {"id": 1, "title": "Entry point", "file": "src/Api.java", "line": 42,
             "snippet": "return service.share(id);", "role": "context",
             "markdown": "REST entry point."},
        ],
    }


def test_serve_root_points_to_ide(tmp_path):
    h, handler = Handlers(), make_handler()
    h.serve_root(handler, make_dirs(tmp_path))
    handler.send_response.assert_called_once_with(200)
    assert b"IntelliJ" in handler.wfile.getvalue()


def test_serve_root_closed_when_finished(tmp_path):
    dirs = make_dirs(tmp_path)
    (dirs["state_dir"] / "finished").write_text("")
    h, handler = Handlers(), make_handler()
    h.serve_root(handler, dirs)
    assert b"closed" in handler.wfile.getvalue()


def test_steps_json_empty_before_generation(tmp_path):
    h, handler = Handlers(), make_handler()
    h.serve_data(handler, make_dirs(tmp_path), "steps.json")
    handler.send_response.assert_called_with(200)
    body = json.loads(handler.wfile.getvalue())
    assert body == {"question": "", "kind": "", "generated_ts": 0, "steps": []}


def test_steps_json_after_generation(tmp_path):
    dirs = make_dirs(tmp_path)
    steps_module.write_steps(dirs["state_dir"], doc())
    h, handler = Handlers(), make_handler()
    h.serve_data(handler, dirs, "steps.json")
    body = json.loads(handler.wfile.getvalue())
    assert body["steps"][0]["title"] == "Entry point"
    assert body["generated_ts"] == 1784720471


def test_thread_endpoint_rejects_non_step_anchor(tmp_path):
    h, handler = Handlers(), make_handler()
    h.serve_data(handler, make_dirs(tmp_path), "thread?anchor=src%2Fx.java%3AR%3A12")
    handler.send_response.assert_called_with(400)


def test_thread_endpoint_returns_empty_thread(tmp_path):
    h, handler = Handlers(), make_handler()
    h.serve_data(handler, make_dirs(tmp_path), "thread?anchor=step%3A3")
    body = json.loads(handler.wfile.getvalue())
    assert body["anchor"] == "step:3"
    assert body["messages"] == []


def test_threads_bulk_skips_threads_without_claude_reply(tmp_path):
    dirs = make_dirs(tmp_path)
    threads_dir = dirs["state_dir"] / "threads"
    threads_module.append_message(threads_dir, "step:1", {
        "role": "user", "ts": 1, "text": "why?", "source_event_id": "user-1"})
    h = Handlers()
    assert h.threads_bulk(dirs) == {}
    threads_module.append_message(threads_dir, "step:1", {
        "role": "claude", "ts": 2, "text": "because X", "source_event_id": "1"},
        title="Ordering")
    bulk = h.threads_bulk(dirs)
    assert bulk["step:1"]["latest_synthesis"] == "because X"
    assert bulk["step:1"]["title"] == "Ordering"
    assert bulk["step:1"]["question"] == "why?"


def test_serve_poll_reports_steps_ts_and_liveness(tmp_path):
    dirs = make_dirs(tmp_path)
    steps_module.write_steps(dirs["state_dir"], doc())
    (dirs["state_dir"] / "watcher_heartbeat").write_text(str(int(time.time())))
    h, handler = Handlers(), make_handler()
    h.serve_poll(handler, dirs)
    body = json.loads(handler.wfile.getvalue())
    assert body["steps_generated_at"] == 1784720471
    assert body["ended"] is False
    assert body["ended_reason"] is None


def test_serve_poll_reports_cancelled(tmp_path):
    dirs = make_dirs(tmp_path)
    (dirs["state_dir"] / "cancelled").write_text("{}")
    h, handler = Handlers(), make_handler()
    h.serve_poll(handler, dirs)
    body = json.loads(handler.wfile.getvalue())
    assert body["ended"] is True
    assert body["ended_reason"] == "cancelled"


def test_create_session_extra_requires_question(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    try:
        h.create_session_extra({}, dirs)
    except ValueError as e:
        assert "question" in str(e)
    else:
        raise AssertionError("expected ValueError")


def test_create_session_extra_seeds_threads_dir_and_meta(tmp_path):
    base = tmp_path / "fresh"
    state_dir = base / "state"
    state_dir.mkdir(parents=True)
    dirs = {"state_dir": state_dir, "_cwd": str(tmp_path)}
    h = Handlers()
    out = h.create_session_extra({"question": "how does sharing work", "kind": "explain"}, dirs)
    assert (state_dir / "threads").is_dir()
    assert out["title"] == "how does sharing work"
    meta = json.loads((state_dir / "meta.json").read_text())
    assert meta["question"] == "how does sharing work"
    assert meta["kind"] == "explain"


def test_create_session_extra_defaults_kind_to_explain(tmp_path):
    state_dir = tmp_path / "s2"
    state_dir.mkdir()
    h = Handlers()
    h.create_session_extra({"question": "q"}, {"state_dir": state_dir, "_cwd": str(tmp_path)})
    assert json.loads((state_dir / "meta.json").read_text())["kind"] == "explain"


def test_comment_count_counts_threads(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    assert h.comment_count(dirs) == 0
    threads_module.append_message(dirs["state_dir"] / "threads", "step:1", {
        "role": "user", "ts": 1, "text": "q", "source_event_id": "user-1"})
    assert h.comment_count(dirs) == 1
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest skills/walkthrough/tests/test_server.py -v`
Expected: collection error — `ModuleNotFoundError: No module named 'skills.walkthrough.server'`.

- [ ] **Step 3: Write the implementation**

Create `skills/walkthrough/server.py` (SSE and submit are added in Task 3 — this file already declares them so the protocol is satisfied, but they raise `NotImplementedError` until then; no test covers them yet):

```python
"""Walkthrough skill — thin handlers module over web_companion.

Guided code tours run in IntelliJ via the IDE plugin. This server is headless:
it holds the generated step list (steps.json), one thread per step, streams
changes over SSE, and enqueues /api/submit questions as events for Claude.
There is no browser UI.
"""
from __future__ import annotations

import json
import sys
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler
from pathlib import Path

from skills._shared.web_companion import server as wc_server
from skills.interactive_review import threads as threads_module
from skills.walkthrough import steps as steps_module

SHARED_STATIC_DIR = Path(__file__).resolve().parent.parent / "_shared" / "web_companion" / "static"

PORT_RANGE = range(54660, 54681)
BANNER = "walkthrough-server v1"

IDE_PAGE = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Walkthrough</title>
<style>body{font-family:-apple-system,Segoe UI,sans-serif;background:#0d1117;color:#c9d1d9;display:flex;min-height:100vh;margin:0;align-items:center;justify-content:center}main{max-width:32rem;text-align:center;padding:2rem;line-height:1.5}h1{font-size:1.15rem;font-weight:600}b{color:#fff}</style></head>
<body><main><h1>🧭 This walkthrough runs in IntelliJ</h1>
<p>Open the project in <b>IntelliJ&nbsp;IDEA</b> — the plugin walks you through the steps and lets you ask a question on any of them.</p></main></body></html>
"""

CLOSED_PAGE = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Closed</title>
<style>body{font-family:-apple-system,Segoe UI,sans-serif;background:#0d1117;color:#8b949e;display:flex;min-height:100vh;margin:0;align-items:center;justify-content:center}</style></head>
<body><main><p>This walkthrough is closed.</p></main></body></html>
"""

EMPTY_DOC = {"question": "", "kind": "", "generated_ts": 0, "steps": []}


def _is_terminal(state_dir: Path) -> bool:
    return (state_dir / "finished").exists() or (state_dir / "cancelled").exists()


class Handlers:
    def __init__(self):
        self._registry = None

    def set_registry(self, registry) -> None:
        self._registry = registry

    def serve_root(self, h: BaseHTTPRequestHandler, dirs: dict) -> None:
        state_dir = Path(dirs["state_dir"])
        _send_html(h, 200, CLOSED_PAGE if _is_terminal(state_dir) else IDE_PAGE)

    def threads_bulk(self, dirs: dict) -> dict:
        """{anchor: {latest_synthesis, version, updated_at, title, question}}.

        Threads with no claude-role message yet are omitted — the IDE has
        nothing to render for them beyond the pending spinner it already owns.
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
            if not isinstance(anchor, str) or not steps_module.valid_anchor(anchor):
                continue
            messages = t.get("messages", [])
            claude_msgs = [m for m in messages if m.get("role") == "claude"]
            if not claude_msgs:
                continue
            user_msgs = [m for m in messages if m.get("role") == "user"]
            last = claude_msgs[-1]
            result[anchor] = {
                "latest_synthesis": last.get("text", ""),
                "version": t.get("version", 0),
                "updated_at": last.get("ts", 0),
                "title": t.get("title", ""),
                "question": user_msgs[-1].get("text", "") if user_msgs else "",
            }
        return result

    def serve_data(self, h: BaseHTTPRequestHandler, dirs: dict, query: str) -> None:
        state_dir = Path(dirs["state_dir"])
        if query == "stream":
            self._serve_stream(h, dirs)
            return
        if query == "steps.json":
            _send_json(h, 200, steps_module.load_steps(state_dir) or EMPTY_DOC)
            return
        if query == "threads.json":
            _send_json(h, 200, self.threads_bulk(dirs))
            return
        if query.startswith("thread"):
            qs = query.split("?", 1)[1] if "?" in query else ""
            anchor = urllib.parse.parse_qs(qs).get("anchor", [None])[0]
            if not anchor:
                _send_text(h, 400, "missing anchor")
                return
            anchor = urllib.parse.unquote(anchor)
            if not steps_module.valid_anchor(anchor):
                _send_text(h, 400, "bad anchor")
                return
            _send_json(h, 200, threads_module.load(state_dir / "threads", anchor))
            return
        _send_text(h, 404, "not found")

    def _serve_stream(self, h: BaseHTTPRequestHandler, dirs: dict) -> None:
        raise NotImplementedError  # Task 3

    def handle_submit(self, h: BaseHTTPRequestHandler, dirs: dict, payload: dict) -> None:
        raise NotImplementedError  # Task 3

    def serve_poll(self, h: BaseHTTPRequestHandler, dirs: dict) -> None:
        state_dir = Path(dirs["state_dir"])
        versions = threads_module.list_versions(state_dir / "threads")
        try:
            hb = int((state_dir / "watcher_heartbeat").read_text().strip())
        except (FileNotFoundError, ValueError, OSError):
            hb = 0
        age = (int(time.time()) - hb) if hb else None
        cancelled = (state_dir / "cancelled").exists()
        finished = (state_dir / "finished").exists()
        dead = age is not None and age > wc_server.REAP_AFTER
        ended_reason = (
            "cancelled" if cancelled
            else "finished" if finished
            else "dead" if dead
            else None
        )
        _send_json(h, 200, {
            "threads": versions,
            "steps_generated_at": steps_module.generated_ts(state_dir),
            "watcher_seen_at": hb,
            "finished": _is_terminal(state_dir),
            "ended": ended_reason is not None,
            "ended_reason": ended_reason,
        })

    def create_session_extra(self, payload: dict, dirs: dict) -> dict | None:
        """Cheap: no external fetch. Seed dirs and record the question."""
        state_dir = Path(dirs["state_dir"])
        (state_dir / "threads").mkdir(exist_ok=True)
        question = payload.get("question") or payload.get("title")
        if not isinstance(question, str) or not question.strip():
            raise ValueError("payload missing 'question' (what the tour should explain)")
        kind = payload.get("kind") or "explain"
        if kind not in steps_module.DOC_KINDS:
            raise ValueError(f"kind must be one of {sorted(steps_module.DOC_KINDS)}")
        (state_dir / "meta.json").write_text(json.dumps({
            "question": question.strip(),
            "kind": kind,
            "cwd": dirs.get("_cwd", ""),
            "created_at": int(time.time()),
        }, indent=2))
        return {"title": question.strip(), "kind": kind}

    def comment_count(self, dirs: dict) -> int:
        """Number of per-step threads for this walkthrough."""
        threads_dir = Path(dirs["state_dir"]) / "threads"
        if not threads_dir.is_dir():
            return 0
        return sum(1 for p in threads_dir.iterdir() if p.suffix == ".json")


def _send_text(h, status, body):
    data = body.encode("utf-8")
    h.send_response(status)
    h.send_header("Content-Type", "text/plain; charset=utf-8")
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)


def _send_html(h, status, body):
    data = body.encode("utf-8")
    h.send_response(status)
    h.send_header("Content-Type", "text/html; charset=utf-8")
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)


def _send_json(h, status, body_obj):
    data = json.dumps(body_obj).encode("utf-8")
    h.send_response(status)
    h.send_header("Content-Type", "application/json; charset=utf-8")
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)


def main() -> int:
    return wc_server.run(
        skill_name="walkthrough",
        port_range=PORT_RANGE,
        handlers=Handlers(),
        static_dirs=[SHARED_STATIC_DIR],
    )


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest skills/walkthrough/tests/ -v`
Expected: all pass (14 from Task 1 + 13 here).

- [ ] **Step 5: Commit**

```bash
git add skills/walkthrough/server.py skills/walkthrough/tests/test_server.py
git commit -m "feat(walkthrough): server handlers for steps, threads, poll and session init"
```

---

## Task 3: submit + SSE stream + `ensure_server.sh`

**Files:**
- Modify: `skills/walkthrough/server.py` (replace the two `NotImplementedError` methods)
- Modify: `skills/walkthrough/tests/test_server.py` (append tests)
- Create: `skills/walkthrough/ensure_server.sh` (executable)

**Interfaces:**
- Consumes: `skills._shared.web_companion.events.append(events_dir, evt) -> str` (existing, returns event id); `registry.waiter(sid) -> threading.Event` (existing).
- Produces:
  - `Handlers.handle_submit` — accepts `{anchor: "step:<id>", type: "comment"|"reject", text, selected_text?, images?}`, appends a `user` message to the step's thread, queues an event, replies `202 {"event_id", "status": "queued"}`.
  - `Handlers._serve_stream` — SSE emitting `thread-changed`, `thread-deleted`, `steps-changed` (`{"generated_ts": n, "count": n}`), `heartbeat`.

- [ ] **Step 1: Write the failing tests**

Append to `skills/walkthrough/tests/test_server.py`:

```python
def test_submit_rejects_non_step_anchor(tmp_path):
    h, handler = Handlers(), make_handler()
    h.handle_submit(handler, make_dirs(tmp_path), {"anchor": "src/x.java:R:1", "text": "hi"})
    handler.send_response.assert_called_with(400)


def test_submit_rejects_bad_type(tmp_path):
    h, handler = Handlers(), make_handler()
    h.handle_submit(handler, make_dirs(tmp_path), {"anchor": "step:1", "type": "shout", "text": "hi"})
    handler.send_response.assert_called_with(400)


def test_submit_rejects_when_session_closed(tmp_path):
    dirs = make_dirs(tmp_path)
    (dirs["state_dir"] / "cancelled").write_text("{}")
    h, handler = Handlers(), make_handler()
    h.handle_submit(handler, dirs, {"anchor": "step:1", "text": "hi"})
    handler.send_response.assert_called_with(409)


def test_submit_queues_event_and_appends_user_message(tmp_path):
    dirs = make_dirs(tmp_path)
    h, handler = Handlers(), make_handler()
    h.handle_submit(handler, dirs, {"anchor": "step:2", "text": "is ordering guaranteed?"})
    handler.send_response.assert_called_with(202)
    body = json.loads(handler.wfile.getvalue())
    assert body["status"] == "queued"
    events = list((dirs["events_dir"]).iterdir())
    assert len(events) == 1
    evt = json.loads(events[0].read_text())
    assert evt["anchor"] == "step:2"
    assert evt["text"] == "is ordering guaranteed?"
    thread = threads_module.load(dirs["state_dir"] / "threads", "step:2")
    assert thread["messages"][0]["role"] == "user"
    assert thread["messages"][0]["source_event_id"] == f"user-{body['event_id']}"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest skills/walkthrough/tests/test_server.py -k submit -v`
Expected: 4 failed with `NotImplementedError`.

- [ ] **Step 3: Write the implementation**

In `skills/walkthrough/server.py`, add the import:

```python
from skills._shared.web_companion import events as events_module
```

Replace `handle_submit`:

```python
    def handle_submit(self, h: BaseHTTPRequestHandler, dirs: dict, payload: dict) -> None:
        state_dir = Path(dirs["state_dir"])
        if _is_terminal(state_dir):
            _send_text(h, 409, "session closed")
            return
        anchor = payload.get("anchor")
        ask_type = payload.get("type", "comment")
        text = payload.get("text", "")
        selected_text = payload.get("selected_text")
        images = payload.get("images", [])
        if not steps_module.valid_anchor(anchor):
            _send_text(h, 400, "bad anchor")
            return
        if ask_type not in ("comment", "reject"):
            _send_text(h, 400, "bad type")
            return
        if not isinstance(text, str) or not text.strip():
            _send_text(h, 400, "bad text")
            return
        eid = events_module.append(Path(dirs["events_dir"]), {
            "anchor": anchor,
            "type": ask_type,
            "text": text,
            "selected_text": selected_text,
            "images": images,
        })
        threads_module.append_message(state_dir / "threads", anchor, {
            "role": "user",
            "ts": int(time.time()),
            "text": text,
            "selected_text": selected_text,
            "images": images,
            "source_event_id": f"user-{eid}",
        })
        _send_json(h, 202, {"event_id": eid, "status": "queued"})
```

Replace `_serve_stream`:

```python
    def _serve_stream(self, h: BaseHTTPRequestHandler, dirs: dict) -> None:
        """SSE: thread-changed / thread-deleted / steps-changed / heartbeat.

        Self-correcting: every wake (and every 30s timeout) re-reads state from
        disk rather than trusting the wake signal, so a missed notify cannot
        strand the IDE on stale content.
        """
        sid = dirs.get("_sid")
        state_dir = Path(dirs["state_dir"])
        h.send_response(200)
        h.send_header("Content-Type", "text/event-stream")
        h.send_header("Cache-Control", "no-cache")
        h.send_header("Connection", "keep-alive")
        h.send_header("X-Accel-Buffering", "no")
        h.end_headers()

        def emit(name: str, obj: dict) -> bool:
            try:
                h.wfile.write(f"event: {name}\ndata: {json.dumps(obj)}\n\n".encode())
                h.wfile.flush()
                return True
            except (BrokenPipeError, ConnectionResetError):
                return False

        if not emit("connected", {}):
            return
        if not self._registry or not sid:
            return

        last_steps_ts = steps_module.generated_ts(state_dir)
        if last_steps_ts:
            doc = steps_module.load_steps(state_dir) or EMPTY_DOC
            if not emit("steps-changed", {"generated_ts": last_steps_ts,
                                          "count": len(doc.get("steps", []))}):
                return
        last_threads = self.threads_bulk(dirs)
        for anchor, info in last_threads.items():
            if not emit("thread-changed", {"anchor": anchor, **info}):
                return

        waiter = self._registry.waiter(sid)
        while True:
            woke = waiter.wait(timeout=30)
            steps_ts = steps_module.generated_ts(state_dir)
            if steps_ts != last_steps_ts:
                last_steps_ts = steps_ts
                doc = steps_module.load_steps(state_dir) or EMPTY_DOC
                if not emit("steps-changed", {"generated_ts": steps_ts,
                                              "count": len(doc.get("steps", []))}):
                    return
            new_threads = self.threads_bulk(dirs)
            for anchor in list(last_threads):
                if anchor not in new_threads and not emit("thread-deleted", {"anchor": anchor}):
                    return
            for anchor, info in new_threads.items():
                old = last_threads.get(anchor)
                if (old is None or old.get("version") != info.get("version")) \
                        and not emit("thread-changed", {"anchor": anchor, **info}):
                    return
            last_threads = new_threads
            if not woke and not emit("heartbeat", {}):
                return
```

Create `skills/walkthrough/ensure_server.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
export PLUGIN_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null && pwd)"
export SKILL="walkthrough"
export MODULE="skills.walkthrough.server"
export BANNER="walkthrough-server v1"
exec "$PLUGIN_ROOT/skills/_shared/web_companion/ensure_server.sh"
```

- [ ] **Step 4: Run tests and start the server for real**

Run: `python3 -m pytest skills/walkthrough/tests/ -v`
Expected: all pass (31 total).

Run: `chmod +x skills/walkthrough/ensure_server.sh && skills/walkthrough/ensure_server.sh`
Expected: JSON on stdout containing `"url"` and a port in 54660–54680; `~/.claude/walkthrough/server.json` now exists.

Run: `curl -s "$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/walkthrough/server.json")))["url"])')/health"`
Expected: `walkthrough-server v1`

- [ ] **Step 5: Commit**

```bash
git add skills/walkthrough/server.py skills/walkthrough/ensure_server.sh skills/walkthrough/tests/test_server.py
git commit -m "feat(walkthrough): ask submit, SSE stream and server launcher"
```

---
## Task 4: `SKILL.md` — the skill itself

**Files:**
- Create: `skills/walkthrough/SKILL.md`
- Create: `skills/walkthrough/README.md`
- Create: `skills/walkthrough/tests/test_skill_doc.py`

**Interfaces:**
- Consumes: everything from Tasks 1–3 (endpoints, `steps.py` helpers, `ensure_server.sh`).
- Produces: the prose contract Claude follows. No code depends on it, so it is guarded by a thin doc test that keeps the load-bearing sections from silently disappearing.

- [ ] **Step 1: Write the failing doc test**

Create `skills/walkthrough/tests/test_skill_doc.py`:

```python
from pathlib import Path

SKILL = Path(__file__).resolve().parent.parent / "SKILL.md"

REQUIRED_SECTIONS = [
    "## Invocation",
    "## On every invocation: ensure the server is running",
    "## Create a session",
    "## Generate the steps",
    "## Generation contract",
    "## Arm the watcher",
    "## Mode D — handling a watcher event",
    "## Response style guide",
    "## Edge cases",
]


def test_frontmatter_declares_name_and_description():
    text = SKILL.read_text()
    assert text.startswith("---\n")
    frontmatter = text.split("---", 2)[1]
    assert "name: walkthrough" in frontmatter
    assert "description:" in frontmatter


def test_required_sections_present():
    text = SKILL.read_text()
    missing = [s for s in REQUIRED_SECTIONS if s not in text]
    assert missing == [], f"SKILL.md missing sections: {missing}"


def test_generation_contract_states_the_hard_rules():
    text = SKILL.read_text()
    for rule in ["5–12 steps", "snippet", "execution order", "edit-site", "cross-block"]:
        assert rule in text, f"generation contract missing rule: {rule}"


def test_documents_step_anchor_form():
    assert "step:<id>" in SKILL.read_text()
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m pytest skills/walkthrough/tests/test_skill_doc.py -v`
Expected: 4 failed with `FileNotFoundError: .../skills/walkthrough/SKILL.md`.

- [ ] **Step 3: Write `SKILL.md`**

Create `skills/walkthrough/SKILL.md`:

````markdown
---
name: walkthrough
description: Answer a codebase question as an ordered sequence of anchored steps walked in IntelliJ, not as terminal prose. Claude generates the steps, the IDE plugin walks the user through them, and the user can ask a question on any step which Claude answers in place. Triggered by /walkthrough <question>. Watcher events are WEBCOMPANION_EVENT / WEBCOMPANION_FINISHED / WEBCOMPANION_CANCELLED.
allowed-tools:
  - Bash
  - Read
  - Write
  - Grep
  - Glob
---

# /walkthrough — guided code tours in IntelliJ

Turn a question about a codebase into a path through it: 5–12 ordered steps, each
anchored to a real `file:line`, walked step-by-step in IntelliJ. The user steps
forward and backward, and can ask a question on any step; you answer into that
step in place.

Use this instead of answering in terminal prose whenever the honest answer is
"here is the path through the code". No code is modified — this is a tool for
*understanding*, and in v1 you never edit files as part of a tour.

## Invocation

```
/walkthrough <question>
/walkthrough --diff <question>
/walkthrough --diff <ref>..HEAD <question>
```

- Plain form: a tour over existing code. Works for both "how does X work" and
  "how would I add X" — the difference shows up in where the last steps land.
- `--diff` form: a tour over a change that already exists (uncommitted working
  copy by default, or the given ref range). You narrate the change; you do not
  make it.

## On every invocation: ensure the server is running

Run this once at the top of every invocation, before anything else:

```bash
PLUGIN_ROOT="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}"
"$PLUGIN_ROOT/skills/walkthrough/ensure_server.sh"
```

`$CLAUDE_PLUGIN_ROOT` is **not** exported into the Bash tool's shell, so it is
resolved from the plugin marketplace registry as a fallback. Idempotent and fast
(<100 ms when already up). Do **not** use `run_in_background: true`. If it exits
non-zero, surface the stderr to the user and stop.

## Create a session

Read `$HOME/.claude/walkthrough/server.json` for the server URL, then create the
session. Do this **before** exploring — the session directory is where the steps
will be written, and creating it early means a slow exploration doesn't leave the
user staring at nothing.

```bash
SERVER_URL=$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/walkthrough/server.json")))["url"])')
curl -sf -X POST "$SERVER_URL/api/sessions" \
  -H 'Content-Type: application/json' \
  -d "$(printf '{"cwd": "%s", "question": "%s", "kind": "%s"}' "$PWD" "$QUESTION" "$KIND")"
```

`kind` is `explain` or `diff`. The response contains `sid`, `slug`, `url`,
`state_dir`, `events_dir`, `consumed_dir`, `title`. Save `sid`, `state_dir`,
`events_dir`, `consumed_dir` for the rest of this turn.

**One active tour per project.** If `/api/sessions?cwd=$PWD` already lists a
walkthrough session, cancel it first (`POST /s/<old_sid>/api/cancel`) so the
plugin switches cleanly instead of showing two tours.

## Generate the steps

Explore, then write the step list. For `--diff` tours, start from
`git diff` / `git diff <range>` and read the touched files around each hunk.

Write the document with the `Write` tool to `<state_dir>/.steps.draft.json`, then
validate and install it (never hand-write `steps.json` — validation is what keeps
a malformed tour out of the IDE):

```bash
PLUGIN_ROOT=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/walkthrough/server.json")))["plugin_root"])')
PYTHONPATH="$PLUGIN_ROOT" STATE_DIR="$STATE_DIR" python3 - <<'PY'
import json, os, time
from pathlib import Path
from skills.walkthrough.steps import write_steps
sd = Path(os.environ["STATE_DIR"])
doc = json.loads((sd / ".steps.draft.json").read_text())
doc["generated_ts"] = int(time.time())
write_steps(sd, doc)
print(f"wrote {len(doc['steps'])} steps")
PY
```

If it raises `ValueError`, the message lists every problem. Fix the draft and
re-run — do not create a second session.

Document shape:

```json
{
  "question": "<the user's question, verbatim>",
  "kind": "explain",
  "generated_ts": 0,
  "steps": [
    {"id": 1,
     "title": "Where sharing starts",
     "file": "src/main/java/com/montblanc/api/ProposalShareController.java",
     "line": 42,
     "snippet": "return shareService.share(id);",
     "role": "context",
     "markdown": "The REST entry point. Everything below hangs off this call."}
  ]
}
```

- `file` is **project-relative** (no leading `/`, no `..`).
- `snippet` is the **verbatim text of that line**, copied from the file you read.
  It is what re-anchors the step after the file shifts; a wrong snippet makes the
  step stale in the IDE.
- `role` is `context` (grey badge), `seam` (blue — where behaviour is extended),
  or `edit-site` (green — where new code goes).
- `id` values are positive integers, unique, in walking order.

## Generation contract

Hard rules. A tour that breaks one of these is a defect, not a style choice.

- **5–12 steps.** Fewer than 5 means the question deserved a paragraph in
  terminal — answer it there instead and do not create a tour. More than 12 means
  the question is too broad: ask the user to narrow it. If they decline, build the
  best 12-step spine and say what you left out.
- **Every step is a real anchor.** `file` + `line` + verbatim `snippet`. Never
  anchor to a file you did not `Read` in this turn. Never guess a line number.
- **Execution order, not file order.** Follow how control and data actually flow:
  entry point → gate → dispatch → implementation → data model → seam. Grouping
  steps by package is a failure mode.
- **Each step earns its place.** The markdown says *what happens here* and *why it
  matters for the question asked* — 2–5 sentences. It is not a file summary.
- **The last step answers the question.** For "how to add X", the final steps carry
  `role: "edit-site"` and name the exact file or directory for the new code, the
  registration point, and the test that would prove it. Concretely named — never
  "somewhere in the workflow package".
- **Link references inline.** `[evaluate](src/main/java/.../PreconditionRegistry.java:30)`
  for code, absolute URLs for tickets. The IDE renders these clickable.
- **Titles ≤ 6 words**, plain-text noun phrases — they are rail rows and HUD text.
- **Cross-block re-pass.** After drafting all steps, re-read them together and fix
  what only shows up in aggregate: a step repeating its neighbour, a jump with a
  missing bridge, a title that no longer matches its body, an ordering that only
  made sense while you were writing it. Do this **before** writing `steps.json` —
  steps are frozen once written.

## Tell the user where to walk

One sentence in terminal, then stop:

**"Walkthrough ready — <N> steps for `<question>`. Open the project in IntelliJ; step forward with the walkthrough shortcut and ask on any step."**

## Arm the watcher

After telling the user, start the watcher with `Monitor` (`persistent: true`):

```bash
PLUGIN_ROOT=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/walkthrough/server.json")))["plugin_root"])')
SKILL=walkthrough \
SID="<sid>" \
STATE_DIR="<state_dir>" \
EVENTS_DIR="<events_dir>" \
CONSUMED_DIR="<consumed_dir>" \
"$PLUGIN_ROOT/skills/_shared/web_companion/watcher.sh"
```

Banners: `WEBCOMPANION_EVENT skill=walkthrough sid=<sid> event_id=<id>`,
`WEBCOMPANION_FINISHED`, `WEBCOMPANION_CANCELLED`. Each stdout line wakes you once;
the watcher stays alive across many events.

Then append a record to `~/.claude/walkthrough/pending-${CLAUDE_CODE_SESSION_ID}.json`
with `{sid, question, state_dir, events_dir, consumed_dir}` so terminal
cancellation can find it later.

## Mode D — handling a watcher event

### `WEBCOMPANION_EVENT` (a question on a step)

1. **Parse the banner** for `sid` and `event_id`.
2. **Read the payload** between `---payload---` and `---end---`:
   - `anchor` — always `step:<id>`.
   - `type` — `"comment"` (or `"reject"` if the user disagrees with a prior reply).
   - `text` — the question.
   - `images` — `[{token, path}]`; `Read` each path before answering if non-empty.
3. **Compose the answer:**
   - Read `<state_dir>/steps.json` and locate the step by id — its `file`, `line`,
     and `markdown` are the subject of the question.
   - `Read` the anchored file around that line. Use `Grep`/`Glob` for anything the
     question pulls in beyond it.
   - Skim the other steps' threads (`ls <state_dir>/threads/`) as background. They
     are READ-ONLY input; never write into another step's thread.
   - 2–4 sentences, code-aware, markdown links inline, fenced code blocks for
     suggested snippets. **Do not modify code.**
4. **Append to that step's thread.** Route content through files so nothing is
   shell-quoted:

   a. `Write` the answer (raw markdown) to `<state_dir>/.reply.md`.

   b. `Write` `<state_dir>/.reply.meta.json`:
   ```json
   {"anchor": "step:3", "title": "<short headline>", "source_event_id": "<event_id>"}
   ```

   c. Run:
   ```bash
   PLUGIN_ROOT=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/walkthrough/server.json")))["plugin_root"])')
   PYTHONPATH="$PLUGIN_ROOT" STATE_DIR="$STATE_DIR" python3 - <<'PY'
   import json, os, time
   from pathlib import Path
   from skills.interactive_review.threads import append_message
   sd = Path(os.environ["STATE_DIR"])
   meta = json.loads((sd / ".reply.meta.json").read_text())
   text = (sd / ".reply.md").read_text()
   append_message(sd / "threads", meta["anchor"], {
       "role": "claude",
       "ts": int(time.time()),
       "text": text,
       "source_event_id": meta["source_event_id"],
   }, title=(meta.get("title") or None))
   PY
   ```
   `append_message` handles anchor→filename encoding and `source_event_id` dedup.
5. **Write the ack:** `<consumed_dir>/<event_id>.ack` (empty file).
6. **End your turn. No terminal output.** The watcher stays armed.

**Never rewrite `steps.json` in response to an event.** Steps are frozen. If the
answer really needs a different path through the code, say so in the reply and
offer to run a new `/walkthrough`.

### `WEBCOMPANION_FINISHED`

1. Terminal: *"Walkthrough for `<question>` closed."*
2. Remove the entry from the pending registry.

### `WEBCOMPANION_CANCELLED`

1. Terminal: *"Walkthrough for `<question>` cancelled."*
2. Remove the entry from the pending registry.

## Response style guide

- **Self-contained synthesis.** Each reply answers *all* questions asked on that
  step so far. The IDE renders only your most recent reply; older ones are stored
  for audit but not displayed.
- **Short.** 2–4 sentences in most cases.
- **Code-aware.** Name the actual variables, methods, and lines.
- **Cite steps by number** when the answer lives elsewhere in the tour ("that's
  step 6").
- **Suggest, don't ask.** If a fix is warranted, show it as a code block. The user
  applies it.
- **Honest uncertainty.** Name exactly what you would need to know. Don't hedge.
- **Headline title.** Pass a `title` to `append_message`: plain text, ≤ 6 words,
  a noun phrase. Refresh it each answer.

## Terminal cancellation

If the user says "scrap it" / "stop the walkthrough" while a watcher is armed:

1. Read `~/.claude/walkthrough/pending-${CLAUDE_CODE_SESSION_ID}.json`.
2. For each entry: `printf '{"reason":"user-cancelled-terminal"}' > "$STATE_DIR/cancelled"`.
3. The watcher emits `WEBCOMPANION_CANCELLED` on its next tick; handle per Mode D.

## Edge cases

- **Question too broad** — would exceed 12 steps. Ask once for a narrower question;
  if refused, build the best 12-step spine and say what you dropped.
- **Zero anchors found** — nothing in the codebase matches. Do **not** write
  `steps.json`. Cancel the session, and in terminal say what you searched for and
  what you found instead.
- **`--diff` with an empty diff** — say so; do not create a session.
- **Validation failure** — `write_steps` lists every problem. Fix the draft, re-run.
  Never bypass validation by writing `steps.json` directly.
- **Server unreachable** — re-run `ensure_server.sh` (it restarts the server) and
  retry the failed request once.
- **Tour lost** — tours are ephemeral by design. If the session ends, say so
  plainly and offer to regenerate.
- **Malformed event payload** — no-op, but still write the `.ack` so the event
  isn't re-emitted forever.

## Token budget

Generation is the expensive part: read what you need to anchor steps honestly, and
stop. Each wake-up afterwards is one question on one step — answer that, 2–4
sentences, and end the turn.
````

- [ ] **Step 4: Write `README.md`**

Create `skills/walkthrough/README.md`:

```markdown
# walkthrough

Guided code tours, walked in IntelliJ. `/walkthrough <question>` generates 5–12
anchored steps; the IDE plugin (`intellij-plugin-spike`) walks the user through
them and posts per-step questions back for Claude to answer in place.

- Skill contract: `SKILL.md`
- Design: `docs/superpowers/specs/2026-07-22-walkthrough-design.md`
- Server: `server.py` (handlers over `skills/_shared/web_companion`), port range
  54660–54680, state root `~/.claude/walkthrough/`.
- Per-session state lives in `<project>/.claude/walkthrough/<sid>/state/`:
  `steps.json` (frozen after generation), `threads/` (one file per step anchor),
  `events/`, `consumed/`.

Run the tests from the repo root:

```bash
python3 -m pytest skills/walkthrough/tests/ -v
```
```

- [ ] **Step 5: Run the doc tests**

Run: `python3 -m pytest skills/walkthrough/tests/ -v`
Expected: all pass (31 + 4 doc tests = 35).

- [ ] **Step 6: Commit**

```bash
git add skills/walkthrough/SKILL.md skills/walkthrough/README.md skills/walkthrough/tests/test_skill_doc.py
git commit -m "feat(walkthrough): SKILL.md generation contract and watcher protocol"
```

---
## Task 5: `WalkthroughStep` + `WalkthroughDoc` (parsing)

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughStep.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughDoc.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/WalkthroughDocTest.java`

**Interfaces:**
- Consumes: Gson (already a dependency).
- Produces:
  - `record WalkthroughStep(int id, String title, String file, int line, String snippet, WalkthroughStep.Role role, String markdown)` with `String anchor()` → `"step:<id>"` and `static Role Role.from(String)`.
  - `enum WalkthroughStep.Role { CONTEXT, SEAM, EDIT_SITE }`
  - `record WalkthroughDoc(String question, String kind, long generatedTs, List<WalkthroughStep> steps)` with:
    - `static WalkthroughDoc parse(String json)` — never throws; returns `EMPTY` on garbage, skips malformed steps.
    - `static final WalkthroughDoc EMPTY`
    - `boolean isEmpty()`
    - `Optional<WalkthroughStep> byId(int id)`
    - `int indexOfId(int id)` — `-1` when absent.

- [ ] **Step 1: Write the failing test**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/WalkthroughDocTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WalkthroughDocTest {

    private static final String JSON = """
        {"question":"how sharing is gated","kind":"explain","generated_ts":1784720471,
         "steps":[
           {"id":1,"title":"Entry point","file":"src/Api.java","line":42,
            "snippet":"return service.share(id);","role":"context","markdown":"REST entry."},
           {"id":2,"title":"The gate","file":"src/Engine.java","line":114,
            "snippet":"var failures = preconditions.evaluate(p);","role":"seam","markdown":"Runs beans."},
           {"id":3,"title":"Where yours goes","file":"src/precondition/","line":1,
            "snippet":"package com.montblanc.precondition;","role":"edit-site","markdown":"Add here."}]}
        """;

    @Test void parsesAllFields() {
        WalkthroughDoc doc = WalkthroughDoc.parse(JSON);
        assertEquals("how sharing is gated", doc.question());
        assertEquals("explain", doc.kind());
        assertEquals(1784720471L, doc.generatedTs());
        assertEquals(3, doc.steps().size());
        WalkthroughStep s = doc.steps().get(1);
        assertEquals(2, s.id());
        assertEquals("The gate", s.title());
        assertEquals("src/Engine.java", s.file());
        assertEquals(114, s.line());
        assertEquals("var failures = preconditions.evaluate(p);", s.snippet());
        assertEquals(WalkthroughStep.Role.SEAM, s.role());
        assertEquals("Runs beans.", s.markdown());
    }

    @Test void mapsEditSiteRole() {
        assertEquals(WalkthroughStep.Role.EDIT_SITE, WalkthroughDoc.parse(JSON).steps().get(2).role());
    }

    @Test void unknownRoleFallsBackToContext() {
        String json = """
            {"question":"q","kind":"explain","generated_ts":1,"steps":[
              {"id":1,"title":"t","file":"a.java","line":1,"snippet":"x","role":"wishful","markdown":"m"}]}
            """;
        assertEquals(WalkthroughStep.Role.CONTEXT, WalkthroughDoc.parse(json).steps().get(0).role());
    }

    @Test void anchorIsStepId() {
        assertEquals("step:2", WalkthroughDoc.parse(JSON).steps().get(1).anchor());
    }

    @Test void byIdAndIndexOfId() {
        WalkthroughDoc doc = WalkthroughDoc.parse(JSON);
        assertEquals("The gate", doc.byId(2).orElseThrow().title());
        assertTrue(doc.byId(99).isEmpty());
        assertEquals(1, doc.indexOfId(2));
        assertEquals(-1, doc.indexOfId(99));
    }

    @Test void garbageParsesToEmpty() {
        assertTrue(WalkthroughDoc.parse("{not json").isEmpty());
        assertTrue(WalkthroughDoc.parse("").isEmpty());
        assertTrue(WalkthroughDoc.parse("[]").isEmpty());
        assertTrue(WalkthroughDoc.parse(null).isEmpty());
    }

    @Test void emptyStepListIsEmptyDoc() {
        String json = """
            {"question":"q","kind":"explain","generated_ts":1,"steps":[]}
            """;
        assertTrue(WalkthroughDoc.parse(json).isEmpty());
    }

    @Test void malformedStepsAreSkippedNotFatal() {
        String json = """
            {"question":"q","kind":"explain","generated_ts":1,"steps":[
              {"id":1,"title":"ok","file":"a.java","line":1,"snippet":"x","role":"context","markdown":"m"},
              {"id":0,"title":"bad id","file":"a.java","line":1,"snippet":"x","role":"context","markdown":"m"},
              {"id":3,"title":"no file","line":1,"snippet":"x","role":"context","markdown":"m"},
              "not-an-object"]}
            """;
        WalkthroughDoc doc = WalkthroughDoc.parse(json);
        assertEquals(1, doc.steps().size());
        assertEquals("ok", doc.steps().get(0).title());
    }

    @Test void stepsKeepDocumentOrder() {
        String json = """
            {"question":"q","kind":"explain","generated_ts":1,"steps":[
              {"id":5,"title":"five","file":"a.java","line":1,"snippet":"x","role":"context","markdown":"m"},
              {"id":2,"title":"two","file":"a.java","line":2,"snippet":"y","role":"context","markdown":"m"}]}
            """;
        WalkthroughDoc doc = WalkthroughDoc.parse(json);
        assertEquals(5, doc.steps().get(0).id());
        assertEquals(2, doc.steps().get(1).id());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd intellij-plugin-spike && ./gradlew test --tests '*WalkthroughDocTest*'`
Expected: compilation failure — `cannot find symbol: class WalkthroughDoc`.

- [ ] **Step 3: Write the implementation**

Create `WalkthroughStep.java`:

```java
package com.petros.ireview;

/**
 * One stop on a guided tour. Immutable; produced by {@link WalkthroughDoc#parse}.
 *
 * <p>{@code line} is a hint only — {@code snippet} is the verbatim text of the
 * anchored line and is what {@link AnchorResolver} uses to re-locate the step
 * after the file shifts.
 */
public record WalkthroughStep(int id, String title, String file, int line,
                              String snippet, Role role, String markdown) {

    public enum Role {
        /** Explains existing behaviour. */
        CONTEXT,
        /** Where behaviour is extended without editing this code. */
        SEAM,
        /** Where new code actually goes. */
        EDIT_SITE;

        /** Unknown / missing roles degrade to CONTEXT rather than failing the tour. */
        public static Role from(String raw) {
            if (raw == null) return CONTEXT;
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "seam" -> SEAM;
                case "edit-site", "edit_site" -> EDIT_SITE;
                default -> CONTEXT;
            };
        }
    }

    /** Thread anchor for this step — must match the server's {@code step:<id>} form. */
    public String anchor() {
        return "step:" + id;
    }
}
```

Create `WalkthroughDoc.java`:

```java
package com.petros.ireview;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A parsed steps.json. Parsing is total: malformed input yields {@link #EMPTY}
 * and individual malformed steps are skipped, so a bad document degrades the
 * tour instead of breaking the IDE.
 */
public record WalkthroughDoc(String question, String kind, long generatedTs,
                             List<WalkthroughStep> steps) {

    public static final WalkthroughDoc EMPTY =
        new WalkthroughDoc("", "", 0L, List.of());

    public WalkthroughDoc {
        steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    public boolean isEmpty() { return steps.isEmpty(); }

    public Optional<WalkthroughStep> byId(int id) {
        return steps.stream().filter(s -> s.id() == id).findFirst();
    }

    /** Position of the step with this id in walking order, or -1. */
    public int indexOfId(int id) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id() == id) return i;
        }
        return -1;
    }

    public static WalkthroughDoc parse(String json) {
        if (json == null || json.isBlank()) return EMPTY;
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return EMPTY;
            root = parsed.getAsJsonObject();
        } catch (Exception e) {
            return EMPTY;
        }
        JsonElement stepsEl = root.get("steps");
        if (stepsEl == null || !stepsEl.isJsonArray()) return EMPTY;
        List<WalkthroughStep> steps = new ArrayList<>();
        for (JsonElement el : stepsEl.getAsJsonArray()) {
            WalkthroughStep step = parseStep(el);
            if (step != null) steps.add(step);
        }
        if (steps.isEmpty()) return EMPTY;
        return new WalkthroughDoc(str(root, "question"), str(root, "kind"),
            num(root, "generated_ts"), steps);
    }

    private static WalkthroughStep parseStep(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        int id = (int) num(o, "id");
        int line = (int) num(o, "line");
        String file = str(o, "file");
        String snippet = str(o, "snippet");
        if (id < 1 || line < 1 || file.isEmpty() || snippet.isEmpty()) return null;
        return new WalkthroughStep(id, str(o, "title"), file, line, snippet,
            WalkthroughStep.Role.from(str(o, "role")), str(o, "markdown"));
    }

    private static String str(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return "";
        return v.getAsString();
    }

    private static long num(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return 0L;
        try {
            return v.getAsLong();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd intellij-plugin-spike && ./gradlew test --tests '*WalkthroughDocTest*'`
Expected: 9 tests passed.

- [ ] **Step 5: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughStep.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughDoc.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/WalkthroughDocTest.java
git commit -m "feat(plugin): walkthrough step model and steps.json parser"
```

---

## Task 6: `WalkthroughSessionClient`

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughSessionClient.java`
- Modify: `intellij-plugin-spike/src/test/java/com/petros/ireview/FakeReviewServer.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/WalkthroughSessionClientTest.java`

**Interfaces:**
- Consumes: `WalkthroughDoc`, `WalkthroughStep` (Task 5); existing `SseClient.connect(HttpClient, URI, Consumer<Event>, Consumer<Throwable>)` and `SseClient.Event` (`name()`, `data()`).
- Produces:
  - `record SessionInfo(String sid, String title, String stateDir)`
  - `record ThreadState(String synthesis, int version, String title, String question)`
  - `enum State { DORMANT, CONNECTING, ACTIVE, DISCONNECTED, PAUSED, ENDED }`
  - `interface Listener { onAttached(SessionInfo); onDetached(); onStepsChanged(WalkthroughDoc); onThreadChanged(String anchor, ThreadState); onPendingChanged(String anchor, boolean); onStateChanged(State); }` — all `default` no-ops.
  - `WalkthroughSessionClient(String baseUrl, String projectCwd, Duration pollInterval)`
  - `start()`, `stop()`, `addListener(Listener)`, `removeListener(Listener)`
  - `Optional<SessionInfo> currentSession()`, `State state()`, `WalkthroughDoc doc()`
  - `Optional<ThreadState> threadFor(String anchor)`, `boolean isPending(String anchor)`
  - `CompletableFuture<Void> postAsk(int stepId, String text)`
  - `CompletableFuture<Void> cancelSession()`

- [ ] **Step 1: Extend the fake server**

In `FakeReviewServer.java`, add the field next to `threadsJson`:

```java
    /** Body returned by GET /s/<sid>/steps.json. */
    public volatile String stepsJson = "{\"steps\":[]}";
```

and add this branch at the top of `handleSession`, immediately after `String path = ex.getRequestURI().getPath();`:

```java
        if (path.endsWith("/steps.json")) {
            byte[] body = stepsJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            return;
        }
```

(The existing `/threads.json` branch stays as-is; `steps.json` must be checked first only in the sense that both are distinct suffixes — order does not matter, but keep them adjacent for readability.)

- [ ] **Step 2: Write the failing test**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/WalkthroughSessionClientTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class WalkthroughSessionClientTest {

    private static final String STEPS = """
        {"question":"q","kind":"explain","generated_ts":7,"steps":[
          {"id":1,"title":"one","file":"a.java","line":1,"snippet":"x","role":"context","markdown":"m"},
          {"id":2,"title":"two","file":"b.java","line":9,"snippet":"y","role":"seam","markdown":"m2"}]}
        """;

    private static void await(BooleanSupplier cond) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("condition not met within 5s");
    }

    private static String sessionsRow(String sid) {
        return "[{\"sid\":\"" + sid + "\",\"title\":\"how sharing is gated\","
             + "\"state_dir\":\"/tmp/state\"}]";
    }

    @Test void attachesAndLoadsSteps() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson = sessionsRow("wt1");
            server.stepsJson = STEPS;
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            AtomicReference<WalkthroughDoc> seen = new AtomicReference<>();
            WalkthroughSessionClient client = new WalkthroughSessionClient(
                server.baseUrl(), "/proj", Duration.ofMillis(100));
            client.addListener(new WalkthroughSessionClient.Listener() {
                @Override public void onStepsChanged(WalkthroughDoc doc) { seen.set(doc); }
            });
            client.start();
            try {
                await(() -> seen.get() != null && seen.get().steps().size() == 2);
                assertEquals("how sharing is gated", client.currentSession().orElseThrow().title());
                assertEquals(2, client.doc().steps().size());
                assertEquals("step:2", client.doc().steps().get(1).anchor());
            } finally {
                client.stop();
            }
        }
    }

    @Test void threadChangedEventUpdatesCacheAndClearsPending() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson = sessionsRow("wt2");
            server.stepsJson = STEPS;
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            List<String> pendingEvents = new CopyOnWriteArrayList<>();
            WalkthroughSessionClient client = new WalkthroughSessionClient(
                server.baseUrl(), "/proj", Duration.ofMillis(100));
            client.addListener(new WalkthroughSessionClient.Listener() {
                @Override public void onPendingChanged(String anchor, boolean pending) {
                    pendingEvents.add(anchor + "=" + pending);
                }
            });
            client.start();
            try {
                await(() -> client.currentSession().isPresent());
                client.postAsk(2, "is ordering guaranteed?").join();
                await(() -> client.isPending("step:2"));
                assertTrue(server.lastSubmitBody.contains("\"anchor\":\"step:2\""));
                assertTrue(server.lastSubmitBody.contains("is ordering guaranteed?"));

                server.pushSseEvent("thread-changed",
                    "{\"anchor\":\"step:2\",\"latest_synthesis\":\"no, bean order\","
                    + "\"version\":1,\"title\":\"Ordering\",\"question\":\"is ordering guaranteed?\"}");
                await(() -> client.threadFor("step:2").isPresent() && !client.isPending("step:2"));
                var t = client.threadFor("step:2").orElseThrow();
                assertEquals("no, bean order", t.synthesis());
                assertEquals(1, t.version());
                assertEquals("Ordering", t.title());
                assertTrue(pendingEvents.contains("step:2=true"));
                assertTrue(pendingEvents.contains("step:2=false"));
            } finally {
                client.stop();
            }
        }
    }

    @Test void stepsChangedEventReloadsDoc() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson = sessionsRow("wt3");
            server.stepsJson = "{\"question\":\"q\",\"kind\":\"explain\",\"generated_ts\":0,\"steps\":[]}";
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            List<Integer> sizes = new CopyOnWriteArrayList<>();
            WalkthroughSessionClient client = new WalkthroughSessionClient(
                server.baseUrl(), "/proj", Duration.ofMillis(100));
            client.addListener(new WalkthroughSessionClient.Listener() {
                @Override public void onStepsChanged(WalkthroughDoc doc) { sizes.add(doc.steps().size()); }
            });
            client.start();
            try {
                await(() -> client.currentSession().isPresent());
                server.stepsJson = STEPS;
                server.pushSseEvent("steps-changed", "{\"generated_ts\":7,\"count\":2}");
                await(() -> client.doc().steps().size() == 2);
                assertTrue(sizes.contains(2));
            } finally {
                client.stop();
            }
        }
    }

    @Test void endedSessionFreezesAndRefusesAsks() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson = sessionsRow("wt4");
            server.stepsJson = STEPS;
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            WalkthroughSessionClient client = new WalkthroughSessionClient(
                server.baseUrl(), "/proj", Duration.ofMillis(100));
            client.start();
            try {
                await(() -> client.state() == WalkthroughSessionClient.State.ACTIVE);
                server.ended = true;
                server.endedReason = "finished";
                await(() -> client.state() == WalkthroughSessionClient.State.ENDED);
                assertThrows(Exception.class, () -> client.postAsk(1, "too late").join());
            } finally {
                client.stop();
            }
        }
    }

    @Test void detachesWhenNoSession() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson = "[]";
            List<String> events = new ArrayList<>();
            WalkthroughSessionClient client = new WalkthroughSessionClient(
                server.baseUrl(), "/proj", Duration.ofMillis(100));
            client.addListener(new WalkthroughSessionClient.Listener() {
                @Override public void onDetached() { events.add("detached"); }
            });
            client.start();
            try {
                await(() -> client.state() == WalkthroughSessionClient.State.DORMANT);
                assertTrue(client.currentSession().isEmpty());
                assertTrue(client.doc().isEmpty());
            } finally {
                client.stop();
            }
        }
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd intellij-plugin-spike && ./gradlew test --tests '*WalkthroughSessionClientTest*'`
Expected: compilation failure — `cannot find symbol: class WalkthroughSessionClient`.

- [ ] **Step 4: Write the implementation**

Create `WalkthroughSessionClient.java`:

```java
package com.petros.ireview;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Talks to the walkthrough server: discovers a session by cwd, loads steps.json,
 * opens an SSE stream for per-step threads, and posts questions.
 *
 * <p>Same lifecycle model as {@link ReviewSessionClient} — DORMANT → CONNECTING →
 * ACTIVE, with PAUSED when the watcher heartbeat goes stale and ENDED as a
 * one-way latch once the server reports the session terminal. Listeners fire on
 * the SSE / poll threads; bridge to the EDT in UI code.
 */
public final class WalkthroughSessionClient {

    public record SessionInfo(String sid, String title, String stateDir) {}
    public record ThreadState(String synthesis, int version, String title, String question) {}

    public enum State { DORMANT, CONNECTING, ACTIVE, DISCONNECTED, PAUSED, ENDED }

    public interface Listener {
        default void onAttached(SessionInfo info) {}
        default void onDetached() {}
        default void onStepsChanged(WalkthroughDoc doc) {}
        default void onThreadChanged(String anchor, ThreadState thread) {}
        default void onPendingChanged(String anchor, boolean pending) {}
        default void onStateChanged(State state) {}
    }

    private static final Duration STALE_AFTER = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private final String baseUrl;
    private final String projectCwd;
    private final Duration pollInterval;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);
    private final ExecutorService sseExec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "walkthrough-sse");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong sseGen = new AtomicLong();
    private final AtomicLong submitSeq = new AtomicLong();
    private final Object stateLock = new Object();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, ThreadState> threads = new ConcurrentHashMap<>();
    private final Map<String, Long> pending = new ConcurrentHashMap<>();
    private final AtomicReference<WalkthroughDoc> doc = new AtomicReference<>(WalkthroughDoc.EMPTY);

    private volatile boolean closed = false;
    private volatile boolean endedLatched = false;
    private volatile State state = State.DORMANT;
    private volatile SessionInfo current = null;
    private volatile Future<?> sseTask = null;
    private volatile ScheduledFuture<?> discoverTask = null;

    public WalkthroughSessionClient(String baseUrl, String projectCwd, Duration pollInterval) {
        this.baseUrl = baseUrl;
        this.projectCwd = projectCwd;
        this.pollInterval = pollInterval;
    }

    public void start() {
        discoverTask = exec.scheduleWithFixedDelay(this::pollDiscover,
            0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        closed = true;
        sseGen.incrementAndGet();
        if (discoverTask != null) discoverTask.cancel(true);
        if (sseTask != null) sseTask.cancel(true);
        exec.shutdownNow();
        sseExec.shutdownNow();
        setState(State.DORMANT);
    }

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    public Optional<SessionInfo> currentSession() { return Optional.ofNullable(current); }

    public State state() { return state; }

    public WalkthroughDoc doc() { return doc.get(); }

    public Optional<ThreadState> threadFor(String anchor) {
        return Optional.ofNullable(threads.get(anchor));
    }

    public boolean isPending(String anchor) { return pending.containsKey(anchor); }

    /** POST a question on a step to /s/&lt;sid&gt;/api/submit. */
    public CompletableFuture<Void> postAsk(int stepId, String text) {
        SessionInfo s = current;
        if (s == null) return CompletableFuture.failedFuture(new IllegalStateException("no session"));
        if (state == State.PAUSED || state == State.ENDED) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Claude session is gone — re-run /walkthrough to resume"));
        }
        String anchor = "step:" + stepId;
        long token = submitSeq.incrementAndGet();
        markPending(anchor, token);
        Map<String, String> payload = new java.util.LinkedHashMap<>();
        payload.put("anchor", anchor);
        payload.put("type", "comment");
        payload.put("text", text);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + s.sid() + "/api/submit"))
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .whenComplete((resp, err) -> {
                if (err != null || (resp != null && resp.statusCode() / 100 != 2)) {
                    clearPendingIfToken(anchor, token);
                }
            })
            .thenAccept(resp -> {
                if (resp.statusCode() / 100 != 2) {
                    throw new RuntimeException("ask failed: HTTP " + resp.statusCode());
                }
            });
    }

    /** POST to /s/&lt;sid&gt;/api/cancel — ends the walkthrough. */
    public CompletableFuture<Void> cancelSession() {
        SessionInfo s = current;
        if (s == null) return CompletableFuture.failedFuture(new IllegalStateException("no session"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + s.sid() + "/api/cancel"))
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .thenAccept(resp -> {
                if (resp.statusCode() / 100 != 2) {
                    throw new RuntimeException("cancel failed: HTTP " + resp.statusCode());
                }
                handleNoSession();
            });
    }

    // --- internal ---

    private void markPending(String anchor, long token) {
        if (pending.put(anchor, token) == null) {
            for (Listener l : listeners) l.onPendingChanged(anchor, true);
        }
    }

    private void clearPending(String anchor) {
        if (pending.remove(anchor) != null) {
            for (Listener l : listeners) l.onPendingChanged(anchor, false);
        }
    }

    private void clearPendingIfToken(String anchor, long token) {
        if (pending.remove(anchor, token)) {
            for (Listener l : listeners) l.onPendingChanged(anchor, false);
        }
    }

    private void pollDiscover() {
        SessionInfo found;
        try {
            found = fetchNewestSession();
        } catch (Exception e) {
            if (!endedLatched) handleNoSession();
            return;
        }
        if (endedLatched) {
            if (found != null && (current == null || !current.sid().equals(found.sid()))) attach(found);
            return;
        }
        if (found == null) {
            if (current != null) {
                pollLiveness(current.sid());
                if (!endedLatched) handleNoSession();
            } else {
                handleNoSession();
            }
            return;
        }
        if (current == null || !current.sid().equals(found.sid())) attach(found);
        pollLiveness(found.sid());
    }

    private SessionInfo fetchNewestSession() throws Exception {
        String url = baseUrl + "/api/sessions?cwd="
            + URLEncoder.encode(projectCwd, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        var root = JsonParser.parseString(resp.body());
        if (!root.isJsonArray() || root.getAsJsonArray().isEmpty()) return null;
        JsonObject o = root.getAsJsonArray().get(0).getAsJsonObject();
        return new SessionInfo(str(o, "sid"), str(o, "title"), str(o, "state_dir"));
    }

    private void pollLiveness(String sid) {
        if (endedLatched) return;
        long seenAt;
        boolean ended;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + sid + "/poll"))
                .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
            seenAt = o.has("watcher_seen_at") && !o.get("watcher_seen_at").isJsonNull()
                ? o.get("watcher_seen_at").getAsLong() : 0;
            ended = o.has("ended") && !o.get("ended").isJsonNull() && o.get("ended").getAsBoolean();
        } catch (Exception e) {
            return;
        }
        if (ended) { latchEnded(); return; }
        if (seenAt <= 0) return;
        long ageMs = System.currentTimeMillis() - seenAt * 1000;
        if (ageMs > STALE_AFTER.toMillis()) {
            if (state != State.PAUSED) {
                for (String a : new java.util.ArrayList<>(pending.keySet())) clearPending(a);
                setState(State.PAUSED);
            }
        } else if (state == State.PAUSED) {
            setState(State.ACTIVE);
        }
    }

    private void latchEnded() {
        endedLatched = true;
        sseGen.incrementAndGet();
        for (String a : new java.util.ArrayList<>(pending.keySet())) clearPending(a);
        if (sseTask != null) { sseTask.cancel(true); sseTask = null; }
        setState(State.ENDED);
    }

    private void handleNoSession() {
        endedLatched = false;
        if (current != null) {
            current = null;
            threads.clear();
            pending.clear();
            doc.set(WalkthroughDoc.EMPTY);
            sseGen.incrementAndGet();
            if (sseTask != null) { sseTask.cancel(true); sseTask = null; }
            for (Listener l : listeners) l.onDetached();
            for (Listener l : listeners) l.onStepsChanged(WalkthroughDoc.EMPTY);
        }
        setState(State.DORMANT);
    }

    private void attach(SessionInfo s) {
        endedLatched = false;
        current = s;
        threads.clear();
        pending.clear();
        doc.set(WalkthroughDoc.EMPTY);
        setState(State.CONNECTING);
        for (Listener l : listeners) l.onAttached(s);
        openSse(s.sid());
    }

    /** GET steps.json and publish it if it actually changed. */
    private void loadSteps(String sid) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + sid + "/steps.json"))
                .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            WalkthroughDoc next = WalkthroughDoc.parse(resp.body());
            WalkthroughDoc prev = doc.get();
            if (prev.generatedTs() == next.generatedTs()
                    && prev.steps().size() == next.steps().size()) {
                return;
            }
            doc.set(next);
            for (Listener l : listeners) l.onStepsChanged(next);
        } catch (Exception ignored) {
            // transient — the next steps-changed event or reconnect retries
        }
    }

    private void seedThreads(String sid) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + sid + "/threads.json"))
                .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            for (var e : root.entrySet()) {
                JsonObject t = e.getValue().getAsJsonObject();
                applyThread(e.getKey(), toThreadState(t));
            }
        } catch (Exception ignored) {
        }
    }

    private void openSse(String sid) {
        if (closed || sseExec.isShutdown()) return;
        URI uri = URI.create(baseUrl + "/s/" + sid + "/stream");
        long gen = sseGen.incrementAndGet();
        Future<?> prev = sseTask;
        if (prev != null) prev.cancel(true);
        try {
            sseTask = sseExec.submit(() -> runSse(sid, uri, gen));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void runSse(String sid, URI uri, long gen) {
        loadSteps(sid);
        seedThreads(sid);
        if (gen != sseGen.get() || closed) return;
        if (!endedLatched) setState(State.ACTIVE);
        try {
            SseClient.connect(http, uri,
                ev -> { if (gen == sseGen.get()) handleSseEvent(sid, ev); },
                t -> { if (gen == sseGen.get() && !endedLatched && state == State.ACTIVE)
                           setState(State.DISCONNECTED); }
            ).join();
        } catch (Throwable ignored) {
        }
        if (gen == sseGen.get() && !closed && !endedLatched) {
            if (state == State.ACTIVE) setState(State.DISCONNECTED);
            scheduleReconnect(sid, gen);
        }
    }

    private void scheduleReconnect(String sid, long gen) {
        if (closed || exec.isShutdown() || gen != sseGen.get()) return;
        try {
            exec.schedule(() -> { if (gen == sseGen.get() && !closed) openSse(sid); },
                2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void handleSseEvent(String sid, SseClient.Event e) {
        String name = e.name();
        if ("steps-changed".equals(name)) {
            loadSteps(sid);
            return;
        }
        JsonObject data;
        try {
            data = JsonParser.parseString(e.data()).getAsJsonObject();
        } catch (Exception ex) {
            return;
        }
        String anchor = str(data, "anchor");
        if (anchor.isEmpty()) return;
        if ("thread-deleted".equals(name)) {
            threads.remove(anchor);
            clearPending(anchor);
            for (Listener l : listeners) l.onThreadChanged(anchor, null);
            return;
        }
        if (!"thread-changed".equals(name)) return;
        applyThread(anchor, toThreadState(data));
    }

    private ThreadState toThreadState(JsonObject o) {
        int version = o.has("version") && !o.get("version").isJsonNull()
            ? o.get("version").getAsInt() : 0;
        return new ThreadState(str(o, "latest_synthesis"), version,
            str(o, "title"), str(o, "question"));
    }

    private void applyThread(String anchor, ThreadState next) {
        ThreadState existing = threads.get(anchor);
        if (existing != null && existing.version() == next.version()
                && existing.synthesis().equals(next.synthesis())) {
            return;
        }
        threads.put(anchor, next);
        clearPending(anchor);
        for (Listener l : listeners) l.onThreadChanged(anchor, next);
    }

    private void setState(State s) {
        synchronized (stateLock) {
            if (state == s) return;
            state = s;
        }
        for (Listener l : listeners) l.onStateChanged(s);
    }

    private static String str(JsonObject o, String key) {
        var v = o.get(key);
        return (v == null || v.isJsonNull()) ? "" : v.getAsString();
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd intellij-plugin-spike && ./gradlew test --tests '*Walkthrough*'`
Expected: 14 tests passed (9 doc + 5 client).

- [ ] **Step 6: Run the whole suite (nothing else may break)**

Run: `cd intellij-plugin-spike && ./gradlew test`
Expected: BUILD SUCCESSFUL — the review tests still pass with the extended `FakeReviewServer`.

- [ ] **Step 7: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughSessionClient.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/WalkthroughSessionClientTest.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/FakeReviewServer.java
git commit -m "feat(plugin): walkthrough session client with steps, threads and asks"
```

---
## Task 7: `WalkthroughController` + anchor resolution

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughController.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughNavigator.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/WalkthroughControllerTest.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/WalkthroughStepAnchorTest.java`

**Interfaces:**
- Consumes: `WalkthroughDoc`, `WalkthroughStep` (Task 5); `AnchorResolver.resolve(List<String>, int, String, int)` and `AnchorResolver.DEFAULT_K` (existing).
- Produces:
  - `enum WalkthroughController.Mode { RAIL, INLINE }` with `static Mode from(String)` and `String key()`.
  - `interface WalkthroughNavigator { void navigate(WalkthroughStep step); }`
  - `WalkthroughNavigator.resolveLine(List<String> documentLines, WalkthroughStep step) -> AnchorResolver.Resolution` (static, pure).
  - `WalkthroughController(WalkthroughNavigator navigator)` with:
    - `void setDoc(WalkthroughDoc doc)` — resets index to 0 and activates step 1 (no-op activation for an empty doc).
    - `WalkthroughDoc doc()`, `int index()`, `Optional<WalkthroughStep> current()`, `int size()`
    - `boolean next()`, `boolean prev()`, `boolean jumpTo(int index)`, `boolean jumpToId(int stepId)` — all return `false` when the move is impossible; a refused move never re-navigates.
    - `Mode mode()`, `void setMode(Mode)`
    - `void addListener(Listener)`, `removeListener(Listener)`
    - `interface Listener { default void onStepActivated(WalkthroughStep step, int index, int total) {} default void onModeChanged(Mode mode) {} default void onDocChanged(WalkthroughDoc doc) {} }`

- [ ] **Step 1: Write the failing tests**

Create `WalkthroughControllerTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalkthroughControllerTest {

    private static WalkthroughDoc doc(int n) {
        StringBuilder sb = new StringBuilder(
            "{\"question\":\"q\",\"kind\":\"explain\",\"generated_ts\":1,\"steps\":[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append(',');
            sb.append("{\"id\":").append(i)
              .append(",\"title\":\"step ").append(i)
              .append("\",\"file\":\"F").append(i)
              .append(".java\",\"line\":").append(i * 10)
              .append(",\"snippet\":\"line ").append(i)
              .append("\",\"role\":\"context\",\"markdown\":\"m\"}");
        }
        return WalkthroughDoc.parse(sb.append("]}").toString());
    }

    private static final class RecordingNavigator implements WalkthroughNavigator {
        final List<String> visited = new ArrayList<>();
        @Override public void navigate(WalkthroughStep step) { visited.add(step.file() + ":" + step.line()); }
    }

    @Test void setDocActivatesFirstStep() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(doc(3));
        assertEquals(0, c.index());
        assertEquals(1, c.current().orElseThrow().id());
        assertEquals(List.of("F1.java:10"), nav.visited);
    }

    @Test void nextAndPrevWalkTheList() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(doc(3));
        assertTrue(c.next());
        assertEquals(1, c.index());
        assertTrue(c.next());
        assertEquals(2, c.index());
        assertTrue(c.prev());
        assertEquals(1, c.index());
        assertEquals(List.of("F1.java:10", "F2.java:20", "F3.java:30", "F2.java:20"), nav.visited);
    }

    @Test void nextAtLastStepIsRefusedAndDoesNotNavigate() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(doc(2));
        assertTrue(c.next());
        assertFalse(c.next());
        assertEquals(1, c.index());
        assertEquals(2, nav.visited.size());
    }

    @Test void prevAtFirstStepIsRefused() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(doc(2));
        assertFalse(c.prev());
        assertEquals(0, c.index());
        assertEquals(1, nav.visited.size());
    }

    @Test void jumpToIndexAndId() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(doc(4));
        assertTrue(c.jumpTo(3));
        assertEquals(4, c.current().orElseThrow().id());
        assertFalse(c.jumpTo(9));
        assertFalse(c.jumpTo(-1));
        assertTrue(c.jumpToId(2));
        assertEquals(1, c.index());
        assertFalse(c.jumpToId(99));
    }

    @Test void emptyDocHasNoCurrentStepAndRefusesMoves() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(WalkthroughDoc.EMPTY);
        assertTrue(c.current().isEmpty());
        assertEquals(0, c.size());
        assertFalse(c.next());
        assertFalse(c.prev());
        assertTrue(nav.visited.isEmpty());
    }

    @Test void modeSwitchPreservesIndexAndDoesNotRenavigate() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(doc(3));
        c.next();
        int before = nav.visited.size();
        c.setMode(WalkthroughController.Mode.INLINE);
        assertEquals(WalkthroughController.Mode.INLINE, c.mode());
        assertEquals(1, c.index());
        assertEquals(before, nav.visited.size());
    }

    @Test void listenersSeeActivationsAndModeChanges() {
        WalkthroughController c = new WalkthroughController(step -> {});
        List<String> events = new ArrayList<>();
        c.addListener(new WalkthroughController.Listener() {
            @Override public void onStepActivated(WalkthroughStep step, int index, int total) {
                events.add("activate:" + step.id() + ":" + index + "/" + total);
            }
            @Override public void onModeChanged(WalkthroughController.Mode mode) {
                events.add("mode:" + mode);
            }
            @Override public void onDocChanged(WalkthroughDoc doc) {
                events.add("doc:" + doc.steps().size());
            }
        });
        c.setDoc(doc(2));
        c.next();
        c.setMode(WalkthroughController.Mode.INLINE);
        assertEquals(List.of("doc:2", "activate:1:0/2", "activate:2:1/2", "mode:INLINE"), events);
    }

    @Test void modeParsesFromStoredKeyWithRailDefault() {
        assertEquals(WalkthroughController.Mode.INLINE, WalkthroughController.Mode.from("inline"));
        assertEquals(WalkthroughController.Mode.RAIL, WalkthroughController.Mode.from("rail"));
        assertEquals(WalkthroughController.Mode.RAIL, WalkthroughController.Mode.from(null));
        assertEquals(WalkthroughController.Mode.RAIL, WalkthroughController.Mode.from("nonsense"));
        assertEquals("inline", WalkthroughController.Mode.INLINE.key());
    }

    @Test void settingSameModeIsANoOp() {
        WalkthroughController c = new WalkthroughController(step -> {});
        List<String> events = new ArrayList<>();
        c.addListener(new WalkthroughController.Listener() {
            @Override public void onModeChanged(WalkthroughController.Mode mode) { events.add(mode.key()); }
        });
        c.setMode(WalkthroughController.Mode.RAIL);
        assertTrue(events.isEmpty());
    }
}
```

Create `WalkthroughStepAnchorTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalkthroughStepAnchorTest {

    private static WalkthroughStep step(int line, String snippet) {
        return new WalkthroughStep(1, "t", "F.java", line, snippet,
            WalkthroughStep.Role.CONTEXT, "m");
    }

    @Test void exactWhenSnippetStillOnRecordedLine() {
        List<String> lines = List.of("a();", "b();", "target();", "c();");
        var res = WalkthroughNavigator.resolveLine(lines, step(3, "target();"));
        assertEquals(AnchorResolver.Kind.EXACT, res.kind());
        assertEquals(3, res.line());
    }

    @Test void movedWhenCodeShiftedDown() {
        List<String> lines = List.of("new();", "new2();", "a();", "b();", "target();");
        var res = WalkthroughNavigator.resolveLine(lines, step(3, "target();"));
        assertEquals(AnchorResolver.Kind.MOVED, res.kind());
        assertEquals(5, res.line());
    }

    @Test void staleWhenSnippetGone() {
        List<String> lines = List.of("a();", "b();", "c();");
        var res = WalkthroughNavigator.resolveLine(lines, step(2, "target();"));
        assertEquals(AnchorResolver.Kind.STALE, res.kind());
        assertEquals(-1, res.line());
    }

    @Test void ignoresLeadingWhitespaceDifferences() {
        List<String> lines = List.of("a();", "        target();");
        var res = WalkthroughNavigator.resolveLine(lines, step(2, "target();"));
        assertEquals(AnchorResolver.Kind.EXACT, res.kind());
    }

    @Test void staleWhenSnippetIsAmbiguous() {
        List<String> lines = List.of("target();", "x();", "target();");
        var res = WalkthroughNavigator.resolveLine(lines, step(2, "target();"));
        assertEquals(AnchorResolver.Kind.STALE, res.kind());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd intellij-plugin-spike && ./gradlew test --tests '*WalkthroughController*' --tests '*WalkthroughStepAnchor*'`
Expected: compilation failure — `cannot find symbol: class WalkthroughController`.

- [ ] **Step 3: Write `WalkthroughNavigator`**

Create `WalkthroughNavigator.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.List;

/**
 * Moves the IDE to a step's anchor. Split from the controller so the walking
 * logic is testable without an IDE: tests pass a recording implementation.
 */
public interface WalkthroughNavigator {

    void navigate(WalkthroughStep step);

    /**
     * Re-locate a step in a document by its snippet. The recorded line is only a
     * hint — {@link AnchorResolver} searches a window around it and reports
     * EXACT / MOVED / STALE. Pure; unit-tested directly.
     */
    static AnchorResolver.Resolution resolveLine(List<String> documentLines, WalkthroughStep step) {
        return AnchorResolver.resolve(documentLines, step.line(), step.snippet(),
            AnchorResolver.DEFAULT_K);
    }

    /** Opens the step's file, re-resolves the anchor, scrolls and places the caret. */
    final class Ide implements WalkthroughNavigator {
        private final Project project;

        public Ide(Project project) { this.project = project; }

        @Override public void navigate(WalkthroughStep step) {
            VirtualFile vf = project.getBaseDir() == null
                ? null
                : project.getBaseDir().findFileByRelativePath(step.file());
            if (vf == null || vf.isDirectory()) return;
            com.intellij.openapi.editor.Document doc =
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
            int line = step.line();
            if (doc != null) {
                List<String> lines = List.of(doc.getText().split("\n", -1));
                AnchorResolver.Resolution res = resolveLine(lines, step);
                if (res.kind() != AnchorResolver.Kind.STALE) line = res.line();
            }
            int line0 = Math.max(0, line - 1);
            OpenFileDescriptor descriptor = new OpenFileDescriptor(project, vf, line0, 0);
            Editor editor = FileEditorManager.getInstance(project)
                .openTextEditor(descriptor, true);
            if (editor != null) {
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            }
        }
    }
}
```

- [ ] **Step 4: Write `WalkthroughController`**

Create `WalkthroughController.java`:

```java
package com.petros.ireview;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single source of truth for a walk: which step is active, which renderer is
 * showing it. Both renderers subscribe here; exactly one is attached at a time,
 * and switching renderers never disturbs the current index.
 */
public final class WalkthroughController {

    public enum Mode {
        RAIL, INLINE;

        public String key() { return name().toLowerCase(Locale.ROOT); }

        /** Parse a persisted key; anything unrecognised falls back to RAIL. */
        public static Mode from(String raw) {
            if (raw == null) return RAIL;
            return "inline".equalsIgnoreCase(raw.trim()) ? INLINE : RAIL;
        }
    }

    public interface Listener {
        default void onStepActivated(WalkthroughStep step, int index, int total) {}
        default void onModeChanged(Mode mode) {}
        default void onDocChanged(WalkthroughDoc doc) {}
    }

    private final WalkthroughNavigator navigator;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile WalkthroughDoc doc = WalkthroughDoc.EMPTY;
    private volatile int index = 0;
    private volatile Mode mode = Mode.RAIL;

    public WalkthroughController(WalkthroughNavigator navigator) {
        this.navigator = navigator;
    }

    public WalkthroughDoc doc() { return doc; }

    public int index() { return index; }

    public int size() { return doc.steps().size(); }

    public Optional<WalkthroughStep> current() {
        List<WalkthroughStep> steps = doc.steps();
        return (index >= 0 && index < steps.size()) ? Optional.of(steps.get(index)) : Optional.empty();
    }

    public Mode mode() { return mode; }

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    /** Install a new step list. Resets to step 1 and activates it. */
    public void setDoc(WalkthroughDoc next) {
        doc = next == null ? WalkthroughDoc.EMPTY : next;
        index = 0;
        for (Listener l : listeners) l.onDocChanged(doc);
        if (!doc.isEmpty()) activate();
    }

    public boolean next() { return jumpTo(index + 1); }

    public boolean prev() { return jumpTo(index - 1); }

    /** @return false when the target is out of range — no navigation happens. */
    public boolean jumpTo(int target) {
        if (doc.isEmpty() || target < 0 || target >= doc.steps().size()) return false;
        index = target;
        activate();
        return true;
    }

    public boolean jumpToId(int stepId) {
        int i = doc.indexOfId(stepId);
        return i >= 0 && jumpTo(i);
    }

    /** Switching renderers keeps the current step; no re-navigation. */
    public void setMode(Mode next) {
        if (next == null || next == mode) return;
        mode = next;
        for (Listener l : listeners) l.onModeChanged(mode);
    }

    private void activate() {
        WalkthroughStep step = doc.steps().get(index);
        navigator.navigate(step);
        for (Listener l : listeners) l.onStepActivated(step, index, doc.steps().size());
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd intellij-plugin-spike && ./gradlew test --tests '*Walkthrough*'`
Expected: 29 tests passed (9 doc + 5 client + 10 controller + 5 anchor).

- [ ] **Step 6: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughController.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughNavigator.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/WalkthroughControllerTest.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/WalkthroughStepAnchorTest.java
git commit -m "feat(plugin): walkthrough controller, navigation and anchor re-resolution"
```

---
## Task 8: `WalkthroughService` + mode A (rail tool window)

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughService.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughPanel.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughToolWindowFactory.java`
- Modify: `intellij-plugin-spike/src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: `WalkthroughSessionClient` (Task 6), `WalkthroughController` + `WalkthroughNavigator.Ide` (Task 7), existing `MarkdownLinkRenderer`, `SynthesisLinkRouter`.
- Produces:
  - `WalkthroughService.get(Project) -> WalkthroughService`, `client()`, `controller()`, `askCurrentStep(String text)`.
  - `WalkthroughPanel(Project)` implementing `Disposable`, exposing `JComponent getComponent()`.
  - Tool window id `"Walkthrough"`, right anchor.

**Note on `MarkdownLinkRenderer`:** read its public signature before wiring
(`grep -n "public static" MarkdownLinkRenderer.java`) and call the same method
`AnnotationsPanel` uses to turn markdown into a Swing-renderable string. Do not
re-implement markdown rendering.

- [ ] **Step 1: Write `WalkthroughService`**

```java
package com.petros.ireview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project-level holder of one walkthrough client + controller.
 *
 * The client discovers a session by the project's base path; when steps arrive
 * they are pushed into the controller, which activates step 1 and drives
 * whichever renderer is currently attached.
 */
@Service(Service.Level.PROJECT)
public final class WalkthroughService implements Disposable {

    private static final String MODE_KEY = "com.petros.ireview.walkthrough.mode";

    private final WalkthroughSessionClient client;
    private final WalkthroughController controller;

    public WalkthroughService(Project project) {
        String baseUrl = resolveServerUrl();
        String cwd = project.getBasePath();
        this.client = new WalkthroughSessionClient(
            baseUrl != null ? baseUrl : "http://127.0.0.1:54660",
            cwd != null ? cwd : System.getProperty("user.home"),
            Duration.ofSeconds(5));
        this.controller = new WalkthroughController(new WalkthroughNavigator.Ide(project));
        this.controller.setMode(WalkthroughController.Mode.from(
            com.intellij.ide.util.PropertiesComponent.getInstance(project).getValue(MODE_KEY)));
        this.controller.addListener(new WalkthroughController.Listener() {
            @Override public void onModeChanged(WalkthroughController.Mode mode) {
                com.intellij.ide.util.PropertiesComponent.getInstance(project)
                    .setValue(MODE_KEY, mode.key());
            }
        });
        this.client.addListener(new WalkthroughSessionClient.Listener() {
            @Override public void onStepsChanged(WalkthroughDoc doc) {
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    .invokeLater(() -> controller.setDoc(doc));
            }
        });
        this.client.start();
    }

    public static WalkthroughService get(Project project) {
        return project.getService(WalkthroughService.class);
    }

    public WalkthroughSessionClient client() { return client; }

    public WalkthroughController controller() { return controller; }

    /** Post a question on the currently active step. */
    public CompletableFuture<Void> askCurrentStep(String text) {
        return controller.current()
            .map(step -> client.postAsk(step.id(), text))
            .orElseGet(() -> CompletableFuture.failedFuture(
                new IllegalStateException("no active step")));
    }

    @Override public void dispose() { client.stop(); }

    /** Read the walkthrough server URL from ~/.claude/walkthrough/server.json. */
    private static String resolveServerUrl() {
        Path p = Path.of(System.getProperty("user.home"), ".claude", "walkthrough", "server.json");
        try {
            Matcher m = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(Files.readString(p));
            if (m.find()) return m.group(1);
        } catch (IOException ignored) {
        }
        return null;
    }
}
```

- [ ] **Step 2: Write `WalkthroughPanel` (mode A)**

```java
package com.petros.ireview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Mode A renderer: the whole step list, with the active step expanded to show
 * its explanation, its Q&amp;A thread and an ask box.
 *
 * Subscribes to the controller only while {@link WalkthroughController#mode()}
 * is RAIL; in INLINE mode it renders a one-line hint instead so the two
 * renderers are never both drawing.
 */
public final class WalkthroughPanel implements Disposable {

    private final Project project;
    private final WalkthroughService service;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel steps = new JPanel();
    private final JBTextField ask = new JBTextField();
    private final JBLabel status = new JBLabel(" ");
    private final JButton back = new JButton("◀ Back");
    private final JButton next = new JButton("Next ▶");
    private final List<JComponent> rows = new ArrayList<>();

    private final WalkthroughController.Listener controllerListener =
        new WalkthroughController.Listener() {
            @Override public void onDocChanged(WalkthroughDoc doc) { invokeRebuild(); }
            @Override public void onStepActivated(WalkthroughStep step, int i, int total) { invokeRebuild(); }
            @Override public void onModeChanged(WalkthroughController.Mode mode) { invokeRebuild(); }
        };

    private final WalkthroughSessionClient.Listener clientListener =
        new WalkthroughSessionClient.Listener() {
            @Override public void onThreadChanged(String anchor, WalkthroughSessionClient.ThreadState t) { invokeRebuild(); }
            @Override public void onPendingChanged(String anchor, boolean pending) { invokeRebuild(); }
            @Override public void onStateChanged(WalkthroughSessionClient.State s) { invokeRebuild(); }
            @Override public void onDetached() { invokeRebuild(); }
        };

    public WalkthroughPanel(Project project) {
        this.project = project;
        this.service = WalkthroughService.get(project);

        steps.setLayout(new BoxLayout(steps, BoxLayout.Y_AXIS));
        steps.setBorder(JBUI.Borders.empty(4));

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.setBorder(JBUI.Borders.empty(6));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(back);
        buttons.add(next);
        buttons.add(status);
        south.add(ask, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        root.add(new JBScrollPane(steps), BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        back.addActionListener(e -> service.controller().prev());
        next.addActionListener(e -> service.controller().next());
        ask.addActionListener(e -> submitAsk());

        service.controller().addListener(controllerListener);
        service.client().addListener(clientListener);
        rebuild();
    }

    public JComponent getComponent() { return root; }

    private void submitAsk() {
        String text = ask.getText().trim();
        if (text.isEmpty()) return;
        ask.setText("");
        service.askCurrentStep(text).exceptionally(err -> {
            SwingUtilities.invokeLater(() -> status.setText(err.getMessage()));
            return null;
        });
    }

    private void invokeRebuild() {
        SwingUtilities.invokeLater(this::rebuild);
    }

    private void rebuild() {
        steps.removeAll();
        rows.clear();
        WalkthroughController c = service.controller();

        if (c.mode() != WalkthroughController.Mode.RAIL) {
            steps.add(new JBLabel("Walkthrough is in inline mode — steps render in the editor."));
            finish(false);
            return;
        }
        WalkthroughDoc doc = c.doc();
        if (doc.isEmpty()) {
            steps.add(new JBLabel("No walkthrough for this project. Run /walkthrough <question>."));
            finish(false);
            return;
        }
        for (int i = 0; i < doc.steps().size(); i++) {
            WalkthroughStep step = doc.steps().get(i);
            boolean active = i == c.index();
            steps.add(row(step, i, active));
        }
        status.setText(statusText());
        finish(true);
    }

    /** One step row: badge + title + file:line, plus body and thread when active. */
    private JComponent row(WalkthroughStep step, int index, boolean active) {
        JPanel p = new JPanel(new BorderLayout(6, 2));
        p.setBorder(JBUI.Borders.empty(6, 8));
        p.setOpaque(active);
        if (active) p.setBackground(JBUI.CurrentTheme.List.Selection.background(false));

        JBLabel head = new JBLabel((index + 1) + ".  " + step.title());
        head.setFont(head.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN));
        head.setForeground(roleColor(step.role()));
        JBLabel where = new JBLabel(step.file() + ":" + step.line());
        where.setForeground(JBUI.CurrentTheme.Label.disabledForeground());

        JPanel headBox = new JPanel(new GridLayout(2, 1));
        headBox.setOpaque(false);
        headBox.add(head);
        headBox.add(where);
        p.add(headBox, BorderLayout.NORTH);

        if (active) {
            JPanel body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setOpaque(false);
            body.add(markdown(step.markdown()));
            service.client().threadFor(step.anchor()).ifPresent(t -> {
                if (!t.question().isEmpty()) {
                    JBLabel q = new JBLabel("You · " + t.question());
                    q.setFont(q.getFont().deriveFont(Font.BOLD));
                    body.add(q);
                }
                body.add(markdown(t.synthesis()));
            });
            if (service.client().isPending(step.anchor())) {
                body.add(new JBLabel("● waiting for Claude…"));
            }
            p.add(body, BorderLayout.CENTER);
        }

        p.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                service.controller().jumpTo(index);
            }
        });
        rows.add(p);
        return p;
    }

    /**
     * Render markdown through the shared renderer so links behave exactly as
     * they do in the review panel (project-relative paths become clickable and
     * are routed by SynthesisLinkRouter).
     */
    private JComponent markdown(String md) {
        JEditorPane pane = new JEditorPane("text/html", MarkdownLinkRenderer.toHtml(md));
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                SynthesisLinkRouter.open(project, e.getDescription());
            }
        });
        return pane;
    }

    private String statusText() {
        WalkthroughSessionClient.State s = service.client().state();
        return switch (s) {
            case ENDED -> "session ended — read only";
            case PAUSED -> "Claude is away — asks disabled";
            case DISCONNECTED -> "reconnecting…";
            default -> (service.controller().index() + 1) + " / " + service.controller().size();
        };
    }

    private static Color roleColor(WalkthroughStep.Role role) {
        return switch (role) {
            case SEAM -> new Color(0x35, 0x74, 0xF0);
            case EDIT_SITE -> new Color(0x1F, 0x9C, 0x5B);
            case CONTEXT -> UIManager.getColor("Label.foreground");
        };
    }

    private void finish(boolean enableControls) {
        boolean live = enableControls
            && service.client().state() != WalkthroughSessionClient.State.ENDED
            && service.client().state() != WalkthroughSessionClient.State.PAUSED;
        ask.setEnabled(live);
        back.setEnabled(enableControls);
        next.setEnabled(enableControls);
        steps.revalidate();
        steps.repaint();
    }

    @Override public void dispose() {
        service.controller().removeListener(controllerListener);
        service.client().removeListener(clientListener);
    }
}
```

- [ ] **Step 3: Write `WalkthroughToolWindowFactory`**

```java
package com.petros.ireview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class WalkthroughToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        WalkthroughPanel panel = new WalkthroughPanel(project);
        Content content = ContentFactory.getInstance()
            .createContent(panel.getComponent(), "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`**

Inside the existing `<extensions defaultExtensionNs="com.intellij">` block, after the `<toolWindow id="Review Annotations" …/>` entry, add:

```xml
        <projectService serviceImplementation="com.petros.ireview.WalkthroughService"/>
        <toolWindow id="Walkthrough"
                    anchor="right"
                    icon="/icons/comment.svg"
                    factoryClass="com.petros.ireview.WalkthroughToolWindowFactory"/>
```

- [ ] **Step 5: Verify it compiles and the suite still passes**

Run: `cd intellij-plugin-spike && ./gradlew build`
Expected: BUILD SUCCESSFUL. If `MarkdownLinkRenderer.toHtml` / `SynthesisLinkRouter.open` do not exist with those names, fix the two call sites in `WalkthroughPanel.markdown` to the real signatures (`grep -n "public static" src/main/java/com/petros/ireview/MarkdownLinkRenderer.java src/main/java/com/petros/ireview/SynthesisLinkRouter.java`) — do not add new renderer code.

- [ ] **Step 6: Manual smoke test**

```bash
cd intellij-plugin-spike && ./gradlew runIde
```

In the sandbox IDE: the "Walkthrough" tool window exists on the right and shows *"No walkthrough for this project. Run /walkthrough <question>."*

- [ ] **Step 7: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughService.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughPanel.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughToolWindowFactory.java \
        intellij-plugin-spike/src/main/resources/META-INF/plugin.xml
git commit -m "feat(plugin): walkthrough service and rail tool window (mode A)"
```

---
## Task 9: mode B — editor inlay + floating HUD

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughInlay.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughHud.java`
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughService.java` (own the inline renderer's lifecycle)

**Interfaces:**
- Consumes: `WalkthroughController` (Task 7), `WalkthroughService` (Task 8), `WalkthroughNavigator.resolveLine`.
- Produces:
  - `WalkthroughInlay(Project)` with `void attach()`, `void detach()`, `void refresh()` — idempotent; safe to call from the EDT only.
  - `WalkthroughHud(Project)` with `void show(WalkthroughStep step, int index, int total)`, `void hide()`.
  - `WalkthroughService.inline()` returning the singleton `WalkthroughInlay` for this project; the service attaches/detaches it on mode change.

- [ ] **Step 1: Write `WalkthroughInlay`**

```java
package com.petros.ireview;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayProperties;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.JBUI;

import java.awt.*;
import java.util.List;

/**
 * Mode B renderer: one block inlay under the active step's anchor line, holding
 * the step's explanation and its latest Claude reply.
 *
 * Exactly one inlay exists at a time — {@link #refresh()} disposes the previous
 * one before creating the next, so stepping never leaves cards behind.
 */
public final class WalkthroughInlay {

    private final Project project;
    private final WalkthroughHud hud;
    private Inlay<?> currentInlay;
    private boolean attached;

    private final WalkthroughController.Listener listener = new WalkthroughController.Listener() {
        @Override public void onStepActivated(WalkthroughStep step, int index, int total) { refresh(); }
        @Override public void onDocChanged(WalkthroughDoc doc) { refresh(); }
    };

    public WalkthroughInlay(Project project) {
        this.project = project;
        this.hud = new WalkthroughHud(project);
    }

    public void attach() {
        if (attached) return;
        attached = true;
        WalkthroughService.get(project).controller().addListener(listener);
        refresh();
    }

    public void detach() {
        if (!attached) return;
        attached = false;
        WalkthroughService.get(project).controller().removeListener(listener);
        disposeInlay();
        hud.hide();
    }

    /** Rebuild the inlay at the current step's re-resolved anchor. */
    public void refresh() {
        disposeInlay();
        if (!attached) return;
        WalkthroughService service = WalkthroughService.get(project);
        WalkthroughController c = service.controller();
        var maybeStep = c.current();
        if (maybeStep.isEmpty()) { hud.hide(); return; }
        WalkthroughStep step = maybeStep.get();

        Editor editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .getSelectedTextEditor();
        if (editor == null) { hud.show(step, c.index(), c.size()); return; }
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null || !vf.getPath().endsWith(step.file())) {
            // The editor is showing a different file — the controller's navigate
            // will land here again once the right file is open.
            hud.show(step, c.index(), c.size());
            return;
        }
        List<String> lines = List.of(editor.getDocument().getText().split("\n", -1));
        AnchorResolver.Resolution res = WalkthroughNavigator.resolveLine(lines, step);
        int line = res.kind() == AnchorResolver.Kind.STALE ? step.line() : res.line();
        int offset = editor.getDocument().getLineEndOffset(
            Math.min(Math.max(0, line - 1), editor.getDocument().getLineCount() - 1));

        String body = step.markdown();
        var thread = service.client().threadFor(step.anchor());
        if (thread.isPresent()) {
            body = body + "\n\nYou · " + thread.get().question() + "\n" + thread.get().synthesis();
        } else if (service.client().isPending(step.anchor())) {
            body = body + "\n\n● waiting for Claude…";
        }
        String header = "Step " + (c.index() + 1) + " of " + c.size() + " — " + step.title()
            + (res.kind() == AnchorResolver.Kind.STALE ? "  (code changed here)" : "");

        currentInlay = editor.getInlayModel().addBlockElement(
            offset, new InlayProperties().showAbove(false).relatesToPrecedingText(true),
            new CardRenderer(header, body));
        hud.show(step, c.index(), c.size());
    }

    private void disposeInlay() {
        if (currentInlay != null) {
            com.intellij.openapi.util.Disposer.dispose(currentInlay);
            currentInlay = null;
        }
    }

    /** Plain-text card. Kept deliberately simple: the rail is where rich rendering lives. */
    private static final class CardRenderer implements EditorCustomElementRenderer {
        private final String header;
        private final String body;

        CardRenderer(String header, String body) {
            this.header = header;
            this.body = body;
        }

        private static String[] lines(String text) { return text.split("\n", -1); }

        @Override public int calcWidthInPixels(Inlay inlay) {
            Editor e = inlay.getEditor();
            FontMetrics fm = e.getContentComponent().getFontMetrics(e.getColorsScheme().getFont(
                com.intellij.openapi.editor.colors.EditorFontType.PLAIN));
            int max = fm.stringWidth(header);
            for (String l : lines(body)) max = Math.max(max, fm.stringWidth(l));
            return max + JBUI.scale(32);
        }

        @Override public int calcHeightInPixels(Inlay inlay) {
            int rows = lines(body).length + 2;
            return rows * inlay.getEditor().getLineHeight() + JBUI.scale(8);
        }

        @Override public void paint(Inlay inlay, Graphics g, Rectangle target, TextAttributes attributes) {
            Editor e = inlay.getEditor();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0xF5, 0xF8, 0xFF));
                g2.fillRect(target.x, target.y, target.width, target.height);
                g2.setColor(new Color(0x35, 0x74, 0xF0));
                g2.fillRect(target.x, target.y, JBUI.scale(3), target.height);
                g2.setFont(e.getColorsScheme().getFont(
                    com.intellij.openapi.editor.colors.EditorFontType.BOLD));
                int lineHeight = e.getLineHeight();
                int x = target.x + JBUI.scale(14);
                int y = target.y + lineHeight;
                g2.setColor(new Color(0x1F, 0x21, 0x26));
                g2.drawString(header, x, y);
                g2.setFont(e.getColorsScheme().getFont(
                    com.intellij.openapi.editor.colors.EditorFontType.PLAIN));
                g2.setColor(new Color(0x2F, 0x32, 0x37));
                for (String l : lines(body)) {
                    y += lineHeight;
                    g2.drawString(l, x, y);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
```

- [ ] **Step 2: Write `WalkthroughHud`**

```java
package com.petros.ireview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/**
 * Mode B position indicator: a small always-on-top balloon showing
 * "3 / 7 · title" plus the navigation keys. Replaced (not stacked) on each step.
 */
public final class WalkthroughHud {

    private final Project project;
    private Balloon balloon;

    public WalkthroughHud(Project project) { this.project = project; }

    public void show(WalkthroughStep step, int index, int total) {
        hide();
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        panel.setOpaque(false);
        panel.add(new JBLabel((index + 1) + " / " + total + " · " + step.title()));
        panel.add(new JBLabel(WalkthroughActions.hintText()));
        balloon = JBPopupFactory.getInstance()
            .createBalloonBuilder(panel)
            .setFadeoutTime(0)
            .setHideOnClickOutside(false)
            .setHideOnKeyOutside(false)
            .setBorderInsets(JBUI.insets(4, 10))
            .createBalloon();
        JComponent frame = (JComponent) WindowManager.getInstance()
            .getIdeFrame(project).getComponent();
        Point at = new Point(frame.getWidth() / 2, frame.getHeight() - JBUI.scale(60));
        balloon.show(new RelativePoint(frame, at), Balloon.Position.above);
    }

    public void hide() {
        if (balloon != null) {
            balloon.hide();
            balloon = null;
        }
    }
}
```

- [ ] **Step 3: Wire mode switching into `WalkthroughService`**

Add the field and accessor, and extend the existing mode listener:

```java
    private final WalkthroughInlay inline;
```

In the constructor, after the controller is created:

```java
        this.inline = new WalkthroughInlay(project);
```

Replace the existing `onModeChanged` listener body with:

```java
            @Override public void onModeChanged(WalkthroughController.Mode mode) {
                com.intellij.ide.util.PropertiesComponent.getInstance(project)
                    .setValue(MODE_KEY, mode.key());
                applyMode(mode);
            }
```

Add these methods:

```java
    public WalkthroughInlay inline() { return inline; }

    /** Exactly one renderer is live: INLINE owns the inlay, RAIL owns the panel. */
    private void applyMode(WalkthroughController.Mode mode) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            if (mode == WalkthroughController.Mode.INLINE) {
                inline.attach();
            } else {
                inline.detach();
            }
        });
    }
```

and call `applyMode(controller.mode());` as the last statement of the constructor, after `client.start();`.

In `dispose()`, detach first:

```java
    @Override public void dispose() {
        inline.detach();
        client.stop();
    }
```

- [ ] **Step 4: Verify build**

Run: `cd intellij-plugin-spike && ./gradlew build`
Expected: BUILD SUCCESSFUL. `WalkthroughActions.hintText()` does not exist yet — add a temporary stub in `WalkthroughHud` returning `""` **only if** Task 11 has not landed; otherwise the real method is used. (If you are executing tasks in order, replace `WalkthroughActions.hintText()` with the literal `""` here and restore the call in Task 11.)

- [ ] **Step 5: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughInlay.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughHud.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughService.java
git commit -m "feat(plugin): inline walkthrough renderer with editor inlay and HUD"
```

---

## Task 10: gutter badges

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughGutter.java`
- Modify: `intellij-plugin-spike/src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: `WalkthroughService` (Task 8), `WalkthroughNavigator.resolveLine` (Task 7). Follows the attach/detach pattern of the existing `WorkingCopyAskGutter` (read it first: it shows how editors are tracked in a `WeakHashMap` and how a debounce timer re-attaches after document changes).
- Produces: `WalkthroughGutter implements EditorFactoryListener` — draws a numbered badge in the gutter of every line that anchors a step of the active tour, in **both** modes. Clicking a badge jumps the controller to that step.

- [ ] **Step 1: Write the implementation**

```java
package com.petros.ireview;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Paints a numbered badge in the gutter of every line that anchors a step of the
 * active tour. Independent of the current mode — the badges are the one part of
 * the walkthrough that both renderers share.
 */
public final class WalkthroughGutter implements EditorFactoryListener {

    private static final Map<Editor, List<RangeHighlighter>> PAINTED = new WeakHashMap<>();

    @Override public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        Project project = editor.getProject();
        if (project == null || !(editor instanceof EditorEx)) return;
        WalkthroughService service = WalkthroughService.get(project);
        repaint(editor, service);
        service.controller().addListener(new WalkthroughController.Listener() {
            @Override public void onDocChanged(WalkthroughDoc doc) {
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    .invokeLater(() -> repaint(editor, service));
            }
            @Override public void onStepActivated(WalkthroughStep step, int i, int total) {
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    .invokeLater(() -> repaint(editor, service));
            }
        });
    }

    @Override public void editorReleased(@NotNull EditorFactoryEvent event) {
        clear(event.getEditor());
    }

    private static void clear(Editor editor) {
        List<RangeHighlighter> old = PAINTED.remove(editor);
        if (old == null) return;
        for (RangeHighlighter h : old) editor.getMarkupModel().removeHighlighter(h);
    }

    private static void repaint(Editor editor, WalkthroughService service) {
        if (editor.isDisposed()) return;
        clear(editor);
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null) return;
        WalkthroughDoc doc = service.controller().doc();
        if (doc.isEmpty()) return;
        List<String> lines = List.of(editor.getDocument().getText().split("\n", -1));
        List<RangeHighlighter> painted = new ArrayList<>();
        for (int i = 0; i < doc.steps().size(); i++) {
            WalkthroughStep step = doc.steps().get(i);
            if (!vf.getPath().endsWith(step.file())) continue;
            AnchorResolver.Resolution res = WalkthroughNavigator.resolveLine(lines, step);
            if (res.kind() == AnchorResolver.Kind.STALE) continue;
            int line0 = res.line() - 1;
            if (line0 < 0 || line0 >= editor.getDocument().getLineCount()) continue;
            RangeHighlighter h = editor.getMarkupModel().addRangeHighlighter(
                editor.getDocument().getLineStartOffset(line0),
                editor.getDocument().getLineEndOffset(line0),
                HighlighterLayer.LAST, null, HighlighterTargetArea.LINES_IN_RANGE);
            int index = i;
            boolean active = index == service.controller().index();
            h.setGutterIconRenderer(new BadgeRenderer(step, index, active, service));
            painted.add(h);
        }
        PAINTED.put(editor, painted);
    }

    private static final class BadgeRenderer extends GutterIconRenderer {
        private final WalkthroughStep step;
        private final int index;
        private final boolean active;
        private final WalkthroughService service;

        BadgeRenderer(WalkthroughStep step, int index, boolean active, WalkthroughService service) {
            this.step = step;
            this.index = index;
            this.active = active;
            this.service = service;
        }

        @Override public @NotNull Icon getIcon() {
            return new BadgeIcon(index + 1, step.role(), active);
        }

        @Override public String getTooltipText() {
            return "Step " + (index + 1) + " — " + step.title();
        }

        @Override public com.intellij.openapi.actionSystem.AnAction getClickAction() {
            return new com.intellij.openapi.actionSystem.AnAction() {
                @Override public void actionPerformed(
                        @NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
                    service.controller().jumpTo(index);
                }
            };
        }

        @Override public boolean equals(Object o) {
            return o instanceof BadgeRenderer b && b.index == index && b.active == active;
        }

        @Override public int hashCode() { return index * 31 + (active ? 1 : 0); }
    }

    /** Small filled circle with the step number; colour encodes the step's role. */
    private static final class BadgeIcon implements Icon {
        private final int number;
        private final WalkthroughStep.Role role;
        private final boolean active;

        BadgeIcon(int number, WalkthroughStep.Role role, boolean active) {
            this.number = number;
            this.role = role;
            this.active = active;
        }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = switch (role) {
                    case SEAM -> new Color(0x35, 0x74, 0xF0);
                    case EDIT_SITE -> new Color(0x1F, 0x9C, 0x5B);
                    case CONTEXT -> new Color(0x8A, 0x8D, 0x93);
                };
                if (!active) fill = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 150);
                g2.setColor(fill);
                g2.fillOval(x, y, getIconWidth(), getIconHeight());
                g2.setColor(Color.WHITE);
                g2.setFont(g2.getFont().deriveFont(Font.BOLD, 9f));
                String s = String.valueOf(number);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(s, x + (getIconWidth() - fm.stringWidth(s)) / 2,
                    y + (getIconHeight() + fm.getAscent()) / 2 - 1);
            } finally {
                g2.dispose();
            }
        }

        @Override public int getIconWidth() { return com.intellij.util.ui.JBUI.scale(14); }

        @Override public int getIconHeight() { return com.intellij.util.ui.JBUI.scale(14); }
    }
}
```

- [ ] **Step 2: Register in `plugin.xml`**

In the `<extensions>` block, next to the existing `editorFactoryListener`:

```xml
        <editorFactoryListener implementation="com.petros.ireview.WalkthroughGutter"/>
```

- [ ] **Step 3: Verify build and suite**

Run: `cd intellij-plugin-spike && ./gradlew build test`
Expected: BUILD SUCCESSFUL, all existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughGutter.java \
        intellij-plugin-spike/src/main/resources/META-INF/plugin.xml
git commit -m "feat(plugin): numbered walkthrough badges in the editor gutter"
```

---
## Task 11: actions, shortcuts, mode toggle widget

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughActions.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughModeWidget.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughModeWidgetFactory.java`
- Modify: `intellij-plugin-spike/src/main/resources/META-INF/plugin.xml`
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughHud.java` (restore the real `hintText()` call if Task 9 stubbed it)

**Interfaces:**
- Consumes: `WalkthroughService` (Task 8), `IdeKeymapCatalog.all() -> List<Row>` (existing, used only for the manual collision check in Step 1).
- Produces:
  - `WalkthroughActions.Next`, `.Prev`, `.Ask`, `.ToggleMode` — four `AnAction` subclasses, all nested in `WalkthroughActions`.
  - `WalkthroughActions.hintText()` — the HUD's key legend, built from the actions' real registered shortcuts so it can never drift from the keymap.
  - Status-bar widget id `com.petros.ireview.walkthrough.mode`.

- [ ] **Step 1: Verify the shortcuts are free BEFORE binding anything**

This step exists because `⌃⇧/` was already taken by `CommentByBlockComment` in the
"Mac OS X 10.5+" keymap, and `$default` `control` shortcuts are rewritten to Cmd on
macOS. Do not skip it and do not guess.

```bash
cd intellij-plugin-spike && ./gradlew runIde
```

In the sandbox IDE, open **Help → Show Shortcut Cheat-Sheet** (the plugin's existing
`ShowShortcutsAction`, `⌃⌥⇧/`) and search for each candidate below. A candidate is
usable only if the cheat-sheet shows **no** existing binding for it in the active
keymap:

| Action | Candidate | Fallback if taken |
| --- | --- | --- |
| Next step | `control alt shift RIGHT` | `control alt shift CLOSE_BRACKET` |
| Previous step | `control alt shift LEFT` | `control alt shift OPEN_BRACKET` |
| Ask on step | `control alt shift A` | `control alt shift Q` |
| Toggle mode | `control alt shift W` | `control alt shift M` |

Record the four winners; use them verbatim in Step 3. Bind them on the **Mac
keymaps only** (`keymap="Mac OS X 10.5+"` and `keymap="Mac OS X"`) so `control`
stays literal Ctrl instead of being translated to Cmd — same as the two existing
actions in `plugin.xml`.

- [ ] **Step 2: Write `WalkthroughActions`**

```java
package com.petros.ireview;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/** The four things a user does to a walkthrough: step, step back, ask, switch view. */
public final class WalkthroughActions {

    private WalkthroughActions() {}

    public static final String NEXT_ID = "com.petros.ireview.WalkthroughNext";
    public static final String PREV_ID = "com.petros.ireview.WalkthroughPrev";
    public static final String ASK_ID = "com.petros.ireview.WalkthroughAsk";
    public static final String TOGGLE_ID = "com.petros.ireview.WalkthroughToggleMode";

    /** Key legend for the HUD, read from the live keymap so it can never drift. */
    public static String hintText() {
        return shortcut(PREV_ID) + " back · " + shortcut(NEXT_ID) + " next · "
             + shortcut(ASK_ID) + " ask · " + shortcut(TOGGLE_ID) + " view";
    }

    private static String shortcut(String actionId) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) return "";
        var shortcuts = action.getShortcutSet().getShortcuts();
        return shortcuts.length == 0 ? "" : KeymapUtil.getShortcutText(shortcuts[0]);
    }

    private abstract static class Base extends AnAction {
        @Override public void update(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            e.getPresentation().setEnabled(project != null
                && !WalkthroughService.get(project).controller().doc().isEmpty());
        }

        @Override public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread
                getActionUpdateThread() {
            return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT;
        }
    }

    public static final class Next extends Base {
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            Project p = e.getProject();
            if (p != null) WalkthroughService.get(p).controller().next();
        }
    }

    public static final class Prev extends Base {
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            Project p = e.getProject();
            if (p != null) WalkthroughService.get(p).controller().prev();
        }
    }

    /** Swing input dialog — no JCEF; the JS→Java bridge is dead under IU-261. */
    public static final class Ask extends Base {
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            Project p = e.getProject();
            if (p == null) return;
            WalkthroughService service = WalkthroughService.get(p);
            var step = service.controller().current();
            if (step.isEmpty()) return;
            String text = Messages.showInputDialog(p,
                "Ask about step " + (service.controller().index() + 1)
                    + " — " + step.get().title(),
                "Walkthrough", null);
            if (text == null || text.isBlank()) return;
            service.askCurrentStep(text).exceptionally(err -> {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showWarningDialog(p, err.getMessage(), "Walkthrough"));
                return null;
            });
        }
    }

    public static final class ToggleMode extends AnAction {
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            Project p = e.getProject();
            if (p == null) return;
            WalkthroughController c = WalkthroughService.get(p).controller();
            c.setMode(c.mode() == WalkthroughController.Mode.RAIL
                ? WalkthroughController.Mode.INLINE
                : WalkthroughController.Mode.RAIL);
        }

        @Override public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread
                getActionUpdateThread() {
            return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT;
        }
    }
}
```

If Task 9 stubbed the HUD's legend with `""`, restore it now to
`WalkthroughActions.hintText()`.

- [ ] **Step 3: Register the actions in `plugin.xml`**

Inside the existing `<actions>` block, using the four keystrokes you confirmed in
Step 1 (shown here with the primary candidates):

```xml
        <action id="com.petros.ireview.WalkthroughNext"
                class="com.petros.ireview.WalkthroughActions$Next"
                text="Walkthrough: Next Step"
                description="Move to the next step of the active walkthrough.">
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="control alt shift RIGHT"/>
            <keyboard-shortcut keymap="Mac OS X"       first-keystroke="control alt shift RIGHT"/>
        </action>
        <action id="com.petros.ireview.WalkthroughPrev"
                class="com.petros.ireview.WalkthroughActions$Prev"
                text="Walkthrough: Previous Step"
                description="Move to the previous step of the active walkthrough.">
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="control alt shift LEFT"/>
            <keyboard-shortcut keymap="Mac OS X"       first-keystroke="control alt shift LEFT"/>
        </action>
        <action id="com.petros.ireview.WalkthroughAsk"
                class="com.petros.ireview.WalkthroughActions$Ask"
                text="Walkthrough: Ask About This Step"
                description="Ask Claude a question about the active walkthrough step.">
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="control alt shift A"/>
            <keyboard-shortcut keymap="Mac OS X"       first-keystroke="control alt shift A"/>
        </action>
        <action id="com.petros.ireview.WalkthroughToggleMode"
                class="com.petros.ireview.WalkthroughActions$ToggleMode"
                text="Walkthrough: Toggle Rail / Inline"
                description="Switch the walkthrough between the tool window and inline editor cards.">
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="control alt shift W"/>
            <keyboard-shortcut keymap="Mac OS X"       first-keystroke="control alt shift W"/>
        </action>
```

- [ ] **Step 4: Write the mode widget**

`WalkthroughModeWidget.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;

/** Status-bar toggle: "Walkthrough: rail" / "Walkthrough: inline". Click flips it. */
public final class WalkthroughModeWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    public static final String WIDGET_ID = "com.petros.ireview.walkthrough.mode";

    private final Project project;
    private StatusBar statusBar;

    public WalkthroughModeWidget(Project project) {
        this.project = project;
        WalkthroughService.get(project).controller().addListener(
            new WalkthroughController.Listener() {
                @Override public void onModeChanged(WalkthroughController.Mode mode) { update(); }
                @Override public void onDocChanged(WalkthroughDoc doc) { update(); }
            });
    }

    @Override public @NotNull String ID() { return WIDGET_ID; }

    @Override public void install(@NotNull StatusBar statusBar) { this.statusBar = statusBar; }

    @Override public void dispose() { statusBar = null; }

    @Override public @NotNull String getText() {
        WalkthroughController c = WalkthroughService.get(project).controller();
        if (c.doc().isEmpty()) return "Walkthrough: —";
        return "Walkthrough: " + c.mode().key() + "  " + (c.index() + 1) + "/" + c.size();
    }

    @Override public float getAlignment() { return 0.5f; }

    @Override public String getTooltipText() { return "Click to switch rail / inline"; }

    @Override public Consumer<MouseEvent> getClickConsumer() {
        return e -> {
            WalkthroughController c = WalkthroughService.get(project).controller();
            c.setMode(c.mode() == WalkthroughController.Mode.RAIL
                ? WalkthroughController.Mode.INLINE
                : WalkthroughController.Mode.RAIL);
        };
    }

    @Override public WidgetPresentation getPresentation() { return this; }

    private void update() {
        if (statusBar != null) statusBar.updateWidget(WIDGET_ID);
    }
}
```

`WalkthroughModeWidgetFactory.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

public final class WalkthroughModeWidgetFactory implements StatusBarWidgetFactory {
    @Override public @NotNull String getId() { return WalkthroughModeWidget.WIDGET_ID; }
    @Override public @NotNull String getDisplayName() { return "Walkthrough Mode"; }
    @Override public boolean isAvailable(@NotNull Project project) { return true; }
    @Override public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new WalkthroughModeWidget(project);
    }
    @Override public void disposeWidget(@NotNull StatusBarWidget widget) { widget.dispose(); }
    @Override public boolean canBeEnabledOn(@NotNull StatusBar statusBar) { return true; }
}
```

Register in `plugin.xml` next to the existing widget factory:

```xml
        <statusBarWidgetFactory id="com.petros.ireview.walkthrough.mode"
                                implementation="com.petros.ireview.WalkthroughModeWidgetFactory"/>
```

- [ ] **Step 5: Verify build and suite**

Run: `cd intellij-plugin-spike && ./gradlew build test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughActions.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughModeWidget.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughModeWidgetFactory.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/WalkthroughHud.java \
        intellij-plugin-spike/src/main/resources/META-INF/plugin.xml
git commit -m "feat(plugin): walkthrough actions, verified shortcuts and mode toggle widget"
```

---

## Task 12: end-to-end verification

**Files:**
- Create: `skills/walkthrough/tests/test_end_to_end.py`
- Modify: `README.md` (repo root — add `/walkthrough` to the skill list)

**Interfaces:**
- Consumes: everything. This task proves the parts meet.
- Produces: an automated round-trip test (server-side) plus a scripted manual checklist for the IDE half, which no unit test can cover.

- [ ] **Step 1: Write the round-trip test**

Create `skills/walkthrough/tests/test_end_to_end.py`:

```python
"""Server-side round trip: create session dirs, write steps, ask, answer, read back."""
import json
import time
from io import BytesIO
from unittest.mock import MagicMock

from skills.walkthrough.server import Handlers
from skills.walkthrough import steps as steps_module
from skills.interactive_review import threads as threads_module


def make_handler():
    h = MagicMock()
    h.rfile = BytesIO(b"")
    h.wfile = BytesIO()
    h.headers = {}
    return h


def test_full_round_trip(tmp_path):
    state_dir = tmp_path / "state"
    for d in (state_dir, state_dir / "events", state_dir / "consumed"):
        d.mkdir(parents=True)
    dirs = {"state_dir": state_dir, "events_dir": state_dir / "events",
            "consumed_dir": state_dir / "consumed", "_cwd": str(tmp_path)}
    h = Handlers()

    # 1. session init records the question and seeds threads/
    out = h.create_session_extra({"question": "how is sharing gated"}, dirs)
    assert out["title"] == "how is sharing gated"

    # 2. Claude writes the steps
    steps_module.write_steps(state_dir, {
        "question": "how is sharing gated",
        "kind": "explain",
        "generated_ts": int(time.time()),
        "steps": [
            {"id": 1, "title": "Entry", "file": "src/Api.java", "line": 42,
             "snippet": "return service.share(id);", "role": "context", "markdown": "Entry."},
            {"id": 2, "title": "Gate", "file": "src/Engine.java", "line": 114,
             "snippet": "var failures = preconditions.evaluate(p);", "role": "seam",
             "markdown": "Runs beans."},
        ],
    })
    handler = make_handler()
    h.serve_data(handler, dirs, "steps.json")
    assert len(json.loads(handler.wfile.getvalue())["steps"]) == 2

    # 3. the IDE asks on step 2
    handler = make_handler()
    h.handle_submit(handler, dirs, {"anchor": "step:2", "text": "is ordering guaranteed?"})
    event_id = json.loads(handler.wfile.getvalue())["event_id"]
    assert len(list((state_dir / "events").iterdir())) == 1

    # 4. Claude answers into that thread only
    threads_module.append_message(state_dir / "threads", "step:2", {
        "role": "claude", "ts": int(time.time()),
        "text": "No — bean order is undefined without @Order.",
        "source_event_id": event_id,
    }, title="Ordering")

    # 5. the IDE reads it back
    handler = make_handler()
    h.serve_data(handler, dirs, "threads.json")
    bulk = json.loads(handler.wfile.getvalue())
    assert set(bulk) == {"step:2"}
    assert bulk["step:2"]["title"] == "Ordering"
    assert "@Order" in bulk["step:2"]["latest_synthesis"]

    # 6. replaying the same event is a no-op (watcher restart safety)
    appended = threads_module.append_message(state_dir / "threads", "step:2", {
        "role": "claude", "ts": int(time.time()), "text": "duplicate",
        "source_event_id": event_id,
    })
    assert appended is False
    thread = threads_module.load(state_dir / "threads", "step:2")
    assert sum(1 for m in thread["messages"] if m["role"] == "claude") == 1


def test_step_one_thread_stays_untouched(tmp_path):
    """Thread isolation: answering step 2 must never write step 1's file."""
    state_dir = tmp_path / "state"
    (state_dir / "threads").mkdir(parents=True)
    threads_module.append_message(state_dir / "threads", "step:2", {
        "role": "claude", "ts": 1, "text": "answer", "source_event_id": "e1"})
    assert threads_module.load(state_dir / "threads", "step:1")["messages"] == []
```

- [ ] **Step 2: Run the Python suite**

Run: `python3 -m pytest skills/walkthrough/tests/ -v`
Expected: all pass (35 + 2 = 37).

- [ ] **Step 3: Manual IDE verification**

Terminal A, in a real project (e.g. `~/projects/montblanc`):

```
/walkthrough how is a proposal blocked from being shared
```

Expect: one terminal sentence naming the step count, then silence.

In IntelliJ, with the plugin built and installed (`./gradlew buildPlugin`, then reload):

1. The "Walkthrough" tool window lists every step; step 1 is expanded and the editor jumped to its anchor.
2. Gutter shows numbered badges — grey for context, blue for seam, green for edit-site.
3. Next / Previous shortcuts move one step and re-navigate; Next at the last step does nothing.
4. Clicking a step row jumps to it.
5. Ask on a step: the row shows `● waiting for Claude…`, then the reply appears **in that row only** — no other step changes.
6. Toggle mode (status bar or shortcut): the rail goes quiet, the inlay card appears under the current line with the same content, HUD shows `N / M · title`. The current step number is unchanged across the switch.
7. Toggle back: the rail returns, still on the same step.
8. Edit a file above an anchor (add two lines): the badge follows the code (MOVED); delete the anchored line: the badge disappears and the step header says "code changed here".
9. In terminal A, say "scrap it": the tool window empties, the HUD disappears.

Any deviation is a defect — fix it before the final commit.

- [ ] **Step 4: Update the repo README**

Add `/walkthrough` to the skill list in the root `README.md`, one line, matching the existing entries' format: name, one-sentence purpose, and a link to `skills/walkthrough/SKILL.md`.

- [ ] **Step 5: Final commit**

```bash
git add skills/walkthrough/tests/test_end_to_end.py README.md
git commit -m "test(walkthrough): server round-trip coverage and README entry"
```

---

## Self-Review Notes

Checked against `docs/superpowers/specs/2026-07-22-walkthrough-design.md`:

- Every spec section maps to a task: server module → 2–3, `steps.py` → 1, skill contract → 4, step model → 5, client → 6, controller + anchor resolution → 7, mode A → 8, mode B → 9, gutter badges → 10, shortcuts + toggle → 11, edge cases + verification → 12.
- Spec's `role` values, `step:<id>` anchor form, `snippet`-over-`line` anchoring, one-active-tour rule, and ephemerality all appear in the implementation, not just the prose.
- Non-goals stay out: no Claude-driven edits, no resume, no multiple tours, no web UI, no branching.
- Known deliberate deferral: the exact keystrokes are chosen in Task 11 Step 1 against the live keymap, with named fallbacks — not left as "TBD".

