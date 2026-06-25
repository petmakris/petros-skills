"""Per-anchor append-only thread persistence for interactive-review.

Each thread lives in <state_dir>/threads/<encoded_anchor>.json with shape:
    {
      "anchor": "<path>:<side>:<line>",
      "version": <int>,
      "messages": [{role, ts, text, ...}, ...]
    }

Threads are append-only; dedup is by source_event_id stored in each message.

Concurrency: thread files are written via web_companion.atomic.write_text_atomic
(unique temp name + os.replace), and every read-modify-write is serialized by an
exclusive flock on a per-anchor sidecar lock file so concurrent writers (the
server worker handling /api/submit and the in-session agent appending its reply)
cannot lose each other's messages.
"""
from __future__ import annotations

import contextlib
import fcntl
import hashlib
import json
import re
import urllib.parse
from pathlib import Path

from skills._shared.web_companion.atomic import write_text_atomic


_ANCHOR_RE = re.compile(r"^[^/:]+(?:/[^/:]+)*:[LR]:\d+(?:-\d+)?$")
GENERAL_ANCHOR = "__general__"
_MAX_NAME = 200


def valid_anchor(anchor: str) -> bool:
    if anchor == GENERAL_ANCHOR:
        return True
    if not _ANCHOR_RE.match(anchor):
        return False
    path = anchor.rsplit(":", 2)[0]
    return not any(part in ("", ".", "..") for part in path.split("/"))


def _encode_anchor(anchor: str) -> str:
    enc = urllib.parse.quote(anchor, safe="")
    if len(enc) > _MAX_NAME:
        enc = "h_" + hashlib.sha256(anchor.encode("utf-8")).hexdigest()
    return enc


def _path_for(threads_dir: Path, anchor: str) -> Path:
    return Path(threads_dir) / f"{_encode_anchor(anchor)}.json"


@contextlib.contextmanager
def _anchor_lock(threads_dir: Path, anchor: str):
    # Lock files live in a sibling `.locks/` dir, never inside threads_dir, so
    # consumers that iterate threads_dir see only thread `.json` files.
    locks_dir = Path(threads_dir).parent / ".locks"
    locks_dir.mkdir(parents=True, exist_ok=True)
    lock_path = locks_dir / f"{_encode_anchor(anchor)}.lock"
    fd = lock_path.open("w")
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        fd.close()


def load(threads_dir: Path, anchor: str) -> dict:
    p = _path_for(threads_dir, anchor)
    if not p.exists():
        return {"anchor": anchor, "version": 0, "messages": []}
    try:
        return json.loads(p.read_text())
    except (json.JSONDecodeError, OSError):
        return {"anchor": anchor, "version": 0, "messages": []}


def save_atomic(threads_dir: Path, thread: dict) -> None:
    write_text_atomic(_path_for(threads_dir, thread["anchor"]), json.dumps(thread, indent=2))


def append_message(threads_dir: Path, anchor: str, msg: dict, title: str | None = None) -> bool:
    """Append a message; dedup by source_event_id.  Returns True if appended.

    If `title` is a non-empty string, set the thread's top-level `title`
    (last-write-wins) — the agent's short headline shown in the IDE panel.
    """
    with _anchor_lock(threads_dir, anchor):
        t = load(threads_dir, anchor)
        seid = msg.get("source_event_id")
        if seid is not None:
            for existing in t["messages"]:
                if existing.get("source_event_id") == seid:
                    return False
        t["messages"].append(msg)
        t["version"] = int(t.get("version", 0)) + 1
        if title:
            t["title"] = title
        save_atomic(threads_dir, t)
        return True


def set_anchor_text_if_absent(threads_dir: Path, anchor: str, text: str) -> None:
    """Record the anchored line's text once, on first creation (first-write-wins).

    No-op if the thread already has a non-empty anchor_text, or if `text` is
    empty. Used to re-locate a drifted annotation later, client-side.
    """
    if not text:
        return
    with _anchor_lock(threads_dir, anchor):
        t = load(threads_dir, anchor)
        if t.get("anchor_text"):
            return
        t["anchor_text"] = text
        save_atomic(threads_dir, t)


def delete(threads_dir: Path, anchor: str) -> bool:
    """Remove the thread file for `anchor`.  Returns True if a file was removed."""
    with _anchor_lock(threads_dir, anchor):
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
