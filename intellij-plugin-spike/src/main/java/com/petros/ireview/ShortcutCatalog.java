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
