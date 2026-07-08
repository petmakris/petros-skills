# Keyboard Shortcut Cheat-Sheet Overlay — design

**Date:** 2026-07-08
**Component:** `intellij-plugin-spike/` (Interactive Review plugin, package `com.petros.ireview`)

## Goal

Add a fast, read-only keyboard cheat-sheet to the IntelliJ plugin. Pressing
the trigger opens a wide HTML overlay listing a curated set of IDE shortcuts
the user keeps forgetting, flowing into as many columns as the window allows.
`Esc` closes it. The set is driven by a bundled `shortcuts.yml`; each entry
names a real IntelliJ action ID and the actual key combo is read **live from
the active keymap**, so what's shown never drifts from what's bound.

Non-goals: it is not interactive (no search, no tabs, no click-to-run), it does
not cover the plugin's own actions specifically (it's a general IDE cheat-sheet),
and it is not runtime-editable (edits go through the bundled YAML + rebuild).

## Decisions (settled during brainstorming)

- **Scope:** curated IntelliJ built-in shortcuts the user forgets — not the
  plugin's own actions, not the full keymap.
- **Key source:** resolved live from the action ID via the active keymap.
  YAML never contains literal key strings.
- **YAML home:** bundled in plugin resources, read-only at runtime. Editing =
  edit the resource + `./gradlew prepareSandbox` + restart.
- **Layout:** wide panel, auto-flowing columns (overflow option "B"), static.
  The user manages the enabled count to fit a single page.
- **Trigger:** `Ctrl+Shift+/` (`⌃⇧/`). Declared literally on the macOS keymaps
  so `control` stays Ctrl and is not auto-translated to `⌘`. Chosen over F1
  (F1 = Quick Documentation) so it "just works" with no keymap conflict.
- **Rendering:** JCEF (`JBCefBrowser`), gated on `JBCefApp.isSupported()`, with
  a `JEditorPane` fallback — mirroring the existing `SynthesisPopup`/
  `SynthesisBrowser` split. CSS multi-column requires JCEF; the fallback
  degrades to a plain grouped list.

## Data — `src/main/resources/shortcuts/shortcuts.yml`

Grouped by category, one `enabled` flag per entry so the full list stays
present and the user flips on what should show:

```yaml
categories:
  - name: Navigation
    entries:
      - action: GotoClass          # IntelliJ action ID (from Settings → Keymap)
        enabled: true
      - action: FindUsages
        label: Find Usages         # optional label override; else action's own text
        enabled: true
      - action: GotoSymbol
        enabled: false             # present but hidden
```

Parsed with the platform-bundled **SnakeYAML** (`org.yaml.snakeyaml`) — no
hand-rolled parser (per project convention: prefer an established library for
standard formats).

Field semantics:
- `action` (required): IntelliJ action ID. If unknown at runtime the entry is
  skipped and a warning is logged — one typo never blanks the whole sheet.
- `label` (optional): display text. When absent, the action's own presentation
  text from the keymap is used.
- `enabled` (required): only `true` entries are resolved and rendered.

## Components (package `com.petros.ireview`)

1. **`ShortcutSheetLoader`** — reads the bundled YAML off the classpath and
   returns the raw model: `ShortcutSheet` → `List<Category>` →
   `List<Entry{action,label,enabled}>`. Pure, testable. On missing/malformed
   YAML it returns an error-carrying result (rendered as a short message)
   rather than throwing; the SnakeYAML exception is logged.

2. **`KeystrokeGlyphs`** — pure helper converting a `javax.swing.KeyStroke`
   into an ordered list of cap tokens: modifiers in canonical order
   `⌃ ⌥ ⇧ ⌘` (mac glyphs) followed by the key glyph (e.g. `F7`, `⌫`, `↑`,
   `/`, letters). Isolated because it's the only fiddly rendering logic, and
   it's unit-testable with hand-built `KeyStroke`s independent of the IDE.

3. **`ShortcutResolver`** — thin adapter over `KeymapManager`. For each
   *enabled* entry: look up the active keymap's shortcuts for the action ID,
   take the first `KeyboardShortcut`, convert its keystroke(s) via
   `KeystrokeGlyphs`, and choose the label (override → else the action's
   presentation text). Produces a `ResolvedSheet` of `ResolvedEntry{label,
   List<List<capToken>>}`. Unknown action ID → skip + log; action present but
   unbound → include with a muted "unassigned" tag. Kept thin so the untestable
   (IDE-dependent) surface is minimal.

4. **`ShortcutsHtmlRenderer`** — pure: `ResolvedSheet` → HTML string in the
   screenshot style (category headers, keycap chips, "Press Esc to close"
   footer), using CSS `column-width` auto-flow so categories pack into as many
   columns as the width allows. Light/dark themed from the IDE LAF, mirroring
   how `SynthesisBrowser`/`SynthesisHtmlRenderer` pull editor colors. Testable.

5. **`ShortcutsOverlay`** — a `DialogWrapper` hosting a `JBCefBrowser` that
   loads the rendered HTML (`Esc`-to-close is free from `DialogWrapper`).
   Gated on `JBCefApp.isSupported()` like `SynthesisPopup`; when JCEF is
   unavailable, falls back to a `JEditorPane` rendering a plain grouped list
   (Swing HTML can't do CSS columns — graceful degradation, not pixel parity).

6. **`ShowShortcutsAction`** (`AnAction`) — builds the overlay from
   loader → resolver → renderer and shows it. Registered in `plugin.xml`,
   bound to `⌃⇧/`, and searchable via Find Action.

## Data flow

```
⌃⇧/ → ShowShortcutsAction
        → ShortcutSheetLoader        (bundled YAML → raw model)
        → ShortcutResolver           (+ active keymap → ResolvedSheet)
        → ShortcutsHtmlRenderer      (ResolvedSheet → HTML)
        → ShortcutsOverlay           (JBCefBrowser in DialogWrapper; Esc closes)
```

## plugin.xml registration

New `<action>` alongside the existing one, with literal-`control` bindings on
the macOS keymaps (the established pattern in this file for the Prettify action):

```xml
<action id="com.petros.ireview.ShowShortcutsAction"
        class="com.petros.ireview.ShowShortcutsAction"
        text="Show Shortcut Cheat-Sheet"
        description="Open a read-only cheat-sheet of curated IDE shortcuts.">
    <add-to-group group-id="HelpMenu" anchor="last"/>
    <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="control shift SLASH"/>
    <keyboard-shortcut keymap="Mac OS X"       first-keystroke="control shift SLASH"/>
    <keyboard-shortcut keymap="$default"        first-keystroke="control shift SLASH"/>
</action>
```

## Error handling

- Missing or malformed `shortcuts.yml` → overlay still opens, showing a short
  "couldn't load shortcuts.yml" message; the parse error is logged.
- Unknown action ID → that entry is skipped and logged; the rest render.
- No JCEF → `JEditorPane` fallback list.

## Testing (matches the existing pure-unit style in `src/test`)

- **`ShortcutSheetLoaderTest`** — sample YAML → model; honors `enabled`,
  `label` override, and category grouping; malformed YAML → error result, no throw.
- **`KeystrokeGlyphsTest`** — hand-built `KeyStroke`s → expected glyph token
  lists (e.g. `⌘⇧A`, `⌥F7`, `⌘⌫`), covering modifier ordering and special keys.
- **`ShortcutsHtmlRendererTest`** — a `ResolvedSheet` → HTML asserts presence of
  keycap chips, labels, category headers, and the column CSS.

`ShortcutResolver` is exercised manually in the sandbox (asserting on the live
keymap would need a platform test fixture); it is deliberately thin.

## Iteration loop (unchanged from HANDOFF.md)

```
cd intellij-plugin-spike
./gradlew test
./gradlew prepareSandbox
osascript -e 'quit app "IntelliJ IDEA"'   # then reopen
```
