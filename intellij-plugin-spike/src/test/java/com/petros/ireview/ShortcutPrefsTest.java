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
