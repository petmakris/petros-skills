package com.petros.ireview;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

/** Opens the editable keyboard cheat-sheet overlay (⌃⌥⇧/). Seeds prefs from the legacy YAML once. */
public final class ShowShortcutsAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ShortcutPrefs prefs = ApplicationManager.getApplication().getService(ShortcutPrefs.class);
        PrefsSeeder.seedIfNeeded(prefs, ShortcutSheetLoader.load());
        new ShortcutsOverlay(e.getProject(), prefs).show();
    }
}
