# Annotate sequence-diagram blocks — design

**Status:** design approved, ready for plan
**Date:** 2026-05-24

## Goal

Give the `annotate` skill a way to render explanations as sequence diagrams instead of (or alongside) prose, so the user can absorb code flows, event sequences, and state-over-time more naturally — visually, with simplified English labels — than reading paragraphs of text.

The diagram style is fixed: actors as columns at the top, time flowing top→bottom, numbered steps with terse arrow labels and italic sub-captions, optional phase bands, color-coded arrow types with a legend. This is the style established in the reference screenshots, and v1 ships exactly that style (no Mermaid baseline, no alternative diagram types).

## Non-goals (v1)

- State machine, flowchart, class/ER/dependency diagrams. Different shapes, different renderers; revisit when a real use case shows up.
- Phase-header and actor-pill clicks. The data is in the SVG; no comment UI on them yet.
- Interactive zoom/pan on the SVG.
- Browser-side editing of diagrams. Only Claude rewrites them, via the comment flow.
- Mermaid-source import or export.

## When Claude reaches for a diagram block

Addition to `SKILL.md`, slotting into the existing Mode A trigger language. Same auto-decide logic — diagrams are simply another block kind Claude may emit when composing a response.

**Use a `kind: "sequence"` block when the content has all of:**
- ≥ 2 named entities interacting (browser ↔ server, two services, user ↔ system, ...)
- A clear temporal order — step 1, then step 2, ...
- Who-talks-to-whom matters — a numbered list loses that information.

Typical fits: code flows, request/response protocols, event lifecycles, deployment pipelines, state transitions tied to events over time.

**Don't use a sequence-diagram block for:**
- Single-actor flows (a numbered list does the job).
- Branching/decision logic where time isn't the dominant axis (that's a flowchart — out of v1 scope).
- Static structure: class hierarchies, data shapes, dependency graphs.
- Anything that fits in 1–2 sentences.

**One per flow.** Diagrams are heavier than prose blocks — visually and token-wise. A response that explains one flow gets one diagram block; longer explanations get prose blocks framing it. Don't emit two diagrams unless they're genuinely two separate flows.

## Architecture: typed blocks

`blocks.json` is extended so each block carries an explicit `kind`. Existing markdown-only blocks become `kind: "markdown"`. New diagram blocks are `kind: "sequence"`.

A block omitting `kind` is treated as `"markdown"` for backward compatibility — no migration needed on the wire format.

This re-introduces the typed-block schema that the reverted trace-block work used. The schema itself was sound; what was abandoned then was the trace-player direction, not the model. Re-using the pattern keeps the door open for future kinds (state machines, etc.) without further schema work.

### Spec schema

```json
{
  "id": "b-2",
  "kind": "sequence",
  "version": 1,
  "spec": {
    "title": "How clicking a block triggers Claude to respond",
    "actors": [
      {"id": "browser",  "label": "Browser"},
      {"id": "server",   "label": "Annotate server"},
      {"id": "watcher",  "label": "Watcher (Monitor)"},
      {"id": "claude",   "label": "Claude"}
    ],
    "phases": [
      {"id": "submit",  "label": "SUBMIT",  "start_at": "s1"},
      {"id": "wake",    "label": "WAKE",    "start_at": "s3"},
      {"id": "respond", "label": "RESPOND", "start_at": "s5"}
    ],
    "steps": [
      {"id": "s1", "from": "browser", "to": "server",  "arrow": "request", "label": "POST /api/submit",      "sub": "block_id · selected_text · comment"},
      {"id": "s2", "from": "server",  "to": "server",  "arrow": "self",    "label": "write event JSON",       "sub": "atomic tmp → rename"},
      {"id": "s3", "from": "watcher", "to": "watcher", "arrow": "self",    "label": "poll events_dir",        "sub": "~200 ms loop"},
      {"id": "s4", "from": "watcher", "to": "claude",  "arrow": "event",   "label": "WEBCOMPANION_EVENT",     "sub": "stdout banner"},
      {"id": "s5", "from": "claude",  "to": "claude",  "arrow": "self",    "label": "rewrite block markdown", "sub": "block-rewrite contract"},
      {"id": "s6", "from": "claude",  "to": "server",  "arrow": "request", "label": "write blocks.json",      "sub": "version bumps on real change"},
      {"id": "s7", "from": "server",  "to": "browser", "arrow": "event",   "label": "poll · new version",     "sub": "re-render single block"}
    ]
  }
}
```

### Arrow types (fixed enum)

Three values, matching the legend in the reference screenshots. The enum is fixed (not extensible per-diagram) so the visual stays consistent across diagrams — same reason Mermaid doesn't let you redefine its arrow styles per call.

- `request` — solid purple, actor↔actor interaction; direction follows `from`/`to`.
- `event` — dashed amber, automatic / system-driven push.
- `self` — green curve, self-action; requires `from === to`.

### Validation rules

Validation runs when the server loads `blocks.json` to render a block, and when `/api/submit` receives a `step_id`. Claude writes `blocks.json` directly to disk (per existing annotate flow); the server is the validation chokepoint, not the write path.

- `from` and `to` must resolve to declared actor IDs.
- `arrow: "self"` ⇔ `from === to`.
- Step IDs unique within the spec, stable across rewrites. Convention: `s1`, `s2`, … minted by a `next_step_id` helper analogous to `next_block_id`.
- `phases[*].start_at` must reference an existing step ID. The `phases` array must be sorted such that each phase's `start_at` step appears before the next phase's `start_at` step in the `steps` array (phases don't interleave).
- ≥ 2 actors, ≥ 1 step.
- Phases optional — a diagram without phase bands omits the `phases` array.

Validation failures at render time fall back to a server-side error block (don't crash the page); failures from `/api/submit` return 422 with the offending path.

## Server-side SVG renderer

**Module:** new file `skills/annotate/diagrams/sequence.py`.

```python
def render(spec: dict, block_id: str) -> str:
    """Validate spec, compute layout, emit SVG string with hit-target IDs."""
```

Pure function, no I/O. Called by the existing block-render path when it sees `kind: "sequence"`. Layout is deterministic:

- Actor columns evenly spaced; column count = `len(actors)`.
- Step rows fixed-height (~56 px).
- Phase bands group consecutive steps based on `start_at`.
- Self-loops use a curved cubic-bezier path from the lifeline out to the right and back.
- Arrow direction follows `from`/`to`; cross-actor arrows are straight lines between the two lifelines.

**Static assets:** one CSS file added to the already-injected head bundle, shipping `.arr-request` / `.arr-event` / `.arr-self` styles, actor-pill styling, phase-band styling, row hover wash, etc. Visual matches the mockup at `.superpowers/brainstorm/.../diagram-block-mockup.html`.

### Hit targets

Each step renders as:

```html
<g class="step-row" data-block-id="b-2" data-step-id="s4">
  <rect class="row-bg" width="100%" height="56"/>
  <!-- arrow, labels, sub-caption -->
</g>
```

The full-width transparent `row-bg` rect makes the whole row clickable, not just the narrow arrow line.

## Click → comment

**Browser-side:** extend the existing click handler with a delegated listener for `[data-step-id]` inside diagram blocks. The POST `/api/submit` payload gains one optional field:

```json
{
  "block_id": "b-2",
  "step_id":  "s4",
  "type":     "comment",
  "text":     "...",
  "selected_text": null
}
```

`step_id: null` (or omitted) when the user clicked empty space inside the diagram block → whole-diagram comment. Same shape as `selected_text` being `null` for whole-block prose comments.

**Server-side:** `POST /api/submit` validates that `step_id` (when present) exists in the targeted block's spec; rejects with 422 otherwise. Parallel to how the existing endpoint rejects unknown `block_id`.

**V1 click scope:** step rows + whole-diagram clicks only. Phase-header and actor-pill clicks are deferred; the data is already in the SVG if/when we want to wire them.

## Block-rewrite contract for diagram blocks

Parallel to the existing markdown block-rewrite contract, with three deltas:

**1. Targeted by default when `step_id` is present.** A comment on `s4` ("does this fire once per click, or can it batch?") rewrites just `s4`'s `label`/`sub` — other steps untouched. Step IDs stay stable across rewrites; new steps mint fresh IDs (same `next_*_id` pattern blocks.py uses).

**2. Whole-diagram comments (`step_id: null`)** apply across steps as needed — restructure phases, reorder steps, add/remove actors. Analogous to general comments with `block_id: null` in the existing contract.

**3. Reject on a step** — either soften/withdraw the claim by rewriting the step, or hold the line by rewriting the sub-caption with reasoning. Don't drop the step silently. Same "fold the answer into the prose" spirit; here the "prose" is the spec.

**Spec re-save:** Claude writes the full updated `blocks.json` atomically. Version bumps only on actual spec change — same content-hash dedup `update_block` already does, just applied to a canonical JSON serialization of the spec (sorted keys, no whitespace). Watcher re-emit safety preserved.

**Off-topic comments** (user comments on `s4` about something that really belongs in `s2`) follow the same "use judgment" rule as today: rewrite the targeted step to be clearer about its actual topic, or rewrite the neighboring step, or both.

## Backward compatibility

- Existing sessions without `kind` on blocks continue to work — the loader treats absent `kind` as `"markdown"`.
- `blocks.py:update_block` keeps its current signature for markdown updates. A new `update_spec_block(doc, block_id, new_spec)` helper handles the spec-comparison + version-bump path; both share the content-hash dedup logic.
- The `find_block` and `next_block_id` helpers are kind-agnostic — no changes needed.

## Testing

Parallels the existing `skills/annotate/tests/` layout.

1. **Renderer unit tests** — `tests/test_diagrams_sequence.py`
   - Spec → SVG: actor pills, lifelines, step rows, phase bands, arrow types all present with expected attributes.
   - Self-loop renders curved path; cross-actor arrows are straight lines with correct from/to x-coordinates.
   - Validation rejects bad specs (unknown actor in `from`/`to`, `arrow: "self"` with `from != to`, duplicate step IDs, phase `start_at` not found).
   - Element IDs in output match step IDs in input.

2. **Blocks-model tests** — extend `tests/test_blocks.py`
   - Round-trip of `kind: "sequence"` blocks through `load`/`save_atomic`.
   - `update_spec_block` bumps `version`; identical spec is a no-op (content-hash dedup, same guarantee markdown blocks have).
   - Absent `kind` loads as `"markdown"` (back-compat).

3. **Server `/api/submit` tests** — extend the existing endpoint tests
   - `step_id` present + valid → 202, event written with the field.
   - `step_id` present + unknown → 422.
   - `step_id` absent → 202 (whole-diagram comment).

4. **End-to-end smoke** — `tests/test_smoke_e2e_diagram.py`, mirroring the existing mixed-markdown smoke pattern. Push a session with mixed prose + sequence blocks; render once; simulate a per-step click event; run an update; verify version bump and SVG re-render.

## File touchpoints (anticipated)

- `skills/annotate/blocks.py` — add `kind` awareness, `update_spec_block` helper, content-hash for specs.
- `skills/annotate/diagrams/sequence.py` (new) — spec validation + SVG renderer.
- `skills/annotate/diagrams/__init__.py` (new).
- `skills/annotate/server.py` — dispatch on `kind` at block-render time; extend `POST /api/submit` schema with optional `step_id`; validate.
- `skills/annotate/static/` — new CSS for diagram styling; extend click-handler JS with `[data-step-id]` delegation.
- `skills/annotate/SKILL.md` — extend Mode A trigger language with the "when to use a sequence block" criteria; add a short "diagram block-rewrite contract" section parallel to the existing one.
- `skills/annotate/tests/` — four new/extended test modules per the Testing section.

## Reference mockup

The reference for visual fidelity is the validated mockup at `.superpowers/brainstorm/42113-1779613437/content/diagram-block-mockup.html` (gitignored; survives only locally). The final renderer's output should be 1:1 with that mockup's SVG.
