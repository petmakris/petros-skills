# Annotate choice blocks — design

**Status:** design approved, ready for plan
**Date:** 2026-06-03

## Goal

Give the `annotate` skill a way to pose a **decision** to the user — a question with a small set of discrete options — and let the user answer by *picking* rather than by typing free-text. This brings the terminal `AskUserQuestion` interaction (multiple-choice, single- or multi-select) into the browser annotation view, where today the only input modalities are free text, a highlighted span, and a pasted image.

The interaction is async by nature, matching annotate's existing rhythm: Claude poses the question and ends its turn; the user picks whenever; the watcher wakes Claude; Claude folds the decision into the block in place and continues the task. This differs from the terminal tool, which is a *blocking* gate — and the difference is intentional. The answered question collapsing into the decision in place is, if anything, nicer than the terminal version, where the question just vanishes.

A choice block is a third block `kind`, slotting alongside `markdown` and `sequence`. It rides the same rails: spec in `blocks.json`, renderer dispatched by `kind`, event through `/api/submit` → watcher → Mode D wake → block rewrite. The only genuinely new surface is the client renderer and one validation branch server-side.

## Non-goals (v1)

- **Multi-question panels.** The terminal tool groups 1–4 questions; v1 is one question per block. Emit several choice blocks if you need several questions. Keeps the spec and the rewrite contract simple.
- **"Other" free-text escape hatch.** No textarea on a choice block in v1. The user picks from the listed options. A clean later addition (reuse the existing comment textarea), not v1.
- **Changing your answer after resolve.** The pick is final, like the terminal tool. Once Claude resolves the block into a decision, the options are gone.
- **Blocking semantics.** Claude does not wait inline for the answer. It poses and ends the turn, consistent with every other annotate interaction.

## When Claude reaches for a choice block

Addition to `SKILL.md`, slotting into the existing Mode A trigger language as another block kind Claude may emit.

**Use a `kind: "choice"` block when all of:**
- The response reaches a genuine decision point — the next step depends on the user's preference.
- There are 2–4 discrete, mutually-comparable options (or, for multi-select, a set the user picks a subset from).
- A free-text comment is a worse fit than a pick — the answer space is closed, not open.

Typical fits: "which migration strategy", "which datastores to provision", "which of these designs", "scope this to A, B, or both".

**Don't use a choice block for:**
- Open-ended questions where the answer isn't one of a few options (use prose + let the user comment).
- A yes/no that's better handled in terminal, or where Claude must block before doing anything else (use the terminal `AskUserQuestion` tool — annotate is async).
- More than ~4 options, or options that need paragraphs to explain (that's prose).

## Architecture: a third block kind

`blocks.json` already carries an explicit `kind` per block (`markdown`, `sequence`; absent = `markdown`). Choice blocks add `kind: "choice"`. No wire-format migration.

### Spec schema

```json
{
  "id": "section-3",
  "kind": "choice",
  "spec": {
    "question": "How should we cut over?",
    "multiSelect": false,
    "options": [
      {"id": "o1", "label": "Big-bang",    "description": "Single cutover window"},
      {"id": "o2", "label": "Incremental", "description": "Phase by read/write path"},
      {"id": "o3", "label": "Dual-write",  "description": "Write both, migrate reads"}
    ]
  }
}
```

- `question` — the prompt, one line.
- `multiSelect` — `false` ⇒ radio (exactly one); `true` ⇒ checkbox (one or more).
- `options[]` — 2–4 entries. Each:
  - `id` — `o1`, `o2`, … minted by hand, stable across rewrites (mirrors sequence `s1, s2`). This is what the submit event references.
  - `label` — the human-facing choice, terse.
  - `description` — optional sub-text under the label.

Block id stays `section-N` (assigned by the same `next_block_id` scheme as every other kind).

## Data flow: pick → event → resolution

```
Browser                     Annotate server            Watcher           Claude
  │ user selects option(s)        │                       │                 │
  │ POST /api/submit              │                       │                 │
  │  {type:"choice",             ─┼─▶ validate against     │                 │
  │   selected_options:[...]}     │   spec option ids      │                 │
  │                               │   append event ───────▶│ WEBCOMPANION_   │
  │ ◀── 202 {event_id} ───────────│                       │   EVENT ───────▶│ Mode D:
  │ overlay "Claude responding"   │                       │                 │  resolve block
  │                               │◀── poll: block now ────────────────────  │  + continue task
  │ re-render (choice→markdown)   │   kind:markdown, version++              │  write ack
```

### The submit payload

The client POSTs to the existing `/api/submit` with a structured field rather than stuffing prose into `text`:

```json
{"block_id": "section-3", "type": "choice", "selected_options": ["o2"],
 "step_id": null, "text": "", "images": []}
```

`server.py:handle_submit` gains a `choice` branch, paralleling the existing `step_id` validation:

- `type: "choice"` joins the allowed type set (currently `comment`, `reject`).
- The target block must be `kind: "choice"` (422 otherwise).
- `selected_options` must be a non-empty list of ids all present in the block's `spec.options` (422 on any unknown id, 422 on empty).
- When `multiSelect` is `false`, exactly one id is required (422 on more than one).
- Closed/terminal session ⇒ 409 (existing `_is_terminal` check).

The watcher relays the event payload verbatim — **no watcher change**. `events.append` already stores arbitrary JSON.

### Resolution (Mode D)

On `WEBCOMPANION_EVENT` with `type: "choice"`, Claude:

1. **Folds the decision into the block, converting `kind: "choice"` → `kind: "markdown"`.** The question and options collapse into a decision paragraph (e.g. *"Decision: incremental cutover — phase 1 ships the read path, …"*). The options disappear; the answer is final.
2. **Continues the task** — the picked option drives the next step. Claude may append follow-up blocks to `blocks.json` and/or take the implied action. This is the "rewrite + continue" behavior, broader than the pure markdown-comment rewrite contract.
3. Writes the `<consumed_dir>/<event_id>.ack` marker and ends the turn. No terminal output, watcher stays armed.

Kind-change across a rewrite is free: the client re-renders by `kind` each poll, and `versions.json` derives from a content hash, so the version simply bumps. The "submitted — Claude is responding" overlay reuses the existing mechanism that clears when the `event_id` appears in `consumed_dir` — no new client signalling.

**Multi-select resolution:** the decision prose names all picked options. Same contract otherwise.

**Re-apply safety:** if the watcher re-emits a processed event, Claude's resolution reproduces the same block content; the content-hash-aware `update_block` returns `False` (no version growth) and the `.ack` re-write is idempotent. Same guarantee as today.

## Client renderer

New `kind === "choice"` branch in `renderBlock` (`static/script.js`, the dispatch around the existing markdown/sequence split):

- Render `spec.question`, then each option as a labelled `<input type="radio">` (when `multiSelect` is false) or `<input type="checkbox">` (when true), with the optional `description` as sub-text.
- A Submit button, disabled until at least one option is selected.
- On submit: POST `{block_id, type:"choice", selected_options:[...], step_id:null, text:"", images:[]}` via the existing `WebCompanion.api.submit`, then enter the existing "submitted — Claude is responding" state.
- No textarea (no "Other" in v1).
- Styling reuses the existing CSS variables (`--accent`, `--surface`, `--surface-soft`, `--border`, `--text`, `--text-dim`) so choice blocks sit visually with the rest of the page. No new palette.

A resolved block arrives as `kind: "markdown"` on the next poll and renders through the existing markdown path — no special "resolved choice" state needed.

## SKILL.md additions

Paralleling the existing sequence-diagram sections:

- **"When to use a `kind: choice` block"** — the trigger rules above, in Mode A.
- **Choice block shape** — the spec schema, option-id convention.
- **Mode D — choice events** — parse `selected_options`, resolve block (choice→markdown), continue the task, ack.
- **Choice block-rewrite contract** — fold the decision into prose; final, no Q&A panel; multi-select names all picks; reject is not applicable (a pick is a pick).

## Testing

- **Server `handle_submit` (choice branch):**
  - valid single-select → 202, event carries `selected_options`.
  - valid multi-select (`multiSelect:true`, ≥1 id) → 202.
  - unknown option id → 422.
  - empty `selected_options` → 422.
  - single-select with 2 ids → 422.
  - `type:"choice"` targeting a non-choice block → 422.
  - closed session → 409.
- **Model:** option-id validation helper (ids ⊆ spec, cardinality vs `multiSelect`).
- **Client:** manual verification (render radio vs checkbox, submit payload shape, submitted overlay, resolved re-render). Consistent with how sequence-diagram client behavior is verified.

## Scope guardrails (YAGNI)

No "Other" free-text, no multi-question panels, no answer-changing after resolve, no blocking semantics. Each is a clean later addition on this foundation, none is v1.
