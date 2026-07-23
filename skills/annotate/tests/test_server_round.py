"""`type: "round"` submit — one event carrying many sub-unit reactions.

A round is the batched form of granular feedback: the client accumulates
agree/dismiss/comment marks on sub-units locally and submits them all at
once. The server validates shape + block existence and queues ONE event.
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


def _reaction(kind="agree", block_id="section-1", **kw):
    r = {"kind": kind, "block_id": block_id, "selected_text": "alpha one"}
    r.update(kw)
    return r


class SubmitRoundTests(unittest.TestCase):
    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="rd-proj-"))
        self.home = Path(tempfile.mkdtemp(prefix="rd-home-"))
        self.proc, self.info = _start_server(self.home)
        self.sess = _create_session(self.info["port"], self.project)
        _write_blocks(Path(self.sess["response_dir"]), "resp-rd", "T", [
            {"id": "section-1", "title": "A",
             "markdown": "- alpha one\n- alpha two\n- alpha one"},
            {"id": "section-2", "title": "B", "markdown": "beta"},
        ])

    def tearDown(self):
        try:
            self.proc.terminate(); self.proc.wait(timeout=2)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.project, ignore_errors=True)
        shutil.rmtree(self.home, ignore_errors=True)

    def _submit(self, payload: dict):
        conn = HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", f"/s/{self.sess['sid']}/api/submit",
                     body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        return resp.status, resp.read().decode()

    def _event(self, event_id: str) -> dict:
        path = Path(self.sess["events_dir"]) / f"{event_id}.json"
        return json.loads(path.read_text())

    def test_round_queues_single_event_with_reactions(self):
        status, body = self._submit({"type": "round", "reactions": [
            _reaction("agree"),
            _reaction("dismiss", selected_text="alpha two"),
            _reaction("comment", block_id="section-2", selected_text="beta",
                      text="why beta?"),
        ]})
        self.assertEqual(status, 202, body)
        evt = self._event(json.loads(body)["event_id"])
        self.assertEqual(evt["type"], "round")
        self.assertEqual(len(evt["reactions"]), 3)
        self.assertEqual(evt["reactions"][2]["text"], "why beta?")

    def test_round_passes_prefix_suffix_through(self):
        status, body = self._submit({"type": "round", "reactions": [
            _reaction("dismiss", prefix="", suffix=" alpha two"),
        ]})
        self.assertEqual(status, 202, body)
        evt = self._event(json.loads(body)["event_id"])
        self.assertEqual(evt["reactions"][0]["suffix"], " alpha two")

    def test_round_rejects_empty_reactions(self):
        status, _ = self._submit({"type": "round", "reactions": []})
        self.assertEqual(status, 422)

    def test_round_rejects_missing_reactions(self):
        status, _ = self._submit({"type": "round"})
        self.assertEqual(status, 422)

    def test_round_rejects_bad_kind(self):
        status, _ = self._submit(
            {"type": "round", "reactions": [_reaction("reject")]})
        self.assertEqual(status, 422)

    def test_round_rejects_unknown_block(self):
        status, _ = self._submit(
            {"type": "round", "reactions": [_reaction(block_id="section-99")]})
        self.assertEqual(status, 422)

    def test_round_rejects_comment_without_text(self):
        status, _ = self._submit(
            {"type": "round", "reactions": [_reaction("comment")]})
        self.assertEqual(status, 422)

    def test_round_rejects_empty_selected_text(self):
        status, _ = self._submit(
            {"type": "round", "reactions": [_reaction(selected_text="")]})
        self.assertEqual(status, 422)


if __name__ == "__main__":
    unittest.main()
