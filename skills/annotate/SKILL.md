---
name: annotate
description: Render Claude responses as an interactive web page with span-based annotation. Three trigger paths — (1) auto: Claude routes its current response through the view when it contains 2+ distinct things the user might want to react to (plans, analyses, multi-paragraph answers, lists of findings); (2) postmortem: user manually invokes the skill ("annotate", "annotate that", "/annotate") after a big response has already landed, and the skill pushes the most recent prior assistant message through the same pipeline; (3) watcher event: a task-notification arrives whose first stdout line starts with `ANNOTATE_SUBMITTED` or `ANNOTATE_CANCELLED` — that's a previously-pushed response's watcher reporting in, and the skill must be re-invoked to parse the payload and respond. In all cases the user reads in the browser, highlights any text, leaves free-text comments, and submits — Claude reads structured annotations on the next turn.
allowed-tools:
  - Bash
  - Read
  - Write
---

# /annotate — interactive annotation view

Long responses (multi-step plans, analyses, lists of findings) get pushed to a browser page where the user highlights any text and leaves free-text comments. You read structured annotations on your next turn and address each.

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

When in doubt, prefer the annotation view.

**Content source in forward mode:** compose the response as fresh plain markdown and write that to `response.md`.

## Mode B — Postmortem (user-invoked)

The user invokes the skill after a big response has already been delivered in terminal. Typical triggers:

- The user types `/annotate` (skill is invoked directly).
- The user says "annotate", "annotate that", "annotate the last response", or anything semantically equivalent.

When invoked this way, treat the user's message as the trigger only — **do not** generate a fresh response. Instead:

1. Take **your most recent prior assistant message from conversation context** as the content. Do not consult transcript files; the conversation context is authoritative.
2. Use that text **verbatim**. No curating, no polishing, no rewording, no summarizing. What the user already saw in terminal must be what they see in the browser. The only transformation is markdown → styled HTML (handled by the renderer).
3. Strip nothing except: the final `assistant:` / system metadata wrappers if any, and any per-turn-hook trailer (e.g. a trailing absolute path the dump hook used to append). Substantive prose, lists, code blocks, headings — preserved exactly.
4. If your most recent prior assistant message is empty, trivial (a one-line acknowledgement), or contains only tool-call narration without standalone prose, do **not** push it. Instead, switch to **Mode C — armed for the session** (see below). Don't invent content; arm forward mode and reply once in terminal so the user knows annotate is on.
5. From here on, follow the exact same flow as forward mode: `ensure_server.sh` → POST `/api/sessions` → write `meta.json` then `response.md` → announce the URL → **start the watcher** (see "Arming the watcher" below) → end your turn.

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
2. Write `meta.json` first: `{"response_id": "resp-<timestamp>", "title": "<short title>", "claude_session_id": "$CLAUDE_CODE_SESSION_ID"}`. Read `claude_session_id` from the `CLAUDE_CODE_SESSION_ID` env var (exposed to all Bash tool calls). The field is no longer load-bearing for hook filtering (the hook is gone), but the renderer still surfaces it and the value remains useful for human auditing of session dirs.
3. Then write the markdown to `<response_dir>/response.md`. Order matters: meta first, response second, so any incoming `GET /` between the two writes sees a consistent pair (the server reads both files per-request and falls through to the waiting page if `response.md` isn't there yet).
4. Tell the user, in one short sentence: **"Response in browser → `<url>`. Submit when ready."**
5. **Arm the watcher** (see "Arming the watcher" below). The Monitor runs in the background; your turn ends immediately. The user is free to chat. When they submit (or cancel) in the browser, you'll wake up with the annotations payload in your next turn — no user typing required.
6. End your turn. Do not produce additional content.

## Arming the watcher

After writing `meta.json` and `response.md` and announcing the URL, start a long-lived `Monitor` keyed to this session's directories. Use `persistent: true` (annotations can sit for hours; non-persistent Monitor caps at 1 hour, and Bash background tasks cap at 10 minutes).

The script:

```bash
ANN="<annotations_dir>/annotations.json"
CANCELLED="<state_dir>/cancelled"
SID="<sid>"
RID="<response_id>"
TITLE="<short title>"

while [ ! -f "$ANN" ] && [ ! -f "$CANCELLED" ]; do
  sleep 1
done

if [ -f "$ANN" ]; then
  printf 'ANNOTATE_SUBMITTED sid=%s rid=%s title=%q\n' "$SID" "$RID" "$TITLE"
  printf '%s\n' '---routing---'
  printf '%s\n' 'If your reply addresses 2+ annotations, contains a plan, or lists separable points, push it back through the annotate browser by re-invoking the skill. If it is a short acknowledgement or single-fact answer, respond in terminal.'
  printf '%s\n' '---annotations---'
  cat "$ANN"
else
  printf 'ANNOTATE_CANCELLED sid=%s rid=%s title=%q\n' "$SID" "$RID" "$TITLE"
fi
```

Substitute the five placeholders (`<annotations_dir>`, `<state_dir>`, `<sid>`, `<response_id>`, `<short title>`) with the values you have from the session-create response and `meta.json`.

Call `Monitor` with `persistent: true`, the script above as `command`, and a short `description` like `"annotate-wait sid=<sid>"`.

After arming the watcher, **also append a record to the pending registry** so you can find this session later if the user cancels from terminal:

```bash
mkdir -p ~/.claude/annotate
REG="$HOME/.claude/annotate/pending-${CLAUDE_CODE_SESSION_ID}.json"
python3 - "$REG" "$SID" "$RID" "$TITLE" "$STATE_DIR" "$ANN_DIR" <<'PY'
import json, os, sys
path, sid, rid, title, state_dir, ann_dir = sys.argv[1:]
try:
    data = json.load(open(path))
except FileNotFoundError:
    data = []
data.append({"sid": sid, "rid": rid, "title": title,
             "state_dir": state_dir, "annotations_dir": ann_dir})
tmp = path + ".tmp"
json.dump(data, open(tmp, "w"), indent=2)
os.replace(tmp, path)
PY
```

The registry persists across watchers within a single Claude Code session. It is *not* shared across sessions (keyed by `CLAUDE_CODE_SESSION_ID`).

## Mode D — handling a watcher event

When you receive a task-notification whose stdout starts with `ANNOTATE_SUBMITTED` or `ANNOTATE_CANCELLED`, you have woken from a previously-armed watcher.

1. Parse the banner line to recover `sid`, `rid`, `title`.
2. If `ANNOTATE_SUBMITTED`:
   - Read the rest of the stdout: a `---routing---` block (the rule for whether your reply pushes back to browser), then `---annotations---` followed by the raw JSON.
   - Follow the existing "How to read annotations" steps to apply each annotation.
   - When composing your reply, apply the routing rule the watcher carried — if long-form, loop back through "How to push a response" with a fresh `response_id`; if short, respond in terminal.
3. If `ANNOTATE_CANCELLED`:
   - Acknowledge briefly in terminal: *"Annotate round for `<title>` cancelled."* — no more, no less.
   - Do not re-engage the annotate flow unless the user asks.
4. In both cases, **remove this session's entry from `~/.claude/annotate/pending-${CLAUDE_CODE_SESSION_ID}.json`** (the watcher is no longer pending). If the file is now empty, leave it as `[]` — don't delete it.

## Terminal cancellation

If the user says "scrap it" / "respond in terminal" / "stop annotating" / equivalent *while a watcher is armed* (the pending registry has entries):

1. Read `~/.claude/annotate/pending-${CLAUDE_CODE_SESSION_ID}.json`.
2. For each entry, write a `cancelled` marker into the entry's `state_dir`:
   ```bash
   printf '{"reason":"user-cancelled-terminal"}' > "$STATE_DIR/cancelled"
   ```
   The server's existing `_terminal_state` check only tests existence, so the body is optional but useful for debugging.
3. The watcher loops detect the marker on their next 1-second tick and exit with `ANNOTATE_CANCELLED`. You'll get task-notifications for each.
4. Handle each cancellation per Mode D and clean up the registry as that step instructs.
5. Continue with whatever the user actually wanted.

## How to read annotations

On your next turn, **before** any other action:

1. The annotations JSON is delivered as the body of the watcher's task-notification (see Mode D). The watcher reads `<annotations_dir>/annotations.json` once and emits it — that's the source of truth. If the notification body looks incomplete or you need to re-read it, the file is still on disk.
   - File exists → read it if the notification body looks truncated.
   - File doesn't exist → the watcher reported `ANNOTATE_CANCELLED` instead; see Mode D step 3.

2. Verify `response_id` matches the response you pushed. If mismatch, re-push and warn the user.

3. **Implicit approval:** any block not present in the `annotations` array is approved by the user. Acknowledge briefly only if you have something to add; otherwise move on.

4. For each annotation in the array:
   - `block_id` = index of the block in your prior `response.md` (b-0 is the first block). May be `null` for a **general comment** — feedback about the response as a whole, not tied to a specific block. Treat null-block comments as overall direction (tone, scope, missing context, etc.).
   - `type` = one of `comment`, `reject`. Drives how you respond:
     - `reject` — user disagrees with the highlighted point. Engage and either justify, revise, or back off.
     - `comment` — free-text feedback; parse intent yourself.
   - `selected_text` = literal text the user highlighted, or absent if the annotation is block-scoped (the user clicked a hover-action button without highlighting first).
   - `comment` = free-text feedback. May be empty for `reject`.
   - Optional `prefix` and `suffix` (~20 chars each) disambiguate when the same `selected_text` appears multiple times in the block.
   - `images` — optional array of `{token, path}` objects. When present and non-empty, `Read` each `path` before composing your reply so you can see the screenshots the user pasted. The `![paste-N]` markers inside `comment` show where in the user's text each image belongs; treat them as inline references when interpreting the comment. The path is on the local filesystem (under the session's `state_dir/images/`) — the standard `Read` tool ingests it directly as an image.

5. Address each annotation in your next response. If the new response is also long-form, loop back through the browser — see "Continuing the annotation loop" below.

## Continuing the annotation loop

The annotation flow is iterative: the user submits, you respond, and if your reply is itself substantive the user should be able to annotate that too. The watcher's stdout banner carries a one-line summary of the rule; this section is the canonical, full version.

When the user submits annotations (or submits with zero annotations as implicit approval), evaluate the reply you are about to write:

- **Long-form** — addresses 2+ annotations, contains a plan or multi-paragraph analysis, or lists separable points → loop back to "How to push" with a fresh `response_id`. The annotation surface stays consistent across iterations.
- **Short** — 1-line acknowledgement, single-fact answer, status update, tool-result narration → respond in terminal.
- **When in doubt** — route through the browser. The cost of an unneeded session is small; the cost of dumping a wall of text into terminal mid-conversation is annoying.

The rule applies regardless of how the original session was opened (forward or postmortem mode) and regardless of whether the user submitted any annotations — what matters is the shape of your reply, not the shape of their feedback.

If the user explicitly says "respond in terminal" or otherwise opts out, follow user instructions and skip routing for the rest of the session.

## Edge cases

- **Empty annotations array** — user submitted with no comments. Treat as approval; proceed.
- **`selected_text: ""`** — comment refers to the entire block; treat the block as the anchor.
- **You're producing v2 of a response** — write a new `response.md` with a fresh `response_id`. Don't migrate previous annotations forward.
- **Server unreachable** — see "verify the server is alive". Restart and continue.
- **Malformed `annotations.json`** — fall back to "no parseable annotations"; continue with terminal text.
- **`cancelled` marker present** — the user clicked Cancel (or you wrote the marker yourself via "Terminal cancellation"). The watcher exits with `ANNOTATE_CANCELLED`; see Mode D step 3.

## Token budget

A typical 5-paragraph response renders to ~300–600 tokens of markdown. The annotations.json reply is typically 50–300 tokens. Net cost is comparable to writing the response in-terminal with materially better UX for any non-trivial output.
