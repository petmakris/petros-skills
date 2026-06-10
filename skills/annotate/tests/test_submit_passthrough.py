"""Submit payload passthrough.

The client sends `block_snippet` (what the block looked like at comment time)
and `prefix`/`suffix` (disambiguation context when the selected text occurs
more than once in the block). The submit handler used to drop all three on
the floor, so Claude never saw them. They must survive into the event file.
"""
import json
import shutil
import tempfile
import unittest
from http.client import HTTPConnection
from pathlib import Path

from skills.annotate.tests.test_server import (  # noqa: E402
    _start_server, _create_session, _write_blocks,
)


class SubmitPassthroughTests(unittest.TestCase):
    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="sp-proj-"))
        self.home = Path(tempfile.mkdtemp(prefix="sp-home-"))
        self.proc, self.info = _start_server(self.home)
        self.sess = _create_session(self.info["port"], self.project)
        _write_blocks(Path(self.sess["response_dir"]), "resp-sp", "T",
                      [{"id": "section-1", "title": "A", "markdown": "alpha beta alpha"}])

    def tearDown(self):
        try:
            self.proc.terminate(); self.proc.wait(timeout=2)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.project, ignore_errors=True)
        shutil.rmtree(self.home, ignore_errors=True)

    def _submit(self, payload: dict) -> dict:
        conn = HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", f"/s/{self.sess['sid']}/api/submit",
                     body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        body = resp.read().decode()
        self.assertEqual(resp.status, 202, body)
        return json.loads(body)

    def _event(self, event_id: str) -> dict:
        path = Path(self.sess["events_dir"]) / f"{event_id}.json"
        return json.loads(path.read_text())

    def test_snippet_and_context_fields_survive_into_event(self):
        res = self._submit({
            "block_id": "section-1", "step_id": None, "type": "comment",
            "text": "why?", "selected_text": "alpha", "images": [],
            "block_snippet": "alpha beta alpha",
            "prefix": "", "suffix": " beta",
        })
        evt = self._event(res["event_id"])
        self.assertEqual(evt["block_snippet"], "alpha beta alpha")
        self.assertEqual(evt["prefix"], "")
        self.assertEqual(evt["suffix"], " beta")

    def test_fields_absent_when_not_sent(self):
        res = self._submit({
            "block_id": None, "step_id": None, "type": "comment",
            "text": "general note", "selected_text": "", "images": [],
        })
        evt = self._event(res["event_id"])
        self.assertNotIn("block_snippet", evt)
        self.assertNotIn("prefix", evt)
        self.assertNotIn("suffix", evt)

    def test_non_string_context_fields_are_ignored(self):
        res = self._submit({
            "block_id": "section-1", "step_id": None, "type": "comment",
            "text": "x", "selected_text": "", "images": [],
            "block_snippet": ["not", "a", "string"], "prefix": 7,
        })
        evt = self._event(res["event_id"])
        self.assertNotIn("block_snippet", evt)
        self.assertNotIn("prefix", evt)


if __name__ == "__main__":
    unittest.main()
