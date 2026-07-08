package com.petros.ireview;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** Composes the featured (enabled) shortcuts into the grouped, A→Z {@link ResolvedSheet} for view mode. */
public final class ViewModelBuilder {

    private ViewModelBuilder() {}

    /**
     * @param categoryOf maps an actionId to its category (IntelliJ-derived); a null/blank
     *                   result buckets the entry under {@link ShortcutCategories#OTHER}.
     */
    public static ResolvedSheet build(List<CatalogEntry> catalog, ShortcutPrefs prefs,
                                      Function<String, String> categoryOf) {
        // TreeMap(CASE_INSENSITIVE_ORDER) → categories A→Z; catalog is pre-sorted A→Z so
        // insertion order within each category is already alphabetical.
        Map<String, List<ResolvedSheet.ResolvedEntry>> byCategory =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (CatalogEntry e : catalog) {
            if (!prefs.isEnabled(e.actionId())) continue;
            String category = categoryOf.apply(e.actionId());
            if (category == null || category.isBlank()) category = ShortcutCategories.OTHER;
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
