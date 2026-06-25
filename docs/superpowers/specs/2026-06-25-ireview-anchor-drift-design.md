# Fix anchor drift in the interactive-review IDE plugin (IntelliJ)

**Date:** 2026-06-25
**Component:** `skills/interactive_review` (server) + `intellij-plugin-spike` (IDE plugin)
**Status:** Approved design, pre-implementation

## Problem

An interactive-review annotation is keyed by `<path>:<L|R>:<line>`, where
`line` is a static line *number* captured when the diff viewer opens. In
`SpikeDiffExtension`, each gutter icon is bound to a fixed `lineZeroBased`, and
the thread is looked up purely by that number. Nothing re-checks that the line
still holds the content it had when the annotation was created.

So when the working tree is edited (or the branch is rebased and a new diff is
snapshotted), the same line number now points at *different* code, and the
annotation icon is painted there silently. For a review tool whose whole job is
to be trustworthy about *which line* a question is about, this quiet wrongness
is the worst failure mode: the reviewer has no signal that the annotation moved.

`HANDOFF.md` calls this out as "the real correctness bug."

## Decision

Capture the anchored line's text once, at creation, and resolve every paint to
one of three states instead of blindly trusting the line number:

- **EXACT** — the recorded line still equals the stored text → paint normally.
- **MOVED** — the stored text is found *uniquely* within a small window around
  the recorded line → re-locate the icon to that line.
- **STALE** — the text is gone, or appears more than once in the window →
  paint a distinct "stale" marker and *never* a wrong line.

The line number stays the anchor key (no migration); we add a single stable
`anchor_text` field to the thread and a pure resolver in the client.

### Alternatives rejected

- **Content-addressed anchors** (`path:side:hash(line)` as identity). Most
  robust, but it changes every anchor's shape and must land in the server and
  both clients at once, breaking existing threads. XL for a dogfood tool —
  over-engineered.
- **Anchor to the `diff.patch` snapshot** and map patch lines → editor lines.
  That mapping *is* the "PR-diff editor integration" `HANDOFF.md` already
  dropped as XL-for-marginal-gain; reopening it re-litigates a decided
  direction.

## Scope

In scope:
- `interactive_review` server — additive schema change.
- `intellij-plugin-spike` — capture, parse, resolve, render.

Explicitly out of scope (separate follow-ups, not this spec):
- The VS Code client (it can adopt the same `anchor_text` field later).
- The port-fallback discovery bug (hardcoded `54620`).
- MCP-enriched synthesis; PR-diff editor integration.

## Server change (additive, backward-compatible)

- A thread gains a top-level `anchor_text` string, **set once on first
  creation** (first-write-wins). Because it is written only when absent, it
  reflects the line as it was when first annotated and is immune to later
  edits.
- `handle_submit` reads `anchor_text` from the submit payload and stores it on
  the thread only if the thread does not already have one. (The agent's own
  appended messages never carry it, so they never overwrite it.)
- `threads_bulk` returns `anchor_text` alongside `latest_synthesis`/`version`,
  so the client receives it both in the seed (`threads.json`) and in
  `thread-changed` SSE events — no per-thread fetch needed.
- Threads without `anchor_text` (legacy, or general/no-line anchors) remain
  valid and are treated by the client as EXACT. No migration.

## Client changes (IntelliJ)

1. **Capture at submit.** `SynthesisPopup` / `ReviewSessionClient.postComment`
   include `anchor_text` = the current line's text in the submit payload.
   Comparison is later done on a trimmed basis (see algorithm).

2. **Real JSON parsing (required enabling work).** Replace the hand-rolled
   regex `jsonField` / `parseThreadsBulk` in `ReviewSessionClient` with an
   actual JSON parser. Stored line text is arbitrary — quotes, braces,
   newlines — and breaks the current regex. (This also resolves `HANDOFF.md`
   backlog item #3.) Add an explicit `com.google.code.gson:gson` dependency in
   `build.gradle.kts` — declared the same way `commonmark` already is, so it
   bundles into the plugin's `lib/` — rather than relying on unverified
   platform-bundled Gson.

3. **`AnchorResolver` — a pure, unit-tested function.** Given
   `(documentLines, recordedLine, anchorText)` it returns `EXACT(line)`,
   `MOVED(newLine)`, or `STALE`. No IDE state, no I/O — fully testable in
   isolation.

4. **Wire-in.**
   - `SpikeDiffExtension`'s gutter renderer consults `AnchorResolver` to decide
     what to paint: the normal annotated icon at the exact/moved line, or a
     greyed "stale" icon otherwise.
   - `AnnotationsPanel` greys and labels stale rows.
   - Re-location is **display-only**: the thread key (`path:side:line`) does not
     change, so follow-ups and deletes still address the same thread; only the
     painted line moves.

## Re-location algorithm

Given the recorded line `r`, the stored `anchorText` (compared as
`anchorText.trim()`), and a window radius `K`:

1. If `anchorText.trim()` is empty → **EXACT(r)** (blank lines aren't
   matchable; preserve current behavior).
2. If line `r` exists and `line(r).trim() == anchorText.trim()` → **EXACT(r)**
   (no search).
3. Otherwise scan lines `[r-K, r+K]` (clamped to the document) for lines whose
   trimmed text equals `anchorText.trim()`:
   - exactly one match at line `m` → **MOVED(m)**,
   - zero or more than one → **STALE**.

`K` is a single tunable constant; **default `K = 25`**.

Rationale: trimming tolerates re-indentation. A *local* window models "the code
shifted a bit" and prevents common one-liners (`}`, `return null;`) from
re-locating across the whole file; whole-window ambiguity safely degrades to
stale rather than guessing.

## Edge cases

- **Left vs right side.** Same logic both sides; in practice only the R
  (working-tree) side drifts within a session, but the base side is handled
  uniformly.
- **General / no-line anchor.** Skip drift logic entirely (not line-bound).
- **Legacy threads without `anchor_text`.** Treated as EXACT — no migration,
  no behavior change for pre-feature threads.
- **Document shorter than the recorded line** (lines deleted past it). The
  recorded line no longer exists → step 2 fails → window scan (clamped) →
  MOVED if found uniquely, else STALE.

## Testing

- **Server (pytest).** `anchor_text` is set once (first-write-wins, second
  submit does not overwrite); `threads_bulk` surfaces it.
- **Client — `AnchorResolver` (pure unit tests).** exact; moved-unique;
  moved-ambiguous → stale; gone → stale; legacy-null → exact; blank → exact;
  window boundary (just inside vs just outside `K`); recorded line past
  end-of-document.
- **Client — JSON parsing.** synthesis and line text containing `"`, `{`/`}`,
  and `\n` round-trip correctly through the new parser.
- **`FakeReviewServer`.** include `anchor_text` in `threads.json` and SSE
  `thread-changed` payloads so client-side resolution is exercised end-to-end.

## Success criteria

- Editing the working tree so an annotated line moves a few lines re-locates
  the icon to the moved line (MOVED), with the same thread intact.
- Deleting or duplicating the annotated line surfaces a visible **stale**
  marker (gutter + panel) — the icon is never painted on an unrelated line.
- Pre-existing threads with no `anchor_text` behave exactly as today.
- All server pytest and IntelliJ test suites pass.
