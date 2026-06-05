# Annotate block search — design

**Date:** 2026-06-04
**Status:** Approved, ready for implementation plan
**Skill:** `skills/annotate`

## Summary

Add a fuzzy search box to the annotate web page that filters the rendered
blocks: as the user types, only blocks matching the query stay on screen
(non-matching blocks are hidden), a small count line shows "Showing X of Y
blocks", and matched terms are highlighted. Ported from the dashboard app's
header search (`~/projects/dashboard`), which uses Fuse.js.

The feature is **100% client-side**. It reads blocks already rendered in the
DOM and toggles their visibility. It does not touch the block model
(`blocks.py`), versioning, polling, the annotation pipeline, the watcher, or
any server endpoint. Clearing the query restores the page exactly as it was.
This is why it carries no risk to the Claude Code / agent side of the tool.

## Decisions (locked during brainstorming)

- **Filter behavior: hide non-matching** (mockup option A). Non-matching blocks
  get `display:none`; matching blocks remain. Mirrors the dashboard. Chosen over
  "highlight + dim" (keep all visible) and a hybrid toggle.
- **Matching: fuzzy via Fuse.js**, vendored locally into `static/`. The
  no-build/vanilla-JS nature of the skill is not an obstacle — Fuse ships as a
  single UMD file and `static/` is already served. Config mirrors the
  dashboard: `threshold: 0.3`, plus `ignoreLocation: true` and
  `includeMatches: true`.
- **Highlight: best-effort yellow `<mark>`** over matched ranges in visible
  blocks, applied with a text-node walk so it never corrupts the rendered HTML.
  A pure-typo fuzzy hit with no literal substring simply shows unhighlighted
  (the dashboard highlights nothing at all, so this is already better than
  parity).
- **Keyboard:** `/` focuses the box (unless a text field is already focused);
  `Esc` clears the query and blurs. Mirrors the dashboard.

## Architecture

### Index (client-side, no server round-trip)

On load, build a Fuse index over the blocks already in `main.prose`:

```
for each  section.block[data-block-id]:
    { id: section.dataset.blockId, text: section.textContent }
```

`textContent` yields clean plain text for every block kind, including diagram
blocks (it returns the step/actor labels). No access to the markdown source is
needed, so no server changes.

The index is **rebuilt when blocks change**. The page reconciles its DOM after
each poll (`script.js` `reconcile()`); to stay decoupled from that code,
`search.js` attaches a `MutationObserver` to `main.prose` and rebuilds the
index (debounced) on childList changes, then re-applies the active query so
new/edited blocks are immediately searchable.

### Search box (markup + style)

Added to the header's `.header-actions`, to the **left** of the Done button, in
`server.py serve_root()` (the header f-string at lines 70–76):

```html
<div class="header-search">
  <svg class="search-icon" ...><!-- magnifier --></svg>
  <input id="block-search" class="search-input" type="text"
         placeholder="Search blocks…" autocomplete="off" spellcheck="false">
  <span class="search-kbd">/</span>
</div>
```

Styled with existing core/style tokens (`--surface`, `--border`, `--accent`,
`--text-dim`) to match the dashboard look. New CSS lives in a small section
appended to `static/style.css`.

### Filter + count

On every input event:

1. If the query is empty → remove all `.search-hidden`, unwrap all highlights,
   remove the count line. Done.
2. Otherwise run `fuse.search(query)`; collect the matching block ids.
3. For each `section.block`: toggle `.search-hidden` (`display:none`) by
   membership. The block's trailing `.inline-comments` sibling is hidden with
   it.
4. Update a count line at the top of `main.prose`
   (`Showing <n> of <total> blocks`), created once and reused.
5. Re-apply highlights: unwrap previous `<mark class="search-hit">`, then walk
   text nodes of each visible block wrapping literal (case-insensitive)
   occurrences of the query terms.

### Keyboard

A `keydown` listener on `document`:

- `/` → if the active element is not an input/textarea, `preventDefault()` and
  focus `#block-search`.
- `Esc` → if `#block-search` is focused, clear it (triggering the empty-query
  restore) and blur.

## Files

| File | Change |
|------|--------|
| `static/fuse.min.js` | **New** — vendored Fuse.js UMD build (pinned version, ~5KB gzipped). |
| `static/search.js` | **New** — index build, MutationObserver rebuild, input filter, count line, highlight, keyboard shortcuts. Wrapped in an IIFE like `script.js`. |
| `static/style.css` | Append a small section: `.header-search`, `.search-input`, `.search-icon`, `.search-kbd`, `.search-hidden`, `mark.search-hit`, `.search-count`. |
| `server.py` | Header f-string: insert the `.header-search` markup into `.header-actions`. `head` assets: add `<script src="/static/fuse.min.js" defer></script>` and `<script src="/static/search.js" defer></script>`. |

No other files change. `STATIC_DIR` is already served (`static_dirs=[SHARED_STATIC_DIR, STATIC_DIR]`, server.py:327), so the two new static files are reachable at `/static/...` with no wiring.

## Edge cases

- **Empty query** — full restore (hidden removed, highlights unwrapped, count
  line removed).
- **No matches** — all blocks hidden, count line reads "Showing 0 of N blocks".
- **Block added/edited by poll** — MutationObserver rebuilds index and
  re-applies the active query.
- **Highlight safety** — only literal substrings inside text nodes are wrapped;
  HTML structure (links, code, diagram SVG) is never re-parsed, so it can't
  break. Diagram SVG text nodes are left unhighlighted to avoid layout shifts
  (matching still works via `textContent`).
- **Interaction with glossary popovers (`popover.js`)** — both touch text
  nodes. Highlight wraps/unwraps its own `mark.search-hit` only and runs after
  popover decoration; clearing the query fully unwraps, leaving popover markup
  intact.

## Testing

- Manual: run the annotate skill, push a multi-block response, type a query,
  confirm only matching blocks remain, count is correct, `/` and `Esc` work,
  clearing restores everything, and a fuzzy/typo query still matches.
- If the skill's test suite has a DOM/JS harness, add a unit test for the
  filter function (given a block list + query → expected visible ids) and for
  the highlight wrap/unwrap round-trip (highlight then clear === original HTML).

## Out of scope

- Searching annotation/comment text (only block content is indexed).
- Highlighting inside diagram SVGs.
- Persisting the query across reloads.
- Any server-side search endpoint.
