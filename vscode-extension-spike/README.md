# interactive-review — VS Code spike

VS Code client for the `interactive_review` server. Sibling to
`intellij-plugin-spike/`. Both surfaces talk to the same Python server
(`skills/interactive_review/`) — no server changes.

## What it does

- Discovers an active `interactive_review` session for the current workspace
  folder (polls `GET /api/sessions?cwd=<folder>` every 5 s).
- Opens an SSE stream for that session and keeps a local cache of
  per-anchor syntheses.
- Renders each anchor as a VS Code Comment thread on the relevant file line.
  Click the gutter "+" to start a new thread; the existing bubble shows
  Claude's latest synthesis.
- Side panel ("Interactive Review" in the activity bar) lists every
  annotated line with a one-click open + inline trash to delete.
- Status-bar item shows the current session state.

## Layout

```
src/
  extension.ts          activate/deactivate, command registration
  sessionClient.ts      HTTP + SSE client (no deps; uses node:http)
  sse.ts                pure SSE parser + line buffer (testable)
  json.ts               regex-based JSON extractors (matches Java side)
  types.ts              shared types + parseAnchor
  markdown.ts           synthesis → MarkdownString rewriter (command: URIs)
  commentsController.ts vscode.comments thread management
  annotationsView.ts    TreeDataProvider for the side panel
  statusBar.ts          StatusBarItem
test/
  *.test.ts             node:test suites for pure modules
scripts/
  version.js            stamps package.json with 0.1.<git-rev-list-count>
```

## Build

```sh
cd vscode-extension-spike
npm install
npm run compile         # tsc → out/
npm test                # node:test suite
```

## Smoke test (manual)

1. `cd vscode-extension-spike && npm install && npm run compile`
2. Open the `vscode-extension-spike/` folder in VS Code.
3. Press **F5** → an "Extension Development Host" window launches with this
   extension active.
4. In the dev host, open a folder that has a running `interactive_review`
   session (manage that from another Claude Code session via
   `/interactive-review <PR>`). Within ~5 s the status bar should flip from
   `Review · idle` to `Review · <pr_ref>`.
5. Open a file that has anchors in the current session → comment-thread
   bubbles appear in the gutter for those lines.
6. Open the side panel ("Interactive Review" icon in the activity bar). It
   lists every annotation in the cache.
7. Click a side-panel row → opens the file at that line and expands the
   comment thread inline.
8. Type a follow-up in the thread reply box, click **Ask Claude** → the
   thread label becomes `v<N> · thinking…` until Claude's reply arrives via
   SSE.
9. Hover a side-panel row → inline trash icon appears → click → row
   disappears (after the SSE `thread-deleted` round-trips).

## Known limitations / deferred

- **PR-diff editor integration.** Same hard problem as IntelliJ side: VS
  Code's PR-diff editor (provided by the GitHub Pull Requests extension) is
  not the same surface as a regular text editor. v1 attaches comments to
  regular file editors only. Working-copy paths must match anchor paths.
- **Side-panel × is always visible, not hover-revealed.** VS Code's
  TreeView doesn't support hover-only inline icons. We render the trash
  icon inline at all times (gated to non-pending rows).
- **Delete spinner.** No animated spinner per row; we suppress the trash
  while the SSE `thread-deleted` is in flight (pending state).
- **Comment-thread author label.** The synthesis comment uses
  `author: { name: "Claude · v<N>" }` so the version is always visible. No
  custom avatar.
- **Reply submit.** Driven by a `comments/commentThread/context` menu
  contribution → `ireview.reply` command. The default Enter-to-submit
  binding lives in the editor input itself.

## Version scheme

`package.json#version = "0.1." + git rev-list --count HEAD`. Stamped by
`scripts/version.js` (run via `npm run prebuild`).
