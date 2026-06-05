# Auto-refine-prompt Hook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `UserPromptSubmit` hook (`auto-refine.sh`) that guarantees every user prompt is evaluated against the refine-prompt skill's messiness heuristic.

**Architecture:** One new bash hook script mirrors the existing `hooks/dump-md.sh` pattern: reads `UserPromptSubmit` payload on stdin, emits a JSON object whose `additionalContext` is a system reminder telling Claude to invoke the refine-prompt skill on messy prompts, skip on clean/short/mid-flow prompts, and treat the refined prompt as canonical after `go`. The refine-prompt skill itself is unchanged.

**Tech Stack:** bash, `jq`. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-14-auto-refine-prompt-hook-design.md`

---

## File Structure

| File | Responsibility | Type |
|---|---|---|
| `hooks/auto-refine.sh` | Reads UserPromptSubmit payload, emits `additionalContext` with the refine-prompt nudge. | NEW |
| `hooks/test_auto_refine.sh` | Bash test: pipes synthetic payloads in, asserts output shape. | NEW |
| `hooks/hooks.json` | Hook manifest. Add a second `UserPromptSubmit` entry. Update description to mention the new hook. | MODIFY |

Each file has one clear responsibility. `auto-refine.sh` is small (~60 lines including the inline reminder text). The test file mirrors the existing `hooks/test_annotate_wait.py` pattern but in bash since `auto-refine.sh` is bash.

---

## Task 1: Write the failing smoke test

**Files:**
- Create: `hooks/test_auto_refine.sh`

- [ ] **Step 1: Write the failing test**

Create `hooks/test_auto_refine.sh`:

```bash
#!/usr/bin/env bash
# Smoke test for hooks/auto-refine.sh.
# Validates JSON output shape on UserPromptSubmit and silent exit on other events.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$SCRIPT_DIR/auto-refine.sh"

if [ ! -x "$SCRIPT" ]; then
  echo "FAIL: $SCRIPT is not executable" >&2
  exit 1
fi

# --- Test 1: UserPromptSubmit event produces the expected JSON shape ---
OUTPUT=$(echo '{"hook_event_name":"UserPromptSubmit","cwd":"/tmp"}' | "$SCRIPT")

if ! echo "$OUTPUT" | jq -e '.hookSpecificOutput.hookEventName == "UserPromptSubmit"' > /dev/null; then
  echo "FAIL: hookEventName field missing or wrong" >&2
  echo "Output: $OUTPUT" >&2
  exit 1
fi

if ! echo "$OUTPUT" | jq -e '.hookSpecificOutput.additionalContext | length > 0' > /dev/null; then
  echo "FAIL: additionalContext is empty" >&2
  exit 1
fi

if ! echo "$OUTPUT" | jq -e '.hookSpecificOutput.additionalContext | contains("REFINE-PROMPT")' > /dev/null; then
  echo "FAIL: additionalContext does not mention REFINE-PROMPT" >&2
  exit 1
fi

if ! echo "$OUTPUT" | jq -e '.hookSpecificOutput.additionalContext | contains("messy")' > /dev/null; then
  echo "FAIL: additionalContext does not mention the messiness heuristic" >&2
  exit 1
fi

# --- Test 2: Non-matching event produces no output ---
OUTPUT=$(echo '{"hook_event_name":"Stop"}' | "$SCRIPT")
if [ -n "$OUTPUT" ]; then
  echo "FAIL: non-UserPromptSubmit event produced output: $OUTPUT" >&2
  exit 1
fi

# --- Test 3: Missing event field exits cleanly ---
OUTPUT=$(echo '{}' | "$SCRIPT")
if [ -n "$OUTPUT" ]; then
  echo "FAIL: empty payload produced output: $OUTPUT" >&2
  exit 1
fi

echo "All tests passed."
```

- [ ] **Step 2: Make the test executable**

```bash
chmod +x hooks/test_auto_refine.sh
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `bash hooks/test_auto_refine.sh`
Expected: FAIL with `hooks/auto-refine.sh is not executable` (the script doesn't exist yet).

- [ ] **Step 4: Commit the failing test**

```bash
git add hooks/test_auto_refine.sh
git commit -m "test: failing smoke test for auto-refine.sh hook"
```

---

## Task 2: Implement `auto-refine.sh`

**Files:**
- Create: `hooks/auto-refine.sh`

- [ ] **Step 1: Write the hook script**

Create `hooks/auto-refine.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

# UserPromptSubmit hook: auto-evaluate every user prompt against the
# refine-prompt skill's messiness heuristic.
#
# Reads the UserPromptSubmit payload on stdin and emits an
# additionalContext block telling Claude to run refine-prompt on messy
# prompts, skip on clean/short/mid-flow prompts, and treat the refined
# prompt as canonical after `go`. Pairs with hooks/dump-md.sh — both
# fire on UserPromptSubmit and inject independent context.
#
# Spec: docs/superpowers/specs/2026-05-14-auto-refine-prompt-hook-design.md

input="$(cat)"
event="$(printf '%s' "$input" | jq -r '.hook_event_name // empty')"

if [ "$event" != "UserPromptSubmit" ]; then
  exit 0
fi

REMINDER=$(cat <<'EOF'
REFINE-PROMPT AUTO-EVALUATION

Before doing anything else with the user's message for this turn:

1. Decide if the message is "messy" — looks like raw speech-to-text:
     • long without punctuation
     • filler words (um, uh, like, basically, you know)
     • word-level duplicates (the the, fix fix)
     • vague references with no antecedent (that thing, the one from before)
     • transcription near-misses for domain/identifier terms
     • imperative shape missing a verb or object
   A clean, well-formed request — even a long one — is NOT messy.

2. Skip refinement entirely if ANY of the following:
     • Message is clean prose (no signals above).
     • Message is a short confirmation/cancellation: yes, no, go, ok,
       stop, cancel, scrap it, or 1–2 word approval.
     • Message is a reply to an in-progress refine-prompt flow
       (a Phase A clarification answer, a Phase B edited refined prompt).
     • User explicitly invoked /refine-prompt — let that path run normally.

3. Otherwise, invoke the refine-prompt skill (Skill tool) and run its
   full pipeline. Treat session consent as `always` for this evaluation —
   do NOT emit the first-messy-message prompt, do NOT ask
   "refine this first?". The user opted in by enabling this hook.

4. The Phase B approval gate is NEVER skipped. The user must reply `go`
   (or edit) before any work begins.

5. After `go`, the refined prompt is the canonical task. Do not
   re-interpret the original messy text in this turn or any follow-up
   turn of this session.
EOF
)

jq -nc --arg ctx "$REMINDER" '{
  hookSpecificOutput: {
    hookEventName: "UserPromptSubmit",
    additionalContext: $ctx
  }
}'
```

- [ ] **Step 2: Make the script executable**

```bash
chmod +x hooks/auto-refine.sh
```

- [ ] **Step 3: Run the smoke test to verify it passes**

Run: `bash hooks/test_auto_refine.sh`
Expected: `All tests passed.`

- [ ] **Step 4: Inspect the output by hand**

Run: `echo '{"hook_event_name":"UserPromptSubmit","cwd":"/tmp"}' | bash hooks/auto-refine.sh | jq .`
Expected: Pretty-printed JSON with `hookSpecificOutput.additionalContext` containing the full reminder text.

- [ ] **Step 5: Commit**

```bash
git add hooks/auto-refine.sh
git commit -m "feat: auto-refine.sh — UserPromptSubmit hook for refine-prompt"
```

---

## Task 3: Wire the hook into `hooks.json`

**Files:**
- Modify: `hooks/hooks.json`

- [ ] **Step 1: Read current `hooks/hooks.json`**

Read the file to see the existing structure (one `UserPromptSubmit` entry for `dump-md.sh` and one `Stop` entry for `annotate-wait.py`).

- [ ] **Step 2: Add the new hook entry and update the description**

Replace the file contents with:

```json
{
  "description": "Hook bundle. (1) dump-md.sh on UserPromptSubmit — injects a system instruction telling Claude to save claude-<timestamp>.md under <cwd>/.claude/dumps/ with a curated version of the turn. Reliable, but uses tokens and shows a Write call per turn. (2) auto-refine.sh on UserPromptSubmit — injects a system reminder telling Claude to evaluate every prompt against the refine-prompt skill's messiness heuristic, run the skill on messy prompts, skip on clean/short/mid-flow prompts, and treat the refined prompt as canonical after `go`. (3) annotate-wait.py on Stop — when an annotate-skill response was just pushed to the browser view, blocks up to 30 min waiting for the user to click Submit, then injects the annotations back to Claude as a system reminder so Claude resumes automatically. Bails in milliseconds when no annotate session is mid-flight.",
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "bash \"${CLAUDE_PLUGIN_ROOT}/hooks/dump-md.sh\""
          }
        ]
      },
      {
        "hooks": [
          {
            "type": "command",
            "command": "bash \"${CLAUDE_PLUGIN_ROOT}/hooks/auto-refine.sh\""
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "python3 \"${CLAUDE_PLUGIN_ROOT}/hooks/annotate-wait.py\"",
            "timeout": 1800
          }
        ]
      }
    ]
  }
}
```

- [ ] **Step 3: Validate the JSON**

Run: `jq . hooks/hooks.json > /dev/null && echo "valid JSON"`
Expected: `valid JSON`

- [ ] **Step 4: Confirm both UserPromptSubmit hooks are registered**

Run: `jq '.hooks.UserPromptSubmit | length' hooks/hooks.json`
Expected: `2`

Run: `jq -r '.hooks.UserPromptSubmit[].hooks[].command' hooks/hooks.json`
Expected:
```
bash "${CLAUDE_PLUGIN_ROOT}/hooks/dump-md.sh"
bash "${CLAUDE_PLUGIN_ROOT}/hooks/auto-refine.sh"
```

- [ ] **Step 5: Commit**

```bash
git add hooks/hooks.json
git commit -m "feat: register auto-refine.sh in UserPromptSubmit hook manifest"
```

---

## Task 4: Manual behavioral verification

This task is not test-driven — the harness for Claude Code's hooks-to-skill flow is the Claude Code runtime itself. Run each scenario in a fresh Claude Code session.

**Files:**
- None (manual verification only).

- [ ] **Step 1: Reload the plugin in Claude Code**

In a fresh Claude Code session opened against this repo, confirm both hooks are loaded. Either restart Claude Code or use `/plugin reload petros-skills` if available.

- [ ] **Step 2: Golden path — messy prompt triggers refinement**

Paste this exact message into Claude Code:

> `um so can you check the the thing with the cards on mobile its like too tight`

Expected:
- Claude does NOT respond to this directly.
- Claude invokes the refine-prompt skill via the Skill tool.
- Claude emits Phase A clarification questions (the message has vague references — "the cards", "mobile") OR jumps straight to Phase B with assumptions.
- Claude waits for `go` before doing any work.
- The dump file at `.claude/dumps/claude-<ts>.md` is also created (existing dump-md hook still works alongside).

- [ ] **Step 3: No-op path — clean prompt passes through**

In a fresh session, paste:

> `Please update the README to mention the new auto-refine hook.`

Expected:
- Claude proceeds with the README update directly.
- No refine-prompt skill invocation.
- No Phase B approval gate.

- [ ] **Step 4: Mid-flow protection — `go` doesn't re-trigger**

After Step 2's refined prompt is shown, reply with the literal word `go`.

Expected:
- Claude does NOT re-evaluate `go` as a fresh prompt to refine.
- Claude proceeds to act on the refined prompt.

- [ ] **Step 5: Behavioral strip — refined prompt is canonical**

After Step 2 completes (Claude has done the requested fix), send a follow-up like:

> `What did you change in the last task?`

Expected:
- Claude describes the work in terms of the *refined* prompt, not the messy original. It does not quote `"um so can you check the the thing..."`.

- [ ] **Step 6: Document verification results**

Append a short note to `docs/superpowers/specs/2026-05-14-auto-refine-prompt-hook-design.md` under a new `## Verification log` section recording date and pass/fail for each scenario. Commit.

```bash
git add docs/superpowers/specs/2026-05-14-auto-refine-prompt-hook-design.md
git commit -m "docs: log manual verification results for auto-refine hook"
```

---

## Task 5: Final repo state check

- [ ] **Step 1: Confirm working tree is clean**

Run: `git status`
Expected: `nothing to commit, working tree clean` (or only the unrelated annotate-related changes already present at session start).

- [ ] **Step 2: Confirm the four commits landed in order**

Run: `git log --oneline -n 6`
Expected (top to bottom):
```
docs: log manual verification results for auto-refine hook
feat: register auto-refine.sh in UserPromptSubmit hook manifest
feat: auto-refine.sh — UserPromptSubmit hook for refine-prompt
test: failing smoke test for auto-refine.sh hook
docs: tighten test language in auto-refine-prompt spec
docs: design for auto-refine-prompt UserPromptSubmit hook
```

- [ ] **Step 3: Confirm the smoke test still passes from a clean state**

Run: `bash hooks/test_auto_refine.sh`
Expected: `All tests passed.`

---

## Self-Review Notes

**Spec coverage check:**

| Spec section | Covered by |
|---|---|
| `hooks/auto-refine.sh` — new bash script | Task 2 |
| `hooks/hooks.json` — add entry, update description | Task 3 |
| `skills/refine-prompt/SKILL.md` — unchanged | No task needed (intentional non-goal) |
| Verification plan — script smoke test | Task 1, Task 2 step 3 |
| Verification plan — manual golden path | Task 4 step 2 |
| Verification plan — manual no-op | Task 4 step 3 |
| Verification plan — manual mid-flow | Task 4 step 4 |
| Verification plan — manual behavioral strip | Task 4 step 5 |

All spec requirements have a corresponding task.

**Placeholder scan:** No TBD/TODO/"implement later" markers. Every code step has full code. Every command has expected output.

**Type/name consistency:** `auto-refine.sh` and `test_auto_refine.sh` are the only two new filenames; both are used consistently. JSON key paths (`hookSpecificOutput.additionalContext`, `hookSpecificOutput.hookEventName`) are consistent across the test and script.
