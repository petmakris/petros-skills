package com.petros.ireview;

import javax.swing.KeyStroke;
import java.util.List;

/**
 * Enumerates every action that has a keyboard shortcut in the active keymap.
 * Behind an interface so {@link ShortcutCatalog} is unit-testable with a fake;
 * the real implementation is {@code IdeKeymapCatalog}.
 */
public interface KeymapCatalog {
    /** {@code sequence} = the action's first keyboard shortcut (1 or 2 strokes); empty/null = unbound. */
    record Row(String actionId, String label, KeyStroke[] sequence) {}
    List<Row> all();
}
