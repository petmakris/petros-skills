# Annotate token budget (maintainer note)

Not loaded at runtime — context for maintainers reasoning about cost.

A typical 5-paragraph response renders to ~300–600 tokens of markdown when pushed to `blocks.json`. Each subsequent wake-up (one comment → one block rewrite) is typically 50–200 tokens — short and focused. The per-block flow means you are never asked to address a whole document's worth of annotations in one turn; just handle the one event and end the turn. Net cost per event is comparable to a short terminal reply, with materially better UX for any non-trivial output.

**Glossary additions.** When a response includes a glossary, each entry adds roughly 45–80 tokens. A typical response with 2–4 entries adds 100–320 tokens (≈25–50% on top of a 600-token response). Rewrites that don't change the term set add nothing.

**Skill body (progressive disclosure).** SKILL.md is a lean router; the heavy procedure is split into `references/pushing.md`, `references/handling-events.md`, and `references/block-kinds/<kind>.md`, each loaded only for the matching lifecycle. Adding a new block kind = add `references/block-kinds/<kind>.md` + one row in the SKILL.md kind menu; the always-loaded router stays flat.
