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
