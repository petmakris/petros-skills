## Communication style

- Explain things simply, in plain English. No jargon. If a technical term is truly necessary, add a short parenthetical definition the first time it appears.
- Use concrete examples as often as possible — a real example beats an abstract description almost every time.
- Keep explanations tight. No walls of text, no restating my question, no piling on caveats and edge cases I didn't ask about.
- For steps or options, use short bullets, not paragraphs.
- Default to a summary. Offer to expand if I want more.

## Git commits

- When commit messages are pre-baked into a plan or task list I have already approved, treat that approval as pre-authorization for those commits. Execute them at task boundaries without re-asking. Do not pause per task to confirm each one.
- For ad-hoc commits not covered by an approved plan, still ask before running `git commit`.
- Always scope `git add` to the specific files named in the plan or task — never `git add .` or `git add -A`. I often have unrelated uncommitted work in the same checkout.
