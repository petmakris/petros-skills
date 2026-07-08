package com.petros.ireview;

import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeystrokeGlyphsTest {

    @Test
    void cmdShiftA() {
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_A,
                InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        assertEquals(List.of("⇧", "⌘", "A"), KeystrokeGlyphs.tokens(ks));
    }

    @Test
    void modifierOrderIsCtrlAltShiftCmd() {
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_L,
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
                        | InputEvent.SHIFT_DOWN_MASK | InputEvent.META_DOWN_MASK);
        assertEquals(List.of("⌃", "⌥", "⇧", "⌘", "L"), KeystrokeGlyphs.tokens(ks));
    }

    @Test
    void functionKeyKeptAsIs() {
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.ALT_DOWN_MASK);
        assertEquals(List.of("⌥", "F7"), KeystrokeGlyphs.tokens(ks));
    }

    @Test
    void specialKeysUseGlyphs() {
        assertEquals(List.of("⌘", "⌫"),
                KeystrokeGlyphs.tokens(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, InputEvent.META_DOWN_MASK)));
        assertEquals(List.of("⌥", "↑"),
                KeystrokeGlyphs.tokens(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK)));
        assertEquals(List.of("⌃", "⇧", "/"),
                KeystrokeGlyphs.tokens(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH,
                        InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
    }
}
