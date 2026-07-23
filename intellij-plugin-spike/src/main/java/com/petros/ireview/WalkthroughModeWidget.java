package com.petros.ireview;

import com.intellij.openapi.application.ApplicationManager;
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

    private final WalkthroughController controller;
    private final WalkthroughController.Listener listener;
    private StatusBar statusBar;

    public WalkthroughModeWidget(WalkthroughController controller) {
        this.controller = controller;
        this.listener = new WalkthroughController.Listener() {
            @Override public void onModeChanged(WalkthroughController.Mode mode) { update(); }
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
        return "Walkthrough: " + controller.mode().key() + " " + (controller.index() + 1) + "/" + controller.size();
    }

    @Override public float getAlignment() { return 0.5f; }

    @Override public @NotNull String getTooltipText() { return "Click to switch rail / inline"; }

    @Override public Consumer<MouseEvent> getClickConsumer() {
        return e -> controller.setMode(controller.mode() == WalkthroughController.Mode.RAIL
            ? WalkthroughController.Mode.INLINE
            : WalkthroughController.Mode.RAIL);
    }

    @Override public StatusBarWidget.WidgetPresentation getPresentation() { return this; }

    /** Controller callbacks may arrive off the EDT; widget repaints must not. */
    private void update() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (statusBar != null) statusBar.updateWidget(WIDGET_ID);
        });
    }
}
