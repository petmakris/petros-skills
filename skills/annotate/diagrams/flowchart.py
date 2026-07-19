"""Flowchart spec validator + server-side SVG renderer.

Pure functions, no I/O. Called by server.py when rendering a block
with kind == "flowchart". No Mermaid, no external process.
"""
from __future__ import annotations

from collections import deque
from html import escape as _esc
from typing import Any

from .flowchart_layout import CONTENT_W, DIAMOND_HW, DIAMOND_HH, layout


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
    # ordered lines: label, ref(link), method, sub. Each line is tagged with
    # a "kind" (label/ref/method/sub) distinct from its CSS class, since the
    # label class varies (flow-label vs flow-label-error) but its kind
    # doesn't — that's what the primary-link priority below matches on.
    lines: list[tuple[str, str, str]] = []
    if node.get("label"):
        cls = "flow-label-error" if node.get("role") == "error" else "flow-label"
        lines.append((cls, node["label"], "label"))
    if node.get("ref"):
        lines.append(("flow-ref", node["ref"], "ref"))
    if node.get("method"):
        lines.append(("flow-method", node["method"], "method"))
    if node.get("sub"):
        lines.append(("flow-sub", node["sub"], "sub"))
    if not lines:
        lines.append(("flow-label", "", "label"))

    # Primary jump-to-source link line, in priority order ref > label >
    # method (sub is never the link target). Deterministic and wraps at
    # most one line.
    href = node.get("href")
    primary_kind = None
    if href:
        if node.get("ref"):
            primary_kind = "ref"
        elif node.get("label"):
            primary_kind = "label"
        elif node.get("method"):
            primary_kind = "method"

    n = len(lines)
    start = cy - (n * 17) / 2 + 13
    out: list[str] = []
    for i, (cls, txt, kind) in enumerate(lines):
        y = start + i * 17
        el = (f'<text class="{cls}" x="{cx:.1f}" y="{y:.1f}" '
              f'text-anchor="middle">{_esc(txt)}</text>')
        if kind == primary_kind:
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
