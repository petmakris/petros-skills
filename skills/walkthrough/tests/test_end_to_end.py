"""Server-side round trip: create session dirs, write steps, ask, answer, read back."""
import json
import time
from io import BytesIO
from unittest.mock import MagicMock

from skills.walkthrough.server import Handlers
from skills.walkthrough import steps as steps_module
from skills.interactive_review import threads as threads_module


def make_handler():
    h = MagicMock()
    h.rfile = BytesIO(b"")
    h.wfile = BytesIO()
    h.headers = {}
    return h


def test_full_round_trip(tmp_path):
    state_dir = tmp_path / "state"
    for d in (state_dir, state_dir / "events", state_dir / "consumed"):
        d.mkdir(parents=True)
    dirs = {"state_dir": state_dir, "events_dir": state_dir / "events",
            "consumed_dir": state_dir / "consumed", "_cwd": str(tmp_path)}
    h = Handlers()

    # 1. session init records the question and seeds threads/
    out = h.create_session_extra({"question": "how is sharing gated"}, dirs)
    assert out["title"] == "how is sharing gated"

    # 2. Claude writes the steps
    steps_module.write_steps(state_dir, {
        "question": "how is sharing gated",
        "kind": "explain",
        "generated_ts": int(time.time()),
        "steps": [
            {"id": 1, "title": "Entry", "file": "src/Api.java", "line": 42,
             "snippet": "return service.share(id);", "role": "context", "markdown": "Entry."},
            {"id": 2, "title": "Gate", "file": "src/Engine.java", "line": 114,
             "snippet": "var failures = preconditions.evaluate(p);", "role": "seam",
             "markdown": "Runs beans."},
        ],
    })
    handler = make_handler()
    h.serve_data(handler, dirs, "steps.json")
    assert len(json.loads(handler.wfile.getvalue())["steps"]) == 2

    # 3. the IDE asks on step 2
    handler = make_handler()
    h.handle_submit(handler, dirs, {"anchor": "step:2", "text": "is ordering guaranteed?"})
    event_id = json.loads(handler.wfile.getvalue())["event_id"]
    assert len(list((state_dir / "events").iterdir())) == 1

    # 4. Claude answers into that thread only
    threads_module.append_message(state_dir / "threads", "step:2", {
        "role": "claude", "ts": int(time.time()),
        "text": "No — bean order is undefined without @Order.",
        "source_event_id": event_id,
    }, title="Ordering")

    # 5. the IDE reads it back
    handler = make_handler()
    h.serve_data(handler, dirs, "threads.json")
    bulk = json.loads(handler.wfile.getvalue())
    assert set(bulk) == {"step:2"}
    assert bulk["step:2"]["title"] == "Ordering"
    assert "@Order" in bulk["step:2"]["latest_synthesis"]

    # 6. replaying the same event is a no-op (watcher restart safety)
    appended = threads_module.append_message(state_dir / "threads", "step:2", {
        "role": "claude", "ts": int(time.time()), "text": "duplicate",
        "source_event_id": event_id,
    })
    assert appended is False
    thread = threads_module.load(state_dir / "threads", "step:2")
    assert sum(1 for m in thread["messages"] if m["role"] == "claude") == 1


def test_step_one_thread_stays_untouched(tmp_path):
    """Thread isolation: answering step 2 must never write step 1's file."""
    state_dir = tmp_path / "state"
    (state_dir / "threads").mkdir(parents=True)
    threads_module.append_message(state_dir / "threads", "step:2", {
        "role": "claude", "ts": 1, "text": "answer", "source_event_id": "e1"})
    assert threads_module.load(state_dir / "threads", "step:1")["messages"] == []
