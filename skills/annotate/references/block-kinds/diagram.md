# `kind: "diagram"` block (Mermaid)

Read this when you've decided (from the kind menu in SKILL.md) that a block
should be a Mermaid diagram, and you need the exact contract to emit or rewrite it.

## When a Mermaid diagram is the right block

Emit a `kind: "diagram"` block when content is clearer seen than read AND it is
one of these shapes (the cases a sequence diagram does NOT cover):

- **flowchart** — DEPRECATED here. Use the first-class `kind: "flowchart"`
  block (structured nodes/edges, role color, jump-to-source links, per-node
  comments). See `references/block-kinds/flowchart.md`. `kind: "diagram"` now
  covers architecture / state / er / class only.
- **architecture** — system/service architecture, how components connect.
- **state** — state machines, lifecycle transitions.
- **er** — entity-relationship / data-model shapes.
- **class** — class hierarchies, static structure.

The block carries Mermaid source; the server renders it to SVG with `mmdc`.

**Do NOT use a `kind: "diagram"` block for:**

- Temporal actor↔actor flows — that's a `kind: "sequence"` block.
- Branching/decision logic, process flows, block diagrams — that's a
  `kind: "flowchart"` block (see `references/block-kinds/flowchart.md`).
- Anything that fits in 1–2 sentences, or a short list that reads fine as prose.

**One diagram per concept.** Like sequence blocks, diagrams are heavier than
prose — visually and token-wise. Frame the diagram with a short prose block; the
diagram must add clarity, not decorate.

## Authoring rules — keep the graph legible (apply before emitting)

`mmdc` renders exactly the graph you describe; it will not rescue bad topology. A
diagram that reads as broken is almost always one of these mistakes, not a render
bug. Check each before emitting:

- **No self-loops.** `A --> A` renders as a dangling teardrop with the label in a
  detached floating box — it always looks broken. To express "this repeats every
  turn / happens continuously", put that as the **label on the meaningful
  transition** (`SEL -->|"re-inject every turn"| Manual`), or add a separate small
  node, or a `note`. Never point a node at itself unless the diagram IS a state
  machine where the self-transition is the actual subject.
- **One dominant direction.** Pick `TD` (top-down) for pipelines/flows or `LR` for
  wide fan-outs, and let every arrow flow that way. Don't mix upstream and
  downstream arrows into the same node from opposite sides.
- **Minimize crossings.** Order sibling nodes left-to-right in the order their
  edges occur so arrows don't cross. If two edges must cross, that's usually a sign
  the node order is wrong — reorder before accepting the crossing.
- **Bound the size.** Aim for ≤ ~10 nodes. If it's bigger, split into two diagrams
  or collapse a cluster into one node — a dense graph is less clear than prose.
- **Quote labels, break with `<br/>`.** Wrap any node/edge label containing spaces,
  punctuation, `:`/`+`/`()` or an apostrophe in double quotes
  (`A["selectLiveObjectives<br/>live-objective selector"]`). Keep node text to ≤ 2
  short lines; push detail into the framing prose block, not the node.
- **Fan-out, not back-edges, for shared producers.** When one node feeds several
  consumers (a selector feeding manual/live/judge), draw three forward edges to the
  three consumers — don't loop an edge back up or reuse one node as both source and
  sink in a way that forces a reverse arrow.

If you catch yourself reaching for a self-loop or a back-edge to show "repeatedly"
or "each turn", stop and convert it to an edge label — that single rule prevents the
most common ugly diagram.

## Block shape

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

## Rewriting a diagram block after a comment

See `references/handling-events.md` § "Diagram block-rewrite contract" — diagram
blocks are whole-diagram only in v1; rewrite `spec.source` via `blocks.update_spec_block`.
