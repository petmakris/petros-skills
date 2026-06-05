"""Stdlib-only shared HTTP companion library used by the annotate and
interactive-review skills.

Skills implement HandlersProtocol and call web_companion.server.run(...).
"""

from skills._shared.web_companion.handlers import HandlersProtocol
from skills._shared.web_companion.sessions import Registry
from skills._shared.web_companion import events, uploads, static_serve, server, templates

__all__ = [
    "HandlersProtocol",
    "Registry",
    "events",
    "uploads",
    "static_serve",
    "server",
    "templates",
]
