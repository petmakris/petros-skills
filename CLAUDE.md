# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## Presenting information — no cloud artifacts

- NEVER publish content via claude.ai web artifacts (the Artifact tool / `https://claude.ai/code/artifact/...` URLs). Work content is sensitive and must not leave the machine.
- When a visual/HTML presentation is useful, write a **local HTML file** instead (in the project's ignored area or the session scratchpad) and send it with SendUserFile for local rendering.

