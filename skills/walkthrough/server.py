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
from skills._shared.web_companion import events as events_module
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
