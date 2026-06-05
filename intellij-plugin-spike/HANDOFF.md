# Interactive Review — IntelliJ Plugin Spike

Handoff document for resuming work in a fresh Claude Code session. Start a new
session in `~/projects/petros-skills/intellij-plugin-spike/`.

## What we're building

An IntelliJ IDEA plugin that lets a developer annotate any line of any diff
viewer with a question, get a synthesized answer from Claude inline, and ask
follow-up questions that **refine the answer** rather than appending to a chat
transcript.

The end target: hover any changed line in a PR diff → click `+` → ask "why is
this private?" → see a synthesized paragraph in a popup. Click the same line
again → ask "and why camelCase?" → the same paragraph is rewritten to absorb
both questions.

## Why this is different from the Claude Code sidechat

The sidechat is a transcript. To understand what was discussed about a piece
of code you scroll history.

This plugin produces an **artifact** per code line: one evolving paragraph
that gets richer with each question. A teammate joining the review later
reads one up-to-date paragraph per annotated line, not a chat to scroll.

Two structural differences:

- **Many parallel, anchored threads.** Each `file:side:line` owns its own
  conversation, independent of others. The sidechat is one linear
  conversation.
- **Synthesis, not response.** The prompt template tells Claude to *replace*
  the prior paragraph with one that combines all prior questions and the new
  one into a single coherent paragraph. Not "answer this question," but
  "refine this annotation."

If the synthesis model doesn't materialize as something visibly different
from chat, the plugin loses its reason to exist — falls back to "anchored
chat" which the sidechat already covers.

## Current architecture

The plugin is now a **passive surface** on top of the existing
`interactive_review` server (`skills/interactive_review/`). All Claude
synthesis happens in the Claude Code session that started the review;
the plugin discovers that session by cwd and shares its threads with the
browser surface.

```
                     ┌──────────────────────────────────┐
   Terminal:         │  /interactive-review <PR>        │
                     └──────────────┬───────────────────┘
                                    │
                                    ▼
                     ┌──────────────────────────────────┐
                     │  interactive_review server       │
                     │  - GET /api/sessions?cwd=        │
                     │  - GET /s/<sid>/threads.json     │
                     │  - GET /s/<sid>/stream  (SSE)    │
                     │  - POST /s/<sid>/api/submit      │
                     └────────┬────────────────┬────────┘
                              ▲                ▼ SSE thread-changed
                              │ POST submit    │
                              │                │
                     ┌────────┴────────────────┴────────┐
                     │  IDE plugin                       │
                     │  - ReviewSessionService           │
                     │      └ ReviewSessionClient        │
                     │  - SpikeDiffExtension             │
                     │  - SynthesisPopup (JEditorPane)   │
                     │  - ReviewStatusBarWidget          │
                     └───────────────────────────────────┘
```

Anchor format is `<project-relative-path>:<L|R>:<line>` — shared with the
browser, so threads created from either surface are interchangeable.

Synthesis is rendered as HTML in a `JEditorPane`. Code references in the
synthesis text use markdown link syntax with `path[:line]` targets; clicks
navigate via `OpenFileDescriptor`. External URLs open in the system
browser via `BrowserUtil`.

See the design spec and implementation plan for the full picture:
- `docs/superpowers/specs/2026-05-22-ide-backend-automation-design.md`
- `docs/superpowers/plans/2026-05-22-ide-backend-automation.md`

## What works

- DiffExtension hooks every `TwosideTextDiffViewer` IntelliJ opens (Blank
  Diff, Compare Files, Compare with Branch, PR review, VCS log diff — all of
  them).
- Per-line gutter icons on both panes. Hover-only: `+` appears only on
  the line under the mouse; lines with existing annotations show a
  persistent balloon icon. Tooltip on icon: `Comment on <file>:<L|R>:<line>`.
- Click `+` → JBPopup anchored to the editor. Shows current synthesis at
  top, textarea below, Ask button (Cmd/Ctrl-Enter also submits).
- Submit → HTTP POST to the server → popup shows "thinking…" → server
  streams SSE thread-changed events → popup re-renders with new synthesis.
- ReviewSessionService discovers active sessions via `~/.claude/interactive-review/server.json`
  and fetches threads/streams from there.
- Status bar widget shows `Review: <PR-title> ✓` when a session is active,
  `Review: idle` otherwise.
- HTML rendering of synthesis in `JEditorPane` with clickable code references
  (`[symbol](path:line)` markdown) and external links.

## What's pending

- **PR loader.** Punted intentionally. IntelliJ's built-in `Git → Branches →
  master → Compare with Current` does this with no plugin code. Don't build
  a custom action unless that workflow proves insufficient.
- **Persistence.** Annotations live in the server (session-lifetime). The IDE
  fetches them on each session start via HTTP. Restarting the IDE without
  ending the session preserves annotations.
- **Inline panel instead of JBPopup.** The popup dismisses on Esc and on
  click-outside. For a more PR-review-shaped UX, embed the
  synthesis between the diff lines via `EditorEmbeddedComponentManager`.
  Tried and deferred — JBPopup proved good enough for v1.
- **Annotated lines should show synthesis even when popup is closed.** Some
  visual cue beyond the balloon icon — maybe an inlay between the lines, or
  a hover-tooltip showing the first 100 chars.

## Known issues and areas to test

1. **HTTP discovery on plugin startup.** If `~/.claude/interactive-review/server.json`
   doesn't exist (no active session), the plugin falls back to hardcoded
   `http://127.0.0.1:54620`. It will fail until a session is started in the
   terminal. That's expected.
2. **Anchor stability across diff re-opens.** If you close the diff viewer
   and reopen the same comparison, are anchors the same string? They should
   be: `<project-relative-path>:<L|R>:<line>`. Test across re-opens.
3. **Multi-file PRs.** When you open per-file diffs through `Compare with
   Branch`, each file opens a separate viewer. Anchors include the file path
   so they should be distinct. Verify switching between files preserves
   the right syntheses.
4. **SSE connection drops.** If the IDE is idle and the server restarts,
   the SSE stream breaks. The next synthesis request will establish a new
   connection. If connectivity is flaky, add reconnect-with-backoff logic
   to `ReviewSessionClient`.

## How to iterate

The plugin lives at `~/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins/interactive-review-spike` as a symlink to the gradle sandbox output. After any code change:

```
cd ~/projects/petros-skills/intellij-plugin-spike
./gradlew prepareSandbox
osascript -e 'quit app "IntelliJ IDEA"'
# reopen IDEA manually (or use `open -a "IntelliJ IDEA"`)
```

To use the plugin:

1. In a terminal Claude Code session, start a review:
   `/interactive-review <PR>`
   (The server URL + browser link print to the terminal.)

2. Open the same project in IDEA. Within ~5 s the bottom status bar shows `Review: <PR-title> ✓` and gutter icons appear on diff viewers.

3. Click `+` on any diff line → popup opens. Type a question, click Ask (or ⌘/Ctrl+Enter). The synthesis arrives via SSE within a few seconds.

4. Same PR open in the browser? Annotations there flow into the IDE popup automatically (and vice versa) — one shared thread per anchor.

## End-to-end smoke recipe

1. Terminal: `/interactive-review <PR>` (in a Claude Code session). Wait for "PR diff in browser → http://localhost:....".
2. IDEA: open the same project. Within ~5 s, gutter icons should appear in any open PR diff. Status bar should show `Review: <PR-title> ✓`.
3. Click `+` on a line in IDE. Type a question. Click Ask. Within ~10 s, popup re-renders with the synthesis (clickable inline references).
4. Open the browser URL. Same anchor — the IDE-asked thread is visible there.
5. Ask a follow-up in the browser. The IDE popup (still open) auto-refreshes; or if closed, the gutter icon's balloon indicates the annotation exists.
6. Click a `[symbol](path:line)` link in the synthesis. IDE navigates to that file + line.
7. End the session: in the terminal Claude session, type `scrap it`. Within ~5 s, IDE gutter icons disappear; status bar shows `Review: idle`.

### Navigation polish smoke (after the backend smoke passes)

8. Open the `Review Annotations` tool window on the right side. You should see one row per annotated line, with file (last segment) · :side:line · 2-line snippet · `v<N>`.
9. Type a substring of a file name into the search box at the top. Verify the list filters live.
10. Click a row that's NOT the currently-open annotation. IDEA should jump to that file/line AND open the popup on it.
11. From the browser surface, ask a follow-up question on a different annotation. The IDE panel should add a yellow `●` next to that row within ~5 s (SSE event). Click the row to clear the dot.
12. In an open popup synthesis, hover any backtick code identifier — should show dashed underline. Click → IDE should navigate to that symbol's declaration (if it exists in the project).
13. Click a backtick word that ISN'T a symbol (e.g. `null` or `POST /foo`). Should be a silent no-op (no error, no popup).

## Gotchas / things to know

- **JDK path hard-coded** in `gradle.properties`. As of 2026-05 the plugin
  targets JDK 25 to match the JBR bundled with IntelliJ 2026.1
  (`/opt/homebrew/Cellar/openjdk@25/25.0.3/...`). If brew updates `openjdk@25`
  or IDEA moves to a new JBR, both `javaVersion=` in `gradle.properties` and
  `JavaLanguageVersion.of(...)` in `build.gradle.kts` must be updated. To
  check what JBR your installed IDEA ships with:
  `"$IDEA_APP/Contents/jbr/Contents/Home/bin/java" -version`.
- **Plugin attaches highlighters to every line of every diff viewer that
  opens.** Performance is fine for normal PRs but unverified on huge
  (>5000-line) diffs. If it lags, add a line-count cap in
  `SpikeDiffExtension.attachAllLines`.
- **Server discovery via `~/.claude/interactive-review/server.json`**: if that file doesn't exist (no session has been started yet) the plugin will hit `http://127.0.0.1:54620` as a hard-coded default and most likely fail. That's expected — without an active session there's nothing to talk to.

## Phase 2: MCP-enriched synthesis

IntelliJ 2026.1 ships with a built-in MCP server (`Settings → Tools →
MCP Server`) that exposes IDE capabilities — open files, project
structure, find usages, run inspections, git blame — to *external* MCP
clients (Claude Code, Claude Desktop, VS Code, Codex). Crucially this is
the opposite direction from what our plugin does: our plugin embeds in
the IDE and calls *out* to Claude; the MCP server lets Claude (running
externally) call *in*.

That asymmetry is exactly the leverage we want. Today our `claude -p`
subprocess sees only `{anchor, question, prior_synthesis, history}` and
can read code only via shell tools. With Claude Code wired to the IDE's
MCP server, the *same* `claude -p` invocation can navigate callers,
inspect tests, run inspections, read git blame — all from inside the
synthesis call. Annotations stop being "what the question implies" and
start being "what the question implies plus what Claude found by
investigating from the IDE."

### Sequencing (do not parallelize — order matters for debugging)

1. **Tune the synthesis prompt on the bare setup.** Confirm the prompt
   genuinely rewrites the paragraph rather than appending. If syntheses
   look chat-shaped here, MCP won't save us — it would just give chat
   richer context.
2. **Auto-Configure Claude Code's MCP integration.** In the sandbox IDE:
   `Settings → Tools → MCP Server → Enable MCP Server` (check the box),
   then click **Auto-Configure** next to **Claude Code**. This writes an
   MCP server entry into Claude Code's config so any `claude -p`
   invocation discovers the IDE's tools. Verify with `claude mcp list`
   in a terminal — should show an `intellij` (or similarly-named) server
   pointing at `127.0.0.1:<port>`.
3. **Update the synthesis prompt to invite tool use.** Add something
   like: "You have IDE tools available via MCP — feel free to inspect
   callers, definitions, tests, or git blame for the anchor file before
   synthesizing. Prefer evidence from the code over speculation."
4. **A/B-compare syntheses.** Ask the same 3 questions on the same
   anchor with MCP on vs off. If MCP doesn't visibly improve depth or
   accuracy, the cost (latency, debug surface) isn't justified.

### Gotchas to expect when wiring MCP

- The MCP server listens on a *specific* IDE instance. The sandbox IDE
  (`./gradlew runIde`) and your real IDEA are different instances on
  different ports. Auto-Configure has to be run in the sandbox if the
  responder runs against the sandbox.
- Claude Code's MCP config is global to the user. If you Auto-Configure
  the sandbox and then close it, the config still points at a dead port
  until you re-run Auto-Configure on whichever IDE you actually want
  tools from.
- Latency goes up. `claude -p` with tool use can take 30s+ on a
  multi-step investigation. The popup's "thinking…" copy should be
  honest about this.
- Tool calls are visible in the responder's stdout (Claude Code prints
  tool invocations). That's a free debugging window — leave it
  unstifled.

## Where to start in the new session

Read this file, then:

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
ls src/main/java/com/petros/ireview/
./gradlew test                      # confirm Java tests pass
./gradlew prepareSandbox            # build into the symlinked sandbox
```

For server-side work, the entry point is `skills/interactive_review/server.py`. For plugin work, the centerpiece is `ReviewSessionClient.java`.

The Phase 2 MCP enrichment direction (described below) is still the next big step once the v1 architecture has time to settle.
