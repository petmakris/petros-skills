package com.petros.ireview;

import java.util.ArrayList;
import java.util.List;

/** Composes the full keymap catalog + prefs into the edit-mode checklist model. */
public final class EditModelBuilder {

    private EditModelBuilder() {}

    public static EditSheet build(List<CatalogEntry> catalog, ShortcutPrefs prefs) {
        List<EditSheet.EditRow> rows = new ArrayList<>();
        for (CatalogEntry e : catalog) {   // catalog is already A→Z by label
            rows.add(new EditSheet.EditRow(
                    e.actionId(), e.label(), e.groups(),
                    prefs.isEnabled(e.actionId()), prefs.categoryOf(e.actionId())));
        }
        return new EditSheet(rows, prefs.categories());
    }
}
