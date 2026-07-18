from skills.annotate.server import _render_block_for_raw


def _blk():
    return {"id": "section-1", "kind": "flowchart", "spec": {
        "nodes": [
            {"id": "a", "role": "entry", "label": "start"},
            {"id": "b", "role": "code", "ref": "F:1", "method": "m()"},
        ],
        "edges": [{"from": "a", "to": "b"}],
    }}


def test_flowchart_block_renders_svg():
    out = _render_block_for_raw(_blk(), version=1)
    assert out["kind"] == "flowchart"
    assert out["svg"].startswith("<svg")
    assert 'class="annotate-flow"' in out["svg"]
    assert out["spec"]["nodes"][0]["id"] == "a"


def test_flowchart_bad_spec_yields_error_pill_not_crash():
    blk = _blk()
    blk["spec"]["edges"] = [{"from": "a", "to": "ghost"}]  # dangling edge
    out = _render_block_for_raw(blk, version=1)
    assert "render failed" in out["svg"]
    assert "annotate-flow" in out["svg"]
