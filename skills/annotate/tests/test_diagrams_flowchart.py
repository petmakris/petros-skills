"""Flowchart validator + renderer tests."""
import pytest

from skills.annotate.diagrams.flowchart import ValidationError, validate, render


def _spec():
    return {
        "title": "guard",
        "nodes": [
            {"id": "a", "role": "entry", "label": "User SAVES"},
            {"id": "b", "role": "code", "ref": "ProposalService:154",
             "method": "validateDocumentsSelection(orders)"},
            {"id": "f", "role": "decision", "label": "toggle ON?"},
            {"id": "g", "role": "success", "label": "allow", "sub": "no check"},
            {"id": "h", "role": "error", "label": "throw",
             "method": "MissingRequiredDocumentsException"},
        ],
        "edges": [
            {"from": "a", "to": "b"},
            {"from": "b", "to": "f"},
            {"from": "f", "to": "g", "label": "OFF"},
            {"from": "f", "to": "h", "label": "ON + doc missing"},
        ],
    }


def test_validate_minimal_ok():
    validate(_spec())  # no raise


def test_validate_requires_a_node():
    spec = _spec(); spec["nodes"] = []
    with pytest.raises(ValidationError, match="node"):
        validate(spec)


def test_validate_duplicate_node_id():
    spec = _spec(); spec["nodes"].append({"id": "a", "role": "code"})
    with pytest.raises(ValidationError, match="duplicate"):
        validate(spec)


def test_validate_edge_unknown_from():
    spec = _spec(); spec["edges"][0]["from"] = "ghost"
    with pytest.raises(ValidationError, match="from"):
        validate(spec)


def test_validate_edge_unknown_to():
    spec = _spec(); spec["edges"][0]["to"] = "ghost"
    with pytest.raises(ValidationError, match="to"):
        validate(spec)


def test_validate_rejects_cycle():
    spec = _spec(); spec["edges"].append({"from": "h", "to": "a"})
    with pytest.raises(ValidationError, match="cycle"):
        validate(spec)


def test_validate_unknown_role_tolerated():
    spec = _spec(); spec["nodes"][0]["role"] = "banana"
    validate(spec)  # no raise — unknown role renders neutral


def test_render_contains_node_hit_targets():
    svg = render(_spec(), block_id="section-1")
    assert svg.startswith("<svg")
    assert 'class="annotate-flow"' in svg
    for nid in ("a", "b", "f", "g", "h"):
        assert f'data-node-id="{nid}"' in svg
    assert 'data-block-id="section-1"' in svg


def test_render_role_classes():
    svg = render(_spec(), block_id="s")
    assert "node-entry" in svg
    assert "node-decision" in svg
    assert "node-success" in svg
    assert "node-error" in svg


def test_render_decision_is_polygon():
    svg = render(_spec(), block_id="s")
    assert "<polygon" in svg  # the diamond


def test_render_ref_becomes_link_when_href():
    spec = _spec()
    spec["nodes"][1]["href"] = "jetbrains://idea/x?path=/p/File.java:154"
    svg = render(spec, block_id="s")
    assert "<a " in svg and "jetbrains://idea" in svg
    assert 'class="flow-ref"' in svg
    # ref still wins over label/method when all three are present.
    assert '<a href="jetbrains://idea/x?path=/p/File.java:154">' \
        '<text class="flow-ref"' in svg
    assert '<a href="jetbrains://idea/x?path=/p/File.java:154">' \
        '<text class="flow-method"' not in svg


def test_render_method_only_node_with_href_wraps_method():
    spec = {
        "nodes": [
            {"id": "m", "role": "call", "method": "doThing()",
             "href": "jetbrains://idea/x"},
        ],
        "edges": [],
    }
    svg = render(spec, block_id="s")
    assert "<a " in svg
    assert "jetbrains://idea/x" in svg
    assert '<a href="jetbrains://idea/x"><text class="flow-method"' in svg


def test_render_label_only_node_with_href_wraps_label():
    spec = {
        "nodes": [
            {"id": "n", "role": "entry", "label": "User SAVES",
             "href": "jetbrains://idea/y"},
        ],
        "edges": [],
    }
    svg = render(spec, block_id="s")
    assert "<a " in svg
    assert "jetbrains://idea/y" in svg
    assert '<a href="jetbrains://idea/y"><text class="flow-label"' in svg


def test_render_edge_labels_present():
    svg = render(_spec(), block_id="s")
    assert "OFF" in svg
    assert "ON + doc missing" in svg


def test_render_escapes_text():
    spec = _spec()
    spec["nodes"][0]["label"] = "a & <b>"
    svg = render(spec, block_id="s")
    assert "a &amp; &lt;b&gt;" in svg


def test_render_unknown_role_defaults_neutral():
    spec = _spec()
    spec["nodes"][0]["role"] = "banana"
    svg = render(spec, block_id="s")
    assert "node-code" in svg  # neutral fallback class
