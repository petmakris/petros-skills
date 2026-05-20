# Annotate Non-Blocking Wait Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop blocking Claude's turn on annotate browser submission. Replace the Stop hook with a `Monitor persistent:true` watcher whose stdout becomes a fresh Claude turn when annotations land.

**Architecture:** When Claude pushes a response, it also starts a `Monitor` running a small shell loop that polls for `annotations.json` or the `cancelled` marker and exits as soon as one appears. The model goes idle. The user can chat freely. When the loop exits, the harness delivers its stdout as a task-notification and Claude wakes with the annotations in context. A frontmatter trigger phrase in `SKILL.md` ensures Claude re-invokes the skill on wake. A small sidecar JSON registry at `~/.claude/annotate/pending-<claude_session_id>.json` records pending watchers so Claude can drop a `cancelled` marker if the user cancels from terminal.

**Tech Stack:** Vanilla `Monitor` tool (no new code), shell, Python (deletions only), Markdown.

**Spec:** `docs/superpowers/specs/2026-05-20-annotate-non-blocking-wait-design.md`

---

## File map

- **Delete** `hooks/annotate-wait.py` (Stop hook, ~150 lines).
- **Delete** `hooks/test_annotate_wait.py` (18 tests, all targeting the deleted hook).
- **Modify** `hooks/hooks.json` — remove the `Stop` registration block; keep `UserPromptSubmit`.
- **Modify** `skills/annotate/SKILL.md`:
  - Frontmatter `description` — add the third trigger (recognize `ANNOTATE_SUBMITTED` / `ANNOTATE_CANCELLED` markers).
  - Body — replace every "Stop hook will block" / "Stop hook injects" / etc. mention with the new mechanism. Add a "Mode D — watcher events" section. Add cancellation-from-terminal protocol. Add the watcher-script template Claude is supposed to spawn.
- **Modify** `README.md` — replace the two mentions of `annotate-wait` with a one-line description of the new Monitor-based mechanism.

No new files in tracked state. The sidecar registry under `~/.claude/annotate/` is runtime state, not committed.

---

## Task 1: Delete the Stop hook and its tests

**Files:**
- Delete: `hooks/annotate-wait.py`
- Delete: `hooks/test_annotate_wait.py`

No tests to write — the deletion is verified by absence.

- [ ] **Step 1: Delete the two files**

```bash
cd /Users/petros.makris/projects/petros-skills
git rm hooks/annotate-wait.py hooks/test_annotate_wait.py
```

- [ ] **Step 2: Confirm nothing else imports them**

Run: `grep -rn "annotate_wait\|annotate-wait" --include="*.py" --include="*.sh" --include="*.json" .`

Expected: zero hits in `*.py`, `*.sh`. Hits in `*.json` (specifically `hooks/hooks.json`) and `*.md` are addressed in later tasks. Hits in `docs/superpowers/{specs,plans}/` are historical and are *not* fixed by this plan.

If any unexpected import shows up (e.g. another hook importing from `annotate-wait`), STOP and report BLOCKED.

- [ ] **Step 3: Commit**

```bash
git commit -m "annotate: delete blocking Stop hook (replaced by Monitor watcher)"
```

---

## Task 2: Remove the Stop hook registration

**Files:**
- Modify: `hooks/hooks.json`

- [ ] **Step 1: Replace file contents**

The current file registers two hooks: `UserPromptSubmit` (auto-refine.sh) and `Stop` (annotate-wait.py). Keep the first, drop the second, and rewrite the top-level `description` so it no longer describes the deleted hook.

New contents of `hooks/hooks.json`:

```json
{
  "description": "Hook bundle. auto-refine.sh on UserPromptSubmit — injects a system reminder telling Claude to evaluate every prompt against the refine-prompt skill's messiness heuristic, run the skill on messy prompts, skip on clean/short/mid-flow prompts, and treat the refined prompt as canonical after `go`.",
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "bash \"${CLAUDE_PLUGIN_ROOT}/hooks/auto-refine.sh\""
          }
        ]
      }
    ]
  }
}
```

- [ ] **Step 2: Validate JSON parses**

Run: `python3 -c 'import json; json.load(open("hooks/hooks.json"))' && echo OK`
Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add hooks/hooks.json
git commit -m "annotate: drop Stop hook registration"
```

---

## Task 3: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Replace line 8**

The current line 8 lists the hook in the top-level `hooks/` description:

```
- `hooks/` — plugin hooks: `dump-md.sh` (UserPromptSubmit) writes each turn as a clean markdown file; `annotate-wait.py` (Stop) blocks until the browser annotations come back when the `/annotate` skill is mid-flight.
```

Replace with:

```
- `hooks/` — plugin hooks: `auto-refine.sh` (UserPromptSubmit) injects the refine-prompt evaluation reminder for every user message. The annotate skill's browser-submission wait is now handled by an in-session `Monitor` watcher (no blocking Stop hook).
```

Note: I'm also swapping the stale `dump-md.sh` mention for `auto-refine.sh` to match the current `hooks.json`. The `dump-md.sh` reference was already wrong before this plan.

- [ ] **Step 2: Replace the dedicated annotate-wait paragraph around line 63**

Find the block (around line 63) that reads:

```
- **`annotate-wait`** (`hooks/annotate-wait.py`) — Stop hook. When the `/annotate` skill has just pushed a response to the browser, blocks for up to 30 minutes waiting for the user to submit annotations, then injects them back to Claude as a system reminder so Claude resumes automatically. Bails in milliseconds when no annotate session is mid-flight.
```

Delete the entire bullet. The new mechanism lives inside the skill body, not as a hook, so it doesn't belong in the README's "Hooks" section.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "annotate: README — drop annotate-wait, mention Monitor-based wait"
```

---

## Task 4: Update `SKILL.md` frontmatter `description`

**Files:**
- Modify: `skills/annotate/SKILL.md`

- [ ] **Step 1: Extend the description**

The current frontmatter description ends after the postmortem-mode sentence. Add a third trigger path inline (keep the existing two intact). The new full description (one paragraph, frontmatter-style):

```yaml
description: Render Claude responses as an interactive web page with span-based annotation. Three trigger paths — (1) auto: Claude routes its current response through the view when it contains 2+ distinct things the user might want to react to (plans, analyses, multi-paragraph answers, lists of findings); (2) postmortem: user manually invokes the skill ("annotate", "annotate that", "/annotate") after a big response has already landed, and the skill pushes the most recent prior assistant message through the same pipeline; (3) watcher event: a task-notification arrives whose first stdout line starts with `ANNOTATE_SUBMITTED` or `ANNOTATE_CANCELLED` — that's a previously-pushed response's watcher reporting in, and the skill must be re-invoked to parse the payload and respond. In all cases the user reads in the browser, highlights any text, leaves free-text comments, and submits — Claude reads structured annotations on the next turn.
```

Important: the frontmatter `description:` is the part that's always loaded into context — without the third trigger phrase explicitly named here, the model will not know to re-invoke the skill when a watcher event arrives.

- [ ] **Step 2: Commit (will be combined with Task 5's body changes — skip the commit here; just stage)**

Don't commit yet. The body changes in Task 5 belong with this frontmatter change in a single commit.

---

## Task 5: Rewrite the wait/handle sections of `SKILL.md` body

**Files:**
- Modify: `skills/annotate/SKILL.md` (body)

There are five separate edits in this task. Apply them in order.

### Edit 5a — Mode B last paragraph (line ~50)

The current text:

```
5. From here on, follow the exact same flow as forward mode: `ensure_server.sh` → POST `/api/sessions` → write `meta.json` then `response.md` → announce the URL → end your turn. The Stop hook waits for submission identically.
```

Replace with:

```
5. From here on, follow the exact same flow as forward mode: `ensure_server.sh` → POST `/api/sessions` → write `meta.json` then `response.md` → announce the URL → **start the watcher** (see "Arming the watcher" below) → end your turn.
```

### Edit 5b — meta.json paragraph (line ~98)

The current text mentions the Stop hook in the context of `claude_session_id`:

```
2. Write `meta.json` first: `{"response_id": "resp-<timestamp>", "title": "<short title>", "claude_session_id": "$CLAUDE_CODE_SESSION_ID"}`. The `claude_session_id` field is **required** — read it from the `CLAUDE_CODE_SESSION_ID` env var (exposed to all Bash tool calls). Without it, the Stop hook can't tell which Claude Code session created this dir and refuses to wait on it; without that filtering, two Claude Code instances running in the same cwd would cross-receive each other's annotations.
```

Replace with:

```
2. Write `meta.json` first: `{"response_id": "resp-<timestamp>", "title": "<short title>", "claude_session_id": "$CLAUDE_CODE_SESSION_ID"}`. Read `claude_session_id` from the `CLAUDE_CODE_SESSION_ID` env var (exposed to all Bash tool calls). The field is no longer load-bearing for hook filtering (the hook is gone), but the renderer still surfaces it and the value remains useful for human auditing of session dirs.
```

### Edit 5c — "How to push" Steps 4–5 (line ~100–106)

The current text ends "How to push a response" with:

```
4. Tell the user, in one short sentence: **"Response in browser → `<url>`. Submit when ready."**
5. End your turn. Do not produce additional content.

The plugin's Stop hook (`hooks/annotate-wait.py`) will block here, polling for
`annotations.json` up to 2 hours. When the user clicks Submit, the hook
injects the annotations payload as a system reminder so your next turn starts
with them already in context — no user typing required.
```

Replace with:

```
4. Tell the user, in one short sentence: **"Response in browser → `<url>`. Submit when ready."**
5. **Arm the watcher** (see "Arming the watcher" below). The Monitor runs in the background; your turn ends immediately. The user is free to chat. When they submit (or cancel) in the browser, you'll wake up with the annotations payload in your next turn — no user typing required.
6. End your turn. Do not produce additional content.
```

### Edit 5d — Insert "Arming the watcher" + "Handling a watcher event" + "Terminal cancellation" sections

Find the line immediately before the "How to read annotations" heading. Insert these three new sections directly before that heading:

````markdown
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

Substitute the four placeholders (`<annotations_dir>`, `<state_dir>`, `<sid>`, `<response_id>`, `<short title>`) with the values you have from the session-create response and `meta.json`.

Call `Monitor` with `persistent: true`, a 1-second poll interval baked into the script, and a short `description` like `"annotate-wait sid=<sid>"`.

After arming the watcher, **also append a record to the pending registry**:

```bash
mkdir -p ~/.claude/annotate
REG=~/.claude/annotate/pending-${CLAUDE_CODE_SESSION_ID}.json
# Read existing list (or empty array), append this entry, atomic-rewrite.
python3 - <<PY
import json, os
path = os.path.expanduser("$REG")
try:
    data = json.load(open(path))
except FileNotFoundError:
    data = []
data.append({
  "sid": "$SID",
  "rid": "$RID",
  "title": "$TITLE",
  "state_dir": "$STATE_DIR",
  "annotations_dir": "$ANN_DIR",
})
tmp = path + ".tmp"
json.dump(data, open(tmp, "w"), indent=2)
os.replace(tmp, path)
PY
```

This registry is what you consult if the user cancels via terminal (see "Terminal cancellation" below).

## Mode D — handling a watcher event

When you receive a task-notification whose stdout starts with `ANNOTATE_SUBMITTED` or `ANNOTATE_CANCELLED`, you have woken from a previously-armed watcher. Steps:

1. Parse the banner line to recover `sid`, `rid`, `title`.
2. If `ANNOTATE_SUBMITTED`:
   - Read the rest of the stdout: a `---routing---` block (the rule for whether your reply pushes back to browser), then `---annotations---` followed by the raw JSON.
   - Follow the existing "How to read annotations" steps to apply each annotation.
   - When composing your reply, apply the routing rule the watcher carried.
3. If `ANNOTATE_CANCELLED`:
   - Acknowledge briefly in terminal: *"Annotate round for `<title>` cancelled."* — no more, no less.
   - Do not re-engage the annotate flow unless the user asks.
4. In both cases, **remove this session's entry from `~/.claude/annotate/pending-<claude_session_id>.json`** (the watcher is no longer pending). If the file is now empty, leave it as `[]` — don't delete it.

## Terminal cancellation

If the user says "scrap it" / "respond in terminal" / "stop annotating" / equivalent *while a watcher is armed* (i.e. the pending registry has entries), do the following:

1. Read `~/.claude/annotate/pending-${CLAUDE_CODE_SESSION_ID}.json`.
2. For each entry, write a zero-byte file to `<state_dir>/cancelled` (or `printf '{"reason":"user-cancelled-terminal"}' > <state_dir>/cancelled` if you want a non-empty marker — both work; the server's existing `_terminal_state` check only tests existence).
3. The watcher loops detect the marker on their next 1-second tick and exit with `ANNOTATE_CANCELLED`. You'll get task-notifications for each.
4. Handle each cancellation per Mode D and clean up the registry.
5. Continue with whatever the user actually wanted.
````

### Edit 5e — Update "How to read annotations" Step 1 (line ~110)

Step 1 currently says:

```
1. If the Stop hook injected an `additionalContext` system reminder containing
   the annotations payload, use it directly. Otherwise fall back to reading
   `<annotations_dir>/annotations.json` from disk (it's the source of truth and
   the hook may have truncated very large payloads).
   - Exists → read it.
   - Doesn't exist → user didn't submit, and the hook either timed out (2 h)
     or the server went away. Treat the terminal message as implicit "looks
     good" and continue.
```

Replace with:

```
1. The annotations JSON is delivered as the body of the watcher's task-notification (see Mode D). The watcher reads `<annotations_dir>/annotations.json` once and emits it — that's the source of truth. If for some reason the payload was truncated or you need to re-read it, the file is still on disk.
   - File exists → read it if the notification body looks incomplete.
   - File doesn't exist → the watcher reported `ANNOTATE_CANCELLED` instead; see Mode D step 3.
```

### Edit 5f — Update "Continuing the annotation loop" intro (line ~139)

Current text:

```
The annotation flow is iterative: the user submits, you respond, and if your reply is itself substantive the user should be able to annotate that too. The Stop hook (`hooks/annotate-wait.py`) injects a routing reminder alongside the annotations payload — this section is the canonical version of the rule it carries.
```

Replace with:

```
The annotation flow is iterative: the user submits, you respond, and if your reply is itself substantive the user should be able to annotate that too. The watcher's stdout banner carries a one-line summary of the rule; this section is the canonical, full version.
```

### Edit 5g — Drop the `cancelled` edge case (line ~158)

Current edge case:

```
- **`cancelled` marker present** — the user clicked Cancel. The Stop hook exits silently; you get a normal next turn driven by whatever the user types. Don't try to re-engage the annotate flow unless the user asks.
```

Replace with:

```
- **`cancelled` marker present** — the user clicked Cancel (or you wrote the marker yourself via "Terminal cancellation"). The watcher exits with `ANNOTATE_CANCELLED`; see Mode D step 3.
```

### Step (after all edits): Commit

- [ ] **Step 1: Sanity-check the file**

Run: `python3 -c 'import yaml,sys; d=open("skills/annotate/SKILL.md").read(); print("frontmatter parses" if yaml.safe_load(d.split("---")[1]) else "MISSING")' 2>&1 || true`
Expected: `frontmatter parses` (or a "yaml not installed" error, which is fine — alternative below).

Fallback: just check the file opens and the frontmatter delimiters are intact:
Run: `head -10 skills/annotate/SKILL.md`
Expected: a `---` line at top, then `name:`, `description:`, `allowed-tools:`, then a closing `---`.

- [ ] **Step 2: Commit**

```bash
git add skills/annotate/SKILL.md
git commit -m "annotate: SKILL.md — Monitor-based wait, Mode D, terminal cancellation"
```

---

## Task 6: End-to-end manual smoke test

No automated coverage — the integration is between Claude (in this session), the harness's Monitor delivery, the file system, and the user's browser. Walk through this manually and check off each step.

- [ ] **Step 1: Confirm hook is gone**

Run: `ls hooks/ 2>&1`
Expected: no `annotate-wait.py`, no `test_annotate_wait.py`, just `auto-refine.sh`, `hooks.json`, and any other unrelated files.

Run: `python3 -c 'import json; print(list(json.load(open("hooks/hooks.json"))["hooks"].keys()))'`
Expected: `['UserPromptSubmit']` — no `Stop`.

- [ ] **Step 2: Push a test response with the new skill body**

In a fresh Claude Code session in this directory, ask Claude to push something annotatable. The skill should execute the new "Arming the watcher" sub-step and write the pending registry entry.

After the turn ends:
```
cat ~/.claude/annotate/pending-${CLAUDE_CODE_SESSION_ID}.json
```
Expected: a JSON array with one object: `sid`, `rid`, `title`, `state_dir`, `annotations_dir`.

- [ ] **Step 3: Confirm the turn really ends**

Type a small unrelated question in the same Claude Code session (e.g. "what time is it"). Expected: Claude answers without any prior delay — the terminal was not blocked.

- [ ] **Step 4: Submit annotations in the browser**

Open the URL from step 2. Annotate something. Click Submit.

Expected within ~1 second: a new turn appears in the Claude Code session whose top is a task-notification, and whose body Claude parses as `ANNOTATE_SUBMITTED`. Claude addresses the annotations per Mode D and the pending registry shrinks.

- [ ] **Step 5: Cancel from terminal**

Push another annotatable response. Confirm pending registry has one entry.

Then type something like "actually scrap that, respond in terminal" to Claude. Expected:
- Claude reads the registry.
- Claude writes a `cancelled` marker to the relevant `state_dir`.
- Within ~1 second a task-notification arrives with `ANNOTATE_CANCELLED`.
- Claude acknowledges briefly.
- Registry is empty.

- [ ] **Step 6: Cancel from browser**

Push a third response. Open the URL. Click Cancel (not Submit). Expected: same as Step 5 but the marker write happens server-side; everything else is identical.

---

## Spec self-review

- **Spec coverage:** Decoupling the wait (Tasks 1+2+4+5). Watcher script (Task 5d). Routing-reminder relocation (Task 5d). Pending registry (Task 5d, Task 6). Terminal cancellation (Task 5d "Terminal cancellation" subsection). Mode D for waking up (Task 5d). Frontmatter trigger (Task 4). Documentation updates (Task 3). All spec sections map to a task.
- **Placeholders:** none — each edit shows the exact before/after, and the watcher script + registry helper are concrete shell.
- **Type/name consistency:** `ANNOTATE_SUBMITTED` / `ANNOTATE_CANCELLED` markers — used consistently across the frontmatter description, the watcher script, Mode D, and the smoke test. The registry path uses `${CLAUDE_CODE_SESSION_ID}` everywhere.
- **Tests:** the deleted code had 18 unit tests; we lose all of them. We accept that loss because the new mechanism's correctness lives in the Monitor primitive and the SKILL.md protocol, neither of which is unit-testable from this repo. Task 6's manual smoke test is the safety net.
