"""Handlers protocol that skills implement.

The shared server dispatches requests it can't handle itself (root, static,
upload — those are owned by the shared core) to the skill's handlers
instance. The protocol is intentionally small and skill-agnostic.
"""
from __future__ import annotations

from typing import Protocol, runtime_checkable
from http.server import BaseHTTPRequestHandler


@runtime_checkable
class HandlersProtocol(Protocol):
    def serve_root(self, handler: BaseHTTPRequestHandler, dirs: dict) -> None:
        """GET /s/<sid>/  — render the session's main page."""
        ...

    def serve_data(self, handler: BaseHTTPRequestHandler, dirs: dict, query: str) -> None:
        """GET /s/<sid>/<path>  — fetch one piece of the page (block, thread, raw).

        `query` is the URL path after /s/<sid>/, e.g. "raw?block=b-3".
        Skill decides what's supported.
        """
        ...

    def handle_submit(self, handler: BaseHTTPRequestHandler, dirs: dict, payload: dict) -> None:
        """POST /s/<sid>/api/submit  — accept a per-block/per-line submission.

        Skill writes one event into dirs["state_dir"]/events/ and responds
        202 {"event_id": "..."}.
        """
        ...

    def serve_poll(self, handler: BaseHTTPRequestHandler, dirs: dict) -> None:
        """GET /s/<sid>/poll  — return version vector + watcher heartbeat."""
        ...

    def create_session_extra(self, payload: dict, dirs: dict) -> dict | None:
        """Per-skill session-init hook.

        Called by the shared /api/sessions handler after dirs are created.
        Return a dict of extra fields to merge into the session response,
        or None for no extras. May raise to fail session creation.
        """
        ...

    def comment_count(self, dirs: dict) -> int:
        """Return the number of comments/threads for this session.

        Used by the scope=all session browser to render a comment count per
        row. Each skill knows where its own comment artifacts live (events,
        threads, ...); the shared server has no business globbing a
        skill-specific directory. Default: 0.
        """
        return 0
