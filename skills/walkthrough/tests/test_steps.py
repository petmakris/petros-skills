import json

import pytest

from skills.walkthrough import steps as steps_module


def good_doc():
    return {
        "question": "how to add a precondition on share",
        "kind": "explain",
        "generated_ts": 1784720471,
        "steps": [
            {"id": 1, "title": "Where sharing starts", "file": "src/Api.java",
             "line": 42, "snippet": "return service.share(id);",
             "role": "context", "markdown": "The REST entry point."},
            {"id": 2, "title": "The precondition gate", "file": "src/Engine.java",
             "line": 114, "snippet": "var failures = preconditions.evaluate(p);",
             "role": "seam", "markdown": "Every Precondition bean runs here."},
        ],
    }


def test_valid_doc_has_no_errors():
    assert steps_module.validate(good_doc()) == []


def test_rejects_missing_snippet():
    doc = good_doc()
    del doc["steps"][0]["snippet"]
    errors = steps_module.validate(doc)
    assert any("snippet" in e for e in errors)


def test_rejects_blank_snippet():
    doc = good_doc()
    doc["steps"][0]["snippet"] = "   "
    assert any("snippet" in e for e in steps_module.validate(doc))


def test_rejects_non_positive_line():
    doc = good_doc()
    doc["steps"][1]["line"] = 0
    assert any("line" in e for e in steps_module.validate(doc))


def test_rejects_unknown_role():
    doc = good_doc()
    doc["steps"][0]["role"] = "wishful"
    assert any("role" in e for e in steps_module.validate(doc))


def test_rejects_duplicate_ids():
    doc = good_doc()
    doc["steps"][1]["id"] = 1
    assert any("duplicate" in e for e in steps_module.validate(doc))


def test_rejects_absolute_or_escaping_paths():
    doc = good_doc()
    doc["steps"][0]["file"] = "/etc/passwd"
    assert any("file" in e for e in steps_module.validate(doc))
    doc["steps"][0]["file"] = "../secrets.txt"
    assert any("file" in e for e in steps_module.validate(doc))


def test_rejects_empty_step_list():
    doc = good_doc()
    doc["steps"] = []
    assert any("at least" in e for e in steps_module.validate(doc))


def test_rejects_bad_kind():
    doc = good_doc()
    doc["kind"] = "vibes"
    assert any("kind" in e for e in steps_module.validate(doc))


def test_write_then_load_round_trip(tmp_path):
    steps_module.write_steps(tmp_path, good_doc())
    assert json.loads((tmp_path / "steps.json").read_text())["steps"][1]["id"] == 2
    loaded = steps_module.load_steps(tmp_path)
    assert loaded["question"] == "how to add a precondition on share"
    assert len(loaded["steps"]) == 2


def test_write_rejects_invalid_doc(tmp_path):
    doc = good_doc()
    doc["steps"][0]["snippet"] = ""
    with pytest.raises(ValueError):
        steps_module.write_steps(tmp_path, doc)
    assert not (tmp_path / "steps.json").exists()


def test_load_returns_none_when_absent(tmp_path):
    assert steps_module.load_steps(tmp_path) is None


def test_load_returns_none_on_garbage(tmp_path):
    (tmp_path / "steps.json").write_text("{not json")
    assert steps_module.load_steps(tmp_path) is None


def test_generated_ts(tmp_path):
    assert steps_module.generated_ts(tmp_path) == 0
    steps_module.write_steps(tmp_path, good_doc())
    assert steps_module.generated_ts(tmp_path) == 1784720471


def test_anchor_helpers():
    assert steps_module.step_anchor(3) == "step:3"
    assert steps_module.valid_anchor("step:3")
    assert not steps_module.valid_anchor("step:0")
    assert not steps_module.valid_anchor("src/x.java:R:12")
    assert not steps_module.valid_anchor("step:abc")
    assert steps_module.anchor_step_id("step:7") == 7
    assert steps_module.anchor_step_id("nope") is None
