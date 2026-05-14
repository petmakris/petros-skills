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
