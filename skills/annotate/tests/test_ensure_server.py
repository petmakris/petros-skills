import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / "skills" / "annotate" / "ensure_server.sh"


class EnsureServerTests(unittest.TestCase):
    def setUp(self):
        self.home = Path(tempfile.mkdtemp(prefix="annotate-home-"))
        self.env = os.environ.copy()
        self.env["HOME"] = str(self.home)
        self.env["PYTHONPATH"] = str(REPO_ROOT)
        # Keep idle timeout long enough that the server is alive across test steps.
        self.env["ANNOTATE_SHUTDOWN_SECONDS"] = "60"

    def tearDown(self):
        # ensure_server.sh records the spawned PID at $HOME/.claude/annotate/server.pid.
        pid_path = self.home / ".claude" / "annotate" / "server.pid"
        if pid_path.exists():
            try:
                pid = int(pid_path.read_text().strip())
                os.kill(pid, 15)
            except (ProcessLookupError, ValueError, OSError):
                pass
        shutil.rmtree(self.home, ignore_errors=True)

    def test_starts_server_when_none_running(self):
        result = subprocess.run(
            ["bash", str(SCRIPT)],
            env=self.env, capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        info_path = self.home / ".claude" / "annotate" / "server.json"
        self.assertTrue(info_path.exists(), "server.json should be written")
        info = json.loads(info_path.read_text())
        with urllib.request.urlopen(info["url"] + "/health", timeout=2) as r:
            body = r.read().decode("utf-8")
        self.assertIn("annotate-server v1", body)

    def test_noop_when_server_already_running(self):
        subprocess.run(["bash", str(SCRIPT)], env=self.env, check=True, timeout=10)
        info1 = json.loads((self.home / ".claude" / "annotate" / "server.json").read_text())
        t0 = time.time()
        result = subprocess.run(
            ["bash", str(SCRIPT)],
            env=self.env, capture_output=True, text=True, timeout=5,
        )
        elapsed = time.time() - t0
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertLess(elapsed, 1.0, "second invocation should be <1s (no-op path)")
        info2 = json.loads((self.home / ".claude" / "annotate" / "server.json").read_text())
        self.assertEqual(info1["port"], info2["port"])

    def test_restarts_if_health_banner_missing(self):
        # Plant a bogus server.json pointing at a port nothing useful is listening on.
        d = self.home / ".claude" / "annotate"
        d.mkdir(parents=True, exist_ok=True)
        (d / "server.json").write_text(json.dumps({
            "port": 1, "url": "http://localhost:1",
        }))
        result = subprocess.run(
            ["bash", str(SCRIPT)],
            env=self.env, capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        info = json.loads((self.home / ".claude" / "annotate" / "server.json").read_text())
        self.assertNotEqual(info["port"], 1)


if __name__ == "__main__":
    unittest.main()
