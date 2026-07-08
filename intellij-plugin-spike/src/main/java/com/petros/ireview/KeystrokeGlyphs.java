package com.petros.ireview;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a {@link KeyStroke} into an ordered list of display tokens:
 * modifiers as mac glyphs in the canonical order {@code ⌃ ⌥ ⇧ ⌘}, then the key.
 * Pure — no IDE dependencies — so it is unit-testable with hand-built keystrokes.
 */
public final class KeystrokeGlyphs {

    private KeystrokeGlyphs() {}

    public static List<String> tokens(KeyStroke ks) {
        List<String> out = new ArrayList<>();
        int m = ks.getModifiers();
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0)  out.add("⌃");
        if ((m & InputEvent.ALT_DOWN_MASK) != 0)   out.add("⌥");
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) out.add("⇧");
        if ((m & InputEvent.META_DOWN_MASK) != 0)  out.add("⌘");
        out.add(keyGlyph(ks.getKeyCode()));
        return out;
    }

    private static String keyGlyph(int vk) {
        return switch (vk) {
            case KeyEvent.VK_ENTER      -> "↵";
            case KeyEvent.VK_BACK_SPACE -> "⌫";
            case KeyEvent.VK_DELETE     -> "⌦";
            case KeyEvent.VK_ESCAPE     -> "Esc";
            case KeyEvent.VK_TAB        -> "⇥";
            case KeyEvent.VK_SPACE      -> "Space";
            case KeyEvent.VK_LEFT       -> "←";
            case KeyEvent.VK_RIGHT      -> "→";
            case KeyEvent.VK_UP         -> "↑";
            case KeyEvent.VK_DOWN       -> "↓";
            case KeyEvent.VK_SLASH      -> "/";
            case KeyEvent.VK_COMMA      -> ",";
            case KeyEvent.VK_PERIOD     -> ".";
            case KeyEvent.VK_MINUS      -> "-";
            case KeyEvent.VK_EQUALS     -> "=";
            case KeyEvent.VK_BACK_QUOTE -> "`";
            default -> KeyEvent.getKeyText(vk); // letters, digits, F-keys, etc.
        };
    }
}
