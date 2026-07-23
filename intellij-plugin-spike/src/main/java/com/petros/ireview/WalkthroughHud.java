package com.petros.ireview;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.VisibleAreaListener;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;

/**
 * Mode B position indicator: a floating "control pill" docked bottom-centre
 * over the active editor — title + progress, real prev/next buttons wired to
 * the controller, and a row of step dots. Replaced (not stacked) on each step.
 *
 * <p>Delivered as a real child component of the editor's own layered pane
 * ({@link Editor#getComponent()}, added at {@link JLayeredPane#PALETTE_LAYER})
 * rather than through a {@link com.intellij.openapi.ui.popup.JBPopup}. A
 * popup is cancelled by anything the platform considers "elsewhere" — focus
 * leaving it, the IDE window (de)activating, another popup opening, Escape,
 * even the editor scrolling — none of which have anything to do with the
 * walkthrough being finished, yet each one silently and permanently killed
 * this bar until the next unrelated refresh brought it back. Anchoring
 * inside the editor's own component tree removes that whole class of bug
 * rather than patching each cancellation path: a plain child component has
 * no cancellation channel to plug in the first place, so it just stays
 * exactly as visible as the editor around it.
 *
 * <p>The pill's bounds are kept to its own visible rectangle only — never a
 * full-size transparent overlay — so hit-testing on the empty pixels around
 * it falls straight through to the editor beneath; nothing extra is needed
 * to avoid stealing clicks meant for the editor. All of its chrome (gradient
 * fill, border, inner highlight, drop shadow) is painted by {@link PillPanel}
 * itself; no callout/pointer, same as before.
 */
public final class WalkthroughHud {

    private static final JBFont TITLE_FONT = JBUI.Fonts.label().asBold();
    private static final JBFont META_FONT = JBUI.Fonts.label().lessOn(1.5f);
    private static final int BOTTOM_MARGIN = JBUI.scale(36);

    private final WalkthroughController controller;

    // The editor currently hosting the pill (null when hidden), its layered
    // pane (the component the pill is actually a child of), the pill itself,
    // and the listener that keeps it glued to that editor's bottom-centre
    // across resizes and scrolling. hide() tears all four down together —
    // see hide() — so at most one pill exists at a time (WalkthroughInlay's
    // refresh() calls show() on every step activation; show() always hides
    // first).
    private JLayeredPane hostLayeredPane;
    private JComponent currentPill;
    private Disposable listenerScope;

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
        // No editor to anchor to (very early in project open, or a
        // non-standard Editor implementation whose root component isn't a
        // JLayeredPane) — there's nowhere sensible to host the pill, so just
        // skip it, the same way the old missing-IdeFrame guard did.
        if (editor == null) return;
        JComponent editorComponent = editor.getComponent();
        if (!(editorComponent instanceof JLayeredPane layeredPane)) return;

        JComponent pill = buildPill(step, index, total);
        pill.setFocusable(false);

        Disposable scope = Disposer.newDisposable("WalkthroughHud");
        VisibleAreaListener reposition = e -> positionPill(layeredPane, pill);
        editor.getScrollingModel().addVisibleAreaListener(reposition, scope);

        layeredPane.add(pill, JLayeredPane.PALETTE_LAYER);
        positionPill(layeredPane, pill);
        layeredPane.revalidate();
        layeredPane.repaint();

        hostLayeredPane = layeredPane;
        currentPill = pill;
        listenerScope = scope;
    }

    public void hide() {
        if (currentPill == null) return;
        hostLayeredPane.remove(currentPill);
        hostLayeredPane.revalidate();
        hostLayeredPane.repaint();
        // Disposing rather than editor.getScrollingModel().removeVisibleAreaListener(...)
        // directly: the editor behind hostLayeredPane may already be closed
        // by the time hide() runs (e.g. the user closed the tab), and
        // Disposer.dispose on an already-torn-down scope is always safe,
        // whereas reaching back into a possibly-disposed editor's scrolling
        // model would not be.
        Disposer.dispose(listenerScope);
        hostLayeredPane = null;
        currentPill = null;
        listenerScope = null;
    }

    private static void positionPill(JLayeredPane layeredPane, JComponent pill) {
        Dimension pref = pill.getPreferredSize();
        int x = Math.max(0, (layeredPane.getWidth() - pref.width) / 2);
        int y = Math.max(0, layeredPane.getHeight() - pref.height - BOTTOM_MARGIN);
        pill.setBounds(x, y, pref.width, pref.height);
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
        metaLabel.setForeground(UIUtil.getLabelDisabledForeground());
        metaLabel.setHorizontalAlignment(SwingConstants.CENTER);
        metaLabel.setFocusable(false);

        JPanel center = new JPanel(new GridLayout(2, 1, 0, JBUI.scale(1)));
        center.setOpaque(false);
        center.setFocusable(false);
        center.setBorder(JBUI.Borders.emptyLeft(JBUI.scale(4)));
        center.add(titleLabel);
        center.add(metaLabel);
        center.setPreferredSize(new Dimension(
            Math.max(titleLabel.getPreferredSize().width, metaLabel.getPreferredSize().width) + JBUI.scale(8),
            center.getPreferredSize().height));

        DotsRow dots = new DotsRow(index, total);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0));
        right.setOpaque(false);
        right.setFocusable(false);
        right.add(dots);
        right.add(nextBtn);

        JPanel content = new JPanel(new BorderLayout(JBUI.scale(10), 0));
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
                SHADOW_SPAN + JBUI.scale(6), SHADOW_SPAN + JBUI.scale(14),
                SHADOW_SPAN + SHADOW_DROP + JBUI.scale(6), SHADOW_SPAN + JBUI.scale(14)));
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
        private static final int SIZE = JBUI.scale(22);
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
                int arc = JBUI.scale(6);
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
        private static final int DOT = JBUI.scale(6);
        private static final int GAP = JBUI.scale(5);
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
