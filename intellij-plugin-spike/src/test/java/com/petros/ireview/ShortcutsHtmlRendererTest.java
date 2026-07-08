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

    @Test
    void multiKeystrokeSequenceKeepsGroupsSeparate() {
        var entry = new ResolvedSheet.ResolvedEntry("Commit and Push",
                List.of(List.of("⌘", "K"), List.of("⌘", "⇧", "K")), false);
        var cat = new ResolvedSheet.ResolvedCategory("VCS", List.of(entry));
        String html = ShortcutsHtmlRenderer.toDocument(new ResolvedSheet(List.of(cat), null), false);
        int grpCount = html.split("class=\"grp\"", -1).length - 1;
        assertEquals(2, grpCount, html); // two distinct chord groups, not one flat run
    }

    @Test
    void viewIncludesEditButtonWhenRequested() {
        var entry = new ResolvedSheet.ResolvedEntry("Go to Class", java.util.List.of(java.util.List.of("⌘", "O")), false);
        var cat = new ResolvedSheet.ResolvedCategory("Navigation", java.util.List.of(entry));
        var sheet = new ResolvedSheet(java.util.List.of(cat), null);

        String withBtn = ShortcutsHtmlRenderer.renderView(sheet, false, true, "");
        assertTrue(withBtn.contains("enterEdit"), withBtn);

        String noBtn = ShortcutsHtmlRenderer.renderView(sheet, false, false, "");
        assertFalse(noBtn.contains("enterEdit"), noBtn);
    }

    @Test
    void toDocumentStillWorksWithoutEditButton() {
        var sheet = new ResolvedSheet(java.util.List.of(), null);
        assertFalse(ShortcutsHtmlRenderer.toDocument(sheet, false).contains("enterEdit"));
    }

    @Test
    void editRendersCheckboxesCategoriesFilterAndEscapes() {
        var enabled = new EditSheet.EditRow("goc", "Go to Class",
                java.util.List.of(java.util.List.of("⌘", "O")), true, "Navigation");
        var disabled = new EditSheet.EditRow("blk", "a <b> & c",
                java.util.List.of(java.util.List.of("⌘", "⌥", "/")), false, null);
        var sheet = new EditSheet(java.util.List.of(enabled, disabled), java.util.List.of("Navigation"));

        String html = ShortcutsHtmlRenderer.renderEdit(sheet, false, "");

        assertTrue(html.startsWith("<!doctype html>"), html);
        assertTrue(html.contains("type=\"checkbox\""), html);
        assertTrue(html.contains("data-action=\"goc\""), html);        // row id
        assertTrue(html.contains("checked"), html);                     // enabled row is checked
        assertTrue(html.contains("Navigation"), html);                  // category option
        assertTrue(html.contains("__new__"), html);                     // new-category sentinel
        assertTrue(html.contains("oninput"), html);                     // filter box
        assertTrue(html.contains("a &lt;b&gt; &amp; c"), html);         // label escaped
        assertFalse(html.contains("a <b> & c"), html);
    }
}
