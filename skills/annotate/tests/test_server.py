import http.client
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


def _http_get(host: str, port: int, path: str, timeout: float = 2.0):
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        conn.request("GET", path)
        resp = conn.getresponse()
        return resp.status, resp.read().decode("utf-8")
    finally:
        conn.close()


def _start_server(fake_home: Path) -> tuple[subprocess.Popen, dict]:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(REPO_ROOT)
    env["ANNOTATE_SHUTDOWN_SECONDS"] = "60"
    env["HOME"] = str(fake_home)
    # Pin announced URLs to localhost so tests don't depend on Tailscale state.
    env["ANNOTATE_PUBLIC_HOST"] = "localhost"
    proc = subprocess.Popen(
        [sys.executable, "-m", "skills.annotate.server"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
    )
    line = proc.stdout.readline().decode("utf-8")
    if not line:
        proc.wait(timeout=2)
        raise RuntimeError(f"server died before printing info: {proc.stderr.read().decode('utf-8')}")
    info = json.loads(line)
    return proc, info


def _create_session(port: int, cwd: Path) -> dict:
    conn = http.client.HTTPConnection("localhost", port, timeout=2)
    conn.request(
        "POST", "/api/sessions",
        body=json.dumps({"cwd": str(cwd)}),
        headers={"Content-Type": "application/json"},
    )
    resp = conn.getresponse()
    assert resp.status == 200, resp.status
    sess = json.loads(resp.read().decode("utf-8"))
    conn.close()
    return sess


class ServerStartupTests(unittest.TestCase):
    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="annotate-test-"))
        self.fake_home = Path(tempfile.mkdtemp(prefix="annotate-home-"))
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

    def test_writes_global_server_info(self):
        home_info_path = self.fake_home / ".claude" / "annotate" / "server.json"
        self.assertTrue(home_info_path.exists())
        global_info = json.loads(home_info_path.read_text())
        self.assertEqual(global_info["port"], self.info["port"])
        self.assertEqual(global_info["url"], self.info["url"])

    def test_health_endpoint_returns_banner(self):
        status, body = _http_get("localhost", self.info["port"], "/health")
        self.assertEqual(status, 200)
        self.assertIn("annotate-server v1", body)

    def test_session_dirs_are_created(self):
        for key in ("response_dir", "annotations_dir", "state_dir"):
            self.assertTrue(Path(self.sess[key]).is_dir(), f"{key} should be a directory")
        # Per-cwd location.
        expected_base = self.project / ".claude" / "annotate" / self.sess["sid"]
        self.assertEqual(Path(self.sess["response_dir"]).parent, expected_base)

    def test_create_session_rejects_missing_cwd(self):
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", "/api/sessions",
                     body=json.dumps({}),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 400)
        conn.close()

    def test_create_session_rejects_bogus_cwd(self):
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", "/api/sessions",
                     body=json.dumps({"cwd": "/no/such/dir/exists/anywhere"}),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 400)
        conn.close()

    def test_root_serves_waiting_page_when_no_response(self):
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        self.assertIn("<!DOCTYPE html>", body)
        self.assertIn("Waiting for a response", body)

    def test_root_serves_session_index(self):
        status, body = _http_get("localhost", self.info["port"], "/")
        self.assertEqual(status, 200)
        self.assertIn("annotate-server", body)
        # The test's session should appear in the index by sid.
        self.assertIn(self.sess["sid"], body)
        # And link to it.
        self.assertIn(f'href="/s/{self.sess["sid"]}/"', body)

    def test_root_serves_response_shell(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-1",
            "title": "Auth refactor",
        }))
        (response_dir / "response.md").write_text("## Plan\n\nDual-write for two weeks.")
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        # Header / title / response-id are rendered server-side.
        self.assertIn("Auth refactor", body)
        self.assertIn('data-response-id="resp-1"', body)
        # The shell ships an empty <main class="prose"></main>; markdown body is
        # fetched and rendered by markdown-it on the client.
        self.assertIn('<main class="prose"></main>', body)
        self.assertNotIn("Dual-write", body)  # not in the shell — JS fetches /raw
        self.assertIn('id="cancel-btn"', body)
        self.assertIn('class="page-header"', body)
        self.assertIn('id="theme-light"', body)
        self.assertIn('id="theme-dark"', body)
        self.assertIn('annotate.theme', body)
        # Client-side renderer is loaded.
        self.assertIn("/static/markdown-it.min.js", body)
        self.assertIn("/static/script.js", body)

    def test_response_shell_does_not_load_cdn_fonts(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-cdn", "title": "T",
        }))
        (response_dir / "response.md").write_text("body")
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        self.assertNotIn("fonts.googleapis.com", body)
        self.assertNotIn("fonts.gstatic.com", body)

    def test_raw_returns_markdown_bytes(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-raw", "title": "T",
        }))
        markdown = "# Heading\n\nA *paragraph* with **mixed** formatting.\n"
        (response_dir / "response.md").write_text(markdown)
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("GET", self.base + "/raw")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        ctype = resp.getheader("Content-Type", "")
        self.assertIn("text/markdown", ctype)
        body = resp.read().decode("utf-8")
        conn.close()
        self.assertEqual(body, markdown)

    def test_raw_returns_404_when_no_response(self):
        status, _ = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 404)

    def test_static_serves_markdown_it_bundle(self):
        status, body = _http_get("localhost", self.info["port"], "/static/markdown-it.min.js")
        self.assertEqual(status, 200)
        self.assertIn("markdown-it", body)

    def test_submit_writes_annotations_json(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-7", "title": "T",
        }))
        (response_dir / "response.md").write_text("para")

        payload = {
            "response_id": "resp-7",
            "annotations": [
                {"block_id": "b-0", "selected_text": "para", "comment": "hi"},
            ],
        }
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        conn.close()

        out = json.loads((Path(self.sess["annotations_dir"]) / "annotations.json").read_text())
        self.assertEqual(out["response_id"], "resp-7")
        self.assertEqual(out["annotations"], payload["annotations"])
        self.assertIn("submitted_at", out)

    def test_submit_returns_409_on_stale_response_id(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-new", "title": "T",
        }))
        (response_dir / "response.md").write_text("para")

        payload = {"response_id": "resp-OLD", "annotations": []}
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 409)
        conn.close()
        self.assertFalse((Path(self.sess["annotations_dir"]) / "annotations.json").exists())

    def test_poll_returns_current_response_id(self):
        response_dir = Path(self.sess["response_dir"])
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"response_id": None, "terminal": None})

        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-poll-1", "title": "T",
        }))
        (response_dir / "response.md").write_text("x")
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"response_id": "resp-poll-1", "terminal": None})

    def test_static_files_are_served(self):
        status, body = _http_get("localhost", self.info["port"], "/static/style.css")
        self.assertEqual(status, 200)
        self.assertIn("--bg: #1a1d22", body)
        status, body = _http_get("localhost", self.info["port"], "/static/script.js")
        self.assertEqual(status, 200)
        self.assertIn("annotate skill", body)
        self.assertIn("const BASE", body)
        self.assertNotIn('fetch("/api/submit"', body)
        self.assertNotIn('fetch("/poll")', body)
        self.assertNotIn('fetch("/api/cancel"', body)

    def test_static_blocks_path_traversal(self):
        status, _ = _http_get("localhost", self.info["port"], "/static/../server.py")
        self.assertEqual(status, 404)

    def test_cancel_endpoint_writes_cancelled_marker(self):
        url = f"http://localhost:{self.info['port']}{self.base}/api/cancel"
        req = urllib.request.Request(url, data=b"", method="POST")
        with urllib.request.urlopen(req, timeout=2) as resp:
            self.assertEqual(resp.status, 200)
        cancelled = Path(self.sess["state_dir"]) / "cancelled"
        self.assertTrue(cancelled.exists())
        payload = json.loads(cancelled.read_text())
        self.assertEqual(payload["reason"], "user-cancelled")
        self.assertIsInstance(payload["at"], int)

    def test_submit_writes_submitted_marker(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-mark", "title": "T",
        }))
        (response_dir / "response.md").write_text("para")
        payload = {"response_id": "resp-mark", "annotations": []}
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        conn.close()
        self.assertTrue((Path(self.sess["state_dir"]) / "submitted").exists())

    def test_root_serves_closed_when_submitted_marker_present(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-locked", "title": "T",
        }))
        (response_dir / "response.md").write_text("para")
        (Path(self.sess["state_dir"]) / "submitted").write_text("")
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        self.assertIn("closed", body.lower())
        self.assertNotIn("Waiting for a response", body)
        self.assertNotIn('data-response-id="resp-locked"', body)
        # The closed page must not load script.js — otherwise a stale tab
        # could try to re-submit. Statically rendered, no JS.
        self.assertNotIn("script.js", body)

    def test_root_serves_closed_when_cancelled_marker_present(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-cancel", "title": "T",
        }))
        (response_dir / "response.md").write_text("para")
        (Path(self.sess["state_dir"]) / "cancelled").write_text("{}")
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        self.assertIn("closed", body.lower())
        self.assertNotIn('data-response-id="resp-cancel"', body)

    def test_raw_returns_410_after_submit(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-r", "title": "T",
        }))
        (response_dir / "response.md").write_text("sensitive content")
        (Path(self.sess["state_dir"]) / "submitted").write_text("")
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 410)
        # Body must not contain the response content — the whole point of 410
        # here is that the URL no longer serves the prose.
        self.assertNotIn("sensitive content", body)

    def test_raw_returns_410_after_cancel(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-r", "title": "T",
        }))
        (response_dir / "response.md").write_text("sensitive content")
        (Path(self.sess["state_dir"]) / "cancelled").write_text("{}")
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 410)
        self.assertNotIn("sensitive content", body)

    def test_poll_reports_terminal_after_submit(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-t", "title": "T",
        }))
        (Path(self.sess["state_dir"]) / "submitted").write_text("")
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(status, 200)
        payload = json.loads(body)
        self.assertEqual(payload.get("terminal"), "submitted")

    def test_poll_reports_terminal_after_cancel(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-t", "title": "T",
        }))
        (Path(self.sess["state_dir"]) / "cancelled").write_text("{}")
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(status, 200)
        payload = json.loads(body)
        self.assertEqual(payload.get("terminal"), "cancelled")

    def test_poll_terminal_null_when_session_active(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-t", "title": "T",
        }))
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(status, 200)
        payload = json.loads(body)
        self.assertIsNone(payload.get("terminal"))

    def test_submit_after_submit_returns_409(self):
        # Double-submit guard. Without it, a slow network + double-click would
        # let the second payload clobber the first.
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-ds", "title": "T",
        }))
        (Path(self.sess["state_dir"]) / "submitted").write_text("")
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit",
                     body=json.dumps({"response_id": "resp-ds", "annotations": []}),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 409)
        conn.close()

    def test_cancel_after_submit_returns_409(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-cs", "title": "T",
        }))
        (Path(self.sess["state_dir"]) / "submitted").write_text("")
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/cancel", body="")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 409)
        conn.close()

    def test_static_serves_bricolage_grotesque_font(self):
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("GET", "/static/fonts/BricolageGrotesque-Variable.woff2")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        body = resp.read()
        conn.close()
        self.assertGreater(len(body), 10000)

    def test_static_serves_monaspace_radon_woff2(self):
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("GET", "/static/fonts/MonaspaceRadon-Regular.woff2")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        body = resp.read()
        conn.close()
        self.assertGreater(len(body), 10000)
        self.assertEqual(body[:4], b"wOF2")

    def test_style_css_declares_monaspace_face(self):
        status, body = _http_get("localhost", self.info["port"], "/static/style.css")
        self.assertEqual(status, 200)
        self.assertIn("@font-face", body)
        self.assertIn("Monaspace Radon", body)
        self.assertIn("/static/fonts/MonaspaceRadon-Regular.woff2", body)

    def test_script_js_has_no_block_approve_remnants(self):
        status, body = _http_get("localhost", self.info["port"], "/static/script.js")
        self.assertEqual(status, 200)
        for term in ("blockApprovals", "renderBlockCheckboxes", "APPROVE_KEY"):
            self.assertNotIn(term, body, f"v3 token '{term}' should be removed")
        self.assertIn("renderHoverActions", body)
        self.assertIn("applyEngagedStyling", body)

    def test_submit_payload_without_approve_type_accepted(self):
        response_dir = Path(self.sess["response_dir"])
        (response_dir / "meta.json").write_text(json.dumps({
            "response_id": "resp-v4", "title": "T",
        }))
        (response_dir / "response.md").write_text("para")

        payload = {
            "response_id": "resp-v4",
            "annotations": [
                {"block_id": "b-0", "type": "question", "selected_text": "para", "comment": "why?"},
            ],
        }
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        conn.close()
        out = json.loads((Path(self.sess["annotations_dir"]) / "annotations.json").read_text())
        types = [a["type"] for a in out["annotations"]]
        self.assertEqual(types, ["question"])
        self.assertNotIn("approve", types)

    def test_static_style_defines_three_accent_variants(self):
        status, body = _http_get("localhost", self.info["port"], "/static/style.css")
        self.assertEqual(status, 200)
        for accent in ("mint", "lavender", "blue"):
            self.assertIn(f'[data-accent="{accent}"]', body,
                          f"accent variant {accent!r} missing from style.css")
        for theme in ("dark", "light"):
            for accent in ("mint", "lavender", "blue"):
                self.assertIn(
                    f'[data-theme="{theme}"][data-accent="{accent}"]',
                    body,
                    f"missing accent selector for theme={theme} accent={accent}",
                )

    def test_two_concurrent_sessions_are_isolated(self):
        other_project = Path(tempfile.mkdtemp(prefix="annotate-other-"))
        self.addCleanup(shutil.rmtree, other_project, True)
        sess2 = _create_session(self.info["port"], other_project)
        self.assertNotEqual(sess2["sid"], self.sess["sid"])

        # Write response under session 1 only.
        (Path(self.sess["response_dir"]) / "meta.json").write_text(json.dumps({
            "response_id": "resp-1", "title": "S1",
        }))
        (Path(self.sess["response_dir"]) / "response.md").write_text("one")

        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertIn("S1", body)
        # Session 2 still shows waiting page.
        status, body = _http_get("localhost", self.info["port"], f"/s/{sess2['sid']}/")
        self.assertEqual(status, 200)
        self.assertIn("Waiting for a response", body)


class ServerIdleShutdownTests(unittest.TestCase):
    def setUp(self):
        self.fake_home = Path(tempfile.mkdtemp(prefix="annotate-home-"))
        env = os.environ.copy()
        env["PYTHONPATH"] = str(REPO_ROOT)
        env["ANNOTATE_SHUTDOWN_SECONDS"] = "2"
        env["HOME"] = str(self.fake_home)
        self.proc = subprocess.Popen(
            [sys.executable, "-m", "skills.annotate.server"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
        )

    def tearDown(self):
        try:
            self.proc.terminate()
            self.proc.wait(timeout=2)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.fake_home, ignore_errors=True)

    def test_idle_shutdown_exits_clean(self):
        # Wait for startup line.
        line = self.proc.stdout.readline()
        self.assertTrue(line, "server didn't start")
        rc = self.proc.wait(timeout=8)
        self.assertEqual(rc, 0)


if __name__ == "__main__":
    unittest.main()
