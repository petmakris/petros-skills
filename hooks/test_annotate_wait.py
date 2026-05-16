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

    SESSION_ID = "claude-sess-aaaa"

    def _make_session(
        self,
        response_id: str = "resp-42",
        meta_age_s: float = 0.0,
        *,
        claude_session_id: str | None = "claude-sess-aaaa",
        dir_name: str = "1700000000",
    ) -> dict:
        session = self.cwd / ".claude" / "annotate" / dir_name
        (session / "response").mkdir(parents=True)
        (session / "annotations").mkdir(parents=True)
        (session / "state").mkdir(parents=True)
        meta = session / "response" / "meta.json"
        meta_payload = {"response_id": response_id, "title": "t"}
        if claude_session_id is not None:
            meta_payload["claude_session_id"] = claude_session_id
        meta.write_text(json.dumps(meta_payload))
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
        event = {"session_id": self.SESSION_ID, **event}
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

    def test_format_one_uses_block_snippet_when_present(self):
        # Snippet present → human label shows up as the target, b-id moves to
        # a trailing parenthetical so it's still parsable but no longer the
        # primary identifier the user sees.
        out_block_scope = hook._format_one({
            "block_id": "b-12", "type": "comment",
            "selected_text": "", "comment": "approve",
            "block_snippet": "Unified skill scaffolding command",
        })
        self.assertIn("Unified skill scaffolding command", out_block_scope)
        self.assertIn("(b-12)", out_block_scope)
        self.assertNotIn("·b-12", out_block_scope)
        self.assertNotIn("(whole block)", out_block_scope)

        out_span_scope = hook._format_one({
            "block_id": "b-7", "type": "reject",
            "selected_text": "burn the build",
            "comment": "no",
            "block_snippet": "Hook installer audit",
        })
        self.assertIn('"burn the build"', out_span_scope)
        self.assertIn("Hook installer audit", out_span_scope)
        self.assertIn("(b-7)", out_span_scope)

    def test_format_one_falls_back_to_block_id_without_snippet(self):
        # Old payloads with no snippet still display the bare `·b-N` form so
        # the hook degrades gracefully if the client doesn't send block_snippet.
        out = hook._format_one({
            "block_id": "b-3", "type": "comment",
            "selected_text": "", "comment": "x",
        })
        self.assertIn("·b-3", out)
        self.assertIn("(whole block)", out)

    def test_format_context_empty_annotations_is_terse(self):
        out = hook._format_context({"response_id": "resp-empty", "annotations": []})
        self.assertIn("resp-empty", out)
        self.assertIn("no annotations", out)

    def test_format_context_with_annotations_includes_routing_reminder(self):
        # The hook is the only thing guaranteed to be in context on the response-
        # to-annotations turn, so the routing rule for follow-ups must travel with
        # the annotations payload. Without this, Claude defaults to terminal and
        # the annotation loop breaks after the first iteration.
        payload = {
            "response_id": "resp-route",
            "annotations": [
                {"block_id": "b-1", "type": "question",
                 "selected_text": "foo", "comment": "explain"},
                {"block_id": "b-2", "type": "comment",
                 "selected_text": "bar", "comment": "also explain"},
            ],
        }
        out = hook._format_context(payload)
        # Routing block is present and marked.
        self.assertIn("continuation", out.lower())
        # Both branches of the rule are spelled out.
        self.assertIn("long-form", out.lower())
        self.assertIn("terminal", out.lower())
        # Names the skill so Claude knows what to re-invoke.
        self.assertIn("annotate", out.lower())
        # Routing block sits after the annotations list (decision needs the
        # annotation context to land first).
        self.assertGreater(out.lower().index("continuation"),
                           out.index("explain"))

    def test_format_context_empty_annotations_includes_routing_reminder(self):
        # Zero-annotation approval still needs the routing nudge — the user may
        # have approved a plan and Claude's next move could itself be long-form.
        out = hook._format_context({"response_id": "resp-empty", "annotations": []})
        self.assertIn("long-form", out.lower())
        self.assertIn("annotate", out.lower())

    def test_timeout_exits_silently(self):
        self._make_session(response_id="resp-x")
        # Force the loop's deadline to be in the past via the `now` kwarg so the
        # first iteration's time-check trips immediately.
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = hook.run(
                {"hook_event_name": "Stop", "cwd": str(self.cwd),
                 "session_id": self.SESSION_ID},
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


    def test_other_session_dir_is_ignored(self):
        # Bug repro: two Claude Code instances in the same cwd. Session B's hook
        # must not pick up the annotate dir created by session A — otherwise
        # B receives A's annotations when the user clicks Submit.
        s = self._make_session(
            response_id="resp-from-A",
            claude_session_id="claude-sess-OTHER",
            dir_name="1700000000",
        )
        ticks = {"n": 0}

        def fake_sleep(_):
            # If the hook entered the wait loop, it'd block forever. Simulate
            # an A-side submit landing — the B-side hook must STILL ignore it
            # because the dir isn't tagged with B's session_id.
            ticks["n"] += 1
            if ticks["n"] == 2:
                s["annotations"].write_text(json.dumps({
                    "response_id": "resp-from-A", "annotations": [
                        {"block_id": "b-0", "comment": "for session A only"},
                    ],
                }))
            if ticks["n"] > 10:
                raise AssertionError("hook entered the wait loop for another session's dir")

        rc, out = self._run(
            {"hook_event_name": "Stop", "cwd": str(self.cwd)},
            sleep=fake_sleep,
        )
        self.assertEqual(rc, 0)
        self.assertEqual(out, "")  # nothing injected

    def test_untagged_dir_is_ignored(self):
        # Legacy / older-format dir with no claude_session_id must not match
        # by accident — the hook treats missing tag as "skip", not "wildcard".
        self._make_session(claude_session_id=None)
        rc, out = self._run({"hook_event_name": "Stop", "cwd": str(self.cwd)})
        self.assertEqual(rc, 0)
        self.assertEqual(out, "")

    def test_missing_session_id_in_event_is_noop(self):
        # If the Stop event somehow lacks session_id, the hook must bail rather
        # than fall back to "latest dir wins" (the old leaky behavior).
        self._make_session()
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = hook.run(
                {"hook_event_name": "Stop", "cwd": str(self.cwd)},
                sleep=lambda _: None,
            )
        self.assertEqual(rc, 0)
        self.assertEqual(buf.getvalue(), "")

    def test_picks_latest_among_own_session_dirs(self):
        # Same Claude session pushed twice (loop continuation). Hook must
        # wait on the newer dir.
        import os
        old = self._make_session(response_id="resp-old", dir_name="aaa-old")
        os.utime(old["session"], (time.time() - 60, time.time() - 60))
        new = self._make_session(response_id="resp-new", dir_name="bbb-new")

        ticks = {"n": 0}

        def fake_sleep(_):
            ticks["n"] += 1
            if ticks["n"] == 2:
                new["annotations"].write_text(json.dumps({
                    "response_id": "resp-new", "annotations": [],
                }))

        rc, out = self._run(
            {"hook_event_name": "Stop", "cwd": str(self.cwd)},
            sleep=fake_sleep,
        )
        self.assertEqual(rc, 0)
        result = json.loads(out)
        self.assertIn("resp-new", result["reason"])


if __name__ == "__main__":
    unittest.main()
