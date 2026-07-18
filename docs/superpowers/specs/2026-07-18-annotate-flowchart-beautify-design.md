# Annotate: beautiful, spec-driven flowchart block

**Date:** 2026-07-18
**Status:** design — approved look (Direction B), pending implementation-plan
**Skill:** `petros-skills:annotate`

## Problem

The current flowchart (`kind: "diagram"`, `type: "flowchart"`) is raw Mermaid
`neutral` theme rendered via the external `mmdc` CLI. It looks visibly worse than
the hand-built sequence diagram:

- Fixed-width grey boxes; long labels **wrap mid-word**
  (`validateDocumentsSelectio` / `n(orders)`) because `htmlLabels:false` bakes
  native `<text>` with no real wrapping.
- No color, no role semantics, no page-matched fonts.
- No links — code references like `ProposalService:154` are dead text.
- No per-node interactivity: the whole diagram is one comment target
  (`step_id: null`), unlike sequence steps which are individually clickable.
- **Authoring friction (AI-first):** the agent must hand-write Mermaid source —
  quoting rules, `<br/>` for line breaks, self-loop/back-edge pitfalls. A bare
  `(` in a label is a parse error. This is exactly the wrong surface for an
  AI-authored tool.

The sequence diagram avoids all of this because it is a pure-Python SVG
generator driven by a **structured spec**, tuned to the page. This design brings
the flowchart to that same bar.

## Goal

A flowchart block that:

1. Looks like **Direction B** (validated in the visual companion): soft
   role-tinted node fills (~10% over white), 1.3px role-colored border, subtle
   drop shadow, amber decision diamonds, green/red terminals, page fonts
   (Bricolage Grotesque body, Monaspace Radon for code). Stays clean at 14+
   nodes (verified — the pale tint does not "candy" at scale).
2. Is **authored as a structured spec** — nodes + edges + a `role` per node — not
   Mermaid source. No syntax, no quoting, no `<br/>`.
3. Makes code references **clickable jump-to-source** (`jetbrains://` deep link)
   and supports **cross-block anchors** (a node linking to another block).
4. Gives every node a **per-node hit target** so nodes are individually
   clickable/commentable (parity with sequence steps).

## Approach

**New first-class block kind `kind: "flowchart"`**, a twin of `kind: "sequence"`
— pure-Python spec validator + SVG renderer, no Mermaid, no `mmdc`.

Rejected alternatives:

- *Theme Mermaid harder (themeVariables, htmlLabels:true).* Keeps the fragile
  external `mmdc` dependency and the Mermaid-source authoring surface — fails the
  AI-first goal. Per-node hit targets and jetbrains links would need brittle SVG
  post-processing.
- *Overload `kind: "diagram"` with structured input for `type:"flowchart"`.*
  Mixing a `source` string shape and a `nodes/edges` shape under one kind is
  confusing to author. A dedicated kind is cleaner and mirrors `sequence`.

`kind: "diagram"` (Mermaid via `mmdc`) **stays** for the rarer families
`architecture / state / er / class`, which the new generator does not cover. The
`flowchart` value of `diagram.type` is deprecated in favor of the new kind.

## Components

### 1. `skills/annotate/diagrams/flowchart.py` (new)

Pure functions, no I/O — same contract header as `sequence.py`. Exposes:

- `validate(spec) -> None` — raises `ValidationError` on malformed spec.
- `render(spec, block_id) -> str` — returns an `<svg class="annotate-flow">`
  string with per-node `data-node-id` hit targets.

**Spec schema:**

```json
{"id": "section-N", "kind": "flowchart", "spec": {
  "title": "Both actions funnel into one guard",
  "direction": "TD",
  "nodes": [
    {"id": "a", "role": "entry",    "label": "User SAVES / edits orders"},
    {"id": "c", "role": "entry",    "label": "User SHARES", "sub": "VALIDATE lifecycle action"},
    {"id": "b", "role": "code",     "ref": "ProposalService:154",
                "method": "validateDocumentsSelection(orders)",
                "href": "jetbrains://idea/navigate/reference?project=...&path=...:154"},
    {"id": "d", "role": "code",     "ref": "LifecycleActionsExecutor:129",
                "method": "validateProposal(proposal)"},
    {"id": "e", "role": "call",     "method": "validateRequiredDocuments(...)"},
    {"id": "f", "role": "decision", "label": "toggle ON?"},
    {"id": "g", "role": "success",  "label": "allow", "sub": "no check"},
    {"id": "h", "role": "error",    "label": "throw", "method": "MissingRequiredDocumentsException"}
  ],
  "edges": [
    {"from": "a", "to": "b"},
    {"from": "c", "to": "d"},
    {"from": "b", "to": "e"},
    {"from": "d", "to": "e"},
    {"from": "e", "to": "f"},
    {"from": "f", "to": "g", "label": "OFF"},
    {"from": "f", "to": "h", "label": "ON + doc missing"}
  ]
}}
```

**Node fields:**

- `id` (required, unique) — used for edges and as `data-node-id`.
- `role` — one of `entry | code | call | decision | success | error`.
  **Unknown / omitted role → neutral slate** (the safety guard: a mis-tag or
  missing role degrades to a plain node, never a loud wrong color).
- `label` — headline text (Bricolage, weight 600). Decisions render label inside
  a diamond.
- `sub` — optional italic secondary line (dim).
- `ref` — monospace code reference, e.g. `File:line`. Rendered as the accent-blue
  underlined link line.
- `method` — monospace method/signature line (text color).
- `href` — optional. If present, `ref` (or `label` when no `ref`) is wrapped in an
  `<a href>`. Two supported targets:
  - `jetbrains://…` deep link → jump to source in IntelliJ.
  - `#<block-id>` → cross-block anchor (scroll to another block on the page).

**Edge fields:** `from`, `to` (must reference existing node ids), optional
`label` (rendered as a pill on the edge).

**Validation rules:** ≥1 node; unique node ids; every edge endpoint exists; graph
is a DAG (no cycles — reject with a clear message, matching the "no back-edge"
guidance); soft size warning is not enforced but ≤ ~15 nodes recommended.

**Layout:** layered top-down (Sugiyama-lite), bounded size keeps it simple:

1. Assign each node a layer = longest path from any root (topological order).
2. Order nodes within a layer by the order their incoming edges appear
   (barycenter of parents) to minimize crossings.
3. Compute x by distributing a layer's nodes across the fixed content width; y by
   layer index with per-row pitch. Terminal side-branches (e.g. error nodes off a
   decision) place in an adjacent column.
4. Route edges: straight vertical for same-column parent→child; smooth cubic
   curve for column changes / funnels; elbow for side branches. Arrowheads via a
   shared marker. Edge labels as `--surface-soft` pills.

This is a port/hardening of the validated `gen2.py` layout prototype.

### 2. `skills/annotate/static/diagram.css` (extend)

Add an `.annotate-flow` block mirroring the existing `.annotate-seq` rules:

- Node fill/stroke from a role→color map (see palette below), fills at ~10%
  tint, strokes at ~55%.
- Text: `.flow-label` (Bricolage 13.5/600, `--text-strong`), `.flow-sub`
  (italic, `--text-dim`), `.flow-ref` (Monaspace, `--accent`, underline),
  `.flow-method` (Monaspace, `--text`).
- Per-node hover + `data-card-focus` + `data-engaged-type` states, same pattern
  as sequence rows, so a node lights up when its comment card is focused.

**Palette (role → color), reusing core.css tokens where possible:**

| role     | color     | meaning              |
|----------|-----------|----------------------|
| entry    | `#0071e3` (`--accent`) | user trigger / start |
| code     | `#5b6472` slate       | code ref / call      |
| call     | `#5b6472` slate       | internal call        |
| decision | `#b45309` amber       | branch (diamond)     |
| success  | `#047857` green       | success terminal     |
| error    | `#c0392b` red         | throw / error        |
| *default*| `#5b6472` slate       | unknown/omitted role |

(amber/green/red match the sequence diagram's hardcoded arrow palette, so the
two diagram types read as one system.)

### 3. `skills/annotate/server.py` (extend)

- Import `render as render_flowchart` from `diagrams.flowchart`.
- In `_render_block_for_raw()`, add a `kind == "flowchart"` branch (next to
  `sequence` / `diagram`): call the renderer, attach `base["spec"]` and
  `base["svg"]`; on `ValidationError`/failure emit the same compact inline SVG
  error pill used by the other diagram kinds.

### 4. `skills/annotate/static/script.js` (extend)

- Add a `kind === "flowchart"` branch: inject `blk.svg`, and (unlike today's
  Mermaid diagram) attach a click listener that scopes comments to
  `data-node-id` — same mechanism as the `sequence` `data-step-id` handler.
- Keep the whole-diagram hover-actions strip as a fallback for clicking the
  frame rather than a node.
- Anchor `<a href="#block-id">` clicks scroll to the target block; `jetbrains://`
  links open normally (no interception).

### 5. Docs / references

- New `references/block-kinds/flowchart.md` — the authoring contract for the new
  kind (spec shape, roles, when to use vs sequence vs diagram, the "prefer a
  structured node over cramming text" guidance). Replaces the mid-doc Mermaid
  authoring-rules burden for flowcharts.
- Update `references/block-kinds/diagram.md`: mark `type:"flowchart"` deprecated,
  point to the new kind; `diagram` now covers `architecture/state/er/class` only.
- Update the kind menu in `SKILL.md` so the agent picks `flowchart` for
  branching/decision/process flows.
- Update `references/handling-events.md` diagram rewrite contract: flowchart
  comments arrive with a `node_id`; rewrite via `blocks.update_spec_block`.

## Data flow

Agent authors `blocks.json` with a `kind:"flowchart"` structured spec →
`server.py` `_render_block_for_raw()` validates + renders to SVG server-side →
SVG shipped in `blk.svg` → `script.js` injects it and wires per-node click →
user clicks a node → comment scoped to `node_id` → Claude rewrites that node (or
the spec) via `update_spec_block` → block re-renders.

## Error handling

- Invalid spec (bad edge endpoint, cycle, empty) → `ValidationError` → compact
  inline SVG error pill, page never blanks (same as sequence/diagram today).
- Unknown/omitted `role` → neutral slate node (no error).
- Missing `href` → plain (non-link) ref text.

## Testing

Mirror the sequence-diagram test suite:

- **Validator unit tests:** duplicate ids, dangling edge, cycle rejection,
  unknown role tolerated (renders neutral), decision-node shape.
- **Renderer golden tests:** the screenshot diagram (7 nodes, funnel + diamond +
  two terminals) and the 14-node pipeline produce stable SVG with the expected
  `data-node-id` targets, role classes, and `<a href>` wrapping on `ref`+`href`.
- **Layout tests:** layer assignment for a funnel (two parents → one child) and a
  decision side-branch; no node overlap; DAG longest-path correctness.

## Out of scope

- Mermaid families `architecture/state/er/class` — unchanged.
- Removing the `mmdc` dependency (still used by those families).
- Full generic graph layout beyond ~15 nodes — bounded by the authoring guidance.

## Open decision captured

`kind: "diagram"` `type:"flowchart"` is retained as a deprecated alias (routes to
Mermaid) rather than hard-removed, so any existing authored flowchart blocks
don't break; new authoring uses `kind:"flowchart"`.
