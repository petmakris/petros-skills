"""End-to-end smoke: prove the client-side sanitizer strips known
dangerous vectors from free HTML emitted inside a markdown block.

This is a Python-driven test that exercises the actual sanitizer code
shipped in script.js. We don't have a full browser harness, so we parse
the JS file, extract the disallowed-tag list and the function, and
assert two invariants:

  1. The blacklist normalises tagName before lookup (case-insensitive)
     so SVG-namespaced descendants (lowercase tagName) are caught.
  2. The function strips on* attributes and javascript: URLs.

We can't execute JS from Python, but we can pin the *shape* of the
sanitizer so a future refactor can't silently regress the SVG case
(which we just patched in a21e1ec) or the on-handler / javascript:
strips. The browser-side proof lives in the commit message + manual
smoke; this test is the regression guard.
"""
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SCRIPT_JS = REPO / "skills" / "annotate" / "static" / "script.js"


def test_sanitizer_uppercase_normalises_tagname():
    """Regression guard for the SVG-script bypass (a21e1ec).

    The blacklist is uppercase. SVG descendants report a lowercase
    tagName. The check MUST normalise before comparing.
    """
    src = SCRIPT_JS.read_text()
    # Find the sanitizer block — bounded by its declaration and the
    # closing `}` of the function. We only need the comparison line.
    san_block = _extract_function(src, "sanitizeFreeHtml")
    assert "SAN_DISALLOWED_TAGS.has(node.tagName.toUpperCase())" in san_block, (
        "sanitizer must call .toUpperCase() on tagName before lookup so "
        "SVG-namespaced <script> elements (lowercase tagName) are caught"
    )


def test_sanitizer_blacklist_covers_dangerous_tags():
    src = SCRIPT_JS.read_text()
    list_match = re.search(
        r"SAN_DISALLOWED_TAGS\s*=\s*new\s+Set\(\[\s*([^\]]+)\]",
        src,
    )
    assert list_match, "could not find SAN_DISALLOWED_TAGS"
    raw = list_match.group(1)
    tags = {t.strip().strip('"').strip("'").upper() for t in raw.split(",") if t.strip()}
    for must_have in ("SCRIPT", "IFRAME", "OBJECT", "EMBED", "STYLE", "FORM",
                      "LINK", "META", "BASE"):
        assert must_have in tags, f"sanitizer blacklist missing {must_have}"


def test_sanitizer_strips_event_handlers_and_js_urls():
    src = SCRIPT_JS.read_text()
    san_block = _extract_function(src, "sanitizeFreeHtml")
    # on* attribute removal
    assert 'name.startsWith("on")' in san_block, (
        "sanitizer must strip on* event-handler attributes"
    )
    # javascript: URL removal on href/src/xlink:href
    assert 'javascript:' in san_block, (
        "sanitizer must filter javascript: URLs"
    )
    assert '"href"' in san_block and '"src"' in san_block, (
        "sanitizer must check both href and src attributes"
    )


def _extract_function(src: str, name: str) -> str:
    """Return the body of `function name(...) { ... }` by brace-matching."""
    needle = f"function {name}"
    start = src.find(needle)
    assert start != -1, f"function {name} not found"
    # Walk to the first { then brace-match.
    i = src.find("{", start)
    assert i != -1
    depth = 1
    j = i + 1
    while j < len(src) and depth:
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
        j += 1
    return src[start:j]
