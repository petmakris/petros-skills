"""Path-traversal-safe static asset serving for web_companion.

Extracted from annotate/server.py:460-481. Extended to accept a list of dirs
resolved in order — first match wins. The first dir is typically the shared
core static; the second is the skill-specific overlay.
"""
from __future__ import annotations

import mimetypes
from pathlib import Path
from http.server import BaseHTTPRequestHandler


def serve(handler: BaseHTTPRequestHandler, name: str, dirs: list[Path]) -> None:
    if "\\" in name or not name or any(
            seg.startswith(".") for seg in name.split("/")):
        _send_text(handler, 404, "not found")
        return
    for static_dir in dirs:
        static_dir = Path(static_dir).resolve()
        # Resolve symlinks and any residual dot segments BEFORE the
        # containment check — a lexical check on the unresolved path lets
        # `fonts/../../x` (and symlinks pointing outside) escape the root.
        path = (static_dir / name).resolve()
        try:
            path.relative_to(static_dir)
        except ValueError:
            continue
        if not path.is_file():
            continue
        ctype = mimetypes.guess_type(str(path))[0] or "application/octet-stream"
        data = path.read_bytes()
        handler.send_response(200)
        handler.send_header("Content-Type", ctype)
        handler.send_header("Content-Length", str(len(data)))
        # Local dev tool — never let the browser hold a stale CSS/JS/HTML
        # asset across an iteration. Bandwidth cost is zero (loopback).
        handler.send_header("Cache-Control", "no-store")
        handler.end_headers()
        handler.wfile.write(data)
        return
    _send_text(handler, 404, "not found")


def _send_text(handler: BaseHTTPRequestHandler, status: int, body: str) -> None:
    data = body.encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "text/plain; charset=utf-8")
    handler.send_header("Content-Length", str(len(data)))
    handler.end_headers()
    handler.wfile.write(data)
