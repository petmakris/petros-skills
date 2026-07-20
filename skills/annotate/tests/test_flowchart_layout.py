from skills.annotate.diagrams.flowchart_layout import assign_layers, layout, node_size


def test_assign_layers_linear():
    layers = assign_layers(["a", "b", "c"],
                           [{"from": "a", "to": "b"}, {"from": "b", "to": "c"}])
    assert layers == {"a": 0, "b": 1, "c": 2}


def test_assign_layers_funnel_longest_path():
    # a->b->e and c->e ; e must sit below b (longest path wins)
    layers = assign_layers(["a", "b", "c", "e"],
                           [{"from": "a", "to": "b"}, {"from": "b", "to": "e"},
                            {"from": "c", "to": "e"}])
    assert layers["a"] == 0 and layers["c"] == 0
    assert layers["b"] == 1
    assert layers["e"] == 2


def test_layout_positions_and_height():
    nodes = [
        {"id": "a", "role": "entry", "label": "start"},
        {"id": "b", "role": "code", "ref": "F:1", "method": "m()"},
    ]
    edges = [{"from": "a", "to": "b"}]
    pos, w, h = layout(nodes, edges)
    assert set(pos) == {"a", "b"}
    # b is below a
    assert pos["b"]["cy"] > pos["a"]["cy"]
    # single node per layer is horizontally centered in the canvas
    assert abs(pos["a"]["cx"] - w / 2) < 1
    assert h > pos["b"]["cy"]


def test_node_is_sized_from_its_text():
    narrow, _ = node_size({"id": "a", "role": "code", "label": "ok"})
    wide, _ = node_size({"id": "b", "role": "code",
                         "method": "aLongMethodSignature(withArguments, andMore)"})
    assert wide > narrow
    # a short label still gets the minimum box, not a hairline one
    assert narrow >= 150


def test_node_size_decision_is_a_diamond_that_contains_its_text():
    from skills.annotate.diagrams.flowchart_layout import node_lines, text_box
    from skills.annotate.diagrams.text_metrics import line_h, text_px
    node = {"id": "f", "role": "decision", "label": "toggle ON?",
            "ref": "ProposalLifecycleActionsService:164"}
    w, h = node_size(node)
    lines = node_lines(node)
    _, th = text_box(lines)
    # every line fits the half-width available at its own vertical offset
    y = -th / 2
    for cls, txt, _ in lines:
        dy = max(abs(y), abs(y + line_h(cls)))
        assert text_px(txt, cls) / 2 <= (w / 2) * (1 - dy / (h / 2)) + 0.01
        y += line_h(cls)
    # and the per-line rule keeps it far tighter than sizing from the text bbox
    assert w < 2 * (text_px(node["ref"], "flow-ref") + 18)


def test_two_nodes_same_layer_do_not_overlap():
    nodes = [
        {"id": "f", "role": "decision", "label": "ok?"},
        {"id": "g", "role": "success", "label": "yes"},
        {"id": "h", "role": "error", "label": "no"},
    ]
    edges = [{"from": "f", "to": "g"}, {"from": "f", "to": "h"}]
    pos, _, _ = layout(nodes, edges)
    # g and h are on the same layer, different x
    assert pos["g"]["cy"] == pos["h"]["cy"]
    assert abs(pos["g"]["cx"] - pos["h"]["cx"]) >= (pos["g"]["w"] + pos["h"]["w"]) / 2
