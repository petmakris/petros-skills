# Annotate — terminology glossary (inline pills)

**Date:** 2026-05-24
**Status:** Design — awaiting user review

## Problem

Claude Code responses routinely lean on project-specific names — services,
components, internal codenames, file/symbol references — without explaining
them. The reader (Petros) often doesn't recognize the name and reads past it
believing they got the gist. Comprehension silently degrades.

Generic engineering jargon (SQL, idempotent, mutex, hydration, etc.) is not the
problem. The user can Google those. The problem is the **project- and
context-specific identifiers** that aren't on Google and that Claude assumes
are common knowledge because they appeared in the conversation or the
codebase it just read.

## Goal

Add a terminology surface to the annotate skill that:

1. Marks project-specific terms inline in the rendered response.
2. Shows a one-line definition plus the term's role in *this* response on hover.
3. Triggers itself when needed; lets the user force a refresh otherwise.
4. Costs a bounded fraction of the existing annotate token budget.

Non-goals:

- Glossing general engineering terms.
- Building a project-wide glossary that persists across sessions.
- Letting the user comment on glossary entries (comments still anchor to blocks).

## Design decisions

| Question | Decision |
|---|---|
| When the glossary fires | Self-judged at composition time + always-available "Decode more" button. |
| Which terms qualify | Project/context-specific identifiers whose absence from the reader's vocabulary would block comprehension of *this* response. Excludes any general engineering term that could be Googled. |
| Entry shape | `{term, definition, role}` — `definition` is a one-line generic description; `role` is the term's function in *this specific response* (e.g. "upstream that emits the payload too early"). |
| UI form | Inline pills with hover popover. Subtle dotted underline on terms in-prose; hovering shows the entry. |
| Rewrite behavior | Term-set diff: unchanged → no-op; new term → add entry; last occurrence removed → drop entry. |
| Decode-more refresh | One focused regeneration pass over current blocks; rewrites `glossary` only, not `blocks`. |

## Architecture

### Data-model change — `blocks.json`

`blocks.json` grows a sibling `glossary` array:

```json
{
  "response_id": "resp-...",
  "title": "...",
  "blocks": [...],
  "glossary": [
    {"term": "OnboardingOrchestrator",
     "definition": "Internal service coordinating new-user signup.",
     "role": "Upstream that emits the payload too early — the trigger of the bug."}
  ]
}
```

Field is optional; absence means no glossary. Block shape unchanged. The
existing block-rewrite contract is unaffected.

### Composition flow (Mode A and Mode B)

After drafting the response blocks, Claude applies a self-check:

> Are there project- or context-specific identifiers in these blocks whose
> absence from the reader's vocabulary would block comprehension? Exclude
> anything that a competent engineer would resolve by Googling.

If yes, emit one glossary entry per qualifying term, each with the
`{term, definition, role}` shape. Otherwise omit the `glossary` field. The
self-check is part of Claude's normal composition reasoning — no separate LLM
call.

### Renderer change — `skills/annotate/server.py` + client

Two pieces:

1. **Server-side term decoration.** In `_render_block_for_raw`, after the
   block's markdown is rendered to HTML, run a single pass per block scanning
   for case-sensitive whole-word matches of any term in the document's
   glossary. Wrap each match in
   `<span class="gloss-term" data-term="OnboardingOrchestrator">OnboardingOrchestrator</span>`.
   Skip matches inside `<code>` and `<pre>` (don't decorate inside code spans
   or code blocks).

2. **Client-side popover.** A small vanilla-JS popover (~30 lines, no
   library) attached on page load. On hover/focus of a `.gloss-term`, read
   `data-term`, look up the matching entry from a JSON blob the page already
   has access to (the existing `/api/sessions/<sid>/blocks` response already
   returns the full doc — extend it to include `glossary`). Render
   `<term>` (bold) + `<definition>` + italic `<role>` in the popover.

### Decode-more trigger

Adds one button to the page header: "Decode more". Click → POST to a new
endpoint `/api/sessions/<sid>/glossary_refresh` (no body). The server writes
a custom event into `events_dir`:

```json
{"type": "glossary_refresh", "block_id": null, "text": "", "selected_text": null, "images": []}
```

This reuses the existing event-append + watcher path. The watcher emits
`WEBCOMPANION_EVENT` as today. The Mode D handler in `SKILL.md` adds one new
case: when `type == "glossary_refresh"`, run a focused regeneration over the
current blocks, replace `doc.glossary` wholesale, save atomically, and ack.
No block versions bump.

### Rewrite-time behavior

In the existing block-rewrite contract (`SKILL.md` § block-rewrite contract),
after composing the new block markdown, apply two rules:

1. **Drop rule.** For each entry in the current `glossary`, check
   whether its `term` still appears (case-sensitive whole-word) in any
   block of the document (with the rewritten block substituted). If
   not, drop the entry.
2. **Add rule.** Scan the rewritten block for project-specific
   identifiers using the same self-check that drives composition. For
   each one that doesn't already have a `glossary` entry, add one.

The drop check is a string scan, not an LLM call. The add check is
part of Claude's normal reasoning while composing the rewrite — no
separate pass.

Common case: rewrite that doesn't touch the term set produces no
glossary mutation at all.

## Components

```
skills/annotate/
  blocks.py             ← +glossary field on BlocksDoc; load/save; diff helper
  server.py             ← +/api/.../glossary_refresh endpoint
                          +decorate_terms() pass in _render_block_for_raw
                          +include glossary in raw API response
  static/popover.js     ← new — popover behavior for .gloss-term spans
  static/popover.css    ← new — pill underline + popover styling
  SKILL.md              ← +composition self-check rule
                          +rewrite term-set diff
                          +Mode D case for glossary_refresh
  tests/                ← +renderer test (decoration, code-span skip, case)
                          +rewrite test (term-set diff produces correct delta)
                          +e2e test (decode-more triggers regen, page updates)
```

Estimated net new code: ~30 lines in `blocks.py`, ~20 lines in `server.py`,
~50 lines client (CSS + JS), ~10 lines in `SKILL.md` prose.

## Data flow

### Composition (Mode A / Mode B)

```
Claude composes blocks
    │
    ├── self-check: any project-specific terms blocking comprehension?
    │       │
    │       └── yes → produce glossary entries
    │
    └── write blocks.json with optional `glossary` field
         │
         └── server renders blocks → decorates terms → client shows pills
```

### Comment-driven rewrite

```
User comment → WEBCOMPANION_EVENT → Claude rewrites block
    │
    ├── compute terms_after by scanning all blocks
    ├── new project-specific identifier in rewrite? → add entry
    ├── existing glossary entry no longer referenced? → drop entry
    └── save blocks.json
```

### Decode-more

```
User clicks button → POST /glossary_refresh → event written
    │
    └── WEBCOMPANION_EVENT type=glossary_refresh
         │
         └── Claude reads blocks, regenerates glossary wholesale
              │
              └── save blocks.json (glossary replaced; blocks untouched)
```

## Edge cases

- **Code spans and code blocks.** Renderer must skip term decoration
  inside `<code>` and `<pre>`. Avoids visual noise on `OnboardingOrchestrator`
  appearing literally in a code snippet.
- **Multiple casings of the same identifier.** `InsightsAggregator` and
  `insights_aggregator` are treated as distinct terms — separate entries.
  Renderer matches case-sensitively.
- **Term coincides with a common English word.** The selection rule already
  filters this out at the source — project-specific identifiers are
  unique-looking by nature. Renderer match is case-sensitive whole-word,
  which avoids matching `Aggregator` inside `Aggregators`.
- **Glossary entry obsoleted by rewrite.** If the last occurrence is
  removed, the entry is dropped. Renderer no-ops on missing entries.
- **Self-check is wrong.** This is the same failure mode as the existing
  "should I route to annotate?" self-check. Mitigation: the Decode-more
  button is always available and forces a regeneration.
- **Server unreachable during decode-more.** Existing retry on
  `ensure_server.sh` covers it; the button POST returns a non-2xx and the
  client surfaces a toast.
- **Watcher restart re-emits a `glossary_refresh` event.** The handler is
  idempotent — replacing `doc.glossary` with the same content is a no-op
  via the same atomic save mechanism.

## Error handling

- Malformed `glossary` field on disk → load returns `[]`, renderer skips
  decoration; no crash.
- Term in `glossary` not present in any block → renderer silently skips it;
  next rewrite drops it.
- `glossary_refresh` event with corrupt payload → ack and no-op (same as
  malformed comment-event handling today).

## Testing

- **Renderer unit tests** (in `tests/`): decoration wraps the right spans,
  skips code spans, respects case-sensitive whole-word match, no-ops when
  glossary absent.
- **Rewrite unit tests**: term-set diff against fixture blocks produces
  expected add/drop deltas.
- **E2E test** (Playwright, mirroring existing annotate E2E setup):
  glossary entries appear as pills; hover surfaces the popover; clicking
  Decode-more triggers a regeneration that updates the page without
  reloading.

## Token-cost envelope

Estimated added cost on top of current annotate baseline:

| Scenario | Added tokens |
|---|---|
| Response with no project terms | ~0 (self-check skips emit) |
| Response with 2–4 project terms (typical) | +100–320 |
| Block rewrite, term set unchanged | ~0 |
| Block rewrite, new term added | +45–80 |
| Decode-more click | +150–400 |

Session envelope (3 annotated responses + 5 rewrites, no manual refresh):
**+25–35% over current annotate token cost**. The self-judgment gate
bounds the growth — responses that don't lean on project-specific names
pay essentially nothing.

## Open questions

None at design time. The composition self-check's calibration ("is this
term Googleable?") may need tuning during implementation — we'll watch
real responses and adjust the prompt in `SKILL.md` if the false-negative
rate is high.

## Out of scope

- Persistent glossary across responses or sessions.
- Glossary entries as commentable surfaces.
- Auto-linking terms to repository files or external docs.
- Glossing inside sequence-diagram blocks (markdown blocks only in v1).
