package com.petros.ireview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mode A renderer: the whole step list, with the active step expanded to show
 * its explanation, its Q&amp;A thread and an ask box.
 *
 * Subscribes to the controller only while {@link WalkthroughController#mode()}
 * is RAIL; in INLINE mode it renders a one-line hint instead so the two
 * renderers are never both drawing.
 *
 * <p>Visual hierarchy ("V2 — Airy"): a coloured numbered disc carries role
 * colour so titles stay plain text; title outranks explanation which
 * outranks the symbol chip — the enclosing {@code Class.method()} from
 * {@link WalkthroughSymbols}, monospaced at disabled-text weight and
 * truncated from the left when it doesn't fit its own small box (the tail —
 * the method name — is what identifies it). The full project-relative
 * path plus line is still one hover away, as the chip's tooltip. All type
 * and colour is derived from the platform ({@link UIUtil}, {@link JBUI},
 * {@link JBColor}) so the panel follows the IDE theme instead of
 * hard-coding a look.
 */
public final class WalkthroughPanel implements Disposable {

    // --- Theme-derived type scale --------------------------------------
    // Every size below is the platform's own label font, nudged by a
    // relative offset (JBFont#biggerOn / #lessOn) rather than a hard-coded
    // point size, so it tracks the user's font-size and HiDPI settings.
    private static final JBFont LABEL_FONT = JBUI.Fonts.label();
    /** Title outranks everything: bold, ~1pt larger than the default label. */
    private static final Font TITLE_FONT = LABEL_FONT.asBold().biggerOn(1f);
    /** Explanation prose: the default label size, just not bold. */
    private static final Font EXPLANATION_FONT = LABEL_FONT.asPlain();
    /** Path: monospaced, ~2pt smaller — recedes behind title and prose. */
    private static final Font PATH_FONT = JBUI.Fonts.create(Font.MONOSPACED, LABEL_FONT.getSize()).lessOn(2f);
    /** Header strip (question + progress counter): small and quiet. */
    private static final Font HEADER_FONT = LABEL_FONT.lessOn(1f);

    private static final int ROW_V = JBUI.scale(11);
    private static final int ROW_H = JBUI.scale(14);
    private static final int ACCENT_W = JBUI.scale(3);

    // Role accents live only on the disc (see RoleDisc) — never on text.
    // JBColor pairs, not bare java.awt.Color, so both themes stay legible.
    private static final JBColor SEAM_COLOR = new JBColor(new Color(0x35, 0x74, 0xF0), new Color(0x54, 0x8A, 0xF7));
    private static final JBColor EDIT_SITE_COLOR = new JBColor(new Color(0x1F, 0x9C, 0x5B), new Color(0x4C, 0xBB, 0x79));
    private static final JBColor STALE_COLOR = new JBColor(new Color(0xB0, 0x90, 0x10), new Color(0xF1, 0xC4, 0x0F));

    private final Project project;
    private final WalkthroughService service;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel header = new JPanel(new BorderLayout(JBUI.scale(10), 0));
    private final ClampedLabel questionLabel = new ClampedLabel(2, HEADER_FONT, UIUtil.getLabelDisabledForeground());
    private final JBLabel progressLabel = new JBLabel();
    private final JPanel steps = new StepsPanel();
    private final JBTextField ask = new JBTextField();
    private final JBLabel status = new JBLabel(" ");
    private final JButton back = new JButton("◀ Back");
    private final JButton next = new JButton("Next ▶");

    private final WalkthroughController.Listener controllerListener =
        new WalkthroughController.Listener() {
            @Override public void onDocChanged(WalkthroughDoc doc) { invokeRebuild(); }
            @Override public void onStepActivated(WalkthroughStep step, int i, int total) { invokeRebuild(); }
            @Override public void onModeChanged(WalkthroughController.Mode mode) { invokeRebuild(); }
        };

    private final WalkthroughSessionClient.Listener clientListener =
        new WalkthroughSessionClient.Listener() {
            @Override public void onThreadChanged(String anchor, WalkthroughSessionClient.ThreadState t) { invokeRebuild(); }
            @Override public void onPendingChanged(String anchor, boolean pending) { invokeRebuild(); }
            @Override public void onStateChanged(WalkthroughSessionClient.State s) { invokeRebuild(); }
            @Override public void onDetached() { invokeRebuild(); }
        };

    public WalkthroughPanel(Project project) {
        this.project = project;
        this.service = WalkthroughService.get(project);

        steps.setLayout(new BoxLayout(steps, BoxLayout.Y_AXIS));
        steps.setBorder(JBUI.Borders.empty(4));

        header.setBorder(JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(8, ROW_H, 8, ROW_H)));
        progressLabel.setFont(HEADER_FONT);
        progressLabel.setForeground(UIUtil.getLabelDisabledForeground());
        progressLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        header.add(questionLabel, BorderLayout.CENTER);
        header.add(progressLabel, BorderLayout.EAST);

        ask.setFont(EXPLANATION_FONT);
        back.setFont(EXPLANATION_FONT);
        next.setFont(EXPLANATION_FONT);
        status.setFont(PATH_FONT);
        status.setForeground(UIUtil.getLabelDisabledForeground());

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.setBorder(JBUI.Borders.empty(6));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(back);
        buttons.add(next);
        buttons.add(status);
        south.add(ask, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        root.add(header, BorderLayout.NORTH);
        root.add(new JBScrollPane(steps), BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        back.addActionListener(e -> service.controller().prev());
        next.addActionListener(e -> service.controller().next());
        ask.addActionListener(e -> submitAsk());

        service.controller().addListener(controllerListener);
        service.client().addListener(clientListener);
        rebuild();
    }

    public JComponent getComponent() { return root; }

    private void submitAsk() {
        String text = ask.getText().trim();
        if (text.isEmpty()) return;
        ask.setText("");
        service.askCurrentStep(text).exceptionally(err -> {
            SwingUtilities.invokeLater(() -> status.setText(err.getMessage()));
            return null;
        });
    }

    private void invokeRebuild() {
        SwingUtilities.invokeLater(this::rebuild);
    }

    private void rebuild() {
        steps.removeAll();
        WalkthroughController c = service.controller();

        if (c.mode() != WalkthroughController.Mode.RAIL) {
            header.setVisible(false);
            steps.add(new JBLabel("Walkthrough is in inline mode — steps render in the editor."));
            steps.add(Box.createVerticalGlue());
            finish(false);
            return;
        }
        WalkthroughDoc doc = c.doc();
        if (doc.isEmpty()) {
            header.setVisible(false);
            steps.add(new JBLabel("No walkthrough for this project. Run /walkthrough <question>."));
            steps.add(Box.createVerticalGlue());
            finish(false);
            return;
        }

        header.setVisible(true);
        // The header question comes from the walkthrough doc itself; if a
        // malformed/older doc never carried one, fall back to the session
        // title so the strip is never blank.
        String question = doc.question();
        if (question == null || question.isBlank()) {
            question = service.client().currentSession()
                .map(WalkthroughSessionClient.SessionInfo::title)
                .orElse("");
        }
        questionLabel.setFullText(question);
        progressLabel.setText((c.index() + 1) + " / " + doc.steps().size());

        for (int i = 0; i < doc.steps().size(); i++) {
            WalkthroughStep step = doc.steps().get(i);
            boolean active = i == c.index();
            steps.add(row(step, i, active, c.index()));
        }
        // BoxLayout(Y_AXIS) grows every child up to its maximum size when there's
        // spare vertical space; each row's max height is capped below to its
        // preferred height, and this trailing glue absorbs the leftover space
        // instead of the rows themselves, so the list stays compact and top-anchored.
        steps.add(Box.createVerticalGlue());
        status.setText(statusText());
        finish(true);
    }

    /**
     * One step row: disc + title + path, plus explanation and thread when
     * active. Colour lives only on the disc — see {@link RoleDisc} — so the
     * title reads as text, not as a link.
     */
    private JComponent row(WalkthroughStep step, int index, boolean active, int currentIndex) {
        // BorderLayout's maximumLayoutSize is unbounded, so a plain JPanel here
        // would stretch to fill any spare vertical space BoxLayout hands it.
        // Capping the height to the row's own preferred height (computed lazily,
        // since it depends on children added below) keeps rows compact.
        JPanel p = new JPanel(new BorderLayout(ROW_H - ACCENT_W, 0)) {
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        Color accent = roleColor(step.role());
        boolean visited = index < currentIndex;

        // Active row gets a 3px accent bar down the left edge; inactive rows
        // reserve the same 3px as empty space so nothing shifts horizontally
        // when a row becomes active.
        p.setBorder(JBUI.Borders.compound(
            active ? JBUI.Borders.customLine(accent, 0, ACCENT_W, 0, 0)
                   : JBUI.Borders.empty(0, ACCENT_W, 0, 0),
            JBUI.Borders.empty(ROW_V, ROW_H, ROW_V, ROW_H)));
        p.setOpaque(active);
        if (active) p.setBackground(JBUI.CurrentTheme.List.Selection.background(false));

        JBLabel disc = new JBLabel(new RoleDisc(accent, index + 1, roleFilled(step.role()), visited));
        disc.setVerticalAlignment(SwingConstants.TOP);
        p.add(disc, BorderLayout.WEST);

        JPanel textColumn = new JPanel();
        textColumn.setLayout(new BoxLayout(textColumn, BoxLayout.Y_AXIS));
        textColumn.setOpaque(false);

        JBLabel title = new JBLabel(step.title());
        title.setFont(TITLE_FONT);
        // Brightest when active, slightly dimmed otherwise — role colour
        // never touches the title, only brightness does.
        title.setForeground(active ? UIUtil.getLabelForeground() : UIUtil.getContextHelpForeground());
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        textColumn.add(title);
        textColumn.add(Box.createVerticalStrut(JBUI.scale(2)));

        SymbolChip symbol = new SymbolChip(PATH_FONT);
        symbol.setSymbol(WalkthroughSymbols.describe(project, step), step.file() + ":" + step.line());
        symbol.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Resolving every step's snippet on every rebuild would be wasted work —
        // only the active step's navigation target matters to the user right now,
        // so that's the only one re-resolved against the live document.
        if (active && isStale(step)) {
            JPanel pathRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
            pathRow.setOpaque(false);
            pathRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            pathRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, symbol.getPreferredSize().height));
            pathRow.add(symbol, BorderLayout.WEST);
            JBLabel stale = new JBLabel("code changed here");
            stale.setFont(PATH_FONT);
            stale.setForeground(STALE_COLOR);
            pathRow.add(stale, BorderLayout.EAST);
            textColumn.add(pathRow);
        } else {
            textColumn.add(symbol);
        }

        if (active) {
            textColumn.add(Box.createVerticalStrut(JBUI.scale(8)));
            JPanel body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setOpaque(false);
            body.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(markdown(step.markdown()));
            service.client().threadFor(step.anchor()).ifPresent(t -> {
                if (!t.question().isEmpty()) {
                    JBLabel q = new JBLabel("You · " + t.question());
                    q.setFont(EXPLANATION_FONT.deriveFont(Font.BOLD));
                    q.setForeground(UIUtil.getLabelForeground());
                    q.setAlignmentX(Component.LEFT_ALIGNMENT);
                    body.add(Box.createVerticalStrut(JBUI.scale(6)));
                    body.add(q);
                }
                body.add(markdown(t.synthesis()));
            });
            if (service.client().isPending(step.anchor())) {
                JBLabel pending = new JBLabel("● waiting for Claude…");
                pending.setFont(EXPLANATION_FONT);
                pending.setForeground(UIUtil.getContextHelpForeground());
                pending.setAlignmentX(Component.LEFT_ALIGNMENT);
                body.add(pending);
            }
            textColumn.add(body);
        }

        p.add(textColumn, BorderLayout.CENTER);

        p.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                service.controller().jumpTo(index);
            }
        });
        return p;
    }

    /**
     * Whether {@code step}'s snippet no longer matches nearby in its own open
     * document — mirrors {@link WalkthroughInlay}'s check so the rail gives the
     * same "code changed here" signal the inline card does.
     */
    private boolean isStale(WalkthroughStep step) {
        VirtualFile vf = WalkthroughNavigator.resolveStepFile(project, step);
        if (vf == null || vf.isDirectory()) return false;
        // getDocument() touches the PSI/VFS model and asserts a read action even
        // on the EDT — only this model read needs the wrapper, not the whole method.
        List<String> lines = ReadAction.compute(() -> {
            Document document = FileDocumentManager.getInstance().getDocument(vf);
            return document == null ? null : List.of(document.getText().split("\n", -1));
        });
        if (lines == null) return false;
        return WalkthroughNavigator.resolveLine(lines, step).kind() == AnchorResolver.Kind.STALE;
    }

    /**
     * Render markdown through the shared renderer so links behave exactly as
     * they do in the review panel (project-relative paths become clickable and
     * are routed by SynthesisLinkRouter).
     *
     * <p>The pane's {@link HTMLEditorKit} gets its own {@link StyleSheet} built
     * from the actual label font — a {@code body} rule with the platform's
     * font family/size/colour and ~1.7 line-height, plus {@code code}/{@code a}
     * rules — rather than {@code <font>} tags in the generated HTML or a bare
     * {@code setFont} (which the HTML view ignores once styled). The new sheet
     * is chained onto the kit's own default sheet via {@code addStyleSheet}
     * rather than mutated in place, because {@link HTMLEditorKit#getStyleSheet()}
     * returns a sheet shared process-wide — mutating it directly would leak
     * this styling into every other HTML pane in the IDE.
     */
    private JComponent markdown(String md) {
        // Inside a BoxLayout, an unconstrained JEditorPane reports a preferred
        // width wide enough to hold its HTML unwrapped on one line. Forcing a
        // reflow at the pane's own current width (once it has one) before
        // reporting preferred/maximum size makes the HTML actually wrap, and
        // re-wrap on every relayout — including tool-window resizes, since
        // StepsPanel above keeps that width in sync with the viewport.
        JEditorPane pane = new JEditorPane() {
            @Override public boolean getScrollableTracksViewportWidth() { return true; }

            @Override public Dimension getPreferredSize() {
                int width = getWidth();
                if (width <= 0) return super.getPreferredSize();
                setSize(width, Short.MAX_VALUE);
                return super.getPreferredSize();
            }

            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setContentType("text/html");

        HTMLEditorKit kit = (HTMLEditorKit) pane.getEditorKit();
        StyleSheet styles = new StyleSheet();
        styles.addStyleSheet(kit.getStyleSheet());
        styles.addRule(explanationStyleRule());
        kit.setStyleSheet(styles);
        pane.setText(MarkdownLinkRenderer.toHtml(md));

        pane.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                SynthesisLinkRouter.route(project, e.getDescription());
            }
        });
        return pane;
    }

    private String explanationStyleRule() {
        // Explanation foreground sits between title and path in weight —
        // blended toward the disabled colour rather than a hard-coded shade,
        // so it still tracks whatever the current theme's greys are.
        Color body = mix(UIUtil.getLabelForeground(), UIUtil.getLabelDisabledForeground(), 0.3f);
        return "body { font-family: '" + EXPLANATION_FONT.getFamily() + "'; "
            + "font-size: " + EXPLANATION_FONT.getSize() + "pt; "
            + "color: " + toHex(body) + "; line-height: 170%; }"
            + " p { margin-top: 0; margin-bottom: " + JBUI.scale(6) + "px; }"
            + " code, pre { font-family: monospace; font-size: " + PATH_FONT.getSize() + "pt; }"
            + " a { color: " + toHex(SEAM_COLOR) + "; text-decoration: none; }";
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

    private String statusText() {
        WalkthroughSessionClient.State s = service.client().state();
        return switch (s) {
            case ENDED -> "session ended — read only";
            case PAUSED -> "Claude is away — asks disabled";
            case DISCONNECTED -> "reconnecting…";
            // The header strip already shows "N / M" — no need to repeat it here.
            default -> "";
        };
    }

    /** Fill colour for a step's role — the only place role colour is used. */
    private static Color roleColor(WalkthroughStep.Role role) {
        return switch (role) {
            case SEAM -> SEAM_COLOR;
            case EDIT_SITE -> EDIT_SITE_COLOR;
            case CONTEXT -> UIUtil.getLabelDisabledForeground();
        };
    }

    /** CONTEXT has no real accent, so its disc is an outline, not a fill. */
    private static boolean roleFilled(WalkthroughStep.Role role) {
        return role != WalkthroughStep.Role.CONTEXT;
    }

    private void finish(boolean enableControls) {
        boolean live = enableControls
            && service.client().state() != WalkthroughSessionClient.State.ENDED
            && service.client().state() != WalkthroughSessionClient.State.PAUSED;
        ask.setEnabled(live);
        back.setEnabled(enableControls);
        next.setEnabled(enableControls);
        steps.revalidate();
        steps.repaint();
    }

    @Override public void dispose() {
        service.controller().removeListener(controllerListener);
        service.client().removeListener(clientListener);
    }

    /**
     * The {@code steps} list's content panel. Implementing {@link Scrollable} and
     * tracking the viewport's width (but not its height) is what makes the
     * enclosing {@link JBScrollPane} fill the tool window horizontally while
     * still scrolling vertically — without this, a plain {@link JPanel} view is
     * simply sized to its own preferred width, which never shrinks to the
     * visible area, so nothing downstream (rows, markdown panes) ever learns
     * how wide it's actually allowed to be.
     */
    private static final class StepsPanel extends JPanel implements Scrollable {
        StepsPanel() {
            super();
        }

        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return 64; }
    }

    /**
     * A small round step-number badge, coloured by {@link WalkthroughStep.Role}
     * — the only element in a row that carries role colour. CONTEXT (no
     * meaningful accent) renders as an outline so it doesn't compete visually
     * with the two colour-carrying roles; SEAM/EDIT_SITE render filled with a
     * white numeral. Steps already passed (before the active index) render at
     * reduced opacity — the "visited" cue.
     */
    private static final class RoleDisc implements Icon {
        private static final int SIZE = JBUI.scale(24);

        private final Color color;
        private final int number;
        private final boolean filled;
        private final boolean dimmed;

        RoleDisc(Color color, int number, boolean filled, boolean dimmed) {
            this.color = color;
            this.number = number;
            this.filled = filled;
            this.dimmed = dimmed;
        }

        @Override public int getIconWidth() { return SIZE; }
        @Override public int getIconHeight() { return SIZE; }

        @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (dimmed) {
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
                }
                Color numberColor;
                if (filled) {
                    g.setColor(color);
                    g.fillOval(x, y, SIZE, SIZE);
                    numberColor = Color.WHITE;
                } else {
                    g.setStroke(new BasicStroke(JBUI.scale(2)));
                    g.setColor(color);
                    g.drawOval(x + 1, y + 1, SIZE - 2, SIZE - 2);
                    numberColor = color;
                }
                Font f = c.getFont().deriveFont(Font.BOLD, SIZE * 0.48f);
                FontMetrics fm = c.getFontMetrics(f);
                String s = String.valueOf(number);
                int tx = x + (SIZE - fm.stringWidth(s)) / 2;
                int ty = y + (SIZE - fm.getHeight()) / 2 + fm.getAscent();
                g.setFont(f);
                g.setColor(numberColor);
                g.drawString(s, tx, ty);
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * Longest left-truncated ("…" + suffix) form of {@code s} that fits
     * {@code width} — {@code "…lTask()"} instead of {@code "completeTa…"} —
     * because the tail is what identifies a symbol or a path (the method
     * name; the filename), not the leading qualifier. Shared by
     * {@link SymbolChip}.
     */
    private static String leftFit(String s, FontMetrics fm, int width) {
        if (s.isEmpty() || width <= 0 || fm.stringWidth(s) <= width) return s;
        String ellipsis = "…";
        int lo = 0, hi = s.length();
        // Binary search for the smallest cut point (= longest remaining
        // suffix) whose ellipsis-prefixed form still fits: fitting is
        // monotonic in the cut point, so this converges to the boundary.
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            String candidate = ellipsis + s.substring(mid);
            if (fm.stringWidth(candidate) <= width) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return ellipsis + s.substring(lo);
    }

    /**
     * Small rounded, bordered, monospaced badge showing the step's enclosing
     * symbol ({@link WalkthroughSymbols#describe}) — {@code Class.method()}
     * with the class name a shade brighter than the method, or a bare class
     * name, or (when the anchor can't be resolved to a symbol) the raw
     * {@code File.java:123} fallback in a single dim tone. Bounded to
     * {@link #MAX_WIDTH} and left-truncated beyond that — a chip stays a
     * chip, it doesn't stretch to fill the row. The full project-relative
     * path plus line lives in the tooltip.
     */
    private static final class SymbolChip extends JComponent {
        // "ClassName.methodName()" — group 1 is the class, group 2 the method
        // (with its trailing parens). Anything that doesn't match this or a
        // bare identifier is treated as the File.java:123 fallback form.
        private static final Pattern METHOD =
            Pattern.compile("^([A-Za-z_$][A-Za-z0-9_$]*)(\\.[A-Za-z_$][A-Za-z0-9_$]*\\(\\))$");
        private static final Pattern CLASS_ONLY =
            Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

        private static final int MAX_WIDTH = JBUI.scale(200);
        private static final int PAD_H = JBUI.scale(6);
        private static final int PAD_V = JBUI.scale(2);
        private static final int ARC = JBUI.scale(6);

        // Class name sits between the title and the disabled/path colour —
        // brighter than the method, dimmer than the title.
        private final Color classColor = mix(UIUtil.getLabelForeground(), UIUtil.getLabelDisabledForeground(), 0.35f);
        private final Color methodColor = UIUtil.getLabelDisabledForeground();
        private final Color fallbackColor = UIUtil.getLabelDisabledForeground();
        // A hairline-subtle tint of the panel background, not a bare literal —
        // derives from the platform colour the chip actually sits on.
        private final Color background = mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.05f);
        private final Color border = JBColor.border();

        private String text = "";
        private String classPart = "";
        private String methodPart = "";
        private boolean isSymbol;

        SymbolChip(Font font) {
            setFont(font);
        }

        void setSymbol(String raw, String tooltip) {
            this.text = raw == null ? "" : raw;
            Matcher m = METHOD.matcher(text);
            if (m.matches()) {
                isSymbol = true;
                classPart = m.group(1);
                methodPart = m.group(2);
            } else if (CLASS_ONLY.matcher(text).matches()) {
                isSymbol = true;
                classPart = text;
                methodPart = "";
            } else {
                isSymbol = false;
                classPart = text;
                methodPart = "";
            }
            setToolTipText(tooltip == null || tooltip.isBlank() ? null : tooltip);
            revalidate();
            repaint();
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int natural = fm.stringWidth(text) + PAD_H * 2;
            int w = Math.max(JBUI.scale(24), Math.min(natural, MAX_WIDTH));
            return new Dimension(w, fm.getHeight() + PAD_V * 2);
        }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g.setColor(background);
                g.fillRoundRect(0, 0, w - 1, h - 1, ARC, ARC);
                g.setColor(border);
                g.drawRoundRect(0, 0, w - 1, h - 1, ARC, ARC);

                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(getFont());
                FontMetrics fm = g.getFontMetrics();
                int innerWidth = Math.max(0, w - PAD_H * 2);
                int ty = (h + fm.getAscent() - fm.getDescent()) / 2;

                if (!isSymbol) {
                    g.setColor(fallbackColor);
                    g.drawString(leftFit(text, fm, innerWidth), PAD_H, ty);
                    return;
                }
                String full = classPart + methodPart;
                if (fm.stringWidth(full) <= innerWidth) {
                    g.setColor(classColor);
                    g.drawString(classPart, PAD_H, ty);
                    if (!methodPart.isEmpty()) {
                        g.setColor(methodColor);
                        g.drawString(methodPart, PAD_H + fm.stringWidth(classPart), ty);
                    }
                } else {
                    // Doesn't fit even the chip's own box: left-truncate the
                    // whole thing in the dimmer (method) tone — the surviving
                    // tail is the method name, which is the more specific of
                    // the two anyway.
                    g.setColor(methodColor);
                    g.drawString(leftFit(full, fm, innerWidth), PAD_H, ty);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * Word-wraps text to at most {@code maxLines} lines at the component's
     * current width, ellipsizing the final line when more text remains.
     * Swing's HTML renderer has no line-clamp primitive, so this measures
     * and wraps by hand; like {@link SymbolChip} it recomputes on every
     * paint, so it re-wraps across tool-window resizes for free.
     */
    private static final class ClampedLabel extends JComponent {
        private final int maxLines;
        private String text = "";

        ClampedLabel(int maxLines, Font font, Color foreground) {
            this.maxLines = maxLines;
            setFont(font);
            setForeground(foreground);
        }

        void setFullText(String t) {
            this.text = t == null ? "" : t.strip();
            revalidate();
            repaint();
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(JBUI.scale(40), fm.getHeight() * maxLines);
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(getFont());
                g.setColor(getForeground());
                FontMetrics fm = g.getFontMetrics();
                int y = fm.getAscent();
                for (String line : wrap(fm, getWidth())) {
                    g.drawString(line, 0, y);
                    y += fm.getHeight();
                }
            } finally {
                g.dispose();
            }
        }

        private List<String> wrap(FontMetrics fm, int width) {
            List<String> lines = new ArrayList<>();
            if (text.isEmpty() || width <= 0) return lines;
            String[] words = text.split("\\s+");
            StringBuilder line = new StringBuilder();
            int idx = 0;
            while (idx < words.length) {
                String word = words[idx];
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (fm.stringWidth(candidate) <= width || line.length() == 0) {
                    line = new StringBuilder(candidate);
                    idx++;
                } else {
                    lines.add(line.toString());
                    line = new StringBuilder();
                    if (lines.size() == maxLines) break;
                }
            }
            boolean overflow = idx < words.length;
            if (lines.size() < maxLines && line.length() > 0) {
                lines.add(line.toString());
            }
            if (overflow && !lines.isEmpty()) {
                String last = lines.get(lines.size() - 1);
                String ellipsized = last + "…";
                while (fm.stringWidth(ellipsized) > width && last.length() > 1) {
                    last = last.substring(0, last.length() - 1).stripTrailing();
                    ellipsized = last + "…";
                }
                lines.set(lines.size() - 1, ellipsized);
            }
            return lines;
        }
    }
}
