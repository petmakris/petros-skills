---
name: annotate
description: Render Claude responses as an interactive web page with span-based annotation. Three trigger paths — (1) auto: Claude routes its current response through the view when it contains 2+ distinct things the user might want to react to (plans, analyses, multi-paragraph answers, lists of findings); (2) postmortem: user manually invokes the skill ("annotate", "annotate that", "/annotate") after a big response has already landed, and the skill pushes the most recent prior assistant message through the same pipeline; (3) watcher event: a task-notification arrives whose first stdout line starts with `WEBCOMPANION_EVENT`, `WEBCOMPANION_FINISHED`, or `WEBCOMPANION_CANCELLED` — that's a previously-pushed response's watcher reporting in, and the skill must be re-invoked to parse the payload and respond. In all cases the user reads in the browser, clicks any block to comment, and Claude updates that block in place when it responds.
allowed-tools:
  - Bash
  - Read
  - Write
---

# /annotate — interactive annotation view

Long responses (multi-step plans, analyses, lists of findings) get pushed to a browser page where the user clicks any block to comment. Claude updates that block in place when it responds — no page reload, no re-push of the whole document.

The skill has two trigger paths. The pipeline downstream of "ensure the server is running" is identical for both — only the **content source** differs.

## Mode A — Forward (Claude-initiated)

Route to the annotation view when ANY of the following is true about the response you are about to write:

- It is a multi-step plan with 2+ steps the user might want to comment on.
- It is an analysis with 2+ distinct claims or recommendations.
- It is a list of findings, options, or items (≥2).
- It contains multiple paragraphs each making a separable point.

DO NOT use the annotation view for:

- Single-fact answers ("the port is 5432").
- Yes/no responses.
- Short prose with no addressable claims.
- Status updates, summaries, brief acknowledgments.
- Tool-result discussions where you're just reporting what a command produced.

### When to use a `kind: "sequence"` diagram block (Mode A extension)

A block in your response should be a sequence diagram (instead of prose) when ALL of:

- The content involves ≥ 2 named entities interacting (browser ↔ server, user ↔ system, two services...).
- The content has a clear temporal order — step 1, then step 2, ...
- Who-talks-to-whom matters — a numbered list loses that information.

Typical fits: code flows, request/response protocols, event lifecycles, deployment pipelines, state transitions tied to events over time.

**Do NOT use a sequence-diagram block for:**

- Single-actor flows (a numbered list does the job).
- Branching/decision logic where time isn't the dominant axis (flowcharts — not supported in v1).
- Static structure: class hierarchies, data shapes, dependency graphs.
- Anything that fits in 1–2 sentences.

**One diagram per flow.** Diagrams are heavier than prose blocks — visually and token-wise. A response that explains one flow gets one diagram block; longer explanations get prose blocks framing it. Don't emit two diagrams unless they're genuinely two separate flows.

### When to use a `kind: "choice"` block (Mode A extension)

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

When in doubt, prefer the annotation view.

**Content source in forward mode:** compose the response as a list of plain-markdown blocks and write those to `blocks.json` (see "How to push a response").

## Mode B — Postmortem (user-invoked)

The user invokes the skill after a big response has already been delivered in terminal. Typical triggers:

- The user types `/annotate` (skill is invoked directly).
- The user says "annotate", "annotate that", "annotate the last response", or anything semantically equivalent.

When invoked this way, treat the user's message as the trigger only — **do not** generate a fresh response. Instead:

1. Take **your most recent prior assistant message from conversation context** as the content. Do not consult transcript files; the conversation context is authoritative.
2. Use that text **verbatim**. No curating, no polishing, no rewording, no summarizing. What the user already saw in terminal must be what they see in the browser. The only transformation is markdown → styled HTML (handled by the renderer).
3. Strip nothing except: the final `assistant:` / system metadata wrappers if any, and any per-turn-hook trailer (e.g. a trailing absolute path the dump hook used to append). Substantive prose, lists, code blocks, headings — preserved exactly.
4. If your most recent prior assistant message is empty, trivial (a one-line acknowledgement), or contains only tool-call narration without standalone prose, do **not** push it. Instead, switch to **Mode C — armed for the session** (see below). Don't invent content; arm forward mode and reply once in terminal so the user knows annotate is on.
5. Split the prior message into blocks (one logical unit per block — a paragraph, a heading + its prose, one bullet, one code block), then follow the exact same flow as forward mode: `ensure_server.sh` → POST `/api/sessions` → write `meta.json` then `blocks.json` → announce the URL → **start the watcher** (see "Arming the watcher" below) → end your turn.

## Mode C — Armed for the session (no prior message to annotate)

Triggered when the user invokes the skill (`/annotate`, "annotate", etc.) but there is nothing usable to push — typically the first turn of a session, or right after a short status reply.

What to do:

1. Don't try to push the empty/trivial prior message.
2. Reply once in terminal, one line: *"Annotate is armed for this session — long-form responses from now on will route through the browser. Say 'respond in terminal' to disarm."*
3. From this turn on, treat forward mode (Mode A) as **armed**: route every response that meets *any* Mode A trigger, AND lower the bar — when in doubt, route. The arming persists across turns of this session because Claude reads its own prior "armed" line in conversation context.
4. Disarm if the user says "respond in terminal", "stop annotating", or anything semantically equivalent. Acknowledge briefly and stop routing for the rest of the session.

**Token-budget note:** postmortem mode does not produce a new response. The only outputs in your terminal turn are short status lines (creating session, writing files, announcing URL). Keep terminal text minimal.

## On every invocation: ensure the server is running

The server is a long-lived singleton shared across all Claude Code sessions. Each turn, run this **once** before composing a response:

```bash
PLUGIN_ROOT="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}"
"$PLUGIN_ROOT/skills/annotate/ensure_server.sh"
```

`$CLAUDE_PLUGIN_ROOT` is **not** exported into the Bash tool's shell, so it is resolved here from the plugin marketplace registry as a fallback. It's idempotent and fast (<100 ms when the server is already up). Internally it delegates to `skills/_shared/web_companion/ensure_server.sh` — no need to call that directly. Do **not** use `run_in_background: true` — wait for it to return. If it exits non-zero, surface the stderr to the user and stop.

## Create a session for this turn

After `ensure_server.sh` succeeds, read `$HOME/.claude/annotate/server.json` to get the server URL, then request a fresh session for the user's project directory:

```bash
SERVER_URL=$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/annotate/server.json")))["url"])')
curl -sf -X POST "$SERVER_URL/api/sessions" \
  -H 'Content-Type: application/json' \
  -d "$(printf '{"cwd": "%s"}' "$PWD")"
```

The response is JSON of the form:

```json
{"sid":"...","url":"http://HOST:PORT/s/SID/",
 "localhost_url":"http://localhost:PORT/s/SID/",
 "response_dir":"...","annotations_dir":"...","state_dir":"...",
 "events_dir":"...","consumed_dir":"..."}
```

Save `url`, `localhost_url`, `response_dir`, `state_dir`, `events_dir`, `consumed_dir` for the rest of this turn. Announce **both** URLs to the user (see "How to push a response"). `url` uses the public/Tailscale host (shareable across the LAN); `localhost_url` is the always-secure-context loopback URL — browser features that require a secure context (voice dictation) only work there. When the two are identical (no Tailscale host configured, `url` already on a loopback host), announce just one. (`annotations_dir` is no longer used by the annotate skill but is still returned by the server.)

## How to push a response

1. Compose the response as a **list of plain-markdown blocks**.  Each block is one logical unit (a paragraph, a heading + its prose, one bullet, one code block).  Aim for blocks of 3-15 lines — small enough that the user can read one at a time, large enough to carry a self-contained thought.
2. Write `meta.json` first:
   ```json
   {"response_id": "resp-<unix-timestamp>",
    "title": "<short title>",
    "claude_session_id": "$CLAUDE_CODE_SESSION_ID"}
   ```
   Read `claude_session_id` from the `CLAUDE_CODE_SESSION_ID` env var (exposed to all Bash tool calls).
3. Then write `blocks.json` at `<response_dir>/blocks.json`:
   ```json
   {"response_id": "<same as meta>",
    "title": "<same as meta>",
    "blocks": [
      {"id": "section-1", "title": "<short header>", "markdown": "<first block's markdown>"},
      {"id": "section-2", "title": "<short header>", "markdown": "<second block's markdown>"},
      ...
    ]}
   ```
   Block ids are sequential `section-1`, `section-2`, `section-3`, ... starting from 1. Each block also carries a **`title`** — a 2-5 word header shown on the block's collapsible card (e.g. `"What happens when you comment"`). Keep it a noun phrase, not a sentence. If you omit it, the client derives a header from the block's first heading or sentence, but an authored title is almost always cleaner. **When you author a `title`, do not also repeat it as a leading `#`/`##` heading inside that block's markdown** — the card already shows the title, so a duplicate heading reads twice. **Do not write a `version` field** — the server derives per-block versions from a content-hash chain stored in a sibling `versions.json`. Any `version` field you write is stripped on save and ignored on read.

### Inline HTML inside markdown blocks

A markdown block can contain raw HTML when prose isn't enough — comparison tables, callout boxes, dense tabular data, anything you'd otherwise contort markdown into. The renderer (`markdown-it`) is configured with `html: true`; after render, a conservative client-side sanitizer strips `<script>`, `<iframe>`, `<style>`, `<form>`, `on*` event-handler attributes, and `javascript:` URLs. Everything else passes through.

Two guidelines:

1. **Reuse the existing CSS variables.** `var(--accent)`, `var(--surface)`, `var(--surface-soft)`, `var(--border)`, `var(--text)`, `var(--text-strong)`, `var(--text-dim)`, `color-mix(...)` against them. Don't invent palettes — the page already has one. Inline `style="..."` is acceptable; a `<style>` block is not (the sanitizer strips it).

2. **Mark commentable sub-units with `data-annotate-id="<slug>"`.** The client uses this attribute to scope a click to a sub-unit of the block. Without it, clicks fall back to the whole block (`step_id: null`). Slugs are kebab-case, scoped within a single block — pick descriptive names (`verdict-row`, `auth-column`, `rate-limit-cell`), not positional indices. When you rewrite the block after a comment, **preserve `data-annotate-id` slugs on sub-units that still exist** so the rewrite contract round-trips cleanly.

Example:

```markdown
Three migration strategies considered:

<table class="weigh-up">
  <thead><tr><th></th>
    <th data-annotate-id="opt-bigbang">Big-bang</th>
    <th data-annotate-id="opt-incremental">Incremental</th>
  </tr></thead>
  <tbody>
    <tr><th>Risk</th>
      <td data-annotate-id="bigbang-risk">High — single window</td>
      <td data-annotate-id="incr-risk">Low</td>
    </tr>
  </tbody>
</table>
```

If the user clicks the `Incremental` header, the comment payload arrives with `step_id: "opt-incremental"`. Same rewrite contract as a diagram-step comment (SKILL.md §"Diagram block-rewrite contract"): fold the answer into the HTML — preserve surviving slugs, restructure freely otherwise.

### Diagram block shape

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

### Choice block shape

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

A choice block carries no `markdown`, and the question is shown in the card header — don't repeat it in the spec elsewhere. The user picks in the browser; you resolve the block on the watcher event (see Mode D).

4. Order matters: write `meta.json` before `blocks.json`, both atomically (write to `*.tmp` then `mv`).  The server reads both per request; an in-flight half-write falls back to the waiting page.
5. Tell the user, announcing **both** URLs (the loopback one first, since it's the one where voice dictation works):
   **"Response in browser → `<localhost_url>` (or `<url>` to open from another device).  Click any block to comment; the page updates that block in place when I respond."**
   If `localhost_url` and `url` are identical, announce just the one.
6. **Arm the watcher** (see next section).  The Monitor runs in the background; your turn ends immediately.  The user can chat in terminal while the page is open.
7. End your turn.

## Glossary (terminology surface)

`blocks.json` may include a sibling `glossary` array next to `blocks`:

```json
{
  "response_id": "...",
  "title": "...",
  "blocks": [...],
  "glossary": [
    {"term": "OnboardingOrchestrator",
     "definition": "Internal service coordinating new-user signup.",
     "role": "Upstream that emits the payload too early — the trigger of the bug."}
  ]
}
```

The client decorates matching terms in rendered block prose with a hover popover. Omit the field when no terms qualify.

### When to emit a glossary entry

While composing the blocks, ask yourself, for each project- or context-specific identifier that appears:

> If the reader didn't know this term, could they still follow this response?

Emit an entry **only when the answer is no**. Exclude any term that a competent engineer would resolve by Googling — `SQL`, `idempotent`, `mutex`, `hydration`, framework names, standard protocols, common patterns. Include identifiers that are unique to the user's project or that name a concept introduced by the current conversation.

Each entry has three fields:

- `term` — the exact string as it appears in the prose. Case-sensitive.
- `definition` — one line, generic (what this thing is).
- `role` — one line, contextual (what this thing does in *this specific response*).

The `role` field is what makes the glossary useful for debugging — it tells the reader why the term matters here, not just what it generically is.

### Term-set diff at rewrite time

When you handle a `WEBCOMPANION_EVENT` that targets a markdown block:

1. After composing the rewritten block markdown, apply the **drop rule**: any glossary entry whose `term` no longer appears (case-sensitive whole-word) in any block is dropped. Use `blocks.drop_unused_terms(doc)` — it does this in one call.
2. Apply the **add rule**: if the rewrite introduces a new project-specific identifier that wasn't already in the glossary and that meets the comprehension-blocker test above, append a new entry.

Do not re-extract the whole glossary on every rewrite. The common case — a rewrite that doesn't touch the term set — produces no glossary mutation.

## Arming the watcher

After writing `meta.json` + `blocks.json` and announcing the URL, start a long-lived `Monitor` keyed to this session's directories.  Use `persistent: true` — the watcher lives for the whole session and emits one notification per submitted comment.

Invocation:

```bash
PLUGIN_ROOT=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/annotate/server.json")))["plugin_root"])')
SKILL=annotate \
SID="<sid>" \
STATE_DIR="<state_dir>" \
EVENTS_DIR="<events_dir>" \
CONSUMED_DIR="<consumed_dir>" \
"$PLUGIN_ROOT/skills/_shared/web_companion/watcher.sh"
```

Substitute `<sid>`, `<state_dir>`, `<events_dir>`, `<consumed_dir>` from the session-create response (returned by `POST /api/sessions`).

Pass this command as the `Monitor` tool's `command` with `persistent: true` and a short `description` like `"annotate-wait sid=<sid>"`.

The watcher emits these stdout banners:

- **`WEBCOMPANION_EVENT skill=annotate sid=<sid> event_id=<id>`** — one per submitted comment.  Followed by `---payload---`, the event JSON, and `---end---`.
- **`WEBCOMPANION_FINISHED skill=annotate sid=<sid>`** — when the user clicks Done.
- **`WEBCOMPANION_CANCELLED skill=annotate sid=<sid>`** — when the user cancels (terminal `scrap it`, etc.).

Each stdout line wakes you once.  The watcher stays alive across many events until the session terminates.

After arming, also append a record to the pending registry so terminal-cancellation can find this session:

```bash
mkdir -p ~/.claude/annotate
REG="$HOME/.claude/annotate/pending-${CLAUDE_CODE_SESSION_ID}.json"
python3 - "$REG" "$SID" "$RID" "$TITLE" "$STATE_DIR" "$EVENTS_DIR" "$CONSUMED_DIR" <<'PY'
import json, os, sys
path, sid, rid, title, state_dir, events_dir, consumed_dir = sys.argv[1:]
try:
    data = json.load(open(path))
except FileNotFoundError:
    data = []
data.append({"sid": sid, "rid": rid, "title": title,
             "state_dir": state_dir, "events_dir": events_dir,
             "consumed_dir": consumed_dir})
tmp = path + ".tmp"
json.dump(data, open(tmp, "w"), indent=2)
os.replace(tmp, path)
PY
```

The registry persists across watchers within a single Claude Code session. It is *not* shared across sessions (keyed by `CLAUDE_CODE_SESSION_ID`).

## Mode D — handling a watcher event

You wake here when a task-notification arrives whose first stdout line is one of the `WEBCOMPANION_*` banners.

### `WEBCOMPANION_EVENT` (per-comment submission)

1. Parse the banner: `skill`, `sid`, `event_id`.
2. Read the event payload between the `---payload---` and `---end---` markers in the notification body. **If `type == "choice"`, jump to the `choice` subsection below.** Otherwise, fields are:
   - `block_id` — the block to update, or `null` for a general comment.
   - `step_id` — for `kind: "sequence"` blocks: the step row the user clicked, or `null` for whole-diagram comments. Absent/null for markdown blocks.
   - `type` — `"comment"`, `"reject"`, `"choice"`, or `"dismiss"`.
   - `selected_options` — for `type: "choice"`: the option id(s) the user picked (a list). Absent otherwise.
   - `text` — the user's free-text feedback.
   - `selected_text` — the span they highlighted, or `null` if the comment is block-scoped.
   - For `type == "dismiss"`: `block_id` is the block to remove; `text` is empty and ignored. Jump to the `dismiss` subsection below.
   - `images` — array of `{token, path}` entries (or empty).  When non-empty, `Read` each `path` before composing your rewrite so you see the screenshots.
3. **Apply the block-rewrite contract** (see next section).
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

When `block_id` is `null` (general comment):

1. Read the comment text.  It will be a directive that applies across blocks ("make this shorter", "more casual tone", "remove the second paragraph", etc.).
2. Update *only the blocks that actually need updating* to apply the directive. Don't re-emit untouched blocks.
3. Save and ack as above.

## Diagram block-rewrite contract

For `WEBCOMPANION_EVENT` payloads that target a `kind: "sequence"` block, the rewrite contract has three deltas from the markdown contract above:

1. **Targeted by default when `step_id` is present.** A comment on step `s4` ("does this fire once per click, or can it batch?") rewrites just that step's `label` and/or `sub`. Other steps untouched. Step ids stay stable across rewrites; new steps mint fresh ids via `next_step_id`.

2. **Whole-diagram comments (`step_id: null`)** apply across steps as needed — restructure phases, reorder steps, add/remove actors. Analogous to general comments with `block_id: null` in the markdown contract.

3. **Reject on a step** — either soften/withdraw the claim by rewriting the step, or hold the line by rewriting the sub-caption with reasoning. Don't drop the step silently. Same "fold the answer into the prose" spirit; here the "prose" is the spec.

Persist updates via `blocks.update_spec_block(doc, block_id, new_spec)` — returns `True` only on real change (canonical-JSON content hash). Then `save_atomic` as today. Watcher re-emit safety is preserved. (All these helpers live in `skills/annotate/blocks.py`; the server and tests import it aliased as `blocks_model`, but in SKILL.md it's always `blocks.`.)

**Off-topic comments** (user comments on `s4` about something that really belongs in `s2`) follow the same "use judgment" rule as the markdown contract: rewrite the targeted step to be clearer about its actual topic, or rewrite the neighboring step, or both.

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
- **Server unreachable** — re-run `ensure_server.sh`; it will restart the server. Retry the failed request.
- **Malformed event payload** — fall back to no-op; write the `.ack` anyway so the event isn't re-emitted forever.
- **`finished` or `cancelled` marker present** — the user ended the session. The watcher emits `WEBCOMPANION_FINISHED` or `WEBCOMPANION_CANCELLED`; see Mode D.

## Token budget

A typical 5-paragraph response renders to ~300–600 tokens of markdown when pushed to `blocks.json`. Each subsequent wake-up (one comment → one block rewrite) is typically 50–200 tokens — short and focused. The per-block flow means you are never asked to address a whole document's worth of annotations in one turn; just handle the one event and end the turn. Net cost per event is comparable to a short terminal reply, with materially better UX for any non-trivial output.

**Glossary additions.** When a response includes a glossary, each entry adds roughly 45–80 tokens. A typical response with 2–4 entries adds 100–320 tokens (≈25–50% on top of a 600-token response). Rewrites that don't change the term set add nothing.

## Page-wide single-flight lock

The browser page is single-flight: while any submitted event is unacked, the page is locked (all comment / reject / dismiss affordances disabled, a "Claude is updating…" banner shown), and only one comment editor can be open at a time. The lock is server-authoritative — `/poll` reports `busy: true` until you write the event's `.ack`. Practical consequence for you: **always write the `<consumed_dir>/<event_id>.ack` when you finish handling an event**, even on a no-op or malformed payload — otherwise the page stays locked forever.
