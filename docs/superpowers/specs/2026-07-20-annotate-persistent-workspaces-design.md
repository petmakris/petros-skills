# Annotate: persistent named workspaces + session browser + reattach

**Date:** 2026-07-20
**Status:** design — approved direction (persistent workspace, auto slug, all-projects browser, advisory concurrency), pending implementation plan
**Skill:** `petros-skills:annotate` (+ shared `skills/_shared/web_companion/`)

## Problem

Users cannot continue an annotate session after the Claude Code session that
created it ends. The data is *not* lost — the web server is a `nohup` singleton
that survives Claude exit (24h idle-shutdown), and each session's state persists
on disk at `<cwd>/.claude/annotate/<sid>/` for 7 days. What's missing is:

1. **No reattach** — the skill mints a **new** `sid` on every push
   (`references/pushing.md:54` "Create a session for this turn"); nothing reuses
   or reopens an existing one.
2. **No discovery** — `/` returns a bare string (`web_companion/server.py:240-242`);
   no page lists sessions. A lost URL is effectively unrecoverable.
3. **Opaque IDs** — a `sid` is `YYMMDD-HHMMSS-<16hex>` (`sessions.py:32-33`).
   A `title` exists in `meta.json` but is surfaced nowhere.

## Goals

1. **Persistent named workspace.** A workspace has a stable, human-readable
   `slug` derived from the work (e.g. `flowchart-beautify`). Successive pushes —
   within a conversation and across future Claude sessions — **reuse the same
   workspace** (same dir, same document, comment threads preserved) instead of
   forking a new one.
2. **Reattach.** `/annotate resume <slug>` (and a no-arg picker) reopens a prior
   workspace and points subsequent pushes at it.
3. **Browser.** A landing page at `/` lists **all** workspaces (flat, project as
   a column) with status, slug, title, project, last-active, comment count, and
   an Open link; client-side search + All/Live/This-project filter.
4. **Advisory concurrency.** When >1 Claude session is live on one workspace,
   show "N sessions attached". No locking.
5. **Lifecycle honesty.** Document the real model in `SKILL.md`.

## Hard constraint — backward compatibility

The `web_companion` server is **shared** with `interactive_review` (the IDE
plugin), which depends on `GET /api/sessions?cwd=<path>` returning
`[{sid, pr_ref, title, state_dir}]`. Every change here MUST be additive:

- New fields on session rows are additive (existing consumers ignore them).
- `GET /api/sessions?cwd=` keeps its exact current behavior/shape.
- `slug` is optional everywhere; absent → behavior identical to today (sid-only).
- Route `/s/<key>/` must still accept a raw `sid`.

## Approach

Build on the machinery that already exists (`Registry`, `find_by_cwd`, the `_cwd`
join key, 7-day retention, `GET /api/sessions`). Add a slug/alias layer, a
create-or-attach path, a landing page, and per-Claude-session heartbeats.

## Components

### 1. Slug identity & registry alias — `web_companion/sessions.py`

- Registry rows gain optional fields: `slug`, `title`, `project`,
  `created_at`, and the existing `_cwd`. Persisted in `sessions.json`
  (additive; rehydrate tolerates their absence for old rows).
- **Slug generation** `make_slug(title, cwd) -> str`:
  - Slugify `title` (lowercase, `[a-z0-9]+` joined by `-`, trim, max ~40 chars);
    empty → fall back to the project basename, then to the raw `sid`.
  - **Dedup** against existing registry slugs: if taken, append `-2`, `-3`, …
    until free. (Case-insensitive compare.)
- **Alias resolution** `resolve(key) -> sid | None`: return `key` if it's a live
  `sid`; else look up `key` in a slug→sid map; else `None`. Used by routing so
  `/s/<slug>/` and `/s/<sid>/` both work. A workspace's slug is unique among
  **live** sessions; a reused/GC'd slug can be reassigned.
- `find_by_cwd` unchanged; add `find_by_slug(slug)` and `list_all()` (all live
  rows, newest-first by `created_at`/last-activity).

### 2. Create-or-attach — `web_companion/server.py` `POST /api/sessions`

Current: always mint a new sid + dirs (`server.py:359-391`). New behavior,
driven by an **additive** request body:

```json
{"cwd": "<path>", "title": "<work title>", "project": "<repo name>",
 "slug": "<optional explicit slug>", "attach": true|false}
```

- If `attach` is true **and** a live workspace resolves for this `(cwd, slug)`
  (or, when no slug given, the most-recent live workspace for `cwd`): return
  that existing session (its sid, slug, dirs, urls) — no new dir. This is the
  reattach primitive.
- Otherwise **create**: mint sid as today, compute `slug = make_slug(title, cwd)`
  (unless an explicit unique slug was supplied), record `slug/title/project/
  created_at` in the registry row and in `meta.json`, create dirs, return
  `{sid, slug, url, localhost_url}` where the urls now use the **slug**:
  `http://host:port/s/<slug>/`.
- Response is a superset of today's — existing callers keep working.

`_match_session` (`server.py:198-209`) routes through `registry.resolve(key)`
so both slug and sid resolve to the same in-memory session.

### 3. Skill-side reuse & resume — `annotate/references/pushing.md`, `SKILL.md`

- **Per-conversation current-workspace marker.** The first push in a conversation
  creates a workspace and records its `sid`+`slug` in the per-Claude-session
  file `~/.claude/annotate/pending-<CLAUDE_CODE_SESSION_ID>.json` (already exists
  for watcher tracking — extend it with `workspace: {sid, slug}`). Subsequent
  pushes in the same conversation read that marker and POST with
  `{attach:true, slug}` so they **update the same workspace** rather than mint a
  new sid.
- **Slug source.** The skill derives `title` from the work (it already writes a
  `title` into `meta.json`); pass it as `title` on create so the server slugifies
  it. The skill may also pass an explicit `slug` when it has a good short name
  for the work.
- **`/annotate resume <slug>`** (new command, documented in `SKILL.md` +
  a new `references/resuming.md`): resolve the slug via `GET /api/sessions`
  (see §4), set it as the conversation's current-workspace marker, re-arm a
  watcher on its `state_dir`, and print its URL. Subsequent pushes target it.
- **`/annotate resume`** (no arg): fetch `GET /api/sessions?cwd=$PWD`, present the
  recent workspaces for this project as a short list for the user to pick, or
  point them at the browser (`/`).
- **Auto-offer on trigger:** when annotate is about to push in a `cwd` that has a
  live workspace, offer *resume that vs start new* rather than silently forking.

### 4. Browser landing page — `/` and `GET /api/sessions`

- **`GET /api/sessions` extended (additive):**
  - Without `cwd` → return **all** live workspaces (new behavior; today it 400s
    without `cwd`). With `cwd` → unchanged filtered behavior.
  - Each row gains: `slug`, `title`, `project`, `last_active` (unix),
    `comment_count`, `status` (`live` | `idle` | `done`). Existing fields
    (`sid`, `pr_ref`, `title`, `state_dir`) stay.
  - `status`: `done` if a `finished` marker exists; `live` if any watcher
    heartbeat is fresh (< ~10s, see §5); else `idle`.
  - `comment_count`: number of comment threads in the doc — count of distinct
    scoped threads recorded in the workspace's `annotations` (authoritative;
    not the cheaper `consumed/*.ack` proxy).
- **`/` route** serves a static `web_companion/static/sessions.html` (new) — the
  page from the approved mockup: fetches `/api/sessions`, renders the flat list
  client-side with status dot, slug (mono/accent), title, project tag,
  last-active, comment count, Open→ link, plus a search box, an **All/Live**
  segment, and a **project selector** (populated from the distinct `project`
  values in the rows). Light Material theme reusing `core.css` tokens + the
  Bricolage/Monaspace fonts. Row → `/s/<slug>/`.
- The standalone landing page has no cwd context, so filtering is by explicit
  project selection (not an implicit "this project"). All filtering/search is
  client-side over the fetched rows; each row already carries its `project`.

### 5. Advisory concurrency — `web_companion/watcher.sh`, `server.py`

- Today one `state/watcher_heartbeat` file per session dir is overwritten by
  whichever watcher runs. To count **distinct** live Claude sessions on a
  workspace, each watcher writes its own heartbeat:
  `state/watchers/<CLAUDE_CODE_SESSION_ID>.hb` (mtime = liveness). Keep writing
  the legacy `state/watcher_heartbeat` too (back-compat for existing
  liveness checks).
- **Attached count** = number of `state/watchers/*.hb` with mtime < ~10s.
  Exposed on the per-session poll payload (`annotate/server.py` `/poll`) as
  `attached: <n>`.
- **UI:** when `attached > 1`, the annotate page shows a small pill
  "N sessions attached" (client reads it from the poll response). No lock;
  comments are append-only and block writes are last-writer-wins (rare).
- Stale `.hb` files are swept by the existing GC alongside the session dir.

### 6. Lifecycle docs — `annotate/SKILL.md`

Add a short "Session lifecycle" section stating: the server is a `nohup`
singleton shared across Claude sessions, survives Claude exit, self-shuts after
24h idle; workspaces persist 7 days at `<cwd>/.claude/annotate/<slug-or-sid>/`;
reopen via the browser at `/` or `/annotate resume <slug>`.

## Data flow

Push #1 in a conversation → skill derives `title` → `POST /api/sessions
{cwd,title,attach:false}` → server mints sid, `slug=make_slug`, writes registry
row + meta → returns `/s/<slug>/` → skill records `{sid,slug}` in
`pending-<claude_sid>.json` + arms watcher. Push #2..N → skill reads marker →
`POST {attach:true, slug}` → server returns the SAME session → skill updates
blocks in the same dir. New Claude session → `/annotate resume <slug>` → resolve
→ set marker + arm watcher → pushes continue on the same workspace. Browser at
`/` → `GET /api/sessions` (all) → flat list → click → `/s/<slug>/`.

## Error handling

- Unknown slug on resume → skill reports "no live workspace '<slug>' — run
  `/annotate resume` to list, or the browser at <url>"; never crashes.
- Slug collision on create → auto-suffixed `-2` (never fails).
- `attach:true` but the workspace was GC'd / dir missing → server falls back to
  **create** a fresh one with the same slug and returns it (self-healing);
  response indicates `created:true` so the skill can note "started fresh".
- Old registry rows without `slug` → routing still works via sid; browser shows
  the sid as the name.
- Backward-compat: `GET /api/sessions?cwd=` and `/s/<sid>/` behave exactly as
  today for `interactive_review`.

## Testing

- **Registry (`sessions.py`):** `make_slug` slugification + dedup (`-2`/`-3`),
  empty-title fallback, `resolve(sid)` and `resolve(slug)`, `find_by_slug`,
  `list_all` ordering, rehydrate tolerating missing new fields.
- **Server (`server.py`):** `POST /api/sessions` create returns slug + slug URL;
  `attach:true` returns the existing session (same sid/dir) not a new one;
  `attach:true` with missing dir self-heals to create; `/s/<slug>/` and
  `/s/<sid>/` both resolve; `GET /api/sessions` without cwd returns all with the
  new fields; **with cwd returns the exact legacy shape** (regression guard for
  interactive_review); status/comment_count computation.
- **Concurrency:** two `.hb` files fresh → `attached==2`; one stale → `attached==1`.
- **Browser page:** static-page smoke — `/` returns HTML referencing
  `/api/sessions`; a rendered fixture list shows slug/title/project/status.
- **Skill flow (doc-level):** create-or-attach marker round-trip described in
  `pushing.md`; resume path in `resuming.md`.

## Out of scope

- Hard locking / conflict resolution for concurrent block writes (advisory only).
- Renaming a workspace after creation (v2).
- Cross-machine / remote session sync.
- Changing the 7-day retention or 24h idle defaults (stay configurable via env).
- Auth on the browser page beyond what the companion already does.

## Open decisions captured

- **Slug is a live-unique alias, sid stays canonical.** Slugs can be reassigned
  after a workspace is GC'd; the sid never changes and always routes.
- **Reuse is conversation-scoped by default** (via the pending marker); crossing
  Claude sessions requires an explicit `/annotate resume` (no silent cross-
  session attach), except the auto-offer prompt when a live workspace exists for
  the cwd.
