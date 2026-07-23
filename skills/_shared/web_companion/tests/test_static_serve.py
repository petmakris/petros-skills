from io import BytesIO
from pathlib import Path
from unittest.mock import MagicMock

from skills._shared.web_companion.static_serve import serve


def make_handler():
    h = MagicMock()
    h.wfile = BytesIO()
    return h


def test_serve_existing_file(tmp_path):
    static = tmp_path / "static"
    static.mkdir()
    (static / "ok.css").write_text("body{}")
    h = make_handler()
    serve(h, "ok.css", [static])
    h.send_response.assert_called_once_with(200)


def test_serve_traversal_rejected(tmp_path):
    static = tmp_path / "static"
    static.mkdir()
    h = make_handler()
    serve(h, "../etc/passwd", [static])
    h.send_response.assert_called_with(404)


def test_serve_embedded_traversal_rejected(tmp_path):
    static = tmp_path / "static"
    (static / "fonts").mkdir(parents=True)
    (tmp_path / "secret.txt").write_text("s3cret")
    h = make_handler()
    serve(h, "fonts/../../secret.txt", [static])
    h.send_response.assert_called_with(404)


def test_serve_symlink_escape_rejected(tmp_path):
    static = tmp_path / "static"
    static.mkdir()
    (tmp_path / "secret.txt").write_text("s3cret")
    (static / "link.txt").symlink_to(tmp_path / "secret.txt")
    h = make_handler()
    serve(h, "link.txt", [static])
    h.send_response.assert_called_with(404)


def test_serve_falls_through_chain(tmp_path):
    a = tmp_path / "a"
    b = tmp_path / "b"
    a.mkdir(); b.mkdir()
    (b / "only-in-b.js").write_text("//")
    h = make_handler()
    serve(h, "only-in-b.js", [a, b])
    h.send_response.assert_called_with(200)


def test_serve_returns_404_for_missing(tmp_path):
    static = tmp_path / "static"
    static.mkdir()
    h = make_handler()
    serve(h, "no-such-file.css", [static])
    h.send_response.assert_called_with(404)
