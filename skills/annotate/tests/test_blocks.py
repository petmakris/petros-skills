import json
from pathlib import Path

import pytest

from skills.annotate.blocks import (
    BlocksDoc, load, save_atomic, update_block, next_block_id,
    update_spec_block, next_step_id, drop_unused_terms, remove_block,
)


def test_load_missing_returns_empty(tmp_path):
    doc = load(tmp_path / "blocks.json")
    assert doc.response_id == ""
    assert doc.blocks == []


def test_save_and_load_round_trip(tmp_path):
    path = tmp_path / "blocks.json"
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[
        {"id": "b-0", "markdown": "hello"},
    ])
    save_atomic(path, doc)
    doc2 = load(path)
    assert doc2.response_id == "r-1"
    assert doc2.title == "t"
    assert doc2.blocks == doc.blocks


def test_save_atomic_strips_legacy_version_field(tmp_path):
    """Older sessions / habit-writers may still emit `version: N` on
    blocks. The on-disk format is canonical: it does not include version."""
    path = tmp_path / "blocks.json"
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[
        {"id": "b-0", "markdown": "hello", "version": 7},
        {"id": "b-1", "kind": "sequence", "spec": {"actors": [], "steps": []}, "version": 3},
    ])
    save_atomic(path, doc)
    raw = json.loads(path.read_text())
    for b in raw["blocks"]:
        assert "version" not in b


def test_update_block_returns_true_on_change(tmp_path):
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[
        {"id": "b-0", "markdown": "old"},
        {"id": "b-1", "markdown": "x"},
    ])
    changed = update_block(doc, "b-0", "new")
    assert changed is True
    assert doc.blocks[0]["markdown"] == "new"
    # No version field is mutated — versions live in versions.json now.


def test_update_block_no_op_when_unchanged(tmp_path):
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[
        {"id": "b-0", "markdown": "same"},
    ])
    changed = update_block(doc, "b-0", "same")
    assert changed is False


def test_update_block_unknown_id_raises():
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[])
    with pytest.raises(KeyError):
        update_block(doc, "b-99", "x")


def test_next_block_id_never_reuses():
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[
        {"id": "section-1", "markdown": "x", "version": 1},
        {"id": "section-5", "markdown": "y", "version": 1},
    ])
    # Next id is the smallest positive integer not in {1, 5}
    assert next_block_id(doc) == "section-2"


def test_next_block_id_empty_doc():
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[])
    assert next_block_id(doc) == "section-1"


def _seq_block(bid: str, spec: dict):
    return {"id": bid, "kind": "sequence", "spec": spec}


def test_update_spec_block_returns_true_on_change():
    spec_old = {"actors": [{"id": "a", "label": "A"}], "steps": []}
    spec_new = {"actors": [{"id": "a", "label": "A2"}], "steps": []}
    doc = BlocksDoc(response_id="r-1", title="t",
                    blocks=[_seq_block("b-0", spec_old)])
    changed = update_spec_block(doc, "b-0", spec_new)
    assert changed is True
    assert doc.blocks[0]["spec"] == spec_new


def test_update_spec_block_no_op_when_equivalent():
    spec = {"actors": [{"id": "a", "label": "A"}], "steps": []}
    doc = BlocksDoc(response_id="r-1", title="t",
                    blocks=[_seq_block("b-0", spec)])
    # Reordered keys must still hash equal — canonical JSON.
    equivalent = {"steps": [], "actors": [{"label": "A", "id": "a"}]}
    changed = update_spec_block(doc, "b-0", equivalent)
    assert changed is False


def test_update_spec_block_unknown_id_raises():
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[])
    with pytest.raises(KeyError):
        update_spec_block(doc, "b-99", {})


def test_next_step_id_empty_spec():
    assert next_step_id({"steps": []}) == "s1"
    assert next_step_id({}) == "s1"


def test_next_step_id_never_reuses():
    spec = {"steps": [{"id": "s1"}, {"id": "s3"}, {"id": "s4"}]}
    assert next_step_id(spec) == "s2"


def test_next_step_id_handles_non_numeric():
    spec = {"steps": [{"id": "s1"}, {"id": "custom"}, {"id": "s2"}]}
    assert next_step_id(spec) == "s3"


def test_load_includes_glossary(tmp_path):
    path = tmp_path / "blocks.json"
    path.write_text(json.dumps({
        "response_id": "r-1", "title": "t",
        "blocks": [{"id": "b-0", "markdown": "hi", "version": 1}],
        "glossary": [
            {"term": "OnboardingOrchestrator",
             "definition": "Internal service coordinating new-user signup.",
             "role": "Upstream that emits the payload too early."},
        ],
    }))
    doc = load(path)
    assert doc.glossary == [
        {"term": "OnboardingOrchestrator",
         "definition": "Internal service coordinating new-user signup.",
         "role": "Upstream that emits the payload too early."},
    ]


def test_load_missing_glossary_defaults_to_empty():
    from pathlib import Path
    doc = load(Path("/nonexistent.json"))
    assert doc.glossary == []


def test_save_round_trips_glossary(tmp_path):
    path = tmp_path / "blocks.json"
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[{"id": "b-0", "markdown": "hi", "version": 1}],
        glossary=[{"term": "Foo", "definition": "a foo", "role": "the bar"}],
    )
    save_atomic(path, doc)
    raw = json.loads(path.read_text())
    assert raw["glossary"] == [{"term": "Foo", "definition": "a foo", "role": "the bar"}]
    doc2 = load(path)
    assert doc2.glossary == doc.glossary


def test_save_omits_glossary_key_when_empty(tmp_path):
    path = tmp_path / "blocks.json"
    doc = BlocksDoc(response_id="r-1", title="t",
                    blocks=[{"id": "b-0", "markdown": "hi", "version": 1}])
    save_atomic(path, doc)
    raw = json.loads(path.read_text())
    assert "glossary" not in raw


def test_drop_unused_terms_removes_orphan_entries():
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[
            {"id": "b-0", "markdown": "Use OnboardingOrchestrator here.", "version": 1},
        ],
        glossary=[
            {"term": "OnboardingOrchestrator", "definition": "...", "role": "..."},
            {"term": "InsightsAggregator", "definition": "...", "role": "..."},  # orphan
        ],
    )
    changed = drop_unused_terms(doc)
    assert changed is True
    assert [g["term"] for g in doc.glossary] == ["OnboardingOrchestrator"]


def test_drop_unused_terms_no_op_when_all_referenced():
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[{"id": "b-0", "markdown": "Foo and Bar.", "version": 1}],
        glossary=[
            {"term": "Foo", "definition": "...", "role": "..."},
            {"term": "Bar", "definition": "...", "role": "..."},
        ],
    )
    changed = drop_unused_terms(doc)
    assert changed is False
    assert len(doc.glossary) == 2


def test_drop_unused_terms_case_sensitive():
    # "foo" lowercase should NOT match the entry for "Foo".
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[{"id": "b-0", "markdown": "the foo bar", "version": 1}],
        glossary=[{"term": "Foo", "definition": "...", "role": "..."}],
    )
    changed = drop_unused_terms(doc)
    assert changed is True
    assert doc.glossary == []


def test_drop_unused_terms_whole_word_only():
    # "Aggregator" should NOT match inside "Aggregators".
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[{"id": "b-0", "markdown": "We have many Aggregators here.", "version": 1}],
        glossary=[{"term": "Aggregator", "definition": "...", "role": "..."}],
    )
    changed = drop_unused_terms(doc)
    assert changed is True
    assert doc.glossary == []


def test_drop_unused_terms_scans_all_blocks():
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[
            {"id": "b-0", "markdown": "intro", "version": 1},
            {"id": "b-1", "markdown": "see Foo for details", "version": 1},
        ],
        glossary=[{"term": "Foo", "definition": "...", "role": "..."}],
    )
    changed = drop_unused_terms(doc)
    assert changed is False
    assert len(doc.glossary) == 1


def test_drop_unused_terms_with_no_blocks_drops_all_entries():
    # Mode D may invoke this before any blocks are populated. Confirm the
    # function returns True and clears the glossary instead of crashing.
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[],
        glossary=[{"term": "Foo", "definition": "...", "role": "..."}],
    )
    changed = drop_unused_terms(doc)
    assert changed is True
    assert doc.glossary == []


from skills.annotate.blocks import (
    choice_option_ids, validate_choice_selection, convert_block_to_markdown,
)


def _choice_spec(multi=False):
    return {
        "question": "Pick one",
        "multiSelect": multi,
        "options": [
            {"id": "o1", "label": "A"},
            {"id": "o2", "label": "B"},
            {"id": "o3", "label": "C"},
        ],
    }


def test_choice_option_ids_lists_ids_in_order():
    assert choice_option_ids(_choice_spec()) == ["o1", "o2", "o3"]


def test_choice_option_ids_empty_for_no_options():
    assert choice_option_ids({}) == []


def test_validate_single_select_accepts_one_known_id():
    assert validate_choice_selection(_choice_spec(), ["o2"]) is None


def test_validate_single_select_rejects_empty():
    err = validate_choice_selection(_choice_spec(), [])
    assert err is not None and "empty" in err.lower()


def test_validate_single_select_rejects_two_picks():
    err = validate_choice_selection(_choice_spec(multi=False), ["o1", "o2"])
    assert err is not None and "one" in err.lower()


def test_validate_rejects_unknown_id():
    err = validate_choice_selection(_choice_spec(), ["o9"])
    assert err is not None and "option" in err.lower()


def test_validate_rejects_non_list():
    err = validate_choice_selection(_choice_spec(), "o1")
    assert err is not None


def test_validate_multi_select_accepts_several():
    assert validate_choice_selection(_choice_spec(multi=True), ["o1", "o3"]) is None


def test_validate_multi_select_rejects_empty():
    err = validate_choice_selection(_choice_spec(multi=True), [])
    assert err is not None and "empty" in err.lower()


def test_convert_block_to_markdown_flips_kind_and_drops_spec():
    doc = BlocksDoc(blocks=[
        {"id": "section-1", "kind": "choice", "spec": _choice_spec()},
    ])
    changed = convert_block_to_markdown(doc, "section-1", "Decision: A.")
    assert changed is True
    blk = doc.blocks[0]
    assert blk.get("kind", "markdown") == "markdown"
    assert blk["markdown"] == "Decision: A."
    assert "spec" not in blk


def test_convert_block_to_markdown_is_noop_when_already_equal():
    doc = BlocksDoc(blocks=[
        {"id": "section-1", "markdown": "Decision: A."},
    ])
    assert convert_block_to_markdown(doc, "section-1", "Decision: A.") is False


def test_remove_block_removes_present_block():
    doc = BlocksDoc(blocks=[
        {"id": "section-1", "markdown": "a"},
        {"id": "section-2", "markdown": "b"},
        {"id": "section-3", "markdown": "c"},
    ])
    assert remove_block(doc, "section-2") is True
    assert [b["id"] for b in doc.blocks] == ["section-1", "section-3"]


def test_remove_block_absent_id_is_noop():
    doc = BlocksDoc(blocks=[{"id": "section-1", "markdown": "a"}])
    assert remove_block(doc, "section-9") is False
    assert [b["id"] for b in doc.blocks] == ["section-1"]


def test_remove_block_removes_non_markdown_block():
    doc = BlocksDoc(blocks=[
        {"id": "section-1", "markdown": "a"},
        {"id": "section-2", "kind": "choice", "spec": {"question": "q", "options": []}},
    ])
    assert remove_block(doc, "section-2") is True
    assert [b["id"] for b in doc.blocks] == ["section-1"]
