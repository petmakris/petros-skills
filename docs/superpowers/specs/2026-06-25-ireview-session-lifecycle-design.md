# Honest session lifecycle for interactive-review

**Date:** 2026-06-25
**Component:** `skills/_shared/web_companion` (server) + `skills/interactive_review/server.py` + `intellij-plugin-spike` (IDE plugin)
**Status:** Approved design (v2 — reviewer gaps closed), pre-implementation

## Problem

The interactive-review server's only notion of "session over" is `_is_terminal()`
= the presence of a `finished`/`cancelled` marker file (`server.py:32`). A session
whose Claude **watcher** simply vanished — context ended, process killed, user moved
on — never gets a marker, so the server treats it as live forever. Two concrete
failures observed:

1. Discovery `GET /api/sessions?cwd=` keeps returning a 22-hour-dead "zombie"
   session. When the user cancels their *current* session, the plugin's discovery
   loop grabs the next session at index 0 — the zombie — and renders its
   annotations as a live review.
2. The plugin renders + accepts input for any session discovery hands it. The
   gutter→`SynthesisPopup` path has **zero** liveness awareness, so the user can
   type a question into a review whose Claude died yesterday and nothing answers.

## Decision

Introduce **watcher liveness** derived from the heartbeat, giving a session an
honest lifecycle the server enforces and the plugin reflects. Ended sessions are
**frozen read-only** (findings preserved — they are the deliverable), never
silently swapped for a zombie.

### Liveness model

Two thresholds, computed from the heartbeat **integer epoch-second contents**
(NOT file mtime — see "Heartbeat mechanism"):

| State   | Condition                                              | Plugin surface |
|---------|-------------------------------------------------------|----------------|
| LIVE    | `age ≤ 15s` (`STALE_AFTER`)                            | normal, input enabled |
| PAUSED  | `15s < age ≤ 180s` (`REAP_AFTER`), no terminal marker | findings stay, input disabled, "Paused — reconnecting", resumable |
| ENDED   | terminal marker (cancel/finished) **or** `age > 180s` | read-only **frozen** + banner, input disabled |

Explicit Done/cancel ⇒ ENDED immediately, regardless of heartbeat.

### Heartbeat mechanism (correction from v1)

The heartbeat file holds an **integer epoch second** written by `watcher.sh`
(`date +%s`), refreshed every ~1s in every watcher loop branch (idle and
ack-wait). Liveness reads the **contents**: `age = now - int(read_text())`. The
file mtime is NOT used. Missing/empty/unparseable heartbeat ⇒ **age unknown ⇒
treated as LIVE / not reaped** (a freshly-armed session has not written its first
beat yet; mirrors the existing client rule "seenAt ≤ 0 → not dead").

This matches the proven pattern already shipping in the sibling `annotate` skill
(`annotate/server.py` emits `watcher_age_s`); we copy that template.

## Scope

**Server** (`skills/_shared/web_companion/server.py`, plus the `serve_poll` site in
`skills/interactive_review/server.py`):

- Add `_watcher_age(dirs) -> int | None` (parse heartbeat contents; `None` if
  missing/unparseable) and `REAP_AFTER = 180`.
- **Discovery** `/api/sessions?cwd=`: skip a session if `_is_terminal(dirs)` **or**
  (`_watcher_age` is not None **and** `> REAP_AFTER`). Preserve the existing
  newest-first sort (`sid` descending) — filter, then sort.
- **`serve_poll`**: add `ended` (bool) and `ended_reason`
  (`"cancelled"`/`"finished"`/`"dead"`) to the existing payload, keeping
  `watcher_seen_at` unchanged. `ended = _is_terminal or (age is not None and age >
  REAP_AFTER)`. Reason precedence: `cancelled` > `finished` > `dead`.
- **Write path** (`/api/submit`, `/api/threads/delete`, `/api/upload`): leave
  gating on `_is_terminal` only — i.e. **keep accepting late writes** for a
  watcher-dead-but-unmarked session, matching the documented `annotate` behavior
  (a re-armed watcher drains queued events). The plugin refuses to submit once it
  sees PAUSED/ENDED, so this is belt-and-suspenders, not a hole. Documented choice,
  not an oversight.
- **Server restart:** `rehydrate()` restoring a watcherless session is *correct* —
  its stale heartbeat ages it straight to ENDED and discovery reaps it. The code
  comment must state this so nobody "fixes" it by re-arming watchers.
- **Out of scope (stated):** the SSE `_serve_stream` loop is not made
  liveness-aware (the plugin polls `/poll` for liveness); idle-shutdown `touch()`
  is orthogonal and untouched.

**Plugin** (`intellij-plugin-spike/.../com/petros/ireview/`):

- `ReviewSessionClient.java` — replace `STALE` with `PAUSED` + `ENDED`. **One**
  place derives effective state with a total precedence
  `ENDED > PAUSED > DISCONNECTED > ACTIVE` from `{serverEnded, heartbeatAge,
  sseConnected}`, instead of scattered `setState` calls. **ENDED is a
  non-recovering latch** — a returning heartbeat must NOT un-freeze it (today's
  STALE→ACTIVE recovery must not apply to ENDED). Trust the server's `ended`
  bool and latch it.
- **Supersede predicate (the hand-wave, now precise):** a frozen ENDED client
  ignores discovery for its own sid, but switches **iff** discovery's newest
  session has a **different** sid **and** that candidate is **LIVE** (its own
  `/poll` heartbeat fresh). Never switch to a dead/terminal candidate. This — plus
  the existing newest-first sort and `attach`-on-sid-change — makes a *new* review
  supersede the frozen one, so the "supersede-on-new-session marker" is correctly
  YAGNI.
- `SynthesisPopup.java` — **in scope** (the central input hole): subscribe to
  state; disable the input field + Ask button and stop showing a hung "thinking"
  spinner when PAUSED/ENDED. Clear in-flight `pending` on ENDED (as STALE already
  does) so spinners don't hang forever.
- `AnnotationsPanel.java` — ENDED → "Session ended — read-only" banner + read-only
  rows + disabled input; PAUSED → "reconnecting" footer. Disable the End-review
  button when already ENDED (or make cancel idempotent).
- `ReviewStatusBarWidget.java` — PAUSED/ENDED labels.
- Suppress the SSE reconnect loop for a frozen (ENDED) sid.
- `ended_reason` is carried in the payload but **not branched on** by the client
  (single `ended` bool drives banner + read-only + disabled input) — YAGNI.

## Data flow

1. Plugin attached to sid `S` polls `/s/S/poll` → `{watcher_seen_at, ended,
   ended_reason}`.
2. Effective state = derive(`ended`, `now - watcher_seen_at`, sseConnected) with the
   precedence above; ENDED latches.
3. Discovery `/api/sessions?cwd=` returns only non-terminal, non-dead sessions,
   newest-first. The attached client switches only to a **different LIVE** newest
   candidate; otherwise it keeps showing `S` (frozen if ENDED).

Consequences (all desirable): a session that ends while attached freezes on **its
own** findings; a long-dead session never appears on a fresh IDE start (reaped from
discovery); starting a new review supersedes the frozen one.

## Error handling / edge cases

- **Clock:** `ended` is computed with the **server's** clock against the
  **watcher's** epoch contents; both are co-located on the local-loopback host, so
  skew is negligible. Remote hosting is out of scope; note as an accepted limitation.
- **No heartbeat yet** (fresh session): age unknown ⇒ LIVE / not reaped.
- **PAUSED band semantics:** a *graceful* watcher exit writes a marker ⇒ immediately
  ENDED, so PAUSED (15–180s) represents a *crashed/SIGKILLed/restarting* watcher.
  Intended.
- **In-flight reply when ENDED:** clear `pending`, drop the spinner.
- **DISCONNECTED vs PAUSED:** DISCONNECTED = server unreachable; PAUSED = server up,
  watcher dead. Precedence resolves overlap; effective state computed in one place to
  avoid the existing flicker race.

## Testing

- **Server (pytest):** discovery skips a session whose heartbeat contents age >
  `REAP_AFTER`; `/poll` returns `ended=true reason=dead` (old heartbeat),
  `reason=cancelled`/`finished` (marker), and `ended=false` + correct
  `watcher_seen_at` when fresh; missing-heartbeat ⇒ not reaped; newest-first sort
  preserved after filtering.
- **Client (unit, FakeReviewServer):** state derivation LIVE/PAUSED/ENDED from poll
  payloads (FakeReviewServer must emit `ended`/`ended_reason`); ENDED latches across
  a returning heartbeat; `postComment` rejected in PAUSED/ENDED; supersede only on a
  different LIVE sid; no switch to a dead candidate.
- **Popup/panel:** manual smoke — gutter popup input disabled + spinner cleared on
  ENDED; banner + read-only rows; paused footer; End-review disabled when ENDED.

## Success criteria

- Reproduce-the-bug trace passes: cancel the current session with a 22h zombie
  present → panel freezes on the cancelled session's own findings (or goes empty),
  **never** the zombie; the zombie is absent from discovery.
- No code path (panel **or** gutter popup) accepts a submission while PAUSED/ENDED.
- A brief Claude restart followed by a new `/interactive-review` supersedes the
  frozen panel with the new live session.
- All server pytest + plugin automated suites pass.
