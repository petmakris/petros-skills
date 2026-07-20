"""Geometry invariants for rendered flowcharts.

These assert on the *rendered SVG* rather than on internal layout state, so
they catch the class of bug that used to only show up in a screenshot: boxes
overlapping each other, text spilling out of its shape, edge labels landing on
top of a node, geometry escaping the viewBox.

The corpus below includes `three_way_branch`, the shape that was visibly broken
(a row of three 300px nodes packed into 260px slots).
"""
from __future__ import annotations

import random
import xml.etree.ElementTree as ET

import pytest

from skills.annotate.diagrams.flowchart import render
from skills.annotate.diagrams.text_metrics import line_h, text_px

PAD = 6.0  # min clearance we require between two node shapes


# ── SVG parsing ────────────────────────────────────────────────────────────

class Shape:
    def __init__(self, kind, x0, y0, x1, y1, texts):
        self.kind, self.x0, self.y0, self.x1, self.y1 = kind, x0, y0, x1, y1
        self.texts = texts  # list of (cls, text, x, baseline_y)

    @property
    def box(self):
        return (self.x0, self.y0, self.x1, self.y1)


def _strip(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse(svg: str):
    root = ET.fromstring(svg)
    vb = [float(v) for v in root.get("viewBox").split()]
    nodes, labels = [], []
    for g in root.iter():
        if _strip(g.tag) != "g":
            continue
        cls = g.get("class", "")
        if "node" in cls.split():
            texts = [(t.get("class"), t.text or "", float(t.get("x")), float(t.get("y")))
                     for t in g.iter() if _strip(t.tag) == "text"]
            rect = next((c for c in g if _strip(c.tag) == "rect"), None)
            if rect is not None:
                x, y = float(rect.get("x")), float(rect.get("y"))
                nodes.append(Shape("rect", x, y, x + float(rect.get("width")),
                                   y + float(rect.get("height")), texts))
            else:
                poly = next(c for c in g if _strip(c.tag) == "polygon")
                pts = [tuple(float(v) for v in p.split(","))
                       for p in poly.get("points").split()]
                xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
                nodes.append(Shape("diamond", min(xs), min(ys), max(xs), max(ys), texts))
        elif cls == "edge-label":
            r = next(c for c in g if _strip(c.tag) == "rect")
            x, y = float(r.get("x")), float(r.get("y"))
            labels.append(Shape("label", x, y, x + float(r.get("width")),
                                y + float(r.get("height")), []))
    return vb, nodes, labels


def _overlap(a: Shape, b: Shape, pad: float = 0.0) -> bool:
    return not (a.x1 + pad <= b.x0 or b.x1 + pad <= a.x0
                or a.y1 + pad <= b.y0 or b.y1 + pad <= a.y0)


# ── the corpus ─────────────────────────────────────────────────────────────

CORPUS: dict[str, dict] = {
    "linear": {
        "nodes": [
            {"id": "a", "role": "entry", "label": "User SAVES"},
            {"id": "b", "role": "code", "ref": "ProposalService:154",
             "method": "validateDocumentsSelection(orders)"},
            {"id": "c", "role": "success", "label": "saved"},
        ],
        "edges": [{"from": "a", "to": "b"}, {"from": "b", "to": "c"}],
    },
    # The diagram from the bug report: three outcomes on one row, long edge
    # labels, a decision node carrying both a question and a code ref.
    "three_way_branch": {
        "nodes": [
            {"id": "a", "role": "entry", "label": "LifeCycleData",
             "sub": "status, workflowType"},
            {"id": "b", "role": "call", "ref": "LifeCycleTaskRepository:52",
             "method": "getTasksForStatus(orgId, status, type)"},
            {"id": "c", "role": "code", "label": "tasks for this status"},
            {"id": "d", "role": "code", "label": "filter by Visibility",
             "sub": "internal / external"},
            {"id": "e", "role": "decision", "label": "Precondition outcome?",
             "ref": "ProposalLifecycleActionsService:164"},
            {"id": "f", "role": "code", "label": "dropped from menu"},
            {"id": "g", "role": "code", "label": "shown, enabled=false",
             "sub": "+ tooltip key"},
            {"id": "h", "role": "success", "label": "shown, enabled=true"},
            {"id": "i", "role": "success", "label": "action menu",
             "method": "TaskView / status-actions"},
        ],
        "edges": [
            {"from": "a", "to": "b"}, {"from": "b", "to": "c"},
            {"from": "c", "to": "d"}, {"from": "d", "to": "e"},
            {"from": "e", "to": "f", "label": "DISABLE_AND_HIDE"},
            {"from": "e", "to": "g", "label": "DISABLE"},
            {"from": "e", "to": "h", "label": "NONE"},
            {"from": "g", "to": "i"}, {"from": "h", "to": "i"},
        ],
    },
    "long_identifiers": {
        "nodes": [
            {"id": "a", "role": "entry",
             "label": "an unusually long node label that has to wrap somewhere"},
            {"id": "b", "role": "code",
             "method": "aVeryLongMethodNameThatCannotBeBrokenMidToken(argument, other)"},
            {"id": "c", "role": "error", "label": "throw",
             "method": "MissingRequiredDocumentsException"},
        ],
        "edges": [{"from": "a", "to": "b"}, {"from": "b", "to": "c"}],
    },
    "skip_edge": {
        "nodes": [
            {"id": "a", "role": "entry", "label": "start"},
            {"id": "b", "role": "decision", "label": "cached?"},
            {"id": "c", "role": "call", "method": "fetchRemote()"},
            {"id": "d", "role": "code", "label": "decode"},
            {"id": "e", "role": "success", "label": "done"},
        ],
        "edges": [
            {"from": "a", "to": "b"},
            {"from": "b", "to": "c", "label": "miss"},
            {"from": "c", "to": "d"}, {"from": "d", "to": "e"},
            {"from": "b", "to": "e", "label": "hit"},
        ],
    },
    "wide_fan": {
        "nodes": [{"id": "a", "role": "decision", "label": "route?"}] + [
            {"id": f"n{i}", "role": "code", "label": f"handler {i}"} for i in range(5)
        ],
        "edges": [{"from": "a", "to": f"n{i}", "label": f"case{i}"} for i in range(5)],
    },
    "single_node": {"nodes": [{"id": "a", "role": "entry", "label": "only"}], "edges": []},
}


def _random_spec(rng: random.Random) -> dict:
    n = rng.randint(2, 12)
    roles = ["entry", "code", "call", "decision", "success", "error"]
    words = ["save", "validate order", "LifeCycleTaskRepository:52", "tasks",
             "getTasksForStatus(orgId)", "throw", "ok?", "a much longer label here"]
    nodes = [{"id": f"n{i}", "role": rng.choice(roles), "label": rng.choice(words)}
             for i in range(n)]
    for nd in nodes:
        if rng.random() < 0.4:
            nd["method"] = rng.choice(words)
        if rng.random() < 0.3:
            nd["sub"] = rng.choice(words)
    edges = []
    for i in range(1, n):  # only forward edges, so it is always a DAG
        for j in range(i):
            if rng.random() < 0.35:
                edges.append({"from": f"n{j}", "to": f"n{i}",
                              "label": rng.choice(["yes", "no", "TIMED_OUT", ""])})
    return {"nodes": nodes, "edges": edges}


ALL_CASES = [(name, spec) for name, spec in CORPUS.items()]
ALL_CASES += [(f"random{i}", _random_spec(random.Random(i))) for i in range(40)]
IDS = [name for name, _ in ALL_CASES]


# ── invariants ─────────────────────────────────────────────────────────────

@pytest.mark.parametrize("name,spec", ALL_CASES, ids=IDS)
def test_nodes_never_overlap(name, spec):
    _, nodes, _ = parse(render(spec, "section-1"))
    for i, a in enumerate(nodes):
        for b in nodes[i + 1:]:
            assert not _overlap(a, b, PAD), f"{name}: node boxes {a.box} / {b.box} overlap"


@pytest.mark.parametrize("name,spec", ALL_CASES, ids=IDS)
def test_text_fits_inside_its_shape(name, spec):
    _, nodes, _ = parse(render(spec, "section-1"))
    for s in nodes:
        cx, cy = (s.x0 + s.x1) / 2, (s.y0 + s.y1) / 2
        hw, hh = (s.x1 - s.x0) / 2, (s.y1 - s.y0) / 2
        for cls, txt, x, base in s.texts:
            if not txt:
                continue
            w = text_px(txt, cls)
            top, bot = base - line_h(cls) * 0.76, base + line_h(cls) * 0.24
            for px in (x - w / 2, x + w / 2):
                for py in (top, bot):
                    if s.kind == "rect":
                        inside = s.x0 <= px <= s.x1 and s.y0 <= py <= s.y1
                    else:  # diamond: |dx|/hw + |dy|/hh <= 1
                        inside = abs(px - cx) / hw + abs(py - cy) / hh <= 1.001
                    assert inside, f"{name}: {txt!r} escapes its {s.kind} at ({px:.1f},{py:.1f})"


@pytest.mark.parametrize("name,spec", ALL_CASES, ids=IDS)
def test_edge_labels_clear_of_nodes_and_each_other(name, spec):
    _, nodes, labels = parse(render(spec, "section-1"))
    for i, lb in enumerate(labels):
        for nd in nodes:
            assert not _overlap(lb, nd), f"{name}: edge label {lb.box} sits on node {nd.box}"
        for other in labels[i + 1:]:
            assert not _overlap(lb, other), f"{name}: edge labels {lb.box} / {other.box} collide"


@pytest.mark.parametrize("name,spec", ALL_CASES, ids=IDS)
def test_everything_inside_the_viewbox(name, spec):
    vb, nodes, labels = parse(render(spec, "section-1"))
    _, _, vw, vh = vb
    for s in nodes + labels:
        assert s.x0 >= -0.5 and s.x1 <= vw + 0.5, f"{name}: {s.box} outside width {vw}"
        assert s.y0 >= -0.5 and s.y1 <= vh + 0.5, f"{name}: {s.box} outside height {vh}"
