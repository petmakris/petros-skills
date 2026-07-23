import json
import time
from io import BytesIO
from unittest.mock import MagicMock

from skills.walkthrough.server import Handlers
from skills.walkthrough import steps as steps_module
from skills.interactive_review import threads as threads_module


def make_handler(body=b""):
    h = MagicMock()
    h.rfile = BytesIO(body)
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
        "_cwd": str(tmp_path),
    }


def doc():
    return {
        "question": "how sharing is gated",
        "kind": "explain",
        "generated_ts": 1784720471,
        "steps": [
            {"id": 1, "title": "Entry point", "file": "src/Api.java", "line": 42,
             "snippet": "return service.share(id);", "role": "context",
             "markdown": "REST entry point."},
        ],
    }


def test_serve_root_points_to_ide(tmp_path):
    h, handler = Handlers(), make_handler()
    h.serve_root(handler, make_dirs(tmp_path))
    handler.send_response.assert_called_once_with(200)
    assert b"IntelliJ" in handler.wfile.getvalue()


def test_serve_root_closed_when_finished(tmp_path):
    dirs = make_dirs(tmp_path)
    (dirs["state_dir"] / "finished").write_text("")
    h, handler = Handlers(), make_handler()
    h.serve_root(handler, dirs)
    assert b"closed" in handler.wfile.getvalue()


def test_steps_json_empty_before_generation(tmp_path):
    h, handler = Handlers(), make_handler()
    h.serve_data(handler, make_dirs(tmp_path), "steps.json")
    handler.send_response.assert_called_with(200)
    body = json.loads(handler.wfile.getvalue())
    assert body == {"question": "", "kind": "", "generated_ts": 0, "steps": []}


def test_steps_json_after_generation(tmp_path):
    dirs = make_dirs(tmp_path)
    steps_module.write_steps(dirs["state_dir"], doc())
    h, handler = Handlers(), make_handler()
    h.serve_data(handler, dirs, "steps.json")
    body = json.loads(handler.wfile.getvalue())
    assert body["steps"][0]["title"] == "Entry point"
    assert body["generated_ts"] == 1784720471


def test_thread_endpoint_rejects_non_step_anchor(tmp_path):
    h, handler = Handlers(), make_handler()
    h.serve_data(handler, make_dirs(tmp_path), "thread?anchor=src%2Fx.java%3AR%3A12")
    handler.send_response.assert_called_with(400)


def test_thread_endpoint_returns_empty_thread(tmp_path):
    h, handler = Handlers(), make_handler()
    h.serve_data(handler, make_dirs(tmp_path), "thread?anchor=step%3A3")
    body = json.loads(handler.wfile.getvalue())
    assert body["anchor"] == "step:3"
    assert body["messages"] == []


def test_threads_bulk_skips_threads_without_claude_reply(tmp_path):
    dirs = make_dirs(tmp_path)
    threads_dir = dirs["state_dir"] / "threads"
    threads_module.append_message(threads_dir, "step:1", {
        "role": "user", "ts": 1, "text": "why?", "source_event_id": "user-1"})
    h = Handlers()
    assert h.threads_bulk(dirs) == {}
    threads_module.append_message(threads_dir, "step:1", {
        "role": "claude", "ts": 2, "text": "because X", "source_event_id": "1"},
        title="Ordering")
    bulk = h.threads_bulk(dirs)
    assert bulk["step:1"]["latest_synthesis"] == "because X"
    assert bulk["step:1"]["title"] == "Ordering"
    assert bulk["step:1"]["question"] == "why?"


def test_serve_poll_reports_steps_ts_and_liveness(tmp_path):
    dirs = make_dirs(tmp_path)
    steps_module.write_steps(dirs["state_dir"], doc())
    (dirs["state_dir"] / "watcher_heartbeat").write_text(str(int(time.time())))
    h, handler = Handlers(), make_handler()
    h.serve_poll(handler, dirs)
    body = json.loads(handler.wfile.getvalue())
    assert body["steps_generated_at"] == 1784720471
    assert body["ended"] is False
    assert body["ended_reason"] is None


def test_serve_poll_reports_cancelled(tmp_path):
    dirs = make_dirs(tmp_path)
    (dirs["state_dir"] / "cancelled").write_text("{}")
    h, handler = Handlers(), make_handler()
    h.serve_poll(handler, dirs)
    body = json.loads(handler.wfile.getvalue())
    assert body["ended"] is True
    assert body["ended_reason"] == "cancelled"


def test_create_session_extra_requires_question(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    try:
        h.create_session_extra({}, dirs)
    except ValueError as e:
        assert "question" in str(e)
    else:
        raise AssertionError("expected ValueError")


def test_create_session_extra_seeds_threads_dir_and_meta(tmp_path):
    base = tmp_path / "fresh"
    state_dir = base / "state"
    state_dir.mkdir(parents=True)
    dirs = {"state_dir": state_dir, "_cwd": str(tmp_path)}
    h = Handlers()
    out = h.create_session_extra({"question": "how does sharing work", "kind": "explain"}, dirs)
    assert (state_dir / "threads").is_dir()
    assert out["title"] == "how does sharing work"
    meta = json.loads((state_dir / "meta.json").read_text())
    assert meta["question"] == "how does sharing work"
    assert meta["kind"] == "explain"
    # Finding 4: the shared framework's session-discovery row builds "title"
    # from meta.json (see web_companion/server.py's meta.get("title", "")) —
    # without this key every walkthrough row came back with title="".
    assert meta["title"] == "how does sharing work"


def test_create_session_extra_defaults_kind_to_explain(tmp_path):
    state_dir = tmp_path / "s2"
    state_dir.mkdir()
    h = Handlers()
    h.create_session_extra({"question": "q"}, {"state_dir": state_dir, "_cwd": str(tmp_path)})
    assert json.loads((state_dir / "meta.json").read_text())["kind"] == "explain"


def test_comment_count_counts_threads(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    assert h.comment_count(dirs) == 0
    threads_module.append_message(dirs["state_dir"] / "threads", "step:1", {
        "role": "user", "ts": 1, "text": "q", "source_event_id": "user-1"})
    assert h.comment_count(dirs) == 1


def test_submit_rejects_non_step_anchor(tmp_path):
    h, handler = Handlers(), make_handler()
    h.handle_submit(handler, make_dirs(tmp_path), {"anchor": "src/x.java:R:1", "text": "hi"})
    handler.send_response.assert_called_with(400)


def test_submit_rejects_bad_type(tmp_path):
    h, handler = Handlers(), make_handler()
    h.handle_submit(handler, make_dirs(tmp_path), {"anchor": "step:1", "type": "shout", "text": "hi"})
    handler.send_response.assert_called_with(400)


def test_submit_rejects_when_session_closed(tmp_path):
    dirs = make_dirs(tmp_path)
    (dirs["state_dir"] / "cancelled").write_text("{}")
    h, handler = Handlers(), make_handler()
    h.handle_submit(handler, dirs, {"anchor": "step:1", "text": "hi"})
    handler.send_response.assert_called_with(409)


def test_submit_queues_event_and_appends_user_message(tmp_path):
    dirs = make_dirs(tmp_path)
    h, handler = Handlers(), make_handler()
    h.handle_submit(handler, dirs, {"anchor": "step:2", "text": "is ordering guaranteed?"})
    handler.send_response.assert_called_with(202)
    body = json.loads(handler.wfile.getvalue())
    assert body["status"] == "queued"
    events = list((dirs["events_dir"]).iterdir())
    assert len(events) == 1
    evt = json.loads(events[0].read_text())
    assert evt["anchor"] == "step:2"
    assert evt["text"] == "is ordering guaranteed?"
    thread = threads_module.load(dirs["state_dir"] / "threads", "step:2")
    assert thread["messages"][0]["role"] == "user"
    assert thread["messages"][0]["source_event_id"] == f"user-{body['event_id']}"
