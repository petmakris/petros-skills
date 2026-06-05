"""Mermaid diagram validator + renderer tests."""
import shutil

import pytest

from skills.annotate.diagrams.mermaid import (
    ValidationError,
    RenderError,
    SUPPORTED_TYPES,
    validate,
    render,
    _postprocess,
)
from skills.annotate.server import _render_block_for_raw


def _minimal_spec():
    return {"type": "flowchart", "title": "Demo", "source": "graph TD\n  A-->B"}


def test_validate_minimal_spec_ok():
    validate(_minimal_spec())  # no raise


def test_validate_rejects_unknown_type():
    spec = _minimal_spec()
    spec["type"] = "gantt"
    with pytest.raises(ValidationError, match="type"):
        validate(spec)


def test_validate_rejects_missing_type():
    spec = _minimal_spec()
    del spec["type"]
    with pytest.raises(ValidationError, match="type"):
        validate(spec)


def test_validate_rejects_empty_source():
    spec = _minimal_spec()
    spec["source"] = "   "
    with pytest.raises(ValidationError, match="source"):
        validate(spec)


def test_validate_rejects_nonstring_source():
    spec = _minimal_spec()
    spec["source"] = 123
    with pytest.raises(ValidationError, match="source"):
        validate(spec)


def test_supported_types_cover_v1():
    assert set(SUPPORTED_TYPES) == {"flowchart", "architecture", "state", "er", "class"}


_HAVE_MMDC = shutil.which("mmdc") is not None
requires_mmdc = pytest.mark.skipif(not _HAVE_MMDC, reason="mmdc (mermaid-cli) not installed")


@requires_mmdc
def test_render_flowchart_returns_svg():
    svg = render(_minimal_spec(), block_id="section-1")
    assert "<svg" in svg
    assert "annotate-diagram" in svg
    # XML prolog stripped so the string can be injected straight into innerHTML.
    assert not svg.lstrip().startswith("<?xml")


@requires_mmdc
def test_render_state_diagram_returns_svg():
    spec = {"type": "state", "title": "S",
            "source": "stateDiagram-v2\n  [*] --> Idle\n  Idle --> Running"}
    assert "<svg" in render(spec, block_id="section-2")


def test_render_rejects_bad_spec_before_shelling_out():
    with pytest.raises(ValidationError):
        render({"type": "gantt", "source": "x"}, block_id="section-3")


@requires_mmdc
def test_render_raises_on_invalid_mermaid_source():
    spec = {"type": "flowchart", "title": "bad",
            "source": "this is not valid mermaid @@@ ->"}
    with pytest.raises(RenderError):
        render(spec, block_id="section-4")


def test_postprocess_strips_prolog_and_adds_class_when_none():
    raw = '<?xml version="1.0"?>\n<svg xmlns="http://www.w3.org/2000/svg"><g/></svg>'
    result = _postprocess(raw)
    assert not result.lstrip().startswith("<?xml")
    assert 'class="annotate-diagram"' in result
    # exactly one class attribute on the root svg
    assert result.count('class="') == 1


def test_postprocess_appends_to_existing_class():
    raw = '<svg class="mermaid" xmlns="http://www.w3.org/2000/svg"><g/></svg>'
    result = _postprocess(raw)
    # appended, not duplicated: still a single class attribute on the root svg
    assert result.count('class="') == 1
    assert "annotate-diagram" in result
    assert "mermaid" in result


def test_postprocess_idempotent_when_already_tagged():
    raw = '<svg class="annotate-diagram mermaid" xmlns="http://www.w3.org/2000/svg"><g/></svg>'
    assert _postprocess(raw) == raw


@requires_mmdc
def test_server_renders_diagram_block():
    blk = {"id": "section-1", "kind": "diagram", "spec": _minimal_spec()}
    out = _render_block_for_raw(blk, version=1)
    assert out["kind"] == "diagram"
    assert "<svg" in out["svg"]
    assert out["spec"] == _minimal_spec()


def test_server_diagram_render_failure_yields_error_pill():
    # Unknown type → mermaid.validate raises → server catches → error pill,
    # never an exception that blanks /raw. (No mmdc needed: validation fails first.)
    blk = {"id": "section-9", "kind": "diagram", "spec": {"type": "gantt", "source": "x"}}
    out = _render_block_for_raw(blk, version=1)
    assert out["kind"] == "diagram"
    assert "diagram render failed" in out["svg"]
