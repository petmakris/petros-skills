package com.petros.ireview;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;

import javax.swing.KeyStroke;
import java.util.ArrayList;
import java.util.List;

/** {@link KeymapCatalog} over the live active keymap: every action with a keyboard shortcut. */
public final class IdeKeymapCatalog implements KeymapCatalog {

    @Override
    public List<Row> all() {
        List<Row> rows = new ArrayList<>();
        Keymap keymap = KeymapManager.getInstance().getActiveKeymap();
        ActionManager actions = ActionManager.getInstance();
        for (String actionId : keymap.getActionIdList()) {
            KeyStroke[] seq = firstKeyboardSequence(keymap.getShortcuts(actionId));
            if (seq.length == 0) continue;                  // mouse-only or unbound
            AnAction action = actions.getAction(actionId);
            String label = action == null ? null : action.getTemplatePresentation().getText();
            rows.add(new Row(actionId, (label == null || label.isBlank()) ? actionId : label, seq));
        }
        return rows;
    }

    private static KeyStroke[] firstKeyboardSequence(Shortcut[] shortcuts) {
        for (Shortcut s : shortcuts) {
            if (s instanceof KeyboardShortcut kbs) {
                KeyStroke first = kbs.getFirstKeyStroke();
                KeyStroke second = kbs.getSecondKeyStroke();
                return second == null ? new KeyStroke[]{first} : new KeyStroke[]{first, second};
            }
        }
        return new KeyStroke[0];
    }
}
