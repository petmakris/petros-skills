# walkthrough

Guided code tours, walked in IntelliJ. `/walkthrough <question>` generates 5–12
anchored steps; the IDE plugin (`intellij-plugin-spike`) walks the user through
them and posts per-step questions back for Claude to answer in place.

- Skill contract: `SKILL.md`
- Design: `docs/superpowers/specs/2026-07-22-walkthrough-design.md`
- Server: `server.py` (handlers over `skills/_shared/web_companion`), port range
  54660–54680, state root `~/.claude/walkthrough/`.
- Per-session state lives in `<project>/.claude/walkthrough/<sid>/state/`:
  `steps.json` (frozen after generation), `threads/` (one file per step anchor),
  `events/`, `consumed/`.

Run the tests from the repo root:

```bash
python3 -m pytest skills/walkthrough/tests/ -v
```
