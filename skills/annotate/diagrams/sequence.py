"""Sequence-diagram spec validator + server-side SVG renderer.

Pure functions, no I/O. Called by server.py when rendering a block
with kind == "sequence".
"""
from __future__ import annotations

from html import escape as _html_escape
from typing import Any

from .text_metrics import text_px

ARROW_TYPES = ("request", "event", "self")


class ValidationError(ValueError):
    """Raised when a sequence spec violates a structural rule."""


def validate(spec: dict[str, Any]) -> None:
    """Raise ValidationError if the spec is malformed; otherwise return None."""
    actors = spec.get("actors") or []
    steps = spec.get("steps") or []
    phases = spec.get("phases") or []

    if len(actors) < 2:
        raise ValidationError("sequence requires at least 2 actors")
    if len(steps) < 1:
        raise ValidationError("sequence requires at least 1 step")

    actor_ids: set[str] = set()
    for a in actors:
        aid = a.get("id")
        if not aid or aid in actor_ids:
            raise ValidationError(f"actor id missing or duplicate: {aid!r}")
        if not a.get("label"):
            raise ValidationError(f"actor {aid!r}: label required")
        actor_ids.add(aid)

    seen_step_ids: set[str] = set()
    step_order: list[str] = []
    for s in steps:
        sid = s.get("id")
        if not sid:
            raise ValidationError("step id required")
        if sid in seen_step_ids:
            raise ValidationError(f"duplicate step id: {sid!r}")
        seen_step_ids.add(sid)
        step_order.append(sid)

        if s.get("from") not in actor_ids:
            raise ValidationError(f"step {sid}: unknown from actor {s.get('from')!r}")
        if s.get("to") not in actor_ids:
            raise ValidationError(f"step {sid}: unknown to actor {s.get('to')!r}")

        arrow = s.get("arrow")
        if arrow not in ARROW_TYPES:
            raise ValidationError(f"step {sid}: unknown arrow type {arrow!r}")
        if arrow == "self" and s.get("from") != s.get("to"):
            raise ValidationError(f"step {sid}: arrow=self requires from == to")
        if arrow != "self" and s.get("from") == s.get("to"):
            raise ValidationError(f"step {sid}: cross-actor arrow with from == to; use arrow=self")

    last_step_idx = -1
    for p in phases:
        if not p.get("label"):
            raise ValidationError(f"phase {p.get('id')!r}: label required")
        start = p.get("start_at")
        if start not in seen_step_ids:
            raise ValidationError(f"phase {p.get('id')!r}: start_at refers to unknown step {start!r}")
        idx = step_order.index(start)
        if idx <= last_step_idx:
            raise ValidationError(f"phase {p.get('id')!r}: phase order violates step order")
        last_step_idx = idx


# ── layout constants ──────────────────────────────────────────────
# Scaled to match the host page's typography (15.5px body); pills/rows
# are deliberately compact so the diagram doesn't dwarf surrounding prose.
# PAD_X must be ≥ ACTOR_PILL_W // 2 so the outermost actor pills (centered
# at x=PAD_X) stay fully within the viewBox at both edges.
ACTOR_PILL_W = 140       # minimum; a longer label widens its own pill
ACTOR_PILL_PAD = 14      # horizontal padding, label to pill edge
PAD_X = ACTOR_PILL_W // 2 + 8  # = 78
PAD_TOP = 12
ACTOR_PILL_H = 28
ACTOR_GAP_MIN = 24
ROW_H = 40
PHASE_LABEL_H = 18  # extra space above the first row of a phase for its label
LIFELINE_TOP = PAD_TOP + ACTOR_PILL_H + 8
LEGEND_H = 28

# minimum total width; grows if wide actor labels need more room
TOTAL_W = 1040


def actor_pill_w(actor: dict[str, Any]) -> int:
    """Pill width for one actor: wide enough for its measured label."""
    return max(ACTOR_PILL_W,
               int(text_px(actor.get("label", ""), "actor-label")) + 2 * ACTOR_PILL_PAD)


def _actor_xs(actors: list[dict[str, Any]]) -> tuple[list[int], int]:
    """Actor column centres and the canvas width that holds them.

    Columns are evenly spaced, but the step is never smaller than what the two
    widest neighbouring pills need — an actor whose label is longer than the
    140px default widens the diagram instead of overflowing its pill.
    """
    widths = [actor_pill_w(a) for a in actors]
    n = len(actors)
    if n <= 1:
        total_w = max(TOTAL_W, (widths[0] if widths else 0) + 2 * PAD_X)
        return [total_w // 2], total_w
    step = max(
        (TOTAL_W - 2 * PAD_X) // (n - 1),
        max((widths[i] + widths[i + 1]) // 2 + ACTOR_GAP_MIN for i in range(n - 1)),
    )
    edge = max(PAD_X, widths[0] // 2 + 8, widths[-1] // 2 + 8)
    total_w = max(TOTAL_W, 2 * edge + step * (n - 1))
    return [edge + i * step for i in range(n)], total_w


def _row_y(row_index: int, phase_offsets: dict[int, int]) -> int:
    """Top-y of the row at row_index (0-based), accounting for phase-label offsets."""
    offset = sum(phase_offsets.get(i, 0) for i in range(row_index + 1))
    return LIFELINE_TOP + 10 + row_index * ROW_H + offset


def render(spec: dict[str, Any], block_id: str) -> str:
    """Render a validated spec to an SVG string with hit-target IDs.

    Raises ValidationError if spec is malformed.
    """
    validate(spec)

    actors = spec["actors"]
    steps = spec["steps"]
    phases = spec.get("phases") or []

    xs, total_w = _actor_xs(actors)

    # phase_offsets[row_index] = extra px added before that row for phase label
    phase_offsets: dict[int, int] = {}
    step_id_to_index = {s["id"]: i for i, s in enumerate(steps)}
    for p in phases:
        idx = step_id_to_index[p["start_at"]]
        phase_offsets[idx] = PHASE_LABEL_H

    total_h = LIFELINE_TOP + 10 + len(steps) * ROW_H + sum(phase_offsets.values()) + LEGEND_H + 20

    parts: list[str] = []
    # No data-block-id on the SVG root: the host <section> already carries
    # it, and putting it here would let the catch-all `[data-block-id]:hover`
    # rule in style.css paint a background tint on the SVG element itself
    # whenever any descendant is hovered (background works on the <svg>
    # replaced element, even though it's a no-op on inner <g>/<rect>).
    parts.append(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {total_w} {total_h}" '
        f'class="annotate-seq">'
    )
    parts.append(_defs())

    # actor pills + labels
    for actor, x in zip(actors, xs):
        pw = actor_pill_w(actor)
        parts.append(
            f'<rect class="actor-pill" x="{x - pw // 2}" y="{PAD_TOP}" '
            f'width="{pw}" height="{ACTOR_PILL_H}" rx="{ACTOR_PILL_H // 2}"/>'
        )
        parts.append(
            f'<text class="actor-label" x="{x}" y="{PAD_TOP + ACTOR_PILL_H // 2 + 4}" '
            f'text-anchor="middle">{_html_escape(actor["label"])}</text>'
        )

    # phase bands first — they're the background; lifelines + arrows paint on top.
    if phases:
        parts.append(_render_phases(phases, steps, step_id_to_index, phase_offsets, total_h, total_w))

    # lifelines (must come after phase bands so they remain visible through them)
    bottom_y = total_h - LEGEND_H - 10
    for x in xs:
        parts.append(
            f'<line class="lane" x1="{x}" y1="{LIFELINE_TOP}" x2="{x}" y2="{bottom_y}"/>'
        )

    # steps
    actor_x = {a["id"]: x for a, x in zip(actors, xs)}
    for i, step in enumerate(steps):
        y = _row_y(i, phase_offsets)
        parts.append(_render_step(step, block_id, actor_x, y, total_w))

    parts.append(_render_legend(steps, total_h))

    parts.append("</svg>")
    return "".join(parts)


def _defs() -> str:
    """Arrow-head marker definitions shared by all arrow types. Fills match
    the corresponding arrow stroke colors in diagram.css."""
    return (
        '<defs>'
        '<marker id="m-user" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">'
        '<path d="M0,0 L10,5 L0,10 z" fill="#6d28d9"/></marker>'
        '<marker id="m-auto" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">'
        '<path d="M0,0 L10,5 L0,10 z" fill="#b45309"/></marker>'
        '<marker id="m-self" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">'
        '<path d="M0,0 L10,5 L0,10 z" fill="#047857"/></marker>'
        '</defs>'
    )


def _render_step(step: dict[str, Any], block_id: str, actor_x: dict[str, int], y: int,
                 total_w: int) -> str:
    """Emit one step row. y is the arrow centerline; row geometry is derived
    from ROW_H so all offsets scale with the layout constants."""
    sid = step["id"]
    arrow = step["arrow"]
    fx = actor_x[step["from"]]
    tx = actor_x[step["to"]]
    label = _html_escape(step.get("label", ""))
    sub = step.get("sub")
    row_half = ROW_H // 2
    label_dy = -6   # label baseline above the arrow centerline
    sub_dy = 13     # sub baseline below the arrow centerline

    parts = [
        f'<g class="step-row" data-block-id="{_html_escape(block_id, quote=True)}" data-step-id="{_html_escape(sid, quote=True)}">',
        f'<rect class="row-bg" x="0" y="{y - row_half}" width="{total_w}" height="{ROW_H}"/>',
    ]

    if arrow == "self":
        # Curved loop next to the actor's lifeline with an arrowhead pointing
        # back at it. The loop and its labels normally extend to the RIGHT
        # of the lifeline. On the rightmost actor that would push labels
        # past the viewBox, so mirror the entire loop to the LEFT.
        loop_w = 40
        max_x = max(actor_x.values())
        mirror = fx == max_x
        side = -1 if mirror else 1
        anchor = ' text-anchor="end"' if mirror else ''
        p_start_x = fx + 4 * side
        p_far_x = fx + loop_w * side
        label_x = fx + (loop_w + 10) * side
        parts.append(
            f'<path class="arr-self" d="M {p_start_x} {y - 7} '
            f'C {p_far_x} {y - 7} {p_far_x} {y + 9} {p_start_x} {y + 9}" '
            f'marker-end="url(#m-self)"/>'
        )
        parts.append(
            f'<text class="arrow-label" x="{label_x}" y="{y + 2}"{anchor}>{label}</text>'
        )
        if sub:
            parts.append(
                f'<text class="arrow-sub" x="{label_x}" y="{y + sub_dy}"{anchor}>{_html_escape(sub)}</text>'
            )
    else:
        # straight line; pull endpoints in 8px so arrowhead doesn't sit on the lifeline
        sign = 1 if tx > fx else -1
        x1 = fx + 8 * sign
        x2 = tx - 8 * sign
        marker = "m-user" if arrow == "request" else "m-auto"
        cls = f"arr-{arrow}"
        mid_x = (fx + tx) // 2
        parts.append(
            f'<line class="{cls}" x1="{x1}" y1="{y}" x2="{x2}" y2="{y}" marker-end="url(#{marker})"/>'
        )
        parts.append(
            f'<text class="arrow-label" x="{mid_x}" y="{y + label_dy}" text-anchor="middle">{label}</text>'
        )
        if sub:
            parts.append(
                f'<text class="arrow-sub" x="{mid_x}" y="{y + sub_dy}" text-anchor="middle">{_html_escape(sub)}</text>'
            )

    parts.append('</g>')
    return "".join(parts)


def _render_phases(
    phases: list[dict[str, Any]],
    steps: list[dict[str, Any]],
    step_id_to_index: dict[str, int],
    phase_offsets: dict[int, int],
    total_h: int,
    total_w: int,
) -> str:
    """Render phase bands as background rects + uppercase labels in the left margin."""
    parts: list[str] = []
    for pi, phase in enumerate(phases):
        start_idx = step_id_to_index[phase["start_at"]]
        end_idx = (
            step_id_to_index[phases[pi + 1]["start_at"]] - 1
            if pi + 1 < len(phases)
            else len(steps) - 1
        )
        y_top = _row_y(start_idx, phase_offsets) - PHASE_LABEL_H - 8
        y_bot = _row_y(end_idx, phase_offsets) + ROW_H // 2
        parts.append(
            f'<rect class="phase-band" x="0" y="{y_top}" width="{total_w}" height="{y_bot - y_top}"/>'
        )
        parts.append(
            f'<text class="phase-label" x="{10}" y="{y_top + 13}">{_html_escape(phase["label"])}</text>'
        )
    return "".join(parts)


def _render_legend(steps: list[dict[str, Any]], total_h: int) -> str:
    """Legend at the bottom — only shows arrow types actually present."""
    present = {s["arrow"] for s in steps}
    items: list[tuple[str, str]] = []
    if "request" in present:
        items.append(("arr-request", "actor ↔ actor"))
    if "event" in present:
        items.append(("arr-event", "automatic / event"))
    if "self" in present:
        items.append(("arr-self", "self-action"))

    y = total_h - LEGEND_H + 10
    parts = [f'<g class="seq-legend" transform="translate({PAD_X}, {y})">']
    cursor_x = 0
    for cls, label in items:
        marker = {"arr-request": "m-user", "arr-event": "m-auto", "arr-self": "m-self"}[cls]
        parts.append(
            f'<line class="{cls}" x1="{cursor_x}" y1="0" x2="{cursor_x + 32}" y2="0" '
            f'marker-end="url(#{marker})"/>'
        )
        parts.append(
            f'<text class="legend-text" x="{cursor_x + 38}" y="3">{_html_escape(label)}</text>'
        )
        cursor_x += 170
    parts.append('</g>')
    return "".join(parts)
