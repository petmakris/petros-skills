# Annotate theme refresh — design spec

**Date:** 2026-05-15
**Surface:** `skills/annotate/static/style.css`, `skills/annotate/static/script.js`, `skills/annotate/server.py`, `skills/annotate/static/fonts/`
**Status:** Approved direction, pending implementation plan

## Goal

Refresh the annotate reading view to feel more professional and easier to read, and give every reader a personal choice of accent color via a UI control in the page header.

## Motivation

- Petros judged the current dark theme "not bad" but a touch generic — the typographic scale and contrast lean toward "default-AI-tool" rather than "well-designed reading surface."
- During brainstorming, three accent palettes (mint teal, lavender, vivid blue) all felt good to him in side-by-side mockups. Rather than picking one and forcing it on every user, the design promotes accent to a runtime preference — same as the existing light/dark toggle.
- A latent policy violation is folded in: the response HTML currently loads Bricolage Grotesque from `fonts.googleapis.com`, which breaks the repo-wide "no runtime network dependency / no CDN fonts" rule in `~/.claude/CLAUDE.md`. Fixing it now keeps the font Petros loves while honoring the policy.

## Non-goals

- No change to the body font (Bricolage Grotesque), the mono font (Monaspace Radon), or the four annotation-type colors (reject red, question amber, rewrite lavender, comment blue). Petros explicitly opted in to keeping the fonts; the type tokens are load-bearing UX signals that must stay stable across accent choices.
- No structural change to the annotation flow (hover-action buttons, engaged-type washes, comment cards, submit footer). The visual language of those components is refined, not rebuilt.
- No light-mode color rework beyond what the accent picker requires. The existing cream/Notion-like light theme stays.

## Design

### Token strategy

The current stylesheet conflates two concepts that need to split before accent can be user-selectable:

- **Page accent** (`--accent` / `--accent-fg`) — used for links, submit button, focus rings, blockquote rule, h4 display label, scrollbar.
- **Rewrite type token** (`--type-rewrite-fg` / `--type-rewrite-bg` / `--type-rewrite-wash`) — used to color rewrite annotations specifically.

Today both happen to be the same lavender. After this change they are decoupled. The rewrite token stays lavender forever. The page accent becomes one of three options, chosen by the user.

### Accent palettes

Three accent sets, each defined in both light and dark themes:

| Accent | Dark `--accent` | Light `--accent` | Selection bg, comment wash, etc. derived in lockstep |
|---|---|---|---|
| `mint` (default) | `#7ee0c2` | `#0d9488` | mint-tinted |
| `lavender` (legacy) | `#c4b5fd` | `#5b21b6` | lavender-tinted |
| `blue` | `#60a5fa` | `#2563eb` | blue-tinted |

Mint is the default for new readers because it's the most distinctive — distances annotate from generic AI-tooling palettes. Lavender exists for users who prefer continuity with the prior look.

Concretely the CSS adds a third axis to the existing token cascade:

```css
[data-theme="dark"][data-accent="mint"] { --accent: #7ee0c2; --accent-fg: #0e0f12; --selection: rgba(126,224,194,0.30); ... }
[data-theme="dark"][data-accent="lavender"] { --accent: #c4b5fd; ... }
[data-theme="dark"][data-accent="blue"] { --accent: #60a5fa; ... }
[data-theme="light"][data-accent="mint"] { --accent: #0d9488; ... }
... etc.
```

### Typographic refinements

Small, deliberate moves — the existing scale is close, just under-confident:

| Element | Before | After | Rationale |
|---|---|---|---|
| Body size | 16px | 15.5px | Slightly denser; matches the modern-sans target |
| Body line-height | 1.65 | 1.6 | Tighter rhythm in sans |
| Body letter-spacing | 0 | -0.003em | Optical tightening |
| Heading letter-spacing (h1/h2) | -0.005em | -0.022em | More confident |
| h3 size | 18px (regular) | 13px uppercase tracked | Acts as section label, not mini-heading — creates clearer hierarchy |
| Dark mode bg | `#1a1d22` | `#0e0f12` | Slightly deeper / cooler, makes accent pop |
| Dark mode surface | `#22262d` | `#16181d` | Matches new bg |
| Dark mode text | `#e8eaee` | `#d4d7de` | Slightly less harsh on the cooler bg |

Light mode tokens are unchanged except where `--accent` derives flow through.

### Accent picker UI

A swatch row sits in `.header-actions`, immediately left of the existing theme toggle:

```
[ • • • ]  [ ☀ ][ ☾ ]
```

Each swatch is a 24px button with a colored 12px dot inside. Active swatch has a subtle ring (`box-shadow: 0 0 0 1.5px var(--text-strong)`). Clicking sets `document.documentElement.dataset.accent = "mint" | "lavender" | "blue"` and persists to `localStorage` under key `annotate.accent`.

The existing pre-paint inline script in `RESPONSE_HTML` (currently reads `annotate.theme` before first render to avoid a flash) gets a sibling read for `annotate.accent`. Both are set before first paint.

### Font bundling fix

- Add `skills/annotate/static/fonts/BricolageGrotesque-Variable.woff2` to the repo (vendor the OFL-licensed variable font from the upstream `ateliertriay/bricolage` GitHub release, converted to woff2; include `BRICOLAGE_LICENSE.txt` alongside it, matching how Monaspace Radon is handled today).
- Remove the `<link href="https://fonts.googleapis.com/...">` line from `RESPONSE_HTML` in `server.py:264`.
- Add a `@font-face` block in `style.css` for Bricolage Grotesque pointing at the local woff2, sibling to the existing Monaspace one.

The rendered typography is unchanged. The only difference is the font now loads from `/static/fonts/` instead of Google.

## Components touched

- **`skills/annotate/static/style.css`** — token reshuffle (accent decoupled from rewrite-type), three accent variants × two themes, typographic micro-adjustments, new `@font-face` for Bricolage, swatch-row styles in `.header-actions`.
- **`skills/annotate/static/script.js`** — three new swatch button refs, `applyAccent(accent)` mirror of existing `applyTheme(theme)`, `persistAccent`, `getInitialAccent` (returns `"mint"` for first-time readers).
- **`skills/annotate/server.py`** — extend the pre-paint inline script in `RESPONSE_HTML` to also resolve `annotate.accent` before first paint; add three swatch buttons to the `.header-actions` markup; remove the Google Fonts `<link>` tag.
- **`skills/annotate/static/fonts/`** — add `BricolageGrotesque-Variable.woff2` + `BRICOLAGE_LICENSE.txt`.

## Acceptance

1. Loading a session at `http://localhost:<port>/s/<sid>/` with no `localStorage` shows the dark theme + mint accent by default.
2. The header shows three swatches left of the existing ☀/☾ toggle. Each swatch displays its color; the active one has a clear ring.
3. Clicking a swatch recolors the page (links, submit button, h3 labels, blockquote rule, accent-derived washes) instantly with no flash.
4. Refreshing the page preserves both the chosen accent and the chosen theme.
5. The four annotation type colors (reject red, question amber, rewrite lavender, comment blue) are unaffected by accent choice — they keep their current values across all six accent×theme combinations.
6. Visiting the response page with the browser DevTools Network panel set to "block 3rd-party requests" produces zero failed network requests for fonts (i.e., the CDN font dependency is gone and Bricolage loads from the local server).
7. Body, headings, and code blocks render in Bricolage Grotesque (or the same fallback chain as today) and Monaspace Radon respectively — visually identical to current rendering on a machine that previously had Bricolage cached from the CDN.
8. Existing tests under `skills/annotate/tests/` continue to pass; one new test asserts the swatch row is present in the rendered HTML.

## Out of scope

- A "system" accent that follows OS accent color settings (low value; OS accent vocabulary doesn't map cleanly to our three choices).
- More than three accents. Three is a strong default; expanding is a separate spec.
- Restyling the annotation hover-action buttons or comment cards. They benefit from the new accent system passively (no work needed).
- Migrating other skills' UI to match. Annotate is the standalone visual surface here.

## Open questions

None.
