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
