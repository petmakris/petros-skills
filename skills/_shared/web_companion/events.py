"""Event queue helpers for web_companion.

Skills enqueue events by calling append(); the watcher script reads them in
monotonic order and emits one stdout banner per event.  Atomicity is via
tmp → rename.  Ordering is by monotonic_ns event_id (the filename).
"""
from __future__ import annotations

import json
import time
from pathlib import Path


def append(events_dir: Path, payload: dict) -> str:
    """Atomically enqueue an event.  Returns the event_id (monotonic ns)."""
    events_dir = Path(events_dir)
    events_dir.mkdir(parents=True, exist_ok=True)
    event_id = str(time.monotonic_ns())
    target = events_dir / f"{event_id}.json"
    tmp = target.with_suffix(".tmp")
    tmp.write_text(json.dumps(payload))
    tmp.replace(target)
    return event_id


def heartbeat(state_dir: Path) -> None:
    """Write the watcher heartbeat (used by /poll's watcher_seen_at)."""
    state_dir = Path(state_dir)
    state_dir.mkdir(parents=True, exist_ok=True)
    (state_dir / "watcher_heartbeat").write_text(str(int(time.time())))
