# `kind: "diagram"` block (Mermaid)

Read this when you've decided (from the kind menu in SKILL.md) that a block
should be a Mermaid diagram, and you need the exact contract to emit or rewrite it.

## When a Mermaid diagram is the right block

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
