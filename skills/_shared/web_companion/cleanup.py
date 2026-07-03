"""Startup garbage-collection for web_companion state.

Session state accumulated without bound: every push created a per-session
directory under ``<cwd>/.claude/<skill>/<sid>/`` plus a
``pending-<claude_session>.json`` registry under ``~/.claude/<skill>/``, and
nothing ever removed either — so both grew by one entry per session forever.

The shared HTTP server is the single owner of this state and re-launches after
each idle shutdown (see ``server.run``'s idle watchdog), which makes server
startup the natural, once-per-lifecycle GC point. ``sweep_state`` runs there,
before ``Registry.rehydrate``, and deletes anything dormant past a retention
window.

The clock is injected (``now``) so the sweep is deterministic under test, and
every filesystem step is best-effort: an error on one entry is counted and
skipped, never raised, so a GC failure can't stop the server from starting.
"""
from __future__ import annotations

import json
import shutil
from pathlib import Path

from skills._shared.web_companion.atomic import write_text_atomic

# Files whose mtime signals recent activity for a session. The server never
# rewrites the <sid> base dir after creation, so its mtime alone would read
# "old" even for a session being actively viewed right now — the watcher
# heartbeat (rewritten ~every 1s while armed), the event/consumed queues, and
# the terminal markers are what actually track liveness.
_LIVENESS_CHILDREN = (
    "watcher_heartbeat",
    "events",
    "consumed",
    "finished",
    "cancelled",
)


def _mtime(path: Path) -> float | None:
    try:
        return path.stat().st_mtime
    except OSError:
        return None


def _last_activity(base: Path, state_dir: Path) -> float | None:
    """Freshest mtime across a session's liveness signals, or None if none stat.

    Taking the max (not the base dir's own mtime) keeps a long-lived session
    that is still being polled from looking dormant just because its directory
    was created long ago.
    """
    candidates = [_mtime(base), _mtime(state_dir)]
    for name in _LIVENESS_CHILDREN:
        candidates.append(_mtime(state_dir / name))
    live = [m for m in candidates if m is not None]
    return max(live) if live else None


def sweep_state(
    state_root: Path,
    retention_seconds: float,
    now: float,
    extra_globs: tuple[str, ...] = (),
) -> dict[str, int]:
    """Delete dormant session dirs, stale pending files, and ancillary junk.

    - Session dirs are enumerated from ``sessions.json`` (the only on-disk index
      of the per-project ``<sid>`` directories). A session is removed when its
      last activity is older than ``retention_seconds``; entries whose dir is
      already gone are pruned from ``sessions.json`` too.
    - ``pending-*.json`` registries and any ``extra_globs`` matches are removed
      purely by their own mtime.

    Returns a summary of what was removed. Never raises.
    """
    state_root = Path(state_root)
    summary = {"sessions_removed": 0, "pending_removed": 0, "files_removed": 0, "errors": 0}
    if not state_root.is_dir():
        return summary

    _sweep_sessions(state_root, retention_seconds, now, summary)
    _sweep_by_mtime(state_root.glob("pending-*.json"), retention_seconds, now,
                    summary, "pending_removed")
    for pattern in extra_globs:
        _sweep_by_mtime(state_root.glob(pattern), retention_seconds, now,
                        summary, "files_removed")
    return summary


def _sweep_sessions(state_root: Path, retention_seconds: float, now: float,
                    summary: dict[str, int]) -> None:
    sessions_file = state_root / "sessions.json"
    try:
        snapshot = json.loads(sessions_file.read_text())
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return
    if not isinstance(snapshot, dict):
        return

    kept: dict = {}
    changed = False
    for sid, dirs in snapshot.items():
        state_dir_str = dirs.get("state_dir") if isinstance(dirs, dict) else None
        if not state_dir_str:
            kept[sid] = dirs  # unrecognizable entry — leave it untouched
            continue
        state_dir = Path(state_dir_str)
        base = state_dir.parent  # <cwd>/.claude/<skill>/<sid>
        if not base.exists():
            changed = True  # dir already gone: drop the dangling registry row
            continue
        activity = _last_activity(base, state_dir)
        if activity is not None and (now - activity) <= retention_seconds:
            kept[sid] = dirs  # still within the retention window
            continue
        if activity is None:
            kept[sid] = dirs  # couldn't stat anything — don't guess, keep it
            continue
        try:
            shutil.rmtree(base)
            summary["sessions_removed"] += 1
            changed = True
        except OSError:
            summary["errors"] += 1
            kept[sid] = dirs

    if changed:
        try:
            write_text_atomic(sessions_file, json.dumps(kept, indent=2))
        except OSError:
            summary["errors"] += 1


def _sweep_by_mtime(paths, retention_seconds: float, now: float,
                    summary: dict[str, int], counter: str) -> None:
    for p in paths:
        if not p.is_file():
            continue
        mtime = _mtime(p)
        if mtime is None or (now - mtime) <= retention_seconds:
            continue
        try:
            p.unlink()
            summary[counter] += 1
        except OSError:
            summary["errors"] += 1
