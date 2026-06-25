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
