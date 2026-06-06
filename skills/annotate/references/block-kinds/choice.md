# `kind: "choice"` block

Read this when you've decided (from the kind menu in SKILL.md) that a block
should be a choice/decision block, and you need the exact contract to emit or resolve it.

## When a choice block is the right block

Emit a choice block when the response reaches a **decision point** and the next step depends on the user's preference, with ALL of:

- 2–4 discrete, comparable options (or, for multi-select, a set the user picks a subset from).
- A closed answer space — picking beats free-text.
- The choice genuinely drives what you do next.

Typical fits: "which migration strategy", "which datastores to provision", "scope this to A, B, or both".

**Do NOT use a choice block for:**

- Open-ended questions where the answer isn't one of a few options (use prose + let the user comment).
- A hard gate where you must block before doing anything else — annotate is async; use the terminal `AskUserQuestion` tool for that.
- More than ~4 options, or options needing paragraphs to explain (that's prose).

One question per choice block. Need several questions? Emit several blocks.

## Block shape

A choice block looks like this in `blocks.json`:

    {"id": "section-N", "kind": "choice", "spec": {
      "question": "<the decision, one line>",
      "multiSelect": false,
      "options": [
        {"id": "o1", "label": "<terse choice>", "description": "<optional sub-text>"},
        {"id": "o2", "label": "...", "description": "..."}
      ]
    }}

Block id is `section-N` (assigned by `next_block_id`). Option ids are `o1`, `o2`, … minted by hand, stable across rewrites. `multiSelect: false` renders radio (exactly one); `true` renders checkboxes (pick ≥ 1). `description` is optional. Use 2–4 options.

A choice block carries no `markdown`, and the question is shown in the card header — don't repeat it in the spec elsewhere. The user picks in the browser; you resolve the block on the watcher event.

## Resolving a choice after the user picks

See `references/handling-events.md` § "`WEBCOMPANION_EVENT` with `type: \"choice\"`".
