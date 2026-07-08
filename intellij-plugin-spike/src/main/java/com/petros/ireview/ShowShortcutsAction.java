package com.petros.ireview;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;

/** Opens the read-only keyboard cheat-sheet overlay. Bound to ⌃⇧/. */
public final class ShowShortcutsAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ShortcutSheet raw = ShortcutSheetLoader.load();
        ResolvedSheet resolved = ShortcutResolver.resolve(raw, new IdeKeymapLookup());
        String html = ShortcutsHtmlRenderer.toDocument(resolved, isDarkTheme());
        new ShortcutsOverlay(e.getProject(), html).show();
    }

    private static boolean isDarkTheme() {
        Color bg = EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground();
        double luminance = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
        return luminance < 128;
    }
}
