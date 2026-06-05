# interactive-review

Per-line threaded Q&A on a GitHub PR diff, rendered in the browser.

## What it does

Fetches a PR diff and renders it as an interactive page where every changed line is clickable. The user clicks a line, types a question, and Claude's answer appears as an inline threaded reply. No code is modified — the view is read-only. The goal is *understanding* a PR through targeted conversation, not reviewing it comprehensively in one pass.

## How to invoke

```
/interactive-review <PR>
```

where `<PR>` is:

- A PR number (`123`) — uses the current repo.
- A full GitHub URL (`https://github.com/org/repo/pull/123`).
- A local branch name (`feature/foo`) — pre-PR review against `main`.

## How it works

On invocation, the skill fetches the PR diff via `gh pr diff` and `gh pr view`, snapshots it into a session directory, and opens a browser page. A persistent background watcher monitors for events. When the user submits a question on a line, the watcher wakes Claude with the anchor (file, side, line number) and the question text. Claude reads the diff context, composes a short answer, and appends it to the line's thread file. The page polls for new thread entries and refreshes the thread in place — no page reload, no full-document re-push.

Each question is one wake-up; Claude answers that question and goes back to sleep. The user iterates by clicking more lines or asking follow-ups on existing threads.

## Architecture

- **Server:** Shared `web_companion` library at `skills/_shared/web_companion/` — one long-lived server instance serves all sessions.
- **Session data:** `diff.patch` (snapshotted diff), `files.json` (changed file list), `meta.json` (PR metadata), and `threads/<anchor>.json` (per-line conversation threads).
- **Watcher:** Long-lived background process (one per session) that monitors the events directory and emits `WEBCOMPANION_EVENT` / `WEBCOMPANION_FINISHED` / `WEBCOMPANION_CANCELLED` notifications.
- **Threads:** `threads.py` — append-only thread model with `source_event_id`-based dedup.
- **Diff parsing:** `diff.py` — hunk extraction and anchor resolution.

## Files

- `SKILL.md` — Full skill definition: invocation, session creation, watcher protocol, Mode D event handling, edge cases.
- `server.py` — Thin wrapper exposing `/api/sessions`; `create_session_extra` runs `gh pr diff`/`gh pr view`.
- `diff.py` — Diff parsing and hunk utilities.
- `threads.py` — Thread append logic with atomic write and dedup.
- `ensure_server.sh` — Idempotent server startup (delegates to shared library).
- `static/` — Browser page HTML/JS/CSS.
- `tests/` — Unit and integration tests.

See `SKILL.md` for the full protocol.
