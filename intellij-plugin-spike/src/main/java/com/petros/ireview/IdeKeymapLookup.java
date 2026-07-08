package com.petros.ireview;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.keymap.KeymapManager;

import javax.swing.KeyStroke;
import java.util.Optional;

/** {@link KeymapLookup} backed by the live {@code ActionManager} + active keymap. */
public final class IdeKeymapLookup implements KeymapLookup {

    @Override
    public Optional<Hit> find(String actionId) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) return Optional.empty(); // unknown action ID

        String label = action.getTemplatePresentation().getText();
        if (label == null || label.isBlank()) label = actionId;

        Shortcut[] shortcuts = KeymapManager.getInstance().getActiveKeymap().getShortcuts(actionId);
        for (Shortcut s : shortcuts) {
            if (s instanceof KeyboardShortcut kbs) {
                KeyStroke first = kbs.getFirstKeyStroke();
                KeyStroke second = kbs.getSecondKeyStroke();
                KeyStroke[] seq = (second == null)
                        ? new KeyStroke[]{first}
                        : new KeyStroke[]{first, second};
                return Optional.of(new Hit(label, seq));
            }
        }
        return Optional.of(new Hit(label, new KeyStroke[0])); // known but unbound
    }
}
