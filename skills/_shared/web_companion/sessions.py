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
        self._meta: dict[str, dict] = {}          # sid -> {slug,title,project,created_at}
        self._lock = threading.Lock()
        self._waiters: dict[str, threading.Event] = {}

    @property
    def sessions_file(self) -> Path:
        return self._state_root / "sessions.json"

    def make_sid(self) -> str:
        return f"{time.strftime('%y%m%d-%H%M%S')}-{secrets.token_hex(8)}"

    @property
    def sessions_meta_file(self) -> Path:
        return self._state_root / "sessions_meta.json"

    @staticmethod
    def _slugify(text: str) -> str:
        s = re.sub(r"[^a-z0-9]+", "-", (text or "").lower()).strip("-")
        return s[:40].strip("-")

    def make_slug(self, title: str, cwd: str) -> str:
        base = self._slugify(title) or self._slugify(Path(cwd).name) or "session"
        with self._lock:
            taken = {m.get("slug") for m in self._meta.values() if m.get("slug")}
        if base not in taken:
            return base
        n = 2
        while f"{base}-{n}" in taken:
            n += 1
        return f"{base}-{n}"

    def register_meta(self, sid: str, meta: dict) -> None:
        with self._lock:
            self._meta[sid] = dict(meta)

    def register_with_slug(
        self, sid: str, dirs: dict[str, Path], meta_base: dict, cwd: str,
        explicit_slug: str = "",
    ) -> str:
        """Atomically pick a free slug and register both dirs + meta.

        Fixes a check-then-act race: make_slug() snapshots taken slugs under
        the lock then releases it, so two concurrent creates with the same
        title could both compute the same slug before either registered.
        Here the pick-and-insert happens inside ONE `with self._lock` block,
        so no other thread can grab the same slug in between.

        Slug base priority: sanitized explicit_slug, else sanitized
        meta_base["title"], else sanitized basename(cwd), else "session".
        Dedup is against the slugs of currently-live sessions (self._meta),
        appending -2, -3, ... as needed.

        Does NOT call persist() — the caller persists after, outside the
        lock (persist() takes the lock itself; calling it here would
        deadlock/nest).
        """
        base = (
            self._slugify(explicit_slug)
            or self._slugify(meta_base.get("title", ""))
            or self._slugify(Path(cwd).name)
            or "session"
        )
        with self._lock:
            taken = {m.get("slug") for m in self._meta.values() if m.get("slug")}
            slug = base
            if slug in taken:
                n = 2
                while f"{base}-{n}" in taken:
                    n += 1
                slug = f"{base}-{n}"
            self._sessions[sid] = dirs
            self._meta[sid] = {**meta_base, "slug": slug}
        return slug

    def get_meta(self, sid: str) -> dict:
        with self._lock:
            return dict(self._meta.get(sid, {}))

    def resolve(self, key: str) -> str | None:
        with self._lock:
            if key in self._sessions:
                return key
            for sid, m in self._meta.items():
                if m.get("slug") == key and sid in self._sessions:
                    return sid
        return None

    def find_by_slug(self, slug: str) -> str | None:
        return self.resolve(slug) if slug else None

    def list_all(self) -> list[tuple[str, dict]]:
        items = self.items()
        items.sort(key=lambda kv: kv[0], reverse=True)
        return items

    def register(self, sid: str, dirs: dict[str, Path]) -> None:
        with self._lock:
            self._sessions[sid] = dirs

    def lookup(self, sid: str) -> dict[str, Path] | None:
        with self._lock:
            return self._sessions.get(sid)

    def unregister(self, sid: str) -> None:
        """Drop a dead session's registration (and meta) in-memory.

        Does NOT persist — persist() takes the lock itself, so calling it
        here would deadlock/nest; the caller's next persist() call snapshots
        the removal. Used by self-heal (a resolved sid whose state_dir is
        gone) to free its slug for reuse instead of leaving a ghost entry
        that forces the create path to dedup to a bumped slug.
        """
        with self._lock:
            self._sessions.pop(sid, None)
            self._meta.pop(sid, None)

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
        with self._lock:
            meta_snapshot = {sid: dict(m) for sid, m in self._meta.items()}
        write_text_atomic(self.sessions_meta_file, json.dumps(meta_snapshot, indent=2))

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

        meta_path = self.sessions_meta_file
        if meta_path.exists():
            try:
                msnap = json.loads(meta_path.read_text())
            except (json.JSONDecodeError, OSError):
                msnap = {}
            if isinstance(msnap, dict):
                with self._lock:
                    live = set(self._sessions)
                    self._meta.update({
                        sid: m for sid, m in msnap.items()
                        if sid in live and isinstance(m, dict)
                    })

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
