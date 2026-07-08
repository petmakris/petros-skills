package com.petros.ireview;

import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShortcutCatalogTest {

    private static KeymapCatalog.Row row(String id, String label, KeyStroke... seq) {
        return new KeymapCatalog.Row(id, label, seq);
    }

    @Test
    void buildsGlyphsAndSortsByLabel() {
        KeymapCatalog src = () -> List.of(
            row("GotoClass", "Go to Class", KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.META_DOWN_MASK)),
            row("Duplicate", "Duplicate Line", KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.META_DOWN_MASK)));

        List<CatalogEntry> out = ShortcutCatalog.build(src);

        assertEquals(2, out.size());
        assertEquals("Duplicate Line", out.get(0).label());     // D before G
        assertEquals("Go to Class", out.get(1).label());
        assertEquals(List.of(List.of("⌘", "O")), out.get(1).groups());
    }

    @Test
    void rowsWithoutKeystrokeAreExcluded() {
        KeymapCatalog src = () -> List.of(
            row("Bound", "Bound", KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.META_DOWN_MASK)),
            row("Unbound", "Unbound"),                 // empty sequence
            new KeymapCatalog.Row("NullSeq", "NullSeq", null));

        List<CatalogEntry> out = ShortcutCatalog.build(src);
        assertEquals(1, out.size());
        assertEquals("Bound", out.get(0).label());
    }

    @Test
    void labelFallsBackToActionId() {
        KeymapCatalog src = () -> List.of(
            row("SomeId", "  ", KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.META_DOWN_MASK)));
        assertEquals("SomeId", ShortcutCatalog.build(src).get(0).label());
    }
}
