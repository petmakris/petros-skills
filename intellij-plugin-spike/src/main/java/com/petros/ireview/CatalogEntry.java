package com.petros.ireview;

import java.util.List;

/** One selectable shortcut: id, display label, and key glyph groups (one list per keystroke). */
public record CatalogEntry(String actionId, String label, List<List<String>> groups) {}
