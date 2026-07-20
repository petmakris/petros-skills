---
name: annotate
description: Render Claude responses as an interactive web page with span-based annotation. Three trigger paths — (1) auto: Claude routes its current response through the view when it contains 2+ distinct things the user might want to react to (plans, analyses, multi-paragraph answers, lists of findings); (2) postmortem: user manually invokes the skill ("annotate", "annotate that", "/annotate") after a big response has already landed, and the skill pushes the most recent prior assistant message through the same pipeline; (3) watcher event: a task-notification arrives whose first stdout line starts with `WEBCOMPANION_EVENT`, `WEBCOMPANION_FINISHED`, or `WEBCOMPANION_CANCELLED` — that's a previously-pushed response's watcher reporting in, and the skill must be re-invoked to parse the payload and respond. In all cases the user reads in the browser, clicks any block to comment, and Claude updates that block in place when it responds.
allowed-tools:
  - Bash
  - Read
  - Write
---

# /annotate — interactive annotation view

Long responses (multi-step plans, analyses, lists of findings) get pushed to a browser page where the user clicks any block to comment. Claude updates that block in place when it responds — no page reload, no re-push of the whole document.

This SKILL.md is the **router**: it tells you whether to act and which detailed
reference to load. The heavy procedure lives in `references/` and is loaded only
when you actually need it — keep this file lean as block kinds grow.

## Phase map — read the matching reference, then follow it

Decide which situation you're in and **`Read` the named file before doing the work**:

| Situation | What it is | Read & follow |
|-----------|-----------|---------------|
| You're composing a response that meets a routing trigger below (or forward mode is armed) | **Push** | `references/pushing.md` |
| The user typed `/annotate` / said "annotate", "annotate that" | **Push** (postmortem/armed) | `references/pushing.md` |
| The user typed `/annotate resume` / `/annotate resume <slug>` | **Resume** a past workspace | `references/resuming.md` |
| A task-notification's first stdout line is `WEBCOMPANION_EVENT` / `WEBCOMPANION_FINISHED` / `WEBCOMPANION_CANCELLED` | **Handle event** | `references/handling-events.md` |
| The user says "scrap it" / "stop annotating" / "respond in terminal" while a watcher is armed | **Cancel** | `references/handling-events.md` (§ Terminal cancellation) |

These lifecycles are independent invocations: pushing creates the page and arms a watcher; handling-events fires later, once per comment; resuming points an existing workspace at this conversation instead of creating one. Do not load a reference you don't need for the situation you're in.

## Routing decision (Mode A — Forward)

Route to the annotation view when ANY of the following is true about the response you are about to write:

- It is a multi-step plan with 2+ steps the user might want to comment on.
- It is an analysis with 2+ distinct claims or recommendations.
- It is a list of findings, options, or items (≥2).
- It contains multiple paragraphs each making a separable point.

DO NOT use the annotation view for:

- Single-fact answers ("the port is 5432").
- Yes/no responses.
- Short prose with no addressable claims.
- Status updates, summaries, brief acknowledgments.
- Tool-result discussions where you're just reporting what a command produced.

When in doubt, prefer the annotation view. Once you've decided to route, follow `references/pushing.md`.

## Block-kind menu

Every block defaults to `kind: "markdown"` (plain markdown; may contain inline HTML — see `references/pushing.md`). For a richer block, pick a kind below by its trigger, then **`Read` that kind's reference for the exact spec shape before emitting it**:

| Kind | Use when | To emit, read |
|------|----------|---------------|
| `markdown` (default) | Prose, lists, code, tables, callouts. | `references/pushing.md` (How to push + Inline HTML) |
| `sequence` | ≥2 named entities interacting **in temporal order**, where who-talks-to-whom matters (code flows, request/response, event lifecycles). | `references/block-kinds/sequence.md` |
| `flowchart` | Branching/decision/process-flow logic — guard clauses, validation pipelines, fan-in from multiple callers, success/error outcomes. Structured nodes/edges, role color, jump-to-source links, per-node comments. | `references/block-kinds/flowchart.md` |
| `diagram` | Better seen than read AND non-temporal, non-branching: architecture, state machine, ER, class. Mermaid source → server renders SVG. | `references/block-kinds/diagram.md` |
| `choice` | A decision point with 2–4 discrete options where the pick drives the next step. | `references/block-kinds/choice.md` |
| `mockup` | A high-fidelity, interactive UI mock is clearer than prose or a static diagram — real `<style>`/`<script>`/Tailwind, hover, interaction. Renders in a sandboxed iframe. | `references/block-kinds/mockup.md` |

One diagram per concept; frame it with a short prose block — a diagram must add clarity, not decorate. Each reference also states when **not** to use that kind.

## Session lifecycle

The web companion server is a **single `nohup` process shared across every
Claude Code session** on this machine, not one per conversation. `ensure_server.sh`
starts it the first time anyone needs it; every later call from any session just
confirms it's already up. It survives this conversation ending, and self-shuts
after **24h with no activity** (any request resets the clock).

Workspaces (one per `sid`/`slug`) persist on disk for **7 days** at
`<cwd>/.claude/annotate/<sid>/` (addressed by `slug` in URLs and `/annotate
resume`, stored under `sid`) independent of whether any Claude session is
currently attached to them. A conversation ending doesn't delete its
workspace — the page stays live, and it's still there to come back to later.

Because of this, don't mint a fresh workspace on every push within one
conversation — `references/pushing.md` § "Create-or-attach a workspace for
this conversation" creates once and attaches on every push after that. To
reopen a workspace from a past conversation, the user can open the browser at
`<server_url>/` (lists every live workspace, filterable by project), or you
can run `/annotate resume <slug>` — see `references/resuming.md`.

## Maintainer notes

Cost/token characteristics of the skill are documented in `docs/token-budget.md` (not needed at runtime).
