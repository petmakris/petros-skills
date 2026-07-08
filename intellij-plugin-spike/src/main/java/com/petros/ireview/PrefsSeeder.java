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
