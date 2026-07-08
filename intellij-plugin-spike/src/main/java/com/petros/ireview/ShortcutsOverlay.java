package com.petros.ireview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import java.awt.Dimension;

/**
 * Read-only shortcut cheat-sheet. Renders {@code html} in a {@link JBCefBrowser}
 * when JCEF is supported (matching {@code SynthesisPopup}); otherwise falls back
 * to a {@link JEditorPane}. Esc closes (free from {@link DialogWrapper}).
 */
public final class ShortcutsOverlay extends DialogWrapper {

    private final String html;

    public ShortcutsOverlay(@Nullable Project project, String html) {
        super(project, false);
        this.html = html;
        setTitle("Keyboard Shortcuts");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        if (JBCefApp.isSupported()) {
            JBCefBrowser browser = new JBCefBrowser();
            Disposer.register(getDisposable(), browser);
            browser.loadHTML(html);
            JComponent component = browser.getComponent();
            component.setPreferredSize(new Dimension(920, 560));
            return component;
        }
        JEditorPane pane = new JEditorPane("text/html", html);
        pane.setEditable(false);
        JBScrollPane scroll = new JBScrollPane(pane);
        scroll.setPreferredSize(new Dimension(720, 560));
        return scroll;
    }

    @Override
    protected Action[] createActions() {
        return new Action[0]; // no OK/Cancel row; Esc dismisses
    }
}
