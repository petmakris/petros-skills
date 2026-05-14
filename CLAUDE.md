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

## Plugin design — zero-friction for end users

Anything in this repo that gets installed as a Claude Code plugin (skills, hooks, commands) must work end-to-end with no setup step beyond installing the plugin. Concretely:

- **No `pip install` / `npm install` steps for adopters.** If a skill needs a library, vendor it into the skill directory or use a client-side equivalent. Stdlib-only is the default; third-party Python deps require an explicit reason.
- **No runtime network dependency.** Don't load JS/CSS/fonts from a CDN at runtime if a user might be offline or behind a firewall. Bundle assets as static files under the skill's `static/` directory.
- **No external services required to function.** The plugin should work on a fresh machine with the plugin tarball alone.

When a new feature tempts you toward a dependency, prefer (in order): stdlib → vendored library → client-side JS bundle → ask the user before introducing a pip/npm dep.
