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
