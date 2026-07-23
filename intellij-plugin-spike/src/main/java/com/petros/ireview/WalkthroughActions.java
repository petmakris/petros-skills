package com.petros.ireview;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/** The four things a user does to a walkthrough: step, step back, ask, toggle the inline card. */
public final class WalkthroughActions {

    private WalkthroughActions() {}

    public static final String NEXT_ID = "com.petros.ireview.WalkthroughNext";
    public static final String PREV_ID = "com.petros.ireview.WalkthroughPrev";
    public static final String ASK_ID = "com.petros.ireview.WalkthroughAsk";
    public static final String TOGGLE_ID = "com.petros.ireview.WalkthroughToggleMode";

    /** Key legend for the HUD, read from the live keymap so it can never drift. */
    public static String hintText() {
        return shortcut(PREV_ID) + " back · " + shortcut(NEXT_ID) + " next · "
             + shortcut(ASK_ID) + " ask · " + shortcut(TOGGLE_ID) + " card";
    }

    private static String shortcut(String actionId) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) return "";
        var shortcuts = action.getShortcutSet().getShortcuts();
        return shortcuts.length == 0 ? "" : KeymapUtil.getShortcutText(shortcuts[0]);
    }

    private abstract static class Base extends AnAction {
        @Override public void update(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            e.getPresentation().setEnabled(project != null
                && !WalkthroughService.get(project).controller().doc().isEmpty());
        }

        @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    public static final class Next extends Base {
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            Project p = e.getProject();
            if (p != null) WalkthroughService.get(p).controller().next();
        }
    }

    public static final class Prev extends Base {
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            Project p = e.getProject();
            if (p != null) WalkthroughService.get(p).controller().prev();
        }
    }

    /** Swing input dialog — no JCEF; the JS→Java bridge is dead under IU-261. */
    public static final class Ask extends Base {
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            Project p = e.getProject();
            if (p == null) return;
            WalkthroughService service = WalkthroughService.get(p);
            Optional<WalkthroughStep> step = service.controller().current();
            if (step.isEmpty()) return;
            String text = Messages.showInputDialog(p,
                "Ask about step " + (service.controller().index() + 1)
                    + " — " + step.get().title(),
                "Walkthrough", null);
            if (text == null || text.isBlank()) return;
            service.askCurrentStep(text).exceptionally(err -> {
                ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showWarningDialog(p, String.valueOf(err.getMessage()), "Walkthrough"));
                return null;
            });
        }
    }

    /** Doesn't require an active tour to be usable: toggling the card is always safe. */
    public static final class ToggleMode extends AnAction {
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            Project p = e.getProject();
            if (p == null) return;
            WalkthroughController c = WalkthroughService.get(p).controller();
            c.setInlineVisible(!c.inlineVisible());
        }

        @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }
}
