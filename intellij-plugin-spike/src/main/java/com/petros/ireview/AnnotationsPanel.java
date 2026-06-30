package com.petros.ireview;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Side-panel component: a live list of every annotated line in the current
 * interactive_review session. Subscribes to ReviewSessionClient for updates.
 *
 * Click a row → open file at line + open the popup for that anchor.
 * Yellow dot on rows whose version is greater than what the user last saw
 * (in-memory only; not persisted across IDE restart).
 */
public final class AnnotationsPanel implements com.intellij.openapi.Disposable {

    private final Project project;
    private final ReviewSessionClient client;
    private final ReviewSessionClient.Listener listener;
    private final DefaultListModel<AnnotationEntry> model = new DefaultListModel<>();
    private final JBList<AnnotationEntry> list = new JBList<>(model);
    private final JLabel titleLabel = new JLabel("Review · idle");
    private final JLabel footer = new JLabel("● live", JLabel.LEFT);
    private final JLabel countLabel = new JLabel();
    private final JBTextField searchField = new JBTextField();
    private final JButton openDiffButton = new JButton("Open PR diff in IDE", AllIcons.Actions.Diff);
    private final JButton endReviewButton = new JButton(AllIcons.Actions.Cancel);
    private final Map<String, Integer> seenVersions = new HashMap<>();
    private final JPanel root;
    /** Index of the row the mouse is currently over, or -1. Drives × visibility. */
    private int hoveredIndex = -1;
    /** True when the mouse is specifically inside the × hit zone (drives the button's hover color). */
    private boolean hoveringDeleteButton = false;
    /** Anchors with a delete request in flight, awaiting SSE thread-deleted confirmation. */
    private final Set<String> deleting = ConcurrentHashMap.newKeySet();
    /** Pixel width of the × hit zone on the right edge of a row. */
    private static final int DELETE_ZONE_WIDTH = 34;
    /** Pixel height of the × hit zone from the top of a row. */
    private static final int DELETE_ZONE_HEIGHT = 28;

    /** 8-frame rotating spinner used while a delete is in flight. */
    private static final Icon[] SPINNER_FRAMES = {
        AllIcons.Process.Step_1, AllIcons.Process.Step_2,
        AllIcons.Process.Step_3, AllIcons.Process.Step_4,
        AllIcons.Process.Step_5, AllIcons.Process.Step_6,
        AllIcons.Process.Step_7, AllIcons.Process.Step_8,
    };
    private int spinFrame = 0;
    private Timer spinTimer;

    public AnnotationsPanel(@NotNull Project project) {
        this.project = project;
        this.client = ReviewSessionService.get(project).client();

        list.setCellRenderer(this::renderCell);
        // Onboarding empty state — otherwise a fresh session shows a blank box
        // with no hint of how to start.
        list.getEmptyText().setText("No questions yet");
        list.getEmptyText().appendLine("Open the PR diff, hover a changed line, click 💬 to ask Claude.");
        list.getEmptyText().appendLine("");
        list.getEmptyText().appendLine("Copy /interactive-review <PR>",
            com.intellij.ui.SimpleTextAttributes.LINK_ATTRIBUTES,
            e -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection("/interactive-review "), null));
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 1) return;
                int idx = list.locationToIndex(e.getPoint());
                if (idx < 0) return;
                AnnotationEntry entry = model.getElementAt(idx);
                if (deleting.contains(entry.anchor())) return;  // already in flight
                Rectangle bounds = list.getCellBounds(idx, idx);
                if (bounds != null && idx == hoveredIndex && isDeleteZone(e, bounds, entry)) {
                    handleDelete(entry);
                    return;
                }
                onRowClicked(entry);
            }
            @Override
            public void mouseMoved(MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                Rectangle bounds = idx >= 0 ? list.getCellBounds(idx, idx) : null;
                boolean inBtn = bounds != null
                    && idx == hoveredIndex
                    && model.getElementAt(idx) != null
                    && !deleting.contains(model.getElementAt(idx).anchor())
                    && isDeleteZone(e, bounds, model.getElementAt(idx));
                boolean changed = false;
                if (idx != hoveredIndex) {
                    hoveredIndex = idx;
                    changed = true;
                }
                if (inBtn != hoveringDeleteButton) {
                    hoveringDeleteButton = inBtn;
                    changed = true;
                }
                if (changed) list.repaint();
            }
            @Override
            public void mouseExited(MouseEvent e) {
                if (hoveredIndex != -1 || hoveringDeleteButton) {
                    hoveredIndex = -1;
                    hoveringDeleteButton = false;
                    list.repaint();
                }
            }
        };
        list.addMouseListener(mouse);
        list.addMouseMotionListener(mouse);

        searchField.getEmptyText().setText("Filter…");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { rebuild(); }
            @Override public void removeUpdate(DocumentEvent e) { rebuild(); }
            @Override public void changedUpdate(DocumentEvent e) { rebuild(); }
        });

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setBorder(JBUI.Borders.empty(6, 8, 4, 8));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12f));
        countLabel.setFont(countLabel.getFont().deriveFont(10.5f));
        countLabel.setForeground(JBColor.GRAY);
        header.add(titleLabel, BorderLayout.WEST);
        header.add(countLabel, BorderLayout.EAST);

        openDiffButton.setToolTipText("Diff every changed file against the PR base with the working tree "
            + "on the right — the only diff shape the ask-+ gutter icon attaches to");
        openDiffButton.addActionListener(e -> openPrDiff());
        endReviewButton.setToolTipText("End this review session — stops the watcher and "
            + "clears it from the IDE. Cannot be undone.");
        endReviewButton.addActionListener(e -> endReview());
        JPanel openRow = new JPanel(new BorderLayout(6, 0));
        openRow.setBorder(JBUI.Borders.empty(0, 8, 4, 8));
        openRow.add(openDiffButton, BorderLayout.CENTER);
        openRow.add(endReviewButton, BorderLayout.EAST);

        JPanel topStack = new JPanel(new BorderLayout());
        topStack.add(header, BorderLayout.NORTH);
        topStack.add(openRow, BorderLayout.SOUTH);

        JPanel headerWrap = new JPanel(new BorderLayout(0, 4));
        headerWrap.add(topStack, BorderLayout.NORTH);
        headerWrap.add(searchField, BorderLayout.SOUTH);
        headerWrap.setBorder(JBUI.Borders.emptyBottom(4));

        footer.setFont(footer.getFont().deriveFont(10f));
        JLabel buildLabel = new JLabel(BuildInfo.label(), JLabel.RIGHT);
        buildLabel.setFont(buildLabel.getFont().deriveFont(10f));
        buildLabel.setForeground(JBColor.GRAY);
        buildLabel.setToolTipText(BuildInfo.tooltip());
        JPanel footerRow = new JPanel(new BorderLayout());
        footerRow.setBorder(JBUI.Borders.empty(4, 8));
        footerRow.add(footer, BorderLayout.WEST);
        footerRow.add(buildLabel, BorderLayout.EAST);

        root = new JPanel(new BorderLayout());
        root.add(headerWrap, BorderLayout.NORTH);
        root.add(new JBScrollPane(list), BorderLayout.CENTER);
        root.add(footerRow, BorderLayout.SOUTH);

        listener = new ReviewSessionClient.Listener() {
            @Override public void onStateChanged(ReviewSessionClient.State state) {
                SwingUtilities.invokeLater(() -> {
                    // No delete confirmation is coming while paused/ended/idle —
                    // clear in-flight spinners so rows recover.
                    if (state != ReviewSessionClient.State.ACTIVE
                            && state != ReviewSessionClient.State.CONNECTING
                            && !deleting.isEmpty()) {
                        deleting.clear();
                        list.repaint();
                    }
                    refreshTitle();
                });
            }
            @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                SwingUtilities.invokeLater(() -> { refreshTitle(); rebuild(); });
            }
            @Override public void onDetached() {
                SwingUtilities.invokeLater(() -> { deleting.clear(); refreshTitle(); rebuild(); });
            }
            @Override public void onThreadChanged(String anchor, String synthesis, int version) {
                SwingUtilities.invokeLater(AnnotationsPanel.this::rebuild);
            }
            @Override public void onThreadDeleted(String anchor) {
                SwingUtilities.invokeLater(() -> {
                    deleting.remove(anchor);
                    rebuild();
                });
            }
            @Override public void onPendingChanged(String anchor, boolean isPending) {
                SwingUtilities.invokeLater(list::repaint);
            }
        };
        client.addListener(listener);

        refreshTitle();
        rebuild();
    }

    public JComponent getComponent() {
        return root;
    }

    @Override
    public void dispose() {
        client.removeListener(listener);
        if (spinTimer != null) spinTimer.stop();
    }

    private static final JBColor LIVE_COLOR =
        new JBColor(new Color(0xb0, 0x90, 0x10), new Color(0xf1, 0xc4, 0x0f));
    private static final JBColor GONE_COLOR =
        new JBColor(new Color(0xc0, 0x32, 0x21), new Color(0xf8, 0x73, 0x71));

    private void refreshTitle() {
        titleLabel.setText(client.currentSession()
            .map(s -> "Review · " + truncate(s.prRef(), 28))
            .orElse("Review · idle"));
        boolean hasSession = client.currentSession().isPresent();
        ReviewSessionClient.State st = client.state();
        openDiffButton.setEnabled(hasSession);
        // "End review" only makes sense for a session that is still running —
        // an already-ENDED (frozen) session has nothing left to end.
        endReviewButton.setEnabled(hasSession && st != ReviewSessionClient.State.ENDED);
        // The footer must tell the truth about the Claude session, not just
        // about server reachability.
        if (!hasSession) {
            footer.setText("● idle");
            footer.setForeground(LIVE_COLOR);
        } else if (st == ReviewSessionClient.State.ENDED) {
            footer.setText("● session ended — read-only");
            footer.setForeground(GONE_COLOR);
        } else if (st == ReviewSessionClient.State.PAUSED
                || st == ReviewSessionClient.State.DISCONNECTED) {
            footer.setText("● paused — reconnecting…");
            footer.setForeground(LIVE_COLOR);
        } else {
            footer.setText("● live");
            footer.setForeground(LIVE_COLOR);
        }
    }

    private void openPrDiff() {
        client.currentSession().ifPresent(s -> GhPrDiffOpener.open(project, s));
    }

    private void endReview() {
        var session = client.currentSession();
        if (session.isEmpty()) return;
        int choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            "End the review of " + session.get().prRef()
                + "? This stops the watcher and removes the session from the IDE. "
                + "It cannot be undone.",
            "End Interactive Review",
            "End review", "Keep it",
            com.intellij.openapi.ui.Messages.getWarningIcon());
        if (choice != com.intellij.openapi.ui.Messages.YES) return;
        endReviewButton.setEnabled(false);
        client.cancelSession().whenComplete((v, t) -> SwingUtilities.invokeLater(() -> {
            if (t != null) {
                refreshTitle(); // re-enable the button if it's still attached
                com.intellij.notification.Notifications.Bus.notify(
                    new com.intellij.notification.Notification(
                        "Interactive Review",
                        "Couldn't end review",
                        t.getMessage() == null ? t.toString() : t.getMessage(),
                        com.intellij.notification.NotificationType.ERROR),
                    project);
            }
            // Success: onDetached fires refreshTitle()+rebuild() via the listener.
        }));
    }

    private void rebuild() {
        String q = searchField.getText().toLowerCase();
        List<AnnotationEntry> rows = new ArrayList<>();
        for (var e : client.snapshotCache().entrySet()) {
            String anchor = e.getKey();
            if (!q.isEmpty() && !anchor.toLowerCase().contains(q)) continue;
            var thread = e.getValue();
            int last = seenVersions.getOrDefault(anchor, 0);
            rows.add(new AnnotationEntry(
                anchor,
                PanelRowTitle.resolve(thread.title(), thread.question(), thread.synthesis(), anchor),
                thread.version(),
                0L,
                thread.version() > last,
                isStale(anchor)
            ));
        }
        // Live rows first; stale (drifted) rows grouped at the bottom so they
        // don't get lost interleaved. Stable by anchor within each group.
        rows.sort(Comparator.comparing(AnnotationEntry::stale).thenComparing(AnnotationEntry::anchor));
        model.clear();
        for (var r : rows) model.addElement(r);
        countLabel.setText(rows.size() + " annotation" + (rows.size() == 1 ? "" : "s"));
    }

    private Component renderCell(JList<? extends AnnotationEntry> jbList,
                                 AnnotationEntry entry,
                                 int index,
                                 boolean selected,
                                 boolean focused) {
        JPanel row = new JPanel(new BorderLayout(0, 3));
        row.setBorder(JBUI.Borders.empty(8, 10));
        row.setOpaque(true);
        row.setBackground(selected
            ? new JBColor(new Color(0x1a, 0x3a, 0x5e), new Color(0x1a, 0x3a, 0x5e))
            : new JBColor(new Color(0xf0, 0xf0, 0xf0), new Color(0x23, 0x25, 0x27)));

        boolean stale = entry.stale();

        // Title line: clean summary title (WEST) + ×/spinner (EAST).
        JPanel titleLine = new JPanel(new BorderLayout());
        titleLine.setOpaque(false);
        JLabel titleLbl = new JLabel(truncate(entry.title(), 64));
        titleLbl.setFont(titleLbl.getFont().deriveFont(Font.BOLD, 12.5f));
        titleLbl.setForeground(stale ? JBColor.GRAY
            : (selected ? new Color(0xe8, 0xe8, 0xe8) : new Color(0xd0, 0xd2, 0xd6)));
        titleLine.add(titleLbl, BorderLayout.WEST);

        JPanel rightCluster = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightCluster.setOpaque(false);
        if (deleting.contains(entry.anchor())) {
            JLabel s = new JLabel(SPINNER_FRAMES[spinFrame]);
            s.setBorder(JBUI.Borders.empty(2, 6));
            rightCluster.add(s);
        } else if (index == hoveredIndex && !client.isPending(entry.anchor())) {
            rightCluster.add(makeDeleteButtonVisual(hoveringDeleteButton));
        }
        titleLine.add(rightCluster, BorderLayout.EAST);

        // Meta line: file:side:line (muted, WEST) + v# / state (EAST).
        String[] parts = entry.anchor().split(":", 3);
        String pathOnly = parts.length >= 1 ? lastSegment(parts[0]) : entry.anchor();
        String lineRef = parts.length >= 3 ? ":" + parts[1] + ":" + parts[2] : "";
        JLabel locLbl = new JLabel(pathOnly + lineRef);
        locLbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11).deriveFont(10.5f));
        locLbl.setForeground(stale ? JBColor.GRAY : new Color(0x8a, 0x8d, 0x93));

        JPanel rightMeta = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightMeta.setOpaque(false);
        JLabel verLbl = new JLabel("v" + entry.version());
        verLbl.setForeground(new Color(0x80, 0x80, 0x80));
        verLbl.setFont(verLbl.getFont().deriveFont(10f));
        rightMeta.add(verLbl);
        if (stale) {
            JLabel st = new JLabel("⚠ stale");
            st.setForeground(JBColor.GRAY);
            st.setFont(st.getFont().deriveFont(10f));
            rightMeta.add(st);
        } else if (entry.isNew()) {
            JLabel dot = new JLabel("●");
            dot.setForeground(new Color(0xf1, 0xc4, 0x0f));
            dot.setFont(dot.getFont().deriveFont(12f));
            rightMeta.add(dot);
        }

        JPanel metaLine = new JPanel(new BorderLayout());
        metaLine.setOpaque(false);
        metaLine.add(locLbl, BorderLayout.WEST);
        metaLine.add(rightMeta, BorderLayout.EAST);

        row.add(titleLine, BorderLayout.NORTH);
        row.add(metaLine, BorderLayout.SOUTH);
        return row;
    }

    private boolean isDeleteZone(MouseEvent e, Rectangle cellBounds, AnnotationEntry entry) {
        if (client.isPending(entry.anchor())) return false;
        int xInCell = e.getX() - cellBounds.x;
        int yInCell = e.getY() - cellBounds.y;
        return xInCell >= cellBounds.width - DELETE_ZONE_WIDTH
            && yInCell <= DELETE_ZONE_HEIGHT;
    }

    private void handleDelete(AnnotationEntry entry) {
        deleting.add(entry.anchor());
        // Mouse is still on the (about-to-be-deleted) row but × is gone; reset
        // the button-hover flag so the next row paints cleanly.
        hoveringDeleteButton = false;
        ensureSpinTimer();
        list.repaint();
        client.deleteThread(entry.anchor()).whenComplete((v, t) -> SwingUtilities.invokeLater(() -> {
            if (t != null) {
                deleting.remove(entry.anchor());
                list.repaint();
                com.intellij.notification.Notifications.Bus.notify(
                    new com.intellij.notification.Notification(
                        "Interactive Review",
                        "Delete failed",
                        t.getMessage() == null ? t.toString() : t.getMessage(),
                        com.intellij.notification.NotificationType.ERROR),
                    project);
                return;
            }
            // Success: SSE thread-deleted normally clears `deleting` within ~1s.
            // Guard against a missed confirmation so the row can't spin forever.
            scheduleDeleteFallback(entry.anchor());
        }));
    }

    /** If no thread-deleted confirmation arrives, stop the spinner so the row
     *  becomes interactive again instead of spinning indefinitely. */
    private void scheduleDeleteFallback(String anchor) {
        Timer t = new Timer(8000, e -> {
            if (deleting.remove(anchor)) list.repaint();
        });
        t.setRepeats(false);
        t.start();
    }

    private void ensureSpinTimer() {
        if (spinTimer != null && spinTimer.isRunning()) return;
        spinTimer = new Timer(80, e -> {
            if (deleting.isEmpty()) {
                spinTimer.stop();
                return;
            }
            spinFrame = (spinFrame + 1) % SPINNER_FRAMES.length;
            list.repaint();
        });
        spinTimer.setRepeats(true);
        spinTimer.start();
    }

    /** A right-edge delete button drawn as a real button, not a bare icon.
     *  Base state is muted; hot=true (mouse is in the button's hit zone) flips
     *  it to a red accent so the user sees a click target light up. */
    private static JComponent makeDeleteButtonVisual(boolean hot) {
        JLabel btn = new JLabel("×");  // multiplication sign — sharp, monospace
        btn.setHorizontalAlignment(SwingConstants.CENTER);
        btn.setVerticalAlignment(SwingConstants.CENTER);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 14f));
        Color base    = new JBColor(new Color(0xd0, 0xd0, 0xd0), new Color(0x4a, 0x4d, 0x52));
        Color hover   = new JBColor(new Color(0xd9, 0x4a, 0x4a), new Color(0xd9, 0x4a, 0x4a));
        Color baseFg  = new JBColor(new Color(0x40, 0x40, 0x40), new Color(0xcc, 0xcc, 0xcc));
        btn.setOpaque(true);
        btn.setBackground(hot ? hover : base);
        btn.setForeground(hot ? Color.WHITE : baseFg);
        btn.setBorder(JBUI.Borders.empty(0, 7));
        btn.setPreferredSize(new Dimension(24, 20));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText("Delete thread");
        return btn;
    }

    /** True if this anchor's thread is currently stale in an open diff editor. */
    private boolean isStale(String anchor) {
        String[] p = anchor.split(":", 3);
        if (p.length < 3) return false;
        var editor = SpikeDiffExtension.editorFor(p[0] + ":" + p[1]);
        if (editor == null) return false;
        var ts = client.threadFor(anchor).orElse(null);
        if (ts == null) return false;
        int recorded;
        try { recorded = Integer.parseInt(p[2]); } catch (NumberFormatException e) { return false; }
        var lines = new ArrayList<String>();
        var doc = editor.getDocument();
        for (int i = 0; i < doc.getLineCount(); i++) {
            lines.add(doc.getText(new com.intellij.openapi.util.TextRange(
                doc.getLineStartOffset(i), doc.getLineEndOffset(i))));
        }
        return AnchorResolver.resolve(lines, recorded, ts.anchorText(), AnchorResolver.DEFAULT_K)
            .kind() == AnchorResolver.Kind.STALE;
    }

    private void onRowClicked(AnnotationEntry entry) {
        String[] parts = entry.anchor().split(":", 3);
        if (parts.length < 3) return;
        String path = parts[0];
        String side = parts[1];
        int line;
        try {
            String lineStr = parts[2].split("-", 2)[0];
            line = Integer.parseInt(lineStr);
        } catch (NumberFormatException e) {
            return;
        }
        int line0 = Math.max(0, line - 1);

        seenVersions.put(entry.anchor(), entry.version());
        rebuild();

        // Preferred: navigate to the original PR diff viewer where this
        // annotation was created. That keeps the user inside the review
        // context (before/after panes) instead of dropping them into the
        // working copy.
        com.intellij.openapi.editor.ex.EditorEx diffEditor =
            SpikeDiffExtension.editorFor(path + ":" + side);
        if (diffEditor != null) {
            // Scroll the diff editor to the line and focus its window.
            diffEditor.getCaretModel().moveToLogicalPosition(
                new com.intellij.openapi.editor.LogicalPosition(line0, 0));
            diffEditor.getScrollingModel().scrollToCaret(
                com.intellij.openapi.editor.ScrollType.CENTER);
            java.awt.Window window = SwingUtilities.getWindowAncestor(
                diffEditor.getContentComponent());
            if (window != null) {
                window.toFront();
                window.requestFocus();
            }
            diffEditor.getContentComponent().requestFocusInWindow();
            SynthesisPopup.show(project, diffEditor, entry.anchor(), line0);
            return;
        }

        // Fallback: no diff viewer is open for this file. Instead of dropping the
        // user into the working-copy source (no PR context), drive the real
        // GitHub PR diff to this file + line. When that diff renders, its child
        // viewer registers with SpikeDiffExtension, so a second click takes the
        // fast path above and shows the ask-Claude popup in PR context.
        client.currentSession().ifPresent(s ->
            GhPrDiffOpener.openAt(project, s, path, side, line0));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
