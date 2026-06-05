"""Structural guard for the comment-card v2 redesign (commit 280cdd8):

  - .card-close lives in the right gutter (right: -32px), not inside the
    card body — so the dismiss button cannot overlap the textarea.
  - The preview/edit-mode toggle is gone — no .editor-preview, no
    .preview-mode / .edit-mode class names remain in JS or CSS.

If a future change reintroduces either, this test fails immediately and
the diff explains why.
"""
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
CORE_CSS = REPO / "skills" / "_shared" / "web_companion" / "static" / "core.css"
SCRIPT_JS = REPO / "skills" / "annotate" / "static" / "script.js"


def test_card_close_lives_in_right_gutter():
    css = CORE_CSS.read_text()
    block_match = re.search(r"\.card-close\s*\{([^}]*)\}", css)
    assert block_match, ".card-close rule missing from core.css"
    rule = block_match.group(1)
    # right must be a negative value — the close sits outside the card.
    right_match = re.search(r"right:\s*(-?\d+)px", rule)
    assert right_match, ".card-close must declare an explicit `right` offset"
    right_px = int(right_match.group(1))
    assert right_px < 0, (
        f".card-close right offset must be negative (outside card body); "
        f"got right: {right_px}px"
    )


def test_preview_mode_is_gone():
    """The preview/edit-mode toggle should leave no trace in JS or CSS."""
    js = SCRIPT_JS.read_text()
    css = CORE_CSS.read_text()
    for needle in (".preview-mode", ".edit-mode", "editor-preview"):
        assert needle not in js, f"JS still references {needle!r}"
        assert needle not in css, f"CSS still references {needle!r}"
