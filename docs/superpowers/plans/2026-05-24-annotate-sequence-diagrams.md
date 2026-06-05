# Annotate Sequence-Diagram Blocks — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `kind: "sequence"` blocks to annotate — Claude can emit sequence-diagram blocks alongside markdown blocks; the server renders them to SVG matching the validated mockup; users click any step row to comment, scoped to that step.

**Architecture:** Typed blocks in `blocks.json` (`kind` field, defaults to `"markdown"` when absent for back-compat). New module `skills/annotate/diagrams/sequence.py` provides spec validation + server-side SVG renderer (pure function, no I/O). `server.py`'s `/raw` endpoint dispatches on `kind` and returns either `markdown` (for markdown blocks) or a pre-rendered `svg` string (for sequence blocks). Browser `script.js` renders markdown via markdown-it as today, and `innerHTML`s the svg for sequence blocks; delegated click handler catches `[data-step-id]` clicks and submits `{block_id, step_id, ...}` to `/api/submit`.

**Tech Stack:** Python 3 (stdlib only — pure-Python SVG renderer, no third-party deps), vanilla JS (no framework), pytest, plain CSS. Static assets served by the existing `wc_server` plumbing.

**Spec:** `docs/superpowers/specs/2026-05-24-annotate-sequence-diagrams-design.md`

**Visual reference:** `.superpowers/brainstorm/42113-1779613437/content/diagram-block-mockup.html` (gitignored; user-approved style).

---

## File Structure

**Created:**
- `skills/annotate/diagrams/__init__.py` — namespace marker.
- `skills/annotate/diagrams/sequence.py` — `validate(spec)`, `render(spec, block_id)`, layout constants.
- `skills/annotate/static/diagram.css` — all diagram visual styling (actor pills, lifelines, arrow types, phase bands, legend, hover wash).
- `skills/annotate/tests/test_diagrams_sequence.py` — renderer + validator unit tests.
- `skills/annotate/tests/test_smoke_e2e_diagram.py` — end-to-end smoke (mixed markdown + sequence doc through real session).

**Modified:**
- `skills/annotate/blocks.py` — add `update_spec_block`, `next_step_id`, `_canonical_spec` helpers; no change to existing `update_block`.
- `skills/annotate/server.py` — `/raw` dispatch on `kind`; render SVG for sequence blocks; `/api/submit` accepts and validates optional `step_id`; inject `diagram.css` in head.
- `skills/annotate/static/script.js` — dispatch on `kind` when rendering blocks; delegated click handler for `[data-step-id]`; submit payload gains `step_id`.
- `skills/annotate/SKILL.md` — extend Mode A trigger criteria; add "diagram block-rewrite contract" section; update "How to push a response" with diagram-block shape.
- `skills/annotate/tests/test_blocks.py` — extend with `update_spec_block` + `next_step_id` tests.
- `skills/annotate/tests/test_server.py` — extend `/raw` and `/api/submit` tests with the kind/step_id paths.

---

## Task 1: blocks.py — `update_spec_block` + canonical-JSON dedup

**Files:**
- Modify: `skills/annotate/blocks.py`
- Test: `skills/annotate/tests/test_blocks.py`

- [ ] **Step 1: Write the failing tests**

Append to `skills/annotate/tests/test_blocks.py`:

```python
from skills.annotate.blocks import update_spec_block


def _seq_block(bid: str, spec: dict, version: int = 1):
    return {"id": bid, "kind": "sequence", "spec": spec, "version": version}


def test_update_spec_block_bumps_version_on_change():
    spec_old = {"actors": [{"id": "a", "label": "A"}], "steps": []}
    spec_new = {"actors": [{"id": "a", "label": "A2"}], "steps": []}
    doc = BlocksDoc(response_id="r-1", title="t",
                    blocks=[_seq_block("b-0", spec_old, 3)])
    changed = update_spec_block(doc, "b-0", spec_new)
    assert changed is True
    assert doc.blocks[0]["spec"] == spec_new
    assert doc.blocks[0]["version"] == 4


def test_update_spec_block_no_op_when_equivalent():
    spec = {"actors": [{"id": "a", "label": "A"}], "steps": []}
    doc = BlocksDoc(response_id="r-1", title="t",
                    blocks=[_seq_block("b-0", spec, 5)])
    # Reordered keys must still hash equal — canonical JSON.
    equivalent = {"steps": [], "actors": [{"label": "A", "id": "a"}]}
    changed = update_spec_block(doc, "b-0", equivalent)
    assert changed is False
    assert doc.blocks[0]["version"] == 5


def test_update_spec_block_unknown_id_raises():
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[])
    with pytest.raises(KeyError):
        update_spec_block(doc, "b-99", {})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest skills/annotate/tests/test_blocks.py::test_update_spec_block_bumps_version_on_change -v`
Expected: FAIL with `ImportError: cannot import name 'update_spec_block'`.

- [ ] **Step 3: Implement `update_spec_block` + canonical helper**

Append to `skills/annotate/blocks.py`:

```python
def _canonical_spec(spec: dict[str, Any]) -> str:
    """Stable JSON serialization for content-hash dedup."""
    return json.dumps(spec, sort_keys=True, separators=(",", ":"))


def update_spec_block(doc: BlocksDoc, block_id: str, new_spec: dict[str, Any]) -> bool:
    """Update a sequence block's spec. Returns True if version bumped, False if no-op."""
    b = find_block(doc, block_id)
    if _canonical_spec(b.get("spec") or {}) == _canonical_spec(new_spec):
        return False
    b["spec"] = new_spec
    b["version"] = int(b.get("version", 0)) + 1
    return True
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest skills/annotate/tests/test_blocks.py -v`
Expected: all tests in the file pass (including pre-existing ones).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/blocks.py skills/annotate/tests/test_blocks.py
git commit -m "annotate: update_spec_block with canonical-JSON dedup"
```

---

## Task 2: blocks.py — `next_step_id` helper

**Files:**
- Modify: `skills/annotate/blocks.py`
- Test: `skills/annotate/tests/test_blocks.py`

- [ ] **Step 1: Write the failing tests**

Append to `skills/annotate/tests/test_blocks.py`:

```python
from skills.annotate.blocks import next_step_id


def test_next_step_id_empty_spec():
    assert next_step_id({"steps": []}) == "s1"
    assert next_step_id({}) == "s1"


def test_next_step_id_never_reuses():
    spec = {"steps": [{"id": "s1"}, {"id": "s3"}, {"id": "s4"}]}
    assert next_step_id(spec) == "s2"


def test_next_step_id_handles_non_numeric():
    spec = {"steps": [{"id": "s1"}, {"id": "custom"}, {"id": "s2"}]}
    assert next_step_id(spec) == "s3"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest skills/annotate/tests/test_blocks.py::test_next_step_id_empty_spec -v`
Expected: FAIL with `ImportError`.

- [ ] **Step 3: Implement `next_step_id`**

Append to `skills/annotate/blocks.py`:

```python
def next_step_id(spec: dict[str, Any]) -> str:
    """Mint a fresh step id never used in this spec. Returns 'sN'."""
    used: set[int] = set()
    for s in (spec.get("steps") or []):
        sid = s.get("id", "")
        if sid.startswith("s"):
            try:
                used.add(int(sid[1:]))
            except ValueError:
                pass
    n = 1
    while n in used:
        n += 1
    return f"s{n}"
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest skills/annotate/tests/test_blocks.py -v`
Expected: all tests in the file pass.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/blocks.py skills/annotate/tests/test_blocks.py
git commit -m "annotate: next_step_id helper for sequence-block specs"
```

---

## Task 3: Sequence spec validation

**Files:**
- Create: `skills/annotate/diagrams/__init__.py`
- Create: `skills/annotate/diagrams/sequence.py`
- Create: `skills/annotate/tests/test_diagrams_sequence.py`

- [ ] **Step 1: Write the failing tests**

Create `skills/annotate/tests/test_diagrams_sequence.py`:

```python
"""Sequence-diagram validator + renderer tests."""
import pytest

from skills.annotate.diagrams.sequence import ValidationError, validate


def _minimal_spec():
    return {
        "actors": [{"id": "a", "label": "A"}, {"id": "b", "label": "B"}],
        "steps": [{"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "hi"}],
    }


def test_validate_minimal_spec_ok():
    validate(_minimal_spec())  # no raise


def test_validate_requires_two_actors():
    spec = _minimal_spec()
    spec["actors"] = [{"id": "a", "label": "A"}]
    with pytest.raises(ValidationError, match="actors"):
        validate(spec)


def test_validate_requires_at_least_one_step():
    spec = _minimal_spec()
    spec["steps"] = []
    with pytest.raises(ValidationError, match="steps"):
        validate(spec)


def test_validate_unknown_from_actor():
    spec = _minimal_spec()
    spec["steps"][0]["from"] = "ghost"
    with pytest.raises(ValidationError, match="from"):
        validate(spec)


def test_validate_unknown_to_actor():
    spec = _minimal_spec()
    spec["steps"][0]["to"] = "ghost"
    with pytest.raises(ValidationError, match="to"):
        validate(spec)


def test_validate_self_arrow_requires_same_actor():
    spec = _minimal_spec()
    spec["steps"][0] = {"id": "s1", "from": "a", "to": "b", "arrow": "self", "label": "x"}
    with pytest.raises(ValidationError, match="self"):
        validate(spec)


def test_validate_self_arrow_with_same_actor_ok():
    spec = _minimal_spec()
    spec["steps"][0] = {"id": "s1", "from": "a", "to": "a", "arrow": "self", "label": "loop"}
    validate(spec)


def test_validate_unknown_arrow_type():
    spec = _minimal_spec()
    spec["steps"][0]["arrow"] = "magic"
    with pytest.raises(ValidationError, match="arrow"):
        validate(spec)


def test_validate_duplicate_step_ids():
    spec = _minimal_spec()
    spec["steps"].append(
        {"id": "s1", "from": "b", "to": "a", "arrow": "request", "label": "dup"}
    )
    with pytest.raises(ValidationError, match="duplicate"):
        validate(spec)


def test_validate_phase_start_at_unknown_step():
    spec = _minimal_spec()
    spec["phases"] = [{"id": "p1", "label": "P", "start_at": "s99"}]
    with pytest.raises(ValidationError, match="start_at"):
        validate(spec)


def test_validate_phase_order_must_follow_step_order():
    spec = _minimal_spec()
    spec["steps"].append({"id": "s2", "from": "b", "to": "a", "arrow": "request", "label": "y"})
    spec["phases"] = [
        {"id": "p1", "label": "P1", "start_at": "s2"},
        {"id": "p2", "label": "P2", "start_at": "s1"},  # out of order
    ]
    with pytest.raises(ValidationError, match="phase.*order"):
        validate(spec)


def test_validate_phases_optional():
    spec = _minimal_spec()
    # No "phases" key — should still validate.
    validate(spec)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest skills/annotate/tests/test_diagrams_sequence.py -v`
Expected: all FAIL with `ModuleNotFoundError: skills.annotate.diagrams`.

- [ ] **Step 3: Create the module + validator**

Create `skills/annotate/diagrams/__init__.py` (empty file).

Create `skills/annotate/diagrams/sequence.py`:

```python
"""Sequence-diagram spec validator + server-side SVG renderer.

Pure functions, no I/O. Called by server.py when rendering a block
with kind == "sequence".
"""
from __future__ import annotations

from typing import Any

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
        start = p.get("start_at")
        if start not in seen_step_ids:
            raise ValidationError(f"phase {p.get('id')!r}: start_at refers to unknown step {start!r}")
        idx = step_order.index(start)
        if idx <= last_step_idx:
            raise ValidationError(f"phase {p.get('id')!r}: phase order violates step order")
        last_step_idx = idx
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest skills/annotate/tests/test_diagrams_sequence.py -v`
Expected: all 12 tests pass.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/diagrams/__init__.py skills/annotate/diagrams/sequence.py skills/annotate/tests/test_diagrams_sequence.py
git commit -m "annotate: sequence-diagram spec validator"
```

---

## Task 4: SVG renderer — layout constants + actor pills + lifelines

**Files:**
- Modify: `skills/annotate/diagrams/sequence.py`
- Modify: `skills/annotate/tests/test_diagrams_sequence.py`

- [ ] **Step 1: Write the failing tests**

Append to `skills/annotate/tests/test_diagrams_sequence.py`:

```python
import re

from skills.annotate.diagrams.sequence import render


def _spec_3actors():
    return {
        "actors": [
            {"id": "a", "label": "Alpha"},
            {"id": "b", "label": "Beta"},
            {"id": "c", "label": "Gamma"},
        ],
        "steps": [
            {"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "ping"},
        ],
    }


def test_render_returns_svg_element():
    svg = render(_spec_3actors(), block_id="b-0")
    assert svg.startswith("<svg")
    assert svg.rstrip().endswith("</svg>")
    assert 'data-block-id="b-0"' in svg


def test_render_emits_one_actor_pill_per_actor():
    svg = render(_spec_3actors(), block_id="b-0")
    # Each actor renders as a <rect class="actor-pill"> + <text class="actor-label">.
    assert svg.count('class="actor-pill"') == 3
    assert ">Alpha<" in svg
    assert ">Beta<" in svg
    assert ">Gamma<" in svg


def test_render_emits_lifeline_per_actor():
    svg = render(_spec_3actors(), block_id="b-0")
    assert svg.count('class="lane"') == 3


def test_render_escapes_actor_label_html():
    spec = _spec_3actors()
    spec["actors"][0]["label"] = "<script>x</script>"
    svg = render(spec, block_id="b-0")
    assert "<script>x</script>" not in svg
    assert "&lt;script&gt;" in svg
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest skills/annotate/tests/test_diagrams_sequence.py::test_render_returns_svg_element -v`
Expected: FAIL with `ImportError: cannot import name 'render'`.

- [ ] **Step 3: Implement layout + actor pills + lifelines**

Append to `skills/annotate/diagrams/sequence.py`:

```python
from html import escape as _html_escape

# ── layout constants ──────────────────────────────────────────────
PAD_X = 40
PAD_TOP = 14
ACTOR_PILL_H = 38
ACTOR_PILL_W = 170
ACTOR_GAP_MIN = 30
ROW_H = 56
PHASE_LABEL_H = 22  # extra space above the first row of a phase for its label
LIFELINE_TOP = PAD_TOP + ACTOR_PILL_H + 8
LEGEND_H = 36

# fixed total width; per-actor column x is computed
TOTAL_W = 1040


def _actor_xs(n: int) -> list[int]:
    """Evenly space n actor columns within TOTAL_W."""
    if n <= 1:
        return [TOTAL_W // 2]
    inner_w = TOTAL_W - 2 * PAD_X
    step = inner_w // (n - 1)
    return [PAD_X + i * step for i in range(n)]


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

    xs = _actor_xs(len(actors))

    # phase_offsets[row_index] = extra px added before that row for phase label
    phase_offsets: dict[int, int] = {}
    step_id_to_index = {s["id"]: i for i, s in enumerate(steps)}
    for p in phases:
        idx = step_id_to_index[p["start_at"]]
        phase_offsets[idx] = PHASE_LABEL_H

    total_h = LIFELINE_TOP + 10 + len(steps) * ROW_H + sum(phase_offsets.values()) + LEGEND_H + 20

    parts: list[str] = []
    parts.append(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {TOTAL_W} {total_h}" '
        f'class="annotate-seq" data-block-id="{_html_escape(block_id)}">'
    )
    parts.append(_defs())

    # actor pills + labels
    for actor, x in zip(actors, xs):
        px = x - ACTOR_PILL_W // 2
        parts.append(
            f'<rect class="actor-pill" x="{px}" y="{PAD_TOP}" '
            f'width="{ACTOR_PILL_W}" height="{ACTOR_PILL_H}" rx="{ACTOR_PILL_H // 2}"/>'
        )
        parts.append(
            f'<text class="actor-label" x="{x}" y="{PAD_TOP + ACTOR_PILL_H // 2 + 5}" '
            f'text-anchor="middle">{_html_escape(actor["label"])}</text>'
        )

    # lifelines
    bottom_y = total_h - LEGEND_H - 10
    for x in xs:
        parts.append(
            f'<line class="lane" x1="{x}" y1="{LIFELINE_TOP}" x2="{x}" y2="{bottom_y}"/>'
        )

    parts.append("</svg>")
    return "".join(parts)


def _defs() -> str:
    """Arrow-head marker definitions shared by all arrow types."""
    return (
        '<defs>'
        '<marker id="m-user" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">'
        '<path d="M0,0 L10,5 L0,10 z" fill="#9b8aff"/></marker>'
        '<marker id="m-auto" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">'
        '<path d="M0,0 L10,5 L0,10 z" fill="#e9b145"/></marker>'
        '<marker id="m-self" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">'
        '<path d="M0,0 L10,5 L0,10 z" fill="#6dd97a"/></marker>'
        '</defs>'
    )
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest skills/annotate/tests/test_diagrams_sequence.py -v`
Expected: all tests pass (validator tests still pass; renderer tests now pass).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/diagrams/sequence.py skills/annotate/tests/test_diagrams_sequence.py
git commit -m "annotate: sequence renderer skeleton — actors + lifelines + defs"
```

---

## Task 5: Renderer — step rows (cross-actor arrows + self-loops + hit targets)

**Files:**
- Modify: `skills/annotate/diagrams/sequence.py`
- Modify: `skills/annotate/tests/test_diagrams_sequence.py`

- [ ] **Step 1: Write the failing tests**

Append to `skills/annotate/tests/test_diagrams_sequence.py`:

```python
def _spec_one_of_each_arrow():
    return {
        "actors": [
            {"id": "a", "label": "A"},
            {"id": "b", "label": "B"},
        ],
        "steps": [
            {"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "req", "sub": "subreq"},
            {"id": "s2", "from": "b", "to": "a", "arrow": "event",   "label": "evt"},
            {"id": "s3", "from": "a", "to": "a", "arrow": "self",    "label": "loop"},
        ],
    }


def test_render_emits_step_row_per_step():
    svg = render(_spec_one_of_each_arrow(), block_id="b-0")
    assert svg.count('class="step-row"') == 3


def test_render_includes_data_step_id_per_row():
    svg = render(_spec_one_of_each_arrow(), block_id="b-0")
    for sid in ("s1", "s2", "s3"):
        assert f'data-step-id="{sid}"' in svg


def test_render_arrow_class_per_type():
    svg = render(_spec_one_of_each_arrow(), block_id="b-0")
    assert 'class="arr-request"' in svg
    assert 'class="arr-event"' in svg
    assert 'class="arr-self"' in svg


def test_render_self_loop_is_path_not_line():
    svg = render(_spec_one_of_each_arrow(), block_id="b-0")
    # Self loops use <path d="..."> ; cross-actor uses <line>.
    assert re.search(r'<path[^>]*class="arr-self"', svg) is not None
    assert re.search(r'<line[^>]*class="arr-request"', svg) is not None


def test_render_emits_step_label_and_sub():
    svg = render(_spec_one_of_each_arrow(), block_id="b-0")
    assert ">req<" in svg
    assert ">subreq<" in svg
    assert ">evt<" in svg


def test_render_step_id_present_as_g_element():
    """Each step is grouped under a <g class='step-row' data-step-id='sN'>."""
    svg = render(_spec_one_of_each_arrow(), block_id="b-0")
    assert re.search(r'<g class="step-row" data-block-id="b-0" data-step-id="s1">', svg) is not None


def test_render_escapes_step_label_html():
    spec = _spec_one_of_each_arrow()
    spec["steps"][0]["label"] = "<b>oops</b>"
    svg = render(spec, block_id="b-0")
    assert "<b>oops</b>" not in svg
    assert "&lt;b&gt;oops&lt;/b&gt;" in svg
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest skills/annotate/tests/test_diagrams_sequence.py::test_render_emits_step_row_per_step -v`
Expected: FAIL with assertion (no `step-row` in output yet).

- [ ] **Step 3: Implement step rendering**

In `skills/annotate/diagrams/sequence.py`, inside `render(...)`, between the lifelines loop and the closing `</svg>`, add step rendering. Replace the existing `parts.append("</svg>")` block with:

```python
    # steps
    actor_x = {a["id"]: x for a, x in zip(actors, xs)}
    for i, step in enumerate(steps):
        y = _row_y(i, phase_offsets)
        parts.append(_render_step(step, block_id, actor_x, y))

    parts.append(_render_legend(steps, total_h))
    parts.append("</svg>")
    return "".join(parts)


def _render_step(step: dict[str, Any], block_id: str, actor_x: dict[str, int], y: int) -> str:
    sid = step["id"]
    arrow = step["arrow"]
    fx = actor_x[step["from"]]
    tx = actor_x[step["to"]]
    label = _html_escape(step.get("label", ""))
    sub = step.get("sub")
    sub_text = (
        f'<text class="arrow-sub" x="{(fx + tx) // 2}" y="{y + 32}" text-anchor="middle">'
        f'{_html_escape(sub)}</text>'
    ) if sub else ""

    parts = [
        f'<g class="step-row" data-block-id="{_html_escape(block_id)}" data-step-id="{_html_escape(sid)}">',
        f'<rect class="row-bg" x="0" y="{y - 28}" width="{TOTAL_W}" height="{ROW_H}"/>',
    ]

    if arrow == "self":
        # curved loop to the right of the actor
        loop_w = 56
        parts.append(
            f'<path class="arr-self" d="M {fx + 5} {y - 10} '
            f'C {fx + loop_w} {y - 10} {fx + loop_w} {y + 12} {fx + 5} {y + 12}"/>'
        )
        # label placed to the right of the loop
        parts.append(
            f'<text class="arrow-label" x="{fx + loop_w + 14}" y="{y + 2}">{label}</text>'
        )
        if sub:
            parts.append(
                f'<text class="arrow-sub" x="{fx + loop_w + 14}" y="{y + 20}">{_html_escape(sub)}</text>'
            )
    else:
        # straight line; pull endpoints in 10px so arrowhead doesn't sit on the lifeline
        sign = 1 if tx > fx else -1
        x1 = fx + 10 * sign
        x2 = tx - 10 * sign
        marker = "m-user" if arrow == "request" else "m-auto"
        cls = f"arr-{arrow}"
        parts.append(
            f'<line class="{cls}" x1="{x1}" y1="{y}" x2="{x2}" y2="{y}" marker-end="url(#{marker})"/>'
        )
        parts.append(
            f'<text class="arrow-label" x="{(fx + tx) // 2}" y="{y - 8}" text-anchor="middle">{label}</text>'
        )
        if sub:
            parts.append(sub_text)

    parts.append('</g>')
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

    y = total_h - LEGEND_H + 12
    parts = [f'<g class="seq-legend" transform="translate({PAD_X}, {y})">']
    cursor_x = 0
    for cls, label in items:
        marker = {"arr-request": "m-user", "arr-event": "m-auto", "arr-self": "m-self"}[cls]
        parts.append(
            f'<line class="{cls}" x1="{cursor_x}" y1="0" x2="{cursor_x + 40}" y2="0" '
            f'marker-end="url(#{marker})"/>'
        )
        parts.append(
            f'<text class="legend-text" x="{cursor_x + 48}" y="4">{_html_escape(label)}</text>'
        )
        cursor_x += 220
    parts.append('</g>')
    return "".join(parts)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest skills/annotate/tests/test_diagrams_sequence.py -v`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/diagrams/sequence.py skills/annotate/tests/test_diagrams_sequence.py
git commit -m "annotate: sequence renderer — steps, arrows, self-loops, legend"
```

---

## Task 6: Renderer — phase bands

**Files:**
- Modify: `skills/annotate/diagrams/sequence.py`
- Modify: `skills/annotate/tests/test_diagrams_sequence.py`

- [ ] **Step 1: Write the failing tests**

Append to `skills/annotate/tests/test_diagrams_sequence.py`:

```python
def _spec_with_phases():
    return {
        "actors": [
            {"id": "a", "label": "A"},
            {"id": "b", "label": "B"},
        ],
        "phases": [
            {"id": "p1", "label": "FIRST",  "start_at": "s1"},
            {"id": "p2", "label": "SECOND", "start_at": "s3"},
        ],
        "steps": [
            {"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "x"},
            {"id": "s2", "from": "b", "to": "a", "arrow": "request", "label": "y"},
            {"id": "s3", "from": "a", "to": "b", "arrow": "request", "label": "z"},
        ],
    }


def test_render_emits_phase_label_per_phase():
    svg = render(_spec_with_phases(), block_id="b-0")
    assert ">FIRST<" in svg
    assert ">SECOND<" in svg
    assert svg.count('class="phase-label"') == 2


def test_render_emits_phase_band_rect_per_phase():
    svg = render(_spec_with_phases(), block_id="b-0")
    assert svg.count('class="phase-band"') == 2


def test_render_omits_phases_when_absent():
    spec = _spec_with_phases()
    del spec["phases"]
    svg = render(spec, block_id="b-0")
    assert 'class="phase-band"' not in svg
    assert 'class="phase-label"' not in svg
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest skills/annotate/tests/test_diagrams_sequence.py::test_render_emits_phase_label_per_phase -v`
Expected: FAIL with assertion.

- [ ] **Step 3: Implement phase-band rendering**

In `skills/annotate/diagrams/sequence.py`, modify `render(...)` — add phase rendering BEFORE the step loop (so they sit behind the steps in z-order). Replace the line `for i, step in enumerate(steps):` and what follows up to `parts.append(_render_legend(...))` with:

```python
    # phase bands behind the step rows
    if phases:
        parts.append(_render_phases(phases, steps, step_id_to_index, phase_offsets, total_h))

    # steps
    actor_x = {a["id"]: x for a, x in zip(actors, xs)}
    for i, step in enumerate(steps):
        y = _row_y(i, phase_offsets)
        parts.append(_render_step(step, block_id, actor_x, y))

    parts.append(_render_legend(steps, total_h))
```

Then append the `_render_phases` function:

```python
def _render_phases(
    phases: list[dict[str, Any]],
    steps: list[dict[str, Any]],
    step_id_to_index: dict[str, int],
    phase_offsets: dict[int, int],
    total_h: int,
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
        y_top = _row_y(start_idx, phase_offsets) - PHASE_LABEL_H - 10
        y_bot = _row_y(end_idx, phase_offsets) + ROW_H - 28
        parts.append(
            f'<rect class="phase-band" x="0" y="{y_top}" width="{TOTAL_W}" height="{y_bot - y_top}"/>'
        )
        parts.append(
            f'<text class="phase-label" x="{12}" y="{y_top + 16}">{_html_escape(phase["label"])}</text>'
        )
    return "".join(parts)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest skills/annotate/tests/test_diagrams_sequence.py -v`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/diagrams/sequence.py skills/annotate/tests/test_diagrams_sequence.py
git commit -m "annotate: sequence renderer — phase bands"
```

---

## Task 7: Diagram CSS file

**Files:**
- Create: `skills/annotate/static/diagram.css`

- [ ] **Step 1: Write the CSS**

Create `skills/annotate/static/diagram.css`:

```css
/* sequence-diagram block styling — matches the validated mockup */

.annotate-seq { display: block; width: 100%; height: auto; }

.annotate-seq .actor-pill   { fill: var(--surface-2, #1a1d25); stroke: var(--accent, #5e63ff); stroke-width: 1.4; }
.annotate-seq .actor-label  { fill: var(--text, #f0f1f5); font-weight: 600; font-size: 14px;
                              font-family: inherit; }
.annotate-seq .lane         { stroke: var(--border, #2d313c); stroke-dasharray: 3 4; stroke-width: 1; }

.annotate-seq .phase-band   { fill: var(--surface-1, #11141b); }
.annotate-seq .phase-label  { fill: var(--text-dim, #6a6e78); font-size: 10.5px;
                              letter-spacing: 1.6px; font-weight: 700; }

.annotate-seq .arrow-label  { fill: var(--text, #ebedf1); font-size: 13px; font-weight: 500; font-family: inherit; }
.annotate-seq .arrow-sub    { fill: var(--text-dim, #8a8e96); font-size: 11px; font-style: italic; }
.annotate-seq .legend-text  { fill: var(--text-dim, #a8acb5); font-size: 11.5px; }

.annotate-seq .arr-request { stroke: #9b8aff; stroke-width: 1.6; fill: none; }
.annotate-seq .arr-event   { stroke: #e9b145; stroke-width: 1.4; stroke-dasharray: 5 4; fill: none; }
.annotate-seq .arr-self    { stroke: #6dd97a; stroke-width: 1.5; fill: none; }

.annotate-seq .row-bg   { fill: transparent; transition: fill .15s; cursor: pointer; }
.annotate-seq .step-row { cursor: pointer; }
.annotate-seq .step-row:hover .row-bg { fill: rgba(155, 138, 255, 0.06); }

/* engaged step state mirrors block engaged-type indicator */
.annotate-seq .step-row[data-engaged-type="comment"] .row-bg { fill: rgba(155, 138, 255, 0.14); }
.annotate-seq .step-row[data-engaged-type="reject"]  .row-bg { fill: rgba(255, 110, 110, 0.10); }
```

- [ ] **Step 2: Visual sanity — no automated test**

This file ships static CSS; visual fidelity is verified end-to-end in Task 11. No unit test needed.

- [ ] **Step 3: Commit**

```bash
git add skills/annotate/static/diagram.css
git commit -m "annotate: diagram.css — visual styling for sequence blocks"
```

---

## Task 8: server.py — dispatch on `kind` in `/raw` + inject diagram.css

**Files:**
- Modify: `skills/annotate/server.py`
- Modify: `skills/annotate/tests/test_server.py`

- [ ] **Step 1: Write the failing tests**

Open `skills/annotate/tests/test_server.py` and append these tests to the `ServerStartupTests` class (same indentation level as the existing `test_raw_returns_full_blocks_json`):

```python
    def test_raw_returns_svg_for_sequence_block(self):
        """A block with kind=sequence in blocks.json round-trips with a rendered 'svg' field."""
        response_dir = Path(self.sess["response_dir"])
        spec = {
            "actors": [{"id": "a", "label": "A"}, {"id": "b", "label": "B"}],
            "steps": [{"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "ping"}],
        }
        _write_blocks(response_dir, "resp-seq", "T", [
            {"id": "b-0", "markdown": "intro", "version": 1},
            {"id": "b-1", "kind": "sequence", "spec": spec, "version": 1},
        ])
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data = json.loads(body)
        md_blk = next(b for b in data["blocks"] if b["id"] == "b-0")
        seq_blk = next(b for b in data["blocks"] if b["id"] == "b-1")
        # Markdown block unaffected (no svg field).
        self.assertEqual(md_blk["kind"], "markdown")
        self.assertEqual(md_blk["markdown"], "intro")
        self.assertNotIn("svg", md_blk)
        # Sequence block has svg + spec, kind preserved.
        self.assertEqual(seq_blk["kind"], "sequence")
        self.assertTrue(seq_blk["svg"].startswith("<svg"))
        self.assertIn('data-step-id="s1"', seq_blk["svg"])
        self.assertEqual(seq_blk["spec"], spec)

    def test_raw_renders_error_block_for_invalid_spec(self):
        """An invalid sequence spec should render an error SVG, not crash the request."""
        response_dir = Path(self.sess["response_dir"])
        bad_spec = {"actors": [{"id": "a", "label": "A"}], "steps": []}  # < 2 actors
        _write_blocks(response_dir, "resp-bad", "T", [
            {"id": "b-0", "kind": "sequence", "spec": bad_spec, "version": 1},
        ])
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data["blocks"][0]["kind"], "sequence")
        # SVG is returned (error placeholder), and mentions the failure.
        svg = data["blocks"][0]["svg"]
        self.assertTrue(svg.startswith("<svg"))
        self.assertIn("error", svg.lower())

    def test_root_html_links_diagram_css(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-css", "T", [
            {"id": "b-0", "markdown": "hi", "version": 1},
        ])
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        self.assertIn('href="/static/diagram.css"', body)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest skills/annotate/tests/test_server.py -v -k "sequence or diagram"`
Expected: the three new tests FAIL (either pass-trivially or assertion failures, depending on the fixture wiring).

- [ ] **Step 3: Implement /raw dispatch + error block + CSS injection**

In `skills/annotate/server.py`:

(a) Update `head_assets` in `serve_root` (line ~84) to include diagram.css:

```python
        head = ('<link rel="stylesheet" href="/static/style.css">'
                '<link rel="stylesheet" href="/static/diagram.css">'
                '<script src="/static/script.js" defer></script>')
```

(b) Add a helper at module scope (near the other `_send_*` helpers):

```python
def _render_block_for_raw(blk: dict) -> dict:
    """Return a dict shape the client expects for one block.
    - markdown blocks → pass markdown through
    - sequence blocks → include rendered svg + spec
    """
    kind = blk.get("kind") or "markdown"
    base = {"id": blk["id"], "kind": kind, "version": int(blk.get("version", 1))}
    if kind == "sequence":
        from skills.annotate.diagrams.sequence import render, ValidationError
        spec = blk.get("spec") or {}
        try:
            svg = render(spec, block_id=blk["id"])
        except ValidationError as e:
            svg = (
                f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 80" '
                f'class="annotate-seq" data-block-id="{html_escape(blk["id"])}">'
                f'<text class="arrow-label" x="20" y="44" fill="#ff6e6e">'
                f'sequence-diagram render error: {html_escape(str(e))}'
                f'</text></svg>'
            )
        base["spec"] = spec
        base["svg"] = svg
    else:
        base["markdown"] = blk.get("markdown", "")
    return base
```

(c) In `serve_data`, replace the existing `_send_json(h, 200, {... "blocks": doc.blocks})` block (around server.py:115-119) with:

```python
            _send_json(h, 200, {
                "response_id": doc.response_id,
                "title": doc.title,
                "blocks": [_render_block_for_raw(b) for b in doc.blocks],
            })
```

And in the same function, update the single-block branch (around server.py:109-113) similarly:

```python
                _send_json(h, 200, _render_block_for_raw(blk))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest skills/annotate/tests/test_server.py -v`
Expected: all tests pass, including the three new ones and all pre-existing tests.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py
git commit -m "annotate: /raw dispatches on kind; render SVG server-side; inject diagram.css"
```

---

## Task 9: server.py — `/api/submit` accepts and validates `step_id`

**Files:**
- Modify: `skills/annotate/server.py`
- Modify: `skills/annotate/tests/test_server.py`

- [ ] **Step 1: Write the failing tests**

In `skills/annotate/tests/test_server.py`, append these tests to the `ServerStartupTests` class (mirrors the existing `test_submit_writes_event_file` pattern). Helper for the POST:

```python
    def _post_json(self, path: str, payload: dict):
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", path, body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        body = resp.read().decode("utf-8")
        status = resp.status
        conn.close()
        return status, body
```

(If a similar helper already exists on the class, reuse it instead.)

Now the tests:

```python
    def _seq_blocks(self):
        spec = {
            "actors": [{"id": "a", "label": "A"}, {"id": "b", "label": "B"}],
            "steps": [{"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "x"}],
        }
        return [{"id": "b-0", "kind": "sequence", "spec": spec, "version": 1}]

    def test_submit_with_step_id_succeeds_when_step_exists(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-sid", "T", self._seq_blocks())
        status, body = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "step_id": "s1", "type": "comment", "text": "what about retries?",
        })
        self.assertEqual(status, 202)
        events_dir = Path(self.sess["events_dir"])
        event_files = list(events_dir.glob("*.json"))
        self.assertEqual(len(event_files), 1)
        evt = json.loads(event_files[0].read_text())
        self.assertEqual(evt["block_id"], "b-0")
        self.assertEqual(evt["step_id"], "s1")
        self.assertEqual(evt["text"], "what about retries?")

    def test_submit_with_unknown_step_id_returns_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-bad-sid", "T", self._seq_blocks())
        status, body = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "step_id": "s99", "type": "comment", "text": "x",
        })
        self.assertEqual(status, 422)
        self.assertIn("step", body.lower())

    def test_submit_step_id_against_markdown_block_returns_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-md-sid", "T", [
            {"id": "b-0", "markdown": "hello", "version": 1},
        ])
        status, body = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "step_id": "s1", "type": "comment", "text": "x",
        })
        self.assertEqual(status, 422)

    def test_submit_without_step_id_succeeds_for_sequence_block(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-whole-seq", "T", self._seq_blocks())
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "comment", "text": "rethink the actors",
        })
        self.assertEqual(status, 202)
        events_dir = Path(self.sess["events_dir"])
        event_files = list(events_dir.glob("*.json"))
        self.assertEqual(len(event_files), 1)
        evt = json.loads(event_files[0].read_text())
        self.assertIsNone(evt["step_id"])
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest skills/annotate/tests/test_server.py -v -k "step_id"`
Expected: all FAIL until step_id support is added.

- [ ] **Step 3: Implement step_id handling**

In `skills/annotate/server.py`, modify `handle_submit` (around line 123). After the existing `selected_text = payload.get("selected_text")` line, add:

```python
        step_id = payload.get("step_id")  # None for whole-diagram or non-diagram comments
```

After the existing `if not isinstance(text, str): ...` check, add step_id validation:

```python
        if step_id is not None:
            if block_id is None:
                _send_text(h, 422, "step_id requires block_id")
                return
            blocks_path = Path(dirs["response_dir"]) / "blocks.json"
            doc = blocks_model.load(blocks_path)
            try:
                blk = blocks_model.find_block(doc, block_id)
            except KeyError:
                _send_text(h, 422, f"unknown block_id {block_id!r}")
                return
            if (blk.get("kind") or "markdown") != "sequence":
                _send_text(h, 422, "step_id only valid for kind=sequence blocks")
                return
            spec = blk.get("spec") or {}
            valid_step_ids = {s.get("id") for s in (spec.get("steps") or [])}
            if step_id not in valid_step_ids:
                _send_text(h, 422, f"unknown step_id {step_id!r}")
                return
```

In the `evt = {...}` dict, add `"step_id": step_id` as a new field:

```python
        evt = {
            "block_id": block_id,
            "step_id": step_id,
            "type": comment_type,
            "text": text,
            "selected_text": selected_text,
            "images": images,
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest skills/annotate/tests/test_server.py -v`
Expected: all tests pass (new + pre-existing).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py
git commit -m "annotate: /api/submit accepts and validates optional step_id"
```

---

## Task 10: Browser JS — render sequence blocks + delegated click handler

**Files:**
- Modify: `skills/annotate/static/script.js`

- [ ] **Step 1: Inspect the existing render path**

Read `skills/annotate/static/script.js:212-260` to find the block-render loop (where it iterates `data.blocks` and creates `<section class="block">` elements with `data-block-id`). Note the exact variable name used for each block (likely `blk`) and the element that holds the rendered content (likely a child div with class `block-content`).

- [ ] **Step 2: Add `kind` dispatch in the render path**

In `skills/annotate/static/script.js`, locate the loop that processes `data.blocks` (around line 227). Inside the loop body, after creating the `section` element and setting `dataset.blockId`, add a kind dispatch:

```javascript
    const kind = blk.kind || "markdown";
    section.dataset.kind = kind;
    if (kind === "sequence") {
      // Server pre-rendered the SVG; inject as-is.
      content.innerHTML = blk.svg || "";
    } else {
      // Existing markdown path — render with markdown-it.
      const html = blockMd.render(blk.markdown || "");
      content.innerHTML = html;
    }
```

Replace the existing markdown-render line so it's the `else` branch above.

- [ ] **Step 3: Add delegated click handler for `[data-step-id]`**

In `skills/annotate/static/script.js`, locate the existing hover-action wiring at line ~106 (`document.querySelectorAll("[data-block-id]").forEach(block => { ... })`).

Inside `onHoverAction` (around line 177), where the existing code reads `block.dataset.blockId` and builds an annotation, also capture the clicked step (if any):

```javascript
  function onHoverAction(block, type, event) {
    // ... existing code ...
    const targetStep = event?.target?.closest("[data-step-id]");
    const stepId = targetStep && block.contains(targetStep) ? targetStep.dataset.stepId : null;
    // ... existing annotation object ...
    annot.step_id = stepId;   // null for non-diagram or whole-diagram clicks
    // ... rest unchanged ...
  }
```

Pass the click event through wherever `onHoverAction` is invoked (the button click handler at line ~130). Update the call site:

```javascript
        b.addEventListener("click", (ev) => {
          ev.stopPropagation();
          onHoverAction(block, t.id, ev);  // pass the event
        });
```

And in the function that submits the annotation (search for the POST to `/api/submit` or `/api/submit`), include `step_id: annot.step_id` in the payload body.

- [ ] **Step 4: Manual smoke check**

Build a tiny test session by hand:

```bash
# In a scratch shell, with the server running:
SERVER_URL=$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/annotate/server.json")))["url"]) ')
SID_JSON=$(curl -sf -X POST "$SERVER_URL/api/sessions" -H 'Content-Type: application/json' -d "{\"cwd\":\"$PWD\"}")
RESP_DIR=$(echo "$SID_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["response_dir"])')
URL=$(echo "$SID_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["url"])')
cat > "$RESP_DIR/meta.json" <<JSON
{"response_id":"resp-test","title":"diagram demo","claude_session_id":"local"}
JSON
cat > "$RESP_DIR/blocks.json" <<JSON
{"response_id":"resp-test","title":"diagram demo","blocks":[
  {"id":"b-0","markdown":"# Intro\nThis is prose.","version":1},
  {"id":"b-1","kind":"sequence","version":1,"spec":{
    "actors":[{"id":"a","label":"A"},{"id":"b","label":"B"}],
    "steps":[
      {"id":"s1","from":"a","to":"b","arrow":"request","label":"ping","sub":"first"},
      {"id":"s2","from":"b","to":"b","arrow":"self","label":"think","sub":"locally"},
      {"id":"s3","from":"b","to":"a","arrow":"event","label":"pong"}
    ]
  }}
]}
JSON
echo "Open: $URL"
```

Open the URL in a browser, hover the diagram, click step `s2` and submit a comment. Watch the events_dir; the written event must include `"step_id": "s2"`.

```bash
EVENTS_DIR=$(echo "$SID_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["events_dir"])')
ls "$EVENTS_DIR" && cat "$EVENTS_DIR"/*.json | python3 -m json.tool
```

Expected: an event file with `"step_id": "s2"`.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/static/script.js
git commit -m "annotate: client dispatches on kind; per-step click submits step_id"
```

---

## Task 11: End-to-end smoke test

**Files:**
- Create: `skills/annotate/tests/test_smoke_e2e_diagram.py`

- [ ] **Step 1: Write the smoke test**

Create `skills/annotate/tests/test_smoke_e2e_diagram.py`, mirroring the `ServerStartupTests` pattern in `test_server.py` (subprocess-spawned server, per-test session). Import the helpers from `test_server` rather than duplicating them.

```python
"""End-to-end smoke: mixed markdown + sequence doc through the live server."""
import http.client
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from skills.annotate import blocks as blocks_model
from skills.annotate.tests.test_server import (
    _create_session, _http_get, _start_server, _write_blocks,
)


class SequenceSmokeTests(unittest.TestCase):
    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="annotate-smoke-"))
        self.fake_home = Path(tempfile.mkdtemp(prefix="annotate-smoke-home-"))
        self.proc, self.info = _start_server(self.fake_home)
        self.sess = _create_session(self.info["port"], self.project)
        self.base = f"/s/{self.sess['sid']}"

    def tearDown(self):
        try:
            self.proc.terminate()
            self.proc.wait(timeout=2)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.project, ignore_errors=True)
        shutil.rmtree(self.fake_home, ignore_errors=True)

    def _post_json(self, path: str, payload: dict):
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", path, body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        body = resp.read().decode("utf-8")
        status = resp.status
        conn.close()
        return status, body

    def test_e2e_mixed_markdown_and_sequence(self):
        response_dir = Path(self.sess["response_dir"])
        spec_v1 = {
            "actors": [{"id": "a", "label": "A"}, {"id": "b", "label": "B"}],
            "steps": [
                {"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "ping"},
            ],
        }
        _write_blocks(response_dir, "resp-smoke", "smoke", [
            {"id": "b-0", "markdown": "# Intro\nProse here.", "version": 1},
            {"id": "b-1", "kind": "sequence", "spec": spec_v1, "version": 1},
        ])

        # 1. GET / returns 200 + diagram.css link.
        status, body = _http_get("localhost", self.info["port"], self.base + "/")
        self.assertEqual(status, 200)
        self.assertIn('href="/static/diagram.css"', body)

        # 2. GET /raw: markdown unchanged; sequence has svg + spec + kind.
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data = json.loads(body)
        md_blk = next(b for b in data["blocks"] if b["id"] == "b-0")
        seq_blk = next(b for b in data["blocks"] if b["id"] == "b-1")
        self.assertEqual(md_blk["kind"], "markdown")
        self.assertEqual(md_blk["markdown"], "# Intro\nProse here.")
        self.assertNotIn("svg", md_blk)
        self.assertEqual(seq_blk["kind"], "sequence")
        self.assertTrue(seq_blk["svg"].startswith("<svg"))
        self.assertIn('data-step-id="s1"', seq_blk["svg"])
        self.assertEqual(seq_blk["spec"], spec_v1)

        # 3. POST /api/submit with step_id → 202 + step_id stored on event.
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-1", "step_id": "s1", "type": "comment",
            "text": "what about retries?",
        })
        self.assertEqual(status, 202)
        events_dir = Path(self.sess["events_dir"])
        event_files = list(events_dir.glob("*.json"))
        self.assertEqual(len(event_files), 1)
        evt = json.loads(event_files[0].read_text())
        self.assertEqual(evt["step_id"], "s1")
        self.assertEqual(evt["block_id"], "b-1")

        # 4. Simulate Claude updating the spec to address the comment.
        blocks_path = Path(self.sess["response_dir"]) / "blocks.json"
        doc = blocks_model.load(blocks_path)
        spec_v2 = {
            "actors": spec_v1["actors"],
            "steps": [
                {"id": "s1", "from": "a", "to": "b", "arrow": "request",
                 "label": "ping", "sub": "retries 3× on 5xx"},
            ],
        }
        bumped = blocks_model.update_spec_block(doc, "b-1", spec_v2)
        self.assertTrue(bumped)
        blocks_model.save_atomic(blocks_path, doc)

        # 5. /raw now reflects the updated spec and re-rendered SVG.
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data2 = json.loads(body)
        seq2 = next(b for b in data2["blocks"] if b["id"] == "b-1")
        self.assertEqual(seq2["version"], 2)
        self.assertIn("retries 3× on 5xx", seq2["svg"])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the smoke test**

Run: `pytest skills/annotate/tests/test_smoke_e2e_diagram.py -v`
Expected: passes.

- [ ] **Step 3: Run the full test suite to catch regressions**

Run: `pytest skills/annotate/tests/ -v`
Expected: all annotate tests pass.

- [ ] **Step 4: Commit**

```bash
git add skills/annotate/tests/test_smoke_e2e_diagram.py
git commit -m "annotate: end-to-end smoke for mixed markdown + sequence doc"
```

---

## Task 12: SKILL.md updates — trigger criteria + diagram block-rewrite contract

**Files:**
- Modify: `skills/annotate/SKILL.md`

- [ ] **Step 1: Extend the Mode A trigger criteria**

In `skills/annotate/SKILL.md`, find the "## Mode A — Forward (Claude-initiated)" section (around line 16). Append a new sub-section right after the existing "DO NOT use" list and before "When in doubt, prefer the annotation view." (around line 32):

```markdown
### When to use a `kind: "sequence"` diagram block (Mode A extension)

A block in your response should be a sequence diagram (instead of prose) when ALL of:

- The content involves ≥ 2 named entities interacting (browser ↔ server, user ↔ system, two services...).
- The content has a clear temporal order — step 1, then step 2, ...
- Who-talks-to-whom matters — a numbered list loses that information.

Typical fits: code flows, request/response protocols, event lifecycles, deployment pipelines, state transitions tied to events over time.

**Do NOT use a sequence-diagram block for:**

- Single-actor flows (a numbered list does the job).
- Branching/decision logic where time isn't the dominant axis (flowcharts — not supported in v1).
- Static structure: class hierarchies, data shapes, dependency graphs.
- Anything that fits in 1–2 sentences.

**One diagram per flow.** Diagrams are heavier than prose blocks — visually and token-wise. A response that explains one flow gets one diagram block; longer explanations get prose blocks framing it. Don't emit two diagrams unless they're genuinely two separate flows.
```

- [ ] **Step 2: Extend "How to push a response" with the diagram-block shape**

Still in `SKILL.md`, find the existing "## How to push a response" section (around line 96). After the existing step describing the markdown block shape, add a sub-section:

```markdown
### Diagram block shape

A sequence-diagram block looks like this in `blocks.json`:

    {"id": "b-N", "kind": "sequence", "version": 1, "spec": {
      "title": "<short title>",
      "actors": [{"id": "<short-id>", "label": "<display name>"}, ...],   // ≥ 2
      "phases": [{"id": "<phase-id>", "label": "<UPPERCASE LABEL>", "start_at": "<step-id>"}, ...],  // optional; in step-order
      "steps": [
        {"id": "s1", "from": "<actor-id>", "to": "<actor-id>",
         "arrow": "request|event|self",
         "label": "<terse English>",
         "sub": "<optional italic sub-caption>"},
        ...
      ]
    }}

Block id remains `b-N` (assigned by `next_block_id`); step ids are `s1`, `s2`, ... per `next_step_id`. Both are stable across rewrites.

Arrow types are exactly three values:

- `request` — actor↔actor interaction; direction follows `from`/`to`.
- `event` — automatic / system-driven push.
- `self` — self-action; requires `from === to`.
```

- [ ] **Step 3: Add the diagram block-rewrite contract**

Still in `SKILL.md`, after the existing "## Block-rewrite contract" section (around line 205), add a new section:

```markdown
## Diagram block-rewrite contract

For `WEBCOMPANION_EVENT` payloads that target a `kind: "sequence"` block, the rewrite contract has three deltas from the markdown contract above:

1. **Targeted by default when `step_id` is present.** A comment on step `s4` ("does this fire once per click, or can it batch?") rewrites just that step's `label` and/or `sub`. Other steps untouched. Step ids stay stable across rewrites; new steps mint fresh ids via `next_step_id`.

2. **Whole-diagram comments (`step_id: null`)** apply across steps as needed — restructure phases, reorder steps, add/remove actors. Analogous to general comments with `block_id: null` in the markdown contract.

3. **Reject on a step** — either soften/withdraw the claim by rewriting the step, or hold the line by rewriting the sub-caption with reasoning. Don't drop the step silently. Same "fold the answer into the prose" spirit; here the "prose" is the spec.

Persist updates via `blocks_model.update_spec_block(doc, block_id, new_spec)` — version bumps only on real change (canonical-JSON content hash). Then `save_atomic` as today. Watcher re-emit safety is preserved.

**Off-topic comments** (user comments on `s4` about something that really belongs in `s2`) follow the same "use judgment" rule as the markdown contract: rewrite the targeted step to be clearer about its actual topic, or rewrite the neighboring step, or both.
```

- [ ] **Step 4: Update the payload description to mention `step_id`**

Still in `SKILL.md`, find the description of the `WEBCOMPANION_EVENT` payload fields (around line 180, where it lists `block_id`, `type`, `text`, `selected_text`, `images`). Insert a new field bullet after `block_id`:

```markdown
   - `step_id` — for `kind: "sequence"` blocks: the step row the user clicked, or `null` for whole-diagram comments. Absent/null for markdown blocks.
```

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/SKILL.md
git commit -m "annotate: SKILL.md — sequence-block trigger, shape, rewrite contract"
```

---

## Self-Review (run by the implementer at the end)

After all 12 tasks land, run:

```bash
pytest skills/annotate/tests/ -v
```

All annotate tests must pass. Then do a quick end-to-end manual check using the snippet from Task 10 Step 4 — open a real browser session with mixed markdown + sequence blocks, click a step, submit a comment, observe the event file contains `step_id`. Visual fidelity should match `.superpowers/brainstorm/42113-1779613437/content/diagram-block-mockup.html`.

**Spec coverage map** (every spec section ↔ task):

| Spec section | Covered by |
|---|---|
| Architecture: typed blocks | Task 1, 2, 8 |
| Spec schema | Task 3, 4, 5, 6 (renderer enforces it via validate()) |
| Arrow types fixed enum | Task 3 (validator) + Task 5 (renderer dispatch) |
| Validation rules | Task 3 |
| Server-side SVG renderer | Task 4, 5, 6 |
| Hit targets | Task 5 |
| Click → comment | Task 9 (server), Task 10 (client) |
| Block-rewrite contract | Task 1 (update_spec_block) + Task 12 (SKILL.md doc) |
| Backward compatibility | Task 8 (kind defaults to "markdown") |
| Testing — renderer | Task 3, 4, 5, 6 |
| Testing — blocks model | Task 1, 2 |
| Testing — /api/submit | Task 9 |
| Testing — end-to-end smoke | Task 11 |
| File touchpoints | All tasks combined |
