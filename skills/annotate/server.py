"""Annotate skill — thin handlers module over web_companion.

Implements HandlersProtocol against the incremental flow:
- blocks.json is the canonical document model
- /api/submit queues one event per block-comment
- /poll returns a per-block version vector

The watcher (see web_companion/watcher.sh) consumes events and wakes Claude.
"""
from __future__ import annotations

import json
import sys
from http.server import BaseHTTPRequestHandler
from pathlib import Path

from skills._shared.web_companion import server as wc_server
from skills._shared.web_companion import events as events_module
from skills._shared.web_companion.templates import html_escape, render_page
from skills.annotate import blocks as blocks_model
from skills.annotate import versions as versions_module
from skills.annotate.diagrams.sequence import render
from skills.annotate.diagrams.mermaid import render as render_mermaid

STATIC_DIR = Path(__file__).resolve().parent / "static"
SHARED_STATIC_DIR = Path(__file__).resolve().parent.parent / "_shared" / "web_companion" / "static"

PORT_RANGE = range(54580, 54601)
BANNER = "annotate-server v1"

WAITING_HTML = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Waiting</title>
<link rel="stylesheet" href="/static/core.css">
<link rel="stylesheet" href="/static/style.css"></head>
<body><main class="waiting"><p>Waiting for a response.</p></main></body></html>
"""

CLOSED_HTML = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Closed</title>
<link rel="stylesheet" href="/static/core.css">
<link rel="stylesheet" href="/static/style.css"></head>
<body><main class="waiting"><p>This annotation round is closed.</p></main></body></html>
"""


def _read_meta(response_dir: Path) -> dict:
    path = response_dir / "meta.json"
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}


def _is_terminal(state_dir: Path) -> bool:
    return (state_dir / "finished").exists() or (state_dir / "cancelled").exists()


def _round_pct(value) -> int | None:
    try:
        return round(float(value))
    except (TypeError, ValueError):
        return None


def _statusline_payload_from_raw(raw: dict) -> dict:
    """Distil the fat statusLine JSON into the compact shape the strip needs.

    Mirrors the field choices in ~/.claude/statusline.sh: context %/tokens,
    model family + 1M badge, rate-limit windows, cost/diff. Absent sections are
    omitted rather than zero-filled, so the client can hide segments it lacks.
    """
    if not isinstance(raw, dict) or not raw:
        return {"ok": False}
    out: dict = {"ok": True}

    model = raw.get("model") or {}
    model_id = str(model.get("id") or "")
    label = None
    if "opus" in model_id:
        label = "Opus"
    elif "sonnet" in model_id:
        label = "Sonnet"
    elif "haiku" in model_id:
        label = "Haiku"
    if label:
        out["model"] = {"label": label}
        if "1m" in model_id.lower():
            out["model"]["badge"] = "1M"

    ctx = raw.get("context_window") or {}
    pct = _round_pct(ctx.get("used_percentage"))
    # Tokens-in-context: current schema exposes total_input_tokens; older builds
    # had used_tokens; otherwise sum the current_usage breakdown.
    used = ctx.get("total_input_tokens")
    if not isinstance(used, (int, float)):
        used = ctx.get("used_tokens")
    if not isinstance(used, (int, float)):
        cu = ctx.get("current_usage") or {}
        parts = [cu.get(k) for k in ("input_tokens", "cache_creation_input_tokens",
                                     "cache_read_input_tokens", "output_tokens")]
        nums = [p for p in parts if isinstance(p, (int, float))]
        used = sum(nums) if nums else None
    # Window size: prefer the explicit field; fall back to the 1M flag.
    total = ctx.get("context_window_size")
    if not isinstance(total, (int, float)):
        total = 1_000_000 if "1m" in model_id.lower() else 200_000
    if pct is not None and isinstance(used, (int, float)):
        out["context"] = {"pct": pct, "used": int(used), "total": int(total)}

    limits = raw.get("rate_limits") or {}
    rl = {}
    for key in ("five_hour", "seven_day"):
        window = limits.get(key) or {}
        p = _round_pct(window.get("used_percentage"))
        if p is not None:
            rl[key] = p
    if rl:
        out["rate_limits"] = rl

    # Code churn (lines added/removed). The dollar figure (total_cost_usd) is
    # deliberately omitted: under a subscription it's an equivalent-API-price
    # estimate, not real spend, so it's noise here — matching statusline.sh,
    # which shows the diff but never a $ amount.
    cost = raw.get("cost") or {}
    c = {}
    if isinstance(cost.get("total_lines_added"), int):
        c["added"] = cost["total_lines_added"]
    if isinstance(cost.get("total_lines_removed"), int):
        c["removed"] = cost["total_lines_removed"]
    if c:
        out["diff"] = c

    return out


def _read_statusline(response_dir: Path) -> dict:
    """Resolve the latest statusLine snapshot for this session's Claude.

    The session's meta.json (in response_dir — where the annotate skill writes
    it alongside blocks.json) records the `claude_session_id`; statusline.sh tees
    its raw stdin to ~/.claude/annotate/statusline/<id>.json on every render.
    Join the two; degrade to {"ok": False} on any miss or parse error.
    """
    meta = _read_meta(response_dir)
    claude_sid = meta.get("claude_session_id")
    if not claude_sid:
        return {"ok": False}
    path = Path.home() / ".claude" / "annotate" / "statusline" / f"{claude_sid}.json"
    try:
        raw = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return {"ok": False}
    return _statusline_payload_from_raw(raw)


class Handlers:
    def serve_root(self, h: BaseHTTPRequestHandler, dirs: dict) -> None:
        if _is_terminal(Path(dirs["state_dir"])):
            _send_html(h, 200, CLOSED_HTML)
            return
        blocks_path = Path(dirs["response_dir"]) / "blocks.json"
        if not blocks_path.exists():
            _send_html(h, 200, WAITING_HTML)
            return
        doc = blocks_model.load(blocks_path)
        body = (
            f'<header class="page-header"><div class="header-title">'
            f'<span class="header-emoji">📝</span>'
            f'<span class="header-text">{html_escape(doc.title)}</span>'
            f'<span class="header-respid">{html_escape(doc.response_id)}</span>'
            f'</div><div class="header-actions">'
            f'<div class="header-search">'
            f'<svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
            f' stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
            f'<circle cx="11" cy="11" r="8"></circle>'
            f'<line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>'
            f'<input id="block-search" class="search-input" type="text"'
            f' placeholder="Search blocks…" autocomplete="off" spellcheck="false"'
            f' aria-label="Search blocks">'
            f'<span class="search-kbd">/</span>'
            f'<button id="block-search-clear" type="button" class="search-clear"'
            f' aria-label="Clear search" tabindex="-1">&times;</button>'
            f'</div>'
            f'<button id="done-btn" type="button" class="done-btn">Done</button>'
            f'</div></header>'
            f'<div id="statstrip" class="statstrip" hidden></div>'
            f'<section class="general-composer">'
            f'  <textarea id="general-input" class="general-input" rows="2"'
            f'    placeholder="Comment on the whole response (not a specific block)…"></textarea>'
            f'  <div class="general-composer-bar">'
            f'    <span id="general-status" class="general-status" aria-live="polite"></span>'
            f'    <button id="general-send" type="button" class="general-send-btn" disabled>Send</button>'
            f'  </div>'
            f'</section>'
            f'<main class="prose"></main>'
        )
        head = ('<link rel="stylesheet" href="/static/style.css">'
                '<link rel="stylesheet" href="/static/diagram.css">'
                '<link rel="stylesheet" href="/static/popover.css">'
                '<script src="/static/popover.js" defer></script>'
                '<script src="/static/script.js" defer></script>'
                '<script src="/static/fuse.min.js" defer></script>'
                '<script src="/static/search.js" defer></script>'
                '<script src="/static/voice.js" defer></script>')
        page = render_page(
            title=doc.title or "Response",
            head_assets=head,
            body_html=body,
            response_id=doc.response_id,
        )
        _send_html(h, 200, page)

    def serve_data(self, h: BaseHTTPRequestHandler, dirs: dict, query: str) -> None:
        # /statusline — compact live snapshot of the pushing Claude's statusLine.
        if query == "statusline":
            _send_json(h, 200, _read_statusline(Path(dirs["response_dir"])))
            return
        # /raw, /raw?block=section-N
        if query.startswith("raw"):
            qs = ""
            if "?" in query:
                qs = query.split("?", 1)[1]
            response_dir = Path(dirs["response_dir"])
            blocks_path = response_dir / "blocks.json"
            versions_path = response_dir / "versions.json"
            doc = blocks_model.load(blocks_path)
            versions = versions_module.derive_versions(versions_path, doc.blocks)
            if "block=" in qs:
                bid = qs.split("block=", 1)[1].split("&", 1)[0]
                try:
                    blk = blocks_model.find_block(doc, bid)
                except KeyError:
                    _send_text(h, 404, "block not found")
                    return
                _send_json(h, 200, _render_block_for_raw(blk, versions.get(bid, 1)))
                return
            _send_json(h, 200, {
                "response_id": doc.response_id,
                "title": doc.title,
                "blocks": [
                    _render_block_for_raw(b, versions.get(b["id"], 1))
                    for b in doc.blocks
                ],
                "glossary": list(doc.glossary),
            })
            return
        _send_text(h, 404, "not found")

    def handle_submit(self, h: BaseHTTPRequestHandler, dirs: dict, payload: dict) -> None:
        if _is_terminal(Path(dirs["state_dir"])):
            _send_text(h, 409, "session closed")
            return
        block_id = payload.get("block_id")  # None for general comment
        comment_type = payload.get("type", "comment")
        text = payload.get("text", "")
        selected_text = payload.get("selected_text")
        step_id = payload.get("step_id")  # None for whole-diagram or non-diagram comments
        images = payload.get("images", [])
        if comment_type not in ("comment", "reject", "choice", "dismiss"):
            _send_text(h, 400, "bad type")
            return
        if not isinstance(text, str):
            _send_text(h, 400, "bad text")
            return
        if comment_type == "dismiss":
            if block_id is None:
                _send_text(h, 422, "dismiss requires block_id")
                return
            blocks_path = Path(dirs["response_dir"]) / "blocks.json"
            doc = blocks_model.load(blocks_path)
            try:
                blocks_model.find_block(doc, block_id)
            except KeyError:
                _send_text(h, 422, f"unknown block_id {block_id!r}")
                return
        selected_options = payload.get("selected_options")
        if comment_type == "choice":
            if block_id is None:
                _send_text(h, 422, "choice requires block_id")
                return
            blocks_path = Path(dirs["response_dir"]) / "blocks.json"
            doc = blocks_model.load(blocks_path)
            try:
                blk = blocks_model.find_block(doc, block_id)
            except KeyError:
                _send_text(h, 422, f"unknown block_id {block_id!r}")
                return
            if (blk.get("kind") or "markdown") != "choice":
                _send_text(h, 422, "type=choice only valid for kind=choice blocks")
                return
            err = blocks_model.validate_choice_selection(blk.get("spec") or {}, selected_options)
            if err is not None:
                _send_text(h, 422, err)
                return
        if step_id is not None:
            if block_id is None:
                _send_text(h, 422, "step_id requires block_id")
                return
            blocks_path = Path(dirs["response_dir"]) / "blocks.json"
            doc = blocks_model.load(blocks_path)
            try:
                blk = blocks_model.find_block(doc, block_id)
            except KeyError:
                _send_text(h, 422, f"unknown block_id {block_id!r}")
                return
            if (blk.get("kind") or "markdown") != "sequence":
                _send_text(h, 422, "step_id only valid for kind=sequence blocks")
                return
            spec = blk.get("spec") or {}
            valid_step_ids = {s.get("id") for s in (spec.get("steps") or [])}
            if step_id not in valid_step_ids:
                _send_text(h, 422, f"unknown step_id {step_id!r}")
                return
        evt = {
            "block_id": block_id,
            "step_id": step_id,
            "type": comment_type,
            "text": text,
            "selected_text": selected_text,
            "images": images,
        }
        if comment_type == "choice":
            evt["selected_options"] = list(selected_options)
        eid = events_module.append(Path(dirs["events_dir"]), evt)
        _send_json(h, 202, {"event_id": eid, "status": "queued"})

    def serve_poll(self, h: BaseHTTPRequestHandler, dirs: dict) -> None:
        response_dir = Path(dirs["response_dir"])
        blocks_path = response_dir / "blocks.json"
        versions_path = response_dir / "versions.json"
        doc = blocks_model.load(blocks_path)
        versions = versions_module.derive_versions(versions_path, doc.blocks)
        state_dir = Path(dirs["state_dir"])
        hb_path = state_dir / "watcher_heartbeat"
        try:
            hb = int(hb_path.read_text().strip())
        except (FileNotFoundError, ValueError):
            hb = 0
        # Event ids the watcher/Claude have finished processing. The client
        # clears a comment's "updating" overlay when its event_id shows up
        # here — the correct done-signal, independent of which block (if any)
        # Claude chose to rewrite in response.
        consumed_dir = state_dir / "consumed"
        try:
            consumed = [p.stem for p in consumed_dir.glob("*.ack")]
        except OSError:
            consumed = []
        # Live progress labels published by the PostToolUse hook (outside the
        # model loop). Skip any event already acked, and length-cap each value
        # as a backstop so a malformed file can never dump bulk text to the
        # browser — the hook only ever writes an allowlisted short label.
        consumed_set = set(consumed)
        events_dir = Path(dirs["events_dir"])
        try:
            queued_ids = {p.stem for p in events_dir.glob("*.json")}
        except OSError:
            queued_ids = set()
        busy = bool(queued_ids - consumed_set)
        progress: dict[str, str] = {}
        try:
            for p in (state_dir / "progress").glob("*"):
                if p.suffix == ".tmp" or p.stem in consumed_set:
                    continue
                try:
                    progress[p.stem] = p.read_text().strip()[:40]
                except OSError:
                    pass
        except OSError:
            pass
        _send_json(h, 200, {
            "blocks": versions,
            "watcher_seen_at": hb,
            "finished": _is_terminal(state_dir),
            "response_id": doc.response_id,
            "consumed_events": consumed,
            "progress": progress,
            "busy": busy,
        })

    def create_session_extra(self, payload: dict, dirs: dict) -> dict | None:
        return None


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


def _render_block_for_raw(blk: dict, version: int) -> dict:
    """Return a dict shape the client expects for one block.

    `version` is derived externally from the chain sidecar — not read off
    the block dict. The on-disk version field (if any) is ignored.

    - markdown blocks → pass markdown through
    - sequence blocks → include rendered svg + spec
    """
    kind = blk.get("kind") or "markdown"
    base = {"id": blk["id"], "kind": kind, "version": int(version)}
    if blk.get("title"):
        base["title"] = blk["title"]
    if kind == "sequence":
        spec = blk.get("spec") or {}
        try:
            svg = render(spec, block_id=blk["id"])
        except Exception as e:
            # Compact inline error pill instead of a full-width red banner.
            # Catch *any* render failure (ValidationError, or a KeyError from a
            # spec that passed validation but is missing a field the renderer
            # reads) so one malformed block can never crash the whole /raw
            # response and blank the page. The message lands in <title>.
            svg = (
                f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 360 36" '
                f'class="annotate-seq annotate-seq-error" '
                f'data-block-id="{html_escape(blk["id"])}" '
                f'role="img" aria-label="sequence diagram failed to render">'
                f'<rect x="0" y="0" width="360" height="36" rx="6" '
                f'fill="#fde7e2" stroke="#e5b8af"/>'
                f'<text x="14" y="22" font-size="12" font-weight="600" '
                f'fill="#c1432f" font-family="ui-monospace, monospace">'
                f'⚠ diagram render failed</text>'
                f'<title>{html_escape(str(e))}</title>'
                f'</svg>'
            )
        base["spec"] = spec
        base["svg"] = svg
    elif kind == "diagram":
        spec = blk.get("spec") or {}
        try:
            svg = render_mermaid(spec, block_id=blk["id"])
        except Exception as e:
            # Same compact error pill as the sequence branch: one malformed
            # block must never crash /raw and blank the page.
            svg = (
                f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 360 36" '
                f'class="annotate-diagram annotate-diagram-error" '
                f'data-block-id="{html_escape(blk["id"])}" '
                f'role="img" aria-label="diagram failed to render">'
                f'<rect x="0" y="0" width="360" height="36" rx="6" '
                f'fill="#fde7e2" stroke="#e5b8af"/>'
                f'<text x="14" y="22" font-size="12" font-weight="600" '
                f'fill="#c1432f" font-family="ui-monospace, monospace">'
                f'⚠ diagram render failed</text>'
                f'<title>{html_escape(str(e))}</title>'
                f'</svg>'
            )
        base["spec"] = spec
        base["svg"] = svg
    elif kind == "choice":
        base["spec"] = blk.get("spec") or {}
    else:
        base["markdown"] = blk.get("markdown", "")
    return base


def main() -> int:
    return wc_server.run(
        skill_name="annotate",
        port_range=PORT_RANGE,
        handlers=Handlers(),
        static_dirs=[SHARED_STATIC_DIR, STATIC_DIR],
    )


if __name__ == "__main__":
    sys.exit(main())
