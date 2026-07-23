---
name: walkthrough
description: Answer a codebase question as an ordered sequence of anchored steps walked in IntelliJ, not as terminal prose. Claude generates the steps, the IDE plugin walks the user through them, and the user can ask a question on any step which Claude answers in place. Triggered by /walkthrough <question>. Watcher events are WEBCOMPANION_EVENT / WEBCOMPANION_FINISHED / WEBCOMPANION_CANCELLED.
allowed-tools:
  - Bash
  - Read
  - Write
  - Grep
  - Glob
  - Monitor
---

# /walkthrough — guided code tours in IntelliJ

Turn a question about a codebase into a path through it: 5–12 ordered steps, each
anchored to a real `file:line`, walked step-by-step in IntelliJ. The user steps
forward and backward, and can ask a question on any step; you answer into that
step in place.

Use this instead of answering in terminal prose whenever the honest answer is
"here is the path through the code". No code is modified — this is a tool for
*understanding*, and in v1 you never edit files as part of a tour.

## Invocation

```
/walkthrough <question>
/walkthrough --diff <question>
/walkthrough --diff <ref>..HEAD <question>
```

- Plain form: a tour over existing code. Works for both "how does X work" and
  "how would I add X" — the difference shows up in where the last steps land.
- `--diff` form: a tour over a change that already exists (uncommitted working
  copy by default, or the given ref range). You narrate the change; you do not
  make it.

## On every invocation: ensure the server is running

Run this once at the top of every invocation, before anything else:

```bash
PLUGIN_ROOT="${CLAUDE_PLUGIN_ROOT:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/plugins/known_marketplaces.json")))["petros-skills"]["installLocation"])')}"
"$PLUGIN_ROOT/skills/walkthrough/ensure_server.sh"
```

`$CLAUDE_PLUGIN_ROOT` is **not** exported into the Bash tool's shell, so it is
resolved from the plugin marketplace registry as a fallback. Idempotent and fast
(<100 ms when already up). Do **not** use `run_in_background: true`. If it exits
non-zero, surface the stderr to the user and stop.

## Create a session

Read `$HOME/.claude/walkthrough/server.json` for the server URL, then create the
session. Do this **before** exploring — the session directory is where the steps
will be written, and creating it early means a slow exploration doesn't leave the
user staring at nothing.

Write the user's question to `~/.claude/walkthrough/.question.txt` using the `Write` tool
so it never passes through the shell (the directory already exists because
`ensure_server.sh` ran first and wrote `server.json` there). Then run:

```bash
SERVER_URL=$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/walkthrough/server.json")))["url"])')
BODY=$(CWD="$PWD" KIND="${KIND:-explain}" python3 -c '
import json, os
q = open(os.path.expanduser("~/.claude/walkthrough/.question.txt")).read().strip()
print(json.dumps({"cwd": os.environ["CWD"], "question": q, "kind": os.environ["KIND"],
                  "claude_session_id": os.environ.get("CLAUDE_CODE_SESSION_ID", "")}))
')
curl -sf --max-time 90 -X POST "$SERVER_URL/api/sessions" \
  -H 'Content-Type: application/json' \
  -d "$BODY"
```

`kind` is `explain` or `diff`. The response contains `sid`, `slug`, `url`,
`state_dir`, `events_dir`, `consumed_dir`, `title`. Save `sid`, `state_dir`,
`events_dir`, `consumed_dir` for the rest of this turn.

**One active tour per project.** If `/api/sessions?cwd=$PWD` already lists a
walkthrough session, cancel it first (`POST /s/<old_sid>/api/cancel`) so the
plugin switches cleanly instead of showing two tours. (Prior tours created by
*this Claude session* — any cwd — are superseded server-side automatically:
the create call above carries `claude_session_id`, and the server cancels
this session's other non-terminal walkthroughs before returning.)

## Generate the steps

Explore, then write the step list. For `--diff` tours, start from
`git diff` / `git diff <range>` and read the touched files around each hunk.

Write the document with the `Write` tool to `<state_dir>/.steps.draft.json`, then
validate and install it (never hand-write `steps.json` — validation is what keeps
a malformed tour out of the IDE):

```bash
PLUGIN_ROOT=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/walkthrough/server.json")))["plugin_root"])')
PYTHONPATH="$PLUGIN_ROOT" STATE_DIR="$STATE_DIR" python3 - <<'PY'
import json, os, time
from pathlib import Path
from skills.walkthrough.steps import write_steps
sd = Path(os.environ["STATE_DIR"])
doc = json.loads((sd / ".steps.draft.json").read_text())
doc["generated_ts"] = int(time.time())
write_steps(sd, doc)
print(f"wrote {len(doc['steps'])} steps")
PY
```

If it raises `ValueError`, the message lists every problem. Fix the draft and
re-run — do not create a second session.

Document shape:

```json
{
  "question": "<the user's question, verbatim>",
  "kind": "explain",
  "generated_ts": 0,
  "steps": [
    {"id": 1,
     "title": "Where sharing starts",
     "file": "src/main/java/com/montblanc/api/ProposalShareController.java",
     "line": 42,
     "snippet": "return shareService.share(id);",
     "role": "context",
     "markdown": "The REST entry point. Everything below hangs off this call."}
  ]
}
```

- `file` is **project-relative** (no leading `/`, no `..`).
- `snippet` is the **verbatim text of that line**, copied from the file you read.
  It is what re-anchors the step after the file shifts; a wrong snippet makes the
  step stale in the IDE.
- `role` is `context` (grey badge), `seam` (blue — where behaviour is extended),
  or `edit-site` (green — where new code goes).
- `id` values are positive integers, unique, in walking order.

## Generation contract

Hard rules. A tour that breaks one of these is a defect, not a style choice.

- **5–12 steps.** Fewer than 5 means the question deserved a paragraph in
  terminal — answer it there instead and do not create a tour. More than 12 means
  the question is too broad: ask the user to narrow it. If they decline, build the
  best 12-step spine and say what you left out.
- **Every step is a real anchor.** `file` + `line` + verbatim `snippet`. Never
  anchor to a file you did not `Read` in this turn. Never guess a line number.
- **Execution order, not file order.** Follow how control and data actually flow:
  entry point → gate → dispatch → implementation → data model → seam. Grouping
  steps by package is a failure mode.
- **Each step earns its place.** The markdown says *what happens here* and *why it
  matters for the question asked* — 2–5 sentences. It is not a file summary.
- **The last step answers the question.** For "how to add X", the final steps carry
  `role: "edit-site"` and name the exact file or directory for the new code, the
  registration point, and the test that would prove it. Concretely named — never
  "somewhere in the workflow package".
- **Link references inline.** `[evaluate](src/main/java/.../PreconditionRegistry.java:30)`
  for code, absolute URLs for tickets. The IDE renders these clickable.
- **Titles ≤ 6 words**, plain-text noun phrases — they are rail rows and HUD text.
- **Cross-block re-pass.** After drafting all steps, re-read them together and fix
  what only shows up in aggregate: a step repeating its neighbour, a jump with a
  missing bridge, a title that no longer matches its body, an ordering that only
  made sense while you were writing it. Do this **before** writing `steps.json` —
  steps are frozen once written.

## Tell the user where to walk

One sentence in terminal, then stop:

**"Walkthrough ready — <N> steps for `<question>`. Open the project in IntelliJ; step forward with the walkthrough shortcut and ask on any step."**

## Arm the watcher

After telling the user, start the watcher with `Monitor` (`persistent: true`):

```bash
PLUGIN_ROOT=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/walkthrough/server.json")))["plugin_root"])')
SKILL=walkthrough \
SID="<sid>" \
STATE_DIR="<state_dir>" \
EVENTS_DIR="<events_dir>" \
CONSUMED_DIR="<consumed_dir>" \
CLAUDE_SID="$CLAUDE_CODE_SESSION_ID" \
"$PLUGIN_ROOT/skills/_shared/web_companion/watcher.sh"
```

Banners: `WEBCOMPANION_EVENT skill=walkthrough sid=<sid> event_id=<id>`,
`WEBCOMPANION_FINISHED`, `WEBCOMPANION_CANCELLED`, and
`WEBCOMPANION_DROPPED skill=walkthrough sid=<sid> event_id=<id>` (the watcher
gave up re-emitting an event that was never acked). Each stdout line wakes you
once; the watcher stays alive across many events.

## Mode D — handling a watcher event

### `WEBCOMPANION_EVENT` (a question on a step)

1. **Parse the banner** for `sid` and `event_id`.
2. **Read the payload** between `---payload---` and `---end---`:
   - `anchor` — always `step:<id>`.
   - `type` — `"comment"` (or `"reject"` if the user disagrees with a prior reply).
   - `text` — the question.
   - `images` — `[{token, path}]`; `Read` each path before answering if non-empty.
3. **Compose the answer:**
   - Read `<state_dir>/steps.json` and locate the step by id — its `file`, `line`,
     and `markdown` are the subject of the question.
   - `Read` the anchored file around that line. Use `Grep`/`Glob` for anything the
     question pulls in beyond it.
   - Skim the other steps' threads (`ls <state_dir>/threads/`) as background. They
     are READ-ONLY input; never write into another step's thread.
   - 2–4 sentences, code-aware, markdown links inline, fenced code blocks for
     suggested snippets. **Do not modify code.**
4. **Append to that step's thread.** Route content through files so nothing is
   shell-quoted:

   a. `Write` the answer (raw markdown) to `<state_dir>/.reply.md`.

   b. `Write` `<state_dir>/.reply.meta.json`:
   ```json
   {"anchor": "step:3", "title": "<short headline>", "source_event_id": "<event_id>"}
   ```

   c. Run — appends the reply AND acks the event in one command:
   ```bash
   PLUGIN_ROOT=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.claude/walkthrough/server.json")))["plugin_root"])')
   PYTHONPATH="$PLUGIN_ROOT" STATE_DIR="$STATE_DIR" \
     python3 -m skills._shared.web_companion.reply_cli --ack "$EVENT_ID"
   ```
   It handles anchor→filename encoding and `source_event_id` dedup, and only
   writes the ack after the append succeeds — a crashed append re-emits.
5. **End your turn. No terminal output.** The watcher stays armed.

**Never rewrite `steps.json` in response to an event.** Steps are frozen. If the
answer really needs a different path through the code, say so in the reply and
offer to run a new `/walkthrough`.

### `WEBCOMPANION_FINISHED`

Terminal: *"Walkthrough for `<question>` closed."*

### `WEBCOMPANION_CANCELLED`

Terminal: *"Walkthrough for `<question>` cancelled."*

### `WEBCOMPANION_DROPPED`

An event went unanswered through every re-emit (an earlier wake-up was
interrupted or compacted away). Tell the user plainly: *"A walkthrough
question went unanswered and was dropped — please re-ask it on the step."*

## Response style guide

- **Self-contained synthesis.** Each reply answers *all* questions asked on that
  step so far. The IDE renders only your most recent reply; older ones are stored
  for audit but not displayed.
- **Short.** 2–4 sentences in most cases.
- **Code-aware.** Name the actual variables, methods, and lines.
- **Cite steps by number** when the answer lives elsewhere in the tour ("that's
  step 6").
- **Suggest, don't ask.** If a fix is warranted, show it as a code block. The user
  applies it.
- **Honest uncertainty.** Name exactly what you would need to know. Don't hedge.
- **Headline title.** Pass a `title` to `append_message`: plain text, ≤ 6 words,
  a noun phrase. Refresh it each answer.

## Terminal cancellation

If the user says "scrap it" / "stop the walkthrough" while a watcher is armed:

```bash
SERVER_URL=$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/walkthrough/server.json")))["url"])')
curl -sf --max-time 10 -X POST "$SERVER_URL/api/cancel_for_claude_session" \
  -H 'Content-Type: application/json' \
  -d "{\"claude_session_id\": \"$CLAUDE_CODE_SESSION_ID\"}"
```

The server writes the cancellation markers; each armed watcher emits
`WEBCOMPANION_CANCELLED` on its next tick — handle per Mode D.

## Edge cases

- **Question too broad** — would exceed 12 steps. Ask once for a narrower question;
  if refused, build the best 12-step spine and say what you dropped.
- **Zero anchors found** — nothing in the codebase matches. Do **not** write
  `steps.json`. Cancel the session, and in terminal say what you searched for and
  what you found instead.
- **`--diff` with an empty diff** — say so; do not create a session.
- **Validation failure** — `write_steps` lists every problem. Fix the draft, re-run.
  Never bypass validation by writing `steps.json` directly.
- **Server unreachable** — re-run `ensure_server.sh` (it restarts the server) and
  retry the failed request once.
- **Tour lost** — tours are ephemeral by design. If the session ends, say so
  plainly and offer to regenerate.
- **Malformed event payload** — no reply; `python3 -m skills._shared.web_companion.reply_cli --ack "$EVENT_ID" --ack-only` so the event
  isn't re-emitted forever.
- **Question with special characters** — the question is written to a file with the
  `Write` tool and never interpolated into a shell command or Python source, so
  quotes, backslashes, backticks, and `$(...)` are all safe. Do not "simplify" this
  into a shell-interpolated `printf`, `echo`, or assignment to `$QUESTION` — the
  file routing is what keeps the question safe.

## Token budget

Generation is the expensive part: read what you need to anchor steps honestly, and
stop. Each wake-up afterwards is one question on one step — answer that, 2–4
sentences, and end the turn.
