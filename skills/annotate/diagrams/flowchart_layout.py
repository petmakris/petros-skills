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
