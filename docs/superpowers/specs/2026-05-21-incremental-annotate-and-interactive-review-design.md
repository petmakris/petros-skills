# Incremental annotate + interactive-review — design

**Date:** 2026-05-21
**Author:** Petros (with Claude)
**Status:** Approved for implementation planning

## Motivation

The existing `annotate` skill solves a real problem — long Claude responses get a browsable surface so the user can leave inline comments instead of pasting into a text editor. But its protocol is one-shot per session: the user must read the whole document, comment everywhere, then click one Submit. That's still a wall to climb. If the user doesn't understand block 2, there's no point reading block 3, but the current flow forces them to either submit early (and lose the rest of the round) or push through cognitive load before getting clarification.

The fix: **per-block submit + partial re-render in place**. Submit one comment → Claude wakes → that one block is rewritten → page auto-refreshes that section. The user dwells on a piece until they understand it, then moves on. The terminal stays out of the loop; everything happens in the browser.

A natural sibling falls out: the same incremental shape applied to a PR diff. The user opens a GitHub-style diff view, asks questions per line, Claude answers inline. Code is immutable so the conversation accumulates as threaded replies — opposite of annotate, where the document itself gets curated.

This spec covers both skills together because they share most of their infrastructure.

## Scope

In scope:
- Upgrade `annotate` to per-block submit + partial re-render. Replaces the current whole-document submit flow (no compatibility shim).
- New skill `interactive-review`: per-line threaded Q&A on a snapshotted PR diff.
- Extract shared `skills/_shared/web_companion/` library hosting HTTP server, sessions, watcher protocol, event queue, paste-image upload, static serving.
- Two ports, two processes, two `~/.claude/<skill>/server.json` files.

Out of scope (defer):
- Re-rebasing threads onto an updated PR diff.
- Server-side code rewriting in `interactive-review` (the skill is for *understanding* PRs, not editing them).
- Multi-user / multi-machine collaboration on the same session.
- Markdown rendering on the server side — stays client via `markdown-it`.

## Architecture

**Approach 2** of the three considered: shared library, two thin per-skill server modules, two processes. Chosen over the "one process, two modes" alternative because:

- The terminal-state model differs between the skills (annotate-incremental has no terminal state per submit; review's threads are append-forever; today's annotate had a one-shot terminal state). Putting both in one process means five call sites become mode-conditional — exactly the smell we want to avoid.
- Single-process blast radius is real: a regression in interactive-review shouldn't take annotate down.
- "Concurrent coexistence" is satisfied equally by disjoint port ranges + per-skill `server.json` paths.

The ~450:150 line ratio of shared:divergent code in today's `server.py` strongly favors extraction.

### Layout

```
skills/_shared/web_companion/
  __init__.py
  server.py          ← run(skill_name, port_range, handlers, static_dirs)
  sessions.py        ← Registry; today's lines 67–163
  events.py          ← per-session event queue, atomic writes, ack markers
  uploads.py         ← paste-image; today's lines 613–653
  static_serve.py    ← path-traversal-safe; today's lines 460–481
  handlers.py        ← Protocol skills implement
  ensure_server.sh   ← parameterized template (skill, port range, module path)
  static/
    core.js          ← polling, version-vector diff, composer, submit, uploads
    core.css         ← shared theme, comment cards, buttons, fonts
    markdown-it.min.js
    fonts/
  tests/

skills/annotate/
  __init__.py
  server.py          ← ~80–120 lines: registers handlers, owns blocks.json semantics
  ensure_server.sh   ← 4-line wrapper over shared
  SKILL.md
  static/
    annotate.js      ← block render, span-highlight, block-action menu
    annotate.css     ← annotate-specific
  tests/

skills/interactive-review/
  __init__.py
  server.py          ← ~80–120 lines: registers handlers, owns threads + diff fetch
  ensure_server.sh   ← 4-line wrapper over shared
  SKILL.md
  static/
    review.js        ← diff render, line-click composer, thread bubbles
    review.css       ← diff styles
  tests/
```

Port ranges (disjoint): annotate `54580–54600`, interactive-review `54620–54640`.
Server-info paths (disjoint): `~/.claude/annotate/server.json`, `~/.claude/interactive-review/server.json`.

### Shared library surface

```python
# server.py
def run(skill_name: str, port_range: range, handlers: HandlersProtocol,
        static_dirs: list[Path]) -> None: ...

# sessions.py
class Registry:
    def make_sid(self) -> str: ...
    def register(self, sid: str, dirs: dict[str, Path]) -> None: ...
    def lookup(self, sid: str) -> dict[str, Path] | None: ...
    def persist(self) -> None: ...
    def rehydrate(self) -> None: ...

# events.py
def append(events_dir: Path, payload: dict) -> str:  # returns event_id
    ...
def heartbeat(state_dir: Path) -> None: ...  # call from watcher

# uploads.py
def handle(handler: BaseHTTPRequestHandler, dirs: dict[str, Path]) -> None: ...

# static_serve.py
def serve(handler: BaseHTTPRequestHandler, name: str, dirs: list[Path]) -> None: ...

# handlers.py
class HandlersProtocol(Protocol):
    def serve_root(self, h, dirs): ...
    def serve_data(self, h, dirs, query): ...     # /raw, /thread, etc.
    def handle_submit(self, h, dirs, payload): ...
    def serve_poll(self, h, dirs): ...
    def create_session_extra(self, payload, dirs) -> dict | None: ...
```

The shared `render_page(title, head_assets, body_html)` template injects `core.css` + `core.js` and yields a complete HTML document. Skill renderers call it.

## Event-queue + watcher protocol

This is the load-bearing safety surface. Every submit is a discrete event on disk; the watcher emits one notification per event; Claude wakes, applies, acks.

### Per-session directory layout

```
state_dir/
  events/                ← pending events, one file per submit
    <monotonic_ns>.json
  consumed/              ← events Claude has finished processing
    <monotonic_ns>.json
    <monotonic_ns>.ack   ← empty marker; Claude writes after disk write
  watcher_heartbeat      ← updated by watcher each loop iteration
  finished               ← marker; user clicked Done. Watcher exits.
  cancelled              ← marker; user cancelled from terminal. Watcher exits.
```

Plus skill-specific files:
- annotate: `blocks.json` (canonical doc model)
- interactive-review: `meta.json`, `diff.patch`, `files.json`, `threads/<anchor>.json`

### Submit flow (server side)

1. Client POSTs `/api/submit` with `{block_id | anchor, type, text, selected_text?, images?[]}`.
2. Server validates payload.
3. Server mints `event_id = monotonic_ns()`.
4. Server writes `events/<event_id>.json` atomically (tmp file → rename).
5. Server responds `202 {event_id, status: "queued"}`. Client UI shows "Claude is updating…" on the block / line.

### Watcher script (one persistent Monitor per session)

```bash
DRAIN=1
while [ ! -f "$STATE_DIR/finished" ] && [ ! -f "$STATE_DIR/cancelled" ]; do
  date +%s > "$STATE_DIR/watcher_heartbeat"
  evt=$(ls "$EVENTS_DIR"/*.json 2>/dev/null | sort | head -n1)
  if [ -n "$evt" ]; then
    id=$(basename "$evt" .json)
    if [ ! -f "$CONSUMED_DIR/$id.ack" ]; then
      printf 'WEBCOMPANION_EVENT skill=%s sid=%s event_id=%s\n' "$SKILL" "$SID" "$id"
      printf '%s\n' '---payload---'
      cat "$evt"
      printf '%s\n' '---end---'
      for _ in $(seq 1 1800); do
        [ -f "$CONSUMED_DIR/$id.ack" ] && break
        sleep 1
      done
      if [ ! -f "$CONSUMED_DIR/$id.ack" ]; then
        touch "$CONSUMED_DIR/$id.failed"
      fi
    fi
    mv "$evt" "$CONSUMED_DIR/$id.json"
  else
    sleep 1
  fi
done
if [ -f "$STATE_DIR/cancelled" ]; then
  printf 'WEBCOMPANION_CANCELLED skill=%s sid=%s\n' "$SKILL" "$SID"
else
  printf 'WEBCOMPANION_FINISHED skill=%s sid=%s\n' "$SKILL" "$SID"
fi
```

The Monitor tool's "each stdout line is a notification" contract means each `printf 'WEBCOMPANION_EVENT…\n'` wakes Claude once. The persistent Monitor stays alive across many events for the session's lifetime.

### Wake-up flow (Claude side, Mode D-equivalent)

1. Parse banner line: `skill`, `sid`, `event_id`.
2. Read payload between `---payload---` and `---end---` markers.
3. Dispatch by `skill`:
   - **annotate** → see [Incremental annotate](#incremental-annotate-skill) below.
   - **interactive-review** → see [Interactive-review](#interactive-review-skill) below.
4. Write `state_dir/consumed/<event_id>.ack` (empty file is sufficient).
5. End turn. **No terminal output.** Watcher remains armed; next submit wakes Claude again.

### Idempotency on watcher restart

If the Monitor dies between emitting an event and Claude's ack, on restart it re-scans `events/`, finds the file still present with no `.ack`, re-emits. Claude's handlers MUST be re-apply-safe:

- **annotate** — content-hash check before writing `blocks.json`; if the block's current content already matches the would-be rewrite, skip the write.
- **interactive-review** — dedup thread messages by `source_event_id` stored in each message's metadata; skip append if already present.

### End-of-session signals

- **Done** (user clicked) → POST `/api/finish` → server writes `state_dir/finished` → watcher exits cleanly → Claude wakes with `WEBCOMPANION_FINISHED`, acks in terminal: *"Annotate session for `<title>` closed."*
- **Terminal cancellation** (`scrap it`, `stop annotating`, etc.) → Claude writes `state_dir/cancelled` per today's flow → watcher exits with `WEBCOMPANION_CANCELLED`.

## Incremental annotate skill

### Document model

`blocks.json`:

```json
{
  "response_id": "resp-<unix_ts>",
  "title": "<short title>",
  "blocks": [
    {"id": "b-0", "markdown": "## Overview\n\n…", "version": 1},
    {"id": "b-1", "markdown": "…", "version": 1},
    {"id": "b-2", "markdown": "…", "version": 3}
  ]
}
```

Block `id` is stable for the session — minted once, never reassigned. Rewriting mutates `markdown` and bumps `version`. Inserts use fresh ids never reused from deletions.

### Submission flow (UI)

- Each comment card has its own **Submit** button. Click → immediate POST `/api/submit`.
- The whole-document "Submit annotations" button is removed.
- Multiple comments on the same block before submitting are allowed; each submit fires independently.
- While a block has a pending event (submitted, no ack yet), the block shows an "updating" indicator. New comments queue normally.

Comment types preserved: `comment` (open-ended), `reject` (stronger steer in Claude's rewrite prompt).

### Rewrite contract (Claude on wake)

1. Read `blocks.json`, find the target block by `block_id`.
2. Read event payload (`text`, `selected_text`, `type`, `images[]`).
3. Generate rewritten markdown that **folds the answer/clarification into the prose**. The document IS the answer; no Q-and-A echo, no thread panel.
4. Edge cases:
   - **Off-topic question** — update the block to be clearer about its actual topic, or rewrite a neighboring block, or both. Claude judges.
   - **Reject with no direction** — rewrite to soften/withdraw, or hold the line with explanation in the new prose.
5. Write updated `blocks.json` atomically. Bump that block's `version`. Write `consumed/<event_id>.ack`. End turn.

**No history retained.** Previous block versions are overwritten. No "show previous" affordance.

### General-comments section

Preserved from today's annotate. Submitting a general comment fires an event with `block_id: null`. Claude reads `blocks.json` whole and rewrites multiple blocks as needed (e.g. "make this shorter", "casual tone"). All updates land in one `blocks.json` write.

### Polling protocol

Client polls `GET /poll?since=<vec>` every 1s. Response:

```json
{
  "blocks": {"b-0": 1, "b-1": 1, "b-2": 4},
  "watcher_seen_at": 1779360000,
  "finished": false
}
```

Client diffs against its in-memory version map. For each changed block, fires `GET /raw?block=b-N` → server returns the rendered HTML for that block alone. Client swaps inner DOM, preserves scroll position.

**Mid-typing race:** if a block gets rewritten while the user has a composer open on it, the composer text stays intact; the surrounding markdown swaps. The submit fires against whatever block version is current at submit time.

## Interactive-review skill

### Session creation

User invokes `/interactive-review <pr>`. Accepted forms:
- PR number (e.g. `123`) — current repo
- PR URL (`https://github.com/org/repo/pull/123`)
- Local branch ref (`feature/foo`) for pre-PR review against `main`

`create_session_extra` runs:

```bash
gh pr diff <pr> --patch                  → diff.patch
gh pr view <pr> --json title,headRefName,baseRefName,author,url  → meta.json
```

Plus a parsed file index:

```json
// files.json
[
  {"path": "src/server.py", "added": 18, "removed": 2, "hunks": [
    {"old_start": 40, "old_lines": 5, "new_start": 40, "new_lines": 21,
     "lines": [
       {"side": "context", "old": 40, "new": 40, "text": "…"},
       {"side": "added",   "old": null, "new": 41, "text": "+ def …"},
       …
     ]}
  ]}
]
```

The diff is snapshotted at session open. PR commits added later are out of scope; if `gh pr view --json headRefOid` changes during the session, the page shows a banner.

### Line anchor format

```
<file_path>:<side>:<line_number>       e.g. src/server.py:R:42
<file_path>:<side>:<start>-<end>       e.g. src/server.py:R:42-58
```

`side ∈ {L, R}` (left/base, right/head). Stable for session lifetime.

### Thread model

One JSON file per anchor:

```json
{
  "anchor": "src/server.py:R:42",
  "version": 3,
  "messages": [
    {"role": "user", "ts": …, "text": "why a range and not a fixed port?",
     "selected_text": "PORT_RANGE = range(54580, 54601)", "images": [],
     "source_event_id": "<id>"},
    {"role": "claude", "ts": …, "text": "Multiple Claude Code sessions…",
     "source_event_id": "<id>"},
    …
  ]
}
```

Threads are append-only. Each append bumps `version`.

### Per-line UX

- Diff renders GitHub-style: file headers, hunks, line numbers on both sides.
- Click a line → composer slides in below.
- Shift-click range on line gutter → composer attaches to the range anchor.
- Composer: textarea + paste-image + per-comment **Submit**.
- Submit → server appends the user message to the thread (creating it if needed) AND queues the event. Optimistic render: the user's bubble shows immediately; the "Claude thinking…" indicator below it resolves when the thread version bumps.
- Multi-round Q&A per anchor is expected. Subsequent comments on the same anchor append to the same thread.

### Response contract (Claude on wake)

1. Read event payload → `anchor`, `text`, optional images.
2. Read `state_dir/diff.patch` for context. Use `git show`, `Read`, `Grep` as needed for wider context.
3. Read existing `threads/<anchor>.json`.
4. Compose response. Style: short, code-aware, markdown-friendly. If user asked "why?", explain. If they questioned correctness, justify or flag a real bug.
5. Append `{role: "claude", ts, text, source_event_id}` to `messages`, bump `version`, write atomically.
6. Write `consumed/<event_id>.ack`. End turn.

**No code rewrites.** Claude never modifies the diff. If a fix is warranted, it goes in the thread message as a code block; the user takes it from there.

### Polling protocol

```json
{
  "threads": {"src/server.py:R:42": 4, "src/server.py:R:78": 2},
  "watcher_seen_at": 1779360000,
  "finished": false
}
```

Client diffs; for each changed thread fires `GET /thread?anchor=…` → full thread HTML; swaps in place. (Full-thread fetch is fine; threads are small.)

### General-review pane

Anchor `__general__` for PR-wide comments. Same composer + thread shape.

## Shared static + client API

Per skill HTML page template (returned by `serve_root`) loads:

```html
<link rel="stylesheet" href="/static/core.css">
<link rel="stylesheet" href="/static/<skill>.css">
<script src="/static/markdown-it.min.js" defer></script>
<script src="/static/core.js" type="module" defer></script>
<script src="/static/<skill>.js" type="module" defer></script>
```

`static.serve(handler, name, [SHARED_STATIC, SKILL_STATIC])` resolves names against the chain in order. Path-traversal guard unchanged.

### Client API

```js
WebCompanion.init({
  pollUrl: "/poll",
  pollIntervalMs: 1000,
  onPollDelta: (changed) => { /* skill decides what to refetch */ },
  fetchPartial:  (key) => fetch(`/raw?id=${key}`).then(r => r.text()),
  swapInto:      (key, html) => { /* skill places it in DOM */ },
});

WebCompanion.composer.open({anchor, position});
WebCompanion.submit({anchor, text, type, images});
WebCompanion.uploads.paste(file);  // POST /api/upload, return {token, path}
WebCompanion.finish();             // confirms, POSTs /api/finish
```

Skill-specific JS supplies: how to interpret poll deltas (blocks vs threads), what to fetch (`/raw?block=` vs `/thread?anchor=`), where to swap in the DOM.

### Markdown rendering

Client-side via `markdown-it.min.js`. Used by both skills:
- annotate — every block's markdown
- interactive-review — Claude's thread messages

Sanitization handled by markdown-it defaults + existing paste-strip pipeline. Diff lines in interactive-review are escaped as plain text — never interpreted as markdown.

## Error handling + edge cases

| Case | Behavior |
|---|---|
| Watcher death mid-session | `/poll` exposes `watcher_seen_at`. If client sees stale heartbeat (>30s) with pending events, surfaces banner: *"Claude seems to have stopped. Re-arm?"* → POST `/api/rearm` → next Claude invocation picks back up. |
| Claude crashes before ack | Watcher re-emits on next iteration. Handler is re-apply-safe via content-hash (annotate) or `source_event_id` dedup (review). |
| Claude wakes but handler fails | Watcher times out after 30 min, writes `<id>.failed` marker. `/poll` exposes failed event; client shows "Claude didn't respond — try resubmitting." |
| Concurrent in-flight events | FIFO by monotonic `event_id`. Watcher processes one at a time, waits for ack before next. Client shows "queued (N ahead)" on later events. |
| Comment-while-rewriting same block | Composer stays open with text; surrounding markdown swaps under it. Next submit fires against current version. |
| Selected text removed by rewrite | Comment still applies — `selected_text` becomes an advisory hint Claude treats as historical context. The current block is still updated to address the user's intent. Invisible to the user. |
| Server idle shutdown with open session | Polling counts as activity (existing `_touch()` behavior). No change. |
| Port collision between skills | Disjoint port ranges enforced in `ensure_server.sh`. Startup fails loudly if config overlaps. |
| Stale event-queue from prior session | Per-sid dirs guarantee fresh start. Old sid dirs are disk hygiene, not correctness. |
| gh CLI failure | `create_session_extra` returns 500 with stderr. Claude surfaces verbatim in terminal: *"Couldn't fetch PR diff: …"*. No partial session created. |
| Empty diff | "No changes in this PR" placeholder. General-comments pane still works. |
| Large PR (>1 MB diff) | Warning header; file-by-file pagination (collapsed by default). Hard cap >5 MB → reject. |
| PR updated during session | Banner if `headRefOid` differs from snapshot. User can finish current questions or restart. |
| Browser tab closed without Done | Today's terminal-cancel flow unchanged. `beforeunload` warning if composer has unsent text. |
| Multiple sessions same skill | Today's per-sid multiplexing preserved. |
| Paste-image limits | Reused from today's annotate (415, 413, 411, 409). Unchanged. |

## Testing

Match the existing pattern (`skills/annotate/tests/`).

**Shared library** (`skills/_shared/web_companion/tests/`):
- `test_sessions.py` — register/lookup/persist/rehydrate, concurrent registration.
- `test_events.py` — event ordering by monotonic id, atomic write, ack-marker semantics, dupe-replay safety.
- `test_uploads.py` — port today's 415/413/411/409 cases.
- `test_server.py` — port-bind, idle-watchdog, static path-traversal guard, `/poll` shape.

**Per-skill:**
- annotate: `blocks.json` round-trip, version bumping, block-id stability, general-comments dispatch, rewrite content-hash dedup.
- interactive-review: anchor parsing (single + range), thread append, diff snapshot against a fixture repo, gh-failure path, multi-message dedup by `source_event_id`.

**Watcher script:** one bash test per skill — create fake event files, run watcher with timeout, assert stdout format and ack handling.

**Client JS:** defer browser-driven tests for v1. One Playwright smoke test per skill (open → render → submit → version bump → swap).

**Manual checklist** (run before merge):
- Annotate: response open → comment block 2 → updates → comment block 4 → updates → general "shorter" → multiple update → Done.
- Interactive-review: open PR → diff loads → click line → comment → thread+reply → reply in thread → second message → range-select → multi-line comment → Done.
- Watcher death: kill Monitor mid-session → submit comment → banner → rearm → resume.

## Deferred / explicitly NOT in this spec

- Server-side code edits in interactive-review.
- Live re-rebasing of threads onto PR updates.
- Multi-user collaborative sessions.
- A version-history UI for annotate (the agent's "no panels" steer extends here).
- SSE transport — interval polling at 1 Hz suffices and avoids streaming-handler surface.
- Replacing `markdown-it.min.js` with a server-side renderer.

## Open questions deferred to implementation plan

- Exact wire format of `/raw?block=` and `/thread?anchor=` (rendered HTML vs JSON for client-side render). Lean: rendered HTML for simplicity, but the planner picks.
- Exact theme/CSS for the GitHub-style diff (will likely lift from existing diff viewers' open-source styles).
- Whether `interactive-review` should accept `--files src/foo.py src/bar.py` to narrow large PRs in v1 or defer to v2.
- Whether to migrate today's `annotate` from `response.md` → `blocks.json` in a single ship or behind a transitional flag (lean: single ship; the existing flow has no live consumers outside of Petros).

## Sequencing

Two implementation plans expected:

1. **Plan A — shared library extraction + annotate-incremental.** Includes the `skills/_shared/web_companion/` extraction, the watcher protocol redesign, the `blocks.json` model, the new polling protocol, and migration of `annotate` to the new shape. Ships first because it lands the load-bearing infrastructure.

2. **Plan B — interactive-review skill.** Built on top of the now-stable shared library. Adds the PR fetch, diff renderer, anchor/thread model, and per-line composer. Ships independently after Plan A.

Plan B does not require Plan A to be already merged into `main` — it can be developed in parallel against the same shared branch — but it does require Plan A's shared-library surface to be stable before integration.
