# Keyboard Shortcut Cheat-Sheet Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read-only `⌃⇧/` overlay to the IntelliJ plugin that renders a curated, YAML-driven cheat-sheet of IDE shortcuts, with key combos resolved live from the active keymap and laid out in auto-flowing columns.

**Architecture:** A bundled `shortcuts.yml` (grouped, `enabled` per entry) is parsed into a raw model, resolved against the active keymap into display-ready rows (label + key glyphs), rendered to a themed HTML document, and shown in a `JBCefBrowser` inside a `DialogWrapper` (Esc closes; `JEditorPane` fallback when JCEF is unavailable). The keymap lookup is behind an interface so the resolver is unit-testable with a fake.

**Tech Stack:** Java 25, IntelliJ Platform SDK (local IDE), SnakeYAML (platform-bundled), JCEF (`JBCefBrowser`), JUnit 5.

## Global Constraints

- Package: `com.petros.ireview`; module: `intellij-plugin-spike/`.
- Java language level 25 (`build.gradle.kts` `JavaLanguageVersion.of(25)`), matching IDEA 2026.1's JBR — do not lower it.
- Tests are pure JUnit 5 unit tests under `src/test/java/com/petros/ireview/` (no platform fixture), matching the existing style (e.g. `SynthesisHtmlRendererTest`).
- Default keystroke is **`Ctrl+Shift+/`**, declared with literal `control` on the macOS keymaps so it is not auto-translated to `⌘`.
- Never hand-roll a YAML parser — use the platform-bundled SnakeYAML (`org.yaml.snakeyaml`).
- SnakeYAML is provided by the running IDE at runtime; add it as `compileOnly` (main) + `testImplementation` (tests) only — do NOT ship it as `implementation` (avoids a duplicate on the plugin classpath).
- Run tests: `./gradlew test`. Build into the symlinked sandbox: `./gradlew prepareSandbox` then restart IDEA.

---

### Task 1: YAML resource, raw model, and loader

**Files:**
- Create: `intellij-plugin-spike/src/main/resources/shortcuts/shortcuts.yml`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutSheet.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutSheetLoader.java`
- Modify: `intellij-plugin-spike/build.gradle.kts:83-91` (dependencies block)
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutSheetLoaderTest.java`

**Interfaces:**
- Produces:
  - `record ShortcutSheet(List<Category> categories, String error)` with nested
    `record Category(String name, List<Entry> entries)` and
    `record Entry(String action, String label, boolean enabled)`;
    helpers `boolean isError()`, `static ShortcutSheet error(String msg)`.
  - `ShortcutSheetLoader.load() -> ShortcutSheet` (reads classpath resource `/shortcuts/shortcuts.yml`).
  - `ShortcutSheetLoader.parse(String yaml) -> ShortcutSheet` (used by tests).

- [ ] **Step 1: Add SnakeYAML to the build (compileOnly + testImplementation)**

Modify `intellij-plugin-spike/build.gradle.kts`, dependencies block at lines 83-91, to add two lines:

```kotlin
dependencies {
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Provided by the running IDE at runtime; here only for compile + tests.
    compileOnly("org.yaml:snakeyaml:2.2")
    testImplementation("org.yaml:snakeyaml:2.2")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}
```

- [ ] **Step 2: Create the bundled YAML with a small starter set**

Create `intellij-plugin-spike/src/main/resources/shortcuts/shortcuts.yml`:

```yaml
# Curated IDE shortcuts for the cheat-sheet overlay (⌃⇧/).
# `action` is an IntelliJ action ID (Settings → Keymap → right-click → Copy Action ID).
# Keys are resolved live from the active keymap. Flip `enabled` to show/hide.
categories:
  - name: Navigation
    entries:
      - action: GotoClass
        enabled: true
      - action: GotoFile
        enabled: true
      - action: GotoSymbol
        enabled: true
      - action: FindUsages
        enabled: true
      - action: GotoDeclaration
        label: Go to Declaration
        enabled: true
  - name: General
    entries:
      - action: SearchEverywhere
        enabled: true
      - action: GotoAction
        label: Find Action
        enabled: true
      - action: RecentFiles
        enabled: true
      - action: ShowSettings
        label: Settings
        enabled: false
  - name: Refactor
    entries:
      - action: RenameElement
        label: Rename
        enabled: true
      - action: IntroduceVariable
        label: Extract Variable
        enabled: true
      - action: ExtractMethod
        label: Extract Method
        enabled: true
```

- [ ] **Step 3: Write the failing loader test**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutSheetLoaderTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShortcutSheetLoaderTest {

    @Test
    void parsesCategoriesEntriesAndFlags() {
        String yaml = """
            categories:
              - name: Navigation
                entries:
                  - action: GotoClass
                    enabled: true
                  - action: FindUsages
                    label: Find Usages
                    enabled: false
            """;
        ShortcutSheet sheet = ShortcutSheetLoader.parse(yaml);

        assertFalse(sheet.isError(), sheet.error());
        assertEquals(1, sheet.categories().size());
        ShortcutSheet.Category nav = sheet.categories().get(0);
        assertEquals("Navigation", nav.name());
        assertEquals(2, nav.entries().size());

        ShortcutSheet.Entry first = nav.entries().get(0);
        assertEquals("GotoClass", first.action());
        assertNull(first.label());
        assertTrue(first.enabled());

        ShortcutSheet.Entry second = nav.entries().get(1);
        assertEquals("Find Usages", second.label());
        assertFalse(second.enabled());
    }

    @Test
    void entryWithoutActionIsSkipped() {
        String yaml = """
            categories:
              - name: X
                entries:
                  - label: no action here
                    enabled: true
                  - action: GotoClass
                    enabled: true
            """;
        ShortcutSheet sheet = ShortcutSheetLoader.parse(yaml);
        assertEquals(1, sheet.categories().get(0).entries().size());
        assertEquals("GotoClass", sheet.categories().get(0).entries().get(0).action());
    }

    @Test
    void malformedYamlReturnsErrorNotThrow() {
        ShortcutSheet sheet = ShortcutSheetLoader.parse("categories: [ this is : broken");
        assertTrue(sheet.isError());
        assertNotNull(sheet.error());
    }

    @Test
    void bundledResourceLoads() {
        ShortcutSheet sheet = ShortcutSheetLoader.load();
        assertFalse(sheet.isError(), sheet.error());
        assertFalse(sheet.categories().isEmpty());
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ShortcutSheetLoaderTest"`
Expected: FAIL — `ShortcutSheet` / `ShortcutSheetLoader` do not exist (compilation error).

- [ ] **Step 5: Create the model record**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutSheet.java`:

```java
package com.petros.ireview;

import java.util.List;

/** Raw, unresolved cheat-sheet model parsed from {@code shortcuts.yml}. */
public record ShortcutSheet(List<Category> categories, String error) {

    public boolean isError() { return error != null; }

    public static ShortcutSheet error(String msg) {
        return new ShortcutSheet(List.of(), msg);
    }

    public record Category(String name, List<Entry> entries) {}

    /** One shortcut row. {@code label} may be null (use the action's own text). */
    public record Entry(String action, String label, boolean enabled) {}
}
```

- [ ] **Step 6: Create the loader**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutSheetLoader.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.diagnostic.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Loads and parses the bundled {@code /shortcuts/shortcuts.yml} via SnakeYAML. */
public final class ShortcutSheetLoader {

    private static final Logger LOG = Logger.getInstance(ShortcutSheetLoader.class);
    private static final String RESOURCE = "/shortcuts/shortcuts.yml";

    private ShortcutSheetLoader() {}

    public static ShortcutSheet load() {
        try (InputStream in = ShortcutSheetLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return ShortcutSheet.error("shortcuts.yml not found on classpath");
            return parse(new Yaml().load(in));
        } catch (Exception e) {
            LOG.warn("Failed to load " + RESOURCE, e);
            return ShortcutSheet.error("couldn't load shortcuts.yml: " + e.getMessage());
        }
    }

    public static ShortcutSheet parse(String yaml) {
        try {
            return parse(new Yaml().load(yaml));
        } catch (Exception e) {
            return ShortcutSheet.error("couldn't parse shortcuts.yml: " + e.getMessage());
        }
    }

    private static ShortcutSheet parse(Object root) {
        if (!(root instanceof Map<?, ?> map)) return ShortcutSheet.error("shortcuts.yml: expected a mapping at top level");
        List<ShortcutSheet.Category> categories = new ArrayList<>();
        if (map.get("categories") instanceof List<?> catList) {
            for (Object c : catList) {
                if (!(c instanceof Map<?, ?> cm)) continue;
                String name = str(cm.get("name"));
                List<ShortcutSheet.Entry> entries = new ArrayList<>();
                if (cm.get("entries") instanceof List<?> entryList) {
                    for (Object e : entryList) {
                        if (!(e instanceof Map<?, ?> em)) continue;
                        String action = str(em.get("action"));
                        if (action == null || action.isBlank()) continue;
                        String label = str(em.get("label"));
                        boolean enabled = Boolean.TRUE.equals(em.get("enabled"));
                        entries.add(new ShortcutSheet.Entry(action, label, enabled));
                    }
                }
                categories.add(new ShortcutSheet.Category(name, entries));
            }
        }
        return new ShortcutSheet(categories, null);
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ShortcutSheetLoaderTest"`
Expected: PASS (4 tests).

- [ ] **Step 8: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add intellij-plugin-spike/build.gradle.kts \
        intellij-plugin-spike/src/main/resources/shortcuts/shortcuts.yml \
        intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutSheet.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutSheetLoader.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutSheetLoaderTest.java
git commit -m "feat(ireview): YAML cheat-sheet model + loader"
```

---

### Task 2: Keystroke → glyph tokens

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/KeystrokeGlyphs.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/KeystrokeGlyphsTest.java`

**Interfaces:**
- Produces: `KeystrokeGlyphs.tokens(javax.swing.KeyStroke ks) -> List<String>` —
  modifiers in canonical order `⌃ ⌥ ⇧ ⌘` followed by one key glyph.

- [ ] **Step 1: Write the failing test**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/KeystrokeGlyphsTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeystrokeGlyphsTest {

    @Test
    void cmdShiftA() {
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_A,
                InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        assertEquals(List.of("⇧", "⌘", "A"), KeystrokeGlyphs.tokens(ks));
    }

    @Test
    void modifierOrderIsCtrlAltShiftCmd() {
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_L,
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
                        | InputEvent.SHIFT_DOWN_MASK | InputEvent.META_DOWN_MASK);
        assertEquals(List.of("⌃", "⌥", "⇧", "⌘", "L"), KeystrokeGlyphs.tokens(ks));
    }

    @Test
    void functionKeyKeptAsIs() {
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.ALT_DOWN_MASK);
        assertEquals(List.of("⌥", "F7"), KeystrokeGlyphs.tokens(ks));
    }

    @Test
    void specialKeysUseGlyphs() {
        assertEquals(List.of("⌘", "⌫"),
                KeystrokeGlyphs.tokens(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, InputEvent.META_DOWN_MASK)));
        assertEquals(List.of("⌥", "↑"),
                KeystrokeGlyphs.tokens(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK)));
        assertEquals(List.of("⌃", "⇧", "/"),
                KeystrokeGlyphs.tokens(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH,
                        InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.KeystrokeGlyphsTest"`
Expected: FAIL — `KeystrokeGlyphs` does not exist.

- [ ] **Step 3: Write the implementation**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/KeystrokeGlyphs.java`:

```java
package com.petros.ireview;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a {@link KeyStroke} into an ordered list of display tokens:
 * modifiers as mac glyphs in the canonical order {@code ⌃ ⌥ ⇧ ⌘}, then the key.
 * Pure — no IDE dependencies — so it is unit-testable with hand-built keystrokes.
 */
public final class KeystrokeGlyphs {

    private KeystrokeGlyphs() {}

    public static List<String> tokens(KeyStroke ks) {
        List<String> out = new ArrayList<>();
        int m = ks.getModifiers();
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0)  out.add("⌃");
        if ((m & InputEvent.ALT_DOWN_MASK) != 0)   out.add("⌥");
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) out.add("⇧");
        if ((m & InputEvent.META_DOWN_MASK) != 0)  out.add("⌘");
        out.add(keyGlyph(ks.getKeyCode()));
        return out;
    }

    private static String keyGlyph(int vk) {
        return switch (vk) {
            case KeyEvent.VK_ENTER      -> "↵";
            case KeyEvent.VK_BACK_SPACE -> "⌫";
            case KeyEvent.VK_DELETE     -> "⌦";
            case KeyEvent.VK_ESCAPE     -> "Esc";
            case KeyEvent.VK_TAB        -> "⇥";
            case KeyEvent.VK_SPACE      -> "Space";
            case KeyEvent.VK_LEFT       -> "←";
            case KeyEvent.VK_RIGHT      -> "→";
            case KeyEvent.VK_UP         -> "↑";
            case KeyEvent.VK_DOWN       -> "↓";
            case KeyEvent.VK_SLASH      -> "/";
            case KeyEvent.VK_COMMA      -> ",";
            case KeyEvent.VK_PERIOD     -> ".";
            case KeyEvent.VK_MINUS      -> "-";
            case KeyEvent.VK_EQUALS     -> "=";
            case KeyEvent.VK_BACK_QUOTE -> "`";
            default -> KeyEvent.getKeyText(vk); // letters, digits, F-keys, etc.
        };
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.KeystrokeGlyphsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add intellij-plugin-spike/src/main/java/com/petros/ireview/KeystrokeGlyphs.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/KeystrokeGlyphsTest.java
git commit -m "feat(ireview): keystroke-to-glyph token converter"
```

---

### Task 3: Resolver (keymap lookup behind an interface) + resolved model

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/KeymapLookup.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/ResolvedSheet.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutResolver.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutResolverTest.java`

**Interfaces:**
- Consumes: `ShortcutSheet` (Task 1), `KeystrokeGlyphs.tokens` (Task 2).
- Produces:
  - `interface KeymapLookup { record Hit(String label, KeyStroke[] sequence) {} Optional<Hit> find(String actionId); }`
    (`sequence.length == 0` means the action exists but is unbound; `Optional.empty()` means unknown action).
  - `record ResolvedSheet(List<ResolvedCategory> categories, String error)` with nested
    `record ResolvedCategory(String name, List<ResolvedEntry> entries)` and
    `record ResolvedEntry(String label, List<List<String>> groups, boolean unassigned)`;
    helper `boolean isError()`. Each element of `groups` is the token list for one keystroke in the sequence (usually one).
  - `ShortcutResolver.resolve(ShortcutSheet sheet, KeymapLookup lookup) -> ResolvedSheet`.

- [ ] **Step 1: Write the failing test**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutResolverTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ShortcutResolverTest {

    /** Fake keymap: GotoClass -> ⌘O; RenameElement -> present but unbound; others unknown. */
    private static KeymapLookup fakeLookup() {
        Map<String, KeymapLookup.Hit> table = Map.of(
                "GotoClass", new KeymapLookup.Hit("Go to Class",
                        new KeyStroke[]{ KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.META_DOWN_MASK) }),
                "RenameElement", new KeymapLookup.Hit("Rename", new KeyStroke[0]));
        return actionId -> Optional.ofNullable(table.get(actionId));
    }

    private static ShortcutSheet sheet(ShortcutSheet.Entry... entries) {
        return new ShortcutSheet(List.of(new ShortcutSheet.Category("Nav", List.of(entries))), null);
    }

    @Test
    void resolvesKeysAndPrefersLabelOverride() {
        ResolvedSheet r = ShortcutResolver.resolve(
                sheet(new ShortcutSheet.Entry("GotoClass", "Class!", true)), fakeLookup());

        ResolvedSheet.ResolvedEntry e = r.categories().get(0).entries().get(0);
        assertEquals("Class!", e.label());            // override wins
        assertFalse(e.unassigned());
        assertEquals(List.of(List.of("⌘", "O")), e.groups());
    }

    @Test
    void fallsBackToActionLabelWhenNoOverride() {
        ResolvedSheet r = ShortcutResolver.resolve(
                sheet(new ShortcutSheet.Entry("GotoClass", null, true)), fakeLookup());
        assertEquals("Go to Class", r.categories().get(0).entries().get(0).label());
    }

    @Test
    void disabledEntriesAreSkipped() {
        ResolvedSheet r = ShortcutResolver.resolve(
                sheet(new ShortcutSheet.Entry("GotoClass", null, false)), fakeLookup());
        assertTrue(r.categories().isEmpty()); // category empty -> dropped
    }

    @Test
    void unknownActionSkipped_unboundActionMarkedUnassigned() {
        ResolvedSheet r = ShortcutResolver.resolve(
                sheet(new ShortcutSheet.Entry("DoesNotExist", null, true),
                      new ShortcutSheet.Entry("RenameElement", null, true)), fakeLookup());

        List<ResolvedSheet.ResolvedEntry> entries = r.categories().get(0).entries();
        assertEquals(1, entries.size());
        assertEquals("Rename", entries.get(0).label());
        assertTrue(entries.get(0).unassigned());
        assertTrue(entries.get(0).groups().isEmpty());
    }

    @Test
    void errorSheetPropagates() {
        ResolvedSheet r = ShortcutResolver.resolve(ShortcutSheet.error("boom"), fakeLookup());
        assertTrue(r.isError());
        assertEquals("boom", r.error());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ShortcutResolverTest"`
Expected: FAIL — `KeymapLookup` / `ResolvedSheet` / `ShortcutResolver` do not exist.

- [ ] **Step 3: Create the lookup interface**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/KeymapLookup.java`:

```java
package com.petros.ireview;

import javax.swing.KeyStroke;
import java.util.Optional;

/**
 * Abstracts the active keymap so {@link ShortcutResolver} can be unit-tested
 * with a fake. The real implementation ({@code IdeKeymapLookup}) reads
 * {@code ActionManager} + {@code KeymapManager}.
 */
public interface KeymapLookup {

    /**
     * @param label    the action's display text
     * @param sequence the first keyboard shortcut's keystrokes (1 or 2);
     *                 length 0 means the action is known but has no binding
     */
    record Hit(String label, KeyStroke[] sequence) {}

    /** Empty means the action ID is unknown. */
    Optional<Hit> find(String actionId);
}
```

- [ ] **Step 4: Create the resolved model**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/ResolvedSheet.java`:

```java
package com.petros.ireview;

import java.util.List;

/** Display-ready cheat-sheet: labels + key glyph groups, keymap already applied. */
public record ResolvedSheet(List<ResolvedCategory> categories, String error) {

    public boolean isError() { return error != null; }

    public record ResolvedCategory(String name, List<ResolvedEntry> entries) {}

    /**
     * One row. {@code groups} holds one token list per keystroke in the
     * shortcut sequence (usually a single group). {@code unassigned} is true
     * when the action exists but has no key bound.
     */
    public record ResolvedEntry(String label, List<List<String>> groups, boolean unassigned) {}
}
```

- [ ] **Step 5: Create the resolver**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutResolver.java`:

```java
package com.petros.ireview;

import javax.swing.KeyStroke;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Maps a raw {@link ShortcutSheet} to a {@link ResolvedSheet} via a {@link KeymapLookup}. */
public final class ShortcutResolver {

    private ShortcutResolver() {}

    public static ResolvedSheet resolve(ShortcutSheet sheet, KeymapLookup lookup) {
        if (sheet.isError()) return new ResolvedSheet(List.of(), sheet.error());

        List<ResolvedSheet.ResolvedCategory> categories = new ArrayList<>();
        for (ShortcutSheet.Category category : sheet.categories()) {
            List<ResolvedSheet.ResolvedEntry> resolved = new ArrayList<>();
            for (ShortcutSheet.Entry entry : category.entries()) {
                if (!entry.enabled()) continue;

                Optional<KeymapLookup.Hit> found = lookup.find(entry.action());
                if (found.isEmpty()) continue; // unknown action ID -> skip

                KeymapLookup.Hit hit = found.get();
                String label = (entry.label() != null && !entry.label().isBlank())
                        ? entry.label() : hit.label();

                if (hit.sequence().length == 0) {
                    resolved.add(new ResolvedSheet.ResolvedEntry(label, List.of(), true));
                } else {
                    List<List<String>> groups = new ArrayList<>();
                    for (KeyStroke ks : hit.sequence()) groups.add(KeystrokeGlyphs.tokens(ks));
                    resolved.add(new ResolvedSheet.ResolvedEntry(label, groups, false));
                }
            }
            if (!resolved.isEmpty()) {
                categories.add(new ResolvedSheet.ResolvedCategory(category.name(), resolved));
            }
        }
        return new ResolvedSheet(categories, null);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ShortcutResolverTest"`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add intellij-plugin-spike/src/main/java/com/petros/ireview/KeymapLookup.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/ResolvedSheet.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutResolver.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutResolverTest.java
git commit -m "feat(ireview): resolve cheat-sheet against active keymap"
```

---

### Task 4: HTML renderer (auto-flow columns, themed)

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutsHtmlRenderer.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutsHtmlRendererTest.java`

**Interfaces:**
- Consumes: `ResolvedSheet` (Task 3).
- Produces: `ShortcutsHtmlRenderer.toDocument(ResolvedSheet sheet, boolean dark) -> String`
  (full `<!doctype html>` document; auto-flowing columns via CSS `column-width`).

- [ ] **Step 1: Write the failing test**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutsHtmlRendererTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShortcutsHtmlRendererTest {

    private static ResolvedSheet oneRow() {
        var entry = new ResolvedSheet.ResolvedEntry("Go to Class", List.of(List.of("⌘", "O")), false);
        var cat = new ResolvedSheet.ResolvedCategory("Navigation", List.of(entry));
        return new ResolvedSheet(List.of(cat), null);
    }

    @Test
    void rendersCategoryLabelAndCaps() {
        String html = ShortcutsHtmlRenderer.toDocument(oneRow(), false);
        assertTrue(html.startsWith("<!doctype html>"), html);
        assertTrue(html.contains("Navigation"), html);
        assertTrue(html.contains("Go to Class"), html);
        assertTrue(html.contains(">⌘<"), html);
        assertTrue(html.contains(">O<"), html);
    }

    @Test
    void usesColumnLayoutCss() {
        String html = ShortcutsHtmlRenderer.toDocument(oneRow(), false);
        assertTrue(html.contains("column-width"), html);
    }

    @Test
    void unassignedEntryShowsTag() {
        var entry = new ResolvedSheet.ResolvedEntry("Rename", List.of(), true);
        var cat = new ResolvedSheet.ResolvedCategory("Refactor", List.of(entry));
        String html = ShortcutsHtmlRenderer.toDocument(new ResolvedSheet(List.of(cat), null), false);
        assertTrue(html.contains("Rename"), html);
        assertTrue(html.contains("unassigned"), html);
    }

    @Test
    void errorSheetRendersMessage() {
        String html = ShortcutsHtmlRenderer.toDocument(new ResolvedSheet(List.of(), "boom"), false);
        assertTrue(html.contains("boom"), html);
    }

    @Test
    void escapesHtmlInLabels() {
        var entry = new ResolvedSheet.ResolvedEntry("a <b> & c", List.of(List.of("A")), false);
        var cat = new ResolvedSheet.ResolvedCategory("X", List.of(entry));
        String html = ShortcutsHtmlRenderer.toDocument(new ResolvedSheet(List.of(cat), null), false);
        assertTrue(html.contains("a &lt;b&gt; &amp; c"), html);
        assertFalse(html.contains("a <b> & c"), html);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ShortcutsHtmlRendererTest"`
Expected: FAIL — `ShortcutsHtmlRenderer` does not exist.

- [ ] **Step 3: Write the renderer**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutsHtmlRenderer.java`:

```java
package com.petros.ireview;

import java.util.List;

/**
 * Renders a {@link ResolvedSheet} to a self-contained HTML document styled like
 * the cheat-sheet mockup: category headers, keycap chips, and CSS auto-flowing
 * columns so groups pack into as many columns as the width allows.
 */
public final class ShortcutsHtmlRenderer {

    private ShortcutsHtmlRenderer() {}

    public static String toDocument(ResolvedSheet sheet, boolean dark) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><style>");
        sb.append(css(dark));
        sb.append("</style></head><body>");
        sb.append("<div class=\"bar\"><h2>Keyboard Shortcuts</h2></div>");

        if (sheet.isError()) {
            sb.append("<p class=\"err\">").append(esc(sheet.error())).append("</p>");
        } else if (sheet.categories().isEmpty()) {
            sb.append("<p class=\"err\">No shortcuts enabled. Edit shortcuts.yml.</p>");
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
                            for (String token : group) {
                                sb.append("<span class=\"cap\">").append(esc(token)).append("</span>");
                            }
                        }
                    }
                    sb.append("</span></div>");
                }
                sb.append("</div>");
            }
            sb.append("</div>");
        }

        sb.append("<div class=\"foot\">Press <span class=\"cap\">Esc</span> to close</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String css(boolean dark) {
        String bg      = dark ? "#2b2d30" : "#ffffff";
        String ink     = dark ? "#dfe1e5" : "#1f2328";
        String muted   = dark ? "#8b9096" : "#8a9099";
        String line    = dark ? "#3c3f43" : "#e6e8eb";
        String capBg   = dark ? "#3a3d41" : "#f6f7f9";
        String capLine = dark ? "#54585d" : "#d6dade";
        String capInk  = dark ? "#d0d3d8" : "#3a4048";
        return ""
            + "*{box-sizing:border-box}"
            + "body{margin:0;background:" + bg + ";color:" + ink + ";"
            + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;}"
            + ".bar{padding:16px 22px;border-bottom:1px solid " + line + ";}"
            + ".bar h2{margin:0;font-size:15.5px;font-weight:650;}"
            + ".board{padding:18px 24px;column-width:230px;column-gap:38px;}"
            + ".group{break-inside:avoid;margin-bottom:20px;}"
            + ".cat{font-size:10.5px;font-weight:700;letter-spacing:.09em;color:" + muted + ";"
            + "text-transform:uppercase;margin:0 0 9px;}"
            + ".row{display:flex;align-items:center;gap:12px;padding:5px 0;}"
            + ".name{font-size:13px;}"
            + ".keys{margin-left:auto;display:flex;gap:5px;flex:0 0 auto;}"
            + ".cap{min-width:22px;height:22px;padding:0 6px;border-radius:6px;background:" + capBg + ";"
            + "border:1px solid " + capLine + ";border-bottom-width:2px;color:" + capInk + ";"
            + "font-size:11.5px;font-weight:600;display:inline-flex;align-items:center;justify-content:center;"
            + "font-family:ui-monospace,SFMono-Regular,Menlo,monospace;}"
            + ".tag{font-size:10.5px;color:" + muted + ";font-style:italic;}"
            + ".err{padding:22px;color:" + muted + ";}"
            + ".foot{text-align:center;color:" + muted + ";font-size:11.5px;padding:11px;border-top:1px solid " + line + ";}"
            + ".foot .cap{display:inline-flex;vertical-align:middle;margin:0 2px;}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.ShortcutsHtmlRendererTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutsHtmlRenderer.java \
        intellij-plugin-spike/src/test/java/com/petros/ireview/ShortcutsHtmlRendererTest.java
git commit -m "feat(ireview): auto-flow columns HTML renderer for the cheat-sheet"
```

---

### Task 5: IDE wiring — keymap lookup, overlay, action, registration

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/IdeKeymapLookup.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutsOverlay.java`
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/ShowShortcutsAction.java`
- Modify: `intellij-plugin-spike/src/main/resources/META-INF/plugin.xml:30-43` (actions block)
- Modify: `intellij-plugin-spike/README.md` (document the shortcut + how to edit the sheet)

**Interfaces:**
- Consumes: `ShortcutSheetLoader.load`, `ShortcutResolver.resolve`, `KeymapLookup`, `ShortcutsHtmlRenderer.toDocument`.
- Produces: `ShowShortcutsAction` (registered `AnAction`, bound to `⌃⇧/`).

This task is IDE glue — verified by building the sandbox and pressing the key, not by unit tests (asserting on the live keymap / JCEF needs a platform fixture, and `IdeKeymapLookup` is deliberately thin).

- [ ] **Step 1: Create the real keymap lookup**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/IdeKeymapLookup.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.keymap.KeymapManager;

import javax.swing.KeyStroke;
import java.util.Optional;

/** {@link KeymapLookup} backed by the live {@code ActionManager} + active keymap. */
public final class IdeKeymapLookup implements KeymapLookup {

    @Override
    public Optional<Hit> find(String actionId) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) return Optional.empty(); // unknown action ID

        String label = action.getTemplatePresentation().getText();
        if (label == null || label.isBlank()) label = actionId;

        Shortcut[] shortcuts = KeymapManager.getInstance().getActiveKeymap().getShortcuts(actionId);
        for (Shortcut s : shortcuts) {
            if (s instanceof KeyboardShortcut kbs) {
                KeyStroke first = kbs.getFirstKeyStroke();
                KeyStroke second = kbs.getSecondKeyStroke();
                KeyStroke[] seq = (second == null)
                        ? new KeyStroke[]{first}
                        : new KeyStroke[]{first, second};
                return Optional.of(new Hit(label, seq));
            }
        }
        return Optional.of(new Hit(label, new KeyStroke[0])); // known but unbound
    }
}
```

- [ ] **Step 2: Create the overlay dialog**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutsOverlay.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import java.awt.Dimension;

/**
 * Read-only shortcut cheat-sheet. Renders {@code html} in a {@link JBCefBrowser}
 * when JCEF is supported (matching {@code SynthesisPopup}); otherwise falls back
 * to a {@link JEditorPane}. Esc closes (free from {@link DialogWrapper}).
 */
public final class ShortcutsOverlay extends DialogWrapper {

    private final String html;

    public ShortcutsOverlay(@Nullable Project project, String html) {
        super(project, false);
        this.html = html;
        setTitle("Keyboard Shortcuts");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        if (JBCefApp.isSupported()) {
            JBCefBrowser browser = new JBCefBrowser();
            Disposer.register(getDisposable(), browser);
            browser.loadHTML(html);
            JComponent component = browser.getComponent();
            component.setPreferredSize(new Dimension(920, 560));
            return component;
        }
        JEditorPane pane = new JEditorPane("text/html", html);
        pane.setEditable(false);
        JBScrollPane scroll = new JBScrollPane(pane);
        scroll.setPreferredSize(new Dimension(720, 560));
        return scroll;
    }

    @Override
    protected Action[] createActions() {
        return new Action[0]; // no OK/Cancel row; Esc dismisses
    }
}
```

- [ ] **Step 3: Create the action**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/ShowShortcutsAction.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;

/** Opens the read-only keyboard cheat-sheet overlay. Bound to ⌃⇧/. */
public final class ShowShortcutsAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ShortcutSheet raw = ShortcutSheetLoader.load();
        ResolvedSheet resolved = ShortcutResolver.resolve(raw, new IdeKeymapLookup());
        String html = ShortcutsHtmlRenderer.toDocument(resolved, isDarkTheme());
        new ShortcutsOverlay(e.getProject(), html).show();
    }

    private static boolean isDarkTheme() {
        Color bg = EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground();
        double luminance = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
        return luminance < 128;
    }
}
```

- [ ] **Step 4: Register the action in plugin.xml**

In `intellij-plugin-spike/src/main/resources/META-INF/plugin.xml`, inside the existing `<actions>` element (after the closing `</action>` of `PrettifyJsonStringAction`, before `</actions>`), add:

```xml
        <action id="com.petros.ireview.ShowShortcutsAction"
                class="com.petros.ireview.ShowShortcutsAction"
                text="Show Shortcut Cheat-Sheet"
                description="Open a read-only cheat-sheet of curated IDE shortcuts.">
            <add-to-group group-id="HelpMenu" anchor="last"/>
            <!-- Literal control on the Mac keymaps so ⌃⇧/ is not translated to ⌘. -->
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="control shift SLASH"/>
            <keyboard-shortcut keymap="Mac OS X"       first-keystroke="control shift SLASH"/>
            <keyboard-shortcut keymap="$default"        first-keystroke="control shift SLASH"/>
        </action>
```

- [ ] **Step 5: Build the sandbox and verify by hand**

Run:
```bash
cd "$(git rev-parse --show-toplevel)/intellij-plugin-spike"
./gradlew test          # all suites still green
./gradlew prepareSandbox
osascript -e 'quit app "IntelliJ IDEA"'
```
Reopen IntelliJ, open any project, press **⌃⇧/**. Expected: a "Keyboard Shortcuts" dialog opens showing the enabled entries grouped into columns with live key glyphs (e.g. Go to Class → ⌘O); the theme matches the IDE (light/dark); **Esc** closes it. Flip an entry's `enabled` in `shortcuts.yml`, rebuild the sandbox, restart, reopen — it appears/disappears.

- [ ] **Step 6: Document it in the README**

In `intellij-plugin-spike/README.md`, add a short section:

```markdown
## Shortcut cheat-sheet

Press **⌃⇧/** (Ctrl+Shift+/) to open a read-only cheat-sheet of curated IDE
shortcuts. The set lives in `src/main/resources/shortcuts/shortcuts.yml` —
each entry names an IntelliJ action ID and the keys are read live from your
active keymap. Toggle `enabled:` on an entry to show/hide it, then rebuild
(`./gradlew prepareSandbox`) and restart the IDE.
```

- [ ] **Step 7: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add intellij-plugin-spike/src/main/java/com/petros/ireview/IdeKeymapLookup.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/ShortcutsOverlay.java \
        intellij-plugin-spike/src/main/java/com/petros/ireview/ShowShortcutsAction.java \
        intellij-plugin-spike/src/main/resources/META-INF/plugin.xml \
        intellij-plugin-spike/README.md
git commit -m "feat(ireview): ⌃⇧/ keyboard cheat-sheet overlay"
```

---

## Self-Review

**Spec coverage:**
- Curated IDE built-ins via YAML with `enabled` flags → Task 1 (resource + model + loader).
- Keys resolved live from active keymap by action ID → Task 3 (`ShortcutResolver`) + Task 5 (`IdeKeymapLookup`).
- Bundled, read-only YAML → Task 1 (`src/main/resources/shortcuts/shortcuts.yml`).
- Auto-flow columns (layout "B"), static → Task 4 (`column-width` CSS, no interactivity).
- Trigger `⌃⇧/`, literal control on Mac keymaps, Find-Action searchable → Task 5 (plugin.xml).
- JCEF render with JEditorPane fallback, Esc closes, IDE theming → Task 5 (`ShortcutsOverlay`, `ShowShortcutsAction.isDarkTheme`).
- Error handling: malformed YAML → error message not throw (Task 1 + Task 4 `errorSheetRendersMessage`); unknown action skipped (Task 3 `unknownActionSkipped...`); no JCEF fallback (Task 5).
- SnakeYAML not hand-rolled (Task 1); glyph converter isolated + tested (Task 2).
- Tests for loader, glyphs, resolver, renderer → Tasks 1–4.

**Placeholder scan:** No TBD/TODO; every code step shows complete code; every command has expected output.

**Type consistency:** `ShortcutSheet{Category,Entry}`, `ResolvedSheet{ResolvedCategory,ResolvedEntry(label,groups,unassigned)}`, `KeymapLookup.Hit(label,sequence)`, `KeystrokeGlyphs.tokens(KeyStroke)->List<String>`, `ShortcutResolver.resolve(ShortcutSheet,KeymapLookup)->ResolvedSheet`, `ShortcutsHtmlRenderer.toDocument(ResolvedSheet,boolean)->String` are used consistently across Tasks 1–5.
