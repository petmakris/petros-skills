import json
from pathlib import Path

import pytest

from skills.interactive_review.threads import (
    load, save_atomic, append_message, valid_anchor, list_versions, delete,
    set_anchor_text_if_absent
)


def test_load_missing_returns_empty(tmp_path):
    t = load(tmp_path, "src/x.py:R:42")
    assert t == {"anchor": "src/x.py:R:42", "version": 0, "messages": []}


def test_append_user_message_creates_thread(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    append_message(threads_dir, "src/x.py:R:42",
                   {"role": "user", "ts": 100, "text": "why?",
                    "selected_text": "y", "images": [], "source_event_id": "e1"})
    t = load(threads_dir, "src/x.py:R:42")
    assert t["version"] == 1
    assert len(t["messages"]) == 1
    assert t["messages"][0]["role"] == "user"


def test_append_dedups_by_source_event_id(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    msg = {"role": "claude", "ts": 100, "text": "answer", "source_event_id": "e2"}
    append_message(threads_dir, "a:R:1", msg)
    append_message(threads_dir, "a:R:1", msg)  # dup
    t = load(threads_dir, "a:R:1")
    assert len(t["messages"]) == 1
    assert t["version"] == 1


def test_valid_anchor_single_line():
    assert valid_anchor("src/foo.py:R:42") is True
    assert valid_anchor("src/foo.py:L:1") is True


def test_valid_anchor_range():
    assert valid_anchor("src/foo.py:R:42-58") is True


def test_invalid_anchor():
    assert valid_anchor("src/foo.py") is False
    assert valid_anchor("src/foo.py:X:1") is False
    assert valid_anchor("src/foo.py:R:abc") is False
    assert valid_anchor("src/foo.py:R:") is False
    assert valid_anchor("__general__") is True


def test_delete_removes_file(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    append_message(threads_dir, "a:R:1", {"role": "user", "ts": 1, "text": "x", "source_event_id": "e1"})
    assert delete(threads_dir, "a:R:1") is True
    assert load(threads_dir, "a:R:1") == {"anchor": "a:R:1", "version": 0, "messages": []}
    # Idempotent: second delete is a no-op returning False.
    assert delete(threads_dir, "a:R:1") is False


def test_list_versions(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    append_message(threads_dir, "a:R:1", {"role": "user", "ts": 1, "text": "x", "source_event_id": "e1"})
    append_message(threads_dir, "a:R:1", {"role": "claude", "ts": 2, "text": "y", "source_event_id": "e2"})
    append_message(threads_dir, "b:R:1", {"role": "user", "ts": 3, "text": "z", "source_event_id": "e3"})
    vs = list_versions(threads_dir)
    assert vs == {"a:R:1": 2, "b:R:1": 1}


def test_set_anchor_text_first_write_wins(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    append_message(threads_dir, "src/x.py:R:42",
                   {"role": "user", "ts": 1, "text": "why?", "source_event_id": "e1"})
    set_anchor_text_if_absent(threads_dir, "src/x.py:R:42", "    return foo(bar)")
    set_anchor_text_if_absent(threads_dir, "src/x.py:R:42", "DIFFERENT LINE")
    t = load(threads_dir, "src/x.py:R:42")
    assert t["anchor_text"] == "    return foo(bar)"


def test_append_message_sets_title_last_write_wins(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    append_message(threads_dir, "src/x.py:R:42",
                   {"role": "claude", "ts": 1, "text": "a", "source_event_id": "c1"},
                   title="First headline")
    append_message(threads_dir, "src/x.py:R:42",
                   {"role": "claude", "ts": 2, "text": "b", "source_event_id": "c2"},
                   title="Second headline")
    assert load(threads_dir, "src/x.py:R:42")["title"] == "Second headline"


def test_distinct_anchors_do_not_collide(tmp_path):
    # `a/b` (file b in dir a) and `a__b` (a file literally named a__b) used to
    # encode to the same thread file; they must be independent threads now.
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    append_message(threads_dir, "a/b:R:1",
                   {"role": "user", "ts": 1, "text": "dir-slash", "source_event_id": "e1"})
    append_message(threads_dir, "a__b:R:1",
                   {"role": "user", "ts": 2, "text": "underscores", "source_event_id": "e2"})
    assert load(threads_dir, "a/b:R:1")["messages"][0]["text"] == "dir-slash"
    assert load(threads_dir, "a__b:R:1")["messages"][0]["text"] == "underscores"
    assert list_versions(threads_dir) == {"a/b:R:1": 1, "a__b:R:1": 1}


def test_traversal_anchors_rejected():
    assert valid_anchor("../../etc/passwd:R:1") is False
    assert valid_anchor("a/../b:R:1") is False
    assert valid_anchor("./a:R:1") is False
    assert valid_anchor("a/.:R:1") is False


def test_concurrent_appends_do_not_lose_messages(tmp_path):
    import threading
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    n = 24
    barrier = threading.Barrier(n)

    def worker(i):
        barrier.wait()
        append_message(threads_dir, "race.py:R:7",
                       {"role": "user", "ts": i, "text": f"q{i}", "source_event_id": f"e{i}"})

    workers = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
    for w in workers:
        w.start()
    for w in workers:
        w.join()
    t = load(threads_dir, "race.py:R:7")
    assert len(t["messages"]) == n
    assert t["version"] == n
    assert {m["text"] for m in t["messages"]} == {f"q{i}" for i in range(n)}


def test_append_message_without_title_leaves_it_untouched(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    append_message(threads_dir, "src/x.py:R:42",
                   {"role": "claude", "ts": 1, "text": "a", "source_event_id": "c1"},
                   title="Keep me")
    append_message(threads_dir, "src/x.py:R:42",
                   {"role": "user", "ts": 2, "text": "follow-up?", "source_event_id": "u1"})
    t = load(threads_dir, "src/x.py:R:42")
    assert t["title"] == "Keep me"
