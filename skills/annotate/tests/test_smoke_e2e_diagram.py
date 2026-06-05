"""End-to-end smoke: mixed markdown + sequence doc through the live server."""
import http.client
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from skills.annotate import blocks as blocks_model
from skills.annotate.tests.test_server import (
    _create_session, _http_get, _start_server, _write_blocks,
)


class SequenceSmokeTests(unittest.TestCase):
    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="annotate-smoke-"))
        self.fake_home = Path(tempfile.mkdtemp(prefix="annotate-smoke-home-"))
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

    def _post_json(self, path: str, payload: dict):
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", path, body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        body = resp.read().decode("utf-8")
        status = resp.status
        conn.close()
        return status, body

    def test_e2e_mixed_markdown_and_sequence(self):
        response_dir = Path(self.sess["response_dir"])
        spec_v1 = {
            "actors": [{"id": "a", "label": "A"}, {"id": "b", "label": "B"}],
            "steps": [
                {"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "ping"},
            ],
        }
        _write_blocks(response_dir, "resp-smoke", "smoke", [
            {"id": "b-0", "markdown": "# Intro\nProse here.", "version": 1},
            {"id": "b-1", "kind": "sequence", "spec": spec_v1, "version": 1},
        ])

        # 1. GET / returns 200 + diagram.css link.
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        self.assertIn('href="/static/diagram.css"', body)

        # 2. GET /raw: markdown unchanged; sequence has svg + spec + kind.
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data = json.loads(body)
        md_blk = next(b for b in data["blocks"] if b["id"] == "b-0")
        seq_blk = next(b for b in data["blocks"] if b["id"] == "b-1")
        self.assertEqual(md_blk["kind"], "markdown")
        self.assertEqual(md_blk["markdown"], "# Intro\nProse here.")
        self.assertNotIn("svg", md_blk)
        self.assertEqual(seq_blk["kind"], "sequence")
        self.assertTrue(seq_blk["svg"].startswith("<svg"))
        self.assertIn('data-step-id="s1"', seq_blk["svg"])
        self.assertEqual(seq_blk["spec"], spec_v1)

        # 3. POST /api/submit with step_id → 202 + step_id stored on event.
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-1", "step_id": "s1", "type": "comment",
            "text": "what about retries?",
        })
        self.assertEqual(status, 202)
        events_dir = Path(self.sess["events_dir"])
        event_files = list(events_dir.glob("*.json"))
        self.assertEqual(len(event_files), 1)
        evt = json.loads(event_files[0].read_text())
        self.assertEqual(evt["step_id"], "s1")
        self.assertEqual(evt["block_id"], "b-1")

        # 4. Simulate Claude updating the spec to address the comment.
        blocks_path = Path(self.sess["response_dir"]) / "blocks.json"
        doc = blocks_model.load(blocks_path)
        spec_v2 = {
            "actors": spec_v1["actors"],
            "steps": [
                {"id": "s1", "from": "a", "to": "b", "arrow": "request",
                 "label": "ping", "sub": "retries 3× on 5xx"},
            ],
        }
        bumped = blocks_model.update_spec_block(doc, "b-1", spec_v2)
        self.assertTrue(bumped)
        blocks_model.save_atomic(blocks_path, doc)

        # 5. /raw now reflects the updated spec and re-rendered SVG.
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data2 = json.loads(body)
        seq2 = next(b for b in data2["blocks"] if b["id"] == "b-1")
        self.assertEqual(seq2["version"], 2)
        self.assertIn("retries 3× on 5xx", seq2["svg"])


if __name__ == "__main__":
    unittest.main()
