#!/usr/bin/env python3
"""PostToolUse hook: publish a coarse, sanitized progress label for the
annotate web companion — entirely outside the model loop, so it costs zero
Claude tokens.

Contract
--------
- stdin carries the PostToolUse JSON; we read only ``tool_name`` and
  ``session_id`` from it.
- We locate the session's pending annotate rounds via the registry at
  ``~/.claude/annotate/pending-<session_id>.json``.
- An annotate event is *in-flight* when ``events/<id>.json`` exists but
  ``consumed/<id>.ack`` does not — exactly the window Claude is rewriting a
  block. We publish only when there is **exactly one** in-flight event across
  all of this session's rounds (the attribution guard): otherwise tool
  activity can't be safely attributed to a comment, so we stay silent.
- The only thing ever written is an allowlisted label (``"Editing the
  response…"`` etc.) — never a path, argument, or tool output. The allowlist
  is the trust boundary: nothing sensitive can reach the browser by
  construction.
- Always exit 0 and print nothing, so a hook failure never disturbs the
  user's tool flow and nothing re-enters the model context.
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
from pathlib import Path

# tool_name -> coarse, human-friendly label. The ONLY strings that can ever
# reach the progress file (and therefore the browser). No raw tool data.
LABELS = {
    "Read": "Reading files…",
    "Glob": "Reading files…",
    "Grep": "Reading files…",
    "NotebookRead": "Reading files…",
    "Edit": "Editing the response…",
    "Write": "Editing the response…",
    "NotebookEdit": "Editing the response…",
    "Bash": "Running a command…",
}
DEFAULT_LABEL = "Working…"


def _label_for(tool_name: str) -> str:
    return LABELS.get(tool_name, DEFAULT_LABEL)


def _in_flight_ids(events_dir: Path, consumed_dir: Path) -> set[str]:
    """Event ids queued but not yet acked — Claude is actively on these."""
    ids: set[str] = set()
    try:
        for ev in events_dir.glob("*.json"):
            if not (consumed_dir / f"{ev.stem}.ack").exists():
                ids.add(ev.stem)
    except OSError:
        pass
    return ids


def _atomic_write(path: Path, text: str) -> None:
    # Unique temp name per writer (mkstemp), not a fixed `<name>.tmp`: this hook
    # can fire concurrently for the same in-flight event (parallel tool calls),
    # and a shared temp name lets two writers truncate the same file so one
    # `replace` hits FileNotFoundError. Mirrors web_companion.atomic, inlined
    # here because the hook runs standalone without the repo on sys.path.
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=path.name + ".", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def main() -> None:
    raw = sys.stdin.read()
    payload = json.loads(raw) if raw.strip() else {}
    tool_name = payload.get("tool_name", "")
    session_id = payload.get("session_id") or os.environ.get(
        "CLAUDE_CODE_SESSION_ID", ""
    )
    if not session_id:
        return

    registry = (
        Path.home() / ".claude" / "annotate" / f"pending-{session_id}.json"
    )
    try:
        rounds = json.loads(registry.read_text())
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return
    if not isinstance(rounds, list):
        return

    # Gather in-flight events across every round, pruning stale progress files
    # (an event that has since been acked) as we go.
    in_flight: list[tuple[Path, str]] = []  # (state_dir, event_id)
    for entry in rounds:
        try:
            state_dir = Path(entry["state_dir"])
            events_dir = Path(entry["events_dir"])
            consumed_dir = Path(entry["consumed_dir"])
        except (KeyError, TypeError):
            continue
        live = _in_flight_ids(events_dir, consumed_dir)
        progress_dir = state_dir / "progress"
        try:
            for pf in progress_dir.glob("*"):
                if pf.suffix == ".tmp" or pf.stem not in live:
                    pf.unlink(missing_ok=True)
        except OSError:
            pass
        for eid in live:
            in_flight.append((state_dir, eid))

    # Attribution guard: publish only when the work is unambiguous.
    if len(in_flight) != 1:
        return
    state_dir, event_id = in_flight[0]
    _atomic_write(state_dir / "progress" / event_id, _label_for(tool_name))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Never let a progress hook disrupt the user's tool flow.
        pass
    sys.exit(0)
