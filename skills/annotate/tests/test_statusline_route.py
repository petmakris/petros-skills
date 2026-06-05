import http.client
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from skills.annotate.server import _statusline_payload_from_raw
from skills.annotate.tests.test_server import _start_server, _create_session


# A representative slice of the JSON Claude Code pipes to the statusLine command,
# using the *current* context_window schema (size + total_input_tokens, no
# used_tokens).
SAMPLE = {
    "session_id": "abc123",
    "model": {"id": "claude-opus-4-8[1m]", "display_name": "Opus 4.8"},
    "context_window": {
        "used_percentage": 18,
        "total_input_tokens": 181387,
        "context_window_size": 1_000_000,
        "current_usage": {"input_tokens": 131, "cache_creation_input_tokens": 1929,
                          "cache_read_input_tokens": 179327, "output_tokens": 3},
    },
    "rate_limits": {
        "five_hour": {"used_percentage": 41.0, "resets_at": 1780000000},
        "seven_day": {"used_percentage": 18.0, "resets_at": 1780500000},
    },
    "cost": {"total_cost_usd": 4.18, "total_lines_added": 1200, "total_lines_removed": 340},
}


class StatuslineParserTests(unittest.TestCase):
    def test_parses_full_payload(self):
        p = _statusline_payload_from_raw(SAMPLE)
        self.assertTrue(p["ok"])
        self.assertEqual(p["context"]["pct"], 18)
        self.assertEqual(p["context"]["used"], 181387)        # total_input_tokens
        self.assertEqual(p["context"]["total"], 1_000_000)    # context_window_size
        self.assertEqual(p["model"], {"label": "Opus", "badge": "1M"})
        self.assertEqual(p["rate_limits"], {"five_hour": 41, "seven_day": 18})
        # $ cost is intentionally dropped (subscription mode); only diff remains.
        self.assertEqual(p["diff"], {"added": 1200, "removed": 340})
        self.assertNotIn("cost", p)

    def test_legacy_used_tokens_and_badge_window_fallback(self):
        # Older schema: used_tokens present, no context_window_size → infer from 1m flag.
        raw = {**SAMPLE, "model": {"id": "claude-sonnet-4-6"},
               "context_window": {"used_percentage": 62.4, "used_tokens": 128000}}
        p = _statusline_payload_from_raw(raw)
        self.assertEqual(p["context"], {"pct": 62, "used": 128000, "total": 200_000})
        self.assertEqual(p["model"], {"label": "Sonnet"})

    def test_missing_sections_are_omitted_not_crashing(self):
        p = _statusline_payload_from_raw({"model": {"id": "claude-haiku-4-5"}})
        self.assertTrue(p["ok"])
        self.assertEqual(p["model"], {"label": "Haiku"})
        self.assertNotIn("context", p)
        self.assertNotIn("rate_limits", p)
        self.assertNotIn("cost", p)

    def test_empty_input_is_not_ok(self):
        self.assertEqual(_statusline_payload_from_raw({}), {"ok": False})


class StatuslineRouteTests(unittest.TestCase):
    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="annotate-sl-proj-"))
        self.fake_home = Path(tempfile.mkdtemp(prefix="annotate-sl-home-"))
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

    def _get(self, path):
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("GET", path)
        resp = conn.getresponse()
        status = resp.status
        body = resp.read().decode("utf-8")
        conn.close()
        return status, body

    def test_returns_not_ok_when_no_statusline_file(self):
        status, body = self._get(self.base + "/statusline")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"ok": False})

    def test_reads_statusline_file_keyed_by_claude_session_id(self):
        # The skill records which Claude session pushed this response.
        claude_sid = "claude-session-xyz"
        # The annotate skill writes meta.json into response_dir (beside blocks.json).
        meta_path = Path(self.sess["response_dir"]) / "meta.json"
        meta_path.write_text(json.dumps({"claude_session_id": claude_sid}))
        # statusline.sh tees the raw JSON here, keyed by that session id.
        sl_dir = self.fake_home / ".claude" / "annotate" / "statusline"
        sl_dir.mkdir(parents=True, exist_ok=True)
        (sl_dir / f"{claude_sid}.json").write_text(json.dumps(SAMPLE))

        status, body = self._get(self.base + "/statusline")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertTrue(data["ok"])
        self.assertEqual(data["context"]["pct"], 18)
        self.assertEqual(data["context"]["total"], 1_000_000)
        self.assertEqual(data["model"]["label"], "Opus")
        self.assertEqual(data["rate_limits"]["five_hour"], 41)


if __name__ == "__main__":
    unittest.main()
