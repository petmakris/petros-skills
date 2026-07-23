# Handling a watcher event

Read this when a task-notification arrives whose first stdout line is one of the
`WEBCOMPANION_*` banners, OR when the user cancels in terminal while a watcher is
armed. These are **separate, later invocations** from the original push — you do
not need the pushing pipeline here.

All helpers below live in `skills/annotate/blocks.py`; the server and tests import
it aliased as `blocks_model`, but here it's always `blocks.`.

## Mode D — handling a watcher event

You wake here when a task-notification arrives whose first stdout line is one of the `WEBCOMPANION_*` banners.

### `WEBCOMPANION_EVENT` (per-comment submission)

1. Parse the banner: `skill`, `sid`, `event_id`.
2. Read the event payload between the `---payload---` and `---end---` markers in the notification body. **If `type == "choice"`, jump to the `choice` subsection below.** Otherwise, fields are:
   - `block_id` — the block to update, or `null` for a general comment.
   - `step_id` — for `kind: "sequence"` blocks: the step row the user clicked, or `null` for whole-diagram comments. For `kind: "flowchart"` blocks: the clicked node's id (the DOM carries it as `data-node-id`, but it arrives on the wire in this same `step_id` field — there is no separate `node_id` field), or `null` for whole-flowchart comments. For `kind: "diagram"` blocks: always `null` (whole-diagram only in v1). Absent/null for markdown blocks.
   - `type` — `"comment"`, `"reject"`, `"choice"`, `"dismiss"`, or `"round"`.
   - `selected_options` — for `type: "choice"`: the option id(s) the user picked (a list). Absent otherwise.
   - `reactions` — for `type: "round"`: the batched sub-unit reactions. Jump to the `round` subsection below.
   - `text` — the user's free-text feedback.
   - `selected_text` — the span they highlighted, or `null` if the comment is block-scoped.
   - `block_snippet` — optional: a short plain-text snapshot of the block as the user saw it when commenting (useful when the block has since been rewritten).
   - `prefix` / `suffix` — optional: surrounding context that pins down *which* occurrence of `selected_text` was highlighted when it appears more than once in the block.
   - For `type == "dismiss"`: `block_id` is the block to remove; `text` is empty and ignored. Jump to the `dismiss` subsection below.
   - `images` — array of `{token, path}` entries (or empty).  When non-empty, `Read` each `path` before composing your rewrite so you see the screenshots.
3. **Apply the block-rewrite contract** (see "Block-rewrite contract" below).
4. Save the updated `blocks.json` atomically (tmp → rename).
5. Write `<consumed_dir>/<event_id>.ack` (empty file is enough — existence is the signal).
6. End your turn.  **No terminal output.**  The watcher remains armed.

### `WEBCOMPANION_EVENT` with `type: "choice"`

The user picked option(s) on a choice block. `selected_options` holds the picked id(s); map them to labels via the block's `spec.options`.

1. Read `<response_dir>/blocks.json`, find the block by `block_id`.
2. **Resolve the choice into a decision** — convert the block from `kind: "choice"` to a markdown block whose prose states the decision and folds in the reasoning (e.g. *"Decision: incremental cutover — phase 1 ships the read path…"*). The options disappear; the answer is final. Use `blocks.convert_block_to_markdown(doc, block_id, markdown)` — it sets the markdown, drops `kind`/`spec`, and is content-hash-safe (a no-op rewrite doesn't bump the version).
3. **Continue the task** — the pick drives the next step. Append follow-up blocks to `blocks.json` and/or take the implied action, as the decision warrants.
4. `save_atomic` the doc, write the `<consumed_dir>/<event_id>.ack`, end your turn. No terminal output; the watcher stays armed.

Multi-select: the decision prose names all picked options. There is no `reject` on a choice — a pick is a pick.

### `WEBCOMPANION_EVENT` with `type: "dismiss"`

The user removed a block. **Delete is not reject.** A reject means "I disagree" — you soften, withdraw, or defend the claim. A dismiss means "this block is *irrelevant*" — you remove it and stop carrying it forward; do not argue, defend, or re-add it.

1. Read `<response_dir>/blocks.json`.
2. `blocks.remove_block(doc, block_id)` — deletes the block. It is a no-op if the block is already gone (watcher re-apply safety).
3. **Smart-drop:** scan the surviving blocks. Re-thread any that referenced the removed one — renumber steps, cut or rewrite dangling references — so the document still reads coherently without it. Use `blocks.update_block` / `blocks.update_spec_block` per touched block; touch only blocks that actually referenced the removed one.
4. `blocks.drop_unused_terms(doc)` — drop any glossary entry whose term was last used by the removed block.
5. Treat the removed content as **out of scope** for the rest of this turn and going forward: do not reintroduce it, and exclude it when acting on the plan.
6. `save_atomic` the doc, write `<consumed_dir>/<event_id>.ack`, end the turn. No terminal output; the watcher stays armed.

A dismissed `choice` or `sequence` block is removed whole-block the same way — there is no step-level dismiss.

### `WEBCOMPANION_EVENT` with `type: "round"`

The user swept the document marking sub-units (list items, paragraphs, table
rows, code blocks) and submitted them all at once. The payload carries the
whole batch:

- `reactions` — a list of `{kind, block_id, selected_text, text, images,
  prefix?, suffix?}`. `kind` is `"agree"`, `"dismiss"`, or `"comment"`.
  `selected_text` is the sub-unit's plain text; `prefix`/`suffix` pin down
  which occurrence when it repeats inside the block (same convention as span
  comments).

Apply the WHOLE round in one pass — this is the entire point of batching:

1. Read `<response_dir>/blocks.json`. Group reactions by `block_id`.
2. For each touched block, compose ONE new markdown that applies all of its
   reactions together:
   - **`dismiss`** — cut that sub-unit (the bullet / paragraph / row / fence
     matching `selected_text`) from the block's markdown, then re-thread the
     remainder (renumber, fix dangling references) so the block still reads
     coherently. This is the sub-unit form of dismiss: do not remove the
     whole block. Dismissed content is out of scope going forward — do not
     reintroduce it (same rule as whole-block dismiss).
   - **`comment`** — the block-rewrite contract scoped to that sub-unit: fold
     the answer or clarification into the sub-unit's prose. `Read` any
     `images` paths first.
   - **`agree`** — no rewrite for this sub-unit. Never re-emit a block whose
     only reactions are agrees.
3. Persist each changed block via `blocks.update_block(doc, block_id,
   new_markdown)` (content-hash-safe), then `blocks.drop_unused_terms(doc)`,
   then ONE `blocks.save_atomic`.
4. Write ONE `<consumed_dir>/<event_id>.ack`. End your turn. No terminal
   output; the watcher stays armed.

Cross-item coherence is required: if a round dismisses two bullets and
questions a third in the same block, the single rewrite resolves all three
together. A `selected_text` that no longer matches the current block content
(concurrent rewrite) is historical context — same rule as span comments.
Re-apply safety is unchanged: re-processing the round is a content-hash
no-op.

### `WEBCOMPANION_FINISHED`

The user clicked Done.

1. Ack briefly in terminal: *"Annotate session for `<title>` closed."*
2. Remove this session's entry from `~/.claude/annotate/pending-${CLAUDE_CODE_SESSION_ID}.json`.

### `WEBCOMPANION_CANCELLED`

The user cancelled (clicked tab close, or wrote `scrap it` in terminal).

1. Ack briefly in terminal: *"Annotate session for `<title>` cancelled."*
2. Remove this session's entry from the pending registry.

## Block-rewrite contract

When you receive a `WEBCOMPANION_EVENT` with a non-null `block_id`:

1. Read `<response_dir>/blocks.json`.  Find the block by `id`.
2. **Generate rewritten markdown for the block that folds the answer or clarification into the prose.**  The document itself is the answer — do not echo the user's question back as Q-and-A.  No "Claude says:" panels, no chat threads.  After your rewrite, a reader who didn't see the user's comment should be able to read the new block and have no remaining question on the topic the comment raised.
3. **Edge cases:**
   - The comment is *off-topic* for the targeted block (the user's question references content that lives elsewhere): update the block to be clearer about its actual topic, or rewrite a *neighboring* block to address the question, or both.  Use judgement.
   - The `type` is `reject`: the user disagrees.  Either soften / withdraw the claim in the new prose, or hold the line with a reasoned explanation woven into the rewrite.  Don't pretend agreement; don't argue back in a side channel.
   - The user's `selected_text` no longer exists after a prior rewrite: treat it as historical context.  The current block content is what matters.
4. **Touch only the blocks you actually need to change.** Do not re-emit unchanged blocks "for completeness" — the server derives `version` from a content-hash chain, so re-writing identical content is a true no-op, but re-emitting the same prose with cosmetic differences (a swapped synonym, a re-flowed sentence) inflates the version of a block the user didn't ask you to touch. Block ids stay the same; versions take care of themselves.

Persist each changed markdown block via `blocks.update_block(doc, block_id, new_markdown)` (content-hash-safe — returns `False`, a true no-op, if identical), then `save_atomic`. (Use `blocks.update_spec_block` for `sequence`/`diagram` spec blocks instead — see "Diagram block-rewrite contract".)

When `block_id` is `null` (general comment):

1. Read the comment text.  It will be a directive that applies across blocks ("make this shorter", "more casual tone", "remove the second paragraph", etc.).
2. Update *only the blocks that actually need updating* to apply the directive. Don't re-emit untouched blocks.
3. Save and ack as above.

## Diagram block-rewrite contract

For `WEBCOMPANION_EVENT` payloads that target a `kind: "sequence"` block, the rewrite contract has three deltas from the markdown contract above:

1. **Targeted by default when `step_id` is present.** A comment on step `s4` ("does this fire once per click, or can it batch?") rewrites just that step's `label` and/or `sub`. Other steps untouched. Step ids stay stable across rewrites; new steps mint fresh ids via `next_step_id`.

2. **Whole-diagram comments (`step_id: null`)** apply across steps as needed — restructure phases, reorder steps, add/remove actors. Analogous to general comments with `block_id: null` in the markdown contract.

3. **Reject on a step** — either soften/withdraw the claim by rewriting the step, or hold the line by rewriting the sub-caption with reasoning. Don't drop the step silently. Same "fold the answer into the prose" spirit; here the "prose" is the spec.

Persist updates via `blocks.update_spec_block(doc, block_id, new_spec)` — returns `True` only on real change (canonical-JSON content hash). Then `save_atomic` as today. Watcher re-emit safety is preserved.

**Off-topic comments** (user comments on `s4` about something that really belongs in `s2`) follow the same "use judgment" rule as the markdown contract: rewrite the targeted step to be clearer about its actual topic, or rewrite the neighboring step, or both.

**`kind: "diagram"` (Mermaid) blocks** have no per-step targeting in v1: a
comment always arrives with `step_id: null` and applies to the whole diagram.
Rewrite `spec.source` (and `spec.title` if warranted) to fold in the answer,
then persist with `blocks.update_spec_block(doc, block_id, new_spec)` — the same
content-hash-safe helper used for sequence specs — and `save_atomic`. To convert
a diagram to/from prose, treat it as a kind change (drop `kind`/`spec`, set
`markdown`) exactly as for other spec blocks.

### Flowchart block-rewrite contract

`kind: "flowchart"` blocks carry per-node targeting the same way sequence
blocks carry per-step targeting: a click on a node arrives with `step_id` set
to that node's `id` (see the `step_id` field note above — there is no
separate `node_id` field on the wire).

1. **Targeted by default when `step_id` is present.** A comment on node `f`
   ("does this decision also fire on a partial save?") rewrites just that
   node's `label`/`sub`/`method`/`ref`/`href`, or the edges touching it if the
   branch structure itself needs to change. Other nodes untouched. Node ids
   are author-assigned and stay stable across rewrites — don't renumber a
   node just because you touched it.
2. **Whole-flowchart comments (`step_id: null`)** apply across the spec as
   needed — add/remove nodes, rewire edges, retitle. Analogous to general
   comments with `block_id: null` in the markdown contract.
3. **Reject on a node** — either soften/withdraw the claim by rewriting the
   node, or hold the line by rewriting its `sub` with reasoning. Don't drop
   the node silently.

Persist updates via `blocks.update_spec_block(doc, block_id, new_spec)` — the
same content-hash-safe helper used for sequence and diagram specs — then
`save_atomic`. To convert a flowchart to/from prose, treat it as a kind
change (drop `kind`/`spec`, set `markdown`) exactly as for other spec blocks.

## Glossary term-set diff at rewrite time

When you handle a `WEBCOMPANION_EVENT` that targets a markdown block:

1. After composing the rewritten block markdown, apply the **drop rule**: any glossary entry whose `term` no longer appears (case-sensitive whole-word) in any block is dropped. Use `blocks.drop_unused_terms(doc)` — it does this in one call.
2. Apply the **add rule**: if the rewrite introduces a new project-specific identifier that wasn't already in the glossary and that meets the comprehension-blocker test (see `references/pushing.md` § "When to emit a glossary entry"), append a new entry.

Do not re-extract the whole glossary on every rewrite. The common case — a rewrite that doesn't touch the term set — produces no glossary mutation.

## Re-apply safety

If the watcher restarts mid-session, it may re-emit an event you've already processed.  This is safe because:

- For block rewrites, your new content will match the current block content — `blocks.py:update_block` is content-hash-aware and returns `False` on a no-op, so the chain in `versions.json` doesn't grow a duplicate entry.
- The `<consumed_dir>/<event_id>.ack` marker may already exist; that's fine.  Write it again (idempotent).

Just process the event normally each time; the system handles dupe detection at the storage layer.

## Terminal cancellation

If the user says "scrap it" / "respond in terminal" / "stop annotating" / equivalent *while a watcher is armed* (the pending registry has entries):

1. Read `~/.claude/annotate/pending-${CLAUDE_CODE_SESSION_ID}.json`.
2. For each entry, write a `cancelled` marker into the entry's `state_dir`:
   ```bash
   printf '{"reason":"user-cancelled-terminal"}' > "$STATE_DIR/cancelled"
   ```
   The server's existing `_terminal_state` check only tests existence, so the body is optional but useful for debugging.
3. The watcher detects the marker on its next tick and emits `WEBCOMPANION_CANCELLED`. You'll get a task-notification for each.
4. Handle each cancellation per Mode D and clean up the registry as that step instructs.
5. Continue with whatever the user actually wanted.

## Edge cases

- **`selected_text: ""`** — comment refers to the entire block; treat the block as the anchor.
- **Server unreachable** — re-run `ensure_server.sh` (see `references/pushing.md`); it will restart the server. Retry the failed request.
- **Malformed event payload** — fall back to no-op; write the `.ack` anyway so the event isn't re-emitted forever.
- **`finished` or `cancelled` marker present** — the user ended the session. The watcher emits `WEBCOMPANION_FINISHED` or `WEBCOMPANION_CANCELLED`; see Mode D.

## Page-wide single-flight lock

The browser page is single-flight: while any submitted event is unacked, the page is locked (block comment / reject / dismiss affordances disabled, a "Claude is updating…" banner shown), and only one comment editor can be open at a time. The lock is server-authoritative — `/poll` reports `busy: true` until you write the event's `.ack`. Practical consequence for you: **always write the `<consumed_dir>/<event_id>.ack` when you finish handling an event**, even on a no-op or malformed payload — otherwise the page stays locked until the user is told your session died.

Two deliberate softenings of the lock:

- The **general composer stays usable while busy** — its submissions queue server-side and the watcher delivers them one at a time, so you may receive a second `WEBCOMPANION_EVENT` notification while (or right after) handling the first. Handle them in order; each gets its own ack.
- The client watches the watcher heartbeat (`watcher_age_s` in `/poll`). If the heartbeat goes stale (the Claude session died mid-event), the page **unlocks itself** and shows a "Claude's session is gone" warning instead of spinning forever. Events submitted in that state stay queued on disk; a freshly armed watcher for the same session directories will re-emit them.
