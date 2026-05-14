#!/usr/bin/env python3
"""Stop hook: wait for an annotate-skill submission and inject it back to Claude.

When Claude pushes a long response into the annotate browser view, it ends its
turn and the user is supposed to click Submit. Without this hook, Claude has no
way to learn that Submit was clicked — the user has to type something to wake
Claude up. This hook fixes that by:

  1. Detecting whether the most-recent .claude/annotate/<ts>/ session is mid-flight
     (response pushed in the last few minutes, no annotations.json yet).
  2. Polling annotations.json until it appears (or a server-stopped marker shows
     up, or the wall-clock cap is hit).
  3. Emitting the annotations payload as a Stop-hook `additionalContext` blob so
     Claude's next turn picks it up as a system reminder.

If no annotate session is in flight, the hook exits in milliseconds — it's safe
to leave always-on alongside dump-md.sh.
"""

import json
import os
import sys
import time
from pathlib import Path

POLL_INTERVAL_S = 0.25
MAX_WAIT_S = 30 * 60
# Pushes older than this are treated as leftovers from a previous turn the user
# already handled (or abandoned). Stops the hook from blocking on stale state.
FRESH_PUSH_WINDOW_S = 5 * 60


def _read_event() -> dict:
    try:
        return json.loads(sys.stdin.read() or "{}")
    except (json.JSONDecodeError, ValueError):
        return {}


def _resolve_cwd(event: dict) -> Path:
    for candidate in (event.get("cwd"), os.environ.get("CLAUDE_PROJECT_DIR")):
        if candidate:
            return Path(candidate)
    return Path.cwd()


def _latest_session(cwd: Path) -> Path | None:
    base = cwd / ".claude" / "annotate"
    if not base.is_dir():
        return None
    sessions = [p for p in base.iterdir() if p.is_dir()]
    if not sessions:
        return None
    return max(sessions, key=lambda p: p.stat().st_mtime)


def _read_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text())
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return None


def _emit_additional_context(text: str) -> None:
    # Stop hooks inject feedback to Claude via `{decision: "block", reason: "..."}`.
    # `hookSpecificOutput.additionalContext` is only valid for UserPromptSubmit /
    # PostToolUse / PostToolBatch — using it on a Stop hook fails schema validation.
    payload = {"decision": "block", "reason": text}
    sys.stdout.write(json.dumps(payload))
    sys.stdout.flush()


_TYPE_DISPLAY = {
    "approve":  ("✓", "APPROVE"),
    "reject":   ("✗", "REJECT"),
    "question": ("?", "QUESTION"),
    "rewrite":  ("✎", "REWRITE"),
    "comment":  ("💬", "COMMENT"),
}


def _truncate(text: str, max_len: int = 140) -> str:
    text = (text or "").strip().replace("\n", " ")
    if len(text) <= max_len:
        return text
    return text[: max_len - 1].rstrip() + "…"


def _format_one(ann: dict) -> str:
    ann_type = ann.get("type") or "comment"
    icon, label = _TYPE_DISPLAY.get(ann_type, ("•", ann_type.upper()))
    selected = _truncate(ann.get("selected_text", ""))
    comment = (ann.get("comment") or "").strip()
    replacement = ann.get("replacement")
    block_id = ann.get("block_id", "")

    target = f'"{selected}"' if selected else "(whole block)"
    header = f"{icon} {label}  {target}"
    if block_id:
        header += f"  ·{block_id}"

    lines = [header]
    if replacement:
        lines.append(f"          → {_truncate(replacement)}")
    if comment:
        lines.append(f'          "{comment}"')
    return "\n".join(lines)


def _format_context(payload: dict) -> str:
    response_id = payload.get("response_id", "?")
    annotations = payload.get("annotations") or []
    count = len(annotations)

    if count == 0:
        return (
            f"The user clicked Submit on the annotate page for `{response_id}` "
            "with no annotations. Treat as full approval — acknowledge briefly "
            "and continue."
        )

    parts = [
        f"📝 {count} annotation{'s' if count != 1 else ''} on `{response_id}`",
        "Address each one in your next response.",
        "",
    ]
    for ann in annotations:
        parts.append(_format_one(ann))
        parts.append("")
    return "\n".join(parts).rstrip()


def run(event: dict, *, now: float | None = None, sleep=time.sleep) -> int:
    """Hook body, factored out for testing. Returns the process exit code."""
    if event.get("hook_event_name") != "Stop":
        return 0

    cwd = _resolve_cwd(event)
    session = _latest_session(cwd)
    if session is None:
        return 0

    meta_path = session / "response" / "meta.json"
    annotations_path = session / "annotations" / "annotations.json"
    server_stopped_path = session / "state" / "server-stopped"
    cancelled_path = session / "state" / "cancelled"

    meta = _read_json(meta_path)
    if not meta:
        return 0
    expected_id = meta.get("response_id")
    if not expected_id:
        return 0

    submitted = _read_json(annotations_path)
    if submitted and submitted.get("response_id") == expected_id:
        return 0  # already handled

    started = now if now is not None else time.time()
    try:
        meta_age = started - meta_path.stat().st_mtime
    except OSError:
        return 0
    if meta_age > FRESH_PUSH_WINDOW_S:
        return 0  # stale leftover, not this turn's push

    deadline = started + MAX_WAIT_S
    while True:
        if server_stopped_path.exists() or cancelled_path.exists():
            return 0
        submitted = _read_json(annotations_path)
        if submitted and submitted.get("response_id") == expected_id:
            _emit_additional_context(_format_context(submitted))
            return 0
        if time.time() >= deadline:
            return 0
        sleep(POLL_INTERVAL_S)


def main() -> int:
    return run(_read_event())


if __name__ == "__main__":
    sys.exit(main())
