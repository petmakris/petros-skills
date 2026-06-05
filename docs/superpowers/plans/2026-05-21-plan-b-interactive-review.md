# Plan B — Interactive-review skill

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement task-by-task.

**Goal:** New skill `interactive-review` that renders a GitHub PR diff in a browser, lets the user click any line to ask Claude a question, and threads the Q&A inline on that line. Code is immutable; conversation accumulates. Built on top of the shared `web_companion` library landed in Plan A.

**Architecture:** Same shape as annotate but with diff hunks rendered instead of markdown blocks, and append-only threads instead of in-place block rewrites. Per-line submit → event → watcher emits → Claude appends a message to the line's thread → page polls the thread version → renders the appended message.

**Tech Stack:** Python stdlib (HTTP + diff parsing), `gh` CLI for PR fetch, vanilla JS + markdown-it (already in shared static).

**Reference spec:** `docs/superpowers/specs/2026-05-21-incremental-annotate-and-interactive-review-design.md` (sections "Interactive-review skill" and "Interactive-review specifics").

---

## File Structure

**Created:**

```
skills/interactive-review/
  __init__.py
  server.py              ~120 lines — HandlersProtocol impl
  ensure_server.sh       10-line wrapper around shared ensure_server.sh
  diff.py                gh-CLI fetch + unified-diff parser → files.json shape
  threads.py             per-anchor thread persistence (load, append, save)
  SKILL.md
  README.md
  static/
    review.js            diff render, line-click composer, thread bubbles, polling
    review.css           diff styles, thread layout
  tests/
    __init__.py
    test_diff.py
    test_threads.py
    test_server.py
docs/superpowers/plans/2026-05-21-plan-b-interactive-review.md   (THIS FILE)
```

Port range: `54620–54640`.
Server-info path: `~/.claude/interactive-review/server.json`.
Banner: `interactive-review-server v1`.

---

## Task B1: diff.py — fetch + parse PR diff

**Files:**
- Create: `skills/interactive-review/diff.py`
- Create: `skills/interactive-review/tests/test_diff.py`
- Create: `skills/interactive-review/__init__.py` (empty)
- Create: `skills/interactive-review/tests/__init__.py` (empty)

- [ ] **Step 1:** Create empty `__init__.py` files for the new packages.

- [ ] **Step 2:** Write `skills/interactive-review/tests/test_diff.py`:

```python
import pytest
from skills.interactive_review.diff import parse_unified_diff, FileChange, Hunk, Line


def test_parse_empty_diff_returns_empty_list():
    assert parse_unified_diff("") == []


def test_parse_single_file_single_hunk():
    diff = """diff --git a/src/foo.py b/src/foo.py
index abc..def 100644
--- a/src/foo.py
+++ b/src/foo.py
@@ -1,3 +1,4 @@
 def existing():
     pass
+def new():
+    return 1
"""
    files = parse_unified_diff(diff)
    assert len(files) == 1
    assert files[0].path == "src/foo.py"
    assert len(files[0].hunks) == 1
    h = files[0].hunks[0]
    assert h.old_start == 1 and h.old_lines == 3
    assert h.new_start == 1 and h.new_lines == 4
    # The two leading context lines + the two added lines
    sides = [l.side for l in h.lines]
    assert sides == ["context", "context", "added", "added"]


def test_parse_handles_removals():
    diff = """diff --git a/x b/x
--- a/x
+++ b/x
@@ -1,3 +1,2 @@
 a
-b
 c
"""
    files = parse_unified_diff(diff)
    assert len(files[0].hunks[0].lines) == 3
    sides = [l.side for l in files[0].hunks[0].lines]
    assert sides == ["context", "removed", "context"]


def test_parse_assigns_old_and_new_line_numbers():
    diff = """diff --git a/x b/x
--- a/x
+++ b/x
@@ -10,3 +10,4 @@
 a
+new
 b
 c
"""
    files = parse_unified_diff(diff)
    lines = files[0].hunks[0].lines
    # context "a": old=10, new=10
    assert (lines[0].old, lines[0].new) == (10, 10)
    # added "new": old=None, new=11
    assert (lines[1].old, lines[1].new) == (None, 11)
    # context "b": old=11, new=12
    assert (lines[2].old, lines[2].new) == (11, 12)
    # context "c": old=12, new=13
    assert (lines[3].old, lines[3].new) == (12, 13)


def test_parse_multi_file():
    diff = """diff --git a/a b/a
--- a/a
+++ b/a
@@ -1 +1,2 @@
 x
+y
diff --git a/b b/b
--- a/b
+++ b/b
@@ -1 +1 @@
-old
+new
"""
    files = parse_unified_diff(diff)
    assert [f.path for f in files] == ["a", "b"]
    assert len(files[1].hunks[0].lines) == 2
```

- [ ] **Step 3:** Run, expect ImportError:

```bash
cd ~/projects/petros-skills
PYTHONPATH=. pytest skills/interactive-review/tests/test_diff.py -v
```

- [ ] **Step 4:** Write `skills/interactive-review/diff.py`:

```python
"""Unified-diff parser + gh-CLI fetcher for interactive-review.

The skill snapshots a PR's diff at session-open time. The diff comes from
`gh pr diff` (raw unified-diff format). We parse it into a typed structure
(FileChange / Hunk / Line) the renderer can consume directly.

Line anchors used by the rest of the skill have the form:
    <path>:<side>:<linenum>           e.g. src/server.py:R:42
    <path>:<side>:<start>-<end>       e.g. src/server.py:R:42-58

`side` is L (base/left) or R (head/right). We expose helpers that convert
between (file, hunk, Line) and anchors.
"""
from __future__ import annotations

import json
import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Line:
    side: str             # "context" | "added" | "removed"
    old: int | None       # old-file line number (1-based) or None for added
    new: int | None       # new-file line number (1-based) or None for removed
    text: str             # line content, no trailing newline


@dataclass
class Hunk:
    old_start: int
    old_lines: int
    new_start: int
    new_lines: int
    lines: list[Line] = field(default_factory=list)


@dataclass
class FileChange:
    path: str
    hunks: list[Hunk] = field(default_factory=list)


_HUNK_RE = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


def parse_unified_diff(text: str) -> list[FileChange]:
    """Parse a unified diff into a list of FileChange.

    Robust against the variations gh emits: file headers, missing-newline
    markers, binary blobs (skipped), and mode-only changes (skipped).
    """
    files: list[FileChange] = []
    current_file: FileChange | None = None
    current_hunk: Hunk | None = None
    old_ln = 0
    new_ln = 0

    for raw in text.splitlines():
        if raw.startswith("diff --git "):
            # New file. The path is recovered from the +++ header for safety.
            if current_file is not None:
                files.append(current_file)
            current_file = FileChange(path="")
            current_hunk = None
            continue
        if raw.startswith("+++ "):
            # +++ b/path or +++ /dev/null
            tail = raw[4:].strip()
            if tail == "/dev/null":
                if current_file:
                    current_file.path = "/dev/null"
            else:
                if tail.startswith("b/"):
                    tail = tail[2:]
                if current_file is not None:
                    current_file.path = tail
            continue
        if raw.startswith("--- ") or raw.startswith("index "):
            continue
        if raw.startswith("Binary files "):
            # skip binary entries — they have no hunks
            continue
        m = _HUNK_RE.match(raw)
        if m:
            old_start = int(m.group(1))
            old_lines = int(m.group(2)) if m.group(2) else 1
            new_start = int(m.group(3))
            new_lines = int(m.group(4)) if m.group(4) else 1
            current_hunk = Hunk(old_start=old_start, old_lines=old_lines,
                                new_start=new_start, new_lines=new_lines)
            old_ln = old_start
            new_ln = new_start
            if current_file is not None:
                current_file.hunks.append(current_hunk)
            continue
        if current_hunk is None:
            continue
        if raw.startswith("+"):
            current_hunk.lines.append(Line(side="added", old=None, new=new_ln, text=raw[1:]))
            new_ln += 1
        elif raw.startswith("-"):
            current_hunk.lines.append(Line(side="removed", old=old_ln, new=None, text=raw[1:]))
            old_ln += 1
        elif raw.startswith(" ") or raw == "":
            text_body = raw[1:] if raw.startswith(" ") else ""
            current_hunk.lines.append(Line(side="context", old=old_ln, new=new_ln, text=text_body))
            old_ln += 1
            new_ln += 1
        elif raw.startswith("\\"):
            # "\ No newline at end of file" — ignore
            continue
    if current_file is not None:
        files.append(current_file)
    return [f for f in files if f.path and f.path != "/dev/null"]


def files_to_json(files: list[FileChange]) -> list[dict]:
    """Serialize FileChange list to plain JSON for files.json on disk."""
    out = []
    for f in files:
        added = sum(1 for h in f.hunks for l in h.lines if l.side == "added")
        removed = sum(1 for h in f.hunks for l in h.lines if l.side == "removed")
        out.append({
            "path": f.path,
            "added": added,
            "removed": removed,
            "hunks": [
                {
                    "old_start": h.old_start, "old_lines": h.old_lines,
                    "new_start": h.new_start, "new_lines": h.new_lines,
                    "lines": [
                        {"side": l.side, "old": l.old, "new": l.new, "text": l.text}
                        for l in h.lines
                    ],
                }
                for h in f.hunks
            ],
        })
    return out


def fetch_pr_diff(pr_ref: str) -> tuple[str, dict]:
    """Run gh pr diff + gh pr view for the given PR ref.

    Returns (diff_text, meta_dict). Raises CalledProcessError on failure.
    """
    diff = subprocess.check_output(["gh", "pr", "diff", pr_ref, "--patch"], text=True)
    meta_json = subprocess.check_output(
        ["gh", "pr", "view", pr_ref, "--json", "title,headRefName,baseRefName,author,url,headRefOid"],
        text=True,
    )
    meta = json.loads(meta_json)
    return diff, meta
```

- [ ] **Step 5:** Run, expect 5 PASS.

- [ ] **Step 6:** Commit:

```bash
git add skills/interactive-review/diff.py skills/interactive-review/tests/test_diff.py \
        skills/interactive-review/__init__.py skills/interactive-review/tests/__init__.py
git commit -m "interactive-review: diff fetch + unified-diff parser"
```

---

## Task B2: threads.py — per-anchor append-only threads

**Files:**
- Create: `skills/interactive-review/threads.py`
- Create: `skills/interactive-review/tests/test_threads.py`

- [ ] **Step 1:** Write failing test:

```python
# skills/interactive-review/tests/test_threads.py
import json
from pathlib import Path

import pytest

from skills.interactive_review.threads import (
    load, save_atomic, append_message, valid_anchor, list_versions
)


def test_load_missing_returns_empty(tmp_path):
    t = load(tmp_path, "src/x.py:R:42")
    assert t == {"anchor": "src/x.py:R:42", "version": 0, "messages": []}


def test_append_user_message_creates_thread(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    append_message(threads_dir, "src/x.py:R:42",
                   {"role": "user", "ts": 100, "text": "why?",
                    "selected_text": "y", "images": [], "source_event_id": "e1"})
    t = load(threads_dir, "src/x.py:R:42")
    assert t["version"] == 1
    assert len(t["messages"]) == 1
    assert t["messages"][0]["role"] == "user"


def test_append_dedups_by_source_event_id(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    msg = {"role": "claude", "ts": 100, "text": "answer", "source_event_id": "e2"}
    append_message(threads_dir, "a:R:1", msg)
    append_message(threads_dir, "a:R:1", msg)  # dup
    t = load(threads_dir, "a:R:1")
    assert len(t["messages"]) == 1
    assert t["version"] == 1


def test_valid_anchor_single_line():
    assert valid_anchor("src/foo.py:R:42") is True
    assert valid_anchor("src/foo.py:L:1") is True


def test_valid_anchor_range():
    assert valid_anchor("src/foo.py:R:42-58") is True


def test_invalid_anchor():
    assert valid_anchor("src/foo.py") is False
    assert valid_anchor("src/foo.py:X:1") is False
    assert valid_anchor("src/foo.py:R:abc") is False
    assert valid_anchor("src/foo.py:R:") is False
    assert valid_anchor("__general__") is True  # special


def test_list_versions(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    append_message(threads_dir, "a:R:1", {"role": "user", "ts": 1, "text": "x", "source_event_id": "e1"})
    append_message(threads_dir, "a:R:1", {"role": "claude", "ts": 2, "text": "y", "source_event_id": "e2"})
    append_message(threads_dir, "b:R:1", {"role": "user", "ts": 3, "text": "z", "source_event_id": "e3"})
    vs = list_versions(threads_dir)
    assert vs == {"a:R:1": 2, "b:R:1": 1}
```

- [ ] **Step 2:** Run, expect ImportError.

- [ ] **Step 3:** Write `skills/interactive-review/threads.py`:

```python
"""Per-anchor append-only thread persistence for interactive-review.

Each thread lives in <state_dir>/threads/<encoded_anchor>.json with shape:
    {
      "anchor": "<path>:<side>:<line>",
      "version": <int>,
      "messages": [{role, ts, text, ...}, ...]
    }

Threads are append-only; dedup is by source_event_id stored in each message.
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any


_ANCHOR_RE = re.compile(r"^[^/:]+(?:/[^/:]+)*:[LR]:\d+(?:-\d+)?$")
GENERAL_ANCHOR = "__general__"


def valid_anchor(anchor: str) -> bool:
    if anchor == GENERAL_ANCHOR:
        return True
    return bool(_ANCHOR_RE.match(anchor))


def _encode_anchor(anchor: str) -> str:
    return anchor.replace("/", "__").replace(":", "_")


def _path_for(threads_dir: Path, anchor: str) -> Path:
    return Path(threads_dir) / f"{_encode_anchor(anchor)}.json"


def load(threads_dir: Path, anchor: str) -> dict:
    p = _path_for(threads_dir, anchor)
    if not p.exists():
        return {"anchor": anchor, "version": 0, "messages": []}
    try:
        return json.loads(p.read_text())
    except (json.JSONDecodeError, OSError):
        return {"anchor": anchor, "version": 0, "messages": []}


def save_atomic(threads_dir: Path, thread: dict) -> None:
    Path(threads_dir).mkdir(parents=True, exist_ok=True)
    p = _path_for(threads_dir, thread["anchor"])
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(thread, indent=2))
    tmp.replace(p)


def append_message(threads_dir: Path, anchor: str, msg: dict) -> bool:
    """Append a message to the anchor's thread, dedup by source_event_id.

    Returns True if appended (version bumped), False if duplicate (no-op).
    """
    t = load(threads_dir, anchor)
    seid = msg.get("source_event_id")
    if seid is not None:
        for existing in t["messages"]:
            if existing.get("source_event_id") == seid:
                return False
    t["messages"].append(msg)
    t["version"] = int(t.get("version", 0)) + 1
    save_atomic(threads_dir, t)
    return True


def list_versions(threads_dir: Path) -> dict[str, int]:
    """Return {anchor: version} across all threads under threads_dir."""
    threads_dir = Path(threads_dir)
    if not threads_dir.is_dir():
        return {}
    out: dict[str, int] = {}
    for p in threads_dir.iterdir():
        if p.suffix == ".json":
            try:
                t = json.loads(p.read_text())
            except (json.JSONDecodeError, OSError):
                continue
            anchor = t.get("anchor")
            if isinstance(anchor, str):
                out[anchor] = int(t.get("version", 0))
    return out
```

- [ ] **Step 4:** Run, expect 7 PASS.

- [ ] **Step 5:** Commit:

```bash
git add skills/interactive-review/threads.py skills/interactive-review/tests/test_threads.py
git commit -m "interactive-review: per-anchor append-only threads"
```

---

## Task B3: server.py + ensure_server.sh

**Files:**
- Create: `skills/interactive-review/server.py`
- Create: `skills/interactive-review/ensure_server.sh` (executable)

- [ ] **Step 1:** Write `skills/interactive-review/server.py`:

```python
"""Interactive-review skill — thin handlers module over web_companion.

Renders a GitHub-style PR diff. Each line is a potential anchor for an
inline thread. /api/submit appends a user message to the thread AND
enqueues an event for Claude. Claude wakes via watcher, reads context,
appends a claude-role message to the thread, acks.
"""
from __future__ import annotations

import json
import sys
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler
from pathlib import Path

from skills._shared.web_companion import server as wc_server
from skills._shared.web_companion import events as events_module
from skills._shared.web_companion.templates import html_escape, render_page
from skills.interactive_review import diff as diff_module
from skills.interactive_review import threads as threads_module

STATIC_DIR = Path(__file__).resolve().parent / "static"
SHARED_STATIC_DIR = Path(__file__).resolve().parent.parent / "_shared" / "web_companion" / "static"

PORT_RANGE = range(54620, 54641)
BANNER = "interactive-review-server v1"

WAITING_HTML = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Waiting</title>
<link rel="stylesheet" href="/static/core.css">
<link rel="stylesheet" href="/static/review.css"></head>
<body><main class="waiting"><p>Loading PR diff…</p></main></body></html>
"""

CLOSED_HTML = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Closed</title>
<link rel="stylesheet" href="/static/core.css">
<link rel="stylesheet" href="/static/review.css"></head>
<body><main class="waiting"><p>This review session is closed.</p></main></body></html>
"""


def _read_meta(state_dir: Path) -> dict:
    p = state_dir / "meta.json"
    if not p.exists():
        return {}
    try:
        return json.loads(p.read_text())
    except json.JSONDecodeError:
        return {}


def _is_terminal(state_dir: Path) -> bool:
    return (state_dir / "finished").exists() or (state_dir / "cancelled").exists()


class Handlers:
    def serve_root(self, h: BaseHTTPRequestHandler, dirs: dict) -> None:
        state_dir = Path(dirs["state_dir"])
        if _is_terminal(state_dir):
            _send_html(h, 200, CLOSED_HTML)
            return
        meta = _read_meta(state_dir)
        if not meta:
            _send_html(h, 200, WAITING_HTML)
            return
        title = meta.get("title", "PR Review")
        body = (
            f'<header class="page-header"><div class="header-title">'
            f'<span class="header-emoji">🔍</span>'
            f'<span class="header-text">{html_escape(title)}</span>'
            f'</div><div class="header-actions">'
            f'<button id="done-btn" type="button" class="done-btn">Done</button>'
            f'</div></header>'
            f'<main class="review"></main>'
            f'<section class="general-section">'
            f'  <h3>General review comments</h3>'
            f'  <div id="general-thread"></div>'
            f'  <button id="add-general" type="button" class="add-general-btn">'
            f'    <span class="plus">+</span><span>Add general comment</span>'
            f'  </button>'
            f'</section>'
        )
        head = ('<link rel="stylesheet" href="/static/review.css">'
                '<script src="/static/review.js" defer></script>')
        page = render_page(title=title, head_assets=head, body_html=body)
        _send_html(h, 200, page)

    def serve_data(self, h: BaseHTTPRequestHandler, dirs: dict, query: str) -> None:
        state_dir = Path(dirs["state_dir"])
        threads_dir = state_dir / "threads"
        # /files → files.json contents
        if query == "files":
            files_path = state_dir / "files.json"
            if not files_path.exists():
                _send_json(h, 404, {"error": "no diff"})
                return
            data = files_path.read_bytes()
            h.send_response(200)
            h.send_header("Content-Type", "application/json; charset=utf-8")
            h.send_header("Content-Length", str(len(data)))
            h.end_headers()
            h.wfile.write(data)
            return
        # /thread?anchor=<encoded>
        if query.startswith("thread"):
            qs = query.split("?", 1)[1] if "?" in query else ""
            params = urllib.parse.parse_qs(qs)
            anchor = params.get("anchor", [None])[0]
            if not anchor:
                _send_text(h, 400, "missing anchor")
                return
            anchor = urllib.parse.unquote(anchor)
            if not threads_module.valid_anchor(anchor):
                _send_text(h, 400, "bad anchor")
                return
            t = threads_module.load(threads_dir, anchor)
            _send_json(h, 200, t)
            return
        _send_text(h, 404, "not found")

    def handle_submit(self, h: BaseHTTPRequestHandler, dirs: dict, payload: dict) -> None:
        state_dir = Path(dirs["state_dir"])
        if _is_terminal(state_dir):
            _send_text(h, 409, "session closed")
            return
        anchor = payload.get("anchor")
        comment_type = payload.get("type", "comment")
        text = payload.get("text", "")
        selected_text = payload.get("selected_text")
        images = payload.get("images", [])
        if not isinstance(anchor, str) or not threads_module.valid_anchor(anchor):
            _send_text(h, 400, "bad anchor")
            return
        if comment_type not in ("comment", "reject"):
            _send_text(h, 400, "bad type")
            return
        if not isinstance(text, str):
            _send_text(h, 400, "bad text")
            return
        # Append the user message to the thread immediately (optimistic).
        evt = {
            "anchor": anchor,
            "type": comment_type,
            "text": text,
            "selected_text": selected_text,
            "images": images,
        }
        eid = events_module.append(Path(dirs["events_dir"]), evt)
        threads_dir = state_dir / "threads"
        threads_module.append_message(threads_dir, anchor, {
            "role": "user",
            "ts": int(time.time()),
            "text": text,
            "selected_text": selected_text,
            "images": images,
            "source_event_id": f"user-{eid}",
        })
        _send_json(h, 202, {"event_id": eid, "status": "queued"})

    def serve_poll(self, h: BaseHTTPRequestHandler, dirs: dict) -> None:
        state_dir = Path(dirs["state_dir"])
        threads_dir = state_dir / "threads"
        versions = threads_module.list_versions(threads_dir)
        hb_path = state_dir / "watcher_heartbeat"
        try:
            hb = int(hb_path.read_text().strip())
        except (FileNotFoundError, ValueError):
            hb = 0
        _send_json(h, 200, {
            "threads": versions,
            "watcher_seen_at": hb,
            "finished": _is_terminal(state_dir),
        })

    def create_session_extra(self, payload: dict, dirs: dict) -> dict | None:
        state_dir = Path(dirs["state_dir"])
        (state_dir / "threads").mkdir(exist_ok=True)
        pr_ref = payload.get("pr")
        if not isinstance(pr_ref, str) or not pr_ref:
            raise ValueError("payload missing 'pr' (PR number, URL, or branch)")
        try:
            diff_text, meta = diff_module.fetch_pr_diff(pr_ref)
        except Exception as e:
            raise ValueError(f"gh pr fetch failed: {e}") from e
        files = diff_module.parse_unified_diff(diff_text)
        files_json = diff_module.files_to_json(files)
        (state_dir / "diff.patch").write_text(diff_text)
        (state_dir / "files.json").write_text(json.dumps(files_json, indent=2))
        (state_dir / "meta.json").write_text(json.dumps({
            "pr_ref": pr_ref,
            "title": meta.get("title", pr_ref),
            "head": meta.get("headRefName", ""),
            "base": meta.get("baseRefName", ""),
            "author": (meta.get("author") or {}).get("login", ""),
            "url": meta.get("url", ""),
            "head_oid": meta.get("headRefOid", ""),
            "fetched_at": int(time.time()),
        }, indent=2))
        return {"pr_ref": pr_ref, "title": meta.get("title", pr_ref)}


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
        skill_name="interactive-review",
        port_range=PORT_RANGE,
        handlers=Handlers(),
        static_dirs=[SHARED_STATIC_DIR, STATIC_DIR],
    )


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2:** Write `skills/interactive-review/ensure_server.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
export PLUGIN_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null && pwd)"
export SKILL="interactive-review"
export MODULE="skills.interactive_review.server"
export BANNER="interactive-review-server v1"
exec "$PLUGIN_ROOT/skills/_shared/web_companion/ensure_server.sh"
```

- [ ] **Step 3:** Note that `skills.interactive_review` uses underscore in the Python module path because the dir is `interactive-review`. Python would refuse to import a hyphenated module name directly. **Resolution:** rename the directory to `interactive_review` (underscore), update all references in this plan accordingly. Or use Python's `importlib` shim. **Pick:** rename to underscore. The skill name as seen by the rest of the system (skill_name="interactive-review", URL path, server.json path) stays hyphenated; only the Python module dir is renamed.

So during this task: ensure the directory is `skills/interactive_review/` (underscore), and update the ensure_server.sh to:

```
export MODULE="skills.interactive_review.server"
```

If you already created the dir as `interactive-review`, rename it:

```bash
cd ~/projects/petros-skills
git mv skills/interactive-review skills/interactive_review
```

- [ ] **Step 4:** chmod +x and verify import:

```bash
chmod +x skills/interactive_review/ensure_server.sh
PYTHONPATH=. python3 -c "from skills.interactive_review import server; print('ok')"
```

- [ ] **Step 5:** Commit:

```bash
git add skills/interactive_review/server.py skills/interactive_review/ensure_server.sh
git commit -m "interactive-review: server.py + ensure_server.sh"
```

---

## Task B4: Server tests for interactive-review

**Files:**
- Create: `skills/interactive_review/tests/test_server.py`

- [ ] **Step 1:** Write tests. Since the session-create path calls `gh pr diff`, the test must either mock that or use a tiny fixture diff. Mocking is cleaner.

```python
# skills/interactive_review/tests/test_server.py
import json
from pathlib import Path
from io import BytesIO
from unittest.mock import patch, MagicMock

import pytest

from skills.interactive_review.server import Handlers
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
    }


def test_serve_root_waiting_when_no_meta(tmp_path):
    h = Handlers()
    handler = make_handler()
    h.serve_root(handler, make_dirs(tmp_path))
    handler.send_response.assert_called_once_with(200)
    # html body should contain "Loading"
    written = handler.wfile.getvalue()
    assert b"Loading PR diff" in written


def test_serve_root_renders_when_meta_present(tmp_path):
    dirs = make_dirs(tmp_path)
    (dirs["state_dir"] / "meta.json").write_text(json.dumps({"title": "PR #42"}))
    h = Handlers()
    handler = make_handler()
    h.serve_root(handler, dirs)
    handler.send_response.assert_called_with(200)
    written = handler.wfile.getvalue()
    assert b"PR #42" in written
    assert b"done-btn" in written
    assert b"review.js" in written


def test_serve_files_returns_files_json(tmp_path):
    dirs = make_dirs(tmp_path)
    files = [{"path": "x", "added": 1, "removed": 0, "hunks": []}]
    (dirs["state_dir"] / "files.json").write_text(json.dumps(files))
    h = Handlers()
    handler = make_handler()
    h.serve_data(handler, dirs, "files")
    handler.send_response.assert_called_with(200)
    written = handler.wfile.getvalue()
    assert b'"path"' in written


def test_serve_files_404_when_missing(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    h.serve_data(handler, dirs, "files")
    handler.send_response.assert_called_with(404)


def test_serve_thread_returns_empty_for_missing(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    h.serve_data(handler, dirs, "thread?anchor=src%2Fx.py%3AR%3A42")
    handler.send_response.assert_called_with(200)
    written = handler.wfile.getvalue().decode("utf-8")
    body_json = json.loads(written.split("\r\n\r\n", 1)[-1] if "\r\n\r\n" in written else written.split("\n\n", 1)[-1] if "\n\n" in written else written)
    # The handler.wfile contains only the body (we mocked the send_response/headers).
    # The full response_text is the JSON body — extract:
    # Simplest: just check it parses and has expected shape.
    # Our mock doesn't include headers in wfile, so written is just JSON.
    body = json.loads(handler.wfile.getvalue())
    assert body["anchor"] == "src/x.py:R:42"
    assert body["version"] == 0
    assert body["messages"] == []


def test_handle_submit_queues_event_and_appends_user(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    payload = {"anchor": "src/x.py:R:42", "type": "comment", "text": "why?"}
    h.handle_submit(handler, dirs, payload)
    handler.send_response.assert_called_with(202)
    events = list((dirs["state_dir"] / "events").iterdir())
    assert len(events) == 1
    threads = list((dirs["state_dir"] / "threads").iterdir())
    assert len(threads) == 1
    t = json.loads(threads[0].read_text())
    assert t["messages"][0]["role"] == "user"
    assert t["messages"][0]["text"] == "why?"


def test_handle_submit_rejects_bad_anchor(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    h.handle_submit(handler, dirs, {"anchor": "garbage", "type": "comment", "text": "x"})
    handler.send_response.assert_called_with(400)


def test_handle_submit_409_when_finished(tmp_path):
    dirs = make_dirs(tmp_path)
    (dirs["state_dir"] / "finished").write_text("")
    h = Handlers()
    handler = make_handler()
    h.handle_submit(handler, dirs, {"anchor": "src/x.py:R:42", "type": "comment", "text": "x"})
    handler.send_response.assert_called_with(409)


def test_serve_poll_returns_thread_versions(tmp_path):
    dirs = make_dirs(tmp_path)
    threads_module.append_message(
        dirs["state_dir"] / "threads", "src/x.py:R:42",
        {"role": "user", "ts": 1, "text": "x", "source_event_id": "e1"},
    )
    h = Handlers()
    handler = make_handler()
    h.serve_poll(handler, dirs)
    handler.send_response.assert_called_with(200)
    body = json.loads(handler.wfile.getvalue())
    assert body["threads"] == {"src/x.py:R:42": 1}
    assert body["finished"] is False


def test_create_session_extra_fetches_diff(tmp_path, monkeypatch):
    dirs = make_dirs(tmp_path)
    sample_diff = """diff --git a/x b/x
--- a/x
+++ b/x
@@ -1 +1,2 @@
 a
+b
"""
    sample_meta = {
        "title": "Test PR", "headRefName": "feat", "baseRefName": "main",
        "author": {"login": "petros"}, "url": "https://example", "headRefOid": "deadbeef",
    }
    with patch("skills.interactive_review.diff.fetch_pr_diff",
               return_value=(sample_diff, sample_meta)):
        h = Handlers()
        h.create_session_extra({"pr": "42"}, dirs)
    assert (dirs["state_dir"] / "diff.patch").exists()
    assert (dirs["state_dir"] / "files.json").exists()
    meta = json.loads((dirs["state_dir"] / "meta.json").read_text())
    assert meta["title"] == "Test PR"
    assert meta["pr_ref"] == "42"


def test_create_session_extra_requires_pr(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    with pytest.raises(ValueError, match="missing 'pr'"):
        h.create_session_extra({}, dirs)
```

The test at `test_serve_thread_returns_empty_for_missing` has an ugly fallback — adjust the assertion shape based on what the handler actually writes (mocks don't include headers in `wfile.getvalue()`; the JSON body is the entire `wfile` content). Simplify if needed.

- [ ] **Step 2:** Run:

```bash
cd ~/projects/petros-skills
PYTHONPATH=. pytest skills/interactive_review/tests/ -v
```

Expect ~10 PASS.

- [ ] **Step 3:** Commit:

```bash
git add skills/interactive_review/tests/test_server.py
git commit -m "interactive-review: server-handler tests"
```

---

## Task B5: Client — review.js + review.css

**Files:**
- Create: `skills/interactive_review/static/review.css`
- Create: `skills/interactive_review/static/review.js`

- [ ] **Step 1:** Write `skills/interactive_review/static/review.css`:

```css
/* interactive-review specific styles */

main.review {
  padding: 12px 0;
}

.file-block {
  margin: 16px 12px;
  border: 1px solid var(--border, #555);
  border-radius: 6px;
  overflow: hidden;
}

.file-header {
  padding: 6px 10px;
  background: var(--bg-elev, #2a2a2a);
  font-family: ui-monospace, monospace;
  font-size: 13px;
  color: var(--fg-dim, #aaa);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.file-header .file-stats {
  font-size: 12px;
}

.file-header .file-stats .added { color: #2da44e; }
.file-header .file-stats .removed { color: #cf222e; }

.hunk-header {
  padding: 4px 10px;
  background: var(--bg-elev2, #1c1c1c);
  font-family: ui-monospace, monospace;
  font-size: 12px;
  color: var(--fg-dim, #888);
  border-top: 1px solid var(--border, #555);
}

.diff-line {
  font-family: ui-monospace, monospace;
  font-size: 12.5px;
  line-height: 1.45;
  padding: 1px 0;
  display: flex;
  cursor: pointer;
}

.diff-line:hover .diff-text {
  background: var(--hover, rgba(255,255,255,0.04));
}

.diff-line .gutter-old, .diff-line .gutter-new {
  flex: 0 0 50px;
  padding: 0 8px;
  text-align: right;
  color: var(--fg-dim, #777);
  user-select: none;
}

.diff-line .diff-text {
  flex: 1 1 auto;
  white-space: pre;
  padding: 0 8px;
}

.diff-line.added .diff-text { background: rgba(45, 164, 78, 0.15); color: var(--fg, #ddd); }
.diff-line.added .diff-text::before { content: "+"; padding-right: 4px; color: #2da44e; }
.diff-line.removed .diff-text { background: rgba(207, 34, 46, 0.15); color: var(--fg, #ddd); }
.diff-line.removed .diff-text::before { content: "-"; padding-right: 4px; color: #cf222e; }
.diff-line.context .diff-text::before { content: " "; padding-right: 4px; }

.diff-line.selected .diff-text {
  outline: 2px solid var(--accent, #7ee0c2);
}

.thread {
  margin: 4px 50px 8px 50px;
  padding: 6px 10px;
  border-left: 3px solid var(--accent, #7ee0c2);
  background: var(--bg-elev2, #1c1c1c);
  font-family: var(--font-prose, system-ui);
  font-size: 13.5px;
}

.thread-message {
  padding: 6px 0;
  border-bottom: 1px dashed var(--border, #444);
}
.thread-message:last-child { border-bottom: none; }

.thread-message .role-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--fg-dim, #888);
  margin-right: 6px;
}
.thread-message.role-claude .role-label { color: #7ee0c2; }
.thread-message.role-user .role-label { color: #c4b5fd; }

.thread-message .message-content {
  margin-top: 4px;
}

.thread-message .message-content p:first-child { margin-top: 0; }
.thread-message .message-content p:last-child { margin-bottom: 0; }
.thread-message .message-content pre {
  background: var(--bg, #111);
  padding: 6px 8px;
  border-radius: 4px;
  overflow-x: auto;
  font-size: 12px;
}

.thread .thinking {
  font-style: italic;
  color: var(--fg-dim, #888);
  font-size: 12px;
}

.composer {
  margin: 6px 50px 10px 50px;
  padding: 8px;
  border: 1px solid var(--border, #555);
  border-radius: 6px;
  background: var(--bg-elev, #2a2a2a);
}

.composer textarea {
  width: 100%;
  min-height: 60px;
  background: transparent;
  color: var(--fg, #ddd);
  border: none;
  resize: vertical;
  font-family: inherit;
  font-size: 13.5px;
  outline: none;
}

.composer .composer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 4px;
}

.composer .composer-actions .cancel {
  background: transparent;
  color: var(--fg-dim, #888);
  border: none;
  cursor: pointer;
  font-size: 12px;
}
```

- [ ] **Step 2:** Write `skills/interactive_review/static/review.js`:

```javascript
// interactive-review client. Renders a PR diff, supports per-line comment
// threads, polls for updates.
(function () {
  const reviewEl = document.querySelector("main.review");
  const generalEl = document.getElementById("general-thread");
  const addGeneralBtn = document.getElementById("add-general");
  const doneBtn = document.getElementById("done-btn");

  const md = (typeof window.markdownit === "function")
    ? window.markdownit({ html: false, linkify: true, breaks: true })
    : null;

  const threadVersions = {};   // anchor → version
  let filesData = null;        // [{path, added, removed, hunks: [...]}, ...]

  function el(tag, attrs, ...children) {
    const e = document.createElement(tag);
    if (attrs) for (const k of Object.keys(attrs)) {
      if (k === "class") e.className = attrs[k];
      else if (k.startsWith("on") && typeof attrs[k] === "function") e.addEventListener(k.slice(2), attrs[k]);
      else if (k === "dataset") for (const dk of Object.keys(attrs.dataset)) e.dataset[dk] = attrs.dataset[dk];
      else e.setAttribute(k, attrs[k]);
    }
    for (const c of children) {
      if (c == null) continue;
      e.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    }
    return e;
  }

  function anchorFor(filePath, line) {
    return `${filePath}:${line.side === "removed" ? "L" : "R"}:${line.side === "removed" ? line.old : line.new}`;
  }

  function buildLine(filePath, line) {
    const lineEl = el("div", { class: `diff-line ${line.side}`, dataset: { anchor: anchorFor(filePath, line), side: line.side } },
      el("span", { class: "gutter-old" }, line.old != null ? String(line.old) : ""),
      el("span", { class: "gutter-new" }, line.new != null ? String(line.new) : ""),
      el("span", { class: "diff-text" }, line.text),
    );
    if (line.side !== "removed" || line.old != null) {
      lineEl.addEventListener("click", () => openComposer(filePath, line));
    }
    return lineEl;
  }

  function buildFile(fileData) {
    const file = el("div", { class: "file-block", dataset: { path: fileData.path } },
      el("div", { class: "file-header" },
        el("span", { class: "file-path" }, fileData.path),
        el("span", { class: "file-stats" },
          el("span", { class: "added" }, `+${fileData.added}`),
          el("span", null, " "),
          el("span", { class: "removed" }, `-${fileData.removed}`),
        ),
      ),
    );
    for (const h of fileData.hunks) {
      file.appendChild(el("div", { class: "hunk-header" },
        `@@ -${h.old_start},${h.old_lines} +${h.new_start},${h.new_lines} @@`));
      for (const line of h.lines) {
        file.appendChild(buildLine(fileData.path, line));
      }
    }
    return file;
  }

  function renderMessage(msg) {
    const role = msg.role === "claude" ? "claude" : "user";
    const content = md ? md.render(msg.text || "") : `<p>${(msg.text || "").replace(/&/g, "&amp;").replace(/</g, "&lt;")}</p>`;
    const wrap = el("div", { class: `thread-message role-${role}` },
      el("div", null,
        el("span", { class: "role-label" }, role === "claude" ? "Claude" : "You")),
    );
    const body = el("div", { class: "message-content" });
    body.innerHTML = content;
    wrap.appendChild(body);
    return wrap;
  }

  function ensureThreadContainerAfter(lineEl, anchor) {
    let next = lineEl.nextElementSibling;
    while (next && next.classList && (next.classList.contains("thread") || next.classList.contains("composer"))) {
      if (next.classList.contains("thread") && next.dataset.anchor === anchor) return next;
      next = next.nextElementSibling;
    }
    const t = el("div", { class: "thread", dataset: { anchor } });
    lineEl.after(t);
    return t;
  }

  async function renderThread(anchor) {
    const data = await WebCompanion.api.fetchJSON(`thread?anchor=${encodeURIComponent(anchor)}`);
    threadVersions[anchor] = data.version || 0;
    if (anchor === "__general__") {
      generalEl.innerHTML = "";
      for (const m of data.messages || []) generalEl.appendChild(renderMessage(m));
      return;
    }
    // Find the line el for this anchor
    const lineEl = document.querySelector(`.diff-line[data-anchor="${CSS.escape(anchor)}"]`);
    if (!lineEl) return;
    const container = ensureThreadContainerAfter(lineEl, anchor);
    container.innerHTML = "";
    for (const m of data.messages || []) container.appendChild(renderMessage(m));
    // Show "thinking" if last message is user (Claude hasn't replied yet)
    const msgs = data.messages || [];
    if (msgs.length && msgs[msgs.length - 1].role === "user") {
      container.appendChild(el("div", { class: "thinking" }, "Claude is thinking…"));
    }
  }

  function openComposer(filePath, line) {
    const anchor = anchorFor(filePath, line);
    const lineEl = document.querySelector(`.diff-line[data-anchor="${CSS.escape(anchor)}"]`);
    if (!lineEl) return;
    // Skip if a composer is already open for this anchor
    if (lineEl.nextElementSibling && lineEl.nextElementSibling.classList.contains("composer")
        && lineEl.nextElementSibling.dataset.anchor === anchor) {
      lineEl.nextElementSibling.querySelector("textarea").focus();
      return;
    }
    buildComposer(anchor, lineEl);
  }

  function buildComposer(anchor, anchorEl) {
    const ta = el("textarea", { placeholder: "Ask Claude…" });
    const submit = el("button", null, "Submit");
    const cancel = el("button", { class: "cancel" }, "cancel");
    const comp = el("div", { class: "composer", dataset: { anchor } },
      ta,
      el("div", { class: "composer-actions" }, cancel, submit),
    );
    cancel.addEventListener("click", () => comp.remove());
    submit.addEventListener("click", async () => {
      const text = ta.value.trim();
      if (!text) return;
      submit.disabled = true;
      submit.textContent = "Submitting…";
      try {
        await WebCompanion.api.submit({ anchor, type: "comment", text, images: [] });
        comp.remove();
        await renderThread(anchor);
      } catch (e) {
        submit.disabled = false;
        submit.textContent = "Submit";
        ta.placeholder = `Error: ${e.message}`;
      }
    });
    // Place composer right after the anchor element
    if (anchorEl) anchorEl.after(comp);
    else generalEl.appendChild(comp);
    ta.focus();
  }

  async function loadDiff() {
    try {
      filesData = await WebCompanion.api.fetchJSON("files");
    } catch (e) {
      reviewEl.innerHTML = `<p style="padding:20px">Failed to load diff: ${e.message}</p>`;
      return;
    }
    reviewEl.innerHTML = "";
    if (!filesData.length) {
      reviewEl.innerHTML = "<p style='padding:20px'>No changes in this PR.</p>";
      return;
    }
    for (const f of filesData) reviewEl.appendChild(buildFile(f));
  }

  function onPollDelta(data, prev) {
    const cur = data.threads || {};
    for (const anchor of Object.keys(cur)) {
      if (cur[anchor] !== threadVersions[anchor]) {
        renderThread(anchor);
      }
    }
  }

  if (addGeneralBtn) {
    addGeneralBtn.addEventListener("click", () => {
      // Open a composer pinned at the general section
      const existing = generalEl.parentElement.querySelector('.composer[data-anchor="__general__"]');
      if (existing) { existing.querySelector("textarea").focus(); return; }
      buildComposer("__general__", null);
    });
  }
  if (doneBtn) {
    doneBtn.addEventListener("click", async () => {
      if (!confirm("Finish this review session?")) return;
      await WebCompanion.api.finish();
      window.location.reload();
    });
  }

  loadDiff().then(() => {
    renderThread("__general__");
    WebCompanion.init({ onPollDelta });
  });
})();
```

- [ ] **Step 3:** Commit:

```bash
git add skills/interactive_review/static/review.css skills/interactive_review/static/review.js
git commit -m "interactive-review: client JS + CSS"
```

---

## Task B6: SKILL.md + README.md

**Files:**
- Create: `skills/interactive_review/SKILL.md`
- Create: `skills/interactive_review/README.md`

- [ ] **Step 1:** Write `skills/interactive_review/SKILL.md`. The agent should pattern-match against `skills/annotate/SKILL.md` (which describes the new shared protocol) and adapt to the threaded review flow. Sections to include:

  - **Frontmatter** — name, description (mentions "/interactive-review &lt;PR&gt;", inline per-line Q&A on a GitHub diff, watcher events `WEBCOMPANION_EVENT`/`FINISHED`/`CANCELLED`).
  - **Overview** — what the skill does, when to use.
  - **Invocation** — user types `/interactive-review <PR>` where PR is a number, URL, or branch ref.
  - **On every invocation: ensure the server is running** — pattern-match annotate's wording, point at `skills/interactive_review/ensure_server.sh`.
  - **Create a session** — POST `/api/sessions` with `{cwd, pr}`. Response includes the standard dirs + `pr_ref` and `title`.
  - **How to push** — there's nothing for Claude to push at session-open. The server already fetched the diff in `create_session_extra`. Claude's only role is "arm the watcher and wait."
  - **Arming the watcher** — same shape as annotate (`watcher.sh` with SKILL=interactive-review, SID, STATE_DIR, EVENTS_DIR, CONSUMED_DIR).
  - **Mode D — handling a watcher event** —
    - `WEBCOMPANION_EVENT`: parse `anchor`, `type`, `text`, `selected_text`, `images`. **Append a claude-role message** to `<state_dir>/threads/<encoded_anchor>.json` answering the user's question. Code context: read `<state_dir>/diff.patch`, optionally `Read`/`Grep` other files for wider context. **No code rewrites** — if a fix is warranted, put it in the thread message as a code block. Write `consumed/<event_id>.ack`. End turn, no terminal output.
    - `WEBCOMPANION_FINISHED` / `CANCELLED`: ack briefly, remove pending-registry entry.
  - **Response-message contract** — short, code-aware, markdown-friendly. Use code blocks for suggested fixes. If the question is broad (not a specific-line clarification), answer in 2-4 sentences with optional code snippet.
  - **Re-apply safety** — same as annotate: `source_event_id` dedup in `threads.append_message` makes re-emission of the same event idempotent.
  - **Edge cases** — gh failure (session-create fails with 500), large PR (skill warns/paginates), PR updated mid-session (banner shown).
  - **Token budget** — a single event wake is small (one line of context + one short answer). Don't try to give a comprehensive PR review per line — answer specifically what the user asked.

  Length: ~150-200 lines, matching annotate's SKILL.md density.

- [ ] **Step 2:** Write `skills/interactive_review/README.md` — short, ~40-50 lines, similar shape to annotate's README.

- [ ] **Step 3:** Commit:

```bash
git add skills/interactive_review/SKILL.md skills/interactive_review/README.md
git commit -m "interactive-review: SKILL.md + README"
```

---

## Task B7: Smoke test gate

- [ ] **Step 1:** Verify all tests pass:

```bash
cd ~/projects/petros-skills
PYTHONPATH=. pytest skills/_shared/web_companion/tests/ skills/annotate/tests/ skills/interactive_review/tests/ -v
```

Expect everything green.

- [ ] **Step 2:** Start the interactive-review server:

```bash
pkill -f 'python3 -m skills.interactive_review.server' || true
rm -f ~/.claude/interactive-review/server.json ~/.claude/interactive-review/server.pid
./skills/interactive_review/ensure_server.sh
URL=$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/interactive-review/server.json")))["url"])')
echo "URL=$URL"
curl -s "$URL/health"
# Expected: interactive-review-server v1
```

- [ ] **Step 3:** Create a session against an actual PR you have access to (use a real PR number — the user is logged into gh as petmakris):

```bash
RES=$(curl -sX POST "$URL/api/sessions" -H 'Content-Type: application/json' -d "{\"cwd\":\"$PWD\",\"pr\":\"<PR-NUMBER>\"}")
echo "$RES" | python3 -m json.tool
```

If you don't have a real PR to test against, fall back to a tiny synthetic test that mocks the gh subprocess — create a session against a small fixture diff manually.

- [ ] **Step 4:** Exercise the endpoints with curl: GET /files, POST /api/submit, GET /thread, GET /poll, POST /api/finish.

- [ ] **Step 5:** Verify watcher round-trip with a fake event ack.

- [ ] **Step 6:** Commit any fixes:

```bash
git add -A
git commit -m "interactive-review: post-smoke fixes" --allow-empty
```

---

## Self-review

- [x] All spec sections for interactive-review covered.
- [x] Tests at every layer (diff parser, threads, server handlers, watcher integration).
- [x] No reused identifiers from annotate that would collide (port range disjoint, skill name disjoint, server.json path disjoint).
- [x] `source_event_id` dedup ensures watcher re-emit safety.
