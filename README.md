# petros-skills

Personal cross-project Claude Code skills and a sandbox for experimentation.

## What's in here

- `skills/` — individual skills, one directory each. `_template/` is a starting template (leading underscore sorts it to the top).
- `hooks/` — plugin hooks: `dump-md.sh` (UserPromptSubmit) writes each turn as a clean markdown file; `annotate-wait.py` (Stop) blocks until the browser annotations come back when the `/annotate` skill is mid-flight.
- `output-styles/` — custom output styles shipped by the plugin.
- `git-hooks/` — repo-local git hooks. `pre-commit-claude-scan` runs Claude over every staged diff to catch secrets / private paths before they're committed. Install with `./install-git-hooks`.
- `.claude-plugin/` — plugin manifest (`plugin.json`) and local marketplace entry (`marketplace.json`).

## Install

**One time, from anywhere on your machine.** Once installed the plugin is enabled globally — every Claude Code session everywhere will get its skills, hooks, and output styles. You do **not** need to repeat this per project.

In a Claude Code session, run these slash commands (not shell commands):

```
/plugin marketplace add $HOME/projects/petros-skills
/plugin install petros-skills@petros-skills
```

Then restart Claude Code (or run `/reload-plugins`). Verify with `/plugin` — `petros-skills` should appear as enabled.

> If the cwd of the Claude session you run the commands from happens to be this repo, `./` works in place of the absolute path. Either way, the marketplace registration is stored in `~/.claude/settings.json` and persists across projects.

## Disable per project

Hooks fire in *every* project once the plugin is installed. To opt a single project out, add to that project's `.claude/settings.json`:

```json
{
  "enabledPlugins": {
    "petros-skills@petros-skills": false
  }
}
```

To uninstall everywhere: `/plugin uninstall petros-skills@petros-skills`.

## Skills

- **`/annotate`** (`skills/annotate/`) — Long Claude responses (plans, analyses, lists of findings) get pushed to a local browser page where you highlight any text and leave free-text comments. The annotations come back to Claude on its next turn. Stdlib-only Python server, no installs.
- **`/humanize`** (`skills/humanize/`) — Rewrite Claude-generated markdown so it reads like a long-time colleague wrote it. Manual only — invoked when you type `/humanize` or ask to "derobotize" something.
- **`/refine-prompt`** (`skills/refine-prompt/`) — Turns messy speech-to-text transcripts into high-quality coding prompts via a normalize → probe → classify → compose pipeline, with explicit approval before execution.

## Hooks

- **`dump-md`** (`hooks/dump-md.sh`) — UserPromptSubmit hook. Injects a system instruction at the start of every turn telling Claude to write a clean markdown rendering of that turn (user prompt + Claude's prose) to `<cwd>/.claude/dumps/claude-<timestamp>.md`. Open the file in VS Code with markdown preview (Cmd+Shift+V) for a nicer version than the terminal output. One file per turn; `.claude/` is gitignored so the dumps stay out of version control.
- **`annotate-wait`** (`hooks/annotate-wait.py`) — Stop hook. When the `/annotate` skill has just pushed a response to the browser, blocks for up to 30 minutes waiting for the user to submit annotations, then injects them back to Claude as a system reminder so Claude resumes automatically. Bails in milliseconds when no annotate session is mid-flight.

## Git hooks (this repo only)

Install once per clone:

```
./install-git-hooks
```

This symlinks `git-hooks/pre-commit-claude-scan` into `.git/hooks/pre-commit`. On every commit it pipes the staged diff into `claude -p` and asks it to flag anything that looks private — API keys, hardcoded `/Users/<name>/` paths, internal URLs, pasted chat logs, etc. Output is `PASS` or `FAIL: <reason>`. `FAIL` blocks the commit.

Bypass once: `git commit --no-verify`. Disable for a single commit: `SKIP_CLAUDE_SCAN=1 git commit ...`. If the `claude` CLI isn't on PATH the hook prints a warning and lets the commit through.

## Output styles

- **`clean`** (`output-styles/clean.md`) — Plain-English, one-idea-at-a-time chat voice. Pre-made decisions instead of A/B/C menus. Opt-in via `/output-style clean`.
- **`ghostwrite`** (`output-styles/ghostwrite.md`) — Strips first-person markers, politeness, and AI tells from text you'll post under your own name (Jira tickets, PR descriptions, Slack/email drafts to teammates). Opt-in via `/output-style ghostwrite`.

## Add a new skill

1. Copy `skills/_template/` to `skills/<your-skill-name>/` (drop the leading underscore).
2. Edit `SKILL.md` frontmatter (`name`, `description`, `allowed-tools`) to match the new directory name.
3. Run `/reload-plugins` in Claude Code to pick up the change.
