"""Session registry shared by all web_companion skills.

Migrated from skills/annotate/server.py:67-163. Each instance is
parameterized by a state_root (where sessions.json lives) so multiple
skills can coexist with separate registries.
"""
from __future__ import annotations

import json
import re
import secrets
import threading
import time
from pathlib import Path

from skills._shared.web_companion.atomic import write_text_atomic

SID_RE = re.compile(r"^[a-zA-Z0-9_-]+$")


class Registry:
    def __init__(self, state_root: Path):
        self._state_root = Path(state_root)
        self._sessions: dict[str, dict[str, Path]] = {}
        self._lock = threading.Lock()
        self._waiters: dict[str, threading.Event] = {}

    @property
    def sessions_file(self) -> Path:
        return self._state_root / "sessions.json"

    def make_sid(self) -> str:
        return f"{time.strftime('%y%m%d-%H%M%S')}-{secrets.token_hex(8)}"

    def register(self, sid: str, dirs: dict[str, Path]) -> None:
        with self._lock:
            self._sessions[sid] = dirs

    def lookup(self, sid: str) -> dict[str, Path] | None:
        with self._lock:
            return self._sessions.get(sid)

    def items(self) -> list[tuple[str, dict[str, Path]]]:
        with self._lock:
            return list(self._sessions.items())

    def find_by_cwd(self, cwd: str) -> list[tuple[str, dict]]:
        """Return [(sid, dirs)] for all sessions whose registered cwd matches."""
        return [
            (sid, dirs)
            for sid, dirs in self.items()
            if str(dirs.get("_cwd", "")) == str(cwd)
        ]

    def persist(self) -> None:
        self._state_root.mkdir(parents=True, exist_ok=True)
        with self._lock:
            snapshot = {
                sid: {k: str(v) for k, v in dirs.items()}
                for sid, dirs in self._sessions.items()
            }
        write_text_atomic(self.sessions_file, json.dumps(snapshot, indent=2))

    def rehydrate(self) -> None:
        path = self.sessions_file
        if not path.exists():
            return
        try:
            snapshot = json.loads(path.read_text())
        except (json.JSONDecodeError, OSError):
            return
        if not isinstance(snapshot, dict):
            return
        restored: dict[str, dict[str, Path]] = {}
        for sid, dirs in snapshot.items():
            if not SID_RE.match(sid) or not isinstance(dirs, dict):
                continue
            try:
                paths = {k: Path(v) for k, v in dirs.items()}
            except (TypeError, ValueError):
                continue
            if not all(p.is_dir() for p in paths.values()):
                continue
            restored[sid] = paths
        with self._lock:
            self._sessions.update(restored)

    def waiter(self, sid: str) -> threading.Event:
        """Return (creating if needed) the threading.Event for SSE consumers of this session."""
        with self._lock:
            if sid not in self._waiters:
                self._waiters[sid] = threading.Event()
            return self._waiters[sid]

    def note_change(self, sid: str) -> None:
        """Signal SSE consumers that a thread for this session was mutated.

        Sets the event briefly so all current waiters wake, then clears so
        new waiters re-block.
        """
        ev = self.waiter(sid)
        ev.set()
        ev.clear()
