"""Structural guards for the dismiss + page-lock feature.

Source-string checks matching the repo's other smoke tests; live behavior
is exercised by tests/e2e/dismiss.e2e.cjs (manual).
"""
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
STYLE_CSS = REPO / "skills" / "annotate" / "static" / "style.css"
SCRIPT_JS = REPO / "skills" / "annotate" / "static" / "script.js"


def test_busy_and_editing_css_present():
    css = STYLE_CSS.read_text()
    for needle in ("body.is-busy", "body.is-editing", ".busy-banner",
                   ".hover-actions button[data-type=\"dismiss\"]"):
        assert needle in css, f"style.css missing {needle!r}"


def test_dismiss_affordance_wired_in_script():
    src = SCRIPT_JS.read_text()
    assert 'type: "dismiss"' in src, "script.js never submits a dismiss event"
    assert "onDismiss" in src, "script.js missing onDismiss handler"


def test_busy_lock_consumed_in_script():
    src = SCRIPT_JS.read_text()
    assert "data.busy" in src, "script.js does not read data.busy from poll"
    assert "is-busy" in src, "script.js does not toggle the is-busy lock"


def test_single_editor_guard_in_script():
    src = SCRIPT_JS.read_text()
    assert "is-editing" in src, "script.js does not toggle the is-editing state"
