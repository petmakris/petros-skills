package com.petros.ireview;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.VisibleAreaListener;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;

/**
 * Mode B position indicator: a floating "control pill" docked bottom-centre
 * over the active editor — title + progress, real prev/next buttons wired to
 * the controller, and a row of step dots. Replaced (not stacked) on each step.
 *
 * <p>Delivered as a real child component of the IDE window's own layered pane
 * ({@code JRootPane#getLayeredPane()}, added at {@link JLayeredPane#PALETTE_LAYER})
 * rather than through a {@link com.intellij.openapi.ui.popup.JBPopup}. A
 * popup is cancelled by anything the platform considers "elsewhere" — focus
 * leaving it, the IDE window (de)activating, another popup opening, Escape,
 * even the editor scrolling — none of which have anything to do with the
 * walkthrough being finished, yet each one silently and permanently killed
 * this bar until the next unrelated refresh brought it back. Anchoring
 * inside a plain child component of the window's component tree removes
 * that whole class of bug rather than patching each cancellation path: a
 * plain child component has no cancellation channel to plug in the first
 * place, so it just stays exactly as visible as the editor around it.
 *
 * <p>{@link Editor#getComponent()} itself is <em>not</em> a layered pane —
 * it is a plain {@code JPanel} wrapper around the editor's scroll pane and
 * gutter — and {@code EditorImpl} keeps its own internal floating-toolbar
 * layered pane private, with no public accessor. The nearest genuinely
 * usable layered pane is therefore the one every {@code JRootPane} already
 * exposes, resolved via {@link SwingUtilities#getRootPane(Component)} from
 * the editor's component. Coordinates are converted from the editor
 * component's origin into that layered pane's coordinate space (see
 * {@link #positionPill}), and the pill's bounds are always clamped to the
 * editor component's own footprint within the layered pane — so even though
 * the host is window-scoped, the pill itself can never stray outside the
 * editor area into a neighbouring tool window or split.
 *
 * <p>The pill's bounds are kept to its own visible rectangle only — never a
 * full-size transparent overlay — so hit-testing on the empty pixels around
 * it falls straight through to the editor beneath; nothing extra is needed
 * to avoid stealing clicks meant for the editor. All of its chrome (gradient
 * fill, border, inner highlight, drop shadow) is painted by {@link PillPanel}
 * itself; no callout/pointer, same as before.
 */
public final class WalkthroughHud {

    private static final Logger LOG = Logger.getInstance(WalkthroughHud.class);

    // Same one-constant-per-class convention as WalkthroughPanel: every font
    // below derives from the platform label font first (so it keeps tracking
    // the user's font-size/HiDPI settings), then this single factor scales
    // the whole ramp via {@link #scale}. JBUI.scale(...) sizes that surround
    // that text — the pill's internal padding, button sizes, progress dots,
    // the gaps between elements — go through {@link #scaled(int)} the same
    // way, so nothing looks cramped once the text has grown.
    private static final float FONT_SCALE = 1.2f;

    private static final Font TITLE_FONT = scale(JBUI.Fonts.label().asBold());
    private static final Font META_FONT = scale(JBUI.Fonts.label().lessOn(1.5f));
    // Position offset from the editor's bottom edge, not text sizing —
    // deliberately left off the FONT_SCALE ramp.
    private static final int BOTTOM_MARGIN = JBUI.scale(36);
    // Natural width ceiling for the title/meta column: past this, Swing's
    // own label truncation (a JLabel clips + appends "…" once its actual
    // layout width is less than the text's natural width) kicks in instead
    // of the whole pill growing wider than a narrow editor — the same
    // bounded-width-then-elide shape WalkthroughPanel's SymbolChip already
    // uses for the identical problem. Scaled so a longer title still gets
    // roughly the same character budget at the larger font.
    private static final int MAX_TITLE_WIDTH = scaled(200);

    /** Multiplies an already theme-derived font by {@link #FONT_SCALE}. */
    private static Font scale(Font f) {
        return f.deriveFont(f.getSize2D() * FONT_SCALE);
    }

    /**
     * Multiplies a {@code JBUI.scale(...)} pixel size that surrounds text
     * (padding, gaps, button/dot diameters) by {@link #FONT_SCALE} before
     * handing it to {@link JBUI#scale(int)}, so those sizes keep pace with
     * the type ramp above instead of looking cramped once it grows.
     */
    private static int scaled(int base) {
        return JBUI.scale(Math.round(base * FONT_SCALE));
    }

    private final WalkthroughController controller;

    // The editor component currently hosting the pill (null when hidden),
    // the layered pane the pill is actually a child of, the root pane that
    // layered pane belongs to, the pill itself, and every listener that
    // keeps it glued to that editor's bottom-centre across scrolling,
    // editor resize/move and window/root-pane resize. hide() tears all of
    // it down together — see hide() — so at most one pill exists at a time
    // (WalkthroughInlay's refresh() calls show() on every step activation;
    // show() always hides first).
    private JLayeredPane hostLayeredPane;
    private JComponent hostEditorComponent;
    private JRootPane hostRootPane;
    private JComponent currentPill;
    private Disposable listenerScope;
    private ComponentAdapter editorComponentListener;
    private ComponentAdapter rootComponentListener;

    // While waiting for a not-yet-realised editor component to become
    // showing (see attemptShow) — at most one pending attempt at a time,
    // torn down by hide() the same as everything else.
    private JComponent pendingComponent;
    private HierarchyListener pendingListener;

    public WalkthroughHud(WalkthroughController controller) {
        this.controller = controller;
    }

    /**
     * Shows the pill anchored to {@code editor}'s bottom-centre. Hides any
     * previously-shown pill first, including one anchored to a *different*
     * editor — this is how the bar follows the active editor: refresh()
     * calls this with whatever editor is currently selected, and switching
     * tabs naturally migrates the pill (or drops it, if {@code editor} is
     * null or the wrong kind of editor) rather than leaving it stuck over a
     * file the user has navigated away from.
     */
    public void show(Editor editor, WalkthroughStep step, int index, int total) {
        hide();
        // No editor to anchor to (very early in project open, or the user
        // has no text editor selected) — there's nowhere sensible to host
        // the pill, so just skip it, the same way the old missing-IdeFrame
        // guard did. This is a normal, expected state, not a failure.
        if (editor == null) return;
        attemptShow(editor.getComponent(), editor, step, index, total);
    }

    /**
     * Resolves a host for {@code editorComponent} and shows the pill in it.
     * If the component isn't realised yet ({@link Component#isShowing()} is
     * false — happens very early in project open, or immediately after a
     * tab switch before layout has run) this does <em>not</em> give up
     * silently: it registers a one-shot {@link HierarchyListener} and
     * retries the instant the component becomes showing. Only a component
     * that is showing yet still has no {@link JRootPane} — genuinely
     * unexpected — is logged and dropped.
     */
    private void attemptShow(JComponent editorComponent, Editor editor, WalkthroughStep step, int index, int total) {
        if (!editorComponent.isShowing()) {
            HierarchyListener[] self = new HierarchyListener[1];
            self[0] = e -> {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && editorComponent.isShowing()) {
                    editorComponent.removeHierarchyListener(self[0]);
                    if (pendingListener == self[0]) {
                        pendingListener = null;
                        pendingComponent = null;
                    }
                    attemptShow(editorComponent, editor, step, index, total);
                }
            };
            editorComponent.addHierarchyListener(self[0]);
            pendingComponent = editorComponent;
            pendingListener = self[0];
            return;
        }

        JRootPane root = SwingUtilities.getRootPane(editorComponent);
        JLayeredPane layeredPane = root != null ? root.getLayeredPane() : null;
        if (layeredPane == null) {
            LOG.warn("WalkthroughHud: editor component is showing but has no JRootPane; "
                + "cannot host the step bar for step \"" + step.title() + "\"");
            return;
        }

        JComponent pill = buildPill(step, index, total);
        pill.setFocusable(false);

        Disposable scope = Disposer.newDisposable("WalkthroughHud");
        VisibleAreaListener reposition = e -> positionPill(layeredPane, editorComponent, pill);
        editor.getScrollingModel().addVisibleAreaListener(reposition, scope);

        // VisibleAreaListener covers scrolling and most resizes (the visible
        // area is a function of viewport size), but editor resize/move that
        // don't change the visible area's logical extent — and root-pane
        // (whole IDE window) resize — need their own listeners so the pill
        // never drifts from the editor's bottom-centre.
        ComponentAdapter editorListener = new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { positionPill(layeredPane, editorComponent, pill); }
            @Override public void componentMoved(ComponentEvent e) { positionPill(layeredPane, editorComponent, pill); }
        };
        editorComponent.addComponentListener(editorListener);

        ComponentAdapter rootListener = new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { positionPill(layeredPane, editorComponent, pill); }
        };
        root.addComponentListener(rootListener);

        layeredPane.add(pill, JLayeredPane.PALETTE_LAYER);
        positionPill(layeredPane, editorComponent, pill);
        layeredPane.revalidate();
        layeredPane.repaint();

        hostLayeredPane = layeredPane;
        hostEditorComponent = editorComponent;
        hostRootPane = root;
        editorComponentListener = editorListener;
        rootComponentListener = rootListener;
        currentPill = pill;
        listenerScope = scope;

        // Defensive check: the exact class of bug this HUD once shipped
        // (WalkthroughHud.show() returning before adding anything to the
        // hierarchy) produced no exception and no log line — just a pill
        // that silently never appeared. Make that failure mode visible
        // instead of silent, in both dev (-ea) and production builds.
        boolean healthy = pill.getParent() != null && !pill.getBounds().isEmpty();
        if (!healthy) {
            LOG.warn("WalkthroughHud: pill attached but not laid out — parent=" + pill.getParent()
                + " bounds=" + pill.getBounds() + " step=\"" + step.title() + "\"");
        }
        assert healthy : "WalkthroughHud pill failed to attach: parent=" + pill.getParent() + " bounds=" + pill.getBounds();
    }

    public void hide() {
        if (pendingComponent != null) {
            pendingComponent.removeHierarchyListener(pendingListener);
            pendingComponent = null;
            pendingListener = null;
        }
        if (currentPill == null) return;
        hostLayeredPane.remove(currentPill);
        hostLayeredPane.revalidate();
        hostLayeredPane.repaint();
        hostEditorComponent.removeComponentListener(editorComponentListener);
        hostRootPane.removeComponentListener(rootComponentListener);
        // Disposing rather than editor.getScrollingModel().removeVisibleAreaListener(...)
        // directly: the editor behind hostLayeredPane may already be closed
        // by the time hide() runs (e.g. the user closed the tab), and
        // Disposer.dispose on an already-torn-down scope is always safe,
        // whereas reaching back into a possibly-disposed editor's scrolling
        // model would not be.
        Disposer.dispose(listenerScope);
        hostLayeredPane = null;
        hostEditorComponent = null;
        hostRootPane = null;
        editorComponentListener = null;
        rootComponentListener = null;
        currentPill = null;
        listenerScope = null;
    }

    /**
     * Positions {@code pill} at {@code editorComponent}'s bottom-centre,
     * converting {@code editorComponent}'s origin into {@code layeredPane}'s
     * coordinate space (they are not the same component — the layered pane
     * belongs to the enclosing {@link JRootPane}, which may host tool
     * windows and other editors besides this one). The result is then
     * clamped to {@code editorComponent}'s own footprint within that space,
     * so a small editor (e.g. a narrow split) can never let the pill
     * overhang into a neighbouring tool window or split — even though the
     * host layered pane itself spans the whole window.
     */
    private static void positionPill(JLayeredPane layeredPane, JComponent editorComponent, JComponent pill) {
        if (!editorComponent.isShowing() || !layeredPane.isShowing()) return;
        Point origin = SwingUtilities.convertPoint(editorComponent, 0, 0, layeredPane);
        int editorWidth = editorComponent.getWidth();
        int editorHeight = editorComponent.getHeight();
        Dimension pref = pill.getPreferredSize();

        int x = origin.x + Math.max(0, (editorWidth - pref.width) / 2);
        int y = origin.y + editorHeight - pref.height - BOTTOM_MARGIN;

        x = clamp(x, origin.x, origin.x + Math.max(0, editorWidth - pref.width));
        y = clamp(y, origin.y, origin.y + Math.max(0, editorHeight - pref.height));

        pill.setBounds(x, y, pref.width, pref.height);
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(value, hi));
    }

    private PillPanel buildPill(WalkthroughStep step, int index, int total) {
        PillButton prevBtn = new PillButton("‹", false, controller::prev);
        prevBtn.setEnabled(index > 0);
        prevBtn.setToolTipText("Previous step" + shortcutSuffix(WalkthroughActions.PREV_ID));

        PillButton nextBtn = new PillButton("›", true, controller::next);
        nextBtn.setEnabled(index < total - 1);
        nextBtn.setToolTipText("Next step" + shortcutSuffix(WalkthroughActions.NEXT_ID));

        JBLabel titleLabel = new JBLabel(step.title());
        titleLabel.setFont(TITLE_FONT);
        titleLabel.setForeground(UIUtil.getLabelForeground());
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        titleLabel.setFocusable(false);

        JBLabel metaLabel = new JBLabel((index + 1) + " / " + total + " · " + roleLabel(step.role()));
        metaLabel.setFont(META_FONT);
        // Was UIUtil.getLabelDisabledForeground() — near-illegible against the
        // pill's gradient at small size. getContextHelpForeground() is the
        // platform's own "subordinate but still readable" tone (used for
        // help/hint text throughout the IDE), so the meta line stays visually
        // secondary to the title without being invisible.
        metaLabel.setForeground(UIUtil.getContextHelpForeground());
        metaLabel.setHorizontalAlignment(SwingConstants.CENTER);
        metaLabel.setFocusable(false);

        JPanel center = new JPanel(new GridLayout(2, 1, 0, scaled(1)));
        center.setOpaque(false);
        center.setFocusable(false);
        center.setBorder(JBUI.Borders.emptyLeft(scaled(4)));
        center.add(titleLabel);
        center.add(metaLabel);
        // Cap the natural width center reports to MAX_TITLE_WIDTH (see field
        // doc) rather than the title's full unclamped width, so a long title
        // elides instead of forcing the whole pill wider than the editor.
        int contentWidth = Math.min(
            Math.max(titleLabel.getPreferredSize().width, metaLabel.getPreferredSize().width),
            MAX_TITLE_WIDTH);
        center.setPreferredSize(new Dimension(contentWidth + scaled(8), center.getPreferredSize().height));

        DotsRow dots = new DotsRow(index, total);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.CENTER, scaled(8), 0));
        right.setOpaque(false);
        right.setFocusable(false);
        right.add(dots);
        right.add(nextBtn);

        JPanel content = new JPanel(new BorderLayout(scaled(10), 0));
        content.setOpaque(false);
        content.setFocusable(false);
        content.add(prevBtn, BorderLayout.WEST);
        content.add(center, BorderLayout.CENTER);
        content.add(right, BorderLayout.EAST);

        PillPanel pill = new PillPanel();
        pill.add(content, BorderLayout.CENTER);
        return pill;
    }

    private static String roleLabel(WalkthroughStep.Role role) {
        String name = role.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Reads the live keymap the same way {@link WalkthroughActions#hintText()}
     * does, from {@code actionId}, so the button tooltip can never drift from
     * the actual bound shortcut. Duplicated in miniature here rather than
     * calling into {@code WalkthroughActions} because its lookup is private
     * and combines all four actions into one string — this needs just one.
     */
    private static String shortcutSuffix(String actionId) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) return "";
        Shortcut[] shortcuts = action.getShortcutSet().getShortcuts();
        return shortcuts.length == 0 ? "" : "  (" + KeymapUtil.getShortcutText(shortcuts[0]) + ")";
    }

    /**
     * The pill's own chrome: a rounded (~12px) shape with a soft vertical
     * gradient fill, a 1px border, a 1px lighter inner top highlight, and a
     * hand-rolled drop shadow (a handful of decreasing-alpha rounded rects,
     * since the platform offers no blur primitive) — no callout/pointer.
     */
    private static final class PillPanel extends JPanel {
        private static final JBColor TOP = new JBColor(new Color(0xFF, 0xFF, 0xFF), new Color(0x4B, 0x4E, 0x52));
        private static final JBColor BOTTOM = new JBColor(new Color(0xEE, 0xF0, 0xF3), new Color(0x30, 0x32, 0x35));
        private static final JBColor BORDER = new JBColor(new Color(0xC9, 0xCC, 0xD1), new Color(0x1B, 0x1C, 0x1F));
        private static final JBColor HIGHLIGHT = new JBColor(new Color(0xFF, 0xFF, 0xFF, 170), new Color(0xFF, 0xFF, 0xFF, 30));
        private static final JBColor SHADOW = new JBColor(new Color(0x00, 0x00, 0x00, 60), new Color(0x00, 0x00, 0x00, 110));

        private static final int ARC = JBUI.scale(12);
        private static final int SHADOW_SPAN = JBUI.scale(6);
        private static final int SHADOW_DROP = JBUI.scale(2);

        PillPanel() {
            super(new BorderLayout());
            setOpaque(false);
            setFocusable(false);
            setBorder(JBUI.Borders.empty(
                SHADOW_SPAN + scaled(6), SHADOW_SPAN + scaled(14),
                SHADOW_SPAN + SHADOW_DROP + scaled(6), SHADOW_SPAN + scaled(14)));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int x = SHADOW_SPAN, y = SHADOW_SPAN;
                int w = getWidth() - SHADOW_SPAN * 2;
                int h = getHeight() - SHADOW_SPAN * 2 - SHADOW_DROP;
                if (w <= 0 || h <= 0) return;

                for (int i = SHADOW_SPAN; i >= 1; i--) {
                    int alpha = Math.max(2, SHADOW.getAlpha() * (SHADOW_SPAN - i + 1) / (SHADOW_SPAN * 3));
                    g.setColor(new Color(SHADOW.getRed(), SHADOW.getGreen(), SHADOW.getBlue(), alpha));
                    g.fillRoundRect(x - i / 2, y - i / 2 + SHADOW_DROP + SHADOW_SPAN, w + i, h + i, ARC + i, ARC + i);
                }

                RoundRectangle2D.Float shape = new RoundRectangle2D.Float(x, y, w, h, ARC, ARC);
                g.setPaint(new GradientPaint(0, y, TOP, 0, y + h, BOTTOM));
                g.fill(shape);

                g.setColor(HIGHLIGHT);
                g.drawLine(x + ARC / 2, y + 1, x + w - ARC / 2, y + 1);

                g.setColor(BORDER);
                g.draw(new RoundRectangle2D.Float(x, y, w - 1, h - 1, ARC, ARC));
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * A small clickable rounded-square button — the pill's prev/next controls.
     * {@code accent} paints a filled accent-blue square (the "next" action);
     * the neutral form is a bordered square with a plain-foreground glyph.
     * Disabled buttons dim and stop reacting to clicks.
     */
    private static final class PillButton extends JComponent {
        private static final int SIZE = scaled(22);
        private static final JBColor ACCENT = new JBColor(new Color(0x35, 0x74, 0xF0), new Color(0x54, 0x8A, 0xF7));

        private final String glyph;
        private final boolean accent;

        PillButton(String glyph, boolean accent, Runnable action) {
            this.glyph = glyph;
            this.accent = accent;
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (isEnabled()) action.run();
                }
            });
        }

        @Override public Dimension getPreferredSize() { return new Dimension(SIZE, SIZE); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (!isEnabled()) {
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
                }
                int arc = scaled(6);
                Color glyphColor;
                if (accent) {
                    g.setColor(ACCENT);
                    g.fillRoundRect(0, 0, SIZE - 1, SIZE - 1, arc, arc);
                    // Fixed white-on-accent, same convention WalkthroughPanel's
                    // RoleDisc uses for a filled disc's numeral — not theme-blind,
                    // just a fixed foreground against a fixed (paired) fill.
                    glyphColor = Color.WHITE;
                } else {
                    g.setColor(JBColor.border());
                    g.drawRoundRect(0, 0, SIZE - 1, SIZE - 1, arc, arc);
                    glyphColor = UIUtil.getLabelForeground();
                }
                g.setFont(getFont().deriveFont(Font.BOLD, SIZE * 0.55f));
                FontMetrics fm = g.getFontMetrics();
                int tx = (SIZE - fm.stringWidth(glyph)) / 2;
                int ty = (SIZE + fm.getAscent() - fm.getDescent()) / 2 - 1;
                g.setColor(glyphColor);
                g.drawString(glyph, tx, ty);
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * One dot per step, current filled with the accent colour and the rest
     * muted. A tour with more than {@link #CAP} steps collapses to
     * {@code CAP} dots representing evenly-spaced buckets of the whole tour
     * (progress-bar style) rather than rendering one per step, so a
     * fifty-step tour still reads as a small, legible row.
     */
    private static final class DotsRow extends JComponent {
        private static final int CAP = 10;
        private static final int DOT = scaled(6);
        private static final int GAP = scaled(5);
        private static final JBColor ACTIVE = new JBColor(new Color(0x35, 0x74, 0xF0), new Color(0x54, 0x8A, 0xF7));
        private static final Color MUTED = JBColor.border();

        private final int count;
        private final int activeDot;

        DotsRow(int index, int total) {
            this.count = Math.max(1, Math.min(total, CAP));
            this.activeDot = total <= CAP
                ? index
                : (int) Math.round(index * (double) (count - 1) / Math.max(1, total - 1));
            setFocusable(false);
        }

        @Override public Dimension getPreferredSize() {
            return new Dimension(count * DOT + Math.max(0, count - 1) * GAP, DOT);
        }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int x = 0;
                for (int i = 0; i < count; i++) {
                    g.setColor(i == activeDot ? ACTIVE : MUTED);
                    g.fillOval(x, 0, DOT, DOT);
                    x += DOT + GAP;
                }
            } finally {
                g.dispose();
            }
        }
    }
}
