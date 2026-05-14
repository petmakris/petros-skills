"""Tests for hooks/annotate-wait.py — the Stop hook that waits for annotate submissions."""

import importlib.util
import io
import json
import sys
import tempfile
import time
import unittest
from contextlib import redirect_stdout
from pathlib import Path


def _load_hook():
    """Import annotate-wait.py despite the hyphen in its name."""
    here = Path(__file__).resolve().parent
    spec = importlib.util.spec_from_file_location("annotate_wait", here / "annotate-wait.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


hook = _load_hook()


class AnnotateWaitTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cwd = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def _make_session(self, response_id: str = "resp-42", meta_age_s: float = 0.0) -> dict:
        session = self.cwd / ".claude" / "annotate" / "1700000000"
        (session / "response").mkdir(parents=True)
        (session / "annotations").mkdir(parents=True)
        (session / "state").mkdir(parents=True)
        meta = session / "response" / "meta.json"
        meta.write_text(json.dumps({"response_id": response_id, "title": "t"}))
        if meta_age_s > 0:
            past = time.time() - meta_age_s
            import os
            os.utime(meta, (past, past))
        return {
            "session": session,
            "annotations": session / "annotations" / "annotations.json",
            "stopped": session / "state" / "server-stopped",
            "meta": meta,
        }

    def _run(self, event: dict, *, sleep=lambda s: None):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = hook.run(event, sleep=sleep)
        return rc, buf.getvalue()

    def test_non_stop_event_is_noop(self):
        rc, out = self._run({"hook_event_name": "PreToolUse", "cwd": str(self.cwd)})
        self.assertEqual(rc, 0)
        self.assertEqual(out, "")

    def test_no_annotate_dir_is_noop(self):
        rc, out = self._run({"hook_event_name": "Stop", "cwd": str(self.cwd)})
        self.assertEqual(rc, 0)
        self.assertEqual(out, "")

    def test_existing_matching_annotations_is_noop(self):
        s = self._make_session(response_id="resp-7")
        s["annotations"].write_text(json.dumps({"response_id": "resp-7", "annotations": []}))
        rc, out = self._run({"hook_event_name": "Stop", "cwd": str(self.cwd)})
        self.assertEqual(rc, 0)
        self.assertEqual(out, "")

    def test_stale_meta_is_noop(self):
        self._make_session(meta_age_s=10 * 60)
        rc, out = self._run({"hook_event_name": "Stop", "cwd": str(self.cwd)})
        self.assertEqual(rc, 0)
        self.assertEqual(out, "")

    def test_server_stopped_marker_exits_silently(self):
        s = self._make_session()
        s["stopped"].write_text("{}")
        rc, out = self._run({"hook_event_name": "Stop", "cwd": str(self.cwd)})
        self.assertEqual(rc, 0)
        self.assertEqual(out, "")

    def test_submission_during_poll_injects_additional_context(self):
        s = self._make_session(response_id="resp-99")
        payload = {
            "response_id": "resp-99",
            "submitted_at": "2026-05-13T10:00:00Z",
            "annotations": [
                {"block_id": "b-1", "selected_text": "foo", "comment": "rename this"}
            ],
        }

        ticks = {"n": 0}

        def fake_sleep(_):
            ticks["n"] += 1
            if ticks["n"] == 2:
                s["annotations"].write_text(json.dumps(payload))

        rc, out = self._run(
            {"hook_event_name": "Stop", "cwd": str(self.cwd)},
            sleep=fake_sleep,
        )

        self.assertEqual(rc, 0)
        result = json.loads(out)
        self.assertEqual(result["decision"], "block")
        # Stop hooks feed text back to Claude via `reason`, NOT via
        # `hookSpecificOutput.additionalContext` (the latter is only valid for
        # UserPromptSubmit / PostToolUse / PostToolBatch).
        self.assertNotIn("hookSpecificOutput", result)
        ctx = result["reason"]
        self.assertIn("resp-99", ctx)
        self.assertIn("rename this", ctx)

    def test_format_context_renders_pretty_summary(self):
        payload = {
            "response_id": "resp-pretty",
            "submitted_at": "2026-05-13T11:11:15Z",
            "annotations": [
                {"block_id": "b-2", "type": "reject",
                 "selected_text": "Resolve marks on v2 pushes",
                 "comment": "I think it's too much"},
                {"block_id": "b-3", "type": "approve", "selected_text": "", "comment": ""},
                {"block_id": "b-4", "type": "rewrite",
                 "selected_text": "old phrase",
                 "replacement": "new phrase",
                 "comment": "sounds better"},
            ],
        }
        out = hook._format_context(payload)
        # Pretty layout, not raw JSON.
        self.assertNotIn("\"selected_text\":", out)
        self.assertIn("resp-pretty", out)
        self.assertIn("3 annotations", out)
        # Type labels + icons.
        self.assertIn("REJECT", out)
        self.assertIn("APPROVE", out)
        self.assertIn("REWRITE", out)
        # Whole-block fallback for the empty-selected approve.
        self.assertIn("(whole block)", out)
        # Rewrite replacement is shown.
        self.assertIn("new phrase", out)
        # Free-text comments are shown.
        self.assertIn("I think it's too much", out)
        self.assertIn("sounds better", out)

    def test_format_context_empty_annotations_is_terse(self):
        out = hook._format_context({"response_id": "resp-empty", "annotations": []})
        self.assertIn("resp-empty", out)
        self.assertIn("no annotations", out)

    def test_timeout_exits_silently(self):
        self._make_session(response_id="resp-x")
        # Force the loop's deadline to be in the past via the `now` kwarg so the
        # first iteration's time-check trips immediately.
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = hook.run(
                {"hook_event_name": "Stop", "cwd": str(self.cwd)},
                now=time.time() - hook.MAX_WAIT_S - 1,
                sleep=lambda _: None,
            )
        self.assertEqual(rc, 0)
        self.assertEqual(buf.getvalue(), "")

    def test_cancelled_marker_during_poll_exits_silently(self):
        s = self._make_session()
        ticks = {"n": 0}

        def fake_sleep(_):
            ticks["n"] += 1
            if ticks["n"] == 2:
                (s["session"] / "state" / "cancelled").write_text("{}")
            if ticks["n"] > 10:
                raise AssertionError("loop did not exit on cancelled marker within 10 iterations")

        rc, out = self._run(
            {"hook_event_name": "Stop", "cwd": str(self.cwd)},
            sleep=fake_sleep,
        )
        self.assertEqual(rc, 0)
        self.assertEqual(out, "")


if __name__ == "__main__":
    unittest.main()
