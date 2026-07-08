package com.petros.ireview;

import java.util.List;

/** Every keymap shortcut as a checkable row (A→Z), plus the categories currently in use. */
public record EditSheet(List<EditRow> rows, List<String> categories) {

    /** {@code category} may be null (shown as a placeholder). */
    public record EditRow(String actionId, String label, List<List<String>> groups,
                          boolean enabled, String category) {}
}
