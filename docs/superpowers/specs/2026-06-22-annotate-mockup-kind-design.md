# Annotate `mockup` block kind — design spec

**Date:** 2026-06-22
**Status:** Implemented — Phase 1 and Phase 2 both shipped (whole-mockup render/commenting + per-region annotation). Only the region hover-glow round-trip remains deferred.
**Author:** Brainstormed with a four-engineer review panel (frontend/rendering, skill-surface/protocol, security, server/protocol), all grounded in the live code.

## Problem

Claude Code is highly expressive with HTML/CSS/JS, but the annotate skill flattens responses to (mostly) text. The existing free-HTML markdown block helps, but its client-side sanitizer (`static/script.js` ~`sanitizeFreeHtml`) strips `<style>`, `<script>`, `<iframe>`, `<form>` etc., so a markdown block can only use inline `style="…"`. That is enough for a flat wireframe but not for a real application **mockup**: no stylesheet, no classes, no hover/focus states, no Tailwind, no interactivity.

**Goal:** let Claude render high-fidelity application mockups inside annotate — real `<style>`/`<script>`/Tailwind, hover, optional interactivity — without (a) bleeding styles into the host page, (b) XSS against the annotate app/session, (c) bloating the always-loaded skill surface or making Claude flakier at emit time, while still allowing click-to-comment.

## Decision summary

| Decision | Choice | Rationale |
|---|---|---|
| Capability shape | **New `kind: "mockup"`** (not a `sandbox:true` flag on markdown) | Doc cost lands in the pay-per-use kind file, not always-on-push `pushing.md`. Server-side it forwards `spec` like `choice` (~2 lines) and does **zero HTML rendering**. A distinct `kind` is a hard discriminator; a stray boolean flag can be silently dropped and render un-sandboxed. |
| Isolation | **Sandboxed iframe**, not loosening the host sanitizer | Inline `<style>`/`<script>` in the host DOM is global: Claude's CSS would wreck the annotate chrome and its `<script>` would run **as the session** with access to the comment-submit machinery and the sid. The iframe jails it to an opaque origin. |
| Sandbox flags | **`sandbox="allow-scripts"` only** | Never `allow-same-origin` (re-merges origins; lets the frame un-sandbox itself), never `allow-top-navigation` (redirect/phishing). |
| Storage | mockup HTML in `spec.html` | Distinct from diagram `spec.source` and markdown `markdown` so the trusted-vs-sanitized boundary is legible in the data. Reuses `update_spec_block` dedup. |
| Bridge auth | **`event.source === iframe.contentWindow`** | srcdoc origin is the literal string `"null"`; origin checks are useless, object-identity is the only spoof-proof gate. |
| Annotation anchoring | **Reuse the block-anchored comment editor** | annotate anchors the comment card to the *block*, never to click coordinates (`renderComments` inserts an `.inline-comments` sibling). No iframe→host coordinate translation is ever needed — forward an *id*, not a *point*. |

## Architecture

```
Host (annotate page)            Sandboxed iframe (Claude's HTML)        Python server
static/script.js                srcdoc, sandbox="allow-scripts"         server.py / blocks.py
─────────────────               ───────────────────────────────        ─────────────────
renderMockup() ── builds ──►    [ Claude mockup HTML ]                  spec.html persisted
  (new kind branch,              real <style>/<script>/Tailwind          (update_spec_block
   skips sanitizeFreeHtml,       regions tagged data-annotate-id          dedup, glossary scan,
   injects trusted bridge                                                 atomic save — unchanged)
   + frame CSP)                  bridge: ResizeObserver ──┐
                                 bridge: click forwarder ─┤             render: 2-line spec
boot message listener ◄──────────── postMessage ─────────┘              forwarding branch
  auth: ev.source===iframe.cw                                            (mirrors `choice`)
  clamp height                                            host → server event path:
openAnnotation(step_id) ──► block-anchored comment card   {block_id, step_id, text, type}
                                                           handling-events.md contract unchanged
```

### Rendering layer (`static/script.js`)

Four touch points, mirroring how `diagram`/`sequence` already special-case themselves:

1. **Creation** — in the kind branch of `createBlockSection` (~line 486–512, beside the `diagram` branch), add `else if (kind === "mockup") { renderMockup(content, blk); }`. `renderMockup` builds an `<iframe sandbox="allow-scripts">`, sets `srcdoc = BRIDGE + FRAME_CSP_META + blk.html`, and **deliberately does not call `sanitizeFreeHtml`** — exactly the documented precedent the `diagram` branch already sets (`script.js` ~497–503). Defensive default: falsy `blk.html` → render a plain "mockup unavailable" placeholder, mirroring `blk.svg || ""`.
2. **Hover strip** — do nothing. Mockups get the whole-block comment/reject/dismiss strip by default (only `sequence`/`choice` opt out in `renderHoverActions`). This gives Phase-1 whole-mockup commenting for free.
3. **Boot message listener** — one global `window.addEventListener("message", …)` attached once at init (near `WebCompanion.init`). It keeps a `Map<blockId, iframe>`, authenticates each message with `event.source === iframe.contentWindow` (origin is `"null"` and must NOT be trusted), validates message shape, clamps height to a sane max, and sets `iframe.style.height`.
4. **In-place update** — in `updateBlockContent` (~1302–1330) add a `mockup` branch that reassigns `iframe.srcdoc` under the existing `is-updating` overlay. The kind-flip guard (~1308) already forces a fresh section when a block becomes/stops being a mockup. **Known cosmetic cost:** reassigning `srcdoc` reloads the frame (brief blank/height-collapse). Phase 1 ships the naive reassign under the spinner; double-buffering (build the new iframe offscreen, swap on first height message) is a ~15-line follow-up only if the flash annoys in practice.

### Trusted bridge (host-injected into srcdoc, never author-supplied)

- **Height (Phase 1):** `ResizeObserver(document.documentElement)` → `parent.postMessage({type:"annotate:height", h: scrollHeight}, "*")`. Also post on `DOMContentLoaded`, `window.load`, and capture-phase `load` (late images). Bridge sets `html,body{margin:0}` and reads `scrollHeight` (not `offsetHeight`); host coalesces with `requestAnimationFrame`. Never let frame CSS key off viewport height (feedback loop).
- **Click forwarding (Phase 2):** one delegated listener — `document.addEventListener("click", e => { const el = e.target.closest("[data-annotate-id]"); if (el) parent.postMessage({type:"annotate:click", id: el.dataset.annotateId, label: el.textContent.slice(0,48)}, "*"); })`. Required because **clicks inside a sandboxed iframe do not bubble to the host** — the host's existing `closest()` capture cannot see them.

### Security configuration (load-bearing)

- `sandbox="allow-scripts"` and nothing else. **Never** `allow-same-origin` or `allow-top-navigation`. Add `allow-forms`/`allow-modals` only on demonstrated need.
- Host injects a frame CSP `<meta http-equiv>` into the srcdoc:
  `default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:; connect-src 'none'; form-action 'none'; base-uri 'none'`.
  `connect-src 'none'` + `default-src 'none'` kill the accidental phone-home/exfil path; the mockup still gets inline style/script and data/inline images+fonts.
- Host message handling MUST: gate on `event.source === iframe.contentWindow`; validate message shape; treat all fields as opaque untrusted data; **never** `eval`/`Function`/`innerHTML`/`fetch`-by-message-data; clamp `height`. Host→frame messages carry **no secrets** (targetOrigin is `"*"`).
- The markdown-block sanitizer and its `IFRAME`/`STYLE` disallow list stay **untouched** — only the typed `mockup` kind produces frames.

### Server layer (`server.py` / `blocks.py`)

Verified against the real code:

- **Persistence — unchanged.** `BlocksDoc` is schemaless about kind; `update_spec_block` dedup, `save_atomic`, and `load` round-trip a `mockup` spec block untouched.
- **Render — two tiny edits for Phase 1 (NOT zero).** Verified against `server.py:465–529`: the pass-through `else` branch emits `markdown`, **not** `spec`. Since the mockup HTML lives in `spec.html`, the server must forward it like `choice` does. Required edits:
  1. `server.py` `_render_block_for_raw` (~line 527, beside the `choice` branch): `elif kind == "mockup": base["spec"] = blk.get("spec") or {}`. The server still does **no rendering** of the HTML (unlike `diagram`/`sequence`, which server-render Mermaid); it only forwards the spec.
  2. `versions.py:42`: add `"mockup"` to `_SPEC_KINDS = ("sequence", "diagram", "choice")`. Without this the version hash keys on `markdown` (empty for a mockup) instead of the canonical spec, so a rewrite never bumps the version and the client never refetches. The in-code comment explicitly requires keeping this set in sync with `_render_block_for_raw`.
- **Glossary — unchanged.** `drop_unused_terms` only scans `kind in (None,"markdown")` blocks and does regex word-boundary matching tolerant of HTML; a `mockup` block is (correctly) excluded from the glossary haystack.
- **`step_id` gate — MUST CHANGE for Phase 2 only.** `server.py` ~337–355 hard-codes *"step_id only valid for kind=sequence"* → HTTP 422 for any other kind, asserted by `tests/test_server.py` ~696 (`test_submit_step_id_against_markdown_block_returns_422`). Phase 2 relaxes this: keep the strict `spec.steps` membership check **only** for `kind=="sequence"`; for other kinds accept a non-empty string `step_id` and pass it through. Update/replace the locking test and add a positive test (mockup block + arbitrary `step_id` → 202).
- **Validation posture.** Server stays permissive: optionally require `spec.html` is a non-empty string; **never** parse HTML server-side. The client owns the render-error fallback (the sandbox already contains the blast radius).

### Skill surface (progressive disclosure — the burden budget)

- `SKILL.md`: **+1 menu row** (~35 words). The entire always-loaded delta. Example row:
  `| `mockup` | A high-fidelity, interactive UI mock is clearer than prose or a static diagram: real <style>/<script>/Tailwind, hover, interaction. Renders in a sandboxed iframe. | references/block-kinds/mockup.md |`
- `references/pushing.md`: +~50-word cross-reference at the end of the inline-HTML section ("a `kind:"mockup"` block is the same free-HTML contract with the sanitizer lifted, rendered in a sandboxed iframe; `data-annotate-id` per-region contract unchanged; see the kind file").
- `references/handling-events.md`: **zero changes.** Phase 1 whole-mockup comments arrive as `step_id:null` (already handled); Phase 2 reuses the free-HTML `data-annotate-id` payload shape and `update_spec_block` helper. This is the linchpin: the per-event always-loaded path does not grow.
- New `references/block-kinds/mockup.md`: ~200 words, **pay-per-use** (loaded only when emitting a mockup). Closer to `choice.md` (320w) than `diagram.md` (651w). Outline:
  - **When to use** / **Do NOT use** (don't escalate to a sandbox for what an inline-`style=""` callout already does; static structure → `diagram`; ≤1 screen of UI).
  - **Block shape** (the JSON below).
  - **Sandboxing → what you may now use**: `<style>`/`<script>`/Tailwind allowed; the sanitizer does not run on this block. The frame is isolated — it does **not** inherit the page's CSS variables (`var(--accent)` etc.), so the mockup must bring its own styling. This is the one divergence from the free-HTML "reuse the page palette" guidance and must be stated explicitly.
  - **Per-region commenting** (one sentence, defer to pushing.md): mark regions with `data-annotate-id`; a click arrives as `step_id:"<slug>"`; preserve surviving slugs on rewrite.
  - **Rewriting** (one sentence): rewrite `spec.html` via `update_spec_block`; same helper as diagram, `spec.html` instead of `spec.source`.

### Block spec shape

```json
{"id": "section-N", "kind": "mockup", "spec": {
  "title": "<short title>",
  "html": "<self-contained fragment: may contain <style>/<script>/Tailwind; tag commentable regions with data-annotate-id>"
}}
```

No `width`/`theme`/`scripts` fields — speculative; omit per simplicity, add later only on real need.

**Rewrite contract:** identical wording to the existing free-HTML round-trip rule — "preserve `data-annotate-id` slugs on sub-units that still exist; restructure freely otherwise." Stated once in `mockup.md` as a one-line deferral.

## Phasing

**Phase 1 — high-fidelity render + whole-mockup commenting. ✅ SHIPPED.**
- `static/script.js`: `renderMockup` branch (iframe + sandbox + frame CSP + injected height bridge, skip sanitizer); boot message listener (auth + clamp + set height); `updateBlockContent` mockup branch (naive `srcdoc` reassign under spinner).
- `server.py`: add the `mockup` spec-forwarding branch in `_render_block_for_raw` (mirrors `choice`, ~2 lines). `versions.py`: add `"mockup"` to `_SPEC_KINDS`. No HTML rendering, no `step_id` gate change. `blocks.py` persistence is schemaless and already round-trips the spec.
- Skill docs: `SKILL.md` row, `pushing.md` cross-ref, new `mockup.md`.
- Result: Claude's full HTML/CSS/JS expressiveness, rendered, isolated, safe, and commentable at block granularity.

**Phase 2 — per-region annotation. ✅ SHIPPED.**
- Bridge: forwards clicks on `[data-annotate-id]` regions via `postMessage`.
- `static/script.js`: extracted `onHoverAction` body into `openAnnotation(block, type, {stepId, selectedText, selection})`; the boot message listener authenticates the frame by identity and calls it for `annotate:click` messages.
- `server.py`: relaxed the `step_id` gate so non-sequence kinds accept an opaque non-empty-string slug (sequence stays strict); replaced the markdown-step_id-422 test with acceptance + blank-string negative; added a mockup positive test. e2e `mockup-region.e2e.cjs` proves the round-trip.
- Accepted cosmetic loss (still deferred): hover-to-glow on a region needs a later `data-cardFocus` round-trip into the iframe; the comment card itself works.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Opaque-origin postMessage spoofing | `event.source === iframe.contentWindow` object-identity gate; strict shape validation; never eval/innerHTML message data. |
| Height feedback loop / infinite grow | bridge sets `html,body{margin:0}`, reads `scrollHeight`, rAF-debounce; host clamps to max; forbid viewport-keyed frame CSS in `mockup.md`. |
| Rewrite flicker (`srcdoc` reload) | Phase 1: reassign under existing `is-updating` overlay. Follow-up: offscreen double-buffer swap on first height message. |
| Malformed mockup HTML | Sandbox contains blast radius (no host DOM, no session). Placeholder for empty `spec.html`; ~1.5s height-timeout → default height + degraded flag; block stays whole-block commentable. |
| Emit-time over-reach (sandbox for a simple callout) | `mockup.md` "Do NOT use" bullets steer back to inline-HTML markdown. |
| Accidental phone-home from frame script | frame CSP `connect-src 'none'` / `default-src 'none'`. |

## Cross-cutting note (not in scope)

The server binds `0.0.0.0` and advertises a Tailscale hostname; the only thing protecting the session and the comment/submit machinery is the 64-bit `sid` in the URL. The sandbox does not change this perimeter, and it is acceptable under the stated "trust Claude, defend against accidents" model — but if mockups ever render attacker-influenced (non-Claude) content, revisit binding to `127.0.0.1`.

## Out of scope (YAGNI)

Stateful mockups that survive rewrite without reload; region hover-glow (Phase 2 cosmetic, deferred); `width`/`theme` spec fields; server-side HTML rendering/validation; host-page CSP.
