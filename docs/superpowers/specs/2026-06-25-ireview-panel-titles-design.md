# Clean summary titles in the interactive-review annotations panel

**Date:** 2026-06-25
**Component:** `skills/interactive_review` (server + SKILL.md) + `intellij-plugin-spike` (IDE plugin)
**Status:** Approved design, pre-implementation

## Problem

The annotations side panel renders each thread's row with `snippet(synthesis)`
— the latest Claude answer with newlines collapsed, HTML-escaped, hard-cut at
160 chars (`AnnotationsPanel.java:343`, `snippet()` at `:530`). Because the
synthesis is markdown, the row shows raw *source*: `**bold**`, `` `code` ``,
`[label](path:line)`, heading `#`, table pipes — truncated mid-token. It is
unreadable and tells the reviewer little about what the thread is *about*.

What a row should lead with is a short, clean **title** — a one-line summary of
the thread's topic — not a slice of the answer's markdown.

## Decision

Give each thread an **agent-written `title`**: when Claude answers (or, in a
future feature, seeds a finding), it emits a short plain-text headline that the
panel shows as the row's primary line. Because a title may not be present yet
(the brief window before Claude replies, or threads answered before this
feature), the panel resolves the row label through a **fallback chain**:

```
title  →  question (first user message, cleaned)  →  first line of plain-text(synthesis)  →  anchor
```

The raw-markdown excerpt is removed; the row becomes **title (primary) + muted
meta (`file:line`, `v#`, state)**. The full rendered answer stays one click
away in the existing popup.

### Alternatives considered

- **Local-only (no agent title): render synthesis to plain text, show first
  sentence.** Ships fastest, but it's still an *answer excerpt*, not a title —
  it doesn't summarize, and a finding-origin thread (no question, terse answer)
  reads poorly. Kept as the *fallback* layer, not the whole solution.
- **Title = the user's question.** Clean when a question exists, but threads can
  originate from Claude (review findings) with no question. Kept as a fallback
  rung, not the primary source.

## Scope

In scope:
- `interactive_review` server — `title`/`question` on the thread + in `threads_bulk`.
- `interactive_review` `SKILL.md` — agent writes a title when it answers.
- `intellij-plugin-spike` — model fields, markdown→plain-text, row redesign + fallback chain.

Explicitly out of scope (separate, larger feature — this spec only stays
forward-compatible with it): **Claude seeding initial review findings at review
start.** Today the flow is purely reactive (user clicks a line → asks → Claude
answers). A Claude-origin thread will simply carry a `title` and no `question`,
which the fallback chain already handles.

## Server changes (additive, backward-compatible)

**`threads.py`**
- `append_message(threads_dir, anchor, msg, title=None)` gains an optional
  `title` keyword. When `title` is a non-empty string, set top-level
  `thread["title"] = title` before saving. **Last-write-wins** — each Claude
  answer refreshes the title so it tracks the evolving synthesis. The kwarg is
  optional, so the server's existing user-message append (which passes no
  title) is unchanged.

**`server.py`**
- `threads_bulk` result rows gain two fields:
  - `"title"`: `t.get("title", "")`.
  - `"question"`: the `text` of the **first** message with `role == "user"` in
    the thread, or `""` if none.
- These flow to the client both in the seed (`threads.json`) and in
  `thread-changed` SSE events (the stream builds payloads from `threads_bulk`).
- Threads without `title`/user-message remain valid; fields default to `""`.
  No migration.

## Agent change — `SKILL.md`

**Mode D, step 4 (append the message).** The agent passes a `title` into the
helper call:

```python
append_message(Path('$STATE_DIR/threads'), '$ANCHOR', {
    'role': 'claude',
    'ts': $(date +%s),
    'text': '''<your answer>''',
    'source_event_id': '$EVENT_ID',
}, title='<short headline>')
```

**Response style guide.** Add one rule: the title is **plain text (no
markdown), ≤ ~6 words / 60 chars**, a noun phrase naming the thread's topic
(e.g. *"Null check on portfolio lookup"*, *"Why the fee branch is skipped"*).
Refresh it each answer so it stays accurate as the synthesis absorbs new
questions.

## Client changes (IntelliJ)

**Model.** `ReviewSessionClient.ThreadState` gains `title` and `question`
(parsed via the existing Gson path added in the anchor-drift work; absent →
`""`). All `ThreadState` construction sites and the SSE/bulk parsers carry the
two new fields.

**Markdown → plain text.** A small helper renders a markdown string to readable
plain text using the commonmark dependency already bundled in the plugin
(`org.commonmark.renderer.text.TextContentRenderer`), used only for the
synthesis-fallback rung. First non-blank line, whitespace-collapsed, truncated.

**Row redesign (`AnnotationsPanel.renderCell`).**
- **Primary line:** the resolved title via the fallback chain
  `title → question → plainTextFirstLine(synthesis) → anchor`, single line,
  ellipsized, the row's most prominent text.
- **Meta line (muted):** `file:line` · `v#` · state marker (`● new` when
  `isNew()`, `⚠ stale` when stale — reuse the existing stale detection).
- The raw-markdown `snippet()` center label is **removed** (and `snippet()`
  deleted if it has no other caller).
- Hover/click behavior unchanged: delete `×`, navigate-to-diff, and the popup
  with the full rendered synthesis.

**Fallback resolver.** Extract the chain into a pure, unit-testable function
(e.g. `PanelRowTitle.resolve(String title, String question, String synthesis,
String anchor)`) so the branch logic is tested without Swing.

## Edge cases

- **Legacy answered threads** (no `title`): fall back to plain-text first line
  of the synthesis. No migration.
- **Finding-origin thread** (future; `title` set, `question` == ""): shows the
  title; the empty question rung is skipped.
- **Pre-answer window:** `threads_bulk` already omits threads with no Claude
  message, so the panel only lists answered threads — every listed row has a
  synthesis, and (new threads) a title.
- **Cleaning:** `question` and the synthesis-fallback are whitespace-collapsed
  and trimmed; a blank result falls through to the next rung.

## Testing

- **Server (pytest):**
  - `append_message(..., title="X")` sets `thread["title"]`; a second call with
    `title="Y"` overwrites it (last-write-wins); a call with no `title` leaves
    an existing title untouched and never creates the key.
  - `threads_bulk` returns `title` and `question` (first user message); both
    default to `""` when absent.
- **Client (pure unit):**
  - Fallback resolver: title present → title; no title, question present →
    question; neither → synthesis first line; all empty → anchor; whitespace-only
    rungs are skipped.
  - Markdown→plain-text: strips `**bold**`, `` `code` ``, `[label](url)` (keeps
    `label`), headings, and table syntax; returns a clean first line.
- **FakeReviewServer:** include `title`/`question` in `threads.json` and SSE
  `thread-changed` payloads so the client mapping is exercised end-to-end.
- **Row rendering:** manual smoke (Swing-bound), like the gutter work — restart
  IntelliJ, confirm rows show clean titles and the muted meta line.

## Success criteria

- A newly-answered thread shows Claude's short plain-text title as the row's
  primary line; no raw markdown characters appear in the panel.
- A thread answered before this feature still shows a readable line (plain-text
  synthesis), never raw markdown.
- The `file:line` reference and version/state are present but visually
  secondary.
- All server pytest and IntelliJ automated suites pass.
