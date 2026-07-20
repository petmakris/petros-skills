"""Layered top-down layout for flowchart specs. Pure geometry, no I/O.

Three properties the earlier fixed-size version could not hold:

* **Nodes are sized from their text**, measured with the real advance widths of
  the fonts the page serves (``text_metrics``). Long labels wrap; code refs
  never wrap mid-token, the node widens instead.
* **Rows are packed by actual width**, so a row of three nodes cannot overlap —
  the canvas grows instead of the boxes colliding.
* **Within-layer order is chosen by barycenter sweeps**, so edges between
  layers cross as little as a one-pass heuristic can manage.
"""
from __future__ import annotations

from collections import deque
from typing import Any

from .text_metrics import line_h, text_px, wrap

MIN_CANVAS_W = 820    # keep small diagrams as wide as they used to be
MARGIN_X = 24
SIDE_GUTTER = 44      # extra side room reserved when a layer-skipping edge exists
TOP = 30
BOTTOM = 30
GAP_X = 34            # horizontal gap between nodes in a row
ROW_GAP = 76          # vertical gap between layer rows (holds an edge label)

PAD_X = 18            # node padding, text box to shape edge
PAD_Y = 12
MIN_NODE_W = 150
MIN_NODE_H = 46
WRAP_W = 300          # prose wrap width inside a rectangular node
WRAP_W_DECISION = 170  # tighter: a diamond costs ~2x its text box in width
MIN_DIAMOND_HH = 40

# ordered (spec key, css class, kind) for the text lines of a node
_LINE_SPECS = (
    ("label", None, "label"),
    ("ref", "flow-ref", "ref"),
    ("method", "flow-method", "method"),
    ("sub", "flow-sub", "sub"),
)
_WRAPPED = {"label", "sub"}  # prose wraps; code refs and signatures do not


def node_lines(node: dict[str, Any]) -> list[tuple[str, str, str]]:
    """Return the node's rendered text lines as ``(css_class, text, kind)``.

    Shared by the layout (which measures them) and the renderer (which draws
    them) so the two can never disagree about how many lines a node has.
    """
    wrap_w = WRAP_W_DECISION if node.get("role") == "decision" else WRAP_W
    out: list[tuple[str, str, str]] = []
    for key, cls, kind in _LINE_SPECS:
        txt = node.get(key)
        if not txt:
            continue
        if cls is None:  # label class depends on role
            cls = "flow-label-error" if node.get("role") == "error" else "flow-label"
        if kind in _WRAPPED:
            for part in wrap(txt, cls, wrap_w):
                out.append((cls, part, kind))
        else:
            out.append((cls, txt, kind))
    if not out:
        out.append(("flow-label", "", "label"))
    return out


def text_box(lines: list[tuple[str, str, str]]) -> tuple[float, float]:
    """Width and height in px of a node's stacked text lines."""
    w = max((text_px(txt, cls) for cls, txt, _ in lines), default=0.0)
    h = sum(line_h(cls) for cls, _, _ in lines)
    return w, h


def node_size(node: dict[str, Any]) -> tuple[float, float]:
    """Outer size of a node's shape, large enough to contain its text."""
    lines = node_lines(node)
    tw, th = text_box(lines)
    if node.get("role") == "decision":
        return _diamond_size(lines, th)
    return (max(MIN_NODE_W, tw + 2 * PAD_X), max(MIN_NODE_H, th + 2 * PAD_Y))


def _diamond_size(lines: list[tuple[str, str, str]], th: float) -> tuple[float, float]:
    """Half-axes for a diamond that contains its text, sized line by line.

    A diamond narrows towards the top and bottom, so the room available to a
    line depends on how far it sits from the vertical centre: at offset ``dy``
    the half-width is ``HW * (1 - |dy| / HH)``. Sizing from the text *bounding
    box* instead (the obvious ``w/2HW + h/2HH <= 1``) assumes the widest line
    lives at the widest point of both axes at once and makes the shape roughly
    70% wider than it needs to be.
    """
    hh = max(MIN_DIAMOND_HH, th / 2 + PAD_Y)
    y = -th / 2
    hw = 0.0
    for cls, txt, _ in lines:
        lh = line_h(cls)
        # worst case for this line is whichever of its edges is further out
        dy = max(abs(y), abs(y + lh))
        room = max(1 - dy / hh, 0.15)  # never divide by ~0 for a very tall line
        hw = max(hw, (text_px(txt, cls) / 2 + PAD_X) / room)
        y += lh
    return (2 * hw, 2 * hh)


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


def order_layers(by_layer: dict[int, list[str]], edges: list[dict[str, Any]],
                 sweeps: int = 4) -> None:
    """Reorder each layer in place by barycenter, to reduce edge crossings.

    Alternating down and up sweeps; author order breaks ties, so a spec with no
    crossings to fix comes out exactly as written.
    """
    preds: dict[str, list[str]] = {}
    succs: dict[str, list[str]] = {}
    for e in edges:
        succs.setdefault(e["from"], []).append(e["to"])
        preds.setdefault(e["to"], []).append(e["from"])

    layers = sorted(by_layer)
    for s in range(sweeps):
        seq = layers[1:] if s % 2 == 0 else layers[-2::-1]
        neigh = preds if s % 2 == 0 else succs
        for L in seq:
            idx = {n: i for i, n in enumerate(by_layer[L])}
            other = by_layer.get(L - 1 if s % 2 == 0 else L + 1, [])
            pos = {n: i for i, n in enumerate(other)}
            def bary(n: str) -> float:
                ns = [pos[p] for p in neigh.get(n, []) if p in pos]
                return sum(ns) / len(ns) if ns else float(idx[n])
            by_layer[L].sort(key=lambda n: (bary(n), idx[n]))


def layout(nodes: list[dict[str, Any]], edges: list[dict[str, Any]]):
    """Position every node. Returns ``(positions, canvas_w, canvas_h)``."""
    node_ids = [n["id"] for n in nodes]
    by_id = {n["id"]: n for n in nodes}
    layer = assign_layers(node_ids, edges)

    by_layer: dict[int, list[str]] = {}
    for nid in node_ids:  # author order is the tie-break for the barycenter sort
        by_layer.setdefault(layer[nid], []).append(nid)
    order_layers(by_layer, edges)

    sizes = {nid: node_size(by_id[nid]) for nid in node_ids}
    row_w = {
        L: sum(sizes[n][0] for n in row) + GAP_X * (len(row) - 1)
        for L, row in by_layer.items()
    }
    # An edge that skips a layer is routed down a side gutter (see the renderer),
    # so reserve the room for it before centring the rows.
    skips = any(layer[e["to"]] - layer[e["from"]] > 1 for e in edges)
    margin = MARGIN_X + (SIDE_GUTTER if skips else 0)
    canvas_w = max(MIN_CANVAS_W, max(row_w.values(), default=0) + 2 * margin)

    positions: dict[str, dict[str, Any]] = {}
    y = TOP
    for L in sorted(by_layer):
        row = by_layer[L]
        rh = max(sizes[n][1] for n in row)
        x = (canvas_w - row_w[L]) / 2
        for nid in row:
            w, h = sizes[nid]
            node = by_id[nid]
            positions[nid] = {
                "cx": x + w / 2, "cy": y + rh / 2, "w": w, "h": h,
                "role": node.get("role"), "node": node,
                "lines": node_lines(node), "layer": L,
            }
            x += w + GAP_X
        y += rh + ROW_GAP
    canvas_h = y - ROW_GAP + BOTTOM
    return positions, canvas_w, canvas_h
