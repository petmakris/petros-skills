# Auto-refine-prompt hook — design

**Date:** 2026-05-14
**Status:** Approved (pending written-spec review)
**Owner:** Petros

## Problem

The `refine-prompt` skill is excellent at turning messy speech-to-text transcripts into well-formed coding prompts, but it relies on Claude's judgment to decide *when* to invoke it. In practice Claude forgets, especially on long sessions, and messy prompts slip through unrefined — polluting both the work that follows and the conversation history.

We want a guarantee: every user prompt gets evaluated against the messiness heuristic. Messy prompts get refined. Clean prompts pass through untouched. Mid-flow replies and short confirmations are not re-evaluated. After approval, downstream behavior treats the refined prompt as the canonical task, not the messy original.

## Goals

1. **Every prompt gets evaluated.** No reliance on Claude remembering to check.
2. **Behavioral strip.** After `go`, Claude treats the refined prompt as the source of truth; the messy original is not re-interpreted in later turns.
3. **No skill rewrite for v1.** The skill's existing flow (Phase A → B → C) and heuristic are good. Just guarantee they fire.
4. **Zero new dependencies.** Bash + `jq`, both already used by the existing `dump-md.sh` hook. Honors the plugin constitution (no pip/npm/CDN).

## Non-goals (explicit)

- **Visible strip.** We are NOT rewriting the user's literal terminal scrollback or stripping the messy original from Claude's input context. That would require the hook itself to call the Claude API per turn (cost + offline concern). Behavioral strip is sufficient.
- **Skill changes.** The existing classification (mild ambiguity → Assumptions; word/name/term ambiguity → blocking Phase A question) is unchanged in v1. May revisit after observing real usage.
- **Stricter clarification.** Not promoting "mild" ambiguities to blocking questions and not adding an interpretation-preview phase. The current Phase A is sufficient.

## Architecture

Two pieces, both already familiar to this repo:

| Component | Type | Status |
|---|---|---|
| `hooks/auto-refine.sh` | New `UserPromptSubmit` hook script | NEW |
| `hooks/hooks.json` | Add a second `UserPromptSubmit` entry alongside the existing `dump-md.sh` | EDIT |
| `skills/refine-prompt/SKILL.md` | The existing skill | UNCHANGED |

### Flow per prompt

1. User submits a message.
2. Claude Code fires `UserPromptSubmit` hooks: `dump-md.sh` and `auto-refine.sh` run independently. Order does not matter — each injects its own `additionalContext` block.
3. Claude sees the user's prompt plus two independent system reminders.
4. The `auto-refine.sh` reminder instructs Claude to evaluate messiness, run the skill if applicable, and treat consent as `always`.
5. Skill runs its normal Phase A → B → C flow (or no-ops on clean prompts and confirmations).
6. After `go`, the refined prompt is the canonical task; the messy original is in the transcript but not interpreted further.

### Why a shell script, not Python

Mirrors the existing `dump-md.sh` pattern. Only depends on `jq`, which is already a hook dependency in this repo. Keeps the surface area minimal.

### Why two hooks, not merged

Separation of concerns: `dump-md.sh` writes a markdown dump; `auto-refine.sh` nudges Claude to refine. Either can be disabled in isolation by editing `hooks/hooks.json`. Each is small and readable.

## The hook output

`auto-refine.sh` reads the standard `UserPromptSubmit` hook input on stdin, confirms the event name, and emits a single JSON object:

```json
{
  "hookSpecificOutput": {
    "hookEventName": "UserPromptSubmit",
    "additionalContext": "REFINE-PROMPT AUTO-EVALUATION\n\n…"
  }
}
```

The `additionalContext` body (exact text):

```
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
```

### Design choices flagged

- **Heuristic paraphrased inline.** Claude has to decide *whether* to load the skill before reading it — so the relevant signals must live in the reminder itself. Cost: ~150 tokens/turn. Acceptable.
- **Consent override is soft.** The reminder says "treat consent as `always` for this evaluation." It does not actually mutate the skill's session-scoped consent state — just bypasses the consent prompt for this turn. Keeps the skill's own logic untouched.
- **Point 5 restates the skill's invariant 6.** Belt-and-suspenders so Claude actually honors "refined prompt is the task" across multi-turn follow-ups.

## Edge cases

| Scenario | Behavior |
|---|---|
| User types `/refine-prompt` explicitly | Skill triggers from explicit invocation; reminder's rule 2 says "let that path proceed." No double-run. |
| User replies `go` in Phase B | Rule 2 (short confirmation) → skip. Claude proceeds with the refined task. |
| User edits the refined prompt in Phase B | Rule 2 (reply to in-progress flow) → skip. |
| User replies to a Phase A clarification question | Same → skip. |
| Clean message | Rule 1 (heuristic) → skip. Skill no-ops. |
| User invokes a different skill (`/humanize`, `/annotate`, …) | Other skill loads normally; reminder doesn't interfere — slash commands are not flagged as messy. |
| Hook coexists with `dump-md.sh` | Both fire on `UserPromptSubmit`, both inject independent `additionalContext`. No coordination needed. |

## Verification plan

1. **Script smoke test.** Pipe a synthetic `UserPromptSubmit` payload into `auto-refine.sh`, assert the output is valid JSON with the expected shape. Add a bash test (e.g., `hooks/test_auto_refine.sh`) that runs the script with a fixture payload and validates the `additionalContext` field is present and non-empty. Bash matches the script's language and avoids pulling python into a script-only path.

2. **Manual: golden path.** Fresh Claude Code session in this repo. Paste a known-messy transcript (e.g., `"um so can you check the the thing with the cards on mobile its like too tight"`). Confirm Claude runs the skill, shows a refined prompt, and asks `go`.

3. **Manual: no-op path.** Paste a clean message (e.g., `"Please update the README to mention the new auto-refine hook."`). Confirm Claude proceeds directly without invoking refine-prompt.

4. **Manual: mid-flow protection.** During a refine-prompt flow, reply `go`. Confirm Claude doesn't re-evaluate `go` as a fresh prompt and proceeds with the refined task.

5. **Manual: behavioral strip.** After a refine-prompt completes, ask a follow-up question that references "the previous task." Confirm Claude treats the refined version as the task, not the messy original.

## Cost

- ~150 tokens per turn for the injected reminder.
- Zero new runtime dependencies (bash + jq, both already used).
- Zero pip/npm installs for adopters.
- Works offline.

## Open questions

None blocking. After v1 ships, watch for:

- Does Claude ever skip evaluation despite the hook? If so, the reminder may need stronger phrasing.
- Are there messy prompts the heuristic misses? If so, revise the inline signals.
- Does the behavioral strip hold across long sessions, or does the messy original creep back in? If it creeps back, may need to put the reminder in `Stop` hook too.
