# Editable Cheat-Sheet (in-panel edit mode) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hand-edited `shortcuts.yml` selection mechanism with an in-panel **edit mode**: a checklist of every keymap-bound shortcut, tick to feature, assign a category, saved by the IDE — no file, no rebuild. Panel stays fully alphabetical (categories A→Z, shortcuts A→Z within).

**Architecture:** A `PersistentStateComponent` (`ShortcutPrefs`) stores per-action `{category, enabled}`. `ShortcutCatalog` enumerates all keymap-bound shortcuts (behind a testable `KeymapCatalog` seam). Two pure builders compose catalog+prefs into a `ResolvedSheet` (view) or `EditSheet` (edit). `ShortcutsHtmlRenderer` renders both. `ShortcutsOverlay` hosts a `JBCefBrowser` with a JS⇄Java bridge (same mechanism as `SynthesisBrowser`) that applies edit events to `ShortcutPrefs` and re-renders. A one-time `PrefsSeeder` imports the legacy YAML.

**Tech Stack:** Java 25, IntelliJ Platform SDK, JCEF (`JBCefBrowser`/`JBCefJSQuery`), Gson (already a dependency), JUnit 5.

## Global Constraints

- Package `com.petros.ireview`; module `intellij-plugin-spike/`. Java language level 25.
- Tests are pure JUnit 5 under `intellij-plugin-spike/src/test/java/com/petros/ireview/`, matching the existing style; no platform test fixture.
- Trigger keystroke is already `⌃⌥⇧/` (`control alt shift SLASH`, macOS keymaps only) — do NOT change the `plugin.xml` `<keyboard-shortcut>` lines.
- Default category constant is the single string **`"General"`**, defined once as `ShortcutPrefs.DEFAULT_CATEGORY` and referenced everywhere (never re-literal it).
- Persisted state file is `ireview-shortcuts.xml` via `@State(name="IReviewShortcutPrefs", storages=@Storage("ireview-shortcuts.xml"))`, application-level service.
- The JS⇄Java bridge uses `JBCefJSQuery.create((JBCefBrowserBase) browser)` + `query.inject("json")`, exactly as `SynthesisBrowser` does; bridge handlers run on the EDT via `ApplicationManager.getApplication().invokeLater(...)`.
- Reuse existing `KeystrokeGlyphs` and `ResolvedSheet` unchanged. Gson is `com.google.gson.Gson` (already in `build.gradle.kts`).
- Run tests: `cd intellij-plugin-spike && ./gradlew test`. Build sandbox: `./gradlew prepareSandbox` (after an IDE update, repoint the plugin symlink to the current `IU-*` sandbox, then restart IDEA).

---

### Task 1: `ShortcutPrefs` persistent settings

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutPrefs.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutPrefsTest.java`

**Interfaces:**
- Produces: `ShortcutPrefs` (application service) with nested `State` and `Assignment`; constant `DEFAULT_CATEGORY = "General"`; methods `isEnabled(String)`, `categoryOf(String)` (nullable), `setEnabled(String,boolean)`, `setCategory(String,String)`, `List<String> categories()` (distinct, A→Z), `List<Assignment> assignments()`, `isInitialized()`, `markInitialized()`, `getState()`, `loadState(State)`.

- [ ] **Step 1: Write the failing test**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutPrefsTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShortcutPrefsTest {

    @Test
    void enableThenReadBack() {
        ShortcutPrefs p = new ShortcutPrefs();
        assertFalse(p.isEnabled("GotoClass"));
        p.setEnabled("GotoClass", true);
        assertTrue(p.isEnabled("GotoClass"));
    }

    @Test
    void categoryRememberedWhileDisabled() {
        ShortcutPrefs p = new ShortcutPrefs();
        p.setEnabled("GotoClass", true);
        p.setCategory("GotoClass", "Navigation");
        p.setEnabled("GotoClass", false);
        assertFalse(p.isEnabled("GotoClass"));
        assertEquals("Navigation", p.categoryOf("GotoClass")); // remembered
    }

    @Test
    void blankCategoryIgnoredAndTrimmed() {
        ShortcutPrefs p = new ShortcutPrefs();
        p.setEnabled("X", true);
        p.setCategory("X", "  ");
        assertNull(p.categoryOf("X"));
        p.setCategory("X", "  Refactor  ");
        assertEquals("Refactor", p.categoryOf("X"));
    }

    @Test
    void categoriesAreDistinctAndSorted() {
        ShortcutPrefs p = new ShortcutPrefs();
        p.setCategory("a", "Navigation");
        p.setCategory("b", "Editing");
        p.setCategory("c", "Navigation");
        assertEquals(List.of("Editing", "Navigation"), p.categories());
    }

    @Test
    void initializedFlag() {
        ShortcutPrefs p = new ShortcutPrefs();
        assertFalse(p.isInitialized());
        p.markInitialized();
        assertTrue(p.isInitialized());
    }

    @Test
    void stateRoundTrips() {
        ShortcutPrefs p = new ShortcutPrefs();
        p.setEnabled("GotoClass", true);
        p.setCategory("GotoClass", "Navigation");
        p.markInitialized();

        ShortcutPrefs restored = new ShortcutPrefs();
        restored.loadState(p.getState());
        assertTrue(restored.isEnabled("GotoClass"));
        assertEquals("Navigation", restored.categoryOf("GotoClass"));
        assertTrue(restored.isInitialized());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ShortcutPrefsTest"`
Expected: FAIL — `ShortcutPrefs` does not exist.

- [ ] **Step 3: Write the implementation**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutPrefs.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Application-level settings: which keymap actions the user has featured in the
 * cheat-sheet, and the category assigned to each. Persisted by the IDE to
 * {@code ireview-shortcuts.xml}. Disabling never forgets the category.
 */
@State(name = "IReviewShortcutPrefs", storages = @Storage("ireview-shortcuts.xml"))
public final class ShortcutPrefs implements PersistentStateComponent<ShortcutPrefs.State> {

    public static final String DEFAULT_CATEGORY = "General";

    /** One remembered action. Mutable bean with a no-arg ctor for IDE XML serialization. */
    public static final class Assignment {
        public String actionId;
        public String category;   // may be null
        public boolean enabled;
        public Assignment() {}
        public Assignment(String actionId, String category, boolean enabled) {
            this.actionId = actionId; this.category = category; this.enabled = enabled;
        }
    }

    public static final class State {
        public boolean initialized = false;
        public List<Assignment> assignments = new ArrayList<>();
    }

    private State state = new State();

    @Override public State getState() { return state; }
    @Override public void loadState(@NotNull State s) { this.state = s; }

    private Assignment find(String id) {
        for (Assignment a : state.assignments) if (id.equals(a.actionId)) return a;
        return null;
    }
    private Assignment findOrCreate(String id) {
        Assignment a = find(id);
        if (a == null) { a = new Assignment(id, null, false); state.assignments.add(a); }
        return a;
    }

    public boolean isEnabled(String actionId) { Assignment a = find(actionId); return a != null && a.enabled; }
    public String categoryOf(String actionId) { Assignment a = find(actionId); return a == null ? null : a.category; }
    public void setEnabled(String actionId, boolean on) { findOrCreate(actionId).enabled = on; }
    public void setCategory(String actionId, String category) {
        if (category == null || category.isBlank()) return;
        findOrCreate(actionId).category = category.trim();
    }
    public List<String> categories() {
        return state.assignments.stream()
                .map(a -> a.category)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }
    public List<Assignment> assignments() { return state.assignments; }
    public boolean isInitialized() { return state.initialized; }
    public void markInitialized() { state.initialized = true; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ShortcutPrefsTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutPrefs.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutPrefsTest.java
git commit -m "feat(ireview): ShortcutPrefs persistent settings for featured shortcuts"
```

---

### Task 2: Shortcut catalog from the keymap

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/KeymapCatalog.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/CatalogEntry.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutCatalog.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutCatalogTest.java`

**Interfaces:**
- Consumes: `KeystrokeGlyphs.tokens` (existing).
- Produces:
  - `interface KeymapCatalog { record Row(String actionId, String label, javax.swing.KeyStroke[] sequence){} List<Row> all(); }`
  - `record CatalogEntry(String actionId, String label, List<List<String>> groups)`
  - `ShortcutCatalog.build(KeymapCatalog source) -> List<CatalogEntry>` — rows with a keystroke, glyphs per keystroke, sorted A→Z by label (case-insensitive); label falls back to actionId.

- [ ] **Step 1: Write the failing test**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutCatalogTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShortcutCatalogTest {

    private static KeymapCatalog.Row row(String id, String label, KeyStroke... seq) {
        return new KeymapCatalog.Row(id, label, seq);
    }

    @Test
    void buildsGlyphsAndSortsByLabel() {
        KeymapCatalog src = () -> List.of(
            row("GotoClass", "Go to Class", KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.META_DOWN_MASK)),
            row("Duplicate", "Duplicate Line", KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.META_DOWN_MASK)));

        List<CatalogEntry> out = ShortcutCatalog.build(src);

        assertEquals(2, out.size());
        assertEquals("Duplicate Line", out.get(0).label());     // D before G
        assertEquals("Go to Class", out.get(1).label());
        assertEquals(List.of(List.of("⌘", "O")), out.get(1).groups());
    }

    @Test
    void rowsWithoutKeystrokeAreExcluded() {
        KeymapCatalog src = () -> List.of(
            row("Bound", "Bound", KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.META_DOWN_MASK)),
            row("Unbound", "Unbound"),                 // empty sequence
            new KeymapCatalog.Row("NullSeq", "NullSeq", null));

        List<CatalogEntry> out = ShortcutCatalog.build(src);
        assertEquals(1, out.size());
        assertEquals("Bound", out.get(0).label());
    }

    @Test
    void labelFallsBackToActionId() {
        KeymapCatalog src = () -> List.of(
            row("SomeId", "  ", KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.META_DOWN_MASK)));
        assertEquals("SomeId", ShortcutCatalog.build(src).get(0).label());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ShortcutCatalogTest"`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Create the interface**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/KeymapCatalog.java`:

```java
package com.petros.ireview;

import javax.swing.KeyStroke;
import java.util.List;

/**
 * Enumerates every action that has a keyboard shortcut in the active keymap.
 * Behind an interface so {@link ShortcutCatalog} is unit-testable with a fake;
 * the real implementation is {@code IdeKeymapCatalog}.
 */
public interface KeymapCatalog {
    /** {@code sequence} = the action's first keyboard shortcut (1 or 2 strokes); empty/null = unbound. */
    record Row(String actionId, String label, KeyStroke[] sequence) {}
    List<Row> all();
}
```

- [ ] **Step 4: Create the entry record**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/CatalogEntry.java`:

```java
package com.petros.ireview;

import java.util.List;

/** One selectable shortcut: id, display label, and key glyph groups (one list per keystroke). */
public record CatalogEntry(String actionId, String label, List<List<String>> groups) {}
```

- [ ] **Step 5: Create the builder**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutCatalog.java`:

```java
package com.petros.ireview;

import javax.swing.KeyStroke;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Builds the A→Z catalog of keymap-bound shortcuts from a {@link KeymapCatalog} source. */
public final class ShortcutCatalog {

    private ShortcutCatalog() {}

    public static List<CatalogEntry> build(KeymapCatalog source) {
        List<CatalogEntry> out = new ArrayList<>();
        for (KeymapCatalog.Row r : source.all()) {
            if (r.sequence() == null || r.sequence().length == 0) continue;
            List<List<String>> groups = new ArrayList<>();
            for (KeyStroke ks : r.sequence()) groups.add(KeystrokeGlyphs.tokens(ks));
            String label = (r.label() == null || r.label().isBlank()) ? r.actionId() : r.label();
            out.add(new CatalogEntry(r.actionId(), label, groups));
        }
        out.sort(Comparator.comparing(e -> e.label().toLowerCase(Locale.ROOT)));
        return out;
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ShortcutCatalogTest"`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add intellij-plugin-spike/src/main/java/com/petros/ireview/KeymapCatalog.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/CatalogEntry.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutCatalog.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutCatalogTest.java
git commit -m "feat(ireview): keymap shortcut catalog (all bound actions, A→Z)"
```

---

### Task 3: View model builder (catalog + prefs → ResolvedSheet)

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/ViewModelBuilder.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/ViewModelBuilderTest.java`

**Interfaces:**
- Consumes: `List<CatalogEntry>` (Task 2), `ShortcutPrefs` (Task 1), `ResolvedSheet` (existing).
- Produces: `ViewModelBuilder.build(List<CatalogEntry> catalog, ShortcutPrefs prefs) -> ResolvedSheet` — enabled entries only; grouped by category (null/blank → `ShortcutPrefs.DEFAULT_CATEGORY`); categories A→Z (case-insensitive); entries A→Z within (preserved from the pre-sorted catalog); `unassigned` always false.

- [ ] **Step 1: Write the failing test**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/ViewModelBuilderTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ViewModelBuilderTest {

    private static CatalogEntry entry(String id, String label) {
        return new CatalogEntry(id, label, List.of(List.of("⌘", "X")));
    }

    @Test
    void onlyEnabledGroupedAndSorted() {
        List<CatalogEntry> catalog = List.of(   // pre-sorted A→Z as the real catalog is
            entry("dup", "Duplicate Line"),
            entry("goc", "Go to Class"),
            entry("ren", "Rename"),
            entry("off", "Not Featured"));

        ShortcutPrefs prefs = new ShortcutPrefs();
        prefs.setEnabled("dup", true); prefs.setCategory("dup", "Editing");
        prefs.setEnabled("goc", true); prefs.setCategory("goc", "Navigation");
        prefs.setEnabled("ren", true); prefs.setCategory("ren", "Editing");
        // "off" left disabled

        ResolvedSheet sheet = ViewModelBuilder.build(catalog, prefs);

        assertFalse(sheet.isError());
        assertEquals(2, sheet.categories().size());
        assertEquals("Editing", sheet.categories().get(0).name());     // categories A→Z
        assertEquals("Navigation", sheet.categories().get(1).name());
        List<ResolvedSheet.ResolvedEntry> editing = sheet.categories().get(0).entries();
        assertEquals(List.of("Duplicate Line", "Rename"), List.of(editing.get(0).label(), editing.get(1).label()));
    }

    @Test
    void enabledWithoutCategoryGoesToGeneral() {
        List<CatalogEntry> catalog = List.of(entry("x", "Xylophone"));
        ShortcutPrefs prefs = new ShortcutPrefs();
        prefs.setEnabled("x", true); // no category
        ResolvedSheet sheet = ViewModelBuilder.build(catalog, prefs);
        assertEquals("General", sheet.categories().get(0).name());
    }

    @Test
    void enabledIdMissingFromCatalogIsDropped() {
        List<CatalogEntry> catalog = List.of(entry("present", "Present"));
        ShortcutPrefs prefs = new ShortcutPrefs();
        prefs.setEnabled("present", true);
        prefs.setEnabled("ghost", true);   // not in catalog (key rebound away)
        ResolvedSheet sheet = ViewModelBuilder.build(catalog, prefs);
        int total = sheet.categories().stream().mapToInt(c -> c.entries().size()).sum();
        assertEquals(1, total);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ViewModelBuilderTest"`
Expected: FAIL — `ViewModelBuilder` does not exist.

- [ ] **Step 3: Write the implementation**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/ViewModelBuilder.java`:

```java
package com.petros.ireview;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Composes the featured (enabled) shortcuts into the grouped, A→Z {@link ResolvedSheet} for view mode. */
public final class ViewModelBuilder {

    private ViewModelBuilder() {}

    public static ResolvedSheet build(List<CatalogEntry> catalog, ShortcutPrefs prefs) {
        // TreeMap(CASE_INSENSITIVE_ORDER) → categories A→Z; catalog is pre-sorted A→Z so
        // insertion order within each category is already alphabetical.
        Map<String, List<ResolvedSheet.ResolvedEntry>> byCategory =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (CatalogEntry e : catalog) {
            if (!prefs.isEnabled(e.actionId())) continue;
            String category = prefs.categoryOf(e.actionId());
            if (category == null || category.isBlank()) category = ShortcutPrefs.DEFAULT_CATEGORY;
            byCategory.computeIfAbsent(category, k -> new ArrayList<>())
                      .add(new ResolvedSheet.ResolvedEntry(e.label(), e.groups(), false));
        }

        List<ResolvedSheet.ResolvedCategory> categories = new ArrayList<>();
        for (Map.Entry<String, List<ResolvedSheet.ResolvedEntry>> en : byCategory.entrySet()) {
            categories.add(new ResolvedSheet.ResolvedCategory(en.getKey(), en.getValue()));
        }
        return new ResolvedSheet(categories, null);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ViewModelBuilderTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add intellij-plugin-spike/src/main/java/com/petros/ireview/ViewModelBuilder.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/ViewModelBuilderTest.java
git commit -m "feat(ireview): view model builder (enabled shortcuts, grouped A→Z)"
```

---

### Task 4: Edit model builder (catalog + prefs → EditSheet)

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/EditSheet.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/EditModelBuilder.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/EditModelBuilderTest.java`

**Interfaces:**
- Consumes: `List<CatalogEntry>` (Task 2), `ShortcutPrefs` (Task 1).
- Produces:
  - `record EditSheet(List<EditRow> rows, List<String> categories)` with nested
    `record EditRow(String actionId, String label, List<List<String>> groups, boolean enabled, String category)` (`category` nullable).
  - `EditModelBuilder.build(List<CatalogEntry> catalog, ShortcutPrefs prefs) -> EditSheet` — every catalog entry as a row (A→Z preserved), each carrying its enabled flag and remembered category; `categories` = `prefs.categories()`.

- [ ] **Step 1: Write the failing test**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/EditModelBuilderTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EditModelBuilderTest {

    private static CatalogEntry entry(String id, String label) {
        return new CatalogEntry(id, label, List.of(List.of("⌘", "X")));
    }

    @Test
    void everyEntryBecomesARowWithStateAndCategory() {
        List<CatalogEntry> catalog = List.of(entry("a", "Alpha"), entry("b", "Bravo"));
        ShortcutPrefs prefs = new ShortcutPrefs();
        prefs.setEnabled("a", true); prefs.setCategory("a", "Navigation");
        // "b" disabled, no category

        EditSheet sheet = EditModelBuilder.build(catalog, prefs);

        assertEquals(2, sheet.rows().size());
        EditSheet.EditRow a = sheet.rows().get(0);
        assertEquals("Alpha", a.label());
        assertTrue(a.enabled());
        assertEquals("Navigation", a.category());
        EditSheet.EditRow b = sheet.rows().get(1);
        assertFalse(b.enabled());
        assertNull(b.category());
    }

    @Test
    void categoriesListComesFromPrefs() {
        List<CatalogEntry> catalog = List.of(entry("a", "Alpha"), entry("b", "Bravo"));
        ShortcutPrefs prefs = new ShortcutPrefs();
        prefs.setCategory("a", "Navigation");
        prefs.setCategory("b", "Editing");
        EditSheet sheet = EditModelBuilder.build(catalog, prefs);
        assertEquals(List.of("Editing", "Navigation"), sheet.categories());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.EditModelBuilderTest"`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Create the record**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/EditSheet.java`:

```java
package com.petros.ireview;

import java.util.List;

/** Every keymap shortcut as a checkable row (A→Z), plus the categories currently in use. */
public record EditSheet(List<EditRow> rows, List<String> categories) {

    /** {@code category} may be null (shown as a placeholder). */
    public record EditRow(String actionId, String label, List<List<String>> groups,
                          boolean enabled, String category) {}
}
```

- [ ] **Step 4: Create the builder**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/EditModelBuilder.java`:

```java
package com.petros.ireview;

import java.util.ArrayList;
import java.util.List;

/** Composes the full keymap catalog + prefs into the edit-mode checklist model. */
public final class EditModelBuilder {

    private EditModelBuilder() {}

    public static EditSheet build(List<CatalogEntry> catalog, ShortcutPrefs prefs) {
        List<EditSheet.EditRow> rows = new ArrayList<>();
        for (CatalogEntry e : catalog) {   // catalog is already A→Z by label
            rows.add(new EditSheet.EditRow(
                    e.actionId(), e.label(), e.groups(),
                    prefs.isEnabled(e.actionId()), prefs.categoryOf(e.actionId())));
        }
        return new EditSheet(rows, prefs.categories());
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.EditModelBuilderTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add intellij-plugin-spike/src/main/java/com/petros/ireview/EditSheet.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/EditModelBuilder.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/EditModelBuilderTest.java
git commit -m "feat(ireview): edit model builder (full checklist model)"
```

---

### Task 5: Renderer — view Edit button + edit-mode HTML/JS

**Files:**
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutsHtmlRenderer.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutsHtmlRendererTest.java` (extend)

**Interfaces:**
- Consumes: `ResolvedSheet` (existing), `EditSheet` (Task 4).
- Produces:
  - `renderView(ResolvedSheet sheet, boolean dark, boolean editButton, String bridgeScript) -> String` — the current view document, plus an `✎ Edit` button (calling `ireviewSend`) when `editButton` is true, and the `bridgeScript` injected in a `<script>`.
  - Keep `toDocument(ResolvedSheet, boolean) -> String` as a delegate to `renderView(sheet, dark, false, "")` so existing callers/tests are unchanged.
  - `renderEdit(EditSheet sheet, boolean dark, String bridgeScript) -> String` — the checklist: filter box, count line, one A→Z list of rows (checkbox + label + caps + category `<select>` for enabled / muted "—" for disabled), a Done button, plus the client-side JS (filter, toggle, category-change) and the injected `bridgeScript`. All text HTML-escaped.

**Bridge/JS contract (the page calls `ireviewSend(jsonString)`; `bridgeScript` defines `ireviewSend`):**
- Edit button → `ireviewSend('{"type":"enterEdit"}')`
- Done button / (Esc handled by dialog) → `ireviewSend('{"type":"exitEdit"}')`
- Checkbox change → `ireviewSend(JSON.stringify({type:"toggle",id:ID,on:checked}))`
- Category `<select>` change to a real category → `ireviewSend(JSON.stringify({type:"setCategory",id:ID,category:VALUE}))`
- Category `<select>` change to the `"__new__"` sentinel → `ireviewSend(JSON.stringify({type:"newCategory",id:ID}))`

- [ ] **Step 1: Write the failing tests (append to the existing test class)**

Add these methods to `intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutsHtmlRendererTest.java`:

```java
    @Test
    void viewIncludesEditButtonWhenRequested() {
        var entry = new ResolvedSheet.ResolvedEntry("Go to Class", java.util.List.of(java.util.List.of("⌘", "O")), false);
        var cat = new ResolvedSheet.ResolvedCategory("Navigation", java.util.List.of(entry));
        var sheet = new ResolvedSheet(java.util.List.of(cat), null);

        String withBtn = ShortcutsHtmlRenderer.renderView(sheet, false, true, "");
        assertTrue(withBtn.contains("enterEdit"), withBtn);

        String noBtn = ShortcutsHtmlRenderer.renderView(sheet, false, false, "");
        assertFalse(noBtn.contains("enterEdit"), noBtn);
    }

    @Test
    void toDocumentStillWorksWithoutEditButton() {
        var sheet = new ResolvedSheet(java.util.List.of(), null);
        assertFalse(ShortcutsHtmlRenderer.toDocument(sheet, false).contains("enterEdit"));
    }

    @Test
    void editRendersCheckboxesCategoriesFilterAndEscapes() {
        var enabled = new EditSheet.EditRow("goc", "Go to Class",
                java.util.List.of(java.util.List.of("⌘", "O")), true, "Navigation");
        var disabled = new EditSheet.EditRow("blk", "a <b> & c",
                java.util.List.of(java.util.List.of("⌘", "⌥", "/")), false, null);
        var sheet = new EditSheet(java.util.List.of(enabled, disabled), java.util.List.of("Navigation"));

        String html = ShortcutsHtmlRenderer.renderEdit(sheet, false, "");

        assertTrue(html.startsWith("<!doctype html>"), html);
        assertTrue(html.contains("type=\"checkbox\""), html);
        assertTrue(html.contains("data-action=\"goc\""), html);        // row id
        assertTrue(html.contains("checked"), html);                     // enabled row is checked
        assertTrue(html.contains("Navigation"), html);                  // category option
        assertTrue(html.contains("__new__"), html);                     // new-category sentinel
        assertTrue(html.contains("oninput"), html);                     // filter box
        assertTrue(html.contains("a &lt;b&gt; &amp; c"), html);         // label escaped
        assertFalse(html.contains("a <b> & c"), html);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ShortcutsHtmlRendererTest"`
Expected: FAIL — `renderView` / `renderEdit` / `EditSheet` symbols not found.

- [ ] **Step 3: Rewrite `ShortcutsHtmlRenderer`**

Replace the whole file `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutsHtmlRenderer.java` with:

```java
package com.petros.ireview;

import java.util.List;

/**
 * Renders the cheat-sheet as a self-contained HTML document. Two modes:
 * {@link #renderView} (read-only, optional Edit button) and {@link #renderEdit}
 * (checklist with checkboxes, category selectors, filter). Interaction is routed
 * through a page-defined {@code ireviewSend(json)} function whose body is supplied
 * as {@code bridgeScript} (a JCEF JS-query injection); empty string in tests.
 */
public final class ShortcutsHtmlRenderer {

    private ShortcutsHtmlRenderer() {}

    /** Backward-compatible view render without an Edit button (fallback + legacy tests). */
    public static String toDocument(ResolvedSheet sheet, boolean dark) {
        return renderView(sheet, dark, false, "");
    }

    public static String renderView(ResolvedSheet sheet, boolean dark, boolean editButton, String bridgeScript) {
        StringBuilder sb = new StringBuilder(4096);
        head(sb, dark);
        sb.append("<div class=\"bar\"><h2>Keyboard Shortcuts</h2>");
        if (editButton) {
            sb.append("<button class=\"btn\" onclick=\"ireviewSend('{&quot;type&quot;:&quot;enterEdit&quot;}')\">&#9998; Edit</button>");
        }
        sb.append("</div>");

        if (sheet.isError()) {
            sb.append("<p class=\"err\">").append(esc(sheet.error())).append("</p>");
        } else if (sheet.categories().isEmpty()) {
            sb.append("<p class=\"err\">No shortcuts featured yet. Click Edit to choose some.</p>");
        } else {
            sb.append("<div class=\"board\">");
            for (ResolvedSheet.ResolvedCategory cat : sheet.categories()) {
                sb.append("<div class=\"group\"><div class=\"cat\">").append(esc(cat.name())).append("</div>");
                for (ResolvedSheet.ResolvedEntry e : cat.entries()) {
                    sb.append("<div class=\"row\"><span class=\"name\">").append(esc(e.label())).append("</span>");
                    sb.append("<span class=\"keys\">");
                    if (e.unassigned()) {
                        sb.append("<span class=\"tag\">unassigned</span>");
                    } else {
                        for (List<String> group : e.groups()) {
                            sb.append("<span class=\"grp\">");
                            for (String token : group) sb.append("<span class=\"cap\">").append(esc(token)).append("</span>");
                            sb.append("</span>");
                        }
                    }
                    sb.append("</span></div>");
                }
                sb.append("</div>");
            }
            sb.append("</div>");
        }
        sb.append("<div class=\"foot\">Press <span class=\"cap\">Esc</span> to close</div>");
        script(sb, bridgeScript, "");
        return sb.append("</body></html>").toString();
    }

    public static String renderEdit(EditSheet sheet, boolean dark, String bridgeScript) {
        StringBuilder sb = new StringBuilder(8192);
        head(sb, dark);
        long selected = sheet.rows().stream().filter(EditSheet.EditRow::enabled).count();

        sb.append("<div class=\"bar\"><h2>Keyboard Shortcuts &middot; <span class=\"muted\">Editing</span></h2>")
          .append("<button class=\"btn primary\" onclick=\"ireviewSend('{&quot;type&quot;:&quot;exitEdit&quot;}')\">&#10003; Done</button></div>");
        sb.append("<div class=\"search\"><input id=\"flt\" placeholder=\"Filter shortcuts…\" oninput=\"flt(this.value)\"></div>");
        sb.append("<div class=\"count\">Showing all <b>").append(sheet.rows().size())
          .append("</b> shortcuts &middot; <b id=\"selN\" class=\"acc\">").append(selected).append("</b> selected</div>");

        sb.append("<div class=\"list\">");
        for (EditSheet.EditRow r : sheet.rows()) {
            String id = esc(r.actionId());
            String jid = jsStr(r.actionId());
            sb.append("<label class=\"erow ").append(r.enabled() ? "on" : "off")
              .append("\" data-action=\"").append(id).append("\" data-label=\"").append(esc(r.label().toLowerCase())).append("\">");
            sb.append("<input type=\"checkbox\"").append(r.enabled() ? " checked" : "")
              .append(" onchange=\"tog(").append(jid).append(",this)\">");
            sb.append("<span class=\"ename\">").append(esc(r.label())).append("</span>");
            sb.append("<span class=\"ekeys\">");
            for (List<String> group : r.groups()) {
                sb.append("<span class=\"grp\">");
                for (String token : group) sb.append("<span class=\"cap\">").append(esc(token)).append("</span>");
                sb.append("</span>");
            }
            sb.append("</span>");
            categorySelect(sb, r, sheet.categories(), jid);
            sb.append("</label>");
        }
        sb.append("</div>");
        sb.append("<div class=\"foot\">Changes save automatically &middot; <span class=\"cap\">Esc</span> or Done to finish</div>");

        String editJs =
            "function tog(id,el){var r=el.closest('.erow');r.classList.toggle('on',el.checked);r.classList.toggle('off',!el.checked);"
          + "var s=r.querySelector('.catsel');if(s)s.style.visibility=el.checked?'visible':'hidden';"
          + "var n=document.getElementById('selN');n.textContent=document.querySelectorAll('.erow input:checked').length;"
          + "ireviewSend(JSON.stringify({type:'toggle',id:id,on:el.checked}));}"
          + "function cat(id,el){if(el.value==='__new__'){ireviewSend(JSON.stringify({type:'newCategory',id:id}));return;}"
          + "ireviewSend(JSON.stringify({type:'setCategory',id:id,category:el.value}));}"
          + "function flt(q){q=q.toLowerCase();document.querySelectorAll('.erow').forEach(function(r){"
          + "r.style.display=r.getAttribute('data-label').indexOf(q)>=0?'':'none';});}";
        script(sb, bridgeScript, editJs);
        return sb.append("</body></html>").toString();
    }

    private static void categorySelect(StringBuilder sb, EditSheet.EditRow r, List<String> categories, String jid) {
        String current = (r.category() == null || r.category().isBlank())
                ? ShortcutPrefs.DEFAULT_CATEGORY : r.category();
        sb.append("<select class=\"catsel\" onchange=\"cat(").append(jid).append(",this)\"")
          .append(r.enabled() ? "" : " style=\"visibility:hidden\"").append(">");
        boolean seen = false;
        // ensure DEFAULT + current are always options, plus the known categories
        java.util.LinkedHashSet<String> opts = new java.util.LinkedHashSet<>();
        opts.add(ShortcutPrefs.DEFAULT_CATEGORY);
        opts.addAll(categories);
        opts.add(current);
        for (String c : opts) {
            boolean sel = c.equalsIgnoreCase(current);
            if (sel) seen = true;
            sb.append("<option").append(sel ? " selected" : "").append(">").append(esc(c)).append("</option>");
        }
        if (!seen) sb.append("<option selected>").append(esc(current)).append("</option>");
        sb.append("<option value=\"__new__\">＋ New category…</option>");
        sb.append("</select>");
    }

    private static void head(StringBuilder sb, boolean dark) {
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><style>").append(css(dark)).append("</style></head><body>");
    }

    private static void script(StringBuilder sb, String bridgeScript, String extra) {
        sb.append("<script>").append(bridgeScript == null ? "" : bridgeScript).append(extra).append("</script>");
    }

    private static String css(boolean dark) {
        String bg = dark ? "#2b2d30" : "#ffffff";
        String ink = dark ? "#dfe1e5" : "#1f2328";
        String muted = dark ? "#8b9096" : "#8a9099";
        String line = dark ? "#3c3f43" : "#e6e8eb";
        String capBg = dark ? "#3a3d41" : "#f6f7f9";
        String capLine = dark ? "#54585d" : "#d6dade";
        String capInk = dark ? "#d0d3d8" : "#3a4048";
        String rowHover = dark ? "#34373b" : "#f7f8fa";
        String accSoft = dark ? "#20364f" : "#eaf1fe";
        String accInk = dark ? "#8ab4f8" : "#1e5fd0";
        return ""
            + "*{box-sizing:border-box}"
            + "body{margin:0;background:" + bg + ";color:" + ink + ";font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;}"
            + ".bar{display:flex;align-items:center;justify-content:space-between;padding:14px 20px;border-bottom:1px solid " + line + ";}"
            + ".bar h2{margin:0;font-size:15px;font-weight:650;} .muted{color:" + muted + ";font-weight:500;} .acc{color:" + accInk + ";}"
            + ".btn{font-size:12.5px;font-weight:600;padding:6px 13px;border-radius:8px;border:1px solid " + capLine + ";background:transparent;color:" + ink + ";cursor:pointer;}"
            + ".btn.primary{background:#3b82f6;border-color:#3b82f6;color:#fff;}"
            + ".board{padding:16px 22px;column-width:250px;column-gap:36px;} .group{break-inside:avoid;margin-bottom:18px;}"
            + ".cat{font-size:10.5px;font-weight:700;letter-spacing:.09em;color:" + muted + ";text-transform:uppercase;margin:0 0 8px;}"
            + ".row{display:flex;align-items:center;gap:12px;padding:5px 0;} .name{font-size:13px;}"
            + ".keys,.ekeys{margin-left:auto;display:flex;gap:5px;flex:0 0 auto;} .grp{display:inline-flex;gap:5px;}"
            + ".cap{min-width:22px;height:22px;padding:0 6px;border-radius:6px;background:" + capBg + ";border:1px solid " + capLine + ";border-bottom-width:2px;color:" + capInk + ";font-size:11.5px;font-weight:600;display:inline-flex;align-items:center;justify-content:center;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;}"
            + ".tag{font-size:10.5px;color:" + muted + ";font-style:italic;}"
            + ".err{padding:22px;color:" + muted + ";}"
            + ".foot{text-align:center;color:" + muted + ";font-size:11.5px;padding:10px;border-top:1px solid " + line + ";} .foot .cap{display:inline-flex;vertical-align:middle;margin:0 2px;}"
            + ".search{padding:12px 20px 4px;} .search input{width:100%;border:1px solid " + capLine + ";border-radius:8px;padding:8px 11px;font-size:12.5px;background:" + bg + ";color:" + ink + ";}"
            + ".count{font-size:11.5px;color:" + muted + ";padding:4px 22px 6px;}"
            + ".list{max-height:360px;overflow-y:auto;padding:2px 10px 10px;}"
            + ".erow{display:flex;align-items:center;gap:12px;padding:8px 12px;border-radius:8px;cursor:pointer;} .erow:hover{background:" + rowHover + ";}"
            + ".erow .ename{font-size:13px;flex:1 1 auto;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;} .erow.off .ename{color:" + muted + ";}"
            + ".catsel{flex:0 0 auto;font-size:11.5px;font-weight:600;color:" + accInk + ";background:" + accSoft + ";border:1px solid " + capLine + ";border-radius:999px;padding:3px 9px;}"
            ;
    }

    /** HTML text escape. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** A JS single-quoted string literal for an action id (ids are [A-Za-z0-9._$-]; escape defensively). */
    private static String jsStr(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ShortcutsHtmlRendererTest"`
Expected: PASS (the 6 prior tests + 3 new).

- [ ] **Step 5: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutsHtmlRenderer.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutsHtmlRendererTest.java
git commit -m "feat(ireview): renderer — view Edit button + interactive edit-mode HTML"
```

---

### Task 6: One-time YAML seed

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/PrefsSeeder.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/PrefsSeederTest.java`

**Interfaces:**
- Consumes: `ShortcutSheet` + `ShortcutSheetLoader` (existing), `ShortcutPrefs` (Task 1).
- Produces: `PrefsSeeder.seedIfNeeded(ShortcutPrefs prefs, ShortcutSheet legacy)` — if `!prefs.isInitialized()`, copy each *enabled* legacy entry into prefs (`setEnabled(actionId,true)` + `setCategory(actionId, categoryName)`), then `markInitialized()`. Idempotent; a null/error legacy sheet still marks initialized.

- [ ] **Step 1: Write the failing test**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/PrefsSeederTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrefsSeederTest {

    private static final String YAML = """
        categories:
          - name: Navigation
            entries:
              - action: GotoClass
                enabled: true
              - action: GotoSymbol
                enabled: false
          - name: Refactor
            entries:
              - action: RenameElement
                label: Rename
                enabled: true
        """;

    @Test
    void seedsEnabledEntriesWithCategory() {
        ShortcutPrefs prefs = new ShortcutPrefs();
        PrefsSeeder.seedIfNeeded(prefs, ShortcutSheetLoader.parse(YAML));

        assertTrue(prefs.isInitialized());
        assertTrue(prefs.isEnabled("GotoClass"));
        assertEquals("Navigation", prefs.categoryOf("GotoClass"));
        assertTrue(prefs.isEnabled("RenameElement"));
        assertEquals("Refactor", prefs.categoryOf("RenameElement"));
        assertFalse(prefs.isEnabled("GotoSymbol"));   // was disabled in YAML
    }

    @Test
    void secondCallIsNoOp() {
        ShortcutPrefs prefs = new ShortcutPrefs();
        PrefsSeeder.seedIfNeeded(prefs, ShortcutSheetLoader.parse(YAML));
        prefs.setEnabled("GotoClass", false);           // user later un-featured it
        PrefsSeeder.seedIfNeeded(prefs, ShortcutSheetLoader.parse(YAML)); // must NOT re-enable
        assertFalse(prefs.isEnabled("GotoClass"));
    }

    @Test
    void errorSheetStillMarksInitialized() {
        ShortcutPrefs prefs = new ShortcutPrefs();
        PrefsSeeder.seedIfNeeded(prefs, ShortcutSheet.error("boom"));
        assertTrue(prefs.isInitialized());
        assertTrue(prefs.assignments().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.PrefsSeederTest"`
Expected: FAIL — `PrefsSeeder` does not exist.

- [ ] **Step 3: Write the implementation**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/PrefsSeeder.java`:

```java
package com.petros.ireview;

/**
 * One-time migration: seeds {@link ShortcutPrefs} from the legacy bundled
 * {@code shortcuts.yml} the first time the panel is opened. After that the
 * settings store is the sole source of truth and the YAML is vestigial.
 */
public final class PrefsSeeder {

    private PrefsSeeder() {}

    public static void seedIfNeeded(ShortcutPrefs prefs, ShortcutSheet legacy) {
        if (prefs.isInitialized()) return;
        if (legacy != null && !legacy.isError()) {
            for (ShortcutSheet.Category category : legacy.categories()) {
                for (ShortcutSheet.Entry entry : category.entries()) {
                    if (!entry.enabled()) continue;
                    prefs.setEnabled(entry.action(), true);
                    prefs.setCategory(entry.action(), category.name());
                }
            }
        }
        prefs.markInitialized();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.PrefsSeederTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add intellij-plugin-spike/src/main/java/com/petros/ireview/PrefsSeeder.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/PrefsSeederTest.java
git commit -m "feat(ireview): one-time seed of prefs from legacy shortcuts.yml"
```

---

### Task 7: IDE wiring — catalog impl, interactive overlay, action, service registration, cleanup

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/IdeKeymapCatalog.java`
- Rewrite: `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutsOverlay.java`
- Rewrite: `intellij-plugin-spike/src/main/java/com/petros/ireview/ShowShortcutsAction.java`
- Modify: `intellij-plugin-spike/src/main/resources/META-INF/plugin.xml` (register `ShortcutPrefs` service)
- Modify: `intellij-plugin-spike/README.md`
- Delete: `ShortcutResolver.java`, `KeymapLookup.java`, `IdeKeymapLookup.java`, and `src/test/java/com/petros/ireview/ShortcutResolverTest.java` (now dead — see Step 6)

**Interfaces:**
- Consumes everything from Tasks 1–6. No unit tests (IDE glue / JCEF); verified by full-suite build + sandbox smoke.

- [ ] **Step 1: Create the real keymap catalog**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/IdeKeymapCatalog.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;

import javax.swing.KeyStroke;
import java.util.ArrayList;
import java.util.List;

/** {@link KeymapCatalog} over the live active keymap: every action with a keyboard shortcut. */
public final class IdeKeymapCatalog implements KeymapCatalog {

    @Override
    public List<Row> all() {
        List<Row> rows = new ArrayList<>();
        Keymap keymap = KeymapManager.getInstance().getActiveKeymap();
        ActionManager actions = ActionManager.getInstance();
        for (String actionId : keymap.getActionIdList()) {
            KeyStroke[] seq = firstKeyboardSequence(keymap.getShortcuts(actionId));
            if (seq.length == 0) continue;                  // mouse-only or unbound
            AnAction action = actions.getAction(actionId);
            String label = action == null ? null : action.getTemplatePresentation().getText();
            rows.add(new Row(actionId, (label == null || label.isBlank()) ? actionId : label, seq));
        }
        return rows;
    }

    private static KeyStroke[] firstKeyboardSequence(Shortcut[] shortcuts) {
        for (Shortcut s : shortcuts) {
            if (s instanceof KeyboardShortcut kbs) {
                KeyStroke first = kbs.getFirstKeyStroke();
                KeyStroke second = kbs.getSecondKeyStroke();
                return second == null ? new KeyStroke[]{first} : new KeyStroke[]{first, second};
            }
        }
        return new KeyStroke[0];
    }
}
```

- [ ] **Step 2: Rewrite the overlay (interactive, bridged)**

Replace `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutsOverlay.java` with:

```java
package com.petros.ireview;

import com.google.gson.Gson;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import java.awt.Color;
import java.awt.Dimension;
import java.util.List;

/**
 * Interactive shortcut cheat-sheet. View mode is read-only with an Edit button;
 * Edit mode is a checklist whose toggles/category changes post through a
 * {@link JBCefJSQuery} bridge into {@link ShortcutPrefs} (persisted immediately).
 * JCEF-gated; the {@link JEditorPane} fallback is view-only.
 */
public final class ShortcutsOverlay extends DialogWrapper {

    private final Project project;
    private final ShortcutPrefs prefs;
    private final Gson gson = new Gson();
    private JBCefBrowser browser;
    private JBCefJSQuery query;

    public ShortcutsOverlay(@Nullable Project project, ShortcutPrefs prefs) {
        super(project, false);
        this.project = project;
        this.prefs = prefs;
        setTitle("Keyboard Shortcuts");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        if (JBCefApp.isSupported()) {
            browser = new JBCefBrowser();
            Disposer.register(getDisposable(), browser);
            query = JBCefJSQuery.create((JBCefBrowserBase) browser);
            query.addHandler(payload -> {
                ApplicationManager.getApplication().invokeLater(() -> handle(payload));
                return new JBCefJSQuery.Response(null);
            });
            renderView();
            JComponent c = browser.getComponent();
            c.setPreferredSize(new Dimension(940, 600));
            return c;
        }
        // Fallback: view-only (no bridge available in Swing HTML).
        ResolvedSheet sheet = ViewModelBuilder.build(ShortcutCatalog.build(new IdeKeymapCatalog()), prefs);
        JEditorPane pane = new JEditorPane("text/html", ShortcutsHtmlRenderer.toDocument(sheet, isDark()));
        pane.setEditable(false);
        JBScrollPane scroll = new JBScrollPane(pane);
        scroll.setPreferredSize(new Dimension(760, 600));
        return scroll;
    }

    private String bridge() {
        return "function ireviewSend(json){" + query.inject("json") + "}";
    }

    private void renderView() {
        ResolvedSheet sheet = ViewModelBuilder.build(ShortcutCatalog.build(new IdeKeymapCatalog()), prefs);
        browser.loadHTML(ShortcutsHtmlRenderer.renderView(sheet, isDark(), true, bridge()));
    }

    private void renderEdit() {
        EditSheet sheet = EditModelBuilder.build(ShortcutCatalog.build(new IdeKeymapCatalog()), prefs);
        browser.loadHTML(ShortcutsHtmlRenderer.renderEdit(sheet, isDark(), bridge()));
    }

    private void handle(String payloadJson) {
        Msg m;
        try { m = gson.fromJson(payloadJson, Msg.class); } catch (Exception e) { return; }
        if (m == null || m.type == null) return;
        switch (m.type) {
            case "enterEdit" -> renderEdit();
            case "exitEdit"  -> renderView();
            case "toggle"    -> { if (m.id != null) prefs.setEnabled(m.id, m.on); }   // no re-render
            case "setCategory" -> { if (m.id != null) prefs.setCategory(m.id, m.category); }
            case "newCategory" -> {
                String name = Messages.showInputDialog(project, "New category name:", "New Category", null);
                if (name != null && !name.isBlank() && m.id != null) prefs.setCategory(m.id, name.trim());
                renderEdit();   // reflect the new option (or reset the <select> if cancelled)
            }
            default -> { /* ignore unknown */ }
        }
    }

    /** Bridge message shape. */
    private static final class Msg {
        String type;
        String id;
        boolean on;
        String category;
    }

    @Override
    protected Action[] createActions() {
        return new Action[0]; // no OK/Cancel; Esc dismisses
    }

    private static boolean isDark() {
        Color bg = EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground();
        return (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) < 128;
    }
}
```

- [ ] **Step 3: Rewrite the action (seed + open)**

Replace `intellij-plugin-spike/src/main/java/com/petros/ireview/ShowShortcutsAction.java` with:

```java
package com.petros.ireview;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

/** Opens the editable keyboard cheat-sheet overlay (⌃⌥⇧/). Seeds prefs from the legacy YAML once. */
public final class ShowShortcutsAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ShortcutPrefs prefs = ApplicationManager.getApplication().getService(ShortcutPrefs.class);
        PrefsSeeder.seedIfNeeded(prefs, ShortcutSheetLoader.load());
        new ShortcutsOverlay(e.getProject(), prefs).show();
    }
}
```

- [ ] **Step 4: Register the service in plugin.xml**

In `intellij-plugin-spike/src/main/resources/META-INF/plugin.xml`, inside the existing
`<extensions defaultExtensionNs="com.intellij">` block, add:

```xml
        <applicationService serviceImplementation="com.petros.ireview.ShortcutPrefs"/>
```

(Leave the `<action>` block, including the `⌃⌥⇧/` `<keyboard-shortcut>` lines, unchanged.)

- [ ] **Step 5: Update the README**

In `intellij-plugin-spike/README.md`, replace the body of the "Shortcut cheat-sheet" section's editing instructions so it describes edit mode instead of the YAML:

```markdown
Press **⌃⌥⇧/** (Ctrl+Alt+Shift+/) to open the cheat-sheet. Click **✎ Edit** to
turn it into a checklist of every shortcut in your keymap: tick the ones to
feature, pick a category for each (they default to **General**), then **Done**.
The panel is always alphabetical — categories A→Z, shortcuts A→Z within — so
adding or removing one never shuffles the rest. Your choices are saved by the
IDE (`ireview-shortcuts.xml`); no file editing or rebuild needed.

> On first open, your previous `shortcuts.yml` selections are imported once.
> The keystroke is macOS-only; elsewhere use **Help** or **Find Action**
> ("Show Shortcut Cheat-Sheet").
```

- [ ] **Step 6: Delete the now-dead single-lookup path**

The view path now uses `ShortcutCatalog`/`ViewModelBuilder`, so the old single-action
resolver is unreferenced. Confirm, then delete:

```bash
cd "$(git rev-parse --show-toplevel)/intellij-plugin-spike"
grep -rn "ShortcutResolver\|KeymapLookup\|IdeKeymapLookup" src/main src/test | grep -v "IdeKeymapCatalog"
```
Expected: no matches outside their own files. If clean, delete:
```bash
git rm src/main/java/com/petros/ireview/ShortcutResolver.java \
       src/main/java/com/petros/ireview/KeymapLookup.java \
       src/main/java/com/petros/ireview/IdeKeymapLookup.java \
       src/test/java/com/petros/ireview/ShortcutResolverTest.java
```
(If any non-self reference remains, stop and report — do not force the delete.)

- [ ] **Step 7: Build, full suite, and sandbox smoke**

Run:
```bash
cd "$(git rev-parse --show-toplevel)/intellij-plugin-spike"
./gradlew test            # ENTIRE suite green (Tasks 1–6 tests + prior)
./gradlew prepareSandbox  # builds cleanly
```
Then (manual, PENDING USER): repoint the plugin symlink to the current `IU-*` sandbox if the
IDE build changed, restart IntelliJ, press **⌃⌥⇧/**. Verify: view mode shows your featured set
grouped A→Z with an **Edit** button; Edit → checklist of all keymap shortcuts, filter works,
ticking updates the count, a category `<select>` per ticked row (incl. "＋ New category…"); Done
returns to view showing the new set; reopen after restart shows the same set (persisted).

- [ ] **Step 8: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add intellij-plugin-spike/src/main/java/com/petros/ireview/IdeKeymapCatalog.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutsOverlay.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/ShowShortcutsAction.java \
        intellij-plugin-spike/src/main/resources/META-INF/plugin.xml \
        intellij-plugin-spike/README.md
git add -A intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutResolver.java \
           intellij-plugin-spike/src/main/java/com/petros/ireview/KeymapLookup.java \
           intellij-plugin-spike/src/main/java/com/petros/ireview/IdeKeymapLookup.java \
           intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutResolverTest.java
git commit -m "feat(ireview): wire interactive edit-mode overlay; retire single-lookup path"
```

---

## Self-Review

**Spec coverage:**
- Candidate pool = all keymap-bound actions → Task 2 (`ShortcutCatalog`/`IdeKeymapCatalog` Task 7).
- Categories A→Z, shortcuts A→Z within → Task 3 (`ViewModelBuilder` TreeMap + pre-sorted catalog).
- Tick-first → default General → Task 1 `DEFAULT_CATEGORY`, Task 3 null→General, Task 5 select defaults.
- Persistence in IDE store, remembers category while disabled → Task 1 (`ShortcutPrefs`, `@State`), Task 7 registration.
- Filter box + edit checklist + category `<select>` incl. new-category → Task 5 (`renderEdit` + JS), Task 7 (`newCategory` dialog).
- Saved instantly, no rebuild → Task 7 bridge writes to `prefs` (persisted by platform).
- One-time YAML import then retire → Task 6 (`PrefsSeeder`), Task 7 (`ShowShortcutsAction` calls it; YAML/loader kept only for seed).
- JCEF interactive with view-only fallback → Task 7 (`ShortcutsOverlay`).
- Dead code removed → Task 7 Step 6.

**Placeholder scan:** none — every code step is complete; every command has expected output.

**Type consistency:** `ShortcutPrefs` (isEnabled/categoryOf/setEnabled/setCategory/categories/isInitialized/markInitialized/DEFAULT_CATEGORY), `KeymapCatalog.Row(actionId,label,sequence)`, `CatalogEntry(actionId,label,groups)`, `ShortcutCatalog.build`, `ViewModelBuilder.build→ResolvedSheet`, `EditSheet(rows,categories)`/`EditRow(actionId,label,groups,enabled,category)`, `EditModelBuilder.build`, `ShortcutsHtmlRenderer.renderView(sheet,dark,editButton,bridge)`/`renderEdit(sheet,dark,bridge)`/`toDocument(sheet,dark)`, `PrefsSeeder.seedIfNeeded(prefs,legacy)` — used consistently across Tasks 1–7. Bridge JSON `{type,id,on,category}` matches `Msg` (Task 7) and the JS emitters (Task 5).
