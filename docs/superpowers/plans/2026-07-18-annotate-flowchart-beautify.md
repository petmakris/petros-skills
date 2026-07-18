# Annotate Beautiful Flowchart Block Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a first-class `kind: "flowchart"` annotate block — a pure-Python, spec-driven SVG generator (twin of `sequence.py`) that renders beautiful, role-colored flowcharts with jump-to-source links and per-node comment targets, replacing the raw-Mermaid flowchart.

**Architecture:** Author supplies a structured spec (`nodes` + `edges` + per-node `role`), never Mermaid source. A pure layout module assigns layers (longest-path) and positions; a renderer emits SVG with CSS-class coloring and `data-node-id` hit targets. `server.py` dispatches the new kind with the same error-pill fallback as sequence/diagram; `script.js` wires per-node clicks; `diagram.css` paints roles. Mermaid `kind:"diagram"` stays for `architecture/state/er/class`.

**Tech Stack:** Python 3 (stdlib only — `html.escape`, `collections.deque`), pytest, vanilla JS, CSS custom properties.

## Global Constraints

- Pure functions, no I/O in `diagrams/*.py` (match `sequence.py` header contract).
- Stdlib only — no new dependencies; no `mmdc` for this path.
- Colors live in CSS classes (`.annotate-flow .node-<role>`), not inline fills — mirror `sequence.py`, which only hardcodes arrowhead marker fills.
- Light theme only (page has no dark mode).
- Role palette (verbatim): entry `#0071e3`, code/call `#5b6472`, decision `#b45309`, success `#047857`, error `#c0392b`; fills = `color-mix(in srgb, <role> 10%, white)`, strokes = `color-mix(in srgb, <role> 55%, white)`.
- Fonts: body `'Bricolage Grotesque'` (inherited), code `'Monaspace Radon', ui-monospace, monospace`.
- Unknown/omitted `role` → neutral slate (same as code) — never error.
- Tests run from repo root: `python -m pytest skills/annotate/tests/<file> -v`.

---

### Task 1: Flowchart spec validator

**Files:**
- Create: `skills/annotate/diagrams/flowchart.py`
- Test: `skills/annotate/tests/test_diagrams_flowchart.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `class ValidationError(ValueError)`; `def validate(spec: dict) -> None` — raises `ValidationError` on: 0 nodes; duplicate node id; edge endpoint referencing an unknown node id; a cycle in the edge graph. Tolerates unknown/omitted `role`.

- [ ] **Step 1: Write the failing tests**

```python
# skills/annotate/tests/test_diagrams_flowchart.py
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest skills/annotate/tests/test_diagrams_flowchart.py -v`
Expected: FAIL — `ImportError: cannot import name 'validate' from ... flowchart` (module absent).

- [ ] **Step 3: Write minimal implementation**

```python
# skills/annotate/diagrams/flowchart.py
"""Flowchart spec validator + server-side SVG renderer.

Pure functions, no I/O. Called by server.py when rendering a block
with kind == "flowchart". No Mermaid, no external process.
"""
from __future__ import annotations

from collections import deque
from html import escape as _esc
from typing import Any


class ValidationError(ValueError):
    """Raised when a flowchart spec violates a structural rule."""


def validate(spec: dict[str, Any]) -> None:
    """Raise ValidationError if the spec is malformed; otherwise return None."""
    nodes = spec.get("nodes") or []
    edges = spec.get("edges") or []

    if len(nodes) < 1:
        raise ValidationError("flowchart requires at least 1 node")

    ids: set[str] = set()
    for n in nodes:
        nid = n.get("id")
        if not nid:
            raise ValidationError("node id required")
        if nid in ids:
            raise ValidationError(f"duplicate node id: {nid!r}")
        ids.add(nid)

    children: dict[str, list[str]] = {nid: [] for nid in ids}
    indeg: dict[str, int] = {nid: 0 for nid in ids}
    for e in edges:
        src, dst = e.get("from"), e.get("to")
        if src not in ids:
            raise ValidationError(f"edge from unknown node {src!r}")
        if dst not in ids:
            raise ValidationError(f"edge to unknown node {dst!r}")
        children[src].append(dst)
        indeg[dst] += 1

    # cycle check via Kahn's algorithm
    q = deque([nid for nid in ids if indeg[nid] == 0])
    seen = 0
    ind = dict(indeg)
    while q:
        n = q.popleft(); seen += 1
        for c in children[n]:
            ind[c] -= 1
            if ind[c] == 0:
                q.append(c)
    if seen != len(ids):
        raise ValidationError("flowchart edges form a cycle (must be a DAG)")


def render(spec: dict[str, Any], block_id: str) -> str:  # filled in Task 3
    raise NotImplementedError
```

- [ ] **Step 4: Run tests to verify validator tests pass**

Run: `python -m pytest skills/annotate/tests/test_diagrams_flowchart.py -k validate -v`
Expected: PASS (7 validate tests). Render tests are added in Task 3.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/diagrams/flowchart.py skills/annotate/tests/test_diagrams_flowchart.py
git commit -m "feat(annotate): flowchart spec validator"
```

---

### Task 2: Layout engine (layers + positions)

**Files:**
- Create: `skills/annotate/diagrams/flowchart_layout.py`
- Test: `skills/annotate/tests/test_flowchart_layout.py`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - Constants: `CONTENT_W=820`, `MARGIN_X=20`, `TOP=30`, `NODE_W=300`, `NODE_H1=46`, `NODE_H2=62`, `DIAMOND_HW=92`, `DIAMOND_HH=44`, `ROW_GAP=58`.
  - `def line_count(node: dict) -> int` — number of text lines a node renders (min 1).
  - `def node_size(node: dict) -> tuple[int, int]` — `(w, h)`; decisions return the diamond bounding box.
  - `def assign_layers(node_ids: list[str], edges: list[dict]) -> dict[str, int]` — longest-path layer per node (roots = 0).
  - `def layout(nodes: list[dict], edges: list[dict]) -> tuple[dict[str, dict], int]` — returns `(positions, total_h)` where `positions[id] = {"cx","cy","w","h","role","node"}` (floats OK) and `total_h` is the SVG height.

- [ ] **Step 1: Write the failing tests**

```python
# skills/annotate/tests/test_flowchart_layout.py
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest skills/annotate/tests/test_flowchart_layout.py -v`
Expected: FAIL — `ModuleNotFoundError: skills.annotate.diagrams.flowchart_layout`.

- [ ] **Step 3: Write minimal implementation**

```python
# skills/annotate/diagrams/flowchart_layout.py
"""Layered top-down layout for flowchart specs. Pure geometry, no I/O.

Bounded graphs (≤ ~15 nodes) let a simple longest-path layering + even
horizontal distribution per layer read cleanly without a full crossing
minimizer.
"""
from __future__ import annotations

from collections import deque
from typing import Any

CONTENT_W = 820
MARGIN_X = 20
TOP = 30
NODE_W = 300
NODE_H1 = 46          # one text line
NODE_H2 = 62          # two+ text lines
DIAMOND_HW = 92       # half width
DIAMOND_HH = 44       # half height
ROW_GAP = 58          # vertical gap between layer rows


def line_count(node: dict[str, Any]) -> int:
    c = 0
    for k in ("label", "ref", "method", "sub"):
        if node.get(k):
            c += 1
    return max(1, c)


def node_size(node: dict[str, Any]) -> tuple[int, int]:
    if node.get("role") == "decision":
        return (DIAMOND_HW * 2, DIAMOND_HH * 2)
    return (NODE_W, NODE_H2 if line_count(node) >= 2 else NODE_H1)


def assign_layers(node_ids: list[str], edges: list[dict[str, Any]]) -> dict[str, int]:
    children: dict[str, list[str]] = {n: [] for n in node_ids}
    indeg: dict[str, int] = {n: 0 for n in node_ids}
    for e in edges:
        children[e["from"]].append(e["to"])
        indeg[e["to"]] += 1
    layer: dict[str, int] = {n: 0 for n in node_ids}
    q = deque([n for n in node_ids if indeg[n] == 0])
    ind = dict(indeg)
    while q:
        n = q.popleft()
        for c in children[n]:
            layer[c] = max(layer[c], layer[n] + 1)
            ind[c] -= 1
            if ind[c] == 0:
                q.append(c)
    return layer


def layout(nodes: list[dict[str, Any]], edges: list[dict[str, Any]]):
    node_ids = [n["id"] for n in nodes]
    layer = assign_layers(node_ids, edges)

    by_layer: dict[int, list[dict[str, Any]]] = {}
    for n in nodes:  # author order preserved within a layer
        by_layer.setdefault(layer[n["id"]], []).append(n)

    positions: dict[str, dict[str, Any]] = {}
    y = TOP
    max_layer = max(layer.values()) if layer else 0
    for L in range(max_layer + 1):
        row = by_layer.get(L, [])
        if not row:
            continue
        rh = max(node_size(n)[1] for n in row)
        k = len(row)
        span = CONTENT_W - 2 * MARGIN_X
        for i, n in enumerate(row):
            w, h = node_size(n)
            cx = MARGIN_X + (i + 0.5) * (span / k)
            positions[n["id"]] = {
                "cx": cx, "cy": y + rh / 2, "w": w, "h": h,
                "role": n.get("role"), "node": n,
            }
        y += rh + ROW_GAP
    total_h = y - ROW_GAP + TOP
    return positions, total_h
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest skills/annotate/tests/test_flowchart_layout.py -v`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/diagrams/flowchart_layout.py skills/annotate/tests/test_flowchart_layout.py
git commit -m "feat(annotate): flowchart layered layout engine"
```

---

### Task 3: SVG renderer

**Files:**
- Modify: `skills/annotate/diagrams/flowchart.py` (replace the `render` stub)
- Test: `skills/annotate/tests/test_diagrams_flowchart.py` (add render tests)

**Interfaces:**
- Consumes: `validate` (Task 1); `layout`, `node_size` (Task 2).
- Produces: `def render(spec: dict, block_id: str) -> str` — returns an `<svg class="annotate-flow">` string containing, per node, a `<g class="node node-<role>" data-block-id data-node-id>`; rounded `<rect>` for boxes / `<polygon>` for decisions; text lines with classes `flow-label` / `flow-sub` / `flow-ref` / `flow-method`; a `ref` (or `label` when no `ref`) wrapped in `<a href>` when the node has `href`; edges as `<path class="flow-edge">` with a shared arrowhead marker and optional `<g class="edge-label">` pill.

- [ ] **Step 1: Write the failing render tests**

```python
# append to skills/annotate/tests/test_diagrams_flowchart.py

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
```

- [ ] **Step 2: Run render tests to verify they fail**

Run: `python -m pytest skills/annotate/tests/test_diagrams_flowchart.py -k render -v`
Expected: FAIL — `NotImplementedError` from the `render` stub.

- [ ] **Step 3: Implement the renderer**

Replace the `render` stub in `skills/annotate/diagrams/flowchart.py` with:

```python
from .flowchart_layout import CONTENT_W, DIAMOND_HW, DIAMOND_HH, layout

_KNOWN_ROLES = {"entry", "code", "call", "decision", "success", "error"}


def _role_class(node: dict[str, Any]) -> str:
    role = node.get("role")
    return f"node-{role}" if role in _KNOWN_ROLES else "node-code"


def _defs() -> str:
    return (
        '<defs><marker id="fc-arrow" viewBox="0 0 10 10" refX="8" refY="5" '
        'markerWidth="7" markerHeight="7" orient="auto">'
        '<path d="M0,0 L10,5 L0,10 z" fill="#9aa3b0"/></marker></defs>'
    )


def _text_lines(node: dict[str, Any], cx: float, cy: float) -> str:
    # ordered lines: label, ref(link), method, sub
    lines: list[tuple[str, str]] = []
    if node.get("label"):
        cls = "flow-label-error" if node.get("role") == "error" else "flow-label"
        lines.append((cls, node["label"]))
    if node.get("ref"):
        lines.append(("flow-ref", node["ref"]))
    if node.get("method"):
        lines.append(("flow-method", node["method"]))
    if node.get("sub"):
        lines.append(("flow-sub", node["sub"]))
    if not lines:
        lines.append(("flow-label", ""))

    n = len(lines)
    start = cy - (n * 17) / 2 + 13
    href = node.get("href")
    out: list[str] = []
    for i, (cls, txt) in enumerate(lines):
        y = start + i * 17
        el = (f'<text class="{cls}" x="{cx:.1f}" y="{y:.1f}" '
              f'text-anchor="middle">{_esc(txt)}</text>')
        # wrap the primary link line (ref, else the first label) in <a>
        if href and (cls == "flow-ref" or (cls == "flow-label" and not node.get("ref") and i == 0)):
            el = f'<a href="{_esc(href, quote=True)}">{el}</a>'
        out.append(el)
    return "".join(out)


def _node_svg(pos: dict[str, Any], block_id: str) -> str:
    node = pos["node"]
    cx, cy, w, h = pos["cx"], pos["cy"], pos["w"], pos["h"]
    cls = _role_class(node)
    parts = [
        f'<g class="node {cls}" data-block-id="{_esc(block_id, quote=True)}" '
        f'data-node-id="{_esc(node["id"], quote=True)}">'
    ]
    if node.get("role") == "decision":
        pts = (f'{cx:.1f},{cy - DIAMOND_HH:.1f} {cx + DIAMOND_HW:.1f},{cy:.1f} '
               f'{cx:.1f},{cy + DIAMOND_HH:.1f} {cx - DIAMOND_HW:.1f},{cy:.1f}')
        parts.append(f'<polygon class="node-shape" points="{pts}"/>')
    else:
        x = cx - w / 2
        y = cy - h / 2
        parts.append(f'<rect class="node-shape" x="{x:.1f}" y="{y:.1f}" '
                     f'width="{w}" height="{h}" rx="12"/>')
    parts.append(_text_lines(node, cx, cy))
    parts.append('</g>')
    return "".join(parts)


def _edge_svg(src: dict[str, Any], dst: dict[str, Any], label: str) -> str:
    x1, y1 = src["cx"], src["cy"] + src["h"] / 2
    x2, y2 = dst["cx"], dst["cy"] - dst["h"] / 2
    end_y = y2 - 8
    if abs(x1 - x2) < 1:
        d = f'M{x1:.1f},{y1:.1f} L{x2:.1f},{end_y:.1f}'
    else:
        my = (y1 + y2) / 2
        d = (f'M{x1:.1f},{y1:.1f} C{x1:.1f},{my:.1f} '
             f'{x2:.1f},{my:.1f} {x2:.1f},{end_y:.1f}')
    out = [f'<path class="flow-edge" d="{d}" marker-end="url(#fc-arrow)"/>']
    if label:
        lx, ly = (x1 + x2) / 2, (y1 + y2) / 2
        tw = 8 + len(label) * 6.4
        out.append(
            f'<g class="edge-label"><rect x="{lx - tw / 2:.1f}" y="{ly - 11:.1f}" '
            f'width="{tw:.1f}" height="20" rx="10"/>'
            f'<text x="{lx:.1f}" y="{ly + 3:.1f}" text-anchor="middle">{_esc(label)}</text></g>'
        )
    return "".join(out)


def render(spec: dict[str, Any], block_id: str) -> str:
    """Render a validated flowchart spec to an SVG string with hit-target IDs."""
    validate(spec)
    nodes = spec["nodes"]
    edges = spec.get("edges") or []
    positions, total_h = layout(nodes, edges)

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {CONTENT_W} {total_h}" '
        f'class="annotate-flow">',
        _defs(),
    ]
    # edges first (under nodes)
    for e in edges:
        parts.append(_edge_svg(positions[e["from"]], positions[e["to"]], e.get("label", "")))
    for n in nodes:
        parts.append(_node_svg(positions[n["id"]], block_id))
    parts.append("</svg>")
    return "".join(parts)
```

- [ ] **Step 4: Run all flowchart tests to verify they pass**

Run: `python -m pytest skills/annotate/tests/test_diagrams_flowchart.py skills/annotate/tests/test_flowchart_layout.py -v`
Expected: PASS (all validate + layout + render tests).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/diagrams/flowchart.py skills/annotate/tests/test_diagrams_flowchart.py
git commit -m "feat(annotate): flowchart SVG renderer with node hit targets and jump-links"
```

---

### Task 4: Server dispatch for `kind: "flowchart"`

**Files:**
- Modify: `skills/annotate/server.py` (import near line 23-24; add branch in `_render_block_for_raw`, after the `sequence` branch, before `diagram`)
- Test: `skills/annotate/tests/test_server_flowchart.py`

**Interfaces:**
- Consumes: `render` (Task 3).
- Produces: `_render_block_for_raw({"id","kind":"flowchart","spec":{...}}, version)` returns a dict with `base["kind"]=="flowchart"`, `base["spec"]`, and `base["svg"]` (rendered SVG, or a compact error-pill SVG on any exception).

- [ ] **Step 1: Write the failing tests**

```python
# skills/annotate/tests/test_server_flowchart.py
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest skills/annotate/tests/test_server_flowchart.py -v`
Expected: FAIL — flowchart branch missing, so the block falls through to the default markdown/other path and has no `svg` (KeyError / assertion fails).

- [ ] **Step 3: Add the import**

In `skills/annotate/server.py`, next to the existing diagram imports (around line 23-24, after `from ...diagrams.sequence import render`):

```python
from ...diagrams.flowchart import render as render_flowchart
```

(Match the exact relative-import depth of the sibling `sequence`/`mermaid` imports already in the file.)

- [ ] **Step 4: Add the dispatch branch**

In `_render_block_for_raw`, immediately after the `if kind == "sequence":` block and before `elif kind == "diagram":`, insert:

```python
    elif kind == "flowchart":
        spec = blk.get("spec") or {}
        try:
            svg = render_flowchart(spec, block_id=blk["id"])
        except Exception as e:
            # Compact inline error pill — one malformed block must never
            # crash /raw and blank the page (same pattern as sequence/diagram).
            svg = (
                f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 360 36" '
                f'class="annotate-flow annotate-flow-error" '
                f'data-block-id="{html_escape(blk["id"])}" '
                f'role="img" aria-label="flowchart failed to render">'
                f'<rect x="0" y="0" width="360" height="36" rx="6" '
                f'fill="#fde7e2" stroke="#e5b8af"/>'
                f'<text x="14" y="22" font-size="12" font-weight="600" '
                f'fill="#c1432f" font-family="ui-monospace, monospace">'
                f'⚠ diagram render failed</text>'
                f'<title>{html_escape(str(e))}</title>'
                f'</svg>'
            )
        base["spec"] = spec
        base["svg"] = svg
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `python -m pytest skills/annotate/tests/test_server_flowchart.py -v`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server_flowchart.py
git commit -m "feat(annotate): dispatch kind=flowchart in _render_block_for_raw"
```

---

### Task 5: CSS for `.annotate-flow`

**Files:**
- Modify: `skills/annotate/static/diagram.css` (add a block after the `.annotate-seq` rules, before the `.annotate-diagram` Mermaid rules)

**Interfaces:**
- Consumes: CSS custom props from `core.css` (`--accent`, `--surface-soft`, `--border`, `--text`, `--text-strong`, `--text-dim`).
- Produces: styling for classes emitted by Task 3 (`.node-<role> .node-shape`, `.flow-label`, `.flow-label-error`, `.flow-sub`, `.flow-ref`, `.flow-method`, `.flow-edge`, `.edge-label`), plus per-node hover / `data-card-focus` / `data-engaged-type` states.

- [ ] **Step 1: Add the CSS**

Insert into `skills/annotate/static/diagram.css`:

```css
/* ── flowchart block (kind: "flowchart") — hand-built SVG, Direction B ── */
.annotate-flow { display: block; width: 100%; height: auto; }

.annotate-flow .node-shape { stroke-width: 1.3; filter: drop-shadow(0 1.5px 2.5px rgba(29,29,31,.10)); }

/* role fills = 10% tint over white; strokes = 55% */
.annotate-flow .node-entry    .node-shape { fill: color-mix(in srgb,#0071e3 10%,white); stroke: color-mix(in srgb,#0071e3 55%,white); }
.annotate-flow .node-code     .node-shape,
.annotate-flow .node-call     .node-shape { fill: color-mix(in srgb,#5b6472 10%,white); stroke: color-mix(in srgb,#5b6472 55%,white); }
.annotate-flow .node-decision .node-shape { fill: color-mix(in srgb,#b45309 10%,white); stroke: #b45309; }
.annotate-flow .node-success  .node-shape { fill: color-mix(in srgb,#047857 10%,white); stroke: color-mix(in srgb,#047857 55%,white); }
.annotate-flow .node-error    .node-shape { fill: color-mix(in srgb,#c0392b 10%,white); stroke: color-mix(in srgb,#c0392b 55%,white); }

.annotate-flow .flow-label       { fill: var(--text-strong); font-size: 13.5px; font-weight: 600; font-family: inherit; }
.annotate-flow .flow-label-error { fill: #c0392b; font-size: 13px; font-weight: 700; font-family: inherit; }
.annotate-flow .flow-sub         { fill: var(--text-dim); font-size: 11px; font-style: italic; }
.annotate-flow .flow-ref         { fill: var(--accent); font-size: 12px; font-weight: 600;
                                   font-family: 'Monaspace Radon', ui-monospace, monospace; text-decoration: underline; }
.annotate-flow .flow-method      { fill: var(--text); font-size: 11.5px;
                                   font-family: 'Monaspace Radon', ui-monospace, monospace; }
.annotate-flow a { cursor: pointer; }

.annotate-flow .flow-edge        { fill: none; stroke: #9aa3b0; stroke-width: 1.6; }
.annotate-flow .edge-label rect  { fill: var(--surface-soft); stroke: var(--border); }
.annotate-flow .edge-label text  { fill: var(--text-dim); font-size: 11px; font-weight: 600; font-family: inherit; }

/* per-node interactivity — parity with .annotate-seq .step-row */
.annotate-flow .node { cursor: pointer; }
.annotate-flow .node:hover .node-shape { stroke-width: 2.2; }
.annotate-flow .node[data-card-focus] .node-shape { stroke-width: 2.4; }
.annotate-flow .node[data-engaged-type="comment"] .node-shape { stroke: var(--type-comment-fg); }
.annotate-flow .node[data-engaged-type="reject"]  .node-shape { stroke: var(--type-reject-fg); }
```

- [ ] **Step 2: Verify the file parses (no syntax typos)**

Run: `python -c "import pathlib,re; s=pathlib.Path('skills/annotate/static/diagram.css').read_text(); assert s.count('{')==s.count('}'), 'brace mismatch'; assert '.annotate-flow' in s; print('ok')"`
Expected: `ok`

- [ ] **Step 3: Commit**

```bash
git add skills/annotate/static/diagram.css
git commit -m "feat(annotate): Direction-B styling for .annotate-flow"
```

---

### Task 6: Client wiring in `script.js`

**Files:**
- Modify: `skills/annotate/static/script.js` (add a `kind === "flowchart"` branch alongside the existing `sequence`/`diagram` branches, ~line 577-594)

**Interfaces:**
- Consumes: `blk.svg` (Task 4), `onHoverAction(section, "comment", ev)` (existing), the `data-node-id` targets (Task 3).
- Produces: per-node click → `onHoverAction` (comment scoped to the clicked node via `event.target.closest("[data-node-id]")`); in-page anchor clicks (`href^="#"`) scroll to the target block instead of navigating.

- [ ] **Step 1: Add the branch**

In `skills/annotate/static/script.js`, add immediately after the `else if (kind === "diagram") { ... }` block:

```javascript
    } else if (kind === "flowchart") {
      // Server pre-rendered the hand-built SVG; inject as-is (trusted server
      // output — bypasses sanitizeFreeHtml so class/data-* attrs survive).
      content.innerHTML = blk.svg || "";
      // Any click inside the SVG opens a comment scoped to the node that was
      // clicked; onHoverAction reads node_id from
      // event.target.closest("[data-node-id]"). In-page anchor links
      // (cross-block, href="#...") scroll instead of commenting.
      content.addEventListener("click", (ev) => {
        const anchor = ev.target.closest && ev.target.closest('a[href^="#"]');
        if (anchor) {
          ev.preventDefault();
          const target = document.querySelector(
            `[data-block-id="${anchor.getAttribute("href").slice(1)}"]`
          );
          if (target) target.scrollIntoView({ behavior: "smooth", block: "center" });
          return;
        }
        onHoverAction(section, "comment", ev);
      });
```

- [ ] **Step 2: Verify `onHoverAction` reads `data-node-id`**

Search `script.js` for how `onHoverAction` derives the scoped id. It currently reads `data-step-id` for sequence. Confirm it also reads `data-node-id`; if it only handles `data-step-id`, extend that lookup.

Run: `grep -n "data-step-id\|data-node-id\|step_id\|node_id" skills/annotate/static/script.js`
Expected: locate the `closest("[data-step-id]")` call. If `data-node-id` is not handled there, update it to also match node ids, e.g.:

```javascript
// inside onHoverAction, where scoped id is derived:
const hit = ev && ev.target && ev.target.closest &&
  ev.target.closest("[data-step-id],[data-node-id]");
const scopedId = hit
  ? (hit.getAttribute("data-step-id") || hit.getAttribute("data-node-id"))
  : null;
```

(Keep the existing variable/field name the comment payload uses; the server-side comment record already carries `step_id`/`node_id` semantics from the sequence path — reuse the same field so no schema change is needed.)

- [ ] **Step 3: Manual smoke in the browser**

Start the annotate server on a blocks file containing one `flowchart` block (see Task 8 fixture), open the page, confirm: nodes render with Direction-B colors; clicking a node opens a comment; clicking a `ref` jump-link fires the `href` (jetbrains or `#anchor`).

Run: `bash skills/annotate/ensure_server.sh` (then open the printed URL)
Expected: flowchart renders; node click → comment popover; no console errors.

- [ ] **Step 4: Commit**

```bash
git add skills/annotate/static/script.js
git commit -m "feat(annotate): wire per-node clicks + anchor scroll for flowchart blocks"
```

---

### Task 7: Authoring docs & references

**Files:**
- Create: `skills/annotate/references/block-kinds/flowchart.md`
- Modify: `skills/annotate/references/block-kinds/diagram.md` (deprecate `type:"flowchart"`)
- Modify: `skills/annotate/SKILL.md` (kind menu → route flows to `flowchart`)
- Modify: `skills/annotate/references/handling-events.md` (flowchart rewrite contract)

**Interfaces:**
- Consumes: the spec schema from Task 1/3.
- Produces: authoring guidance the agent reads to emit `kind:"flowchart"` blocks.

- [ ] **Step 1: Write `flowchart.md`**

Create `skills/annotate/references/block-kinds/flowchart.md` documenting: when to use flowchart (branching/decision/process flows) vs sequence (temporal actor↔actor) vs diagram (architecture/state/er/class); the exact spec shape; the `role` values and their meaning/color; node fields (`label`/`ref`/`method`/`sub`/`href`); edge fields; the `href` targets (`jetbrains://…` for jump-to-source, `#<block-id>` for cross-block anchor); and the guidance "prefer a structured node over cramming text; keep ≤ ~15 nodes". Include this canonical example:

```json
{"id": "section-N", "kind": "flowchart", "spec": {
  "title": "Both actions funnel into one guard",
  "nodes": [
    {"id": "a", "role": "entry",    "label": "User SAVES / edits orders"},
    {"id": "c", "role": "entry",    "label": "User SHARES", "sub": "VALIDATE lifecycle action"},
    {"id": "b", "role": "code",     "ref": "ProposalService:154", "method": "validateDocumentsSelection(orders)",
                "href": "jetbrains://idea/navigate/reference?project=proj&path=/abs/ProposalService.java:154"},
    {"id": "d", "role": "code",     "ref": "LifecycleActionsExecutor:129", "method": "validateProposal(proposal)"},
    {"id": "e", "role": "call",     "method": "validateRequiredDocuments(...)"},
    {"id": "f", "role": "decision", "label": "toggle ON?"},
    {"id": "g", "role": "success",  "label": "allow", "sub": "no check"},
    {"id": "h", "role": "error",    "label": "throw", "method": "MissingRequiredDocumentsException"}
  ],
  "edges": [
    {"from": "a", "to": "b"}, {"from": "c", "to": "d"},
    {"from": "b", "to": "e"}, {"from": "d", "to": "e"}, {"from": "e", "to": "f"},
    {"from": "f", "to": "g", "label": "OFF"}, {"from": "f", "to": "h", "label": "ON + doc missing"}
  ]
}}
```

State: flowchart blocks have per-node hit targets; comments arrive scoped to `node_id`.

- [ ] **Step 2: Deprecate `type:"flowchart"` in `diagram.md`**

In `skills/annotate/references/block-kinds/diagram.md`, change the flowchart bullet (line ~11) and the "When … right" section to direct flowcharts to the new kind. Replace the `flowchart` list item with:

```markdown
- **flowchart** — DEPRECATED here. Use the first-class `kind: "flowchart"`
  block (structured nodes/edges, role color, jump-to-source links, per-node
  comments). See `references/block-kinds/flowchart.md`. `kind: "diagram"` now
  covers architecture / state / er / class only.
```

Leave the Mermaid authoring rules for the remaining four families intact.

- [ ] **Step 3: Update the kind menu in `SKILL.md`**

Find the block-kind menu in `skills/annotate/SKILL.md` and route branching/decision/process-flow content to `flowchart` (not `diagram`). Add a one-line entry mirroring the sequence entry's phrasing.

Run: `grep -n "sequence\|diagram\|flowchart" skills/annotate/SKILL.md`
Expected: locate the kind menu; add/adjust the flowchart line.

- [ ] **Step 4: Update `handling-events.md` rewrite contract**

In `skills/annotate/references/handling-events.md`, add a "Flowchart block-rewrite contract" note next to the diagram one: flowchart comments carry `node_id`; rewrite the node or whole spec via `blocks.update_spec_block`.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/references/block-kinds/flowchart.md \
        skills/annotate/references/block-kinds/diagram.md \
        skills/annotate/SKILL.md \
        skills/annotate/references/handling-events.md
git commit -m "docs(annotate): flowchart authoring contract; deprecate mermaid flowchart type"
```

---

### Task 8: End-to-end smoke test

**Files:**
- Create: `skills/annotate/tests/test_smoke_e2e_flowchart.py` (mirror `test_smoke_e2e_diagram.py`)

**Interfaces:**
- Consumes: the full `/raw` render path through `_render_block_for_raw` (Task 4).
- Produces: a regression test that a realistic multi-node flowchart (the canonical funnel + decision + two terminals) renders to a well-formed SVG with all node hit targets and a jump-link.

- [ ] **Step 1: Read the existing diagram smoke test to match structure**

Run: `sed -n '1,60p' skills/annotate/tests/test_smoke_e2e_diagram.py`
Expected: see how it invokes the render path and asserts on the SVG.

- [ ] **Step 2: Write the smoke test**

```python
# skills/annotate/tests/test_smoke_e2e_flowchart.py
"""End-to-end: a realistic flowchart block renders through the /raw path."""
from skills.annotate.server import _render_block_for_raw

_SPEC = {
    "title": "Both actions funnel into one guard",
    "nodes": [
        {"id": "a", "role": "entry", "label": "User SAVES / edits orders"},
        {"id": "c", "role": "entry", "label": "User SHARES", "sub": "VALIDATE lifecycle action"},
        {"id": "b", "role": "code", "ref": "ProposalService:154",
         "method": "validateDocumentsSelection(orders)",
         "href": "jetbrains://idea/navigate/reference?project=p&path=/a/ProposalService.java:154"},
        {"id": "d", "role": "code", "ref": "LifecycleActionsExecutor:129",
         "method": "validateProposal(proposal)"},
        {"id": "e", "role": "call", "method": "validateRequiredDocuments(...)"},
        {"id": "f", "role": "decision", "label": "toggle ON?"},
        {"id": "g", "role": "success", "label": "allow", "sub": "no check"},
        {"id": "h", "role": "error", "label": "throw",
         "method": "MissingRequiredDocumentsException"},
    ],
    "edges": [
        {"from": "a", "to": "b"}, {"from": "c", "to": "d"},
        {"from": "b", "to": "e"}, {"from": "d", "to": "e"}, {"from": "e", "to": "f"},
        {"from": "f", "to": "g", "label": "OFF"},
        {"from": "f", "to": "h", "label": "ON + doc missing"},
    ],
}


def test_full_flowchart_renders():
    out = _render_block_for_raw(
        {"id": "section-5", "kind": "flowchart", "spec": _SPEC}, version=1
    )
    svg = out["svg"]
    assert svg.startswith("<svg") and svg.rstrip().endswith("</svg>")
    for nid in ("a", "b", "c", "d", "e", "f", "g", "h"):
        assert f'data-node-id="{nid}"' in svg
    assert "jetbrains://idea" in svg           # jump-to-source link
    assert "<polygon" in svg                    # decision diamond
    assert "OFF" in svg and "ON + doc missing" in svg  # edge labels
    assert "render failed" not in svg
```

- [ ] **Step 3: Run the smoke test**

Run: `python -m pytest skills/annotate/tests/test_smoke_e2e_flowchart.py -v`
Expected: PASS.

- [ ] **Step 4: Run the full annotate test suite**

Run: `python -m pytest skills/annotate/tests/ -v`
Expected: PASS — new flowchart tests green, no regression in sequence/diagram/other tests.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/tests/test_smoke_e2e_flowchart.py
git commit -m "test(annotate): e2e smoke for flowchart render path"
```

---

## Self-Review

**Spec coverage:**
- New `kind:"flowchart"` generator → Tasks 1-3. ✓
- Spec-driven authoring (nodes/edges/role) → Task 1 schema + Task 7 docs. ✓
- Direction B look / palette / fonts → Task 5. ✓
- Unknown-role neutral guard → Task 1 (tolerated) + Task 3 (`_role_class`) + tests. ✓
- Jump-to-source `href` + cross-block anchor → Task 3 (`<a>`) + Task 6 (anchor scroll). ✓
- Per-node `data-node-id` hit targets → Task 3 + Task 6 click wiring. ✓
- Server error-pill fallback → Task 4. ✓
- Layered layout → Task 2. ✓
- Mermaid retained for other families / `type:"flowchart"` deprecated alias → Task 7 (docs); server `diagram` branch untouched. ✓
- Tests mirroring sequence suite (validator, renderer golden-ish, layout, e2e) → Tasks 1,2,3,8. ✓

**Placeholder scan:** No TBD/TODO; every code step carries full code; commands have expected output. ✓

**Type consistency:** `validate`/`render` signatures identical across Tasks 1,3,4,8. `layout` returns `(positions, total_h)` consumed by `render` in Task 3. `node_size`/`assign_layers` names consistent Tasks 2↔3. `data-node-id` consistent Tasks 3↔6↔8. Role class `node-code` fallback consistent Task 3↔5↔test. ✓

**Note for executor:** Task 6 Step 2 requires reading the real `onHoverAction` implementation before editing — the exact variable names there win over the illustrative snippet if they differ.
