# Pushing a response to the annotation view

Read this when you are about to **push** a response to the browser — either
Forward mode (Mode A, you decided to route per the kind menu in SKILL.md) or
because the user invoked `/annotate` / "annotate" (Modes B & C below).

The pipeline downstream of "ensure the server is running" is identical for all
modes — only the **content source** differs.

## Mode A — Forward (Claude-initiated)

You already decided to route (SKILL.md routing decision). **Content source:**
compose the response as a list of plain-markdown blocks (plus any `kind: "sequence"|"diagram"|"choice"` blocks per `references/block-kinds/`) and write those to `blocks.json` (see "How to push a response" below).

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
2. Write `meta.json` first (at `<response_dir>/meta.json`):
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

   For non-markdown blocks (`kind: "sequence"|"diagram"|"choice"`), read the exact spec shape in `references/block-kinds/<kind>.md`.

4. Order matters: write `meta.json` before `blocks.json`, both atomically (write to `*.tmp` then `mv`).  The server reads both per request; an in-flight half-write falls back to the waiting page.
5. Tell the user, announcing **both** URLs (the loopback one first, since it's the one where voice dictation works):
   **"Response in browser → `<localhost_url>` (or `<url>` to open from another device).  Click any block to comment; the page updates that block in place when I respond."**
   If `localhost_url` and `url` are identical, announce just the one.
6. **Arm the watcher** (see "Arming the watcher").  The Monitor runs in the background; your turn ends immediately.  The user can chat in terminal while the page is open.
7. End your turn.

## Code blocks

Fenced code blocks are syntax-highlighted (highlight.js, Tokyo Night theme) and rendered as a dark card. **Tag the opening fence with the language** (```` ```python ````, ```` ```ts ````, ```` ```bash ````) for accurate coloring; an untagged fence is auto-detected, which is usually right but not guaranteed. Inline `` `code` `` stays a light chip — don't fence single identifiers.

## Inline HTML inside markdown blocks

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

If the user clicks the `Incremental` header, the comment payload arrives with `step_id: "opt-incremental"`. Same rewrite contract as a diagram-step comment (`references/handling-events.md` § "Diagram block-rewrite contract"): fold the answer into the HTML — preserve surviving slugs, restructure freely otherwise.

For a **high-fidelity** mock that needs `<style>`/`<script>`/Tailwind, hover, or interaction, use `kind: "mockup"` instead — it renders in a sandboxed iframe with the sanitizer lifted. The `data-annotate-id` region convention above is unchanged. See `references/block-kinds/mockup.md`.

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

(The glossary term-set diff applied **at rewrite time** lives in `references/handling-events.md` § "Glossary term-set diff at rewrite time".)

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
CLAUDE_SID="$CLAUDE_CODE_SESSION_ID" \
"$PLUGIN_ROOT/skills/_shared/web_companion/watcher.sh"
```

Substitute `<sid>`, `<state_dir>`, `<events_dir>`, `<consumed_dir>` from the session-create response (returned by `POST /api/sessions`). `CLAUDE_SID` is this Claude Code session's own id — read from the `CLAUDE_CODE_SESSION_ID` env var (exposed to all Bash tool calls, same one used for `meta.json`'s `claude_session_id` and the pending registry below). The watcher writes a per-session heartbeat file keyed by it (`state/watchers/<CLAUDE_SID>.hb`), which is how the server counts distinct live Claude sessions attached to one shared workspace. It's optional — an unset `CLAUDE_SID` doesn't break the watcher, it just isn't counted.

Pass this command as the `Monitor` tool's `command` with `persistent: true` and a short `description` like `"annotate-wait sid=<sid>"`.

The watcher emits these stdout banners:

- **`WEBCOMPANION_EVENT skill=annotate sid=<sid> event_id=<id>`** — one per submitted comment.  Followed by `---payload---`, the event JSON, and `---end---`.
- **`WEBCOMPANION_FINISHED skill=annotate sid=<sid>`** — when the user clicks Done.
- **`WEBCOMPANION_CANCELLED skill=annotate sid=<sid>`** — when the user cancels (terminal `scrap it`, etc.).

Each stdout line wakes you once.  The watcher stays alive across many events until the session terminates. When an event fires, follow `references/handling-events.md`.

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
