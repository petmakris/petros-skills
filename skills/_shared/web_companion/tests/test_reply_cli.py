import json
from pathlib import Path

from skills._shared.web_companion.reply_cli import main
from skills._shared.web_companion import threads as threads_module


def _setup(tmp_path):
    state = tmp_path / "state"
    (state / "consumed").mkdir(parents=True)
    (state / ".reply.md").write_text("the answer")
    (state / ".reply.meta.json").write_text(json.dumps({
        "anchor": "src/x.py:R:42", "title": "Why", "source_event_id": "evt-1"}))
    return state


def test_reply_and_ack(tmp_path):
    state = _setup(tmp_path)
    rc = main(["--state-dir", str(state), "--ack", "evt-1"])
    assert rc == 0
    t = threads_module.load(state / "threads", "src/x.py:R:42")
    assert t["messages"][-1]["text"] == "the answer"
    assert (state / "consumed" / "evt-1.ack").exists()


def test_reply_dedups_by_source_event_id(tmp_path):
    state = _setup(tmp_path)
    main(["--state-dir", str(state), "--ack", "evt-1"])
    main(["--state-dir", str(state), "--ack", "evt-1"])
    t = threads_module.load(state / "threads", "src/x.py:R:42")
    assert len([m for m in t["messages"] if m["role"] == "claude"]) == 1


def test_ack_only_writes_no_thread(tmp_path):
    state = _setup(tmp_path)
    rc = main(["--state-dir", str(state), "--ack", "evt-9", "--ack-only"])
    assert rc == 0
    assert (state / "consumed" / "evt-9.ack").exists()
    assert not (state / "threads").exists()


def test_missing_inputs_fail_without_ack(tmp_path):
    state = tmp_path / "state"
    state.mkdir()
    rc = main(["--state-dir", str(state), "--ack", "evt-1"])
    assert rc == 2
    assert not (state / "consumed" / "evt-1.ack").exists()
