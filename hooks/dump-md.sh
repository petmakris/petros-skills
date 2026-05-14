#!/usr/bin/env bash
set -euo pipefail

# UserPromptSubmit hook: per-turn markdown dump.
#
# Injects a system instruction at the start of every turn telling Claude to
# save a clean, curated markdown file at <cwd>/.claude/dumps/claude-<ts>.md
# via the Write tool. Claude is asked to lightly polish (re-flow phrasing,
# drop false starts) — not to invent content. The path is echoed at the very
# end of Claude's terminal response so the user can ⌘-click it.
#
# This replaces an earlier Stop-hook version that read the transcript with jq
# and silently dumped it. That approach raced Claude Code's JSONL flush and
# frequently produced empty Response sections.

input="$(cat)"
event="$(printf '%s' "$input" | jq -r '.hook_event_name // empty')"

if [ "$event" != "UserPromptSubmit" ]; then
  exit 0
fi

cwd="$(printf '%s' "$input" | jq -r '.cwd // empty')"
[ -z "$cwd" ] && cwd="$(pwd)"

ts="$(date '+%Y-%m-%d-%H%M%S')"
out_dir="${cwd}/.claude/dumps"
mkdir -p "$out_dir"
out="${out_dir}/claude-${ts}.md"

jq -nc --arg path "$out" '{
  hookSpecificOutput: {
    hookEventName: "UserPromptSubmit",
    additionalContext: (
      "PER-TURN MARKDOWN FILE\n\n" +
      "For this turn, in addition to your normal terminal response, also save a well-formatted markdown file at:\n\n  " + $path + "\n\n" +
      "File layout (in this exact order):\n" +
      "  1. `# Prompt` heading\n" +
      "  2. The user prompt for THIS turn, verbatim (copy it from your context — do not paraphrase or summarize). If the user attached an image or other non-text content, omit it here; only include the text the user typed.\n" +
      "  3. A horizontal rule on its own line: `---`\n" +
      "  4. `# Response` heading\n" +
      "  5. A CURATED version of your prose answer for this turn. You may lightly polish — re-flow choppy sentences, drop false starts, merge fragments, tighten lists — but do not invent content beyond what you actually said in this turn. The goal is a clean standalone note, not a verbatim transcript.\n\n" +
      "Rules:\n" +
      "- Use the Write tool to create the file near the end of your turn, once your answer has taken shape.\n" +
      "- Contents = headings, prose, code fences, lists. NO raw tool JSON, NO transcript dumps, NO tool-result blobs.\n" +
      "- Format it as if writing a small standalone note a human would enjoy reading in a markdown viewer.\n" +
      "- The very last line of your terminal response must be this absolute path on its own line so the user can ⌘-click it:\n\n  " + $path + "\n\n" +
      "Do this every turn this instruction appears."
    )
  }
}'
