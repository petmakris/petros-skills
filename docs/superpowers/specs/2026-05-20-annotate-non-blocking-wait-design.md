# annotate: non-blocking wait for browser submission

## Problem

Today, when Claude pushes a response into the annotate browser view, the Stop hook (`hooks/annotate-wait.py`) blocks the turn from ending — it polls `annotations.json` for up to 2 hours. That locks the main Claude Code session: the user cannot ask Claude anything else while the browser tab is open. They have to submit or cancel before they can interact again.

This is wrong for the obvious reason: the annotate browser tab is a *side channel*. The main conversation should stay free. The user might want to ask Claude something quick mid-review, or hand Claude a follow-up task, or just walk away and come back later.

## Goal

Decouple the wait. After Claude pushes a response, the turn ends immediately. A background watcher detects the submit/cancel and delivers the payload back to Claude as its own conversational event — Claude's next turn has the annotations in context and responds.

The user, meanwhile, can keep typing.

## Key insight (verified by probe)

Claude Code's harness wakes an *idle* session on background-task completion. A background watcher that exits when `annotations.json` appears triggers a fresh Claude turn whose only input is the task-completion notification. That turn carries the watcher's stdout, and Claude reads the annotations from it.

This was confirmed empirically: a 15-second `Bash run_in_background` slept the full duration with no user input, and a task-notification arrived as a top-level system reminder at the 15-second mark.

## Primitive

`Monitor` with `persistent: true`. The watcher script polls every second for either marker file and exits as soon as one appears. Persistent mode is session-length, which is what we need — annotations can sit for hours. Bash `run_in_background` caps at 10 minutes and is therefore unsuitable.

The watcher's `stdout` lines arrive as conversational events. Because the script polls then exits, we get exactly one event per watcher.

## Architecture

### Per response push

1. Claude pushes response (writes `meta.json` then `response.md`).
2. Claude announces the URL in terminal as today.
3. **NEW:** Claude starts a `Monitor` keyed to that session's `annotations_dir` and `state_dir`. Persistent. The command is a small shell loop (see below).
4. Claude ends the turn.

### The watcher script

```bash
ANN="<annotations_dir>/annotations.json"
CANCELLED="<state_dir>/cancelled"
SID="<sid>"
RID="<response_id>"
TITLE="<short title>"

while [ ! -f "$ANN" ] && [ ! -f "$CANCELLED" ]; do
  sleep 1
done

# Single line per event; Monitor emits it as one notification.
if [ -f "$ANN" ]; then
  printf 'ANNOTATE_SUBMITTED sid=%s rid=%s title=%q\n' "$SID" "$RID" "$TITLE"
  # Routing reminder travels with the payload. Without it Claude's next
  # turn loses the "if your reply is long-form, push back to browser" rule.
  printf '%s\n' '---routing---'
  printf '%s\n' 'If your reply addresses 2+ annotations, contains a plan, or lists separable points, push it back through the annotate browser by re-invoking the skill. If it is a short acknowledgement or single-fact answer, respond in terminal.'
  printf '%s\n' '---annotations---'
  cat "$ANN"
else
  printf 'ANNOTATE_CANCELLED sid=%s rid=%s title=%q\n' "$SID" "$RID" "$TITLE"
fi
```

One line wouldn't be enough — Monitor batches stdout within 200ms into one notification, but a multi-line payload is fine. The structure (`---routing---` / `---annotations---`) is parseable but mostly there for human readability if anyone tails the output.

### Claude on receiving the event

The event arrives as a tool-result-like notification on a fresh Claude turn. Claude:

1. Parses the first line. Extracts `sid` / `rid` / `title`.
2. If `ANNOTATE_SUBMITTED`, reads the JSON block, applies the existing "How to read annotations" steps, addresses each annotation, follows the routing rule for whether the reply pushes back to browser.
3. If `ANNOTATE_CANCELLED`, acknowledges briefly in terminal and stops.

The skill body (`SKILL.md`) describes this handling so Claude knows what `ANNOTATE_SUBMITTED` and `ANNOTATE_CANCELLED` mean even after the skill body has rotated out of context. But there's a subtlety: the model has to *recognize* the markers as an annotate event in order to re-invoke the skill in the first place. That recognition has to come from the always-loaded skill description (the `description:` frontmatter), not from `SKILL.md` body, since the body isn't in context for the wake-up turn.

The skill description gains a third trigger path:

> (3) the model sees an `ANNOTATE_SUBMITTED` or `ANNOTATE_CANCELLED` line at the top of a task-notification system reminder — that's a watcher event from a previously-pushed response, and the skill must be re-invoked to parse the payload and respond.

Once the skill re-loads, the `SKILL.md` body's new "Mode D — handling watcher events" section drives parsing and follow-up.

### Cancellation from terminal

If the user types "scrap it" / "respond in terminal" / equivalent while the watcher is armed, Claude:

1. Writes the `cancelled` marker to `<state_dir>/cancelled` for each pending session.
2. The watcher detects it on the next poll tick and exits with `ANNOTATE_CANCELLED`.
3. The browser tab transitions to its existing "round cancelled" closed page (the existing `/poll` endpoint already drives that transition).
4. Claude continues with whatever the user actually wanted.

We need a way for Claude to *know* what's pending. A small sidecar registry:

- File: `~/.claude/annotate/pending-<CLAUDE_CODE_SESSION_ID>.json`
- Format: `[{ "sid": "...", "state_dir": "...", "rid": "...", "title": "..." }, ...]`
- Claude appends to it when starting a watcher, removes the entry when the corresponding watcher event lands.

This sidecar is what Claude consults when it sees a cancellation cue from the user.

### Concurrent pushes

If Claude pushes response A, then later in the same session pushes B before A is submitted, there are two Monitors armed. They are independent. Whichever submits first wakes Claude with its `sid` — Claude routes the reply to that response (writes a new `response.md` under that `sid` to push v2, or continues the conversation as appropriate). The `sid` in the watcher banner is what disambiguates.

### Stop hook

`hooks/annotate-wait.py` is deleted along with `hooks/test_annotate_wait.py`. Its job is fully replaced by the Monitor-based mechanism. The hook entry in the plugin's `hooks/` registration also gets removed.

The routing-reminder logic that the hook used to inject is now in the watcher's stdout banner. The "format the annotations prettily" logic the hook had is no longer needed — Claude gets the raw JSON and renders it however it wants for its reply.

## Non-goals

- No new tools, no new server endpoints. The server is unchanged.
- No cross-Claude-Code-session coordination. Each Claude Code session has its own pending registry and its own watchers; they don't share.
- No retroactive watcher recovery. If Claude Code is killed mid-wait, the watcher dies with it (Monitor is session-scoped). The user just doesn't get their annotations delivered to *that* session — they can start a new one.
- No fancy routing of the cancellation back to the browser tab beyond what already happens (the user clicking Cancel in browser drops the marker; us writing the marker from Claude side is symmetric).

## Risks and edges

- **User submits in browser while Claude is mid-tool-call.** Claude finishes the tool call, then the next conversational unit is either the user's typed message (if any) or the task-notification. Ordering is determined by the harness. Worst case the annotations arrive on the turn *after* the user's interjection — still works, Claude addresses both.
- **User types a follow-up while a watcher is armed AND clicks Submit.** Two events queue. Claude sees both on its next turn(s). Acceptable.
- **Watcher script error (e.g., directory deleted).** The script exits non-zero. Monitor reports exit code. Claude sees an empty annotations event and treats it as cancellation.
- **Server shut down mid-wait.** No effect on the watcher — it's polling local files, not the server. The user can't submit via browser anymore (server gone), but if they cancelled before the shutdown that still works. Otherwise the watcher hangs until Claude Code session ends or the user types a cancellation.
- **Sidecar registry stale across sessions.** A previous Claude Code session may have left entries. We key the file by `CLAUDE_CODE_SESSION_ID` so two simultaneous sessions don't collide; we also clear the registry on first push of a new session.
- **Routing reminder duplication.** Today the hook prepends the rule on every submission. The Monitor banner now does that too, with the same wording. No duplication issue — the hook is being removed.

## What changes in code

| File | Change |
|---|---|
| `skills/annotate/SKILL.md` (frontmatter) | Extend the `description:` field with a third trigger path: recognize `ANNOTATE_SUBMITTED` / `ANNOTATE_CANCELLED` markers in task-notifications and re-invoke the skill. |
| `skills/annotate/SKILL.md` (body) | Replace the "Stop hook will block here" section with "start a Monitor watcher" instructions, including the exact shell script template. Add a "Mode D — handling watcher events" section explaining how to parse the marker, the routing reminder, and the JSON payload. Add cancellation-from-terminal instructions. |
| `hooks/annotate-wait.py` | **Delete.** |
| `hooks/test_annotate_wait.py` | **Delete.** |
| Plugin hook registration (wherever `annotate-wait.py` is listed) | Remove the hook entry. |
| `~/.claude/annotate/` | New file `pending-<claude_session_id>.json` written by Claude on push, cleaned on event. Not committed (runtime state). |

No changes to `skills/annotate/server.py`, no changes to `static/script.js` / `static/style.css`. This is purely about the wait mechanism, not the browser UI.

## Acceptance

- After pushing a response, Claude's turn ends. The terminal accepts user input immediately.
- User can carry on an unrelated conversation; Claude responds to each turn normally.
- User clicks Submit in browser. Within ~1 second, Claude's chat shows a new turn whose context contains the annotations payload. Claude addresses each annotation.
- User instead clicks Cancel in browser. Same flow, but with `ANNOTATE_CANCELLED`; Claude moves on without re-engaging.
- User types "scrap it" mid-wait. Claude drops the `cancelled` marker; within ~1 second the watcher fires with `ANNOTATE_CANCELLED`; Claude acknowledges and continues.
- `hooks/annotate-wait.py` no longer present; no Stop hook lingers waiting on annotate sessions.
