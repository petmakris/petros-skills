# Annotate skill — hover-button placement redesign

**Date:** 2026-05-15
**Status:** approved
**Files touched:** `skills/annotate/static/style.css`

## Problem

In the annotate skill, the 4 action buttons (`✗ ? ✏ 💬`) appear on hover at `right: -44px` — outside the paragraph block. When adjacent paragraphs are short and close together, the button clusters visually collide. The placement also reads as cramped and "in the margin," which doesn't match the polish the page needs for a live presentation.

## Solution — Option A: gutter inside the block

Reserve permanent right-padding on every annotatable block so the buttons live in a dedicated gutter that is part of the block itself. On hover, tint the block and fade the buttons in at the top-right of the gutter. Because the buttons sit inside the block's own bounds, two adjacent blocks can never overlap visually — each cluster is constrained to its block.

## Design

### Block-level changes

Every annotatable block (`p`, `h1`–`h6`, `li`, `pre`, `blockquote`) gets a permanent `padding-right: 140px`. The button cluster occupies ~132px (4 × 28px buttons + 3 × 4px gaps + 8px right offset); 140px gives 8px of breathing room between text and the leftmost button. The prose container's `max-width` of 820px is unchanged; the effective text column becomes ~680px, which is still well within readable line-length.

### Hover tint

On hover, the block's background changes to a tint slightly stronger than today's `--surface-hover` so the active block clearly stands out:

- Light theme: `#ece9e0`
- Dark theme: `rgba(255, 255, 255, 0.06)`

The tint is added to the existing hover rule that already affects `p` and `li`; extending it to the other annotatable block types is part of this work.

### Button cluster

The `.hover-actions` element is repositioned from the right margin into the top-right corner of its block:

- `position: absolute; top: 6px; right: 8px;`
- Horizontal row (`flex-direction: row`) instead of vertical.
- Buttons remain 28×28 with a 4px gap, retaining the size and subtle shadow added in the prior turn.
- `bottom` is no longer set; the cluster is a fixed-size element pinned to the top-right corner of the block.

### Engaged-block interaction

Blocks that already carry an annotation keep their type-colored wash (`--type-{reject|question|rewrite|comment}-wash`) and their left bar. On hover, the standard hover tint blends on top of the wash so an engaged block hovered for further action is still distinguishable from a merely-hovered one. The buttons appear at top-right in the same position.

### Code blocks (`pre`)

Code blocks retain their own surface background and border. Their existing `padding: 12px 14px` is overridden to `padding: 12px 140px 12px 14px` so the right gutter exists inside the pre. The buttons appear over the code surface (not the prose background), keeping them visually anchored to the code block rather than floating in the prose margin.

Inline code (`code` not inside `pre`) and short code spans are unaffected — buttons live on the parent paragraph's gutter as for any other inline content.

### Mobile / no-hover

The existing `@media (hover: none)` rule continues to apply: the cluster shows at reduced opacity at all times, and expands fully on focus-within. No new mobile-specific logic.

### Behavior already in place (unchanged)

- 500ms linger timer after `mouseleave` so the cluster doesn't disappear too eagerly.
- `focus({ preventScroll: true })` on the new comment textarea so clicking a button doesn't scroll the page out from under the cursor.
- Button size, shadow, and visual style from the prior turn.

## Out of scope

These were raised in earlier conversation and are explicitly not part of this redesign:

- Removing auto-accept-by-default for paragraphs and adding an explicit "accept this block" action.
- A distinct (greener) background wash for explicitly-accepted blocks.

They are tracked for a separate change.

## Acceptance criteria

- Two adjacent short paragraphs both display their action buttons on hover with **no visual overlap** between the two clusters.
- A hovered block is clearly identifiable via its background tint.
- Code blocks, list items, blockquotes, and headings remain annotatable through the same button cluster.
- Engaged (already-annotated) blocks remain visually distinct from merely-hovered blocks.
- No regression in the linger timer or the comment-click behavior added in the prior turn.

## Files touched

- `skills/annotate/static/style.css` — the only file modified.
- `skills/annotate/static/script.js` — no change.
