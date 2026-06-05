# Annotate: dismiss-a-block + page-wide single-flight lock

**Date:** 2026-06-04
**Status:** Approved design, ready for implementation plan
**Affected skill:** `skills/annotate`

## Problem

When reading an annotated response in the browser, the user sometimes wants to
remove a block entirely — a section that is superfluous or irrelevant to what
they care about. Today annotate supports only `comment` and `reject` on a block;
there is no way to say "this part is irrelevant, drop it."

Designing this surfaced a second, broader problem: annotate currently lets the
user submit a comment on one block while a previous submission is still being
processed by Claude. Because a single Claude reply can rewrite the *whole*
document, overlapping submissions race against each other and produce a
confusing, inconsistent page. The user wants the page to behave predictably: one
action at a time, with clear feedback about what Claude is doing.

This spec covers both, because the dismiss feature only behaves consistently once
the single-flight lock exists.

## Goals

- Add a **dismiss** (delete) action on a block. Dismiss means *irrelevant* — not
  rejection. The block is removed from the page and Claude stops carrying it
  forward.
- Make the whole page **single-flight**: at most one submission in flight, and at
  most one comment editor open, at any time.
- Make the locked/working state **server-authoritative** so it survives a page
  reload and is consistent across devices.
- Keep the user's mental model simple and surprise-free: every action resolves
  the same way — submit, page locks, Claude replies, page unlocks.

## Non-goals

- No undo / restore of a dismissed block. A dismiss is permanent within the
  session, by design.
- No step-level dismiss for `kind: "sequence"` blocks in v1. A diagram or choice
  block is dismissed whole-block, like a markdown block.
- No optimistic client-side hiding. The block disappears only after Claude's
  reply lands (the "consistent" path, not the "snappy" one).

## Key concept: what "remove from context" actually means

`blocks.json` is the page's content, not Claude's memory. The originating turn
where Claude first wrote a block remains in the conversation; dismissing a block
does **not** retroactively erase it from the live context window.

What dismiss *does* achieve, and what is sufficient for the use case:

1. **No re-ingestion going forward.** Annotate re-reads `blocks.json` on every
   later edit turn. Once the block is removed from the file, no future turn pulls
   it back in.
2. **Explicit out-of-scope marking.** When Claude handles the dismiss event, it
   treats the removed content as out of scope for the rest of the turn, so its
   subsequent behavior excludes it rather than silently reconsidering it.

This is a *forward exclusion*, not a retroactive erase — which is exactly what
"don't make me deal with this part next time" requires.

## Design

### Page state machine

Three states, driven by server-reported `busy` plus a client-local editing flag:

| State | What is interactive | Transitions |
| --- | --- | --- |
| **IDLE** | every block's comment / reject / dismiss affordance; the general composer | open an editor → EDITING · click dismiss × → BUSY |
| **EDITING** (one editor open, not yet submitted) | only the open editor's Submit/Cancel | cancel → IDLE · submit → BUSY |
| **BUSY** (an event is in flight, unacked) | nothing that submits to Claude; a "Claude is updating…" banner is shown | event acked + page updates → IDLE |

Passive interactions — scrolling, reading, block search — stay live in all
states; they do not submit anything to Claude.

**EDITING is client-local.** Opening one comment editor disables all other
affordances in the browser. Clicking another block's comment icon while an editor
is open does nothing. Only one comment can be active.

**BUSY is server-authoritative.** It is derived from `/poll`, so a reload or a
second device sees the same locked page and cannot sneak in a second submission.

### Delete vs reject (instruction to Claude)

- **reject** (existing): "I disagree with this block." Claude softens, withdraws,
  or holds the line with reasoning folded into the rewrite.
- **dismiss** (new): "This block is irrelevant." Claude removes it and re-threads
  the survivors; it does not argue or defend. The content is dropped from scope.

These are distinct event types with distinct handling. The implementation must
not collapse dismiss into reject.

## Component changes

### Server — `skills/annotate/server.py`

1. **`/poll` reports `busy`.** Compute it from the directories the server already
   knows: `busy = (event ids appended to events_dir) − (event ids in
   consumed_dir) is non-empty`. Add a `"busy": <bool>` field to the `serve_poll`
   JSON response. No new storage is introduced; `consumed_events` is already
   surfaced and can be reused for the set difference.
2. **`handle_submit` accepts `type: "dismiss"`.** Add `"dismiss"` to the accepted
   `comment_type` set. A dismiss requires a non-null `block_id` (return 422
   otherwise) and ignores `text` (no editor feeds it). The queued event shape is
   the existing event dict with `type: "dismiss"`.

### Model — `skills/annotate/blocks.py`

- Add **`remove_block(doc, block_id)`** — deletes the block from `doc.blocks`.
  Must be consistent with the content-hash version chain in `versions.json` (the
  removed block's id simply leaves the vector). Returns whether a block was
  removed (False if the id was already absent, for watcher re-apply safety).

### Client — `skills/annotate/static/script.js`

1. **Consume `busy` from `/poll`.** When `busy` is true, enter BUSY: disable all
   comment/reject/dismiss affordances and the general composer, and show the
   "Claude is updating the plan…" banner. When `busy` clears, return to IDLE.
2. **Single open editor.** Opening a comment editor sets an editing flag that
   disables every other affordance until the editor is submitted or cancelled.
3. **Dismiss control.** Each block gets a × affordance. Clicking it submits a
   `type: "dismiss"` event for that block (via the existing `/api/submit` path),
   which moves the page to BUSY on the next poll. No confirmation dialog — dismiss
   is final and cheap.
4. **Replace the per-block "updating" overlay model** where it conflicts with the
   page-wide lock: the lock is now global, not per-block. (The existing event_id →
   `consumed_events` done-signal is still how the client learns the in-flight
   event finished.)

### Styling — `skills/annotate/static/style.css` (or `core.css`)

- BUSY banner (with spinner), disabled-affordance treatment (greyed icons), and
  the dimmed-page look. Reuse the existing graphite palette and CSS variables
  (`var(--accent)`, `var(--surface)`, `var(--border)`, …) — no new palette.

### Skill — `skills/annotate/SKILL.md`

1. Document the new **`type: "dismiss"`** in the Mode D event reference, alongside
   `comment` / `reject` / `choice`.
2. Add a **dismiss-handling branch** to Mode D:
   - `blocks.remove_block(doc, block_id)`.
   - **Smart-drop:** scan surviving blocks; re-thread any that referenced the
     removed one (renumber steps, cut/rewrite dangling references) so the document
     reads coherently without it.
   - Run `blocks.drop_unused_terms(doc)` in case the removed block held the last
     use of a glossary term.
   - Treat the removed content as **out of scope** for the rest of the turn.
   - `save_atomic`, write the `<consumed_dir>/<event_id>.ack`, end the turn. No
     terminal output; the watcher stays armed.
3. State the **delete ≠ reject** distinction explicitly so the rewrite contract
   does not conflate them.

## Data flow (dismiss, end to end)

1. User clicks × on a block. Client POSTs `/api/submit` with
   `{block_id, type: "dismiss"}`; server queues the event and returns `event_id`.
2. Next `/poll` returns `busy: true`. Client enters BUSY: page locks, banner
   shows.
3. Watcher emits `WEBCOMPANION_EVENT` with `type: "dismiss"`. Claude wakes,
   removes the block, smart-drops/re-threads survivors, drops orphaned glossary
   terms, saves `blocks.json`, writes the `.ack`.
4. Next `/poll`: the event id is now in `consumed_events`, so `busy` is false and
   the block versions reflect the new document. Client returns to IDLE and the
   block is gone with no trace.

## Error handling & edge cases

- **Reload / second device while BUSY:** `/poll` still reports `busy: true`; the
  page stays locked. No client state required.
- **Watcher re-emits a dismiss already processed:** `remove_block` is a no-op when
  the id is already gone; rewrite stays version-hash-safe; the `.ack` is rewritten
  idempotently.
- **Dismiss with unknown/absent `block_id`:** server returns 422; nothing queued.
- **Dismiss of a `choice` or `sequence` block:** removed whole-block, same path as
  markdown.
- **Malformed dismiss event reaches Claude:** fall back to no-op but still write
  the `.ack` so it is not re-emitted forever (matches existing Mode D edge-case
  policy).

## Testing

- `blocks.remove_block`: removes a present block; no-op on absent id; version
  vector for survivors unchanged; glossary drop interplay.
- Server `handle_submit`: accepts `dismiss` with `block_id`; 422 on missing
  `block_id`; rejects unknown types still.
- Server `/poll`: `busy` true while an event is unacked, false once acked.
- Client behavior (smoke/e2e): single open editor enforced; page locks on submit;
  unlock on ack; dismiss removes the block after the round-trip.
- E2E dismiss flow mirroring existing `test_smoke_e2e_*` patterns.

## Open implementation questions (for the plan stage)

- Exact reuse vs. refactor of the existing per-block "updating" overlay code in
  `script.js` now that the lock is global.
- Whether `busy` belongs in the shared `web_companion` poll contract or stays
  annotate-specific (other web-companion skills — interactive_review — may want
  the same single-flight guarantee later).
