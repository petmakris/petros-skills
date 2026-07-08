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
