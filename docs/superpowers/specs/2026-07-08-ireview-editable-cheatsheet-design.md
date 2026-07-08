# Editable Cheat-Sheet (in-panel edit mode) — design

**Date:** 2026-07-08
**Component:** `intellij-plugin-spike/` (Interactive Review plugin, package `com.petros.ireview`)
**Builds on:** the read-only cheat-sheet overlay (spec `2026-07-08-ireview-shortcut-cheatsheet-overlay-design.md`).

## Goal (plain)

Today you choose which shortcuts appear in the `⌃⌥⇧/` panel by hand-editing a bundled
`shortcuts.yml` and rebuilding. Replace that with an **in-panel edit mode**: open the
panel, click **Edit**, see a checklist of *every shortcut in your keymap*, tick the ones
to feature, give each a category, click **Done**. Picks are saved by the IDE itself —
no file, no rebuild.

The organizing UX principle (the user's, and it governs the layout): people find things
on screen by **stable order**, not absolute pixel position. So the panel is **always
alphabetical** — categories A→Z, shortcuts A→Z within each. Adding or removing a shortcut
never shuffles the others; the newcomer just slots into its alphabetical place.

## Decisions (settled during brainstorming)

- **Candidate pool = every keymap-bound action.** The edit list is built live from the
  active keymap (all actions that have a keyboard shortcut). No hand-maintained catalog;
  it follows your keymap automatically.
- **Grouping = user categories, A→Z within; categories themselves A→Z.** The keymap does
  not supply categories, so the user assigns one per *enabled* shortcut (only ~50, not all).
- **Tick-first, categorize-later.** Ticking a shortcut immediately shows it under a default
  **General** category; its row carries a category control to move it elsewhere or to a new
  category. Nothing is blocked waiting for categorization.
- **Persistence = IDE settings store** (`PersistentStateComponent`, application-level). Ticks
  and categories survive restarts/updates. Re-ticking a previously-disabled shortcut restores
  its remembered category.
- **Filter box** in edit mode (client-side substring filter) — necessary at a few hundred rows.
- **`shortcuts.yml` retires.** On first run its enabled entries seed the settings store
  (one-time import), after which the store is the single source of truth. The file + loader
  stay only for that import.
- **Rendering = JCEF** (interactive). The `JEditorPane` fallback is **view-only** (no Edit
  button) since Swing HTML can't host the bridge.

## Architecture

```
⌃⌥⇧/ → ShowShortcutsAction → ShortcutsOverlay (JBCefBrowser + JS⇄Java bridge)
                                   │
   VIEW  render ◄── ViewModelBuilder ──┐
   EDIT  render ◄── EditModelBuilder ──┤ read
                                        ├── ShortcutCatalog (active keymap → all bound shortcuts)
   bridge events ──► ShortcutPrefs ─────┘ read/write  (PersistentStateComponent: id → {category, enabled})
   (toggle / setCategory / newCategory / enterEdit / exitEdit)
```

Data flow, view mode: `ShortcutCatalog` (all bound shortcuts) ∩ `ShortcutPrefs` (enabled) →
group by category (default *General*) → sort → `ResolvedSheet` → `ShortcutsHtmlRenderer.renderView`.

Data flow, edit mode: `ShortcutCatalog` (all) + `ShortcutPrefs` (enabled/category per id) →
`EditSheet` (one A→Z list) → `ShortcutsHtmlRenderer.renderEdit`. User interaction posts events
through the bridge; `ShortcutsOverlay` applies them to `ShortcutPrefs` (persisted immediately)
and re-renders.

## Components (package `com.petros.ireview`)

### New

1. **`ShortcutPrefs`** — application-level `PersistentStateComponent<ShortcutPrefs.State>`,
   `@State(name="IReviewShortcutPrefs", storages=@Storage("ireview-shortcuts.xml"))`.
   `State` holds `List<Assignment>` where `Assignment{String actionId; String category; boolean enabled}`.
   API (pure logic over the in-memory state, unit-testable):
   - `boolean isEnabled(String actionId)`
   - `String categoryOf(String actionId)` — remembered category, or `null`
   - `void setEnabled(String actionId, boolean on)` — creates the assignment if absent; when
     enabling with no remembered category, leaves category `null` (view builder treats `null`
     as *General*)
   - `void setCategory(String actionId, String category)`
   - `List<String> categories()` — distinct categories in use, A→Z
   - `List<Assignment> assignments()`
   - `boolean isInitialized()` / `void markInitialized()` — a `boolean initialized` field in
     `State` gates the one-time YAML seed
   Disabling never deletes the assignment (category is remembered).

2. **`ShortcutCatalog`** — builds `List<CatalogEntry>` from the active keymap.
   `CatalogEntry{String actionId; String label; List<List<String>> groups}` (`groups` = key
   glyph tokens per keystroke, from `KeystrokeGlyphs`). Enumeration is behind an interface for
   testability, mirroring the existing `KeymapLookup` seam:
   - `interface KeymapCatalog { record Row(String actionId, String label, javax.swing.KeyStroke[] sequence){} List<Row> all(); }`
   - `IdeKeymapCatalog implements KeymapCatalog` — `KeymapManager.getActiveKeymap().getActionIdList()`,
     take each id's first `KeyboardShortcut`, resolve label via `ActionManager` presentation text
     (fallback to id), skip ids with no `KeyboardShortcut`. (IDE glue, thin.)
   - `ShortcutCatalog.build(KeymapCatalog source) -> List<CatalogEntry>` — pure: converts each
     `Row`'s keystrokes to glyph groups via `KeystrokeGlyphs`, sorts A→Z by label. Unit-testable
     with a fake source.

3. **`ViewModelBuilder`** — pure: `build(List<CatalogEntry> catalog, ShortcutPrefs prefs) -> ResolvedSheet`.
   Keeps only enabled ids present in the catalog; groups by `prefs.categoryOf(id)` (null → "General");
   categories A→Z; entries A→Z by label. Reuses the existing `ResolvedSheet` record.

4. **`EditSheet`** (record) + **`EditModelBuilder`** — `EditSheet{List<EditRow> rows; List<String> categories}`,
   `EditRow{String actionId; String label; List<List<String>> groups; boolean enabled; String category}`.
   `EditModelBuilder.build(catalog, prefs) -> EditSheet`: every catalog entry, A→Z by label, each row
   carrying its enabled flag and category (null shown as "—"); `categories` = `prefs.categories()`.
   Pure, unit-testable.

### Changed

5. **`ShortcutsHtmlRenderer`** — add:
   - `renderView(ResolvedSheet, boolean dark) -> String` (the current `toDocument`, renamed/kept;
     add an **✎ Edit** button to the title bar in the JCEF build).
   - `renderEdit(EditSheet, boolean dark) -> String` — the checklist: filter box, count line, one
     A→Z list of rows. Each row: `data-action="<id>"`, a checkbox reflecting `enabled`, the label,
     the key caps, and a category control (pill/`<select>`) for enabled rows / muted "—" for
     disabled. Includes the client-side JS: substring filter over rows, and calls to the injected
     bridge function for toggle / category-change / new-category / Done. All text HTML-escaped
     (labels, categories, tokens). Pure/testable on the produced HTML string.

6. **`ShortcutsOverlay`** — becomes interactive. Hosts `JBCefBrowser` with a `JBCefJSQuery`
   bridge (same mechanism as `SynthesisBrowser`). Injects a JS function the page calls with a
   JSON payload; handles event types:
   - `enterEdit` → render `renderEdit(...)` into the browser
   - `exitEdit` / `done` → render `renderView(...)`
   - `toggle {id,on}` → `prefs.setEnabled(id,on)` (persisted); if enabling and category is null,
     it will show under *General*
   - `setCategory {id,category}` → `prefs.setCategory(id,category)`
   - `newCategory {id}` → `Messages.showInputDialog` for a name, then `prefs.setCategory(id,name)`,
     re-render edit
   All bridge handlers run on the EDT via `SwingUtilities.invokeLater`. Gated on
   `JBCefApp.isSupported()`; the `JEditorPane` fallback renders **view mode only** (no Edit button).

7. **`ShowShortcutsAction`** — unchanged trigger; opens the overlay in view mode. Before first
   render, runs the one-time seed (below).

8. **One-time YAML seed** — a small step (in `ShowShortcutsAction` or a `PrefsSeeder` helper):
   if `!prefs.isInitialized()`, load bundled `shortcuts.yml` via the existing `ShortcutSheetLoader`,
   and for each `enabled` entry call `prefs.setCategory(actionId, entry.category)` +
   `prefs.setEnabled(actionId, true)`, then `prefs.markInitialized()`. Idempotent. `ShortcutSheet`,
   `ShortcutSheetLoader`, and `shortcuts.yml` are retained solely for this import and marked
   deprecated in comments; `ShortcutResolver`/`KeymapLookup` remain (still used by the view path
   via the catalog seam — see note).

> Note on reuse: `IdeKeymapLookup`/`KeymapLookup` resolved a *single* action; `KeymapCatalog`
> enumerates *all*. Keep `KeystrokeGlyphs` and `ResolvedSheet` as-is (reused). `ShortcutResolver`
> becomes unused by the new view path and may be removed if nothing else references it — the plan
> will confirm and delete it rather than leave dead code.

## Persistence format

`ireview-shortcuts.xml` in the IDE config dir, e.g.:
```xml
<application>
  <component name="IReviewShortcutPrefs">
    <option name="initialized" value="true" />
    <option name="assignments">
      <list>
        <Assignment><option name="actionId" value="GotoClass"/><option name="category" value="Navigation"/><option name="enabled" value="true"/></Assignment>
        ...
      </list>
    </option>
  </component>
</application>
```

## Error / edge handling

- Enabled id no longer in the catalog (key rebound away/removed) → silently omitted from view;
  assignment retained so it returns if the key comes back.
- Enabling with no category → shown under *General* until the user assigns one.
- No JCEF → view-only fallback (Edit hidden); the user can still see their current set.
- Malformed/absent `shortcuts.yml` at seed time → seed is skipped, `initialized` still set (start empty),
  logged. Never throws into the overlay.
- Category names are free text; trimmed; empty/blank rejected (no-op).

## Testing (pure JUnit 5, matching existing `src/test` style)

- **`ShortcutPrefsTest`** — `getState`/`loadState` round-trip; `setEnabled`/`setCategory`;
  category remembered while disabled; `categories()` distinct + A→Z; `isInitialized`/`markInitialized`.
- **`ShortcutCatalogTest`** — fake `KeymapCatalog` → `CatalogEntry` list, A→Z by label, glyphs
  correct (via real `KeystrokeGlyphs`), no-keystroke rows excluded.
- **`ViewModelBuilderTest`** — enabled-only; null category → *General*; categories A→Z; entries
  A→Z; enabled id absent from catalog is dropped.
- **`EditModelBuilderTest`** — all rows A→Z; enabled/category reflected; `categories` list.
- **`ShortcutsHtmlRendererTest`** (extend) — `renderEdit`: checked/unchecked checkboxes, category
  control present for enabled rows and "—" for disabled, `data-action` attributes, filter box,
  HTML-escaping; `renderView` still carries the Edit button.
- **`PrefsSeederTest`** — empty+uninitialized prefs + sample YAML → seeded enabled+categories;
  second run is a no-op; malformed YAML → still marks initialized, no throw.

IDE glue (`IdeKeymapCatalog`, the `ShortcutsOverlay` bridge/JCEF, `Messages` dialog) has no unit
tests by design — verified by building the sandbox and driving the panel (open → Edit → tick →
category → Done → reopen shows the set; restart persists).

## Iteration loop (unchanged)

```
cd intellij-plugin-spike
./gradlew test
./gradlew prepareSandbox   # then repoint symlink if the IDE build changed, restart IDEA
```
(Reminder: the sandbox path is keyed to the IDE build; after an IDE update, repoint
`~/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins/interactive-review-spike`.)
