---
name: template
description: Template skill — copy this directory, rename it, and edit this frontmatter. Replace this description with one that explains when Claude should invoke the skill.
disable-model-invocation: true
allowed-tools:
  - Bash
  - Read
---

# Skill template

This file is a template. To create a new skill:

1. Copy `skills/_template/` to `skills/<your-skill-name>/` — drop the leading underscore in the new directory name; the underscore here just sorts the template to the top.
2. Update `name:` in the frontmatter to match the new directory name (no underscore).
3. Rewrite `description:` so Claude Code knows when to invoke the skill.
4. Adjust `allowed-tools:` (or remove it) to match what the skill actually needs.
5. Remove `disable-model-invocation: true` if you want Claude to auto-invoke the skill.
6. Replace this body with the skill instructions.
