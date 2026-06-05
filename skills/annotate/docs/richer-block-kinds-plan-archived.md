# Richer block kinds: comparison, table, callout (ARCHIVED)

> **Archived 2026-05-25.** This plan proposed three new typed block
> `kind`s (`comparison`, `table`, `callout`) with structured specs and
> server-side renderers. After review we reversed course: the typed-kind
> approach would lock the tool's expressive surface to whatever kinds we
> happen to define, and that constraint compounds as Claude's HTML
> authoring improves. The replacement direction is documented in
> `free-html-direction.md` — markdown-it runs with `html: true`, a
> conservative client-side sanitizer scrubs dangerous nodes, and
> commentable sub-units use `data-annotate-id="..."`. The visuals this
> plan described are still useful — Claude now emits them as inline
> HTML inside markdown blocks rather than as separate kinds.
>
> Kept as a record of the path considered and rejected.

## Why this plan exists

Today every block is one of two kinds: `markdown` (free prose, renderer turns it into styled HTML) or `sequence` (structured spec → SVG diagram). The bulk of the surface is markdown-first. That's fine for most prose, but it makes certain content shapes — side-by-side comparisons, dense tabular data, "watch out" highlights — feel cramped. Authors either fight markdown tables, drop into bullets, or just lose the visual.

We're adding three new typed kinds rather than a raw-HTML escape hatch. The reason: the annotation loop depends on Claude being able to *re-emit* a block cleanly when a comment comes in (see [SKILL.md:306 — block-rewrite contract](../SKILL.md)). Markdown and structured specs round-trip well; arbitrary HTML drifts, breaks styling, and makes "what's the commentable unit" ambiguous. We extend the `kind` registry the same way `sequence` was added.

## Pattern (inherited from `sequence`)

1. New `kind` literal in `blocks.json` block records.
2. Spec lives under `block["spec"]`. Block id is `b-N` from `next_block_id()`; sub-unit ids are stable per spec.
3. Renderer is a pure function in `skills/annotate/diagrams/<kind>.py` (despite the folder name; we may rename or namespace later). Inputs: `spec` + `block_id`. Outputs: an HTML/SVG fragment.
4. `server.py:_render_block_for_raw` routes by `kind` to that renderer.
5. Updates use `blocks.update_spec_block(doc, block_id, new_spec)` — canonical-JSON dedup, version bump only on real change.
6. Watcher events carry `block_id` + optional `step_id` (we'll call it that for parity; semantically it's "sub-unit id" — a column, a row, etc.).

We are not introducing a parallel `update_*_block` per kind. The existing `update_spec_block` is generic over spec dict and stays.

---

## 1. `kind: "comparison"`

### Purpose

Side-by-side weigh-up of N options across a fixed set of axes. Use it when the content is "X vs Y (vs Z)" — migration strategies, library choices, before/after, design tradeoffs. Three columns or fewer is the sweet spot; more starts looking like a table.

### Spec shape

```json
{
  "id": "b-3",
  "kind": "comparison",
  "version": 1,
  "spec": {
    "title": "Migration strategies",
    "axes": ["Cost", "Risk", "Effort", "Reversibility"],
    "columns": [
      {
        "id": "c1",
        "title": "Big-bang cutover",
        "values": {
          "Cost": "Low",
          "Risk": "High — single irreversible window",
          "Effort": "2 weeks",
          "Reversibility": "None"
        },
        "verdict": "rejected"
      },
      {
        "id": "c2",
        "title": "Incremental dual-write",
        "values": {
          "Cost": "Medium",
          "Risk": "Low",
          "Effort": "6 weeks",
          "Reversibility": "Each step revertible"
        },
        "verdict": "preferred"
      }
    ]
  }
}
```

Rules:
- `axes` ≥ 1, `columns` ≥ 2.
- `column.id` is `c1`, `c2`, ... — minted by a `next_column_id(spec)` helper analogous to `next_step_id`. Stable across rewrites.
- `column.values` keys must be a subset of `axes`. Missing values render as an em-dash, not as an error.
- `verdict` ∈ `{"preferred", "rejected", "neutral", null}`. Drives a colored chip in the column footer. Optional.
- `column.title` is plain text; `values` are plain text (no nested markdown for v1 — keeps renderer dumb).

### Visual

```
┌─ Migration strategies ───────────────────────────────────────────┐
│                                                                  │
│                  │  Big-bang cutover  │  Incremental dual-write  │
│ ─────────────────┼────────────────────┼──────────────────────────│
│  Cost            │  Low               │  Medium                  │
│  Risk            │  High — single …   │  Low                     │
│  Effort          │  2 weeks           │  6 weeks                 │
│  Reversibility   │  None              │  Each step revertible    │
│ ─────────────────┼────────────────────┼──────────────────────────│
│                  │  ✗ rejected        │  ✓ preferred             │
└──────────────────────────────────────────────────────────────────┘
```

### Renderer

`skills/annotate/diagrams/comparison.py`. Emits an HTML `<table class="annotate-comparison" data-block-id="...">` with one `<th>` per column header, one `<tr>` per axis, and a final `<tr class="verdict-row">`. Each `<td>` carries `data-column-id` and `data-axis` so the client can scope clicks.

CSS lives in `static/style.css` under `.annotate-comparison`. Verdict chip variants: `.verdict-preferred` (green), `.verdict-rejected` (muted red), `.verdict-neutral` (gray).

### Annotation surface

Commentable units, in order of specificity:
- **Whole block** — `block_id` only, no `step_id`. Restructure axes/columns, change framing, drop a column.
- **Column** — `step_id: "c1"`. "This column's verdict feels wrong" or "explain c1 more". Rewrite that column's values + verdict.
- **Cell** — `step_id: "c1:Risk"` (column id + axis, colon-separated). "This risk assessment is too rosy." Rewrite that single value, or hold the line and rewrite the axis label to be clearer about scope.

The client surfaces three clickable regions per cell: cell itself, column header (for column-level), and the title bar (for whole-block).

### Rewrite contract integration

Same spirit as the diagram contract (see [SKILL.md:324 — diagram block-rewrite contract](../SKILL.md)):
1. Fold the answer into the spec. No Q-and-A panels.
2. Whole-block comments may restructure axes/columns.
3. Cell-level comments rewrite that one value; if the comment is really an axis-level issue (wrong axis, missing axis), promote scope and rewrite the axis instead. Use judgment.
4. Reject-on-a-cell ("no, this is fine") — either soften the value or hold the line and tighten the axis label. Don't drop the cell silently.
5. Persist via `update_spec_block`. Re-apply safe via canonical-JSON dedup.

### Glossary interaction

`drop_unused_terms` currently scans only `markdown` blocks (see `blocks.py:145`). Comparison cells contain prose that may carry project-specific identifiers — we'd want them to count. Plan: extend the haystack-builder in `blocks.py` to know how to extract searchable text per kind:

```python
def _searchable_text(block: dict) -> str:
    kind = block.get("kind") or "markdown"
    if kind == "markdown":
        return block.get("markdown", "")
    if kind == "comparison":
        spec = block.get("spec") or {}
        parts = [c.get("title", "") for c in spec.get("columns", [])]
        parts += [v for c in spec.get("columns", []) for v in (c.get("values") or {}).values()]
        return "\n".join(parts)
    # ...table, callout
    return ""
```

Single registry function, easy to extend per kind.

---

## 2. `kind: "table"`

### Purpose

Generic tabular data when markdown tables are too limited (sticky headers, alignment per column, monospace cells, longer cell content, more rows). Decision matrices, status grids, schema definitions, endpoint inventories.

### Spec shape

```json
{
  "id": "b-5",
  "kind": "table",
  "version": 1,
  "spec": {
    "title": "API endpoints",
    "columns": [
      {"id": "col-method",  "label": "Method", "align": "left",   "mono": true},
      {"id": "col-path",    "label": "Path",   "align": "left",   "mono": true},
      {"id": "col-auth",    "label": "Auth",   "align": "center"},
      {"id": "col-notes",   "label": "Notes",  "align": "left"}
    ],
    "rows": [
      {"id": "r1", "cells": {
         "col-method": "GET", "col-path": "/users", "col-auth": "✓", "col-notes": "paginated"
      }},
      {"id": "r2", "cells": {
         "col-method": "POST", "col-path": "/users", "col-auth": "✓", "col-notes": "admin only"
      }}
    ]
  }
}
```

Rules:
- `columns` ≥ 1, `rows` ≥ 1.
- `column.id` is author-supplied stable slug; `row.id` is `r1`, `r2`, ... via `next_row_id(spec)`.
- `column.align` ∈ `{"left", "center", "right"}`, defaults to `"left"`.
- `column.mono` boolean, defaults to `false`. Monospace cells for paths/code-like values.
- `row.cells` keys must be a subset of column ids. Missing → em-dash.

### Visual

```
┌─ API endpoints ────────────────────────────────────────────────────┐
│ Method  │ Path           │ Auth   │ Notes                          │
│ ────────┼────────────────┼────────┼─────────────────────────────── │
│ GET     │ /users         │   ✓    │ paginated                      │
│ POST    │ /users         │   ✓    │ admin only                     │
│ GET     │ /health        │   —    │ public                         │
└────────────────────────────────────────────────────────────────────┘
```

### Renderer

`skills/annotate/diagrams/table.py`. Emits `<table class="annotate-table" data-block-id="...">`. `<th>` carries `data-column-id`; `<tr>` carries `data-row-id`; each `<td>` carries both.

### Annotation surface

- **Whole block** — `block_id` only. Restructure columns, change framing.
- **Row** — `step_id: "r1"`. "This endpoint shouldn't require auth." Rewrite the row.
- **Header (column)** — `step_id: "col-auth"`. "Rename this column, it's misleading." Rewrite the column header + possibly cell values to match.

Cell-level addressing (`r1:col-auth`) is supported by the spec but not surfaced as a separate click target in v1 — row-level is granular enough and keeps the click surface simple. Revisit if real use shows otherwise.

### Rewrite contract integration

- Whole-block: restructure freely. Row ids stay stable for rows that survive.
- Row comment: rewrite the row in place. Off-topic row comments (user comments on r3 about something that's really an r1 issue) follow the same use-judgment rule — promote scope and rewrite both, or rewrite the right row.
- Column header comment: rename `column.label` and reshape `cells` if the meaning changed enough to invalidate values.
- Persist via `update_spec_block`.

### Glossary

Searchable-text extractor: row cell values + column labels + title.

---

## 3. `kind: "callout"`

### Purpose

A highlighted side-note. One per place where you'd otherwise write "**Warning:**" or "**Note:**" in prose and hope it stands out. Variants: warning, info, tip, danger, success.

This is the simplest of the three — close to a styled markdown block — but worth its own kind because (a) variant styling is awkward in pure markdown, (b) it lets the renderer add a leading icon and accent color, (c) it stays a structured unit for the rewrite contract.

### Spec shape

```json
{
  "id": "b-7",
  "kind": "callout",
  "version": 1,
  "spec": {
    "variant": "warning",
    "title": "Snapshot before migrating",
    "body": "The migration drops the legacy `tokens` table. Take a database snapshot before running step 3 — there is no automated rollback."
  }
}
```

Rules:
- `variant` ∈ `{"warning", "info", "tip", "danger", "success"}`. Required.
- `title` optional; if absent, the variant's default ("Warning", "Note", "Tip", "Danger", "Success") is used.
- `body` is markdown (rendered with the same pipeline as `kind: "markdown"` blocks). Single string field. Keeps the authoring shape natural — callouts often contain inline code, links, emphasis.

### Visual

```
┌─ ⚠  Snapshot before migrating ─────────────────────────────────┐
│                                                                │
│  The migration drops the legacy `tokens` table.  Take a        │
│  database snapshot before running step 3 — there is no         │
│  automated rollback.                                           │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

Color accents per variant (left border + soft background):
- `warning` — amber
- `info` — blue
- `tip` — teal
- `danger` — red
- `success` — green

### Renderer

`skills/annotate/diagrams/callout.py`. Emits `<aside class="annotate-callout variant-warning" data-block-id="..."><header>...</header><div class="body">...</div></aside>`. Body markdown rendered via the same markdown→HTML pipeline `markdown` blocks already use (reuse, don't reimplement).

### Annotation surface

Whole-block only. No sub-units. `step_id` is always absent/null.

This is intentional: a callout is one atomic point. If you want to comment on a sub-part, you probably want a different kind (markdown for prose, comparison/table for structured pieces).

### Rewrite contract integration

- Comments rewrite the whole callout. May change `variant`, `title`, `body`.
- Variant changes are allowed and expected: "this is really a danger, not a warning" → flip the variant.
- A comment that turns the callout into something that's no longer side-note-shaped (e.g., "expand this into three points") is a signal to convert it to a different kind. Spec-shape change across kinds is rare enough that v1 doesn't need a formal protocol — handle by recomposing the response if it happens.
- Persist via `update_spec_block`.

### Glossary

Searchable-text extractor: `title` + `body`.

---

## Cross-cutting changes

These touch the existing skill machinery once and serve all three kinds.

1. **`blocks.py`**
   - Add `next_column_id(spec)` and `next_row_id(spec)` helpers, parallel to `next_step_id`.
   - Generalize `drop_unused_terms` via a per-kind `_searchable_text` dispatcher (sketched in the comparison section). New kinds register their extractor; sequence and markdown keep current behavior.
   - `update_spec_block` already handles the version-bump + dedup story — no change.

2. **`server.py:_render_block_for_raw`**
   - Extend the `kind` branch to dispatch to the three new renderers.
   - Each renderer returns a dict like `{"id", "kind", "version", "spec", "html"}` (note: `html` instead of `svg` since these are HTML fragments, not SVG). Client-side renderer reads either depending on `kind`.

3. **Client (`static/script.js` + `static/style.css`)**
   - Per-kind rendering: insert the server-emitted HTML fragment into the block container as-is. Don't re-render client-side.
   - Click handlers: read `data-block-id` (always) + the relevant sub-unit attribute (`data-column-id`, `data-row-id`, `data-axis`, etc.). Compose `step_id` from those and post the same comment payload shape as today.
   - CSS for `.annotate-comparison`, `.annotate-table`, `.annotate-callout` lives alongside existing block styles. Reuse existing color tokens; do not introduce a new palette.

4. **`SKILL.md`**
   - Add a "When to use" subsection per kind, analogous to the existing sequence-diagram guidance at SKILL.md:33. Each gets a 3–5 line decision rule: use when ALL of … / do not use for …. The point is to keep authors from reaching for these when prose is fine.
   - Extend the rewrite-contract section to enumerate the new sub-unit ids.

5. **Tests**
   - Validator + renderer unit tests in `skills/annotate/tests/` mirroring the sequence-diagram coverage. Validate: minimum cardinalities, id stability across `update_spec_block` calls, dedup behavior, missing-cell rendering.

## Out of scope (v1)

- Raw-HTML kind. Explicitly rejected in this branch.
- Nested kinds (a callout containing a table). Possible later; the spec shape allows it for callout's `body` if we ever want to render structured content there.
- Cell-level click target on `table` (row-level only in v1).
- Resizable columns, sortable tables, collapsible rows. The kinds are for *static* presentation; if a user needs interactive data exploration, this isn't the tool.

## Acceptance for the plan itself

You read this and either (a) approve and we implement, or (b) push back on a spec shape, an annotation surface decision, or the scope. After approval, implementation goes kind-by-kind (callout is the smallest; start there to shake out the per-kind renderer registration pattern, then comparison, then table).
