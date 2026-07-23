"""Structural guards for granular sub-unit marks + batched review rounds.

Source-string checks matching the repo's other smoke tests (see
test_smoke_dismiss_lock.py). Live behavior is manual via the demo push.
"""
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
STATIC = REPO / "skills" / "annotate" / "static"
SUBUNITS_JS = STATIC / "subunits.js"
SCRIPT_JS = STATIC / "script.js"
STYLE_CSS = STATIC / "style.css"
SERVER_PY = REPO / "skills" / "annotate" / "server.py"


def test_subunits_js_exists_with_public_api():
    src = SUBUNITS_JS.read_text()
    for needle in ("window.AnnotateSubunits", "decorate", "onPoll",
                   '"round"', "reactions", "localStorage"):
        assert needle in src, f"subunits.js missing {needle!r}"


def test_subunits_selectors_cover_all_four_unit_types():
    src = SUBUNITS_JS.read_text()
    for needle in (":scope > ul > li", ":scope > ol > li",
                   ":scope > p", ":scope > pre", "tbody tr"):
        assert needle in src, f"subunits.js missing unit selector {needle!r}"


def test_subunits_skips_authored_annotate_ids():
    assert "data-annotate-id" in SUBUNITS_JS.read_text()


def test_subunits_marks_are_ordinal_aware():
    """Same-text units in one block must not collide on a shared markKey,
    and the wire prefix/suffix must be computed for the clicked occurrence
    (not always the first) — see design spec § "Duplicate unit text"."""
    src = SUBUNITS_JS.read_text()
    for needle in ("unitOrdinal", "nthIndexOf", "::${ordinal}"):
        assert needle in src, f"subunits.js missing ordinal guard {needle!r}"
    # The in-flight round id / sentinel must never leak the ordinal onto
    # the wire payload built in submitRound.
    assert "ordinal" not in src.split("function submitRound")[1].split(
        "function clearRound")[0]


def test_subunits_prunes_orphan_marks_before_submit_and_render():
    """A block/unit Claude has already removed must never reach the wire —
    server.py's _handle_round 422s the WHOLE round on the first unknown
    block_id, so an unpruned orphan would wedge Submit forever."""
    src = SUBUNITS_JS.read_text()
    for needle in ("pruneMarks", "booted", "main.prose section.block",
                   ".block-content"):
        assert needle in src, f"subunits.js missing prune guard {needle!r}"
    # pruneMarks must run at the top of both call sites the reviewer named.
    assert "function renderDock() {\n    pruneMarks();" in src
    assert "function submitRound() {\n    pruneMarks();" in src


def test_subunits_resets_pending_round_on_dead_watcher():
    """Mirrors script.js's WATCHER_DEAD_AFTER_S handling — a dead watcher
    means no ack is ever coming, so the dock must not stay wedged forever."""
    src = SUBUNITS_JS.read_text()
    for needle in ("watcher_age_s", "WATCHER_DEAD_AFTER_S"):
        assert needle in src, f"subunits.js missing {needle!r}"


def test_subunits_surfaces_submit_failure():
    assert "roundError" in SUBUNITS_JS.read_text()


def test_script_js_calls_decorate_on_both_render_paths():
    src = SCRIPT_JS.read_text()
    assert src.count("AnnotateSubunits.decorate") >= 2, \
        "script.js must decorate in createBlockSection AND updateBlockContent"
    assert "AnnotateSubunits.onPoll" in src


def test_server_page_includes_subunits_script():
    assert "subunits.js" in SERVER_PY.read_text()


def test_style_css_has_subunit_styles():
    css = STYLE_CSS.read_text()
    for needle in (".sub-unit", ".unit-strip", '[data-mark="dismiss"]',
                   '[data-mark="agree"]', ".unit-chip", "#round-dock",
                   "body.is-busy .unit-strip"):
        assert needle in css, f"style.css missing {needle!r}"
