import json
from pathlib import Path

from skills.annotate.versions import derive_versions


def test_first_call_assigns_v1_to_every_block(tmp_path):
    vp = tmp_path / "versions.json"
    blocks = [
        {"id": "b-0", "markdown": "hello"},
        {"id": "b-1", "markdown": "world"},
    ]
    versions = derive_versions(vp, blocks)
    assert versions == {"b-0": 1, "b-1": 1}
    assert vp.exists()


def test_repeated_call_same_content_no_bump(tmp_path):
    vp = tmp_path / "versions.json"
    blocks = [{"id": "b-0", "markdown": "hi"}]
    derive_versions(vp, blocks)
    versions = derive_versions(vp, blocks)
    assert versions == {"b-0": 1}


def test_content_change_bumps_only_that_block(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [
        {"id": "b-0", "markdown": "alpha"},
        {"id": "b-1", "markdown": "beta"},
    ])
    versions = derive_versions(vp, [
        {"id": "b-0", "markdown": "alpha-edited"},
        {"id": "b-1", "markdown": "beta"},
    ])
    assert versions == {"b-0": 2, "b-1": 1}


def test_whitespace_only_change_does_not_bump(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "markdown": "same"}])
    versions = derive_versions(vp, [{"id": "b-0", "markdown": "  same  \n"}])
    assert versions == {"b-0": 1}


def test_sequence_block_uses_canonical_spec(tmp_path):
    vp = tmp_path / "versions.json"
    spec_a = {"actors": [{"id": "a", "label": "A"}], "steps": []}
    spec_a_reordered = {"steps": [], "actors": [{"label": "A", "id": "a"}]}
    derive_versions(vp, [{"id": "b-0", "kind": "sequence", "spec": spec_a}])
    versions = derive_versions(
        vp, [{"id": "b-0", "kind": "sequence", "spec": spec_a_reordered}]
    )
    assert versions == {"b-0": 1}


def test_diagram_spec_change_bumps(tmp_path):
    """Regression: diagram content lives in `spec`, not `markdown`. A spec edit
    (e.g. rewriting the Mermaid source) must bump the version, or the client
    never refetches the updated SVG."""
    vp = tmp_path / "versions.json"
    derive_versions(vp, [
        {"id": "b-0", "kind": "diagram", "spec": {"type": "flowchart", "source": "A-->B"}}
    ])
    versions = derive_versions(vp, [
        {"id": "b-0", "kind": "diagram", "spec": {"type": "flowchart", "source": "A-->C"}}
    ])
    assert versions == {"b-0": 2}


def test_diagram_spec_unchanged_no_bump(tmp_path):
    vp = tmp_path / "versions.json"
    spec = {"type": "flowchart", "source": "A-->B"}
    derive_versions(vp, [{"id": "b-0", "kind": "diagram", "spec": spec}])
    versions = derive_versions(vp, [{"id": "b-0", "kind": "diagram", "spec": dict(spec)}])
    assert versions == {"b-0": 1}


def test_choice_spec_change_bumps(tmp_path):
    """Choice content also lives in `spec`; a spec edit must bump the version."""
    vp = tmp_path / "versions.json"
    derive_versions(vp, [
        {"id": "b-0", "kind": "choice", "spec": {"options": [{"id": "o1", "label": "A"}]}}
    ])
    versions = derive_versions(vp, [
        {"id": "b-0", "kind": "choice", "spec": {"options": [{"id": "o1", "label": "B"}]}}
    ])
    assert versions == {"b-0": 2}


def test_kind_change_bumps(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "markdown": "hi"}])
    versions = derive_versions(vp, [
        {"id": "b-0", "kind": "sequence", "spec": {"actors": [], "steps": []}}
    ])
    assert versions == {"b-0": 2}


def test_new_block_starts_at_v1(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "markdown": "alpha"}])
    versions = derive_versions(vp, [
        {"id": "b-0", "markdown": "alpha"},
        {"id": "b-1", "markdown": "fresh"},
    ])
    assert versions == {"b-0": 1, "b-1": 1}


def test_sidecar_format_is_chain_per_block(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "markdown": "v1"}])
    derive_versions(vp, [{"id": "b-0", "markdown": "v2"}])
    chain = json.loads(vp.read_text())
    assert set(chain.keys()) == {"b-0"}
    assert isinstance(chain["b-0"], list)
    assert len(chain["b-0"]) == 2
    assert all(isinstance(h, str) and len(h) == 40 for h in chain["b-0"])


def test_corrupt_sidecar_is_treated_as_empty(tmp_path):
    vp = tmp_path / "versions.json"
    vp.write_text("{not json")
    versions = derive_versions(vp, [{"id": "b-0", "markdown": "hi"}])
    assert versions == {"b-0": 1}


def test_html_inter_tag_whitespace_collapses(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "markdown": "<div><p>hi</p></div>"}])
    versions = derive_versions(vp, [{
        "id": "b-0",
        "markdown": "<div>\n  <p>hi</p>\n</div>",
    }])
    assert versions == {"b-0": 1}


def test_over_rewrite_regression(tmp_path):
    """Claude re-emits the whole document twice with identical content.
    Every block's chain must have exactly one entry — this is the
    regression guard for the inflation bug."""
    vp = tmp_path / "versions.json"
    doc = [
        {"id": "b-0", "markdown": "alpha"},
        {"id": "b-1", "markdown": "beta"},
        {"id": "b-2", "markdown": "gamma"},
        {"id": "b-3", "markdown": "delta"},
    ]
    derive_versions(vp, doc)
    derive_versions(vp, doc)
    versions = derive_versions(vp, doc)
    assert versions == {"b-0": 1, "b-1": 1, "b-2": 1, "b-3": 1}
    chain = json.loads(vp.read_text())
    for bid in ("b-0", "b-1", "b-2", "b-3"):
        assert len(chain[bid]) == 1


def test_chain_pruned_when_block_removed_so_reused_id_resets(tmp_path):
    """next_block_id remints the smallest free id, so a deleted id can be
    reused by an unrelated block. The old chain must be pruned on removal,
    otherwise the new block would inherit a stale (inflated) version — or,
    worse, be reported unchanged if its content hash-matched the old tail."""
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "markdown": "alpha"}])
    derive_versions(vp, [{"id": "b-0", "markdown": "alpha-2"}])
    assert len(json.loads(vp.read_text())["b-0"]) == 2
    # b-0 removed from the doc — its chain is pruned.
    derive_versions(vp, [{"id": "b-1", "markdown": "beta"}])
    assert "b-0" not in json.loads(vp.read_text())
    # b-0 reused by a brand-new block → fresh v1, not v3.
    versions = derive_versions(vp, [
        {"id": "b-1", "markdown": "beta"},
        {"id": "b-0", "markdown": "totally different content"},
    ])
    assert versions["b-0"] == 1


def test_ignores_legacy_version_field(tmp_path):
    """A block carrying a legacy `version: 99` field is treated like any
    other block — versions come from the chain, not the field."""
    vp = tmp_path / "versions.json"
    versions = derive_versions(
        vp, [{"id": "b-0", "markdown": "hi", "version": 99}]
    )
    assert versions == {"b-0": 1}
