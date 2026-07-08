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
