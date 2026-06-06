# `kind: "sequence"` block

Read this when you've decided (from the kind menu in SKILL.md) that a block
should be a sequence diagram, and you need the exact contract to emit or rewrite it.

## When a sequence diagram is the right block

A block should be a sequence diagram (instead of prose) when ALL of:

- The content involves ≥ 2 named entities interacting (browser ↔ server, user ↔ system, two services...).
- The content has a clear temporal order — step 1, then step 2, ...
- Who-talks-to-whom matters — a numbered list loses that information.

Typical fits: code flows, request/response protocols, event lifecycles, deployment pipelines, state transitions tied to events over time.

**Do NOT use a sequence-diagram block for:**

- Single-actor flows (a numbered list does the job).
- Branching/decision logic where time isn't the dominant axis — use a `kind: "diagram"` block (flowchart).
- Static structure: class hierarchies, data shapes, dependency graphs, system architecture — use a `kind: "diagram"` block.
- Anything that fits in 1–2 sentences.

**One diagram per flow.** Diagrams are heavier than prose blocks — visually and token-wise. A response that explains one flow gets one diagram block; longer explanations get prose blocks framing it. Don't emit two diagrams unless they're genuinely two separate flows.

## Block shape

A sequence-diagram block looks like this in `blocks.json`:

    {"id": "section-N", "kind": "sequence", "spec": {
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

Block id remains `section-N` (assigned by `next_block_id`); step ids are `s1`, `s2`, ... per `next_step_id`. Both are stable across rewrites.

Arrow types are exactly three values:

- `request` — actor↔actor interaction; direction follows `from`/`to`.
- `event` — automatic / system-driven push.
- `self` — self-action; requires `from === to`.

## Rewriting a sequence block after a comment

See `references/handling-events.md` § "Diagram block-rewrite contract" — sequence blocks support per-step targeting via `step_id`.
