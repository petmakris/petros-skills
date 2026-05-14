---
name: annotate
description: Render long-form Claude responses as an interactive web page with span-based annotation. Activated automatically by Claude whenever a response contains 2+ distinct things the user might want to react to (plans, analyses, multi-paragraph answers, lists of findings). The user reads in the browser, highlights any text, leaves free-text comments, and submits — Claude reads the annotations on the next turn.
allowed-tools:
  - Bash
  - Read
  - Write
---

# /annotate — interactive annotation view

Long responses (multi-step plans, analyses, lists of findings) get pushed to a browser page where the user highlights any text and leaves free-text comments. You read structured annotations on your next turn and address each.

## When to use

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

When in doubt, prefer the annotation view.

## On every invocation: ensure the server is running

The server is a long-lived singleton shared across all Claude Code sessions. Each turn, run this **once** before composing a response:

```bash
"$CLAUDE_PLUGIN_ROOT/skills/annotate/ensure_server.sh"
```

It's idempotent and fast (<100 ms when the server is already up). Do **not** use `run_in_background: true` — wait for it to return. If it exits non-zero, surface the stderr to the user and stop.

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
{"sid":"...","url":"http://localhost:PORT/s/SID/",
 "response_dir":"...","annotations_dir":"...","state_dir":"..."}
```

Save `url`, `response_dir`, `annotations_dir`, `state_dir` for the rest of this turn. The session URL is what you announce to the user.

## How to push a response

1. Compose the response as **plain markdown**. No frontmatter, no block IDs — the server assigns those.
2. Write `meta.json` first: `{"response_id": "resp-<timestamp>", "title": "<short title>"}`.
3. Then write the markdown to `<response_dir>/response.md`. Order matters: meta first, response second, so any incoming `GET /` between the two writes sees a consistent pair (the server reads both files per-request and falls through to the waiting page if `response.md` isn't there yet).
4. Tell the user, in one short sentence: **"Response in browser → `<url>`. Submit when ready."**
5. End your turn. Do not produce additional content.

The plugin's Stop hook (`hooks/annotate-wait.py`) will block here, polling for
`annotations.json` up to 30 minutes. When the user clicks Submit, the hook
injects the annotations payload as a system reminder so your next turn starts
with them already in context — no user typing required.

## How to read annotations

On your next turn, **before** any other action:

1. If the Stop hook injected an `additionalContext` system reminder containing
   the annotations payload, use it directly. Otherwise fall back to reading
   `<annotations_dir>/annotations.json` from disk (it's the source of truth and
   the hook may have truncated very large payloads).
   - Exists → read it.
   - Doesn't exist → user didn't submit, and the hook either timed out (30 min)
     or the server went away. Treat the terminal message as implicit "looks
     good" and continue.

2. Verify `response_id` matches the response you pushed. If mismatch, re-push and warn the user.

3. **Implicit approval:** any block not present in the `annotations` array is approved by the user. Acknowledge briefly only if you have something to add; otherwise move on.

4. For each annotation in the array:
   - `block_id` = index of the block in your prior `response.md` (b-0 is the first block).
   - `type` = one of `reject`, `question`, `rewrite`, `comment`. Drives how you respond:
     - `reject` — user disagrees with the highlighted point. Engage and either justify, revise, or back off.
     - `question` — user wants you to expand or clarify the highlighted point.
     - `rewrite` — user wants the highlighted span literally replaced with `replacement`. Apply the rewrite and move on (no debate unless the rewrite breaks something).
     - `comment` — free-text feedback; parse intent yourself.
   - `selected_text` = literal text the user highlighted, or absent if the annotation is block-scoped (the user clicked a hover-action button without highlighting first).
   - `comment` = free-text feedback. May be empty for `reject`.
   - `replacement` = present only on `rewrite` annotations; the literal text the user wants substituted.
   - Optional `prefix` and `suffix` (~20 chars each) disambiguate when the same `selected_text` appears multiple times in the block.

5. Address each annotation in your next response. If the new response is also long-form (2+ items), loop back to "How to push" with a fresh `response_id`.

## Edge cases

- **Empty annotations array** — user submitted with no comments. Treat as approval; proceed.
- **`selected_text: ""`** — comment refers to the entire block; treat the block as the anchor.
- **You're producing v2 of a response** — write a new `response.md` with a fresh `response_id`. Don't migrate previous annotations forward.
- **Server unreachable** — see "verify the server is alive". Restart and continue.
- **Malformed `annotations.json`** — fall back to "no parseable annotations"; continue with terminal text.
- **`cancelled` marker present** — the user clicked Cancel. The Stop hook exits silently; you get a normal next turn driven by whatever the user types. Don't try to re-engage the annotate flow unless the user asks.

## Token budget

A typical 5-paragraph response renders to ~300–600 tokens of markdown. The annotations.json reply is typically 50–300 tokens. Net cost is comparable to writing the response in-terminal with materially better UX for any non-trivial output.
