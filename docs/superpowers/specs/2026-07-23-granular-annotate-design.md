# Granular annotate — design

Date: 2026-07-23
Status: approved via annotate choice blocks (workspace `granular-annotate`)

## Problem

Annotate comments and dismissals operate on whole blocks. A block holding a
10-bullet list forces the user to either comment on the entire list or manually
highlight a span — and dismiss has no sub-block form at all (`remove_block()`
kills the card). Worse, the page is single-flight: every comment locks the page
until Claude wakes, rewrites, and acks. Granular commenting under that lock
would mean one serial wake-cycle per bullet, defeating the tool's purpose.

## Decisions (made in the annotate round)

1. **Interaction model: batched review rounds.** Marks are instant and local;
   nothing wakes Claude until the user hits **Submit round**. One submit = one
   event = one wake = one coherent cross-item rewrite pass = one ack.
2. **Sub-units: all four** — list items (`li`), paragraphs (`p`), table rows
   (`tr`), fenced code blocks (`pre`). Same DOM walk; no phasing.

## Design

### 1. Client-side sub-unit decoration (script.js)

After each markdown block renders, walk its DOM and mark addressable units:
direct `li` elements, top-level `p`, table `tr`, and `pre` blocks. No authoring
change, no new block kind — every existing and future markdown block becomes
granular at render time.

Each unit gets a hover strip with three affordances:

- **✓ agree** — toggles a local "confirmed" state.
- **✕ dismiss** — toggles strikethrough + dimmed state (undo by re-click).
- **💬 comment** — opens the inline composer scoped to that unit; pinned text
  shows as a chip under the unit. One composer open at a time (existing rule).

Marks are mutually exclusive where contradictory (agree vs dismiss); a comment
can coexist with agree. Marks live in page state and persist across refresh via
the existing annotations draft mechanism.

Unit identity on the wire reuses the **existing span format**: the unit's plain
text as `selected_text` plus `prefix`/`suffix` disambiguation (ordinal fallback
when the same text repeats). No new server-side identity scheme.

### 2. Round submission

A docked pill shows `Submit round (n)`; disabled at 0. Submit POSTs one event:

```json
{"type": "round",
 "reactions": [
   {"kind": "dismiss", "block_id": "section-3",
    "selected_text": "…bullet text…", "prefix": "…", "suffix": "…"},
   {"kind": "comment", "block_id": "section-3",
    "selected_text": "…", "prefix": "…", "suffix": "…",
    "text": "does this fire per click?", "images": []},
   {"kind": "agree", "block_id": "section-5", "selected_text": "…"}
 ]}
```

Server queues it exactly like today's events (`/api/submit` → 202). The
single-flight lock applies only from submit until ack — never between marks.
The existing immediate paths (block-header comment/reject/dismiss, free span
selection, choice picks, general composer) are unchanged and still submit
immediately.

### 3. Claude contract (handling-events.md addition)

New `WEBCOMPANION_EVENT` payload `type: "round"`:

1. Group reactions by `block_id`.
2. One rewrite pass over the touched blocks, applying all reactions together:
   - **dismiss** → cut that unit from the block's markdown; re-thread
     neighbors (renumber, fix dangling references) so the block reads
     coherently. Dismissed content becomes out-of-scope going forward (same
     rule as whole-block dismiss).
   - **comment** → existing block-rewrite contract, scoped to the unit: fold
     the answer into that unit's prose.
   - **agree** → no rewrite, zero tokens; may inform a one-line round summary.
3. One `update_block` per touched block (one version bump each), one
   `save_atomic`, one `.ack`.

Cross-item coherence is the point: dismissing two bullets and questioning a
third is resolved in a single pass that sees all three.

### 4. Compatibility

- Spec blocks (`sequence`, `flowchart`, `diagram`, `choice`, `mockup`) keep
  their existing targeting (`step_id`, `data-annotate-id`); their per-node
  comments stay immediate in v1. A later phase may fold them into rounds.
- Inline-HTML `data-annotate-id` sub-units keep working; auto-decoration
  skips elements that already carry the attribute.
- Old workspaces gain the feature on next page load (pure client change +
  one new event type).

### Edge cases

- **Unit text rewritten concurrently** (round submitted against stale block):
  existing rule — `selected_text` is historical context; current block content
  is authoritative; Claude uses judgment.
- **Duplicate unit text**: `prefix`/`suffix` first, ordinal fallback.
- **Malformed round / empty reactions**: no-op, ack anyway (existing rule).
- **Watcher re-emit**: content-hash dedup in `update_block` makes re-apply a
  true no-op; ack rewrite is idempotent.

### Testing

- Server: `type: "round"` accepted, queued, surfaces in watcher payload
  (extend existing pytest suite in `skills/annotate/tests/`).
- Client: unit decoration walk + mark state machine + round payload assembly
  (existing JS test approach in the suite, or DOM fixture tests).
- Contract: handling-events reference gains a `round` section; re-apply-safety
  test mirroring the existing dismiss test.
