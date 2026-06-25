import json
import threading
import time
from io import BytesIO
from unittest.mock import patch, MagicMock

import pytest

from skills.interactive_review.server import Handlers
from skills.interactive_review import threads as threads_module


def make_handler(body=b""):
    h = MagicMock()
    h.rfile = BytesIO(body)
    # wfile captures only the body bytes written (since headers go through
    # send_response/send_header which are mocked away).
    h.wfile = BytesIO()
    h.headers = {}
    return h


def make_dirs(tmp_path):
    state_dir = tmp_path / "state"
    events_dir = state_dir / "events"
    consumed_dir = state_dir / "consumed"
    threads_dir = state_dir / "threads"
    response_dir = tmp_path / "response"
    for d in (state_dir, events_dir, consumed_dir, threads_dir, response_dir):
        d.mkdir(parents=True, exist_ok=True)
    return {
        "state_dir": state_dir,
        "events_dir": events_dir,
        "consumed_dir": consumed_dir,
        "response_dir": response_dir,
        "annotations_dir": response_dir,
    }


def test_serve_root_points_to_ide(tmp_path):
    """Root serves a headless 'use your IDE' page — no diff UI."""
    h = Handlers()
    handler = make_handler()
    h.serve_root(handler, make_dirs(tmp_path))
    handler.send_response.assert_called_once_with(200)
    written = handler.wfile.getvalue()
    assert b"IntelliJ" in written
    assert b"review.js" not in written


def test_serve_root_closed_when_finished(tmp_path):
    dirs = make_dirs(tmp_path)
    (dirs["state_dir"] / "finished").write_text("")
    h = Handlers()
    handler = make_handler()
    h.serve_root(handler, dirs)
    handler.send_response.assert_called_with(200)
    assert b"closed" in handler.wfile.getvalue()


def test_serve_thread_returns_empty_for_missing(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    h.serve_data(handler, dirs, "thread?anchor=src%2Fx.py%3AR%3A42")
    handler.send_response.assert_called_with(200)
    body = json.loads(handler.wfile.getvalue())
    assert body["anchor"] == "src/x.py:R:42"
    assert body["version"] == 0
    assert body["messages"] == []


def test_serve_thread_400_on_bad_anchor(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    h.serve_data(handler, dirs, "thread?anchor=garbage")
    handler.send_response.assert_called_with(400)


def test_handle_submit_queues_event_and_appends_user(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    h.handle_submit(handler, dirs, {"anchor": "src/x.py:R:42", "type": "comment", "text": "why?"})
    handler.send_response.assert_called_with(202)
    events = list((dirs["state_dir"] / "events").iterdir())
    assert len(events) == 1
    threads = list((dirs["state_dir"] / "threads").iterdir())
    assert len(threads) == 1
    t = json.loads(threads[0].read_text())
    assert t["messages"][0]["role"] == "user"
    assert t["messages"][0]["text"] == "why?"


def test_handle_submit_rejects_bad_anchor(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    h.handle_submit(handler, dirs, {"anchor": "garbage", "type": "comment", "text": "x"})
    handler.send_response.assert_called_with(400)


def test_handle_submit_rejects_bad_type(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    h.handle_submit(handler, dirs, {"anchor": "src/x.py:R:42", "type": "huh", "text": "x"})
    handler.send_response.assert_called_with(400)


def test_handle_submit_409_when_finished(tmp_path):
    dirs = make_dirs(tmp_path)
    (dirs["state_dir"] / "finished").write_text("")
    h = Handlers()
    handler = make_handler()
    h.handle_submit(handler, dirs, {"anchor": "src/x.py:R:42", "type": "comment", "text": "x"})
    handler.send_response.assert_called_with(409)


def test_serve_poll_returns_thread_versions(tmp_path):
    dirs = make_dirs(tmp_path)
    threads_module.append_message(
        dirs["state_dir"] / "threads", "src/x.py:R:42",
        {"role": "user", "ts": 1, "text": "x", "source_event_id": "e1"},
    )
    h = Handlers()
    handler = make_handler()
    h.serve_poll(handler, dirs)
    handler.send_response.assert_called_with(200)
    body = json.loads(handler.wfile.getvalue())
    assert body["threads"] == {"src/x.py:R:42": 1}
    assert body["finished"] is False


def _poll_body(tmp_path, dirs):
    h = Handlers()
    handler = make_handler()
    h.serve_poll(handler, dirs)
    return json.loads(handler.wfile.getvalue())


def test_serve_poll_not_ended_when_fresh_heartbeat(tmp_path):
    dirs = make_dirs(tmp_path)
    now = int(time.time())
    (dirs["state_dir"] / "watcher_heartbeat").write_text(str(now))
    body = _poll_body(tmp_path, dirs)
    assert body["watcher_seen_at"] == now
    assert body["ended"] is False
    assert body["ended_reason"] is None


def test_serve_poll_not_ended_when_no_heartbeat(tmp_path):
    # No heartbeat yet => age unknown => not ended (freshly armed session).
    dirs = make_dirs(tmp_path)
    body = _poll_body(tmp_path, dirs)
    assert body["ended"] is False
    assert body["ended_reason"] is None


def test_serve_poll_ended_dead_when_heartbeat_stale(tmp_path):
    from skills._shared.web_companion import server as wc_server
    dirs = make_dirs(tmp_path)
    stale = int(time.time()) - (wc_server.REAP_AFTER + 60)
    (dirs["state_dir"] / "watcher_heartbeat").write_text(str(stale))
    body = _poll_body(tmp_path, dirs)
    assert body["ended"] is True
    assert body["ended_reason"] == "dead"


def test_serve_poll_ended_cancelled_takes_precedence(tmp_path):
    dirs = make_dirs(tmp_path)
    (dirs["state_dir"] / "watcher_heartbeat").write_text(str(int(time.time())))
    (dirs["state_dir"] / "cancelled").write_text("{}")
    body = _poll_body(tmp_path, dirs)
    assert body["ended"] is True
    assert body["ended_reason"] == "cancelled"


def test_serve_poll_ended_finished(tmp_path):
    dirs = make_dirs(tmp_path)
    (dirs["state_dir"] / "finished").write_text("")
    body = _poll_body(tmp_path, dirs)
    assert body["ended"] is True
    assert body["ended_reason"] == "finished"


def test_create_session_extra_fetches_diff(tmp_path):
    dirs = make_dirs(tmp_path)
    sample_diff = (
        "diff --git a/x b/x\n"
        "--- a/x\n"
        "+++ b/x\n"
        "@@ -1 +1,2 @@\n"
        " a\n"
        "+b\n"
    )
    sample_meta = {
        "title": "Test PR", "headRefName": "feat", "baseRefName": "main",
        "author": {"login": "petros"}, "url": "https://example", "headRefOid": "deadbeef",
    }
    with patch("skills.interactive_review.diff.fetch_pr_diff",
               return_value=(sample_diff, sample_meta)):
        h = Handlers()
        extra = h.create_session_extra({"pr": "42"}, dirs)
    assert extra == {"pr_ref": "42", "title": "Test PR"}
    assert (dirs["state_dir"] / "diff.patch").exists()
    meta = json.loads((dirs["state_dir"] / "meta.json").read_text())
    assert meta["title"] == "Test PR"
    assert meta["pr_ref"] == "42"


def test_create_session_extra_requires_pr(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    with pytest.raises(ValueError, match="missing 'pr'"):
        h.create_session_extra({}, dirs)


def test_create_session_extra_wraps_gh_failure(tmp_path):
    dirs = make_dirs(tmp_path)
    with patch("skills.interactive_review.diff.fetch_pr_diff",
               side_effect=RuntimeError("not found")):
        h = Handlers()
        with pytest.raises(ValueError, match="gh pr fetch failed"):
            h.create_session_extra({"pr": "99999"}, dirs)


def test_create_session_extra_rejects_oversized_diff(tmp_path):
    dirs = make_dirs(tmp_path)
    huge = "x" * (6 * 1024 * 1024)
    with patch("skills.interactive_review.diff.fetch_pr_diff",
               return_value=(huge, {"title": "Big PR"})):
        h = Handlers()
        with pytest.raises(ValueError, match="over the 5 MB limit"):
            h.create_session_extra({"pr": "42"}, dirs)
    assert not (dirs["state_dir"] / "diff.patch").exists()


def test_create_session_extra_warns_on_large_diff(tmp_path):
    dirs = make_dirs(tmp_path)
    big = "x" * (2 * 1024 * 1024)
    with patch("skills.interactive_review.diff.fetch_pr_diff",
               return_value=(big, {"title": "Largish PR"})):
        h = Handlers()
        extra = h.create_session_extra({"pr": "42"}, dirs)
    assert "warning" in extra
    assert (dirs["state_dir"] / "diff.patch").exists()


def test_threads_bulk_returns_anchor_to_latest_synthesis(tmp_path):
    state_dir = tmp_path / "state"
    threads_dir = state_dir / "threads"
    threads_dir.mkdir(parents=True)

    from skills.interactive_review import threads as th
    th.append_message(threads_dir, "foo.java:R:10",
                      {"role": "user", "ts": 1, "text": "q1", "source_event_id": "e1"})
    th.append_message(threads_dir, "foo.java:R:10",
                      {"role": "claude", "ts": 2, "text": "answer one"})
    th.append_message(threads_dir, "foo.java:R:10",
                      {"role": "user", "ts": 3, "text": "q2", "source_event_id": "e2"})
    th.append_message(threads_dir, "foo.java:R:10",
                      {"role": "claude", "ts": 4, "text": "answer two — final synthesis"})
    th.append_message(threads_dir, "bar.java:L:5",
                      {"role": "claude", "ts": 5, "text": "only one here"})

    from skills.interactive_review.server import Handlers
    h = Handlers()
    result = h.threads_bulk({"state_dir": state_dir})
    assert result["foo.java:R:10"]["latest_synthesis"] == "answer two — final synthesis"
    assert result["foo.java:R:10"]["version"] == 4
    assert result["bar.java:L:5"]["latest_synthesis"] == "only one here"


def test_threads_bulk_skips_threads_with_no_claude_messages(tmp_path):
    state_dir = tmp_path / "state"
    threads_dir = state_dir / "threads"
    threads_dir.mkdir(parents=True)
    from skills.interactive_review import threads as th
    th.append_message(threads_dir, "only-user.java:R:1",
                      {"role": "user", "ts": 1, "text": "q", "source_event_id": "e1"})

    from skills.interactive_review.server import Handlers
    result = Handlers().threads_bulk({"state_dir": state_dir})
    assert "only-user.java:R:1" not in result


def test_threads_bulk_empty_when_no_threads_dir(tmp_path):
    from skills.interactive_review.server import Handlers
    result = Handlers().threads_bulk({"state_dir": tmp_path / "missing"})
    assert result == {}


def test_handle_submit_stores_anchor_text(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    h.handle_submit(handler, dirs, {"anchor": "src/x.py:R:42", "type": "comment",
                                    "text": "why?", "anchor_text": "  return foo()"})
    t = json.loads(next((dirs["state_dir"] / "threads").iterdir()).read_text())
    assert t["anchor_text"] == "  return foo()"


def test_threads_bulk_returns_anchor_text(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    h.handle_submit(handler, dirs, {"anchor": "src/x.py:R:42", "type": "comment",
                                    "text": "why?", "anchor_text": "  return foo()"})
    # Give the thread a claude reply so threads_bulk surfaces it.
    from skills.interactive_review import threads as tm
    tm.append_message(dirs["state_dir"] / "threads", "src/x.py:R:42",
                      {"role": "claude", "ts": 2, "text": "because.", "source_event_id": "c1"})
    bulk = h.threads_bulk(dirs)
    assert bulk["src/x.py:R:42"]["anchor_text"] == "  return foo()"


def test_threads_bulk_returns_title_and_question(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    # User asks (creates the thread with a question), then Claude answers with a title.
    h.handle_submit(handler, dirs, {"anchor": "src/x.py:R:42", "type": "comment",
                                    "text": "why is this null-checked?"})
    from skills.interactive_review import threads as tm
    tm.append_message(dirs["state_dir"] / "threads", "src/x.py:R:42",
                      {"role": "claude", "ts": 9, "text": "Because foo() can return null.",
                       "source_event_id": "c1"},
                      title="Null check on foo()")
    bulk = h.threads_bulk(dirs)
    row = bulk["src/x.py:R:42"]
    assert row["title"] == "Null check on foo()"
    assert row["question"] == "why is this null-checked?"


def test_threads_bulk_defaults_title_and_question_empty(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    threads_dir = dirs["state_dir"] / "threads"
    from skills.interactive_review import threads as tm
    # Claude-origin thread: a claude message, no user message, no title.
    tm.append_message(threads_dir, "src/x.py:R:7",
                      {"role": "claude", "ts": 1, "text": "finding", "source_event_id": "c1"})
    row = h.threads_bulk(dirs)["src/x.py:R:7"]
    assert row["title"] == ""
    assert row["question"] == ""


# ---------------------------------------------------------------------------
# SSE stream unit tests
# ---------------------------------------------------------------------------

class _BreakingBytesIO(BytesIO):
    """BytesIO that raises BrokenPipeError after max_writes writes to wfile."""
    def __init__(self, max_writes: int = 10):
        super().__init__()
        self._write_count = 0
        self._max_writes = max_writes

    def write(self, b):
        self._write_count += 1
        if self._write_count > self._max_writes:
            raise BrokenPipeError("simulated disconnect")
        return super().write(b)

    def flush(self):
        pass


def make_stream_handler(wfile=None):
    h = MagicMock()
    h.wfile = wfile or BytesIO()
    return h


def make_mock_registry(sid: str):
    """Return a Registry-like object with a real threading.Event for sid."""
    from skills._shared.web_companion.sessions import Registry
    from pathlib import Path
    reg = Registry(state_root=Path("/tmp/never-written"))
    reg.waiter(sid)  # pre-create the event
    return reg


def test_serve_stream_sends_connected_event(tmp_path):
    """Initial 'connected' event is emitted before any thread data."""
    dirs = make_dirs(tmp_path)
    dirs["_sid"] = "test-sid-1"
    reg = make_mock_registry("test-sid-1")

    wfile = _BreakingBytesIO(max_writes=2)  # connected + first flush, then break
    h_mock = make_stream_handler(wfile)
    handlers = Handlers()
    handlers.set_registry(reg)

    # Run in a thread since _serve_stream loops until disconnected
    t = threading.Thread(target=handlers._serve_stream, args=(h_mock, dirs))
    t.daemon = True
    t.start()
    t.join(timeout=3)

    output = wfile.getvalue()
    assert b"event: connected" in output


def test_serve_stream_emits_initial_thread_changed_events(tmp_path):
    """Existing claude threads are emitted as thread-changed events on connect."""
    dirs = make_dirs(tmp_path)
    dirs["_sid"] = "test-sid-2"
    threads_dir = dirs["state_dir"] / "threads"

    threads_module.append_message(threads_dir, "src/a.py:R:10",
                                  {"role": "user", "ts": 1, "text": "q", "source_event_id": "e1"})
    threads_module.append_message(threads_dir, "src/a.py:R:10",
                                  {"role": "claude", "ts": 2, "text": "initial answer"})

    reg = make_mock_registry("test-sid-2")
    wfile = _BreakingBytesIO(max_writes=5)  # enough for connected + thread-changed + heartbeat flush
    h_mock = make_stream_handler(wfile)
    handlers = Handlers()
    handlers.set_registry(reg)

    t = threading.Thread(target=handlers._serve_stream, args=(h_mock, dirs))
    t.daemon = True
    t.start()
    t.join(timeout=3)

    output = wfile.getvalue().decode("utf-8")
    assert "event: thread-changed" in output
    assert "initial answer" in output
    assert "src/a.py:R:10" in output


def test_serve_stream_emits_thread_changed_on_note_change(tmp_path):
    """After note_change, a new thread-changed event is emitted for updated threads."""
    dirs = make_dirs(tmp_path)
    sid = "test-sid-3"
    dirs["_sid"] = sid
    threads_dir = dirs["state_dir"] / "threads"

    reg = make_mock_registry(sid)

    collected = []

    class CollectingFile:
        def write(self, b):
            collected.append(b)
            # Raise after we've seen at least one thread-changed following the heartbeat gap
            lines = b"".join(collected).split(b"\n")
            thread_changed_count = sum(1 for l in lines if l == b"event: thread-changed")
            if thread_changed_count >= 1 and b"new answer" in b"".join(collected):
                raise BrokenPipeError("done")

        def flush(self):
            pass

    h_mock = make_stream_handler(CollectingFile())
    handlers = Handlers()
    handlers.set_registry(reg)

    def submit_after_delay():
        time.sleep(0.1)
        # Add a claude message so threads_bulk returns something new
        threads_module.append_message(threads_dir, "src/b.py:R:5",
                                      {"role": "user", "ts": 1, "text": "q", "source_event_id": "e1"})
        threads_module.append_message(threads_dir, "src/b.py:R:5",
                                      {"role": "claude", "ts": 2, "text": "new answer"})
        reg.note_change(sid)

    submitter = threading.Thread(target=submit_after_delay)
    submitter.daemon = True
    submitter.start()

    stream_thread = threading.Thread(target=handlers._serve_stream, args=(h_mock, dirs))
    stream_thread.daemon = True
    stream_thread.start()
    stream_thread.join(timeout=5)

    all_output = b"".join(collected).decode("utf-8", errors="replace")
    assert "thread-changed" in all_output
    assert "new answer" in all_output


def test_serve_stream_sends_heartbeat_on_timeout(tmp_path):
    """When no change arrives within timeout, a heartbeat event is emitted."""
    dirs = make_dirs(tmp_path)
    sid = "test-sid-4"
    dirs["_sid"] = sid

    reg = make_mock_registry(sid)

    # We'll use a patched waiter that immediately returns False (timeout) once,
    # then raises BrokenPipeError on the next write to stop the loop.
    ev = MagicMock()
    ev.wait.return_value = False  # always timeout so heartbeat path is exercised

    patched_reg = MagicMock()
    patched_reg.waiter.return_value = ev

    call_count = [0]

    class LimitedFile:
        def write(self, b):
            call_count[0] += 1
            if b"heartbeat" in b and call_count[0] > 3:
                raise BrokenPipeError("done")

        def flush(self):
            pass

    h_mock = make_stream_handler(LimitedFile())
    handlers = Handlers()
    handlers.set_registry(patched_reg)

    # Patch threads_bulk to return empty so we focus only on heartbeat logic
    with patch.object(handlers, "threads_bulk", return_value={}):
        t = threading.Thread(target=handlers._serve_stream, args=(h_mock, dirs))
        t.daemon = True
        t.start()
        t.join(timeout=5)

    assert ev.wait.called
    # heartbeat must have been attempted (call_count > initial connected write)
    assert call_count[0] > 1


def test_sse_recovers_from_missed_wakeup_on_heartbeat(tmp_path):
    """Even if note_change is raced and missed, the heartbeat path re-polls
    threads_bulk and emits any version diffs the SSE consumer hasn't seen yet."""
    dirs = make_dirs(tmp_path)
    sid = "test-sid-5"
    dirs["_sid"] = sid
    threads_dir = dirs["state_dir"] / "threads"

    # Waiter always times out (simulating a missed note_change wake signal).
    ev = MagicMock()
    ev.wait.return_value = False

    patched_reg = MagicMock()
    patched_reg.waiter.return_value = ev

    collected = []

    class CollectingFile:
        def write(self, b):
            collected.append(b)
            # Stop as soon as we've seen the thread-changed event carrying new data
            if b"new synthesis" in b"".join(collected):
                raise BrokenPipeError("done")

        def flush(self):
            pass

    handlers = Handlers()
    handlers.set_registry(patched_reg)

    # threads_bulk call sequence:
    # 1st call (initial snapshot, before loop) — no claude messages yet → empty
    # 2nd call (first heartbeat iteration) — new claude message has appeared → version diff
    call_number = [0]
    original_threads_bulk = handlers.threads_bulk

    def staged_threads_bulk(d):
        call_number[0] += 1
        if call_number[0] <= 1:
            return {}
        # Simulate a claude message that arrived after the initial snapshot
        threads_module.append_message(
            threads_dir, "src/c.py:R:7",
            {"role": "user", "ts": 1, "text": "q", "source_event_id": "e1"},
        )
        threads_module.append_message(
            threads_dir, "src/c.py:R:7",
            {"role": "claude", "ts": 2, "text": "new synthesis"},
        )
        return original_threads_bulk(d)

    h_mock = make_stream_handler(CollectingFile())
    with patch.object(handlers, "threads_bulk", side_effect=staged_threads_bulk):
        t = threading.Thread(target=handlers._serve_stream, args=(h_mock, dirs))
        t.daemon = True
        t.start()
        t.join(timeout=5)

    all_output = b"".join(collected).decode("utf-8", errors="replace")
    assert "thread-changed" in all_output
    assert "new synthesis" in all_output
