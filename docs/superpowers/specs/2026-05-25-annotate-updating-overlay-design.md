# Annotate — block "updating" overlay

## Problem

After a user submits a comment on a block, the annotate page marks the block as
"updating" while it waits for Claude to rewrite it. Today this state looks
frozen:

- The block content is dimmed via `opacity: 0.7`.
- A small grey `updating…` text label sits at the top-right of the block.

There is no motion and no signal of elapsed time. A long rewrite (10–60s of
streaming) is indistinguishable from a stuck request. Users have reported it
looks broken.

Adjacent gap: the "disabled" half of the contract isn't enforced — hover
actions (`✗`, `💬`) still appear over a block that is mid-rewrite, and the
SVG-step click handler on sequence diagrams still fires. A user can queue a
second annotation on a block that hasn't finished receiving the first rewrite.

## Goal

Replace the static "updating…" label with a clearly-alive overlay that also
makes the block uninteractive while the rewrite is in flight.

## Out of scope

- Determinate progress (we don't know how long Claude will take).
- Server-side changes — this is a CSS + thin JS change in `static/`.
- A "this is taking a while" / timeout / error treatment. If the rewrite
  never lands, the pill keeps ticking; the user has the chat above to
  decide what to do. We can add escalation later if it becomes a real
  complaint.
- Persistence of the elapsed timer across page reloads. The badge is a
  visual cue, not a data record.

## Design

### Visual treatment

While a block is in the updating state:

1. **Veil** — a warm tinted overlay covers the block:
   `background: rgba(253, 243, 234, 0.55)`, absolutely positioned to
   `inset: 0` inside the `section.block`. Same colour family as the existing
   accent and version-badge palette.
2. **Block content** — dimmed to `opacity: 0.42` with `filter: saturate(0.7)`,
   `pointer-events: none`, `user-select: none`. A short opacity transition
   (~180ms) keeps the change from feeling jarring.
3. **Overlay pill** — absolutely positioned at the centre of the block
   (`top: 50%; left: 50%; transform: translate(-50%, -50%)`), above the veil.
   Contents, left to right:
   - 12×12px circular spinner (1.5px border, accent top-colour, 0.85s linear
     spin).
   - The word `updating`.
   - Elapsed time in `mm:ss`, tabular-nums, accent-coloured, semibold.

   Pill chrome: rounded-full, white background at ~96% opacity, 1px accent
   border at 40% opacity, soft 4px halo using the accent at 8% opacity,
   `backdrop-filter: blur(4px)` for the frosted effect. `pointer-events: none`
   so clicks fall through to the (already disabled) block.

### Interaction lockdown

While `is-updating` is on the section:

- **Hover actions** (`✗`, `💬` floating buttons wired in
  `script.js:96-106`) must not appear. Implemented via CSS:
  `section.block.is-updating .hover-actions { display: none; }` or equivalent
  rule scoping the existing actions selector.
- **Sequence-diagram step clicks** (`script.js:244`) — already neutralised by
  `pointer-events: none` on the inner content, no extra wiring needed. Verify
  during implementation.
- **Cards / drafts in the sidebar are unaffected.** Other in-progress
  comment drafts for other blocks keep working.

### Lifecycle

| Event | Trigger | Effect |
|---|---|---|
| Enter updating | Submit promise resolves in `script.js:478` | Add `is-updating` to `section.block`; mount `.overlay-pill` node; start a 1s `setInterval` that updates the timer text |
| Tick | Every 1000ms | Recompute `mm:ss` from a stored start timestamp; update the timer span |
| Exit updating | Poll loop receives the new block version (`script.js:686`) | Clear the interval; remove the overlay pill; remove `is-updating` |
| Page reload during update | Browser refresh | Overlay is not restored. Block returns to its pre-update content until the next poll, at which point the new version arrives normally. Acceptable — visual-only state. |

### Concurrent updates

Each block owns its overlay node and its own interval. Tracking is per-block:
the interval id should be stored on the section element (e.g.
`section._updatingTimerId`) so the cleanup path in the poll loop can clear
exactly the right one.

## Files touched

- `skills/annotate/static/style.css` — replace the `.is-updating` opacity
  block (currently lines 231-237) with the veil + dim + pill styles, plus the
  hover-actions suppression rule.
- `skills/annotate/static/script.js`:
  - Submit-success branch (`~478-488`): replace the `updating-indicator` div
    construction with overlay-pill construction and timer setup.
  - Poll-replace branch (`~686-688`): clear the timer interval and remove the
    overlay pill in addition to removing `is-updating`.
- No `server.py`, `blocks.py`, or other Python changes.

## Testing

Manual verification on the annotate page:

1. Submit a comment on a block; confirm:
   - Veil + centered pill appear immediately.
   - Spinner spins, timer ticks `0:01`, `0:02`…
   - Hover actions do not appear on the block being updated.
   - Hover actions still appear on other blocks.
2. Wait for Claude to rewrite the block; confirm:
   - Pill and veil disappear at the moment the new content swaps in.
   - Hover actions reappear on the now-fresh block.
3. Submit comments on two blocks back-to-back; confirm both overlays
   animate independently and both clear independently.

The existing E2E smoke test (`tests/test_smoke_e2e_diagram.py`) exercises a
submit-update cycle. Adjust its assertions if they reference the old
`.updating-indicator` text element.

## Risks

- **Backdrop blur on older browsers.** If `backdrop-filter` is unsupported,
  the pill falls back to its white background — still readable, just less
  glassy. Acceptable.
- **Hover-actions selector drift.** If the hover-actions DOM changes in a
  future redesign, the suppression rule may need to be re-anchored. Low
  ongoing maintenance cost.
- **Long blocks where the pill scrolls out of view.** Centre-of-block is
  computed once on mount; on very tall blocks the user may scroll past the
  pill. Acceptable for v1; sticky positioning can be added later if it
  becomes a real complaint.
