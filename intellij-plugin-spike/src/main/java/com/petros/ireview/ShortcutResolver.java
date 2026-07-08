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
