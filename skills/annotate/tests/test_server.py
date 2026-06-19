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


def _write_blocks(response_dir: Path, response_id: str, title: str, blocks: list) -> None:
    """Write a blocks.json file for testing."""
    doc = {"response_id": response_id, "title": title, "blocks": blocks}
    path = response_dir / "blocks.json"
    path.write_text(json.dumps(doc))


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
        for key in ("response_dir", "annotations_dir", "state_dir", "events_dir", "consumed_dir"):
            self.assertIn(key, self.sess, f"{key} missing from session response")
            self.assertTrue(Path(self.sess[key]).is_dir(), f"{key} should be a directory")
        # Per-cwd location.
        expected_base = self.project / ".claude" / "annotate" / self.sess["sid"]
        self.assertEqual(Path(self.sess["response_dir"]).parent, expected_base)

    def test_session_includes_localhost_url(self):
        self.assertIn("localhost_url", self.sess)
        self.assertTrue(self.sess["localhost_url"].startswith("http://localhost:"))
        self.assertTrue(self.sess["localhost_url"].endswith(f"/s/{self.sess['sid']}/"))
        # Same port as the announced public URL, different host.
        self.assertEqual(self.sess["localhost_url"].rsplit(":", 1)[1],
                         self.sess["url"].rsplit(":", 1)[1])

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

    def test_root_serves_blocks_shell(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-1", "Auth refactor", [
            {"id": "b-0", "markdown": "## Plan\n\nDual-write for two weeks.", "version": 1},
        ])
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        # Title and response-id are in the header.
        self.assertIn("Auth refactor", body)
        self.assertIn("resp-1", body)
        # Done button replaces old Submit/Cancel footer.
        self.assertIn('id="done-btn"', body)
        self.assertNotIn('id="cancel-btn"', body)
        self.assertNotIn('id="submit-btn"', body)
        # The shell ships an empty <main class="prose"></main>; JS renders blocks.
        self.assertIn('<main class="prose"></main>', body)
        # Markdown content is not inlined — JS fetches /raw.
        self.assertNotIn("Dual-write", body)
        self.assertIn('class="page-header"', body)
        # Syntax-highlighting assets are wired in, and highlight.js loads before
        # script.js (which builds the markdown-it highlight hook).
        self.assertIn('href="/static/code-theme.css"', body)
        self.assertLess(body.index('/static/highlight.min.js'),
                        body.index('/static/script.js'))

    def test_root_serves_closed_when_cancelled_marker_present(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-cancel", "T", [])
        (Path(self.sess["state_dir"]) / "cancelled").write_text("{}")
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        self.assertIn("closed", body.lower())
        self.assertNotIn("resp-cancel", body)

    def test_root_serves_closed_when_finished_marker_present(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-done", "T", [])
        (Path(self.sess["state_dir"]) / "finished").write_text("")
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        self.assertIn("closed", body.lower())
        self.assertNotIn("resp-done", body)

    def test_raw_returns_full_blocks_json(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-raw", "T", [
            {"id": "b-0", "markdown": "hello", "version": 1},
        ])
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("GET", self.base + "/raw")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        ctype = resp.getheader("Content-Type", "")
        self.assertIn("application/json", ctype)
        data = json.loads(resp.read().decode("utf-8"))
        conn.close()
        self.assertEqual(data["response_id"], "resp-raw")
        self.assertEqual(len(data["blocks"]), 1)
        self.assertEqual(data["blocks"][0]["id"], "b-0")

    def test_raw_includes_glossary(self):
        response_dir = Path(self.sess["response_dir"])
        # _write_blocks does not handle glossary — use BlocksDoc directly.
        from skills.annotate.blocks import BlocksDoc, save_atomic
        doc = BlocksDoc(
            response_id="r-gloss", title="T",
            blocks=[{"id": "b-0", "markdown": "x", "version": 1}],
            glossary=[{"term": "Foo", "definition": "a foo", "role": "the bar"}],
        )
        save_atomic(response_dir / "blocks.json", doc)
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("GET", self.base + "/raw")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        data = json.loads(resp.read().decode("utf-8"))
        conn.close()
        self.assertEqual(data["glossary"], [
            {"term": "Foo", "definition": "a foo", "role": "the bar"}
        ])

    def test_raw_empty_glossary_returns_empty_list(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-noglos", "T", [
            {"id": "b-0", "markdown": "x", "version": 1},
        ])
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("GET", self.base + "/raw")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        data = json.loads(resp.read().decode("utf-8"))
        conn.close()
        self.assertEqual(data["glossary"], [])

    def test_raw_block_query_returns_single_block(self):
        response_dir = Path(self.sess["response_dir"])
        # Drive b-1's derived version to 2 by writing it, polling, then changing it.
        _write_blocks(response_dir, "resp-blk", "T", [
            {"id": "b-0", "markdown": "first"},
            {"id": "b-1", "markdown": "second-v1"},
        ])
        _http_get("localhost", self.info["port"], self.base + "/poll")
        _write_blocks(response_dir, "resp-blk", "T", [
            {"id": "b-0", "markdown": "first"},
            {"id": "b-1", "markdown": "second"},
        ])
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("GET", self.base + "/raw?block=b-1")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        data = json.loads(resp.read().decode("utf-8"))
        conn.close()
        self.assertEqual(data["id"], "b-1")
        self.assertEqual(data["markdown"], "second")
        self.assertEqual(data["version"], 2)

    def test_raw_block_query_returns_404_for_unknown_block(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-noblk", "T", [
            {"id": "b-0", "markdown": "only", "version": 1},
        ])
        status, _ = _http_get("localhost", self.info["port"], self.base + "/raw?block=b-99")
        self.assertEqual(status, 404)

    def test_static_serves_markdown_it_bundle(self):
        status, body = _http_get("localhost", self.info["port"], "/static/markdown-it.min.js")
        self.assertEqual(status, 200)
        self.assertIn("markdown-it", body)

    def test_static_files_are_served(self):
        status, body = _http_get("localhost", self.info["port"], "/static/style.css")
        self.assertEqual(status, 200)
        # style.css is annotate-specific; tokens live in core.css
        self.assertIn("main.prose", body)
        status, body = _http_get("localhost", self.info["port"], "/static/script.js")
        self.assertEqual(status, 200)
        self.assertIn("annotate skill", body)
        self.assertIn("const BASE", body)
        self.assertNotIn('fetch("/api/submit"', body)
        self.assertNotIn('fetch("/poll")', body)
        self.assertNotIn('fetch("/api/cancel"', body)
        # core.css (shared) serves the --bg token — single light theme.
        status, body = _http_get("localhost", self.info["port"], "/static/core.css")
        self.assertEqual(status, 200)
        self.assertIn("--bg: #e4e7ed", body)

    def test_static_blocks_path_traversal(self):
        status, _ = _http_get("localhost", self.info["port"], "/static/../server.py")
        self.assertEqual(status, 404)

    def test_static_assets_send_no_store_cache_header(self):
        """Local dev iteration must not be blocked by browser CSS/JS caches."""
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        for path in ("/static/style.css", "/static/script.js", "/static/core.css",
                     "/static/diagram.css"):
            conn.request("GET", path)
            resp = conn.getresponse()
            self.assertEqual(resp.status, 200, f"{path} should be served")
            self.assertEqual(resp.getheader("Cache-Control"), "no-store",
                             f"{path} must send Cache-Control: no-store")
            resp.read()  # drain body
        conn.close()

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

    def test_cancel_after_finished_returns_409(self):
        (Path(self.sess["state_dir"]) / "finished").write_text("")
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/cancel", body="")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 409)
        conn.close()

    def test_submit_writes_event_file(self):
        """Per-block submit creates one event JSON in events_dir."""
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-ev", "T", [
            {"id": "b-0", "markdown": "some text", "version": 1},
        ])
        payload = {
            "block_id": "b-0",
            "type": "comment",
            "text": "looks good",
            "selected_text": "some text",
        }
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 202)
        body = json.loads(resp.read().decode("utf-8"))
        conn.close()
        self.assertIn("event_id", body)
        self.assertEqual(body["status"], "queued")
        events_dir = Path(self.sess["events_dir"])
        event_files = list(events_dir.glob("*.json"))
        self.assertEqual(len(event_files), 1)
        evt = json.loads(event_files[0].read_text())
        self.assertEqual(evt["block_id"], "b-0")
        self.assertEqual(evt["type"], "comment")
        self.assertEqual(evt["text"], "looks good")
        self.assertEqual(evt["selected_text"], "some text")

    def test_submit_general_comment_has_null_block_id(self):
        """General comment (no block_id) stores null block_id in event."""
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-gen", "T", [])
        payload = {"type": "comment", "text": "overall: needs work"}
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 202)
        conn.close()
        events_dir = Path(self.sess["events_dir"])
        event_files = list(events_dir.glob("*.json"))
        self.assertEqual(len(event_files), 1)
        evt = json.loads(event_files[0].read_text())
        self.assertIsNone(evt["block_id"])

    def test_submit_rejects_bad_type(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-bt", "T", [])
        payload = {"type": "approve", "text": "lgtm"}
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 400)
        conn.close()

    def test_submit_rejects_image_path_outside_images_dir(self):
        """A client-supplied image path outside <state_dir>/images/ is rejected
        (422) — stops a hostile client naming an arbitrary file for Claude to
        read as a 'pasted image'."""
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-img", "T", [
            {"id": "b-0", "markdown": "some text"},
        ])
        payload = {
            "block_id": "b-0",
            "type": "comment",
            "text": "see attached",
            "images": [{"token": "paste-1", "path": "/etc/passwd"}],
        }
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 422)
        conn.close()
        self.assertEqual(len(list(Path(self.sess["events_dir"]).glob("*.json"))), 0)

    def test_submit_accepts_image_path_under_images_dir(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-img2", "T", [
            {"id": "b-0", "markdown": "some text"},
        ])
        images_dir = Path(self.sess["state_dir"]) / "images"
        images_dir.mkdir(parents=True, exist_ok=True)
        img_path = images_dir / "abc.png"
        img_path.write_bytes(b"\x89PNG")
        payload = {
            "block_id": "b-0",
            "type": "comment",
            "text": "see attached",
            "images": [{"token": "paste-1", "path": str(img_path)}],
        }
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 202)
        conn.close()
        self.assertEqual(len(list(Path(self.sess["events_dir"]).glob("*.json"))), 1)

    def test_submit_rejects_nonexistent_image_under_images_dir(self):
        """A path inside images/ that isn't a real file (never uploaded) is
        rejected — uploads always precede the submit that echoes the path."""
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-img3", "T", [
            {"id": "b-0", "markdown": "some text"},
        ])
        ghost = Path(self.sess["state_dir"]) / "images" / "never-uploaded.png"
        payload = {
            "block_id": "b-0",
            "type": "comment",
            "text": "see attached",
            "images": [{"token": "paste-1", "path": str(ghost)}],
        }
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 422)
        conn.close()

    def test_submit_returns_409_when_terminal(self):
        (Path(self.sess["state_dir"]) / "finished").write_text("")
        payload = {"type": "comment", "text": "too late"}
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 409)
        conn.close()

    def test_poll_returns_version_vector(self):
        """/poll derives {id: version} from observed content changes,
        not from any field Claude wrote."""
        response_dir = Path(self.sess["response_dir"])
        # Initial write — both blocks at v1 after first poll.
        _write_blocks(response_dir, "resp-poll", "T", [
            {"id": "b-0", "markdown": "alpha"},
            {"id": "b-1", "markdown": "beta"},
        ])
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data["response_id"], "resp-poll")
        self.assertEqual(data["blocks"], {"b-0": 1, "b-1": 1})

        # Edit b-1 twice — only b-1's chain grows.
        _write_blocks(response_dir, "resp-poll", "T", [
            {"id": "b-0", "markdown": "alpha"},
            {"id": "b-1", "markdown": "beta-edited"},
        ])
        _http_get("localhost", self.info["port"], self.base + "/poll")
        _write_blocks(response_dir, "resp-poll", "T", [
            {"id": "b-0", "markdown": "alpha"},
            {"id": "b-1", "markdown": "beta-edited-again"},
        ])
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        data = json.loads(body)
        self.assertEqual(data["blocks"], {"b-0": 1, "b-1": 3})
        self.assertFalse(data["finished"])
        self.assertIsInstance(data["watcher_seen_at"], int)

    def test_poll_reports_finished_true_when_finished_marker(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-pf", "T", [])
        (Path(self.sess["state_dir"]) / "finished").write_text("")
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertTrue(data["finished"])

    def test_poll_reports_finished_true_when_cancelled_marker(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-pc", "T", [])
        (Path(self.sess["state_dir"]) / "cancelled").write_text("{}")
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertTrue(data["finished"])

    def test_poll_empty_blocks_when_no_blocks_json(self):
        # No blocks.json yet — poll returns empty blocks dict.
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data["blocks"], {})
        self.assertFalse(data["finished"])

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

    def test_core_css_declares_monaspace_face(self):
        # @font-face declarations moved to shared core.css
        status, body = _http_get("localhost", self.info["port"], "/static/core.css")
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

    def test_core_css_is_single_slate_sky_theme(self):
        # One palette baked into :root — the Apple-blue accent — no runtime
        # accent switching (the data-accent variant indirection was retired).
        status, body = _http_get("localhost", self.info["port"], "/static/core.css")
        self.assertEqual(status, 200)
        self.assertIn("--accent: #0071e3", body)
        self.assertNotIn('[data-accent="', body)
        self.assertNotIn('[data-theme="dark"]', body)
        self.assertNotIn('[data-theme="light"]', body)

    def test_two_concurrent_sessions_are_isolated(self):
        other_project = Path(tempfile.mkdtemp(prefix="annotate-other-"))
        self.addCleanup(shutil.rmtree, other_project, True)
        sess2 = _create_session(self.info["port"], other_project)
        self.assertNotEqual(sess2["sid"], self.sess["sid"])

        # Write blocks.json under session 1 only.
        _write_blocks(Path(self.sess["response_dir"]), "resp-1", "S1", [
            {"id": "b-0", "markdown": "one", "version": 1},
        ])

        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertIn("S1", body)
        # Session 2 still shows waiting page.
        status, body = _http_get("localhost", self.info["port"], f"/s/{sess2['sid']}/")
        self.assertEqual(status, 200)
        self.assertIn("Waiting for a response", body)

    def test_core_css_typographic_refinements(self):
        # Body typography and color tokens moved to shared core.css
        status, body = _http_get("localhost", self.info["port"], "/static/core.css")
        self.assertEqual(status, 200)
        self.assertIn('font-size: 15.5px', body)
        self.assertIn('line-height: 1.6', body)
        self.assertIn('--bg: #e4e7ed', body)
        # annotate heading typography is in style.css
        status, body = _http_get("localhost", self.info["port"], "/static/style.css")
        self.assertEqual(status, 200)
        self.assertIn('letter-spacing: -0.022em', body)

    def test_raw_returns_svg_for_sequence_block(self):
        """A block with kind=sequence in blocks.json round-trips with a rendered 'svg' field."""
        response_dir = Path(self.sess["response_dir"])
        spec = {
            "actors": [{"id": "a", "label": "A"}, {"id": "b", "label": "B"}],
            "steps": [{"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "ping"}],
        }
        _write_blocks(response_dir, "resp-seq", "T", [
            {"id": "b-0", "markdown": "intro", "version": 1},
            {"id": "b-1", "kind": "sequence", "spec": spec, "version": 1},
        ])
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data = json.loads(body)
        md_blk = next(b for b in data["blocks"] if b["id"] == "b-0")
        seq_blk = next(b for b in data["blocks"] if b["id"] == "b-1")
        # Markdown block unaffected (no svg field).
        self.assertEqual(md_blk["kind"], "markdown")
        self.assertEqual(md_blk["markdown"], "intro")
        self.assertNotIn("svg", md_blk)
        # Sequence block has svg + spec, kind preserved.
        self.assertEqual(seq_blk["kind"], "sequence")
        self.assertTrue(seq_blk["svg"].startswith("<svg"))
        self.assertIn('data-step-id="s1"', seq_blk["svg"])
        self.assertEqual(seq_blk["spec"], spec)

    def test_raw_includes_authored_title(self):
        """An authored block `title` round-trips through /raw so the client can
        render it as the card header (else it falls back to deriving one)."""
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-title", "T", [
            {"id": "section-1", "title": "How this works", "markdown": "body text"},
            {"id": "section-2", "markdown": "no title here"},
        ])
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data = json.loads(body)
        titled = next(b for b in data["blocks"] if b["id"] == "section-1")
        untitled = next(b for b in data["blocks"] if b["id"] == "section-2")
        self.assertEqual(titled["title"], "How this works")
        self.assertNotIn("title", untitled)

    def test_raw_renders_error_block_for_invalid_spec(self):
        """An invalid sequence spec should render a compact inline error pill,
        not crash the request and not dominate the page."""
        response_dir = Path(self.sess["response_dir"])
        bad_spec = {"actors": [{"id": "a", "label": "A"}], "steps": []}  # < 2 actors
        _write_blocks(response_dir, "resp-bad", "T", [
            {"id": "b-0", "kind": "sequence", "spec": bad_spec, "version": 1},
        ])
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data["blocks"][0]["kind"], "sequence")
        svg = data["blocks"][0]["svg"]
        # Compact pill: error class, small viewBox, friendly headline,
        # full message tucked in <title> for hover tooltip.
        self.assertTrue(svg.startswith("<svg"))
        self.assertIn("annotate-seq-error", svg)
        self.assertIn("diagram render failed", svg)
        self.assertIn("<title>", svg)
        self.assertIn("at least 2 actors", svg)  # the actual reason, in <title>

    def test_root_html_links_diagram_css(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-css", "T", [
            {"id": "b-0", "markdown": "hi", "version": 1},
        ])
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        self.assertIn('href="/static/diagram.css"', body)

    def _post_json(self, path: str, payload: dict):
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", path, body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        body = resp.read().decode("utf-8")
        status = resp.status
        conn.close()
        return status, body

    def _seq_blocks(self):
        spec = {
            "actors": [{"id": "a", "label": "A"}, {"id": "b", "label": "B"}],
            "steps": [{"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "x"}],
        }
        return [{"id": "b-0", "kind": "sequence", "spec": spec, "version": 1}]

    def test_submit_with_step_id_succeeds_when_step_exists(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-sid", "T", self._seq_blocks())
        status, body = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "step_id": "s1", "type": "comment", "text": "what about retries?",
        })
        self.assertEqual(status, 202)
        events_dir = Path(self.sess["events_dir"])
        event_files = list(events_dir.glob("*.json"))
        self.assertEqual(len(event_files), 1)
        evt = json.loads(event_files[0].read_text())
        self.assertEqual(evt["block_id"], "b-0")
        self.assertEqual(evt["step_id"], "s1")
        self.assertEqual(evt["text"], "what about retries?")

    def test_submit_with_unknown_step_id_returns_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-bad-sid", "T", self._seq_blocks())
        status, body = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "step_id": "s99", "type": "comment", "text": "x",
        })
        self.assertEqual(status, 422)
        self.assertIn("step", body.lower())

    def test_submit_step_id_against_markdown_block_returns_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-md-sid", "T", [
            {"id": "b-0", "markdown": "hello", "version": 1},
        ])
        status, body = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "step_id": "s1", "type": "comment", "text": "x",
        })
        self.assertEqual(status, 422)

    def test_submit_without_step_id_succeeds_for_sequence_block(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-whole-seq", "T", self._seq_blocks())
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "comment", "text": "rethink the actors",
        })
        self.assertEqual(status, 202)
        events_dir = Path(self.sess["events_dir"])
        event_files = list(events_dir.glob("*.json"))
        self.assertEqual(len(event_files), 1)
        evt = json.loads(event_files[0].read_text())
        self.assertIsNone(evt["step_id"])

    def _choice_blocks(self, multi=False):
        spec = {
            "question": "Pick one",
            "multiSelect": multi,
            "options": [
                {"id": "o1", "label": "A"},
                {"id": "o2", "label": "B"},
            ],
        }
        return [{"id": "b-0", "kind": "choice", "spec": spec, "version": 1}]

    def test_submit_choice_single_select_succeeds(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-ch", "T", self._choice_blocks())
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o2"],
        })
        self.assertEqual(status, 202)
        events_dir = Path(self.sess["events_dir"])
        evt = json.loads(list(events_dir.glob("*.json"))[0].read_text())
        self.assertEqual(evt["type"], "choice")
        self.assertEqual(evt["block_id"], "b-0")
        self.assertEqual(evt["selected_options"], ["o2"])

    def test_submit_choice_multi_select_succeeds(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-chm", "T", self._choice_blocks(multi=True))
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o1", "o2"],
        })
        self.assertEqual(status, 202)
        events_dir = Path(self.sess["events_dir"])
        evt = json.loads(list(events_dir.glob("*.json"))[0].read_text())
        self.assertEqual(evt["selected_options"], ["o1", "o2"])

    def test_submit_choice_unknown_option_returns_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-chu", "T", self._choice_blocks())
        status, body = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o9"],
        })
        self.assertEqual(status, 422)
        self.assertIn("option", body.lower())

    def test_submit_choice_empty_selection_returns_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-che", "T", self._choice_blocks())
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": [],
        })
        self.assertEqual(status, 422)

    def test_submit_choice_single_select_two_picks_returns_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-ch2", "T", self._choice_blocks(multi=False))
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o1", "o2"],
        })
        self.assertEqual(status, 422)

    def test_submit_choice_against_markdown_block_returns_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-chmd", "T", [
            {"id": "b-0", "markdown": "hello", "version": 1},
        ])
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o1"],
        })
        self.assertEqual(status, 422)

    def test_submit_choice_returns_409_when_terminal(self):
        (Path(self.sess["state_dir"]) / "finished").write_text("")
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o1"],
        })
        self.assertEqual(status, 409)

    def test_raw_passes_spec_through_for_choice_block(self):
        response_dir = Path(self.sess["response_dir"])
        blocks = self._choice_blocks()
        _write_blocks(response_dir, "resp-chraw", "T", blocks)
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data = json.loads(body)
        blk = next(b for b in data["blocks"] if b["id"] == "b-0")
        self.assertEqual(blk["kind"], "choice")
        self.assertEqual(blk["spec"], blocks[0]["spec"])
        self.assertNotIn("svg", blk)


    def test_poll_reports_consumed_events(self):
        """/poll surfaces event ids that have a *.ack in consumed_dir so the
        client can clear a comment's spinner the moment Claude acks it."""
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-ce", "T", [{"id": "b-0", "markdown": "x"}])
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["consumed_events"], [])
        # Write an ack as the watcher/Claude would.
        (Path(self.sess["consumed_dir"]) / "12345.ack").write_text("")
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(json.loads(body)["consumed_events"], ["12345"])

    def test_submit_rejects_non_utf8_body(self):
        """A non-UTF-8 body must return 400, not kill the worker thread."""
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=b"\xff\xfe\xff",
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 400)
        conn.close()
        # Server is still alive and serving (thread did not die).
        status, _ = _http_get("localhost", self.info["port"], "/health")
        self.assertEqual(status, 200)

    def test_submit_rejects_negative_content_length(self):
        """A negative Content-Length would make rfile.read(-n) read to EOF;
        the server must reject it with 400 and stay alive."""
        import socket as _socket
        raw = (
            f"POST {self.base}/api/submit HTTP/1.1\r\n"
            f"Host: localhost:{self.info['port']}\r\n"
            f"Content-Type: application/json\r\n"
            f"Content-Length: -5\r\n"
            f"Connection: close\r\n\r\n"
        ).encode()
        sock = _socket.create_connection(("localhost", self.info["port"]), timeout=2)
        try:
            sock.sendall(raw)
            resp = b""
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                resp += chunk
        finally:
            sock.close()
        status = int(resp.split(b"\r\n", 1)[0].split()[1])
        self.assertEqual(status, 400)
        status, _ = _http_get("localhost", self.info["port"], "/health")
        self.assertEqual(status, 200)

    def test_raw_renders_error_pill_for_labelless_actor(self):
        """A sequence spec with an actor missing `label` used to KeyError out
        of render() and crash the whole /raw response. Now it degrades to the
        compact error pill, page intact."""
        response_dir = Path(self.sess["response_dir"])
        bad_spec = {
            "actors": [{"id": "a"}, {"id": "b", "label": "B"}],  # 'a' has no label
            "steps": [{"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "x"}],
        }
        _write_blocks(response_dir, "resp-nolabel", "T", [
            {"id": "b-0", "kind": "sequence", "spec": bad_spec},
        ])
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data = json.loads(body)
        svg = data["blocks"][0]["svg"]
        self.assertIn("annotate-seq-error", svg)
        self.assertIn("label required", svg)

    def test_script_js_has_reconcile_and_event_ack(self):
        """Guard the client fix: structural reconcile + consumed-event overlay
        clearing must be present in the served script."""
        status, body = _http_get("localhost", self.info["port"], "/static/script.js")
        self.assertEqual(status, 200)
        for token in ("function reconcile", "handleConsumedEvents",
                      "consumed_events", "pendingEvents", "CSS.escape"):
            self.assertIn(token, body, f"client fix token {token!r} missing")

    def test_submit_dismiss_writes_event(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-dis", "T", [
            {"id": "section-1", "markdown": "irrelevant section"},
        ])
        payload = {"block_id": "section-1", "type": "dismiss", "text": "ignored"}
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 202)
        conn.close()
        events_dir = Path(self.sess["events_dir"])
        evt = json.loads(list(events_dir.glob("*.json"))[0].read_text())
        self.assertEqual(evt["type"], "dismiss")
        self.assertEqual(evt["block_id"], "section-1")

    def test_submit_dismiss_requires_block_id(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-dis2", "T", [])
        payload = {"type": "dismiss", "text": ""}
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 422)
        conn.close()

    def test_submit_dismiss_unknown_block_is_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-dis3", "T", [
            {"id": "section-1", "markdown": "x"},
        ])
        payload = {"block_id": "section-9", "type": "dismiss"}
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 422)
        conn.close()

    def test_poll_busy_tracks_unacked_events(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-busy", "T", [
            {"id": "section-1", "markdown": "hi"},
        ])

        # No events yet -> not busy.
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(status, 200)
        self.assertFalse(json.loads(body)["busy"])

        # Submit a dismiss -> an unacked event exists -> busy.
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit",
                     body=json.dumps({"block_id": "section-1", "type": "dismiss"}),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 202)
        event_id = json.loads(resp.read().decode("utf-8"))["event_id"]
        conn.close()

        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertTrue(json.loads(body)["busy"])

        # Ack it (simulate Claude) -> not busy. serve_poll reads acks from
        # state_dir/"consumed", so write the marker there.
        consumed = Path(self.sess["state_dir"]) / "consumed"
        consumed.mkdir(parents=True, exist_ok=True)
        (consumed / f"{event_id}.ack").write_text("")

        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertFalse(json.loads(body)["busy"])


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


class UploadTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.home = self.tmp / "home"
        self.home.mkdir()
        self.cwd = self.tmp / "proj"
        self.cwd.mkdir()
        self.proc, info = _start_server(self.home)
        self.port = info["port"]
        self.sess = _create_session(self.port, self.cwd)

    def tearDown(self):
        self.proc.terminate()
        try:
            self.proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _upload(self, body: bytes, content_type: str):
        conn = http.client.HTTPConnection("localhost", self.port, timeout=2)
        conn.request(
            "POST", f"/s/{self.sess['sid']}/api/upload",
            body=body,
            headers={"Content-Type": content_type, "Content-Length": str(len(body))},
        )
        resp = conn.getresponse()
        status = resp.status
        data = resp.read().decode("utf-8")
        conn.close()
        return status, data

    def test_upload_writes_image_and_returns_path(self):
        png = bytes.fromhex(
            "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
            "0000000d49444154789c63000100000005000101a5f645400000000049454e44ae426082"
        )
        status, body = self._upload(png, "image/png")
        self.assertEqual(status, 200, body)
        payload = json.loads(body)
        self.assertIn("path", payload)
        self.assertEqual(payload["size"], len(png))
        on_disk = Path(payload["path"])
        self.assertTrue(on_disk.is_file())
        self.assertEqual(on_disk.read_bytes(), png)
        self.assertEqual(on_disk.suffix, ".png")
        expected_parent = Path(self.sess["state_dir"]) / "images"
        self.assertEqual(on_disk.parent, expected_parent)

    def test_upload_rejects_unsupported_media_type(self):
        status, _ = self._upload(b"not really a pdf", "application/pdf")
        self.assertEqual(status, 415)

    def test_upload_rejects_oversized_payload(self):
        # Declare Content-Length above the limit but don't send the body —
        # the server checks the header value before reading, so it returns 413
        # immediately. Use a raw socket so we can read the response without
        # the http.client machinery blocking on sending the (unsent) body.
        import socket as _socket
        oversized = 10 * 1024 * 1024 + 1
        raw = (
            f"POST /s/{self.sess['sid']}/api/upload HTTP/1.1\r\n"
            f"Host: localhost:{self.port}\r\n"
            f"Content-Type: image/png\r\n"
            f"Content-Length: {oversized}\r\n"
            f"Connection: close\r\n"
            f"\r\n"
        ).encode()
        sock = _socket.create_connection(("localhost", self.port), timeout=2)
        try:
            sock.sendall(raw)
            resp_bytes = b""
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                resp_bytes += chunk
        finally:
            sock.close()
        status_line = resp_bytes.split(b"\r\n", 1)[0].decode()
        status = int(status_line.split()[1])
        self.assertEqual(status, 413)

    def test_upload_rejects_missing_content_length(self):
        conn = http.client.HTTPConnection("localhost", self.port, timeout=2)
        conn.putrequest("POST", f"/s/{self.sess['sid']}/api/upload")
        conn.putheader("Content-Type", "image/png")
        conn.endheaders()
        resp = conn.getresponse()
        self.assertEqual(resp.status, 411)
        conn.close()

    def test_upload_rejects_after_session_terminal(self):
        # Write the finished marker directly (no more whole-doc submit).
        (Path(self.sess["state_dir"]) / "finished").write_text("")
        png = bytes.fromhex(
            "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
            "0000000d49444154789c63000100000005000101a5f645400000000049454e44ae426082"
        )
        status, _ = self._upload(png, "image/png")
        self.assertEqual(status, 409)


if __name__ == "__main__":
    unittest.main()
