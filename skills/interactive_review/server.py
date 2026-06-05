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
    def __init__(self):
        self._registry = None

    def set_registry(self, registry) -> None:
        self._registry = registry

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

    def threads_bulk(self, dirs: dict) -> dict:
        """Return {anchor: {latest_synthesis, version, updated_at}} for all threads.

        latest_synthesis is the text of the most-recent message with role='claude'.
        Threads without a claude message yet are omitted.
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

    def serve_data(self, h: BaseHTTPRequestHandler, dirs: dict, query: str) -> None:
        state_dir = Path(dirs["state_dir"])
        threads_dir = state_dir / "threads"
        if query == "stream":
            self._serve_stream(h, dirs)
            return
        if query == "threads.json":
            _send_json(h, 200, self.threads_bulk(dirs))
            return
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

    def _serve_stream(self, h: BaseHTTPRequestHandler, dirs: dict) -> None:
        sid = dirs.get("_sid")
        h.send_response(200)
        h.send_header("Content-Type", "text/event-stream")
        h.send_header("Cache-Control", "no-cache")
        h.send_header("Connection", "keep-alive")
        h.send_header("X-Accel-Buffering", "no")
        h.end_headers()
        try:
            h.wfile.write(b"event: connected\ndata: {}\n\n")
            h.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            return
        if not self._registry or not sid:
            # Registry not injected — should not happen in production.
            return
        waiter = self._registry.waiter(sid)
        last_threads = self.threads_bulk(dirs)
        for anchor, info in last_threads.items():
            payload = json.dumps({"anchor": anchor, **info})
            try:
                h.wfile.write(f"event: thread-changed\ndata: {payload}\n\n".encode())
                h.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
        while True:
            woke = waiter.wait(timeout=30)
            # Always re-check threads — self-correcting against a missed wake
            new_threads = self.threads_bulk(dirs)
            # Deletions: anchors present last time but missing now.
            for anchor in list(last_threads):
                if anchor not in new_threads:
                    payload = json.dumps({"anchor": anchor})
                    try:
                        h.wfile.write(f"event: thread-deleted\ndata: {payload}\n\n".encode())
                        h.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError):
                        return
            for anchor, info in new_threads.items():
                old = last_threads.get(anchor)
                if old is None or old.get("version") != info.get("version"):
                    payload = json.dumps({"anchor": anchor, **info})
                    try:
                        h.wfile.write(f"event: thread-changed\ndata: {payload}\n\n".encode())
                        h.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError):
                        return
            last_threads = new_threads
            # Heartbeat only if no wakeup (keep proxies alive)
            if not woke:
                try:
                    h.wfile.write(b"event: heartbeat\ndata: {}\n\n")
                    h.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    return

    def handle_thread_delete(self, h: BaseHTTPRequestHandler, dirs: dict, payload: dict) -> None:
        state_dir = Path(dirs["state_dir"])
        if _is_terminal(state_dir):
            _send_text(h, 409, "session closed")
            return
        anchor = payload.get("anchor")
        if not isinstance(anchor, str) or not threads_module.valid_anchor(anchor):
            _send_text(h, 400, "bad anchor")
            return
        threads_module.delete(state_dir / "threads", anchor)
        _send_json(h, 200, {"anchor": anchor, "status": "deleted"})

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
