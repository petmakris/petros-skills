"""Voice dictation wiring tests.

The dictation behavior runs in the browser (SpeechRecognition), so these tests
assert the wiring: the page loads voice.js, the asset is served, and it carries
the two non-negotiable guards (feature-detect + secure-context) so it degrades
to a silent no-op where the API is unavailable.
"""
import http.client
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from skills.annotate.tests.test_server import (  # noqa: E402
    _start_server, _create_session, _write_blocks, _http_get,
)


class VoiceWiringTests(unittest.TestCase):
    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="voice-proj-"))
        self.home = Path(tempfile.mkdtemp(prefix="voice-home-"))
        self.proc, self.info = _start_server(self.home)
        self.sess = _create_session(self.info["port"], self.project)
        _write_blocks(Path(self.sess["response_dir"]), "resp-v", "T",
                      [{"id": "section-1", "title": "A", "markdown": "hi"}])

    def tearDown(self):
        try:
            self.proc.terminate(); self.proc.wait(timeout=2)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.project, ignore_errors=True)
        shutil.rmtree(self.home, ignore_errors=True)

    def test_page_loads_voice_script(self):
        status, body = _http_get("localhost", self.info["port"],
                                 f"/s/{self.sess['sid']}/")
        self.assertEqual(status, 200)
        self.assertIn("/static/voice.js", body)

    def test_voice_asset_served_with_guards(self):
        status, body = _http_get("localhost", self.info["port"],
                                 "/static/voice.js")
        self.assertEqual(status, 200)
        # Feature-detect + secure-context guards must both be present.
        self.assertIn("webkitSpeechRecognition", body)
        self.assertIn("isSecureContext", body)
        # Unsupported API → silent no-op early return.
        self.assertIn("if (!SR) return", body)
        # Insecure context → discoverable disabled button, not a missing one.
        self.assertIn("localhost", body)


if __name__ == "__main__":
    unittest.main()
