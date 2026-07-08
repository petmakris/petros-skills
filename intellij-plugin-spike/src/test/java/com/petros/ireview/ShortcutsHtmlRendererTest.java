package com.petros.ireview;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShortcutsHtmlRendererTest {

    private static ResolvedSheet oneRow() {
        var entry = new ResolvedSheet.ResolvedEntry("Go to Class", List.of(List.of("⌘", "O")), false);
        var cat = new ResolvedSheet.ResolvedCategory("Navigation", List.of(entry));
        return new ResolvedSheet(List.of(cat), null);
    }

    @Test
    void rendersCategoryLabelAndCaps() {
        String html = ShortcutsHtmlRenderer.toDocument(oneRow(), false);
        assertTrue(html.startsWith("<!doctype html>"), html);
        assertTrue(html.contains("Navigation"), html);
        assertTrue(html.contains("Go to Class"), html);
        assertTrue(html.contains(">⌘<"), html);
        assertTrue(html.contains(">O<"), html);
    }

    @Test
    void usesColumnLayoutCss() {
        String html = ShortcutsHtmlRenderer.toDocument(oneRow(), false);
        assertTrue(html.contains("column-width"), html);
    }

    @Test
    void unassignedEntryShowsTag() {
        var entry = new ResolvedSheet.ResolvedEntry("Rename", List.of(), true);
        var cat = new ResolvedSheet.ResolvedCategory("Refactor", List.of(entry));
        String html = ShortcutsHtmlRenderer.toDocument(new ResolvedSheet(List.of(cat), null), false);
        assertTrue(html.contains("Rename"), html);
        assertTrue(html.contains("unassigned"), html);
    }

    @Test
    void errorSheetRendersMessage() {
        String html = ShortcutsHtmlRenderer.toDocument(new ResolvedSheet(List.of(), "boom"), false);
        assertTrue(html.contains("boom"), html);
    }

    @Test
    void escapesHtmlInLabels() {
        var entry = new ResolvedSheet.ResolvedEntry("a <b> & c", List.of(List.of("A")), false);
        var cat = new ResolvedSheet.ResolvedCategory("X", List.of(entry));
        String html = ShortcutsHtmlRenderer.toDocument(new ResolvedSheet(List.of(cat), null), false);
        assertTrue(html.contains("a &lt;b&gt; &amp; c"), html);
        assertFalse(html.contains("a <b> & c"), html);
    }
}
