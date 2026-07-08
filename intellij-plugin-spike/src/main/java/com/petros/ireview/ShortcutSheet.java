package com.petros.ireview;

import java.util.List;

/** Raw, unresolved cheat-sheet model parsed from {@code shortcuts.yml}. */
public record ShortcutSheet(List<Category> categories, String error) {

    public boolean isError() { return error != null; }

    public static ShortcutSheet error(String msg) {
        return new ShortcutSheet(List.of(), msg);
    }

    public record Category(String name, List<Entry> entries) {}

    /** One shortcut row. {@code label} may be null (use the action's own text). */
    public record Entry(String action, String label, boolean enabled) {}
}
