"""End-to-end smoke: glossary feature through the live server (HTTP-only).

Note: pill rendering in the browser DOM and popover hover behavior require a
real browser and are out-of-scope for this HTTP-only smoke test.  They would
need Playwright or a headless browser to verify.
"""
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from skills.annotate.blocks import BlocksDoc, save_atomic
from skills.annotate.tests.test_server import (
    _create_session, _http_get, _start_server,
)


class GlossarySmokeTests(unittest.TestCase):
    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="annotate-gloss-smoke-"))
        self.fake_home = Path(tempfile.mkdtemp(prefix="annotate-gloss-home-"))
        self.proc, self.info = _start_server(self.fake_home)
        self.sess = _create_session(self.info["port"], self.project)
        self.base = f"/s/{self.sess['sid']}"

    def tearDown(self):
        try:
            self.proc.terminate()
            self.proc.wait(timeout=2)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.project, ignore_errors=True)
        shutil.rmtree(self.fake_home, ignore_errors=True)

    def test_e2e_glossary_round_trip(self):
        """Glossary written via BlocksDoc is served in HTML shell and /raw."""
        response_dir = Path(self.sess["response_dir"])
        glossary = [
            {"term": "Foo", "definition": "a foo thing", "role": "the bar"},
            {"term": "Baz", "definition": "a baz thing", "role": "the qux"},
        ]
        doc = BlocksDoc(
            response_id="resp-gloss",
            title="Glossary test",
            blocks=[{"id": "b-0", "markdown": "Foo is important. Baz too.", "version": 1}],
            glossary=glossary,
        )
        save_atomic(response_dir / "blocks.json", doc)

        # 1. GET / returns 200 and the HTML shell contains the glossary surface elements.
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        self.assertIn('href="/static/popover.css"', body)
        self.assertIn('src="/static/popover.js"', body)

        # 2. GET /raw returns the glossary we wrote, unchanged.
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data["glossary"], glossary)


if __name__ == "__main__":
    unittest.main()
