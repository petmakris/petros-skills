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
    """Append a message; dedup by source_event_id.  Returns True if appended."""
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


def delete(threads_dir: Path, anchor: str) -> bool:
    """Remove the thread file for `anchor`.  Returns True if a file was removed."""
    p = _path_for(threads_dir, anchor)
    try:
        p.unlink()
        return True
    except FileNotFoundError:
        return False


def list_versions(threads_dir: Path) -> dict[str, int]:
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
