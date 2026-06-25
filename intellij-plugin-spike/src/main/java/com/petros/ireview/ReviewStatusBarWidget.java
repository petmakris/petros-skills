package com.petros.ireview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseEvent;

public final class ReviewStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    public static final String WIDGET_ID = "com.petros.ireview.statusbar";

    private final Project project;
    private final ReviewSessionClient client;
    private volatile String text = "Review: idle";
    private StatusBar statusBar;

    public ReviewStatusBarWidget(Project project, ReviewSessionClient client) {
        this.project = project;
        this.client = client;
        client.addListener(new ReviewSessionClient.Listener() {
            @Override public void onStateChanged(ReviewSessionClient.State state) {
                text = switch (state) {
                    case DORMANT -> "Review: idle — /interactive-review <PR>";
                    case CONNECTING -> "Review: connecting…";
                    case ACTIVE -> client.currentSession()
                        .map(s -> "Review: " + s.prRef() + " ✓")
                        .orElse("Review: active ✓");
                    case DISCONNECTED -> "Review: reconnecting…";
                    case PAUSED -> client.currentSession()
                        .map(s -> "Review: " + s.prRef() + " — paused")
                        .orElse("Review: paused");
                    case ENDED -> client.currentSession()
                        .map(s -> "Review: " + s.prRef() + " — ended (read-only)")
                        .orElse("Review: ended");
                };
                if (statusBar != null) statusBar.updateWidget(WIDGET_ID);
            }
            @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                if (statusBar != null) statusBar.updateWidget(WIDGET_ID);
            }
        });
    }

    @Override public @NonNls @NotNull String ID() { return WIDGET_ID; }
    @Override public @Nullable WidgetPresentation getPresentation() { return this; }
    @Override public void install(@NotNull StatusBar statusBar) { this.statusBar = statusBar; }
    @Override public void dispose() {}

    @Override public @Nullable String getTooltipText() {
        return "Click to copy /interactive-review command to clipboard";
    }
    @Override public @NotNull String getText() { return text; }
    @Override public float getAlignment() { return 0f; }
    @Override public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return e -> java.awt.Toolkit.getDefaultToolkit()
            .getSystemClipboard()
            .setContents(new StringSelection("/interactive-review "), null);
    }
}
