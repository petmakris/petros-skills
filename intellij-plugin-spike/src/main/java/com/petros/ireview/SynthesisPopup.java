package com.petros.ireview;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Inline popup anchored to a diff line. Shows the current synthesis for the
 * anchor (or "no annotation yet") and lets the user submit a follow-up
 * question. Re-rendering on synthesis updates is handled by registering a
 * listener on ReviewSessionClient that lives as long as the popup is open.
 */
public final class SynthesisPopup {

    /** One open popup per anchor; opening a new one cancels the previous. */
    private static final java.util.Map<String, JBPopup> OPEN_POPUPS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void show(@NotNull Project project,
                            @NotNull EditorEx editor,
                            @NotNull String anchor,
                            int visualLine) {
        ReviewSessionClient client = ReviewSessionService.get(project).client();

        // Dedupe: if there's already a popup open for this anchor, close it
        // before opening a new one (gives the user the new screen position).
        JBPopup existing = OPEN_POPUPS.remove(anchor);
        if (existing != null && !existing.isDisposed()) {
            existing.cancel();
        }

        AtomicReference<Boolean> thinking = new AtomicReference<>(false);
        AtomicReference<JBPopup> popupRef = new AtomicReference<>();

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(JBUI.Borders.empty(4, 6));
        content.setPreferredSize(new Dimension(480, 200));

        // Make any non-input area of the popup draggable. Without a title bar,
        // there's nothing else to grab. We install a drag listener on the
        // content panel and the header — clicks on the synthesisPane / input /
        // buttons still get their own events (this listener fires on the
        // background, not on child components).
        java.awt.event.MouseAdapter dragger = new java.awt.event.MouseAdapter() {
            java.awt.Point pressOnScreen;
            java.awt.Point windowAtPress;
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                pressOnScreen = e.getLocationOnScreen();
                java.awt.Window w = SwingUtilities.getWindowAncestor(e.getComponent());
                if (w != null) windowAtPress = w.getLocation();
            }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                if (pressOnScreen == null || windowAtPress == null) return;
                java.awt.Window w = SwingUtilities.getWindowAncestor(e.getComponent());
                if (w == null) return;
                java.awt.Point now = e.getLocationOnScreen();
                w.setLocation(windowAtPress.x + (now.x - pressOnScreen.x),
                              windowAtPress.y + (now.y - pressOnScreen.y));
            }
        };
        content.addMouseListener(dragger);
        content.addMouseMotionListener(dragger);

        // Header: native-feeling close button on the right (uses IDEA's standard
        // close icon — themed, has hover/pressed states out of the box).
        JPanel headerRow = new JPanel(new BorderLayout());
        com.intellij.ui.InplaceButton dismissBtn = new com.intellij.ui.InplaceButton(
            new com.intellij.openapi.ui.popup.IconButton(
                "Close annotation",
                com.intellij.icons.AllIcons.Actions.Close,
                com.intellij.icons.AllIcons.Actions.CloseHovered),
            e -> {
                JBPopup p = popupRef.get();
                if (p != null) p.cancel();
            }
        );
        // Pad the button so its hit target is larger than the 16px icon.
        JPanel dismissWrap = new JPanel(new BorderLayout());
        dismissWrap.setOpaque(false);
        dismissWrap.setBorder(JBUI.Borders.empty(2, 4, 2, 2));
        dismissWrap.add(dismissBtn, BorderLayout.CENTER);
        headerRow.add(dismissWrap, BorderLayout.EAST);
        // Version label on the LEFT side of the header (replaces the
        // bottom-row placement; bottom row is now flush against the input).
        JLabel headerVersion = new JLabel(pluginVersionLabel());
        headerVersion.setFont(headerVersion.getFont().deriveFont(java.awt.Font.PLAIN, 10f));
        headerVersion.setForeground(new JBColor(new java.awt.Color(0xa0, 0xa0, 0xa0), new java.awt.Color(0x6a, 0x6e, 0x75)));
        headerVersion.setBorder(JBUI.Borders.empty(0, 6, 0, 0));
        headerRow.add(headerVersion, BorderLayout.WEST);
        headerRow.addMouseListener(dragger);
        headerRow.addMouseMotionListener(dragger);
        content.add(headerRow, BorderLayout.NORTH);

        JEditorPane synthesisPane = new JEditorPane("text/html", "");
        synthesisPane.setEditable(false);
        synthesisPane.setOpaque(false);
        synthesisPane.setBorder(JBUI.Borders.empty(2, 4));

        synthesisPane.addHyperlinkListener(e -> {
            if (e.getEventType() != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) return;
            SynthesisLinkRouter.route(project, e.getDescription());
        });

        JScrollPane synthesisScroll = new JScrollPane(synthesisPane);
        synthesisScroll.setBorder(BorderFactory.createLineBorder(JBColor.border(), 1, true));
        synthesisScroll.setPreferredSize(new Dimension(520, 130));

        // "Thinking" card: AsyncProcessIcon (purpose-built self-animating spinner)
        // + label, centered in the synthesis area.
        JPanel thinkingCard = new JPanel(new java.awt.GridBagLayout());
        thinkingCard.setBorder(BorderFactory.createLineBorder(JBColor.border(), 1, true));
        JPanel thinkingInner = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 8, 0));
        thinkingInner.setOpaque(false);
        com.intellij.util.ui.AsyncProcessIcon spinner = new com.intellij.util.ui.AsyncProcessIcon("thinking");
        spinner.resume();  // start animating immediately; it's cheap
        JLabel thinkingText = new JLabel("Claude is thinking…");
        thinkingText.setForeground(JBColor.GRAY);
        thinkingInner.add(spinner);
        thinkingInner.add(thinkingText);
        thinkingCard.add(thinkingInner);

        // CardLayout swap between synthesis content and thinking spinner.
        // Prefer JCEF (real browser) for the synthesis card; fall back to the
        // JEditorPane (synthesisScroll) when JCEF is unavailable.
        final SynthesisBrowser browser = JBCefApp.isSupported() ? new SynthesisBrowser(project) : null;
        JComponent synthesisCard = browser != null ? (JComponent) browser.getComponent() : synthesisScroll;
        java.awt.CardLayout cards = new java.awt.CardLayout();
        JPanel centerCards = new JPanel(cards);
        centerCards.add(synthesisCard, "synthesis");
        centerCards.add(thinkingCard, "thinking");
        centerCards.setPreferredSize(new Dimension(520, 130));

        JTextArea input = new JTextArea(2, 50);
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        JScrollPane inputScroll = new JScrollPane(input);
        inputScroll.setBorder(BorderFactory.createLineBorder(JBColor.border(), 1, true));

        // renderCurrent captures synthesisPane/browser and thinking; called on EDT.
        Runnable renderCurrent = () -> {
            if (thinking.get()) {
                cards.show(centerCards, "thinking");
                return;
            }
            cards.show(centerCards, "synthesis");
            var cached = client.threadFor(anchor);
            if (browser != null) {
                browser.render(cached.isEmpty()
                    ? "*No annotation yet. Ask a question to start.*"
                    : cached.get().synthesis());
                return;
            }
            if (cached.isEmpty()) {
                synthesisPane.setText(wrapHtml("<i style='color:#7a7e85'>No annotation yet. Ask a question to start.</i>"));
            } else {
                synthesisPane.setText(wrapHtml(MarkdownLinkRenderer.toHtml(cached.get().synthesis())));
            }
            synthesisPane.setCaretPosition(0);
        };
        renderCurrent.run();

        JButton askBtn = makeAccentButton("Ask");
        askBtn.setMnemonic(KeyEvent.VK_A);
        Runnable submit = () -> {
            String q = input.getText().trim();
            if (q.isEmpty()) return;
            input.setText("");
            thinking.set(true);
            renderCurrent.run();
            String anchorText = lineTextAt(editor.getDocument(), visualLine);
            client.postComment(anchor, q, anchorText).whenComplete((v, t) -> SwingUtilities.invokeLater(() -> {
                if (t != null) {
                    thinking.set(false);
                    cards.show(centerCards, "synthesis");
                    if (browser != null) {
                        browser.render("**Failed to submit — retry?**");
                    } else {
                        synthesisPane.setText(wrapHtml(
                            "<span style='color:#cc6666'>Failed to submit — retry?</span>"));
                    }
                }
                // On success, do nothing; the SSE thread-changed event will call
                // onThreadChanged, which sets thinking=false and re-renders.
            }));
        };
        askBtn.addActionListener(e -> submit.run());

        // Cmd/Ctrl+Enter submits.
        input.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), "submit");
        input.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "submit");
        input.getActionMap().put("submit", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { submit.run(); }
        });

        // Compact south region: input row + Ask button side-by-side.
        // No "Ask a question (Cmd/Ctrl-Enter)" label — use placeholder text instead.
        // Ask button is small, no label.
        input.setRows(1);
        input.setBorder(JBUI.Borders.empty(2, 6));
        askBtn.setMargin(JBUI.emptyInsets());
        askBtn.setBorder(JBUI.Borders.empty(0, 10));

        JPanel south = new JPanel(new BorderLayout(4, 0));
        south.setBorder(JBUI.Borders.emptyTop(4));
        south.add(inputScroll, BorderLayout.CENTER);
        south.add(askBtn, BorderLayout.EAST);

        content.add(centerCards, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);

        // SSE listener: when the thread for OUR anchor updates, re-render.
        // When it's deleted, dismiss the popup so the user doesn't keep
        // interacting with a gone thread.
        // KNOWN MINOR LEAK: ReviewSessionClient has no removeListener, so this
        // listener outlives the popup. For long-running IDE sessions with many
        // popups this is a leak; acceptable for v1, fix in a follow-up.
        ReviewSessionClient.Listener listener = new ReviewSessionClient.Listener() {
            @Override
            public void onThreadChanged(String changedAnchor, String synthesis, int version) {
                if (!changedAnchor.equals(anchor)) return;
                SwingUtilities.invokeLater(() -> {
                    thinking.set(false);
                    renderCurrent.run();
                });
            }
            @Override
            public void onThreadDeleted(String deletedAnchor) {
                if (!deletedAnchor.equals(anchor)) return;
                SwingUtilities.invokeLater(() -> {
                    JBPopup p = popupRef.get();
                    if (p != null && !p.isDisposed()) p.cancel();
                });
            }
        };
        client.addListener(listener);

        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, input)
                .setRequestFocus(true)
                .setMovable(true)
                .setResizable(true)
                .setCancelOnClickOutside(false)
                .setCancelOnOtherWindowOpen(false)
                .setCancelOnWindowDeactivation(false)
                .setCancelKeyEnabled(false)  // Esc no longer dismisses — must click ✕
                // No setTitle → no native title bar. The custom MouseAdapter
                // dragger above makes the popup draggable from any background
                // surface in the content/header panels.
                .createPopup();
        popupRef.set(popup);
        if (browser != null) Disposer.register(popup, browser);
        OPEN_POPUPS.put(anchor, popup);
        popup.addListener(new com.intellij.openapi.ui.popup.JBPopupListener() {
            @Override public void onClosed(@NotNull com.intellij.openapi.ui.popup.LightweightWindowEvent e) {
                OPEN_POPUPS.remove(anchor, popup);
            }
        });
        popup.showInBestPositionFor(editor);
    }

    private static String lineTextAt(Document doc, int line0) {
        if (line0 < 0 || line0 >= doc.getLineCount()) return "";
        int s = doc.getLineStartOffset(line0);
        int en = doc.getLineEndOffset(line0);
        return doc.getText(new TextRange(s, en));
    }

    private static String wrapHtml(String body) {
        // Pick up the IDE editor font + size so the popup blends with the code.
        var scheme = com.intellij.openapi.editor.colors.EditorColorsManager
                .getInstance().getGlobalScheme();
        String editorFont = scheme.getEditorFontName();
        int editorFontSize = Math.max(10, scheme.getEditorFontSize() - 1); // a hair smaller than the editor

        // Single-quote-escape font name in case it contains apostrophes.
        String fontFamily = "'" + editorFont.replace("'", "\\'") + "', monospace";

        return "<html><head><style>"
             + "body { font-family: " + fontFamily
             +        "; color: #d8d8d8; font-size: " + editorFontSize + "px;"
             +        " line-height: 1.45; margin: 0; padding: 4px 6px; }"
             + "b { color: #e4e4e4; }"
             + "a.ref-code { color: #ce9178; text-decoration: none; }"
             + "a.ref-ticket { color: #b5b6e3; text-decoration: none; }"
             + "a.ref-sym { color: #ce9178; text-decoration: none; }"
             + "a.ref-sym:hover { text-decoration: underline dashed; }"
             + "pre.code-block { background: #1e1f22; color: #d8d8d8; padding: 6px 10px;"
             +                 " margin: 4px 0; border: 1px solid #393b40;"
             +                 " font-size: " + (editorFontSize - 1) + "px; }"
             + "</style></head><body>"
             + body + "</body></html>";
    }

    /**
     * Build a button that visibly looks like a button: explicit accent
     * background, white text, hover/pressed states. Bypasses macOS Aqua
     * L&F which silently ignores setBackground on stock JButtons.
     */
    private static JButton makeAccentButton(String text) {
        final java.awt.Color base = new java.awt.Color(0x3b, 0x72, 0xe8);
        final java.awt.Color hover = new java.awt.Color(0x4f, 0x83, 0xed);
        final java.awt.Color pressed = new java.awt.Color(0x2c, 0x5f, 0xd0);
        JButton b = new JButton(text);
        b.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setBackground(base);
        b.setForeground(java.awt.Color.WHITE);
        b.setFont(b.getFont().deriveFont(java.awt.Font.BOLD));
        b.setBorder(JBUI.Borders.empty(6, 18));
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        b.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { b.setBackground(hover); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { b.setBackground(base); }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { b.setBackground(pressed); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                b.setBackground(b.contains(e.getPoint()) ? hover : base);
            }
        });
        return b;
    }

    /**
     * Return "v<version>" derived from the plugin descriptor in plugin.xml.
     * The version uses the running commit count, so v0.1.42 is one commit
     * ahead of v0.1.41 — easy to reason about.
     */
    private static String pluginVersionLabel() {
        try {
            var pluginId = com.intellij.openapi.extensions.PluginId.getId("com.petros.interactive-review-spike");
            var descriptor = com.intellij.ide.plugins.PluginManagerCore.getPlugin(pluginId);
            String version = descriptor != null ? descriptor.getVersion() : null;
            return version != null ? "v" + version : "v?";
        } catch (Throwable t) {
            return "v?";
        }
    }

    private SynthesisPopup() {}
}
