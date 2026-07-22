package com.petros.ireview;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;

/**
 * Status-bar toggle: "Walkthrough: rail 3/7" / "Walkthrough: inline 3/7". Click
 * flips the renderer via the controller — no mode logic lives here.
 */
public final class WalkthroughModeWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    public static final String WIDGET_ID = "com.petros.ireview.walkthrough.mode";

    private final Project project;
    private final WalkthroughController.Listener listener;
    private StatusBar statusBar;

    public WalkthroughModeWidget(Project project) {
        this.project = project;
        this.listener = new WalkthroughController.Listener() {
            @Override public void onModeChanged(WalkthroughController.Mode mode) { update(); }
            @Override public void onDocChanged(WalkthroughDoc doc) { update(); }
            @Override public void onStepActivated(WalkthroughStep step, int index, int total) { update(); }
        };
        WalkthroughService.get(project).controller().addListener(listener);
    }

    @Override public @NotNull String ID() { return WIDGET_ID; }

    @Override public void install(@NotNull StatusBar statusBar) { this.statusBar = statusBar; }

    @Override public void dispose() {
        statusBar = null;
        WalkthroughService.get(project).controller().removeListener(listener);
    }

    @Override public @NotNull String getText() {
        WalkthroughController c = WalkthroughService.get(project).controller();
        if (c.doc().isEmpty()) return "Walkthrough: —";
        return "Walkthrough: " + c.mode().key() + " " + (c.index() + 1) + "/" + c.size();
    }

    @Override public float getAlignment() { return 0.5f; }

    @Override public @NotNull String getTooltipText() { return "Click to switch rail / inline"; }

    @Override public Consumer<MouseEvent> getClickConsumer() {
        return e -> {
            WalkthroughController c = WalkthroughService.get(project).controller();
            c.setMode(c.mode() == WalkthroughController.Mode.RAIL
                ? WalkthroughController.Mode.INLINE
                : WalkthroughController.Mode.RAIL);
        };
    }

    @Override public StatusBarWidget.WidgetPresentation getPresentation() { return this; }

    /** Controller callbacks may arrive off the EDT; widget repaints must not. */
    private void update() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (statusBar != null) statusBar.updateWidget(WIDGET_ID);
        });
    }
}
