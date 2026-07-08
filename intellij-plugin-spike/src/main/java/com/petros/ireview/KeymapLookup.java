package com.petros.ireview;

import javax.swing.KeyStroke;
import java.util.Optional;

/**
 * Abstracts the active keymap so {@link ShortcutResolver} can be unit-tested
 * with a fake. The real implementation ({@code IdeKeymapLookup}) reads
 * {@code ActionManager} + {@code KeymapManager}.
 */
public interface KeymapLookup {

    /**
     * @param label    the action's display text
     * @param sequence the first keyboard shortcut's keystrokes (1 or 2);
     *                 length 0 means the action is known but has no binding
     */
    record Hit(String label, KeyStroke[] sequence) {}

    /** Empty means the action ID is unknown. */
    Optional<Hit> find(String actionId);
}
