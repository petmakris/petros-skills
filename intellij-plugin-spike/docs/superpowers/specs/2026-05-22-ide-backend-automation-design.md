# IDE backend automation + popup redesign

**Slice A** (backend automation) **+ part of Slice B** (popup visual design).
Combined spec because the popup design depends on the data model that the new
backend exposes (synthesis versions, clickable references, live tool status).

## Goal

Replace the spike's `claude -p` subprocess responder with integration into the
existing `interactive_review` skill, so the IDE plugin becomes another surface
on the same review session that the browser surface already uses. Same
threads, same anchors, same server, same Claude conversation.

Concretely, after this work lands:

- The user runs `/interactive-review <PR>` in a terminal. A session starts.
- The user opens the same PR (or any diff of the same commit) in IDEA. The
  plugin detects the active session via cwd-match and lights up gutter icons.
- The user clicks `+`, asks a question. The plugin POSTs to the same server
  endpoint the browser uses; the existing watcher wakes Claude; Claude
  appends to the per-anchor thread; the server pushes a `thread-changed`
  event via Server-Sent Events; the IDE popup updates in place.
- Asking the same question from the browser produces the same thread update
  visible in the IDE, and vice versa.

## Non-goals

- IDEA-GitHub-plugin integration for auto-detecting PR. Deferred —
  terminal-start session is the only entry point in v1.
- A "Start Review" action inside IDEA. Same reason.
- Cross-block re-synthesis logic. That is **Slice C**, designed separately.
- Hover-preview tooltip on inline references. Deferred to keep v1 shippable.
- Persistence of annotation threads across PR rebases / force-pushes. Out of
  scope; the existing skill snapshots the diff at session-open and warns if
  the head moves.

## Architecture

The IDE plugin becomes a **passive HTTP client** of the existing
`interactive_review` server. No file-based IPC; the `~/spike-events/`
directory and `claude_responder.py` are removed entirely.

```
                +-------------------------------------+
   Terminal:    |  /interactive-review <PR>           |
                +-------------------+-----------------+
                                    |
                                    v
                +-------------------------------------+
                |  interactive_review server          |
                |  - /api/sessions  POST/GET          |
                |  - /api/sessions/<sid>/events POST  |
                |  - /api/sessions/<sid>/stream  SSE  |  <-- new
                |  - thread files on disk             |
                +----+-----------------------+--------+
                     ^                       |
                     | POST event            | SSE
                     | GET sessions          | thread-changed
                     |                       v
                +----+-----------------------+--------+
                |  IDE plugin (Java)                  |
                |  - ReviewSessionClient              |
                |  - SpikeDiffExtension (existing)    |
                |  - SynthesisPopup (refactored)      |
                |  - StatusBarWidget                  |
                +-------------------------------------+
                     ^
                     | wakes via WEBCOMPANION_EVENT
                +----+--------------------------------+
                |  Claude Code session armed via      |
                |  Monitor on watcher.sh              |
                +-------------------------------------+
```

## Lifecycle

1. **Session start** (terminal-only in v1)
   User runs `/interactive-review 1234`. The existing skill creates a server
   session bound to the terminal's `cwd`, arms the watcher, and reports the
   browser URL. Nothing in the IDE yet.

2. **IDE discovery** (polling)
   `ReviewSessionClient` polls `GET /api/sessions?cwd=<idea-project-root>`
   every 5 s. The IDE project root is `Project.getBasePath()`. While no
   matching session exists, the plugin is *dormant* (no `+` icons in any
   diff viewer).

3. **IDE attach** (one-shot per session)
   When the polling response contains a session matching the project root,
   the client transitions to *connecting*:
   - Opens an SSE connection to `GET /api/sessions/<sid>/stream`.
   - Issues `GET /api/sessions/<sid>/threads` to seed the in-memory cache of
     existing per-anchor syntheses.
   - On connection established: transitions to *active*. Gutter icons appear
     across all open diff viewers.

4. **Ask flow** (per question)
   User clicks `+` on a line → `SynthesisPopup` opens, pre-populated from
   the cache for that anchor (or empty placeholder). User types question,
   clicks Ask:
   - Popup POSTs `{anchor, type: "comment", text}` to
     `POST /api/sessions/<sid>/events`. Same payload shape the browser uses.
   - Popup enters a "thinking" state with a progress affordance.
   - Server queues the event; watcher emits `WEBCOMPANION_EVENT`; Claude
     wakes, synthesizes, appends to the thread file.
   - Server detects the thread mutation, pushes
     `{type: "thread-changed", anchor, latest_synthesis, version}` over SSE.
   - Plugin updates the in-memory cache. If the popup for that anchor is
     still open, it re-renders with the new synthesis in place. If the
     popup is closed but the line has a balloon icon, the icon pulses
     until next view.

5. **Session end**
   The `/api/sessions?cwd=...` polling response stops including the session
   (user typed `/exit` or `scrap it`, or watcher exited). Plugin transitions
   to *dormant*. SSE connection closes. Gutter icons disappear.

## Anchor format

The IDE plugin currently produces `<sha>:<L|R>:<line>` because
`SpikeDiffExtension` uses `DiffRequest.getContentTitle()`. The browser uses
`<path>:<L|R>:<line>`. To share threads, we standardize on the browser
format.

The plugin extracts the file path via:

1. If the `DiffRequest` is a `ChangeDiffRequestProducer`-derived request,
   read `getProducer().getChange().getAfterRevision().getFile().getPath()`
   (or `getBeforeRevision()` for the left pane).
2. Else, if the `DiffContent` is a `DocumentContent` with a backing
   `VirtualFile`, use `virtualFile.getPath()` relative to the project base.
3. Else, the viewer is unusable for our purposes (e.g., `Compare Files`
   with raw text). The plugin marks that viewer as *unsupported* — no
   gutter icons, no popups for it. Other viewers in the same window still
   work.

The result is normalized to project-relative POSIX form, matching what the
server stores in `files.json` and what `threads.append_message` keys by.

## UI states

The plugin has four observable states. Each is reflected in the gutter
icons, the popup behavior, and a status-bar widget.

| State          | Gutter icons       | Popup    | Status bar text                                     |
|----------------|--------------------|----------|-----------------------------------------------------|
| Dormant        | None               | n/a      | `Review: idle — /interactive-review <PR>`           |
| Connecting     | None               | n/a      | `Review: connecting…` (with spinner)                |
| Active         | `+` on hover, 💬   | Works    | `Review: PMP-171 ✓` (PR title)                      |
| Disconnected   | Frozen (last seen) | Disabled | `Review: reconnecting…` (with spinner)              |

The status-bar widget is a single click target. Clicking it copies the
appropriate command to clipboard (e.g., `/interactive-review` while dormant;
session SID while active for diagnostics).

### Gutter icon variants

- `+` (hover-only, no annotation yet) — adds new annotation
- `💬` (persistent, annotation exists) — open popup pre-filled
- `💬` with pulse animation (annotation updated since last view) — same as
  above, plus the pulse signals "you should look again." Pulse stops on
  next popup-open or after 60 s. State is tracked per-anchor in
  `ReviewSessionClient`'s in-memory `seenVersions` map.

The pulse animation is the primary signal that the document is *living* —
that other surfaces and future re-synthesis passes can change what you
already annotated.

## Popup design

The popup is the **Reader** variant from the brainstorm session.

- Compact header: anchor path (mono, dim), close button. No big title bar.
- Body: one paragraph of synthesis, ~13 px, 1.7 line-height, generous
  padding.
- Inline references are clickable. See "Reference format" below.
- Footer: one line of muted text — `v3 · 3 questions absorbed · updated 2
  min ago`. Subtle, not the main attraction.
- Input: borderless single-line input under the footer, placeholder text
  `ask a follow-up to refine…`. Submit on `⌘/Ctrl-Enter` or click Ask.
- Below the input while a synthesis is in flight: a small `[spinner]
  Reading callers of forDashboard…` row indicating tool activity. Hidden
  when idle.

### Reference format (markdown-style)

Claude's synthesis uses standard markdown link syntax for clickable
references:

```
[proposalListService](src/main/.../AdvisorDashboardController.java:37)
[PMP-171](https://your-jira/browse/PMP-171)
[forDashboard(dto)](src/main/.../ProposalListService.java:18)
```

The plugin renders the popup body via a `JEditorPane` with HTML content,
parses markdown links with a small regex, and installs a `HyperlinkListener`
that:

- For path-shaped link targets (`path[:line]`): resolves to a `VirtualFile`
  in the project and calls
  `FileEditorManager.openFile(file, true)` (plus `OpenFileDescriptor` for
  the line when present).
- For absolute URLs: opens in default browser via `BrowserUtil.browse`.

Code-symbol references render with `monospace + faint orange background +
dashed underline`; ticket/URL references render with `purplish color +
no monospace + dashed underline`. The two visual treatments make the link
type obvious at a glance.

Hover preview (tooltip showing 2–3 lines of target context) is **deferred
to v2**. v1 just navigates on click.

### Synthesis prompt update

The existing `interactive_review` skill's response style guide says
*"short, code-aware answer in markdown: 2-4 sentences typically"* and
treats each reply as a new appended message. For Slice A we make a small,
targeted change so that the *latest* Claude message functions as a
self-contained synthesis the IDE can render verbatim.

Two clauses to add to `interactive_review/SKILL.md` under "Response style
guide":

> **Self-contained synthesis.** Each reply should stand on its own as the
> answer to *all* questions asked so far on this anchor, not just the
> latest one. Absorb prior questions; do not assume the reader has scrolled
> back. The IDE surface renders only your most recent reply.

> **Link references inline.** When you reference a specific file, method,
> or symbol from the code, render it as a markdown link whose target is the
> project-relative file path optionally followed by `:line`
> (e.g., `[forDashboard](src/.../ProposalListService.java:18)`). For ticket
> IDs and external URLs, use a normal markdown link with the absolute URL.

Full cross-block re-synthesis (the "re-pass on other anchors when new
evidence arrives" requirement) is **Slice C** — not in scope here. Slice C
will likely require a richer thread schema; this spec deliberately stops at
"latest claude message = synthesis" to keep Slice A small and shippable.

## Components

### New server endpoints (Python, in `interactive_review/server.py`)

- `GET /api/sessions?cwd=<path>` — list active sessions whose `cwd` exactly
  matches. Used by the IDE for discovery. Response: array of
  `{sid, pr_ref, title, state_dir, started_at}`.
- `GET /api/sessions/<sid>/threads` — bulk-fetch all threads for a session.
  Response: `{anchor → {latest_synthesis, version, updated_at}}`. Used for
  cache seed.
- `GET /api/sessions/<sid>/stream` — SSE endpoint. Emits one event per
  thread mutation. Event format:
  ```
  event: thread-changed
  data: {"anchor": "...", "latest_synthesis": "...", "version": 3,
         "updated_at": 1779405469}
  ```
  Implemented as a long-polling generator backed by a per-session
  `threading.Event` set whenever `append_message` runs.

  **Derivation of `latest_synthesis`**: the existing thread schema in
  `threads.py` is append-only — `{anchor, version, messages: [...]}`. There
  is no separate "synthesis" field. The server derives `latest_synthesis`
  as the `text` of the most-recent message whose `role == "claude"`. This
  is a *read-only projection*; nothing in the underlying storage changes
  for Slice A.

The existing `POST /api/sessions/<sid>/events` endpoint is unchanged.

### New plugin components (Java)

- `ReviewSessionClient`
  - Discovers sessions via cwd polling.
  - Manages SSE connection lifecycle (connect, retry with backoff,
    disconnect cleanup).
  - Maintains in-memory `Map<String anchor, ThreadState>` cache.
  - Exposes listener interface for `SynthesisPopup` and `StatusBarWidget`.
- `StatusBarWidget`
  - Implements `StatusBarWidget` / `StatusBarWidgetFactory`.
  - Renders the four-state text + spinner.
- `MarkdownLinkRenderer`
  - Parses markdown links in synthesis text.
  - Produces HTML for `JEditorPane`.
  - Resolves `path[:line]` link targets to `OpenFileDescriptor`.

### Refactored plugin components

- `SpikeDiffExtension`
  - Anchor extraction switched from `getContentTitle()` to path-based
    extraction (see "Anchor format" above).
  - On viewer open: registers viewer with `ReviewSessionClient`; renders
    gutter icons based on cached thread state per anchor.
  - Listens for `ReviewSessionClient` updates to refresh gutter icons
    in-place.
- `SynthesisPopup`
  - No longer reads/writes `~/spike-events/`. Talks to
    `ReviewSessionClient`.
  - Renders synthesis via `MarkdownLinkRenderer`.
  - Adds tool-activity row (driven by future server-side enrichment of
    `thread-changed` events; for v1, just show a static spinner while
    "thinking").

### Removed

- `scripts/claude_responder.py`
- `scripts/echo_responder.py`
- `~/spike-events/` directory usage
- Any references in `HANDOFF.md` to the file-based IPC

## Failure modes

| Failure                                       | Behavior                                                                                                            |
|-----------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| Server not running                            | Polling fails. Stay dormant. Status bar: `Review: server unreachable`. No retry storm — log once per minute.        |
| Server reachable, no matching session         | Stay dormant. Silent.                                                                                               |
| SSE connection drops                          | Retry with exponential backoff (1s, 2s, 4s, 8s, capped at 30s). After 30 s of continuous failure, drop to dormant. |
| POST event fails                              | Popup shows inline `Failed to submit — Retry` button. Question text preserved.                                      |
| Synthesis times out server-side               | Same as `claude -p` timeout today: the server's existing watcher writes a `[timeout]` reply to the thread file.    |
| Diff viewer with no recoverable path          | That viewer is unsupported. Other viewers in the same window work normally.                                         |
| Two sessions match the same cwd               | Attach to the most-recently-started. Status bar shows the PR title; switcher menu (deferred).                       |
| Anchor format mismatch (legacy threads)       | Discovered on cache seed: any thread keyed by `<sha>:...` is ignored. Logged once per session.                      |

## Testing plan

### Server side (Python, pytest)

- New test: `test_sessions_by_cwd_returns_active_only`
- New test: `test_stream_emits_thread_changed_within_1s`
- New test: `test_threads_bulk_endpoint_returns_all_anchors`

### Plugin side (Java, JUnit)

- New test: `ReviewSessionClientTest` — exercises discover/connect/SSE
  message/disconnect/reconnect against a `FakeReviewServer` HTTP fixture.
- New test: `MarkdownLinkRendererTest` — parses sample syntheses, asserts
  rendered HTML and resolved navigation targets.
- Existing tests in `ThreadsService` / `EventBridge` are mostly deleted
  (those components either go away or are gutted).

### End-to-end smoke (manual, documented in HANDOFF.md)

1. Terminal: `/interactive-review <PR>`. Wait for browser URL.
2. IDEA: open the same project. Within ~5 s, gutter icons should appear in
   any open PR diff. Status bar should show `Review: <PR-title> ✓`.
3. Click a line in IDE, ask a question. Within ~10 s, popup re-renders with
   synthesis.
4. Open the browser URL. The IDE-asked question appears as a thread on
   that anchor.
5. Ask a follow-up *in the browser*. The IDE popup auto-refreshes (still
   open) or the gutter icon pulses (if popup is closed). Confirm pulse.
6. Terminal: `scrap it`. Gutter icons disappear from IDEA within ~5 s.

## Iteration mechanism

Already in place: the symlink from real IDEA's plugin dir to
`build/.intellijPlatform/sandbox/.../plugins/interactive-review-spike/`.
After any code change:

```
./gradlew prepareSandbox && osascript -e 'quit app "IntelliJ IDEA"'
# reopen IDEA manually
```

(A future improvement: a `restartIde.sh` script that quits + relaunches via
`osascript` + `open`. Deferred.)
