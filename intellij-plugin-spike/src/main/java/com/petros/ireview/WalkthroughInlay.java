package com.petros.ireview;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayProperties;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.event.VisibleAreaEvent;
import com.intellij.openapi.editor.event.VisibleAreaListener;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mode B renderer: one block inlay under the active step's anchor line, holding
 * the step's explanation and its latest Claude reply.
 *
 * Exactly one inlay exists at a time — {@link #refresh()} disposes the previous
 * one before creating the next, so stepping never leaves cards behind.
 */
public final class WalkthroughInlay {

    private final Project project;
    private final WalkthroughController controller;
    private final WalkthroughSessionClient client;
    private final WalkthroughHud hud;
    private Inlay<?> currentInlay;
    private boolean attached;

    // Controller callbacks may arrive off the EDT; refresh() touches the editor's
    // inlay model, so bridge here the same way WalkthroughService/WalkthroughPanel do.
    private final WalkthroughController.Listener controllerListener = new WalkthroughController.Listener() {
        @Override public void onStepActivated(WalkthroughStep step, int index, int total) { invokeRefresh(); }
        @Override public void onDocChanged(WalkthroughDoc doc) { invokeRefresh(); }
    };

    // Thread/pending updates arrive out of band from the client (e.g. an answer
    // landing while this step's card is showing) and must refresh the card the
    // same way a step change does — otherwise INLINE mode never shows the
    // "waiting for Claude…" line or the eventual answer without navigating away
    // and back.
    private final WalkthroughSessionClient.Listener clientListener = new WalkthroughSessionClient.Listener() {
        @Override public void onThreadChanged(String anchor, WalkthroughSessionClient.ThreadState thread) { invokeRefresh(); }
        @Override public void onPendingChanged(String anchor, boolean pending) { invokeRefresh(); }
        @Override public void onDetached() { invokeRefresh(); }
    };

    private void invokeRefresh() {
        ApplicationManager.getApplication().invokeLater(this::refresh);
    }

    public WalkthroughInlay(Project project, WalkthroughController controller, WalkthroughSessionClient client) {
        this.project = project;
        this.controller = controller;
        this.client = client;
        this.hud = new WalkthroughHud(project, controller);
    }

    public void attach() {
        if (attached) return;
        attached = true;
        controller.addListener(controllerListener);
        client.addListener(clientListener);
        refresh();
    }

    public void detach() {
        if (!attached) return;
        attached = false;
        controller.removeListener(controllerListener);
        client.removeListener(clientListener);
        disposeInlay();
        hud.hide();
    }

    /** Rebuild the inlay at the current step's re-resolved anchor. */
    public void refresh() {
        disposeInlay();
        if (!attached) return;
        WalkthroughController c = controller;
        var maybeStep = c.current();
        if (maybeStep.isEmpty()) { hud.hide(); return; }
        WalkthroughStep step = maybeStep.get();

        Editor editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .getSelectedTextEditor();
        if (editor == null) { hud.show(step, c.index(), c.size()); return; }
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (!WalkthroughNavigator.isStepFile(project, vf, step)) {
            // The editor is showing a different file — the controller's navigate
            // will land here again once the right file is open.
            hud.show(step, c.index(), c.size());
            return;
        }
        List<String> lines = List.of(editor.getDocument().getText().split("\n", -1));
        AnchorResolver.Resolution res = WalkthroughNavigator.resolveLine(lines, step);
        int line = res.kind() == AnchorResolver.Kind.STALE ? step.line() : res.line();
        int offset = editor.getDocument().getLineEndOffset(
            Math.min(Math.max(0, line - 1), editor.getDocument().getLineCount() - 1));

        String body = step.markdown();
        var thread = client.threadFor(step.anchor());
        if (thread.isPresent()) {
            body = body + "\n\nYou · " + thread.get().question() + "\n" + thread.get().synthesis();
        } else if (client.isPending(step.anchor())) {
            body = body + "\n\n● waiting for Claude…";
        }
        boolean stale = res.kind() == AnchorResolver.Kind.STALE;
        String meta = "step " + (c.index() + 1) + " of " + c.size() + " · " + roleLabel(step.role())
            + (stale ? " · code changed here" : "");

        currentInlay = editor.getInlayModel().addBlockElement(
            offset, new InlayProperties().showAbove(false).relatesToPrecedingText(true),
            new CardRenderer(step.title(), meta, body, step.role()));

        // Block elements are sized by calcWidthInPixels()/calcHeightInPixels(),
        // which the renderer computes fresh from the editor's *current* visible
        // width every time — but nothing re-invokes those unless told to. A
        // VisibleAreaListener scoped to the inlay (Inlay is itself Disposable,
        // so this is auto-removed the moment disposeInlay() tears the inlay
        // down on the next refresh — no separate bookkeeping needed) calls
        // Inlay#update() on resize so the card actually re-wraps and repaints
        // instead of quietly holding stale wrapped lines.
        Inlay<?> inlayForListener = currentInlay;
        int[] lastWidth = { CardRenderer.availableWidth(inlayForListener) };
        editor.getScrollingModel().addVisibleAreaListener(new VisibleAreaListener() {
            @Override public void visibleAreaChanged(VisibleAreaEvent e) {
                int w = CardRenderer.availableWidth(inlayForListener);
                if (w != lastWidth[0]) {
                    lastWidth[0] = w;
                    inlayForListener.update();
                }
            }
        }, inlayForListener);

        hud.show(step, c.index(), c.size());
    }

    private static String roleLabel(WalkthroughStep.Role role) {
        String name = role.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private void disposeInlay() {
        if (currentInlay != null) {
            com.intellij.openapi.util.Disposer.dispose(currentInlay);
            currentInlay = null;
        }
    }

    /**
     * Rounded card, prose in the UI font (not the editor's monospace — this is
     * explanation text, not code). One {@link #layout} helper produces the
     * wrapped header/body lines and the total height from them, and both
     * {@link #calcHeightInPixels} and {@link #paint} call it — so the height
     * reserved for the inlay always matches exactly what gets drawn, even as
     * the editor is resized and the wrap width changes.
     */
    private static final class CardRenderer implements EditorCustomElementRenderer {
        private static final JBFont TITLE_FONT = JBUI.Fonts.label().asBold();
        private static final JBFont META_FONT = JBUI.Fonts.label().lessOn(1.5f);
        private static final JBFont BODY_FONT = JBUI.Fonts.label();

        private static final Color TITLE_COLOR = UIUtil.getLabelForeground();
        private static final Color META_COLOR = UIUtil.getLabelDisabledForeground();
        private static final Color BODY_COLOR =
            mix(UIUtil.getLabelForeground(), UIUtil.getLabelDisabledForeground(), 0.25f);

        // Card chrome — paired light/dark tones, same convention as
        // WalkthroughPanel's role palette below.
        private static final JBColor CARD_BG = new JBColor(new Color(0xF7, 0xF8, 0xFA), new Color(0x2B, 0x2D, 0x30));
        private static final JBColor CARD_BORDER = new JBColor(new Color(0xD8, 0xDA, 0xDE), new Color(0x45, 0x47, 0x4A));
        // Same role hues WalkthroughPanel uses for its discs, so the rail and
        // the inline card agree on what "SEAM"/"EDIT_SITE" look like.
        private static final JBColor SEAM_COLOR = new JBColor(new Color(0x35, 0x74, 0xF0), new Color(0x54, 0x8A, 0xF7));
        private static final JBColor EDIT_SITE_COLOR = new JBColor(new Color(0x1F, 0x9C, 0x5B), new Color(0x4C, 0xBB, 0x79));

        private static final int PAD_X = JBUI.scale(14);
        private static final int PAD_TOP = JBUI.scale(8);
        private static final int PAD_BOTTOM = JBUI.scale(8);
        private static final int HEADER_GAP = JBUI.scale(6);
        private static final int LINE_GAP = JBUI.scale(3);
        private static final int ARC = JBUI.scale(8);
        private static final int EDGE_W = JBUI.scale(3);

        private final String title;
        private final String meta;
        private final String body;
        private final WalkthroughStep.Role role;

        CardRenderer(String title, String meta, String body, WalkthroughStep.Role role) {
            this.title = title;
            this.meta = meta;
            this.body = body;
            this.role = role;
        }

        /** The width the card wraps to: the editor's current visible width, minus its own insets and gutter. */
        static int availableWidth(Inlay<?> inlay) {
            Editor e = inlay.getEditor();
            int w = e.getScrollingModel().getVisibleArea().width;
            if (w <= 0) w = e.getContentComponent().getWidth();
            if (w <= 0) w = JBUI.scale(640); // not laid out yet — a sane placeholder, re-wrapped on the next resize
            return Math.max(JBUI.scale(160), w - JBUI.scale(8));
        }

        private record Layout(String title, String meta, List<String> bodyLines,
                               int headerHeight, int lineHeight, int totalHeight) {}

        private Layout layout(Inlay<?> inlay) {
            Component c = inlay.getEditor().getContentComponent();
            FontMetrics titleFm = c.getFontMetrics(TITLE_FONT);
            FontMetrics metaFm = c.getFontMetrics(META_FONT);
            FontMetrics bodyFm = c.getFontMetrics(BODY_FONT);

            int width = availableWidth(inlay);
            int innerWidth = Math.max(JBUI.scale(60), width - EDGE_W - PAD_X * 2);

            int metaWidth = metaFm.stringWidth(meta);
            int titleBudget = Math.max(JBUI.scale(20), innerWidth - metaWidth - JBUI.scale(12));
            String fittedTitle = truncateEnd(title, titleFm, titleBudget);

            List<String> bodyLines = wrap(body, bodyFm, innerWidth);

            int headerHeight = Math.max(titleFm.getHeight(), metaFm.getHeight());
            int lineHeight = bodyFm.getHeight() + LINE_GAP;
            int totalHeight = PAD_TOP + headerHeight + HEADER_GAP
                + bodyLines.size() * lineHeight + PAD_BOTTOM;

            return new Layout(fittedTitle, meta, bodyLines, headerHeight, lineHeight, totalHeight);
        }

        @Override public int calcWidthInPixels(Inlay inlay) {
            return availableWidth(inlay);
        }

        @Override public int calcHeightInPixels(Inlay inlay) {
            return layout(inlay).totalHeight();
        }

        @Override public void paint(Inlay inlay, Graphics g, Rectangle target, TextAttributes attributes) {
            Layout l = layout(inlay);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                RoundRectangle2D.Float cardShape = new RoundRectangle2D.Float(
                    target.x, target.y, Math.max(1, target.width - 1), Math.max(1, target.height - 1), ARC, ARC);

                Shape oldClip = g2.getClip();
                g2.setClip(cardShape);
                g2.setColor(CARD_BG);
                g2.fill(cardShape);
                g2.setColor(roleColor(role));
                g2.fillRect(target.x, target.y, EDGE_W, target.height);
                g2.setClip(oldClip);
                g2.setColor(CARD_BORDER);
                g2.draw(cardShape);

                int x = target.x + EDGE_W + PAD_X;
                int rightEdge = target.x + target.width - PAD_X;
                int y = target.y + PAD_TOP;

                g2.setFont(TITLE_FONT);
                FontMetrics titleFm = g2.getFontMetrics();
                g2.setColor(TITLE_COLOR);
                g2.drawString(l.title(), x, y + titleFm.getAscent());

                g2.setFont(META_FONT);
                FontMetrics metaFm = g2.getFontMetrics();
                g2.setColor(META_COLOR);
                int metaWidth = metaFm.stringWidth(l.meta());
                g2.drawString(l.meta(), rightEdge - metaWidth, y + metaFm.getAscent());

                y += l.headerHeight() + HEADER_GAP;

                g2.setFont(BODY_FONT);
                FontMetrics bodyFm = g2.getFontMetrics();
                g2.setColor(BODY_COLOR);
                for (String line : l.bodyLines()) {
                    g2.drawString(line, x, y + bodyFm.getAscent());
                    y += l.lineHeight();
                }
            } finally {
                g2.dispose();
            }
        }

        private static Color roleColor(WalkthroughStep.Role role) {
            return switch (role) {
                case SEAM -> SEAM_COLOR;
                case EDIT_SITE -> EDIT_SITE_COLOR;
                case CONTEXT -> UIUtil.getLabelDisabledForeground();
            };
        }

        private static Color mix(Color a, Color b, float t) {
            int r = Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
            int g = Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
            int bl = Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
            return new Color(r, g, bl);
        }

        /** Word-wraps {@code text} to {@code maxWidth}, treating existing "\n" as paragraph breaks. */
        private static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
            List<String> out = new ArrayList<>();
            if (maxWidth <= 0) { out.add(text); return out; }
            for (String paragraph : text.split("\n", -1)) {
                if (paragraph.isEmpty()) { out.add(""); continue; }
                String[] words = paragraph.split(" ");
                StringBuilder line = new StringBuilder();
                for (String word : words) {
                    String candidate = line.length() == 0 ? word : line + " " + word;
                    if (fm.stringWidth(candidate) <= maxWidth || line.length() == 0) {
                        line = new StringBuilder(candidate);
                    } else {
                        out.add(line.toString());
                        line = new StringBuilder(word);
                    }
                }
                out.add(line.toString());
            }
            return out;
        }

        /** Right-truncating ellipsis fit — the header title reads left to right, so unlike the rail's
         *  chip (whose tail identifies it) this keeps the beginning and drops the end. */
        private static String truncateEnd(String s, FontMetrics fm, int width) {
            if (s.isEmpty() || width <= 0 || fm.stringWidth(s) <= width) return s;
            String ellipsis = "…";
            int lo = 0, hi = s.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                String candidate = s.substring(0, mid) + ellipsis;
                if (fm.stringWidth(candidate) <= width) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo == 0 ? ellipsis : s.substring(0, lo) + ellipsis;
        }
    }
}
