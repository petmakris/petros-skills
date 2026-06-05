from io import BytesIO
from pathlib import Path
from unittest.mock import MagicMock

from skills._shared.web_companion.uploads import handle, UPLOAD_MAX_BYTES


def make_handler(headers, body=b""):
    h = MagicMock()
    h.headers = headers
    h.rfile = BytesIO(body)
    h.wfile = BytesIO()
    return h


def test_unsupported_media_type(tmp_path):
    h = make_handler({"Content-Type": "text/plain", "Content-Length": "1"}, b"x")
    handle(h, {"state_dir": tmp_path})
    h.send_response.assert_called_with(415)


def test_missing_content_length(tmp_path):
    h = make_handler({"Content-Type": "image/png"})
    handle(h, {"state_dir": tmp_path})
    h.send_response.assert_called_with(411)


def test_payload_too_large(tmp_path):
    big = str(UPLOAD_MAX_BYTES + 1)
    h = make_handler({"Content-Type": "image/png", "Content-Length": big})
    handle(h, {"state_dir": tmp_path})
    h.send_response.assert_called_with(413)


def test_invalid_content_length(tmp_path):
    h = make_handler({"Content-Type": "image/png", "Content-Length": "abc"})
    handle(h, {"state_dir": tmp_path})
    h.send_response.assert_called_with(400)


def test_happy_path(tmp_path):
    png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 16
    h = make_handler({"Content-Type": "image/png", "Content-Length": str(len(png))}, png)
    handle(h, {"state_dir": tmp_path})
    h.send_response.assert_called_with(200)
    images = list((tmp_path / "images").iterdir())
    assert len(images) == 1
    assert images[0].suffix == ".png"
