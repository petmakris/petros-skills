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
    pos, h = layout(nodes, edges)
    assert set(pos) == {"a", "b"}
    # b is below a
    assert pos["b"]["cy"] > pos["a"]["cy"]
    # single node per layer is horizontally centered in content width
    assert abs(pos["a"]["cx"] - 820 / 2) < 1
    assert h > pos["b"]["cy"]


def test_node_size_decision_is_diamond():
    w, h = node_size({"id": "f", "role": "decision", "label": "ok?"})
    assert (w, h) == (184, 88)


def test_two_nodes_same_layer_do_not_overlap():
    nodes = [
        {"id": "f", "role": "decision", "label": "ok?"},
        {"id": "g", "role": "success", "label": "yes"},
        {"id": "h", "role": "error", "label": "no"},
    ]
    edges = [{"from": "f", "to": "g"}, {"from": "f", "to": "h"}]
    pos, _ = layout(nodes, edges)
    # g and h are on the same layer, different x
    assert pos["g"]["cy"] == pos["h"]["cy"]
    assert abs(pos["g"]["cx"] - pos["h"]["cx"]) >= pos["g"]["w"]
