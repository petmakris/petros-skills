# Interactive Review — IntelliJ plugin handoff

Resume doc for a fresh Claude Code session. Covers the IntelliJ plugin
(`intellij-plugin-spike/`), the sole client of the `interactive_review`
server (`skills/interactive_review/`). IntelliJ is the only supported review
surface — there is no VS Code extension and no browser UI.

This file was rewritten 2026-06 after an audit found the prior version
describing code that never existed. Everything below is verified against source.

## What we're building

Let a developer annotate any line of a PR diff with a question and get a
short, code-aware answer inline, then ask follow-ups on the same line. Each
`file:side:line` owns its own thread. No code is modified — this is for
*understanding* a PR.

The answer for a line is meant to read as **one self-contained paragraph** that
absorbs every question asked on that line so far — a teammate joining later
reads one up-to-date answer per line, not a chat to scroll. See "Synthesis
model" for how close we actually are to that.

## Architecture (the real one)

```
  Terminal:  /interactive-review <PR>   (a Claude Code session)
                       │
                       ▼
         interactive_review server  ── headless: no browser UI
           - POST /api/sessions           (discovery by cwd)
           - GET  /s/<sid>/threads.json
           - GET  /s/<sid>/stream  (SSE thread-changed)
           - POST /s/<sid>/api/submit
                  ▲                 │ WEBCOMPANION_EVENT (watcher stdout)
                  │ submit          ▼
         ┌────────┴────────┐   the SAME Claude session that ran
         │ IntelliJ plugin │   /interactive-review wakes, reads
         │   (sole client) │   diff.patch + threads, answers,
         └─────────────────┘   appends to the thread, acks
```

Two facts the old doc got wrong:

- **There is no `claude -p` subprocess.** Synthesis is done by the live
  in-session agent, woken by the watcher (`skills/_shared/web_companion/watcher.sh`).
  The server does zero LLM calls — it's a thread store + SSE bus.
- **There is no browser surface.** The server's old PR-diff page was removed
  (commit `697665f`); the IntelliJ plugin is the only review surface. The server
  still snapshots the diff to `diff.patch` (the agent reads it for context) and
  `meta.json` (PR title for the status bar).

Anchor format is `<project-relative-path>:<L|R>:<line>`.

## What works

**Server** (`skills/interactive_review/`, 33 pytest passing)
- Session discovery, threads.json, SSE stream (with deletion + missed-wake
  self-heal), submit, append-only threads with `source_event_id` dedup.

**IntelliJ** (`intellij-plugin-spike/`, 26 tests passing)
- `SpikeDiffExtension` hooks every `TwosideTextDiffViewer`; per-line gutter
  icons on both panes (hover `+`, persistent yellow icon on annotated lines).
- Click → `SynthesisPopup` (JBPopup): shows current synthesis, textarea, Ask
  (⌘/Ctrl-Enter). Esc / click-outside deliberately **do not** dismiss — click ✕.
- Synthesis rendered as HTML in a `JEditorPane`; `[symbol](path:line)` links
  navigate via `OpenFileDescriptor`, backtick identifiers resolve via PSI,
  external URLs open in the system browser.
- `Review Annotations` tool window: one row per anchor (file · side:line ·
  snippet · v#), live search filter, yellow ● on SSE-updated rows.
- `ReviewSessionClient`: seeds from threads.json, SSE with fixed 2s reconnect.
- Status bar widget: `Review: <prRef> ✓` when active, hint when idle.
- `PrDiffOpener`: an "Open PR diff in IDE" button (this exists — the old doc
  said "don't build it"; it was built and works).

## Synthesis model — honest status

`threads.py` is **append-only**; it cannot rewrite a prior message. "Synthesis"
is achieved two soft ways: the clients display only the latest `claude` message
(`threads_bulk` → `latest_synthesis`), and `SKILL.md` instructs the agent to
make each reply self-contained and absorb prior questions. So in practice this
is *anchored chat with last-message-wins display*, leaning on the prompt rather
than enforced structure. For an LLM-driven flow that's a reasonable mechanism;
there's no cheap way to *enforce* rewrite without a separate mutable field, and
that has not been judged worth the schema work.

## Known gaps worth fixing

- **Anchor drift (the real correctness bug).** A snapshot line number is bound
  to live editor coordinates with no re-anchoring. Local edits / rebase silently
  move an annotation to the wrong line. Fix: persist the anchored line's text
  (server schema + `threads_bulk`), re-locate on attach in the plugin, show a
  "stale" marker when the text is gone. ~L, server + plugin.
- **Discovery port fallback.** If `~/.claude/interactive-review/server.json` is
  missing, clients fall back to hardcoded `127.0.0.1:54620` — the *first* of the
  range `54620-54640`, so they hit a dead port if the server grabbed any other.
- **`ReviewSessionClient` hand-rolls JSON with regex** (`jsonField`/
  `parseThreadsBulk`). Brittle if synthesis text contains JSON-ish characters;
  replace with a real parser.

## Decided directions (do not re-litigate)

- **PR-diff editor integration: dropped.** Anchoring onto IntelliJ's native PR
  view is XL effort — the PR content/side mapping is unproven — for marginal
  gain. The plain "Compare with Branch" / file-editor workflow is what delivers
  the value.
- **MCP-enriched synthesis: a spike, not a build.** The in-session agent
  already inherits whatever MCP servers the session has configured, so there is
  nothing to wire in code. "Enrichment" = enable IntelliJ's MCP server +
  Auto-Configure Claude Code (one settings toggle, on the *real* IDE) + a
  one-line `SKILL.md` nudge to prefer find-usages / git-blame / inspections.
  Then A/B the same questions on vs off and keep it only if the answers are
  visibly more grounded. The agent already has `git blame` and ripgrep, so the
  marginal value is unproven — time-box it, don't build anything.

## How to iterate

**IntelliJ** — the plugin is symlinked into
`~/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins/interactive-review-spike`.
```
cd intellij-plugin-spike
./gradlew test            # Java tests
./gradlew prepareSandbox  # build into the symlinked sandbox
osascript -e 'quit app "IntelliJ IDEA"'   # then reopen
```
JDK 25 is hard-coded in `gradle.properties` (`javaVersion=25`) and
`build.gradle.kts` (`JavaLanguageVersion.of(25)`) to match IDEA 2026.1's JBR.
Update both if the JBR changes.

## Using it

1. Terminal (Claude Code session): `/interactive-review <PR>`. It snapshots the
   diff and starts a headless session; no URL to open.
2. Open the same project in IntelliJ. Within ~5 s the status bar
   shows the review is active and gutter icons appear.
3. Click a line, ask a question, get a threaded reply within ~10 s.
4. End the session: type `scrap it` in the terminal.

## Key files

- Server: `skills/interactive_review/server.py` (handlers), `threads.py`
  (append/dedup), `diff.py` (snapshot parsing), `SKILL.md` (the agent's
  answer protocol — this is where synthesis prompting lives).
- IntelliJ: `ReviewSessionClient.java` (transport), `SpikeDiffExtension.java`
  (gutter hooks), `SynthesisPopup.java`, `AnnotationsPanel` (tool window).
