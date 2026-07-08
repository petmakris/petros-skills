package com.petros.ireview;

import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ShortcutResolverTest {

    /** Fake keymap: GotoClass -> ⌘O; RenameElement -> present but unbound; others unknown. */
    private static KeymapLookup fakeLookup() {
        Map<String, KeymapLookup.Hit> table = Map.of(
                "GotoClass", new KeymapLookup.Hit("Go to Class",
                        new KeyStroke[]{ KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.META_DOWN_MASK) }),
                "RenameElement", new KeymapLookup.Hit("Rename", new KeyStroke[0]));
        return actionId -> Optional.ofNullable(table.get(actionId));
    }

    private static ShortcutSheet sheet(ShortcutSheet.Entry... entries) {
        return new ShortcutSheet(List.of(new ShortcutSheet.Category("Nav", List.of(entries))), null);
    }

    @Test
    void resolvesKeysAndPrefersLabelOverride() {
        ResolvedSheet r = ShortcutResolver.resolve(
                sheet(new ShortcutSheet.Entry("GotoClass", "Class!", true)), fakeLookup());

        ResolvedSheet.ResolvedEntry e = r.categories().get(0).entries().get(0);
        assertEquals("Class!", e.label());            // override wins
        assertFalse(e.unassigned());
        assertEquals(List.of(List.of("⌘", "O")), e.groups());
    }

    @Test
    void fallsBackToActionLabelWhenNoOverride() {
        ResolvedSheet r = ShortcutResolver.resolve(
                sheet(new ShortcutSheet.Entry("GotoClass", null, true)), fakeLookup());
        assertEquals("Go to Class", r.categories().get(0).entries().get(0).label());
    }

    @Test
    void disabledEntriesAreSkipped() {
        ResolvedSheet r = ShortcutResolver.resolve(
                sheet(new ShortcutSheet.Entry("GotoClass", null, false)), fakeLookup());
        assertTrue(r.categories().isEmpty()); // category empty -> dropped
    }

    @Test
    void unknownActionSkipped_unboundActionMarkedUnassigned() {
        ResolvedSheet r = ShortcutResolver.resolve(
                sheet(new ShortcutSheet.Entry("DoesNotExist", null, true),
                      new ShortcutSheet.Entry("RenameElement", null, true)), fakeLookup());

        List<ResolvedSheet.ResolvedEntry> entries = r.categories().get(0).entries();
        assertEquals(1, entries.size());
        assertEquals("Rename", entries.get(0).label());
        assertTrue(entries.get(0).unassigned());
        assertTrue(entries.get(0).groups().isEmpty());
    }

    @Test
    void errorSheetPropagates() {
        ResolvedSheet r = ShortcutResolver.resolve(ShortcutSheet.error("boom"), fakeLookup());
        assertTrue(r.isError());
        assertEquals("boom", r.error());
    }
}
