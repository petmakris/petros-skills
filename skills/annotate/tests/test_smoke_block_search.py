"""Structural guard for the client-side block-search feature.

Asserts the search box markup and its script/style hooks exist. These are
string/source checks (matching the repo's other smoke tests) — the live
behavior is covered by tests/e2e/search.e2e.cjs.
"""
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SERVER_PY = REPO / "skills" / "annotate" / "server.py"
STYLE_CSS = REPO / "skills" / "annotate" / "static" / "style.css"
SEARCH_JS = REPO / "skills" / "annotate" / "static" / "search.js"
FUSE_JS = REPO / "skills" / "annotate" / "static" / "fuse.min.js"


def test_search_box_markup_in_server():
    src = SERVER_PY.read_text()
    assert 'id="block-search"' in src, "search input missing from rendered header"
    assert "header-search" in src, "search wrapper missing from header"


def test_search_scripts_included():
    src = SERVER_PY.read_text()
    assert "/static/fuse.min.js" in src, "fuse.min.js not included in page head"
    assert "/static/search.js" in src, "search.js not included in page head"


def test_search_static_files_exist():
    assert FUSE_JS.exists(), "vendored fuse.min.js missing"
    assert SEARCH_JS.exists(), "search.js missing"


def test_search_css_present():
    css = STYLE_CSS.read_text()
    for needle in (".header-search", ".search-input", ".search-hidden",
                   "mark.search-hit", ".search-count"):
        assert needle in css, f"style.css missing {needle!r}"
