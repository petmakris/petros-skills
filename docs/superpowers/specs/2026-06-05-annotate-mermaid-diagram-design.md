# Design: Mermaid-backed `kind: "diagram"` block for annotate

**Date:** 2026-06-05
**Status:** Approved (design), pending implementation plan
**Skill:** `skills/annotate`

## Problem

The annotate skill can render one diagram type — `kind: "sequence"` — via a
hand-rolled SVG generator (`diagrams/sequence.py`). Sequence diagrams only fit
content with a temporal order and ≥2 interacting actors. Everything else that is
better seen than read — system architecture, branching/process flows, state
machines, data relationships — currently falls back to prose.

We want to generalize: when a response would be clearer as a block / architecture
/ flowchart / state / ER / class diagram, render it inside the annotate page too.

## Decisions (locked)

1. **Renderer: Mermaid-backed block kind.** A new `kind: "diagram"` whose spec
   carries Mermaid source. The server renders it to SVG via `mmdc` (already
   installed). Sequence stays as-is (hand-rolled) — not touched.
2. **Granularity: whole-diagram comments in v1.** Clicking the diagram block
   comments on the whole thing. Per-node (`data-annotate-id` on individual
   nodes/edges) is deferred to v2.
3. **Types in v1:** `flowchart`, `architecture`, `state`, `er`, `class`.

## Architecture

The feature mirrors the existing `sequence` path component-for-component. The
only novelty is delegating layout to an external engine instead of computing it
in Python.

### 1. `diagrams/mermaid.py` (new) — sibling of `diagrams/sequence.py`

- `SUPPORTED_TYPES = {"flowchart", "architecture", "state", "er", "class"}`
- `class ValidationError(ValueError)` — same shape as sequence's.
- `validate(spec) -> None`: raise `ValidationError` unless `spec["type"]` is in
  `SUPPORTED_TYPES` and `spec["source"]` is a non-empty string. Validation is
  deliberately light — Mermaid itself is the authoritative validator at render
  time, and a malformed source surfaces through the render error pill.
- `render(spec, block_id) -> str`: write `source` to a temp `.mmd`, run
  `mmdc -i <tmp>.mmd -o <tmp>.svg` (plus theme/init config, see Theming), read
  the resulting SVG back, post-process minimally (drop the XML prolog if present,
  add `class="annotate-diagram"` to the root `<svg>`), and return the SVG string.
  Unlike `sequence.render`, this does subprocess + temp-file I/O — unavoidable
  with an external engine, and isolated entirely within this module.

The `type` field is informational/validation-only in v1 (Mermaid infers the
diagram kind from the source header, e.g. `graph TD`, `stateDiagram-v2`). It is
retained in the spec for SKILL.md authoring guidance and future per-type theming.

### 2. `server.py` → `_render_block_for_raw` (edit)

Add one branch next to the existing `kind == "sequence"` branch:

```python
elif kind == "diagram":
    spec = blk.get("spec") or {}
    try:
        svg = render_mermaid(spec, block_id=blk["id"])
    except Exception as e:
        svg = <same compact error-pill SVG used by the sequence branch>
    base["spec"] = spec
    base["svg"] = svg
```

Reuses the existing error-pill fallback verbatim so a malformed diagram can never
crash `/raw` or blank the page. Add the import:
`from skills.annotate.diagrams.mermaid import render as render_mermaid`.

### 3. `static/script.js` + `static/diagram.css` (edit)

- Confirm the client's existing SVG-injection path (currently used to display the
  `svg` field of sequence blocks) also fires for `kind == "diagram"`. If it is
  hard-keyed to `"sequence"`, generalize the condition to accept `"diagram"` too.
- `diagram.css`: add minimal rules for `.annotate-diagram` (max-width 100%,
  centered, sensible margins). Mermaid carries most styling itself, so this is
  light.

### 4. `SKILL.md` (edit) — the trigger guidance ("native" behavior)

- Add a *"When to use a `kind: \"diagram\"` block"* subsection under Mode A,
  modeled on the existing sequence subsection. Trigger when content is better
  seen than read AND is one of: branching/process logic (flowchart), system /
  component architecture, state machine, entity relationships (ER), or static
  structure (class). One diagram per concept; pair with framing prose; the
  diagram must add clarity, not decorate.
- Update the two now-stale exclusions in the **sequence** subsection
  ("Branching/decision logic … flowcharts — not supported in v1" and "Static
  structure: class hierarchies, data shapes, dependency graphs") to redirect to
  the new `kind: "diagram"` block instead of saying unsupported.
- Document the block shape (see below) in the "Diagram block shape" area
  alongside the sequence shape.

Block shape:

```json
{"id": "section-N", "kind": "diagram", "spec": {
  "type": "flowchart|architecture|state|er|class",
  "title": "<short title>",
  "source": "<mermaid source>"
}}
```

### 5. Rewrite contract (Mode D) (edit)

A `WEBCOMPANION_EVENT` targeting a `kind: "diagram"` block (always `step_id:
null` in v1) rewrites `spec.source` to fold in the answer, persisted via the
existing `blocks.update_spec_block(doc, block_id, new_spec)` (content-hash-safe,
already used by sequence). Generalize the "Diagram block-rewrite contract"
section so it covers both `sequence` and `diagram` kinds; for `diagram` in v1
there is no step-level targeting. The existing server guard
("`step_id only valid for kind=sequence blocks`") needs no change — diagram
comments carry no `step_id`.

### 6. Tests (new)

- `tests/test_diagrams_mermaid.py`: `validate()` accepts a good spec and rejects
  (unknown type, empty source); `render()` of a basic `flowchart` returns a
  string containing `<svg`. Since `mmdc` is installed, `render` tests run live;
  guard with a skip if `mmdc` is absent so CI on a bare machine still passes.
- A smoke e2e paralleling `tests/test_smoke_e2e_diagram.py` (sequence) for the
  diagram kind, if the smoke harness generalizes cheaply.

## Known integration risk (verify FIRST in implementation)

Mermaid emits a `<style>` element **inside** the generated SVG, and the annotate
client sanitizer **strips `<style>`**. If the server-generated `svg` field is
injected through a trusted path that bypasses the markdown sanitizer (very likely
— the sequence SVG already relies on this), Mermaid's styling survives and we are
fine. If the `svg` field *does* pass through the sanitizer, the fallback is to
render with styles inlined onto elements (`mmdc --cssFile` / init theme producing
inline `style=` attributes rather than a `<style>` block). This must be confirmed
before building on top of the renderer.

## Dependencies

- `mmdc` (`@mermaid-js/mermaid-cli`) — already installed at
  `/opt/homebrew/bin/mmdc`; verified rendering a sequence diagram to SVG. The
  render path degrades to the error pill if `mmdc` is missing or fails.

## Out of scope (v2+)

- Per-node `data-annotate-id` injection and step-level commenting on diagrams.
- Alternative engine (D2).
- Auto-installing `mmdc`.
- Source-hash render caching (mitigation for the ~1–2s headless-Chrome render
  cost; acceptable for occasional use in v1).

## Success criteria

1. A `blocks.json` block with `kind: "diagram"` and a valid Mermaid `source`
   renders as a themed SVG in the annotate page for all five types.
2. A malformed diagram shows the compact error pill, not a blank page.
3. Commenting on a diagram block rewrites `spec.source` and the page updates the
   block in place (whole-diagram scope).
4. SKILL.md instructs Claude to reach for `kind: "diagram"` on the right content,
   and the sequence section no longer claims those cases are unsupported.
5. New tests pass; existing annotate tests still pass.
