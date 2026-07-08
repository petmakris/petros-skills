package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ViewModelBuilderTest {

    private static CatalogEntry entry(String id, String label) {
        return new CatalogEntry(id, label, List.of(List.of("⌘", "X")));
    }

    /** Fake IntelliJ-derived category lookup; unknown ids fall back to "Other". */
    private static Function<String, String> categories(Map<String, String> map) {
        return id -> map.getOrDefault(id, ShortcutCategories.OTHER);
    }

    @Test
    void onlyEnabledGroupedAndSorted() {
        List<CatalogEntry> catalog = List.of(   // pre-sorted A→Z as the real catalog is
            entry("dup", "Duplicate Line"),
            entry("goc", "Go to Class"),
            entry("ren", "Rename"),
            entry("off", "Not Featured"));

        ShortcutPrefs prefs = new ShortcutPrefs();
        prefs.setEnabled("dup", true);
        prefs.setEnabled("goc", true);
        prefs.setEnabled("ren", true);
        // "off" left disabled

        Function<String, String> cat = categories(Map.of(
                "dup", "Editing", "goc", "Navigation", "ren", "Editing"));

        ResolvedSheet sheet = ViewModelBuilder.build(catalog, prefs, cat);

        assertFalse(sheet.isError());
        assertEquals(2, sheet.categories().size());
        assertEquals("Editing", sheet.categories().get(0).name());     // categories A→Z
        assertEquals("Navigation", sheet.categories().get(1).name());
        List<ResolvedSheet.ResolvedEntry> editing = sheet.categories().get(0).entries();
        assertEquals(List.of("Duplicate Line", "Rename"), List.of(editing.get(0).label(), editing.get(1).label()));
    }

    @Test
    void unknownCategoryGoesToOther() {
        List<CatalogEntry> catalog = List.of(entry("x", "Xylophone"));
        ShortcutPrefs prefs = new ShortcutPrefs();
        prefs.setEnabled("x", true);
        ResolvedSheet sheet = ViewModelBuilder.build(catalog, prefs, categories(Map.of()));
        assertEquals(ShortcutCategories.OTHER, sheet.categories().get(0).name());
    }

    @Test
    void enabledIdMissingFromCatalogIsDropped() {
        List<CatalogEntry> catalog = List.of(entry("present", "Present"));
        ShortcutPrefs prefs = new ShortcutPrefs();
        prefs.setEnabled("present", true);
        prefs.setEnabled("ghost", true);   // not in catalog (key rebound away)
        ResolvedSheet sheet = ViewModelBuilder.build(catalog, prefs, categories(Map.of("present", "Nav", "ghost", "Nav")));
        int total = sheet.categories().stream().mapToInt(c -> c.entries().size()).sum();
        assertEquals(1, total);
    }
}
