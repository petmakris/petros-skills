# Annotate skill — post-submit lockdown and lifecycle

**Date:** 2026-05-15
**Status:** approved
**Files touched:** `skills/annotate/static/script.js`, `skills/annotate/static/style.css`, `skills/annotate/server.py`

## Problem

After a user clicks **Submit annotations**, the page today only updates a small status string in the footer and re-enables the submit button. Everything else — hover-action buttons, comment textareas, the submit button itself — remains interactive. A user who isn't paying attention can keep poking at a page whose annotations have already been delivered to Claude. There's no clear "you're done" signal, no protection against double-submit, and a browser refresh re-renders the same response from disk, allowing the user to (uselessly) start over against an already-consumed response.

The cancel and stale (409) terminal states have the same problem: cancel updates a status string but leaves the rest of the page interactive; the stale message asks the user to reload but doesn't enforce anything.

## Solution

Treat **submitted**, **cancelled**, and **stale** as terminal states. Each shows the same full-screen "session ended" overlay component, parameterised by message and primary action. The server marks consumed responses so a refresh after submit shows the waiting page instead of re-rendering a done response.

## Design

### Lifecycle

| State | Trigger | Page | Overlay message | Primary action |
|---|---|---|---|---|
| drafting | page load | interactive | — | — |
| submitting | click Submit | submit/cancel buttons disabled briefly | — | — |
| **submitted** | 200 on `/api/submit` | **locked** | "Annotations submitted. You can close this tab." | Refresh |
| submit failed | non-200, non-409 on `/api/submit` | interactive (retry) | — | — |
| **stale** | 409 on `/api/submit` | **locked** | "A newer response is available." | Refresh |
| cancelling | click Cancel + confirm | cancel button disabled briefly | — | — |
| **cancelled** | 200 on `/api/cancel` | **locked** | "Annotation round cancelled. You can close this tab." | Refresh |
| cancel failed | non-200 on `/api/cancel` | interactive (retry) | — | — |

All three locked states use the same overlay component. Only `message` and the secondary explanatory line differ. The action button is always **Refresh**, which calls `location.reload()`.

### Client — overlay component

The overlay is a single DOM element built once and parameterised at activation. Layout:

- A full-viewport positioned `<div class="locked-overlay">` with `position: fixed; inset: 0; z-index: 10000;` and a translucent dark backdrop (`rgba(20, 22, 28, 0.55)`) plus `backdrop-filter: blur(2px)`.
- A centred card (`<div class="locked-card">`) with rounded corners, padding, drop shadow.
- Inside the card: a large success/info icon, a title, a sub-message, and the Refresh button.
- Backdrop clicks do **not** dismiss — the overlay is intentionally non-dismissable.

The overlay's `pointer-events: auto;` swallows all clicks on the page underneath. To also remove the underlying elements (hover-action buttons, textareas, comment cards, submit/cancel buttons) from tab order and screen-reader focus, `enterLockedState` sets the `inert` attribute on `main.prose` and `footer.actions`. `inert` is widely supported (Chrome 102+, Safari 15.5+, Firefox 112+) and handles pointer events and keyboard focus uniformly. The submit and cancel buttons are also explicitly `disabled` as defense-in-depth.

An `enterLockedState(kind)` function in `script.js` handles all three variants:

```
kind ∈ { "submitted", "stale", "cancelled" }
```

The function:
1. Builds the overlay DOM (if not already built).
2. Populates the title and sub-message text per `kind`.
3. Sets `submitBtn.disabled = true` and `cancelBtn.disabled = true`.
4. Shows the overlay.

### Client — call sites

- `onSubmit()` success branch (currently line ~347): replace the `statusEl.textContent = "Submitted ✓"` line with `enterLockedState("submitted")`.
- `onSubmit()` 409 branch (line ~351): replace `statusEl.textContent = "Response is stale — reload the page."` with `enterLockedState("stale")`.
- `onSubmit()` other-error and `.catch` branches: leave the existing failure messages as they are. Re-enable the submit button so the user can retry (today this happens in `finally`).
- `onSubmit()` `finally`: today this unconditionally re-enables `submitBtn`. Refactor so the re-enable happens only in the failure paths — after a terminal state (`submitted`/`stale`), the button must stay disabled (and is also `inert`-protected by the overlay).
- `onCancel()` success branch (line ~389): replace the existing `statusEl.textContent = "Cancelled — you can close this tab."` with `enterLockedState("cancelled")`.

### Server — consumed-response markers

After `_handle_submit` writes `annotations.json` (line ~514), it additionally writes an empty marker file:

```python
(dirs["state_dir"] / "submitted").write_text("")
```

This mirrors the existing `cancelled` marker pattern at line 517.

`_serve_root` (line ~370) is updated to check for either marker before rendering the response:

```python
def _serve_root(self, dirs: dict) -> None:
    state_dir = dirs["state_dir"]
    if (state_dir / "submitted").exists() or (state_dir / "cancelled").exists():
        self._send_html(200, WAITING_HTML)
        return
    response_path = dirs["response_dir"] / "response.md"
    if not response_path.exists():
        self._send_html(200, WAITING_HTML)
        return
    # … existing path …
```

`_serve_raw` (line ~384) is left unchanged. The client-side script never runs against the waiting page (it lacks `script.js`), so `/raw` will not be fetched after a refresh. Direct access to `/raw` after submission is acceptable — `annotations.json` is the authoritative artifact.

### Marker lifetime — open assumption

The existing `cancelled` marker is keyed only by `state_dir` location, not by `response_id`. When Claude pushes a new response into the same session directory, the marker is **not** automatically cleared.

This matches the existing cancel behavior, so we adopt the same assumption: Claude-side orchestration (the part of the system that writes a new `response.md`) is responsible for clearing `state_dir/submitted` and `state_dir/cancelled` when starting a new annotation round.

If that assumption is wrong, the symptom would be: a new response gets pushed, but the page keeps showing the waiting screen because the old marker is still present. The fix would be to key markers by `response_id` (e.g., `state_dir/submitted-<response_id>`) and check the current `meta.json`'s `response_id` when reading. That refactor is deferred until the assumption is empirically falsified.

### Styling

Add styles for `.locked-overlay`, `.locked-card`, `.locked-icon`, `.locked-title`, `.locked-message`, `.locked-action` to `style.css`. The card sits centred via flexbox on the overlay. Theme-aware: use existing `--surface`, `--text`, `--text-dim`, `--accent` variables for card background, text colors, and the Refresh button.

The card visuals:

- 320px max-width, white-ish surface in light, dark surface in dark.
- Centred 28px icon (success: `✓` green; stale: `⟳` amber; cancelled: `⊘` dim).
- 15px title, 12px sub-message.
- Refresh button uses the existing `footer.actions button` styling (accent background) for consistency.

### Behavior already in place (unchanged)

- The 500ms hover linger, `preventScroll`, larger buttons, and the new top-right placement from prior turns remain — they're behind the overlay but unaffected by this change.
- Draft persistence (`localStorage`) is cleared on success (existing line `localStorage.removeItem(STORAGE_KEY)`). Cancelled and stale do **not** clear drafts; the user can refresh and see the page rendered freshly only after a new response arrives.

## Out of scope

- Reopening a "completed" annotation round (no UI affordance for re-editing after submit).
- Keying markers by `response_id` (see open assumption above).
- Mid-drafting refresh — clicking refresh while in the **drafting** state is a normal browser action; we don't add a custom warning.
- The earlier-deferred asks (no auto-accept; explicit accept button; greener wash for accepted blocks).
- Multiple-tab handling: if a user opens two tabs and submits in one, the other tab won't auto-update. Long-term we could leverage the existing `/poll` endpoint for this; not in scope here.

## Acceptance criteria

- After a successful submit, the page shows a full-screen overlay with a "session ended" card and a Refresh button. Clicking Refresh reloads the page; the reloaded page shows the waiting screen, not the submitted response.
- A 409 stale response shows the same overlay shape with the stale message and a Refresh button that loads the new response.
- A successful cancel shows the same overlay shape with the cancelled message and a Refresh button (refresh shows waiting).
- A failed submit (non-409, non-200) leaves the page interactive so the user can retry.
- Clicking the overlay backdrop does not dismiss it.
- The submit and cancel buttons remain disabled in all terminal states (no way to re-submit or re-cancel).

## Files touched

- `skills/annotate/static/script.js` — add `enterLockedState`, wire it into `onSubmit`/`onCancel`, remove the success-path re-enable.
- `skills/annotate/static/style.css` — add `.locked-overlay` and related styles.
- `skills/annotate/server.py` — write `submitted` marker in `_handle_submit`; add marker check in `_serve_root`.
- `skills/annotate/tests/test_server.py` — add coverage for the new marker behavior (submitted → root serves waiting; submitted marker present → root never opens response.md).
