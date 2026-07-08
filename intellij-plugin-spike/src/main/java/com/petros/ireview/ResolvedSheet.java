package com.petros.ireview;

import java.util.List;

/** Display-ready cheat-sheet: labels + key glyph groups, keymap already applied. */
public record ResolvedSheet(List<ResolvedCategory> categories, String error) {

    public boolean isError() { return error != null; }

    public record ResolvedCategory(String name, List<ResolvedEntry> entries) {}

    /**
     * One row. {@code groups} holds one token list per keystroke in the
     * shortcut sequence (usually a single group). {@code unassigned} is true
     * when the action exists but has no key bound.
     */
    public record ResolvedEntry(String label, List<List<String>> groups, boolean unassigned) {}
}
