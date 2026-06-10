"""Structural guards: the general composer must never silently swallow input.

Three invariants born from a real data-loss postmortem (2026-06-10/11):
  1. The busy lock must NOT make the general composer click-inert — sends
     queue server-side instead of being dropped on the floor.
  2. Sending while busy must say so ("queued"), not pretend nothing happened.
  3. Enter inserts a newline; only Cmd/Ctrl+Enter sends — same convention as
     the block comment cards, so a numbered answer can't fire prematurely.

Source-string checks matching the repo's other smoke tests.
"""
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
STYLE_CSS = REPO / "skills" / "annotate" / "static" / "style.css"
SCRIPT_JS = REPO / "skills" / "annotate" / "static" / "script.js"
SERVER_PY = REPO / "skills" / "annotate" / "server.py"


def test_busy_lock_does_not_freeze_general_composer():
    css = STYLE_CSS.read_text()
    assert "body.is-busy .general-composer" not in css, (
        "style.css still pointer-locks the general composer while busy — "
        "Send clicks during a multi-minute update are silently swallowed"
    )


def test_general_send_reports_queued_while_busy():
    src = SCRIPT_JS.read_text()
    assert "queued" in src, (
        "script.js general send() has no queued-while-busy feedback"
    )


def test_general_composer_enter_is_newline_not_send():
    src = SCRIPT_JS.read_text()
    assert 'e.key === "Enter" && !e.shiftKey' not in src, (
        "general composer still submits on plain Enter — newline intent "
        "becomes a premature partial send"
    )
    # Cmd/Ctrl+Enter must be the send chord in the general composer too.
    assert src.count('ev.metaKey || ev.ctrlKey') + src.count('e.metaKey || e.ctrlKey') >= 2, (
        "expected both the card composer and the general composer to gate "
        "Enter-send behind meta/ctrl"
    )


def test_general_composer_shows_send_chord_hint():
    page = SERVER_PY.read_text()
    assert "general-hint" in page, (
        "serve_root HTML carries no send-chord hint for the general composer"
    )
