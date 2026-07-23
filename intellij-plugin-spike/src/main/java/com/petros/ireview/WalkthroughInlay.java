package com.petros.ireview;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.event.VisibleAreaEvent;
import com.intellij.openapi.editor.event.VisibleAreaListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Locale;

/**
 * Mode B renderer: one block inlay under the active step's anchor line, holding
 * the step's explanation and its latest Claude reply.
 *
 * Exactly one inlay exists at a time — {@link #refresh()} disposes the previous
 * one before creating the next, so stepping never leaves cards behind.
 *
 * <p>The card is a genuine embedded Swing component, not hand-painted text.
 * IntelliJ's {@link EditorEmbeddedComponentManager} — the same mechanism the
 * platform uses to host notebook-cell output and inline AI prompts inside a
 * text editor — hosts a real {@link WalkthroughCard} ({@code JComponent}) as
 * a block element. That gives us real HTML (via {@link JEditorPane}) for
 * markdown, instead of {@code Graphics2D.drawString} on the literal
 * characters.
 */
public final class WalkthroughInlay {

    private static final Logger LOG = Logger.getInstance(WalkthroughInlay.class);

    private final Project project;
    private final WalkthroughController controller;
    private final WalkthroughSessionClient client;
    private final WalkthroughHud hud;
    private Inlay<?> currentInlay;
    private boolean attached;
    private MessageBusConnection editorConnection;

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

    // The HUD is now anchored to whichever editor is selected (see
    // WalkthroughHud), so switching tabs — not just step/doc/thread
    // activity — has to trigger a refresh: otherwise the bar would stay
    // parented to an editor the user has already navigated away from, or
    // fail to reappear when they navigate back to the step's file.
    private final FileEditorManagerListener editorListener = new FileEditorManagerListener() {
        @Override public void selectionChanged(FileEditorManagerEvent event) { invokeRefresh(); }
    };

    private void invokeRefresh() {
        ApplicationManager.getApplication().invokeLater(this::refresh);
    }

    public WalkthroughInlay(Project project, WalkthroughController controller, WalkthroughSessionClient client) {
        this.project = project;
        this.controller = controller;
        this.client = client;
        this.hud = new WalkthroughHud(controller);
    }

    public void attach() {
        if (attached) return;
        attached = true;
        controller.addListener(controllerListener);
        client.addListener(clientListener);
        editorConnection = project.getMessageBus().connect();
        editorConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, editorListener);
        refresh();
    }

    public void detach() {
        if (!attached) return;
        attached = false;
        controller.removeListener(controllerListener);
        client.removeListener(clientListener);
        editorConnection.disconnect();
        editorConnection = null;
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
        // No editor at all — nothing to anchor the bar to.
        if (editor == null) { hud.hide(); return; }
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (!WalkthroughNavigator.isStepFile(project, vf, step)) {
            // The editor is showing a different file — don't linger over it;
            // hide until the controller's navigate (or the user) brings the
            // right file back, which re-triggers refresh() via onStepActivated
            // or the editor-selection listener.
            hud.hide();
            return;
        }
        if (!(editor instanceof EditorEx editorEx)) {
            // Every text editor FileEditorManager hands back is an EditorEx in
            // practice (EditorImpl implements it) — this is the platform
            // interface EditorEmbeddedComponentManager requires. Guarded
            // rather than assumed so an unexpected editor kind degrades to
            // "no card" instead of a ClassCastException.
            LOG.warn("WalkthroughInlay: selected editor is not an EditorEx (" + editor.getClass()
                + "); cannot host an embedded card for step \"" + step.title() + "\"");
            hud.hide();
            return;
        }
        List<String> lines = List.of(editor.getDocument().getText().split("\n", -1));
        AnchorResolver.Resolution res = WalkthroughNavigator.resolveLine(lines, step);
        int line = res.kind() == AnchorResolver.Kind.STALE ? step.line() : res.line();
        int lineIndex = Math.min(Math.max(0, line - 1), editor.getDocument().getLineCount() - 1);
        int offset = editor.getDocument().getLineEndOffset(lineIndex);
        String anchorLineText = lineIndex < lines.size() ? lines.get(lineIndex) : "";

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

        WalkthroughCard card = new WalkthroughCard(
            project, editor, step.title(), meta, body, step.role(), anchorLineText);

        currentInlay = EditorEmbeddedComponentManager.getInstance().addComponent(
            editorEx,
            card,
            new EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(),
                null,   // no custom gutter icon renderer
                true,   // relatesToPrecedingText
                false,  // showAbove — render below the anchor line, like the old renderer
                false,  // showWhenFolded
                false,  // fullWidth — the card sizes itself, it doesn't stretch edge to edge
                0,
                offset));

        // The card is sized once, from the editor's width at construction time
        // (see WalkthroughCard). Rather than trying to live-rewrap the same
        // JEditorPane in place on every pixel of a drag-resize, a changed
        // available width just triggers a full refresh() — disposing this
        // inlay/card and building a new one sized for the new width. That
        // keeps "exactly one card, refresh() replaces it" a single code path
        // for every kind of change (step, doc, thread, resize) instead of two.
        // Scoped as a child of the inlay (itself Disposable) — disposeInlay()
        // on the next refresh() tears this listener down too, no separate
        // bookkeeping needed.
        Inlay<?> inlayForListener = currentInlay;
        Editor editorForListener = editor;
        int[] lastWidth = { WalkthroughCard.availableWidth(editorForListener) };
        editor.getScrollingModel().addVisibleAreaListener(new VisibleAreaListener() {
            @Override public void visibleAreaChanged(VisibleAreaEvent e) {
                int w = WalkthroughCard.availableWidth(editorForListener);
                if (w != lastWidth[0]) {
                    lastWidth[0] = w;
                    refresh();
                }
            }
        }, inlayForListener);

        hud.show(editor, step, c.index(), c.size());
    }

    private static String roleLabel(WalkthroughStep.Role role) {
        String name = role.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private void disposeInlay() {
        if (currentInlay != null) {
            Disposer.dispose(currentInlay);
            currentInlay = null;
        }
    }

    /**
     * The inline card's real content component: a rounded, bordered, drop-
     * shadowed panel with a hand-painted header (title + meta — plain text,
     * no markdown, so it's cheaper and more predictable to lay out by hand)
     * and a real {@link JEditorPane} body rendering the step's markdown as
     * genuine HTML — bold, inline {@code code} chips and links all work,
     * unlike the old renderer's raw {@code drawString}.
     *
     * <p>Geometry is decided once, at construction, from the editor's current
     * visible width and the anchor line's indentation — see the constructor.
     * {@link #getPreferredSize()} reports exactly the bounds
     * {@link #paintComponent} draws (both read the same {@code cardX/Y/Width/
     * Height} fields), so the height {@link EditorEmbeddedComponentManager}
     * reserves for this component always matches what's actually on screen.
     * Re-wrapping on editor resize is handled by {@link WalkthroughInlay},
     * which rebuilds a fresh card (and thus a fresh layout) instead of this
     * class trying to live-reflow itself.
     */
    private static final class WalkthroughCard extends JPanel {
        private static final int ARC = JBUI.scale(8);
        private static final int EDGE_W = JBUI.scale(3);
        private static final int PAD_X = JBUI.scale(14);
        private static final int PAD_TOP = JBUI.scale(12);
        private static final int PAD_BOTTOM = JBUI.scale(12);
        private static final int HEADER_GAP = JBUI.scale(6);
        private static final int TITLE_META_GAP = JBUI.scale(10);
        private static final int SHADOW_SPAN = JBUI.scale(5);
        private static final int SHADOW_DROP = JBUI.scale(2);
        private static final int MAX_INDENT = JBUI.scale(240);
        private static final int MIN_INNER_WIDTH = JBUI.scale(220);
        private static final int RIGHT_MARGIN = JBUI.scale(16);
        // "roughly 70-80 characters of body text" — measured in the body font.
        private static final int MAX_BODY_CHARS = 78;

        private final JEditorPane bodyPane;
        private final String title;
        private final String meta;
        private final Color roleColor;
        private final Color cardBg;
        private final Color cardBorder;
        private final Color titleColor;
        private final Color metaColor;
        private final JBFont titleFont;
        private final JBFont metaFont;

        private final int innerWidth;
        private final int headerHeight;
        private final int bodyHeight;
        private final int cardX;
        private final int cardY;
        private final int cardWidth;
        private final int cardHeight;

        WalkthroughCard(Project project, Editor editor, String title, String meta, String bodyMarkdown,
                         WalkthroughStep.Role role, String anchorLineText) {
            setLayout(null);
            setOpaque(false);
            setFocusable(false);

            this.title = title;
            this.meta = meta;
            this.roleColor = roleColor(role);
            this.titleColor = UIUtil.getLabelForeground();
            // getContextHelpForeground() reads as "secondary but legible" —
            // the platform's own answer to the old renderer's "meta is nearly
            // invisible" problem (WalkthroughHud made the same fix for its pill).
            this.metaColor = UIUtil.getContextHelpForeground();

            // --- Type scale: derived from the *editor's* font, not the UI's ---
            EditorColorsScheme scheme = editor.getColorsScheme();
            int editorSize = Math.max(9, scheme.getEditorFontSize());
            JBFont bodyFont = JBUI.Fonts.create(Font.SANS_SERIF, editorSize).asPlain();
            this.titleFont = bodyFont.asBold().biggerOn(1f);
            this.metaFont = bodyFont.lessOn(2f);

            // Card fill: a subtle tint of the editor's own background, not a
            // fixed literal — stays legible (and distinct from the editor
            // behind it) in both light and dark themes.
            this.cardBg = mix(scheme.getDefaultBackground(), UIUtil.getLabelForeground(), 0.045f);
            this.cardBorder = JBColor.border();

            // --- Geometry: indent to the anchor line's text column, cap width ---
            Component metrics = editor.getContentComponent();
            Font codeFont = new Font(scheme.getEditorFontName(), Font.PLAIN, scheme.getEditorFontSize());
            int spaceWidth = Math.max(1, metrics.getFontMetrics(codeFont).charWidth(' '));
            int column = leadingColumn(anchorLineText, editor, project);

            int viewport = availableWidth(editor);
            FontMetrics bodyFm = metrics.getFontMetrics(bodyFont);
            int measuredWidth = bodyFm.stringWidth("n".repeat(MAX_BODY_CHARS));
            int chromeWidth = EDGE_W + PAD_X * 2;

            int rawIndent = Math.min(column * spaceWidth, MAX_INDENT);
            int floor = chromeWidth + MIN_INNER_WIDTH + SHADOW_SPAN * 2 + RIGHT_MARGIN;
            int indent = Math.max(0, Math.min(rawIndent, viewport - floor));

            int maxCardWidth = Math.max(chromeWidth + MIN_INNER_WIDTH,
                viewport - RIGHT_MARGIN - indent - SHADOW_SPAN * 2);
            int desiredCardWidth = chromeWidth + measuredWidth;
            int resolvedCardWidth = Math.min(desiredCardWidth, maxCardWidth);
            this.innerWidth = Math.max(MIN_INNER_WIDTH, resolvedCardWidth - chromeWidth);

            // --- Body: real HTML, reflowed once at innerWidth ---
            this.bodyPane = buildMarkdownPane(project, bodyMarkdown, bodyFont);
            add(bodyPane);
            bodyPane.setSize(new Dimension(innerWidth, Short.MAX_VALUE));
            this.bodyHeight = Math.max(1, bodyPane.getPreferredSize().height);

            FontMetrics titleFm = metrics.getFontMetrics(titleFont);
            FontMetrics metaFm = metrics.getFontMetrics(metaFont);
            this.headerHeight = Math.max(titleFm.getHeight(), metaFm.getHeight());

            this.cardX = indent + SHADOW_SPAN;
            this.cardY = SHADOW_SPAN;
            this.cardWidth = chromeWidth + innerWidth;
            this.cardHeight = PAD_TOP + headerHeight + HEADER_GAP + bodyHeight + PAD_BOTTOM;
        }

        /** The width the card is allowed to grow into: the editor's current visible width. */
        static int availableWidth(Editor editor) {
            int w = editor.getScrollingModel().getVisibleArea().width;
            if (w <= 0) w = editor.getContentComponent().getWidth();
            if (w <= 0) w = JBUI.scale(640); // not laid out yet — re-measured on the next resize
            return Math.max(JBUI.scale(160), w);
        }

        @Override public Dimension getPreferredSize() {
            return new Dimension(
                cardX + cardWidth + SHADOW_SPAN,
                cardY + cardHeight + SHADOW_SPAN + SHADOW_DROP);
        }

        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override public void doLayout() {
            int bodyX = cardX + EDGE_W + PAD_X;
            int bodyY = cardY + PAD_TOP + headerHeight + HEADER_GAP;
            bodyPane.setBounds(bodyX, bodyY, innerWidth, bodyHeight);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                // Soft drop shadow — a handful of decreasing-alpha rounded
                // rects offset slightly down, same hand-rolled technique
                // WalkthroughHud's pill uses (the platform has no blur
                // primitive). Alpha derives from JBColor.black, not a magic
                // literal, so it still reads correctly against either theme.
                Color shadowBase = JBColor.black;
                for (int i = SHADOW_SPAN; i >= 1; i--) {
                    int alpha = Math.max(4, 40 * (SHADOW_SPAN - i + 1) / SHADOW_SPAN);
                    g.setColor(new Color(shadowBase.getRed(), shadowBase.getGreen(), shadowBase.getBlue(), alpha));
                    g.fillRoundRect(cardX - i / 2, cardY - i / 2 + SHADOW_DROP,
                        cardWidth + i, cardHeight + i, ARC + i, ARC + i);
                }

                RoundRectangle2D.Float cardShape = new RoundRectangle2D.Float(
                    cardX, cardY, Math.max(1, cardWidth - 1), Math.max(1, cardHeight - 1), ARC, ARC);

                Shape oldClip = g.getClip();
                g.setClip(cardShape);
                g.setColor(cardBg);
                g.fill(cardShape);
                g.setColor(roleColor);
                g.fillRect(cardX, cardY, EDGE_W, cardHeight);
                g.setClip(oldClip);
                g.setColor(cardBorder);
                g.draw(cardShape);

                int textX = cardX + EDGE_W + PAD_X;
                int rightEdge = cardX + cardWidth - PAD_X;
                int headerY = cardY + PAD_TOP;

                g.setFont(metaFont);
                FontMetrics metaFm = g.getFontMetrics();
                int metaWidth = metaFm.stringWidth(meta);
                g.setColor(metaColor);
                g.drawString(meta, rightEdge - metaWidth, headerY + metaFm.getAscent());

                g.setFont(titleFont);
                FontMetrics titleFm = g.getFontMetrics();
                int titleBudget = Math.max(JBUI.scale(20), rightEdge - textX - metaWidth - TITLE_META_GAP);
                String fittedTitle = truncateEnd(title, titleFm, titleBudget);
                g.setColor(titleColor);
                g.drawString(fittedTitle, textX, headerY + titleFm.getAscent());
            } finally {
                g.dispose();
            }
        }

        /**
         * Renders {@code markdown} as real HTML via the same conversion
         * {@link WalkthroughPanel} uses ({@link MarkdownLinkRenderer#toHtml})
         * and routes link clicks through the same
         * {@link SynthesisLinkRouter#route}, so bold/inline-code/links behave
         * identically to the rail — reusing the panel's public APIs rather
         * than a second markdown implementation. Not focusable: the card must
         * not steal focus or intercept editor keystrokes, and hyperlink
         * clicks are delivered to {@link JEditorPane} regardless of focus.
         */
        private static JEditorPane buildMarkdownPane(Project project, String markdown, Font font) {
            JEditorPane pane = new JEditorPane();
            pane.setEditable(false);
            pane.setFocusable(false);
            pane.setOpaque(false);
            pane.setContentType("text/html");

            HTMLEditorKit kit = (HTMLEditorKit) pane.getEditorKit();
            StyleSheet styles = new StyleSheet();
            styles.addStyleSheet(kit.getStyleSheet());
            Color bodyColor = mix(UIUtil.getLabelForeground(), UIUtil.getLabelDisabledForeground(), 0.25f);
            styles.addRule("body { font-family: '" + font.getFamily() + "'; font-size: " + font.getSize()
                + "pt; color: " + toHex(bodyColor) + "; line-height: 155%; }"
                + " p { margin-top: 0; margin-bottom: " + JBUI.scale(6) + "px; }"
                + " code, pre { font-family: monospace; }"
                + " a { color: " + toHex(JBColor.BLUE) + "; text-decoration: none; }");
            kit.setStyleSheet(styles);
            pane.setText(MarkdownLinkRenderer.toHtml(markdown));

            pane.addHyperlinkListener(e -> {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                    SynthesisLinkRouter.route(project, e.getDescription());
                }
            });
            return pane;
        }

        /**
         * Leading-whitespace column of {@code lineText} (tabs expanded per the
         * project's editor settings) — how far into the line its text starts,
         * so the card can indent to roughly that column instead of the gutter
         * edge.
         */
        private static int leadingColumn(String lineText, Editor editor, Project project) {
            if (lineText == null || lineText.isEmpty()) return 0;
            int tabSize = editor.getSettings().getTabSize(project);
            if (tabSize <= 0) tabSize = 4;
            int col = 0;
            for (int i = 0; i < lineText.length(); i++) {
                char ch = lineText.charAt(i);
                if (ch == ' ') col++;
                else if (ch == '\t') col += tabSize - (col % tabSize);
                else break;
            }
            return col;
        }

        private static Color roleColor(WalkthroughStep.Role role) {
            return switch (role) {
                case SEAM -> JBColor.BLUE;
                case EDIT_SITE -> JBColor.GREEN;
                case CONTEXT -> JBColor.GRAY;
            };
        }

        private static Color mix(Color a, Color b, float t) {
            int r = Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
            int g = Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
            int bl = Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
            return new Color(r, g, bl);
        }

        private static String toHex(Color c) {
            return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
        }

        /** Right-truncating ellipsis fit — the header title reads left to right. */
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
