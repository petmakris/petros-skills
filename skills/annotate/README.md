# annotate

Render Claude responses as an interactive web page with per-block annotation.

## What it does

Long responses (multi-step plans, analyses, lists of findings) get pushed to a browser page where the user clicks any block to leave a comment. Claude updates that block in place when it responds — no page reload, no re-pushing the whole document.

## How it works

**User workflow:**
1. User sees their response split into blocks on a web page.
2. Click any block, leave a comment, hit Submit.
3. Claude wakes up, rewrites that block, the page auto-refreshes it in place.
4. Repeat per block, in any order.
5. Click "Done" to finish the session.

**Technical flow:**
- Response blocks are stored in `blocks.json` (the canonical document).
- User comments trigger a per-block submit, not a whole-document submit.
- A persistent watcher polls for events and notifies Claude of each submission.
- Claude reads the event, updates the affected block in `blocks.json`, and the page auto-refreshes via polling.

## Architecture

- **Server:** Shared web_companion library at `skills/_shared/web_companion/` — single long-lived server serving all annotation sessions.
- **Client:** Static HTML/JS page (per session) that polls for block updates and renders with annotation UI.
- **Session data:** `meta.json` (session metadata), `blocks.json` (current blocks), and event/state directories for watcher coordination.
- **Watcher:** Long-lived background process (one per session) that monitors the event directory and emits notifications on comment submit, Done, or cancellation.

## Files

- `SKILL.md` — Full skill definition and implementation guide (API contracts, event flow, all edge cases).
- `server.py` — Thin wrapper exposing `/api/sessions` to create a new session.
- `blocks.py` — Block document model and update logic.
- `static/` — HTML/JS/CSS for the browser page.
- `ensure_server.sh` — Idempotent startup script (delegates to shared library).
- `tests/` — Unit and integration tests.

## Trigger modes

- **Forward:** Claude-initiated. Compose response as blocks, write `blocks.json`, announce the URL, arm the watcher.
- **Postmortem:** User-initiated. User says `/annotate` after a response; Claude pushes the prior terminal response to the page.
- **Armed:** First postmortem invocation with no prior content; Claude arms the skill for the session and routes subsequent long responses forward.
- **Event handling:** Watcher emits an event on submit/Done/cancel; Claude wakes up, applies the block-rewrite contract, updates `blocks.json`, acks the event.

See SKILL.md for full details.
