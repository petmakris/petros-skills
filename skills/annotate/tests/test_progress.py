"""Live-progress hook + poll-surfacing tests.

Covers the PostToolUse progress hook (skills/annotate/hooks/progress_publish.py)
and the /poll field that surfaces it. The headline guarantee — a sensitive tool
argument never reaches the poll JSON — is proven end to end: the real hook
produces the progress file, the real server serves /poll, and the secret is
asserted absent from the wire.
"""
import http.client
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
HOOK = REPO_ROOT / "skills" / "annotate" / "hooks" / "progress_publish.py"

# Reuse the server harness helpers from the sibling test module.
from skills.annotate.tests.test_server import (  # noqa: E402
    _start_server, _create_session, _write_blocks,
)


def _run_hook(home: Path, payload: dict) -> None:
    env = os.environ.copy()
    env["HOME"] = str(home)
    subprocess.run(
        [sys.executable, str(HOOK)],
        input=json.dumps(payload).encode("utf-8"),
        env=env, timeout=10, check=True,
    )


def _registry(home: Path, sid: str, rounds: list) -> None:
    reg = home / ".claude" / "annotate" / f"pending-{sid}.json"
    reg.parent.mkdir(parents=True, exist_ok=True)
    reg.write_text(json.dumps(rounds))


def _round_dirs(base: Path) -> dict:
    state = base / "state"
    events = state / "events"
    consumed = state / "consumed"
    for d in (events, consumed):
        d.mkdir(parents=True, exist_ok=True)
    return {"state_dir": str(state), "events_dir": str(events),
            "consumed_dir": str(consumed)}


def _enqueue(dirs: dict, eid: str) -> None:
    Path(dirs["events_dir"], f"{eid}.json").write_text("{}")


class HookUnitTests(unittest.TestCase):
    def setUp(self):
        self.home = Path(tempfile.mkdtemp(prefix="prog-home-"))
        self.work = Path(tempfile.mkdtemp(prefix="prog-work-"))
        self.sid = "claude-sess-1"
        self.dirs = _round_dirs(self.work)
        _registry(self.home, self.sid, [self.dirs])

    def tearDown(self):
        shutil.rmtree(self.home, ignore_errors=True)
        shutil.rmtree(self.work, ignore_errors=True)

    def _progress_path(self, eid: str) -> Path:
        return Path(self.dirs["state_dir"], "progress", eid)

    def test_secret_arg_never_written_only_allowlisted_label(self):
        _enqueue(self.dirs, "1001")
        secret = "hunter2_TOPSECRET_ZZZ"
        _run_hook(self.home, {
            "session_id": self.sid, "tool_name": "Bash",
            "tool_input": {"command": f"export API_KEY={secret}"},
        })
        body = self._progress_path("1001").read_text()
        self.assertEqual(body, "Running a command…")
        self.assertNotIn(secret, body)

    def test_label_mapping(self):
        cases = {"Read": "Reading files…", "Edit": "Editing the response…",
                 "Bash": "Running a command…", "WebFetch": "Working…"}
        for i, (tool, label) in enumerate(cases.items()):
            eid = str(2000 + i)
            # Fresh single-in-flight state for each case.
            for f in Path(self.dirs["events_dir"]).glob("*.json"):
                f.unlink()
            _enqueue(self.dirs, eid)
            _run_hook(self.home, {"session_id": self.sid, "tool_name": tool,
                                  "tool_input": {}})
            self.assertEqual(self._progress_path(eid).read_text(), label)

    def test_attribution_guard_two_in_flight_writes_nothing(self):
        _enqueue(self.dirs, "3001")
        _enqueue(self.dirs, "3002")
        _run_hook(self.home, {"session_id": self.sid, "tool_name": "Edit",
                              "tool_input": {}})
        self.assertFalse(self._progress_path("3001").exists())
        self.assertFalse(self._progress_path("3002").exists())

    def test_zero_in_flight_writes_nothing(self):
        # No queued events at all → terminal-conversation activity, stay silent.
        _run_hook(self.home, {"session_id": self.sid, "tool_name": "Bash",
                              "tool_input": {}})
        self.assertFalse(Path(self.dirs["state_dir"], "progress").exists()
                         and any(Path(self.dirs["state_dir"], "progress").iterdir()))

    def test_acked_event_progress_is_pruned(self):
        _enqueue(self.dirs, "4001")
        _run_hook(self.home, {"session_id": self.sid, "tool_name": "Read",
                              "tool_input": {}})
        self.assertTrue(self._progress_path("4001").exists())
        # Claude writes the ack → event no longer in-flight; next hook prunes it.
        Path(self.dirs["consumed_dir"], "4001.ack").touch()
        _run_hook(self.home, {"session_id": self.sid, "tool_name": "Bash",
                              "tool_input": {}})
        self.assertFalse(self._progress_path("4001").exists())

    def test_no_registry_is_a_noop(self):
        _run_hook(self.home, {"session_id": "unknown-sess",
                              "tool_name": "Bash", "tool_input": {}})
        # Nothing created under our round.
        self.assertFalse(Path(self.dirs["state_dir"], "progress").exists())


class PollSurfacingTests(unittest.TestCase):
    """End-to-end: real hook → real server /poll, secret stays off the wire."""

    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="prog-proj-"))
        self.home = Path(tempfile.mkdtemp(prefix="prog-srvhome-"))
        self.proc, self.info = _start_server(self.home)
        self.sess = _create_session(self.info["port"], self.project)
        _write_blocks(Path(self.sess["response_dir"]), "resp-x", "T",
                      [{"id": "section-1", "title": "A", "markdown": "hello"}])

    def tearDown(self):
        try:
            self.proc.terminate(); self.proc.wait(timeout=2)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.project, ignore_errors=True)
        shutil.rmtree(self.home, ignore_errors=True)

    def _poll(self) -> dict:
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("GET", f"/s/{self.sess['sid']}/poll")
        resp = conn.getresponse()
        raw = resp.read().decode("utf-8")
        conn.close()
        return json.loads(raw), raw

    def test_hook_label_appears_in_poll_without_the_secret(self):
        sid = "claude-sess-poll"
        dirs = {"state_dir": self.sess["state_dir"],
                "events_dir": self.sess["events_dir"],
                "consumed_dir": self.sess["consumed_dir"]}
        _registry(self.home, sid, [dirs])
        eid = "5001"
        _enqueue(dirs, eid)
        secret = "S3CRET_shibboleth_QQ"
        _run_hook(self.home, {
            "session_id": sid, "tool_name": "Bash",
            "tool_input": {"command": f"curl -H 'Authorization: {secret}'"},
        })
        data, raw = self._poll()
        self.assertEqual(data["progress"].get(eid), "Running a command…")
        self.assertNotIn(secret, raw)

    def test_poll_caps_label_length(self):
        # A malformed (non-hook) over-long progress file is length-capped.
        prog = Path(self.sess["state_dir"], "progress")
        prog.mkdir(parents=True, exist_ok=True)
        (prog / "6001").write_text("x" * 500)
        data, _ = self._poll()
        self.assertLessEqual(len(data["progress"]["6001"]), 40)

    def test_consumed_event_progress_is_filtered(self):
        prog = Path(self.sess["state_dir"], "progress")
        prog.mkdir(parents=True, exist_ok=True)
        (prog / "7001").write_text("Editing the response…")
        Path(self.sess["consumed_dir"], "7001.ack").touch()
        data, _ = self._poll()
        self.assertNotIn("7001", data["progress"])


if __name__ == "__main__":
    unittest.main()
