package com.petros.ireview;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;

/**
 * Status-bar progress indicator: "Walkthrough: 3/7". Click opens the
 * Walkthrough tool window — the show/hide control for the inline card lives
 * on that panel, not here.
 */
public final class WalkthroughModeWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    public static final String WIDGET_ID = "com.petros.ireview.walkthrough.mode";

    private final WalkthroughController controller;
    private final WalkthroughController.Listener listener;
    private StatusBar statusBar;

    public WalkthroughModeWidget(WalkthroughController controller) {
        this.controller = controller;
        this.listener = new WalkthroughController.Listener() {
            @Override public void onDocChanged(WalkthroughDoc doc) { update(); }
            @Override public void onStepActivated(WalkthroughStep step, int index, int total) { update(); }
        };
        controller.addListener(listener);
    }

    @Override public @NotNull String ID() { return WIDGET_ID; }

    @Override public void install(@NotNull StatusBar statusBar) { this.statusBar = statusBar; }

    @Override public void dispose() {
        statusBar = null;
        controller.removeListener(listener);
    }

    @Override public @NotNull String getText() {
        if (controller.doc().isEmpty()) return "Walkthrough: —";
        return "Walkthrough: " + (controller.index() + 1) + "/" + controller.size();
    }

    @Override public float getAlignment() { return 0.5f; }

    @Override public @NotNull String getTooltipText() { return "Click to open the Walkthrough panel"; }

    @Override public Consumer<MouseEvent> getClickConsumer() {
        return e -> {
            Project project = statusBar != null ? statusBar.getProject() : null;
            if (project == null) return;
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow("Walkthrough");
            if (tw != null) tw.activate(null);
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
