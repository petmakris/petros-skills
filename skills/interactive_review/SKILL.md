---
name: interactive-review
description: Render a GitHub PR diff in a browser with per-line threaded Q&A. User clicks any diff line, asks a question, Claude wakes via WEBCOMPANION_EVENT, appends a reply to the line's thread, and the page auto-refreshes that thread. Triggered by /interactive-review <PR>. Watcher events are WEBCOMPANION_EVENT / WEBCOMPANION_FINISHED / WEBCOMPANION_CANCELLED.
allowed-tools:
  - Bash
  - Read
  - Write
  - Grep
  - Glob
---

# /interactive-review — per-line threaded Q&A on a PR diff

Render a GitHub PR diff in the browser as an interactive page where the user clicks any changed line to open a threaded conversation on it. Claude answers in that thread; the page auto-refreshes the thread in place. No code is modified — this is a tool for *understanding* a PR, not rewriting it.

Use this when you want to walk through a PR line-by-line, ask questions about specific changes, or discuss a diff with a collaborator. The session is anchored to a diff snapshot taken at session-open. The conversation persists as a thread file per anchor so you can return to earlier questions.

If a fix is warranted, suggest it as a markdown code block inside the thread. Never modify the diff itself — code is immutable in this view.

## Invocation

The user types:

```
/interactive-review <PR>
```

where `<PR>` is one of:

- A number (`123`) — current repo's PR #123.
- A full URL (`https://github.com/org/repo/pull/123`).
- A local branch ref (`feature/foo`) — pre-PR review against `main`.

## On every invocation: ensure the server is running

The server is a long-lived singleton shared across all Claude Code sessions. Run this once at the top of every invocation, before anything else:

```bash
PLUGIN_ROOT="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}"
"$PLUGIN_ROOT/skills/interactive_review/ensure_server.sh"
```

`$CLAUDE_PLUGIN_ROOT` is **not** exported into the Bash tool's shell, so it is resolved here from the plugin marketplace registry as a fallback. Idempotent and fast (<100 ms when already up). Internally delegates to `skills/_shared/web_companion/ensure_server.sh` — do not call that directly. Do **not** use `run_in_background: true`. If it exits non-zero, surface the stderr to the user and stop.

## Create a session

After `ensure_server.sh` succeeds, read `$HOME/.claude/interactive-review/server.json` to get the server URL, then create a session:

```bash
SERVER_URL=$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/interactive-review/server.json")))["url"])')
curl -sf -X POST "$SERVER_URL/api/sessions" \
  -H 'Content-Type: application/json' \
  -d "$(printf '{"cwd": "%s", "pr": "%s"}' "$PWD" "$PR_REF")"
```

The server's `create_session_extra` runs `gh pr diff` and `gh pr view`, then writes `diff.patch`, `files.json`, and `meta.json` into the session's state directory. The response is:

```json
{
  "sid": "...",
  "url": "http://localhost:PORT/s/SID/",
  "response_dir": "...",
  "annotations_dir": "...",
  "state_dir": "...",
  "events_dir": "...",
  "consumed_dir": "...",
  "pr_ref": "...",
  "title": "..."
}
```

Save `url`, `sid`, `state_dir`, `events_dir`, `consumed_dir`, and `title` for the rest of this turn.

**gh failure:** if `create_session_extra` raises, the endpoint returns HTTP 500 with body `session-init failed: <stderr>`. Surface this verbatim in terminal: *"Couldn't fetch PR diff: `<error>`. Check `gh auth status` and try again."* Do not retry.

## Announce the URL

One sentence in terminal:

**"PR diff in browser → `<url>`. Click any line to ask a question; my answer appears as a threaded reply inline."**

## Arm the watcher

After announcing the URL, start the watcher with `Monitor` (`persistent: true`):

```bash
PLUGIN_ROOT=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/interactive-review/server.json")))["plugin_root"])')
SKILL=interactive-review \
SID="<sid>" \
STATE_DIR="<state_dir>" \
EVENTS_DIR="<events_dir>" \
CONSUMED_DIR="<consumed_dir>" \
"$PLUGIN_ROOT/skills/_shared/web_companion/watcher.sh"
```

Substitute values from the session-create response. Pass as the `Monitor` tool's `command` with `persistent: true` and a description like `"interactive-review-wait sid=<sid>"`.

The watcher emits these stdout banners:

- **`WEBCOMPANION_EVENT skill=interactive-review sid=<sid> event_id=<id>`** — one per submitted question. Followed by `---payload---`, the event JSON, and `---end---`.
- **`WEBCOMPANION_FINISHED skill=interactive-review sid=<sid>`** — when the user clicks Done.
- **`WEBCOMPANION_CANCELLED skill=interactive-review sid=<sid>`** — when the user cancels.

Each stdout line wakes you once. The watcher stays alive across many events until the session terminates.

After arming, append a record to the pending registry:

```bash
mkdir -p ~/.claude/interactive-review
REG="$HOME/.claude/interactive-review/pending-${CLAUDE_CODE_SESSION_ID}.json"
python3 - "$REG" "$SID" "$TITLE" "$STATE_DIR" "$EVENTS_DIR" "$CONSUMED_DIR" <<'PY'
import json, os, sys
path, sid, title, state_dir, events_dir, consumed_dir = sys.argv[1:]
try:
    data = json.load(open(path))
except FileNotFoundError:
    data = []
data.append({"sid": sid, "title": title,
             "state_dir": state_dir, "events_dir": events_dir,
             "consumed_dir": consumed_dir})
tmp = path + ".tmp"
json.dump(data, open(tmp, "w"), indent=2)
os.replace(tmp, path)
PY
```

The registry is keyed by `CLAUDE_CODE_SESSION_ID` — not shared across sessions.

## Mode D — handling a watcher event

You wake here when a task-notification arrives whose first stdout line is one of the `WEBCOMPANION_*` banners.

### `WEBCOMPANION_EVENT` (per-question submission)

1. **Parse the banner:** extract `sid`, `event_id`.
2. **Read the payload** between `---payload---` and `---end---`. Fields:
   - `anchor` — `<path>:<L|R>:<line>` or `<path>:<L|R>:<start>-<end>`, or `__general__` for a whole-PR comment.
   - `type` — `"comment"` (or rarely `"reject"` if the user disagrees with a prior reply).
   - `text` — the user's question.
   - `selected_text` — highlighted text (usually empty for diff review).
   - `images` — `[{token, path}]`. `Read` each `path` before composing your answer if non-empty.
3. **Compose an answer:**
   - Read `<state_dir>/diff.patch`. For specific-line anchors, narrow to the relevant hunk. For `__general__`, scan the whole diff.
   - **Read other open threads as background context.** `ls <state_dir>/threads/` — each file is one anchor's prior conversation. Skim them. A question the user asked on `Foo.java:42` may sharpen what you say about `Bar.java:113`, and vice versa. These threads are READ-ONLY input — they inform your synthesis on the active anchor; never write into another anchor's thread.
   - Use `Read`, `Grep`, `Glob` to pull in surrounding source context if the diff alone isn't enough.
   - Write a short, code-aware answer in markdown: 2-4 sentences typically. Fenced code blocks for snippet suggestions.
   - If you spot a real bug, flag it and suggest a fix as a code block. **Do not modify the diff.**
   - Avoid hedging. If you genuinely need more context, say so concretely ("I'd need to see how `foo()` is called elsewhere") — don't ramble.
4. **Append the message to the thread.** Write ONLY to the active anchor's thread (the one in the event payload). Thread isolation is load-bearing: never mutate any other anchor's file in response to this event. Use the Python helper to handle encoding and dedup atomically:
   ```bash
   PLUGIN_ROOT=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/interactive-review/server.json")))["plugin_root"])')
   PYTHONPATH="$PLUGIN_ROOT" python3 -c "
   from skills.interactive_review.threads import append_message
   from pathlib import Path
   append_message(Path('$STATE_DIR/threads'), '$ANCHOR', {
       'role': 'claude',
       'ts': $(date +%s),
       'text': '''<your answer>''',
       'source_event_id': '$EVENT_ID',
   })
   "
   ```
   The anchor encoding maps `path/to/file:R:42` → `path__to__file_R_42.json`. `append_message` handles this; you don't need to compute it manually.
5. **Write the ack:** `<consumed_dir>/<event_id>.ack` (empty file — existence is the signal).
6. **End your turn. No terminal output.** The watcher stays armed.

### `WEBCOMPANION_FINISHED`

The user clicked Done.

1. Ack in terminal: *"Review session for `<title>` closed."*
2. Remove this session's entry from `~/.claude/interactive-review/pending-${CLAUDE_CODE_SESSION_ID}.json`.

### `WEBCOMPANION_CANCELLED`

The user cancelled (clicked tab close or typed `scrap it` in terminal).

1. Ack in terminal: *"Review session for `<title>` cancelled."*
2. Remove this session's entry from the pending registry.

## Response style guide

- **Self-contained synthesis.** Each reply should stand on its own as the
  answer to *all* questions asked so far on this anchor, not just the
  latest one. Absorb prior questions; do not assume the reader has scrolled
  back. The IDE surface renders only your most recent reply — older replies
  are stored for audit but not displayed.
- **Link references inline.** When you reference a specific file, method,
  or symbol from the code, render it as a markdown link whose target is
  the project-relative file path optionally followed by `:line`, e.g.
  `[forDashboard](src/main/java/.../ProposalListService.java:18)`. For
  ticket IDs and external URLs, use a normal markdown link with the
  absolute URL.
- **Short.** 2–4 sentences in most cases. Answer the question; don't
  review the whole PR.
- **Code-aware.** Reference specific lines, variable names, and functions
  from the diff.
- **Suggest, don't ask.** When a fix is warranted, show it as a markdown
  code block immediately. The user copies it themselves.
- **Honest uncertainty.** If you need more context, name exactly what you
  need ("I'd need to see `<file>:<function>` to know"). Don't hedge.
- **No general reviews per event.** Each wake-up is one question on one
  anchor. Answer that; let the user iterate.

## Re-apply safety

`threads.append_message` dedups by `source_event_id`. If the watcher restarts and re-emits an event you've already handled, the second call is a no-op. Process the event normally each time — storage handles dedup.

## Terminal cancellation

If the user says "scrap it" / "stop the review" / equivalent while a watcher is armed:

1. Read `~/.claude/interactive-review/pending-${CLAUDE_CODE_SESSION_ID}.json`.
2. For each entry, write the cancellation marker:
   ```bash
   printf '{"reason":"user-cancelled-terminal"}' > "$STATE_DIR/cancelled"
   ```
3. The watcher detects the marker on its next tick and emits `WEBCOMPANION_CANCELLED`.
4. Handle each cancellation per Mode D and clean up the registry.
5. Continue with whatever the user actually wanted.

## Edge cases

- **gh failure** — session creation fails with a descriptive error. Surface verbatim; don't retry.
- **Empty PR (no diff)** — `files.json` is `[]`; the page shows "No changes". The general-comments pane still works.
- **PR updated mid-session** — the diff is snapshotted at session-open. If the PR head changes mid-session, anchors may no longer match current head. A banner appears in the page. Recommend the user restart the session.
- **Very large PR** — soft warning above 1 MB of diff; hard reject above 5 MB. The user can request narrower review by passing a branch or a more focused PR.
- **Malformed event payload** — fall back to no-op; write the `.ack` anyway so the event isn't re-emitted forever.
- **Server unreachable** — re-run `ensure_server.sh`; it will restart the server. Retry the failed request once.

## Token budget

Each event wake-up is a single question on a single anchor. Answer specifically what was asked. 2-4 sentences is right for most questions; expand only when code context genuinely requires it. The user iterates by asking more questions — don't try to anticipate them.
