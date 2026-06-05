# Mermaid Diagram Block Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `kind: "diagram"` block to the annotate skill that renders Mermaid source to SVG server-side, so flowchart/architecture/state/ER/class diagrams appear in the annotate page (whole-diagram commenting; per-node deferred to v2).

**Architecture:** Mirror the existing `kind: "sequence"` path component-for-component, but delegate layout to the `mmdc` CLI instead of hand-rolled SVG. A new `diagrams/mermaid.py` validates the spec and shells out to `mmdc`; `server.py` dispatches `kind == "diagram"` to it with the same error-pill fallback; the client injects the trusted SVG via `innerHTML` (bypassing the sanitizer, exactly as sequence does) but keeps the hover-actions strip for whole-diagram comments.

**Tech Stack:** Python 3.14 (stdlib `subprocess`/`tempfile`), pytest, `@mermaid-js/mermaid-cli` (`mmdc`, already installed), vanilla JS client.

**Spec:** `docs/superpowers/specs/2026-06-05-annotate-mermaid-diagram-design.md`

---

## File Structure

- **Create** `skills/annotate/diagrams/mermaid.py` — validator + `mmdc` renderer (the only new logic).
- **Create** `skills/annotate/tests/test_diagrams_mermaid.py` — validator + renderer + server-dispatch tests.
- **Modify** `skills/annotate/server.py` — add `elif kind == "diagram"` branch in `_render_block_for_raw` (~line 450) + import.
- **Modify** `skills/annotate/static/script.js` — 3 spots: `createBlockSection` (~460), `blockTitle` (~330), `updateBlockContent` (~1229).
- **Modify** `skills/annotate/static/diagram.css` — `.annotate-diagram` sizing.
- **Modify** `skills/annotate/SKILL.md` — trigger guidance, block shape, rewrite contract, fix stale sequence exclusions.

All commands below run from the worktree root (the `.claude/worktrees/annotate-mermaid-diagram` checkout of this repo). Tests run via `python3 -m pytest` from that root, which is how the 183-test baseline passed.

---

## Task 1: Mermaid spec validator

**Files:**
- Create: `skills/annotate/diagrams/mermaid.py`
- Test: `skills/annotate/tests/test_diagrams_mermaid.py`

- [ ] **Step 1: Write the failing validator tests**

Create `skills/annotate/tests/test_diagrams_mermaid.py`:

```python
"""Mermaid diagram validator + renderer tests."""
import shutil

import pytest

from skills.annotate.diagrams.mermaid import (
    ValidationError,
    RenderError,
    SUPPORTED_TYPES,
    validate,
    render,
)


def _minimal_spec():
    return {"type": "flowchart", "title": "Demo", "source": "graph TD\n  A-->B"}


def test_validate_minimal_spec_ok():
    validate(_minimal_spec())  # no raise


def test_validate_rejects_unknown_type():
    spec = _minimal_spec()
    spec["type"] = "gantt"
    with pytest.raises(ValidationError, match="type"):
        validate(spec)


def test_validate_rejects_missing_type():
    spec = _minimal_spec()
    del spec["type"]
    with pytest.raises(ValidationError, match="type"):
        validate(spec)


def test_validate_rejects_empty_source():
    spec = _minimal_spec()
    spec["source"] = "   "
    with pytest.raises(ValidationError, match="source"):
        validate(spec)


def test_validate_rejects_nonstring_source():
    spec = _minimal_spec()
    spec["source"] = 123
    with pytest.raises(ValidationError, match="source"):
        validate(spec)


def test_supported_types_cover_v1():
    assert set(SUPPORTED_TYPES) == {"flowchart", "architecture", "state", "er", "class"}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 -m pytest skills/annotate/tests/test_diagrams_mermaid.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'skills.annotate.diagrams.mermaid'`.

- [ ] **Step 3: Write the validator (minimal module)**

Create `skills/annotate/diagrams/mermaid.py`:

```python
"""Mermaid-backed diagram validator + server-side SVG renderer.

Renders a `kind: "diagram"` block's Mermaid source to SVG via the `mmdc`
CLI (mermaid-cli). Called by server.py when rendering a block with
kind == "diagram". Unlike diagrams/sequence.py (pure functions), render()
does subprocess + temp-file I/O because layout is delegated to an external
engine; that I/O is isolated entirely within this module.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
from typing import Any

SUPPORTED_TYPES = ("flowchart", "architecture", "state", "er", "class")

# mmdc spawns headless Chrome; a generous ceiling so a hung render can't
# wedge the request thread forever.
RENDER_TIMEOUT_S = 30


class ValidationError(ValueError):
    """Raised when a diagram spec is structurally invalid."""


class RenderError(RuntimeError):
    """Raised when mmdc is missing or fails to produce an SVG."""


def validate(spec: dict[str, Any]) -> None:
    """Raise ValidationError if the spec is malformed; otherwise return None."""
    dtype = spec.get("type")
    if dtype not in SUPPORTED_TYPES:
        raise ValidationError(
            f"unknown diagram type {dtype!r}; expected one of {SUPPORTED_TYPES}"
        )
    source = spec.get("source")
    if not isinstance(source, str) or not source.strip():
        raise ValidationError("diagram source must be a non-empty string")
```

- [ ] **Step 4: Run the validator tests to verify they pass**

Run: `python3 -m pytest skills/annotate/tests/test_diagrams_mermaid.py -q`
Expected: PASS — the 6 validator tests pass. (`render` is imported but not yet exercised; the import resolves because the next task adds it. If you split strictly, the `render` import line will fail here — so add the `render` stub in Step 3 too: see note.)

> Note: because the test module imports `render`, add this stub at the end of `mermaid.py` in Step 3 so the import resolves:
> ```python
> def render(spec: dict[str, Any], block_id: str) -> str:
>     raise NotImplementedError  # implemented in Task 2
> ```
> Task 2 replaces the stub body.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/diagrams/mermaid.py skills/annotate/tests/test_diagrams_mermaid.py
git commit -m "feat(annotate): mermaid diagram spec validator"
```

---

## Task 2: Mermaid SVG renderer (mmdc)

**Files:**
- Modify: `skills/annotate/diagrams/mermaid.py`
- Test: `skills/annotate/tests/test_diagrams_mermaid.py`

- [ ] **Step 1: Write the failing renderer tests**

Append to `skills/annotate/tests/test_diagrams_mermaid.py`:

```python
_HAVE_MMDC = shutil.which("mmdc") is not None
requires_mmdc = pytest.mark.skipif(not _HAVE_MMDC, reason="mmdc (mermaid-cli) not installed")


@requires_mmdc
def test_render_flowchart_returns_svg():
    svg = render(_minimal_spec(), block_id="section-1")
    assert "<svg" in svg
    assert "annotate-diagram" in svg
    # XML prolog stripped so the string can be injected straight into innerHTML.
    assert not svg.lstrip().startswith("<?xml")


@requires_mmdc
def test_render_state_diagram_returns_svg():
    spec = {"type": "state", "title": "S",
            "source": "stateDiagram-v2\n  [*] --> Idle\n  Idle --> Running"}
    assert "<svg" in render(spec, block_id="section-2")


def test_render_rejects_bad_spec_before_shelling_out():
    with pytest.raises(ValidationError):
        render({"type": "gantt", "source": "x"}, block_id="section-3")


@requires_mmdc
def test_render_raises_on_invalid_mermaid_source():
    spec = {"type": "flowchart", "title": "bad",
            "source": "this is not valid mermaid @@@ ->"}
    with pytest.raises(RenderError):
        render(spec, block_id="section-4")
```

- [ ] **Step 2: Run the renderer tests to verify they fail**

Run: `python3 -m pytest skills/annotate/tests/test_diagrams_mermaid.py -q`
Expected: FAIL — `NotImplementedError` from the stub (or skips for mmdc-gated tests if mmdc is absent; `test_render_rejects_bad_spec_before_shelling_out` still fails because the stub raises NotImplementedError before validating).

- [ ] **Step 3: Implement `render` (replace the stub)**

In `skills/annotate/diagrams/mermaid.py`, replace the `render` stub with:

```python
def render(spec: dict[str, Any], block_id: str) -> str:
    """Render a validated spec to an SVG string.

    Raises ValidationError if the spec is malformed, RenderError if mmdc is
    missing or fails. The block_id is accepted for signature parity with
    diagrams/sequence.render (v1 injects no per-node hit targets).
    """
    validate(spec)

    mmdc = shutil.which("mmdc")
    if not mmdc:
        raise RenderError("mmdc (mermaid-cli) not found on PATH")

    source = spec["source"]
    with tempfile.TemporaryDirectory() as td:
        in_path = os.path.join(td, "in.mmd")
        out_path = os.path.join(td, "out.svg")
        with open(in_path, "w", encoding="utf-8") as f:
            f.write(source)
        try:
            proc = subprocess.run(
                [mmdc, "-i", in_path, "-o", out_path, "-t", "neutral", "-b", "transparent"],
                capture_output=True,
                text=True,
                timeout=RENDER_TIMEOUT_S,
            )
        except subprocess.TimeoutExpired as e:
            raise RenderError(f"mmdc timed out after {RENDER_TIMEOUT_S}s") from e
        if proc.returncode != 0 or not os.path.exists(out_path):
            detail = (proc.stderr or proc.stdout or "").strip()
            raise RenderError(f"mmdc failed: {detail or 'no output produced'}")
        with open(out_path, "r", encoding="utf-8") as f:
            svg = f.read()

    return _postprocess(svg)


_XML_PROLOG = re.compile(r"^\s*<\?xml[^>]*\?>\s*", re.IGNORECASE)


def _postprocess(svg: str) -> str:
    """Strip the XML prolog and tag the root <svg> with our CSS hook class."""
    svg = _XML_PROLOG.sub("", svg).strip()
    if "annotate-diagram" not in svg:
        svg = re.sub(r"<svg\b", '<svg class="annotate-diagram"', svg, count=1)
    return svg
```

- [ ] **Step 4: Run the renderer tests to verify they pass**

Run: `python3 -m pytest skills/annotate/tests/test_diagrams_mermaid.py -q`
Expected: PASS — all tests pass (mmdc is installed, so the gated tests run; ~3–5s for the live renders).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/diagrams/mermaid.py skills/annotate/tests/test_diagrams_mermaid.py
git commit -m "feat(annotate): render mermaid diagrams to SVG via mmdc"
```

---

## Task 3: Server dispatch for `kind: "diagram"`

**Files:**
- Modify: `skills/annotate/server.py` (import near line 22; branch in `_render_block_for_raw` ~line 450)
- Test: `skills/annotate/tests/test_diagrams_mermaid.py`

- [ ] **Step 1: Write the failing server-dispatch tests**

Append to `skills/annotate/tests/test_diagrams_mermaid.py`:

```python
from skills.annotate.server import _render_block_for_raw


@requires_mmdc
def test_server_renders_diagram_block():
    blk = {"id": "section-1", "kind": "diagram", "spec": _minimal_spec()}
    out = _render_block_for_raw(blk, version=1)
    assert out["kind"] == "diagram"
    assert "<svg" in out["svg"]
    assert out["spec"] == _minimal_spec()


def test_server_diagram_render_failure_yields_error_pill():
    # Unknown type → mermaid.validate raises → server catches → error pill,
    # never an exception that blanks /raw. (No mmdc needed: validation fails first.)
    blk = {"id": "section-9", "kind": "diagram", "spec": {"type": "gantt", "source": "x"}}
    out = _render_block_for_raw(blk, version=1)
    assert out["kind"] == "diagram"
    assert "diagram render failed" in out["svg"]
```

- [ ] **Step 2: Run to verify failure**

Run: `python3 -m pytest skills/annotate/tests/test_diagrams_mermaid.py -k server -q`
Expected: FAIL — `_render_block_for_raw` falls into the `else` markdown branch for an unknown kind, so `out["svg"]` is missing (`KeyError`) / `out["kind"]` is `"diagram"` but no svg.

- [ ] **Step 3: Add the import to `server.py`**

In `skills/annotate/server.py`, next to line 22 (`from skills.annotate.diagrams.sequence import render`), add:

```python
from skills.annotate.diagrams.mermaid import render as render_mermaid
```

- [ ] **Step 4: Add the dispatch branch in `_render_block_for_raw`**

In `skills/annotate/server.py`, in `_render_block_for_raw`, insert a new branch immediately AFTER the `if kind == "sequence":` block and BEFORE `elif kind == "choice":` (currently ~line 450):

```python
    elif kind == "diagram":
        spec = blk.get("spec") or {}
        try:
            svg = render_mermaid(spec, block_id=blk["id"])
        except Exception as e:
            # Same compact error pill as the sequence branch: one malformed
            # block must never crash /raw and blank the page.
            svg = (
                f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 360 36" '
                f'class="annotate-diagram annotate-diagram-error" '
                f'data-block-id="{html_escape(blk["id"])}" '
                f'role="img" aria-label="diagram failed to render">'
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

- [ ] **Step 5: Run to verify pass**

Run: `python3 -m pytest skills/annotate/tests/test_diagrams_mermaid.py -q`
Expected: PASS — all diagram tests pass, including the two server-dispatch tests.

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_diagrams_mermaid.py
git commit -m "feat(annotate): dispatch kind:diagram blocks to mermaid renderer"
```

---

## Task 4: Client rendering for `kind: "diagram"`

No unit-test harness exists for the vanilla-JS client (it has Playwright `.cjs` e2e tests). These three edits are verified by the full suite (Task 6) plus a manual browser check (Task 7). Each edit is minimal and mirrors the existing `"sequence"` handling.

**Files:**
- Modify: `skills/annotate/static/script.js` (3 spots)

- [ ] **Step 1: Inject the SVG in `createBlockSection`**

In `skills/annotate/static/script.js`, in `createBlockSection`, the content branch currently reads (~line 460):

```javascript
    if (kind === "sequence") {
      // Server pre-rendered the SVG; inject as-is.
      content.innerHTML = blk.svg || "";
      content.addEventListener("click", (ev) => {
        onHoverAction(section, "comment", ev);
      });
    } else if (kind === "choice") {
```

Insert a `diagram` branch between the sequence and choice branches:

```javascript
    if (kind === "sequence") {
      // Server pre-rendered the SVG; inject as-is.
      content.innerHTML = blk.svg || "";
      content.addEventListener("click", (ev) => {
        onHoverAction(section, "comment", ev);
      });
    } else if (kind === "diagram") {
      // Server pre-rendered the Mermaid SVG; inject as-is. This is trusted
      // server output and deliberately bypasses sanitizeFreeHtml so Mermaid's
      // inline <style> survives. v1 has no per-node hit targets, so there is
      // no step-click listener — whole-diagram comments come from the
      // hover-actions strip (renderHoverActions does not skip "diagram").
      content.innerHTML = blk.svg || "";
    } else if (kind === "choice") {
```

- [ ] **Step 2: Title fallback in `blockTitle`**

In `skills/annotate/static/script.js`, `blockTitle` (~line 330) currently reads:

```javascript
    if ((blk.kind || "markdown") === "sequence") {
      const t = blk.spec && blk.spec.title;
      return (t && String(t).trim()) || "Diagram";
    }
```

Change the condition to cover both spec-backed diagram kinds:

```javascript
    const k = blk.kind || "markdown";
    if (k === "sequence" || k === "diagram") {
      const t = blk.spec && blk.spec.title;
      return (t && String(t).trim()) || "Diagram";
    }
```

- [ ] **Step 3: SVG injection in `updateBlockContent`**

In `skills/annotate/static/script.js`, `updateBlockContent` (~line 1229) currently reads:

```javascript
      if (newKind === "sequence") {
        content.innerHTML = blk.svg || "";
      } else if (blockMd) {
```

Change the condition:

```javascript
      if (newKind === "sequence" || newKind === "diagram") {
        content.innerHTML = blk.svg || "";
      } else if (blockMd) {
```

(The kind-flip guard at ~line 1221 already recreates the section via `createBlockSection` when `newKind !== oldKind`, so a markdown→diagram rewrite is handled by Step 1's branch.)

- [ ] **Step 4: Syntax-check the JS**

Run: `node --check skills/annotate/static/script.js`
Expected: no output, exit 0 (file parses).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/static/script.js
git commit -m "feat(annotate): client rendering for kind:diagram blocks"
```

---

## Task 5: Diagram block CSS

**Files:**
- Modify: `skills/annotate/static/diagram.css`

- [ ] **Step 1: Append the `.annotate-diagram` rule**

Append to `skills/annotate/static/diagram.css`:

```css
/* Mermaid-rendered diagram blocks (kind: "diagram"). The SVG carries its own
   inline styling from mmdc; we only constrain its size and center it within
   the card so wide diagrams scale down instead of overflowing. */
.annotate-diagram {
  display: block;
  max-width: 100%;
  height: auto;
  margin: 0 auto;
}
.annotate-diagram-error {
  display: block;
  margin: 4px 0;
}
```

- [ ] **Step 2: Commit**

```bash
git add skills/annotate/static/diagram.css
git commit -m "style(annotate): size and center kind:diagram SVGs"
```

---

## Task 6: SKILL.md guidance

**Files:**
- Modify: `skills/annotate/SKILL.md`

- [ ] **Step 1: Fix stale exclusions in the sequence subsection**

In `skills/annotate/SKILL.md`, the sequence subsection's "Do NOT use" list currently reads:

```markdown
- Branching/decision logic where time isn't the dominant axis (flowcharts — not supported in v1).
- Static structure: class hierarchies, data shapes, dependency graphs.
```

Replace those two bullets with:

```markdown
- Branching/decision logic where time isn't the dominant axis — use a `kind: "diagram"` block (flowchart).
- Static structure: class hierarchies, data shapes, dependency graphs, system architecture — use a `kind: "diagram"` block.
```

- [ ] **Step 2: Add the diagram trigger subsection**

In `skills/annotate/SKILL.md`, immediately AFTER the sequence subsection (after the "One diagram per flow." paragraph, before "### When to use a `kind: \"choice\"` block"), insert:

```markdown
### When to use a `kind: "diagram"` block (Mode A extension)

Emit a `kind: "diagram"` block when content is clearer seen than read AND it is
one of these shapes (the cases a sequence diagram does NOT cover):

- **flowchart** — branching/decision logic, process flows, block diagrams.
- **architecture** — system/service architecture, how components connect.
- **state** — state machines, lifecycle transitions.
- **er** — entity-relationship / data-model shapes.
- **class** — class hierarchies, static structure.

The block carries Mermaid source; the server renders it to SVG with `mmdc`.

**Do NOT use a `kind: "diagram"` block for:**

- Temporal actor↔actor flows — that's a `kind: "sequence"` block.
- Anything that fits in 1–2 sentences, or a short list that reads fine as prose.

**One diagram per concept.** Like sequence blocks, diagrams are heavier than
prose — visually and token-wise. Frame the diagram with a short prose block; the
diagram must add clarity, not decorate.
```

- [ ] **Step 3: Add the diagram block shape**

In `skills/annotate/SKILL.md`, immediately AFTER the "### Diagram block shape" section that documents the sequence shape (after the "Arrow types are exactly three values" list), insert:

```markdown
### Diagram (Mermaid) block shape

A `kind: "diagram"` block looks like this in `blocks.json`:

    {"id": "section-N", "kind": "diagram", "spec": {
      "type": "flowchart|architecture|state|er|class",
      "title": "<short title>",
      "source": "<mermaid source>"
    }}

`type` selects the diagram family (validated server-side); `source` is raw
Mermaid. The server renders it to SVG via `mmdc` and themes it to the page. If
the source is invalid or `mmdc` fails, the block shows a compact error pill
instead of blanking the page. v1 has whole-diagram commenting only — there are
no per-node hit targets, so comments arrive with `step_id: null`.
```

- [ ] **Step 4: Generalize the diagram block-rewrite contract**

In `skills/annotate/SKILL.md`, the "## Diagram block-rewrite contract" section opens with a sentence scoped to sequence. Add a paragraph at the END of that section:

```markdown
**`kind: "diagram"` (Mermaid) blocks** have no per-step targeting in v1: a
comment always arrives with `step_id: null` and applies to the whole diagram.
Rewrite `spec.source` (and `spec.title` if warranted) to fold in the answer,
then persist with `blocks.update_spec_block(doc, block_id, new_spec)` — the same
content-hash-safe helper used for sequence specs — and `save_atomic`. To convert
a diagram to/from prose, treat it as a kind change (drop `kind`/`spec`, set
`markdown`) exactly as for other spec blocks.
```

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/SKILL.md
git commit -m "docs(annotate): document kind:diagram trigger, shape, rewrite contract"
```

---

## Task 7: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run the whole annotate test suite**

Run: `python3 -m pytest skills/annotate/tests -q`
Expected: PASS — the prior 183 baseline tests plus the new `test_diagrams_mermaid.py` tests all pass, 0 failures.

- [ ] **Step 2: Manual end-to-end render check (one-shot, no server)**

Run this from the worktree root to confirm a real diagram renders through the server dispatch and lands as valid SVG:

```bash
python3 - <<'PY'
from skills.annotate.server import _render_block_for_raw
blk = {"id": "section-1", "kind": "diagram", "spec": {
    "type": "architecture", "title": "Demo",
    "source": "flowchart LR\n  Client-->API\n  API-->DB[(Postgres)]\n  API-->Cache[(Redis)]"}}
out = _render_block_for_raw(blk, version=1)
print("kind:", out["kind"])
print("has <svg>:", "<svg" in out["svg"])
print("has annotate-diagram class:", "annotate-diagram" in out["svg"])
open("/tmp/annotate-diagram-check.svg", "w").write(out["svg"])
print("wrote /tmp/annotate-diagram-check.svg")
PY
open /tmp/annotate-diagram-check.svg
```

Expected: prints `kind: diagram`, `has <svg>: True`, `has annotate-diagram class: True`, and opens a rendered architecture-style diagram in your browser/Preview. Confirm Mermaid's styling is present (boxes are styled, not raw black-on-white) — this confirms the `<style>` survived post-processing.

- [ ] **Step 3: Optional live browser smoke**

If you want a full in-page check: push a session containing a `kind: "diagram"` block via the normal annotate flow (the skill's "How to push a response" steps), open the URL, confirm the diagram renders inside a card, the hover-actions strip appears on hover, and clicking comment → submitting rewrites the block. (Covered functionally by Tasks 3–4; this is a visual confirmation.)

- [ ] **Step 4: Final commit (if any verification fixups were needed)**

```bash
git add -A
git commit -m "test(annotate): verify kind:diagram end-to-end"
```

---

## Self-Review Notes (author-completed)

- **Spec coverage:** mermaid.py (Tasks 1–2) ✓; server dispatch + error pill (Task 3) ✓; client SVG injection + sanitizer-bypass confirmed in code (Task 4) ✓; CSS (Task 5) ✓; SKILL.md trigger + shape + rewrite contract + stale-exclusion fix (Task 6) ✓; tests + manual e2e (Tasks 1–3, 7) ✓; `mmdc` dependency + error-pill degradation ✓. Out-of-scope items (per-node, D2, auto-install, caching) intentionally omitted.
- **Integration risk from spec resolved:** script.js:460 injects `blk.svg` via `innerHTML` directly, NOT through `sanitizeFreeHtml` (which only runs on the markdown branch, line 477). Mermaid's inline `<style>` therefore survives. No fallback needed.
- **Type/name consistency:** `render_mermaid` (server alias) ↔ `render` (mermaid.py); `SUPPORTED_TYPES`, `ValidationError`, `RenderError` used consistently across module + tests; `update_spec_block` confirmed kind-agnostic (blocks.py:111).
- **No placeholders:** every code/edit step shows complete content and exact insertion point.
