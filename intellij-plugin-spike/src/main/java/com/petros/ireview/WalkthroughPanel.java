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
    //
    // FONT_SCALE is the one knob for this whole class: every derived font is
    // computed relative to the platform label font first (so it keeps
    // tracking the user's font-size/HiDPI settings), then multiplied by this
    // single factor via {@link #scale}, so a future "make it all N% bigger"
    // is one edit here rather than six separately-tuned deltas. Everything
    // that surrounds that text — row padding, the disc diameter, chip
    // padding, the gaps between elements — is scaled the same way via
    // {@link #scaled(int)} so nothing looks cramped once the text has grown.
    private static final float FONT_SCALE = 1.2f;

    private static final JBFont LABEL_FONT = JBUI.Fonts.label();
    /** Title outranks everything: bold, ~1pt larger than the default label. */
    private static final Font TITLE_FONT = scale(LABEL_FONT.asBold().biggerOn(1f));
    /** Explanation prose: the default label size, just not bold. */
    private static final Font EXPLANATION_FONT = scale(LABEL_FONT.asPlain());
    /** Path: monospaced, ~2pt smaller — recedes behind title and prose. */
    private static final Font PATH_FONT = scale(JBUI.Fonts.create(Font.MONOSPACED, LABEL_FONT.getSize()).lessOn(2f));
    /**
     * The symbol chip's own type — one step up from {@link #PATH_FONT} (only
     * 1pt below the base label instead of 2) so the pill text the owner
     * flagged as "very very small" reads comfortably, while staying
     * proportional to the platform's label font rather than a fixed size.
     * Kept distinct from {@link #PATH_FONT}, which other, smaller elements
     * (the status line, the stale marker) still use.
     */
    private static final Font SYMBOL_CHIP_FONT = scale(JBUI.Fonts.create(Font.MONOSPACED, LABEL_FONT.getSize()).lessOn(1f));
    /**
     * The role tag badge's type — same nominal size as {@link #SYMBOL_CHIP_FONT}
     * (never smaller, per spec) but sans-serif and bold so it still reads as
     * a small label, not code.
     */
    private static final Font ROLE_TAG_FONT = scale(JBUI.Fonts.label().lessOn(1f).asBold());
    /** Header strip (question + progress counter): small and quiet. */
    private static final Font HEADER_FONT = scale(LABEL_FONT.lessOn(1f));

    /** Multiplies an already theme-derived font by {@link #FONT_SCALE}. */
    private static Font scale(Font f) {
        return f.deriveFont(f.getSize2D() * FONT_SCALE);
    }

    /**
     * Multiplies a {@code JBUI.scale(...)} pixel size that surrounds text
     * (padding, gaps, icon diameters) by {@link #FONT_SCALE} before handing
     * it to {@link JBUI#scale(int)}, so those sizes keep pace with the type
     * ramp above instead of looking cramped once it grows.
     */
    private static int scaled(int base) {
        return JBUI.scale(Math.round(base * FONT_SCALE));
    }

    private static final int ROW_V = scaled(11);
    private static final int ROW_H = scaled(14);
    // Decorative accent-bar thickness, not text padding — deliberately left
    // off the FONT_SCALE ramp.
    private static final int ACCENT_W = JBUI.scale(3);

    // Role accents live only on the disc (see RoleDisc) — never on text.
    // JBColor pairs, not bare java.awt.Color, so both themes stay legible.
    private static final JBColor SEAM_COLOR = new JBColor(new Color(0x35, 0x74, 0xF0), new Color(0x54, 0x8A, 0xF7));
    private static final JBColor EDIT_SITE_COLOR = new JBColor(new Color(0x1F, 0x9C, 0x5B), new Color(0x4C, 0xBB, 0x79));
    private static final JBColor STALE_COLOR = new JBColor(new Color(0xB0, 0x90, 0x10), new Color(0xF1, 0xC4, 0x0F));

    private final Project project;
    private final WalkthroughService service;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel header = new JPanel(new BorderLayout(scaled(10), 0));
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
            JBUI.Borders.empty(scaled(8), ROW_H, scaled(8), ROW_H)));
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

        JPanel south = new JPanel(new BorderLayout(scaled(4), scaled(4)));
        south.setBorder(JBUI.Borders.empty(scaled(6)));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, scaled(6), 0));
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
        disc.setToolTipText(roleTooltip(step.role()));
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

        // SEAM/EDIT_SITE get an explicit tag next to the title so the disc's
        // colour coding is self-explanatory instead of a silent convention;
        // CONTEXT is the default and stays untagged so it doesn't add noise
        // to most rows. A horizontal Box (not a BorderLayout panel) so its own
        // maximumLayoutSize stays bounded to its children instead of stretching
        // to fill the row — see the row panel's own getMaximumSize override above.
        Box titleRow = Box.createHorizontalBox();
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleRow.add(title);
        String tagText = roleTagText(step.role());
        if (tagText != null) {
            titleRow.add(Box.createHorizontalStrut(scaled(6)));
            RoleTag tag = new RoleTag(tagText, accent);
            tag.setAlignmentX(Component.LEFT_ALIGNMENT);
            titleRow.add(tag);
        }
        titleRow.add(Box.createHorizontalGlue());
        textColumn.add(titleRow);
        textColumn.add(Box.createVerticalStrut(scaled(2)));

        SymbolChip symbol = new SymbolChip(SYMBOL_CHIP_FONT);
        symbol.setSymbol(WalkthroughSymbols.describe(project, step), step.file() + ":" + step.line());
        symbol.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Resolving every step's snippet on every rebuild would be wasted work —
        // only the active step's navigation target matters to the user right now,
        // so that's the only one re-resolved against the live document.
        if (active && isStale(step)) {
            JPanel pathRow = new JPanel(new BorderLayout(scaled(6), 0));
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
            textColumn.add(Box.createVerticalStrut(scaled(8)));
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
                    body.add(Box.createVerticalStrut(scaled(6)));
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

        // Hand cursor over the whole row reads as clickable; children that
        // don't set their own cursor (every leaf here except the active row's
        // JEditorPane, which installs its own text cursor) inherit it from the
        // row automatically, so this alone covers disc/title/chip/padding —
        // no per-descendant cursor juggling needed.
        if (!active) {
            p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        wireRowActivation(p, p, index, active);
        return p;
    }

    /**
     * Wires {@code index}'s row so a click anywhere in it — disc, title, chip,
     * padding, empty space to the right — activates the step, with a subtle
     * hover highlight on inactive rows. AWT only ever delivers a mouse event
     * to the single deepest component under the pointer; it never bubbles to
     * ancestors on its own. So the listener has to be attached recursively to
     * every descendant as the row is built, not just to {@code row} itself —
     * otherwise only the gaps between children (not the children) would
     * activate the step, which is exactly today's bug.
     *
     * <p>The one deliberate exception is the active row's explanation
     * {@link JEditorPane}: it already routes its own link clicks through
     * {@link SynthesisLinkRouter} via its own hyperlink listener, and it
     * needs to keep handling its own text-selection drag undisturbed.
     * {@link WalkthroughController#jumpTo} unconditionally rebuilds the whole
     * step list — even when the target is already the active index — so
     * wiring the pane too would tear it (and any in-progress selection or
     * link click) down mid-gesture. Skipping it entirely means a click on its
     * prose that isn't a link simply does nothing, which is the correct
     * behaviour for a step that's already active.
     */
    private void wireRowActivation(Component c, JPanel row, int index, boolean active) {
        if (c instanceof JEditorPane) return;
        c.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (!active) service.controller().jumpTo(index);
            }
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                if (active) return;
                row.setOpaque(true);
                row.setBackground(JBUI.CurrentTheme.List.Hover.background(false));
                row.repaint();
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                if (active) return;
                row.setOpaque(false);
                row.repaint();
            }
        });
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                wireRowActivation(child, row, index, active);
            }
        }
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
            + " p { margin-top: 0; margin-bottom: " + scaled(6) + "px; }"
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

    /**
     * Uppercase badge text for the title row — only for the two roles the
     * colour coding actually distinguishes. CONTEXT is the default state and
     * stays untagged: tagging every row would be noise, not signal.
     */
    private static String roleTagText(WalkthroughStep.Role role) {
        return switch (role) {
            case SEAM -> "SEAM";
            case EDIT_SITE -> "EDIT SITE";
            case CONTEXT -> null;
        };
    }

    /** One-sentence tooltip for the disc, spelling out what its colour means. */
    private static String roleTooltip(WalkthroughStep.Role role) {
        return switch (role) {
            case SEAM -> "Seam — where behaviour is extended";
            case EDIT_SITE -> "Edit site — where new code goes";
            case CONTEXT -> "Context — explains existing behaviour";
        };
    }

    /**
     * {@code color} at a low, fixed opacity — derives the role tag's badge
     * background from the same theme-aware role colour used everywhere else
     * (see {@link #roleColor}) rather than a fresh literal, the same way
     * {@link #mix} derives blended colours from platform colours elsewhere
     * in this file.
     */
    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
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
        private static final int SIZE = scaled(24);

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
                    g.setStroke(new BasicStroke(scaled(2)));
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
     * Small pill-shaped badge next to a step's title, naming its
     * {@link WalkthroughStep.Role} in words so the disc's colour coding isn't
     * the only cue — text in the role colour on a low-opacity tint of that
     * same colour, never a solid fill, so it stays a badge rather than
     * competing with the title for attention.
     */
    private static final class RoleTag extends JComponent {
        private static final int PAD_H = scaled(6);
        private static final int PAD_V = scaled(1);
        private static final int ARC = JBUI.scale(4);
        // A visible but low-opacity tint — enough to read as "coloured", not
        // enough to compete with the disc or the active row's accent bar.
        private static final int BACKGROUND_ALPHA = 38;

        private final String text;
        private final Color foreground;
        private final Color background;

        RoleTag(String text, Color roleColor) {
            this.text = text;
            this.foreground = roleColor;
            this.background = withAlpha(roleColor, BACKGROUND_ALPHA);
            setFont(ROLE_TAG_FONT);
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + PAD_H * 2, fm.getHeight() + PAD_V * 2);
        }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(background);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);

                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(getFont());
                g.setColor(foreground);
                FontMetrics fm = g.getFontMetrics();
                int ty = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g.drawString(text, PAD_H, ty);
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

        // Left as-is rather than run through FONT_SCALE: a fixed ceiling on
        // how wide any one chip can get, independent of type size — growing
        // it in lockstep with the font would just move the truncation point,
        // not remove it. PAD_H/PAD_V (below) are scaled, so the bigger text
        // still gets breathing room inside that fixed envelope.
        private static final int MAX_WIDTH = JBUI.scale(200);
        // Padding bumped alongside SYMBOL_CHIP_FONT (Fix: chip font was "very
        // very small") so the larger text doesn't look cramped in its pill.
        private static final int PAD_H = scaled(8);
        private static final int PAD_V = scaled(4);
        private static final int ARC = JBUI.scale(7);

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
            int w = Math.max(scaled(24), Math.min(natural, MAX_WIDTH));
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
