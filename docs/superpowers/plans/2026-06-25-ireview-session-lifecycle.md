# Session-lifecycle fix — Implementation Plan

> **For agentic workers:** TDD, frequent commits. Steps use `- [ ]`.

**Goal:** Give interactive-review an honest session lifecycle: dead/ended sessions are reaped from discovery and frozen read-only in the IDE, never silently swapped for a zombie, and no surface accepts input once the watcher is gone.

**Architecture:** Watcher liveness derived from the heartbeat's integer epoch-second contents (`age = now - int(read)`), two thresholds `STALE_AFTER=15s` / `REAP_AFTER=180s`. Server reaps from discovery + reports `ended` on `/poll`; plugin derives one effective state with precedence `ENDED>PAUSED>DISCONNECTED>ACTIVE`, ENDED latching.

**Tech Stack:** Python stdlib http.server; Java 21 IntelliJ plugin (Gson, Swing); pytest; JUnit5/gradle.

## Global Constraints

- Heartbeat is **contents** (`int(read_text())`), never mtime. Missing/empty ⇒ age unknown ⇒ LIVE/not-reaped.
- `REAP_AFTER=180`, `STALE_AFTER=15` (seconds). Discovery reaps at REAP_AFTER; client PAUSED at STALE_AFTER, ENDED at REAP_AFTER or server `ended`.
- ENDED is a non-recovering latch. Supersede a frozen panel only with a *different LIVE* sid.
- Keep accepting late writes server-side (gate only on `_is_terminal`); the client refuses to submit when PAUSED/ENDED.
- Mirror the proven `annotate/server.py` `watcher_age_s` pattern.

---

### Task 1: Server — `_watcher_age` + discovery reap

**Files:** `skills/_shared/web_companion/server.py`; test `skills/_shared/web_companion/tests/` (pytest).
- [ ] Failing test: a session with heartbeat contents older than `REAP_AFTER` is absent from `/api/sessions?cwd=`; a fresh one is present; missing-heartbeat one is present (not reaped); newest-first order preserved.
- [ ] Add `REAP_AFTER=180` and `_watcher_age(dirs)->int|None` (parse `watcher_heartbeat` contents; `None` on missing/ValueError).
- [ ] Discovery loop: `if _is_terminal(dirs) or (age is not None and age > REAP_AFTER): continue`. Keep the descending `sid` sort.
- [ ] Comment: rehydrated watcherless sessions are intentionally reaped.
- [ ] Tests green.

### Task 2: Server — `/poll` reports `ended` + `ended_reason`

**Files:** `skills/interactive_review/server.py` (`serve_poll` site ~`:229-242`); pytest.
- [ ] Failing test: `/poll` returns `ended=false` + correct `watcher_seen_at` when fresh; `ended=true,reason=dead` when heartbeat aged > REAP_AFTER; `reason=cancelled`/`finished` when marker present; missing heartbeat ⇒ `ended=false`.
- [ ] Compute `ended`/`ended_reason` (precedence cancelled>finished>dead) reusing `_watcher_age` + `_is_terminal`; keep `watcher_seen_at`.
- [ ] Tests green.

### Task 3: Client — effective-state machine (PAUSED/ENDED, latch, precedence)

**Files:** `intellij-plugin-spike/.../ReviewSessionClient.java`; `FakeReviewServer.java`; `ReviewSessionClientTest.java`.
- [ ] Extend `FakeReviewServer` poll payload with `ended`/`ended_reason`.
- [ ] Failing tests: LIVE/PAUSED/ENDED derivation from poll; ENDED latches across a returning heartbeat; `postComment` rejected in PAUSED and ENDED.
- [ ] Replace `STALE` with `PAUSED`+`ENDED`; read `ended` from poll; single `deriveState({serverEnded,age,sseConnected})` with precedence `ENDED>PAUSED>DISCONNECTED>ACTIVE`; latch ENDED.
- [ ] `postComment` rejects PAUSED/ENDED; clear `pending` on ENDED.
- [ ] Tests green.

### Task 4: Client — supersede predicate + freeze (no zombie fallback)

**Files:** `ReviewSessionClient.java` (`pollDiscover`/`attach`); `ReviewSessionClientTest.java`.
- [ ] Failing test: attached client whose session goes ENDED keeps its sid (does not fall back to a different sid that is dead); switches only when discovery's newest sid differs AND is LIVE.
- [ ] Implement: while ENDED-frozen, ignore discovery for own sid; switch iff candidate sid≠frozen sid AND candidate `/poll` heartbeat fresh. Suppress SSE reconnect for frozen sid.
- [ ] Tests green.

### Task 5: UI — popup, panel, status bar reflect state

**Files:** `SynthesisPopup.java`, `AnnotationsPanel.java`, `ReviewStatusBarWidget.java`.
- [ ] `SynthesisPopup`: subscribe to state; disable input + Ask button and clear spinner on PAUSED/ENDED.
- [ ] `AnnotationsPanel`: ENDED → banner + read-only rows + disabled input; PAUSED → reconnecting footer; End-review disabled when ENDED.
- [ ] `ReviewStatusBarWidget`: PAUSED/ENDED labels.
- [ ] Compile + existing panel/popup tests green (UI behavior manual-smoke at the end).

### Task 6: Build, reload, clean up

- [ ] `pytest` (server) + `./gradlew test` (plugin) all green.
- [ ] `./reload` to install the new jar.
- [ ] Remove the two dead session dirs so the panel is clean on restart.
