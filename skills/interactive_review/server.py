"""Interactive-review skill — thin handlers module over web_companion.

Per-line PR review runs in the IDE (IntelliJ plugin / VS Code extension).
This server is headless: it snapshots the PR diff, holds per-anchor threads,
streams thread changes over SSE, and enqueues /api/submit comments as events
for Claude. Claude wakes via watcher, reads context, appends a claude-role
message to the thread, acks. There is no browser review UI.
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
from skills.interactive_review import diff as diff_module
from skills.interactive_review import threads as threads_module

SHARED_STATIC_DIR = Path(__file__).resolve().parent.parent / "_shared" / "web_companion" / "static"

PORT_RANGE = range(54620, 54641)
BANNER = "interactive-review-server v1"

IDE_PAGE = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Interactive Review</title>
<style>body{font-family:-apple-system,Segoe UI,sans-serif;background:#0d1117;color:#c9d1d9;display:flex;min-height:100vh;margin:0;align-items:center;justify-content:center}main{max-width:32rem;text-align:center;padding:2rem;line-height:1.5}h1{font-size:1.15rem;font-weight:600}b{color:#fff}</style></head>
<body><main><h1>🔍 Interactive review runs in your IDE</h1>
<p>This review has no browser page. Open the project in <b>IntelliJ&nbsp;IDEA</b> or <b>VS&nbsp;Code</b> — the plugin shows per-line annotations on the diff.</p></main></body></html>
"""

CLOSED_PAGE = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Closed</title>
<style>body{font-family:-apple-system,Segoe UI,sans-serif;background:#0d1117;color:#8b949e;display:flex;min-height:100vh;margin:0;align-items:center;justify-content:center}</style></head>
<body><main><p>This review session is closed.</p></main></body></html>
"""


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
            _send_html(h, 200, CLOSED_PAGE)
            return
        _send_html(h, 200, IDE_PAGE)

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
            user_msgs = [m for m in t.get("messages", []) if m.get("role") == "user"]
            result[anchor] = {
                "latest_synthesis": last.get("text", ""),
                "version": t.get("version", 0),
                "updated_at": last.get("ts", 0),
                "anchor_text": t.get("anchor_text", ""),
                "title": t.get("title", ""),
                "question": user_msgs[0].get("text", "") if user_msgs else "",
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
        anchor_text = payload.get("anchor_text")
        if isinstance(anchor_text, str):
            threads_module.set_anchor_text_if_absent(threads_dir, anchor, anchor_text)
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
        # Liveness: a session is ENDED if explicitly cancelled/finished, or if
        # its watcher has been silent past REAP_AFTER. hb==0 means no beat yet
        # (age unknown) -> not dead. The client latches ENDED and freezes the
        # panel read-only; PAUSED (watcher_seen_at age 15-180s) is its own
        # client-side styling derived from watcher_seen_at below.
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
            "watcher_seen_at": hb,
            "finished": _is_terminal(state_dir),
            "ended": ended_reason is not None,
            "ended_reason": ended_reason,
        })

    def create_session_extra(self, payload: dict, dirs: dict) -> dict | None:
        state_dir = Path(dirs["state_dir"])
        (state_dir / "threads").mkdir(exist_ok=True)
        pr_ref = payload.get("pr")
        if not isinstance(pr_ref, str) or not pr_ref:
            raise ValueError("payload missing 'pr' (PR number, URL, or branch)")
        try:
            diff_text, meta = diff_module.fetch_pr_diff(pr_ref, dirs.get("_cwd"))
        except Exception as e:
            raise ValueError(f"gh pr fetch failed: {e}") from e
        (state_dir / "diff.patch").write_text(diff_text)
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
        static_dirs=[SHARED_STATIC_DIR],
    )


if __name__ == "__main__":
    sys.exit(main())
