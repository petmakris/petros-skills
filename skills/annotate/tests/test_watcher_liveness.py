"""Dead-watcher detection.

When the Claude session dies mid-event (crash, closed terminal), the event is
never acked, /poll reports busy forever, and the old client kept the page
locked indefinitely — silently eating every interaction. The fix:

  * server: /poll exposes `watcher_age_s` (seconds since the watcher's last
    heartbeat, computed server-side so client clocks don't matter; null when
    no heartbeat has ever been written).
  * client: a stale heartbeat replaces the busy lock with a visible
    "session is gone" warning and unlocks the page.
"""
import json
import shutil
import tempfile
import time
import unittest
from pathlib import Path

from skills.annotate.tests.test_server import (  # noqa: E402
    _start_server, _create_session, _write_blocks, _http_get,
)

REPO = Path(__file__).resolve().parents[3]
SCRIPT_JS = REPO / "skills" / "annotate" / "static" / "script.js"


class WatcherAgeInPoll(unittest.TestCase):
    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="wl-proj-"))
        self.home = Path(tempfile.mkdtemp(prefix="wl-home-"))
        self.proc, self.info = _start_server(self.home)
        self.sess = _create_session(self.info["port"], self.project)
        _write_blocks(Path(self.sess["response_dir"]), "resp-wl", "T",
                      [{"id": "section-1", "title": "A", "markdown": "hi"}])

    def tearDown(self):
        try:
            self.proc.terminate(); self.proc.wait(timeout=2)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.project, ignore_errors=True)
        shutil.rmtree(self.home, ignore_errors=True)

    def _poll(self) -> dict:
        status, body = _http_get("localhost", self.info["port"],
                                 f"/s/{self.sess['sid']}/poll")
        self.assertEqual(status, 200)
        return json.loads(body)

    def test_no_heartbeat_reports_null_age(self):
        data = self._poll()
        self.assertIn("watcher_age_s", data)
        self.assertIsNone(data["watcher_age_s"])

    def test_fresh_heartbeat_reports_small_age(self):
        hb = Path(self.sess["state_dir"]) / "watcher_heartbeat"
        hb.write_text(str(int(time.time())))
        data = self._poll()
        self.assertIsInstance(data["watcher_age_s"], int)
        self.assertLessEqual(data["watcher_age_s"], 5)

    def test_stale_heartbeat_reports_real_age(self):
        hb = Path(self.sess["state_dir"]) / "watcher_heartbeat"
        hb.write_text(str(int(time.time()) - 120))
        data = self._poll()
        self.assertGreaterEqual(data["watcher_age_s"], 119)


def test_client_unlocks_and_warns_on_dead_watcher():
    src = SCRIPT_JS.read_text()
    assert "watcher_age_s" in src, "script.js never reads watcher_age_s"
    assert "watcher-dead-banner" in src, (
        "script.js has no dead-watcher banner — a crashed session leaves the "
        "page locked with no explanation"
    )
    assert "session is gone" in src, (
        "dead-watcher banner should say plainly that Claude's session is gone"
    )


if __name__ == "__main__":
    unittest.main()
