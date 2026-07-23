package com.petros.ireview;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Status-bar widget showing the review session state with a glanceable colored
 * dot (green = live, amber = connecting/paused, red = ended, gray = idle) next
 * to the text. Click copies the /interactive-review command to the clipboard.
 */
public final class ReviewStatusBarWidget implements StatusBarWidget, CustomStatusBarWidget {

    public static final String WIDGET_ID = "com.petros.ireview.statusbar";

    private static final String GREEN = "#59A869";
    private static final String AMBER = "#C9A227";
    private static final String RED   = "#D9534F";
    private static final String GRAY  = "#888888";

    private final ReviewSessionClient client;
    private final ReviewSessionClient.Listener listener;
    private final JLabel label = new JLabel();

    public ReviewStatusBarWidget(com.intellij.openapi.project.Project project, ReviewSessionClient client) {
        this.client = client;
        label.setBorder(JBUI.Borders.empty(0, 6));
        label.setToolTipText("Click to copy /interactive-review command to clipboard");
        label.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection("/interactive-review "), null);
            }
        });
        this.listener = new ReviewSessionClient.Listener() {
            @Override public void onStateChanged(ReviewSessionClient.State state) { refresh(); }
            @Override public void onAttached(ReviewSessionClient.SessionInfo info) { refresh(); }
            @Override public void onDetached() { refresh(); }
        };
        client.addListener(listener);
        refresh();
    }

    private void refresh() {
        ApplicationManager.getApplication().invokeLater(() -> {
            String dot;
            String text;
            switch (client.state()) {
                case DORMANT -> { dot = GRAY; text = "Review: idle — /interactive-review <PR>"; }
                case CONNECTING -> { dot = AMBER; text = "Review: connecting…"; }
                case ACTIVE -> { dot = GREEN; text = "Review: " + prRefOr("active") + " ✓"; }
                case DISCONNECTED -> { dot = AMBER; text = "Review: reconnecting…"; }
                case PAUSED -> { dot = AMBER; text = "Review: " + prRefOr("paused") + " — paused"; }
                case ENDED -> { dot = RED; text = "Review: " + prRefOr("ended") + " — ended (read-only)"; }
                case OFFLINE -> { dot = GRAY; text = "Review: ○ server offline"; }
                default -> { dot = GRAY; text = "Review: idle"; }
            }
            label.setText("<html><span style='color:" + dot + ";'>●</span> " + escape(text) + "</html>");
        });
    }

    private String prRefOr(String fallback) {
        return client.currentSession().map(ReviewSessionClient.SessionInfo::prRef).orElse(fallback);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override public @NonNls @NotNull String ID() { return WIDGET_ID; }
    @Override public @NotNull JComponent getComponent() { return label; }
    @Override public @Nullable WidgetPresentation getPresentation() { return null; }
    @Override public void install(@NotNull StatusBar statusBar) { }
    @Override public void dispose() { client.removeListener(listener); }
}
