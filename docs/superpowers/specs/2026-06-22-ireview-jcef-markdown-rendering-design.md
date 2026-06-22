# Rich markdown rendering in the synthesis popup (JCEF)

**Date:** 2026-06-22
**Component:** `intellij-plugin-spike` (interactive_review IDE plugin)
**Status:** Approved design, pre-implementation

## Problem

The synthesis popup (`SynthesisPopup`) renders Claude's response through a
hand-rolled regex markdown parser (`MarkdownLinkRenderer`) into a Swing
`JEditorPane`. This is buggy: the parser only understands four constructs
(bold, links, inline code, fenced code blocks), so markdown tables render as
raw `|` pipes, and lists/headings/italics/blockquotes are dropped. Even with a
better parser, `JEditorPane` only speaks HTML 3.2 with very weak CSS, so the
output would still look rough. New markdown shapes keep surfacing rendering
bugs.

## Decision

Render markdown with **commonmark-java** (the reference CommonMark
implementation) and display it in **JCEF** (`JBCefBrowser`, embedded Chromium,
bundled with the IntelliJ platform).

Rationale — this is the *lowest* room for error, not the highest, because it
**removes** code we own and maintain (the regex parser + the fight with
JEditorPane's HTML engine) and delegates to two mature, battle-tested engines.
The remaining surface area is small and testable: markdown→HTML (one library
call), load into the browser, and a link-click bridge. It is also the only
option that fixes the whole class of bugs and can match the web companion's
visual quality.

Alternatives rejected:
- **commonmark-java + JEditorPane** — cheaper, but HTML 3.2 keeps rich content
  visually weak and every new construct is a fresh rendering surprise. A
  band-aid.
- **Bundled `org.intellij.plugins.markdown` preview component** — unstable
  internal API that shifts across IDE versions and fights custom link schemes
  and styling. More room for error, in a contract we do not control.

## Scope

In scope: the `SynthesisPopup` rendering path only.

Out of scope: `AnnotationsPanel`'s one-line truncated `snippet()` preview in the
side list (stays plain text); token-level syntax highlighting (clean
follow-up — v1 ships styled-but-uncolored code blocks).

## Components

Each unit has one job.

### `SynthesisHtmlRenderer` (new, pure, fully unit-tested)
`String markdown → String html`. The testable core; all logic lives here.
- commonmark-java parser + `commonmark-ext-gfm-tables` extension.
- Custom **link** handling (`AttributeProvider` or link renderer): `http(s)`
  destinations pass through unchanged; every other destination is rewritten to
  `href="ireview-nav://<destination>"`.
- Custom **inline-code** node renderer: `` `Foo` `` →
  `<a href="ireview-sym://Foo"><code>Foo</code></a>`, preserving today's
  behavior where every backtick is a clickable symbol jump.
- Wraps the body in theme-aware CSS (background/foreground/font pulled from the
  current `EditorColorsScheme`; dark/light aware; real bordered tables, styled
  code blocks, lists, headings). HTML escaping is handled by commonmark's own
  renderer (replaces the hand-rolled `escape()` and its recently-patched `"`
  bug).

### `SynthesisBrowser` (new, `Disposable`)
Wraps `JBCefBrowser` + `JBCefJSQuery`.
- `getComponent()` → the browser component for the popup's center card.
- `render(String markdown)` → `SynthesisHtmlRenderer.toHtml(...)` then
  `loadHTML(...)`.
- Installs a JS snippet that intercepts **all** `<a>` clicks, cancels default
  navigation, and forwards the `href` to Java via `JBCefJSQuery`.
- `dispose()` releases the browser and the JS query.

### `SynthesisLinkRouter` (new, extracted from `SynthesisPopup`)
`route(Project, String href)`. Shared by JCEF and fallback paths so behavior
cannot drift.
- `ireview-nav://path[:line]` → `OpenFileDescriptor` (reuses
  `MarkdownLinkRenderer.parseNavTarget`).
- `ireview-sym://identifier` → PSI short-names lookup + navigate / chooser
  (the existing `resolveAndNavigateSymbol` logic, moved here).
- otherwise → `BrowserUtil.browse`.

### `SynthesisPopup` (modified)
- On `show`, choose the renderer: if `JBCefApp.isSupported()` use
  `SynthesisBrowser`; else fall back to the existing `JEditorPane` +
  `MarkdownLinkRenderer` path (unchanged).
- The chosen component goes into the existing `centerCards` "synthesis" card
  (the "thinking" spinner card is unchanged).
- `renderCurrent()` calls `browser.render(markdown)` (JCEF) or the existing
  `setText(wrapHtml(...))` (fallback).
- Dispose the browser in the existing `JBPopupListener.onClosed`, fixing the
  native-resource side of the leak the code already flags.

### `MarkdownLinkRenderer` (kept)
Becomes the fallback-only renderer. Not deleted. Its existing test stays.

### `build.gradle` (modified)
Add `org.commonmark:commonmark` and `org.commonmark:commonmark-ext-gfm-tables`.
JCEF is part of the IntelliJ platform — no dependency needed.

## Data flow

SSE `thread-changed` → `renderCurrent()` → JCEF: `browser.render(markdown)` →
commonmark → `loadHTML()`. Click in page → JS query → `SynthesisLinkRouter`
→ IDE navigation. Identical to today from the user's side; rendered by
Chromium.

## Error handling & lifecycle

- JCEF unsupported → fallback renderer. The popup never breaks; worst case is
  today's behavior.
- commonmark is lenient (does not throw on arbitrary input); wrapped
  defensively regardless.
- `SynthesisBrowser` (browser + JS query) disposed on popup close.

## Testing

- **`SynthesisHtmlRendererTest`** (unit): GFM table → `<table>`; path link →
  `ireview-nav://`; `http(s)` link → external `href` unchanged; inline code →
  `ireview-sym://` wrapping; HTML escaping (incl. `"` and `<`); fenced code
  block → `<pre>`; unordered/ordered lists; headings.
- **Manual `runIde`**: render the exact table from the bug report and confirm
  it displays as a real table; click a path link, a backtick symbol, and an
  external link to confirm routing; confirm fallback path still renders when
  JCEF is forced off.

## Follow-ups (explicitly deferred)

- Token-level syntax highlighting in code blocks (bundle highlight.js or
  similar).
- Richer `AnnotationsPanel` snippet rendering, if ever wanted.
