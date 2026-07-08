package com.petros.ireview;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Map;
import java.util.function.Function;

/**
 * Keyboard cheat-sheet overlay. View mode renders as HTML in a {@link JBCefBrowser}
 * (display-only — no JS bridge). Edit mode is a native-Swing {@link ShortcutEditPanel},
 * because the JCEF {@code JBCefJSQuery} JS→Java bridge is unreliable under the IDE's
 * out-of-process JCEF. A native toolbar button toggles between the two; Esc closes.
 */
public final class ShortcutsOverlay extends DialogWrapper {

    private final Project project;
    private final ShortcutPrefs prefs;

    private JBCefBrowser browser;      // view component (JCEF), when supported
    private JEditorPane fallbackPane;  // view component (Swing), when JCEF is unavailable
    private JComponent viewComponent;  // whichever of the two is in use
    private JPanel holder;             // center; swaps between view and edit
    private JButton toggle;
    private boolean editing = false;

    /** actionId → IntelliJ category, derived once per open (stable while the dialog is up). */
    private final Function<String, String> categoryOf;

    public ShortcutsOverlay(@Nullable Project project, ShortcutPrefs prefs) {
        super(project, false);
        this.project = project;
        this.prefs = prefs;
        Map<String, String> categories = ShortcutCategories.byAction(project);
        this.categoryOf = id -> categories.getOrDefault(id, ShortcutCategories.OTHER);
        setTitle("Keyboard Shortcuts");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout());

        toggle = new JButton("✎ Edit");   // ✎ Edit
        toggle.addActionListener(a -> toggleMode());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bar.setBorder(JBUI.Borders.emptyRight(6));
        bar.add(toggle);
        root.add(bar, BorderLayout.NORTH);

        viewComponent = buildViewComponent();
        holder = new JPanel(new BorderLayout());
        holder.add(viewComponent, BorderLayout.CENTER);
        root.add(holder, BorderLayout.CENTER);

        renderViewContent();
        root.setPreferredSize(new Dimension(940, 620));
        return root;
    }

    private JComponent buildViewComponent() {
        if (JBCefApp.isSupported()) {
            browser = new JBCefBrowser();
            Disposer.register(getDisposable(), browser);
            return browser.getComponent();
        }
        fallbackPane = new JEditorPane("text/html", "");
        fallbackPane.setEditable(false);
        return new JBScrollPane(fallbackPane);
    }

    private void renderViewContent() {
        ResolvedSheet sheet = ViewModelBuilder.build(ShortcutCatalog.build(new IdeKeymapCatalog()), prefs, categoryOf);
        String html = ShortcutsHtmlRenderer.renderView(sheet, isDark(), false, "");
        if (browser != null) {
            browser.loadHTML(html);
        } else if (fallbackPane != null) {
            fallbackPane.setText(html);
            fallbackPane.setCaretPosition(0);
        }
    }

    private void toggleMode() {
        editing = !editing;
        holder.removeAll();
        if (editing) {
            ShortcutEditPanel edit = new ShortcutEditPanel(
                    prefs, ShortcutCatalog.build(new IdeKeymapCatalog()), categoryOf);
            holder.add(edit, BorderLayout.CENTER);
            toggle.setText("✓ Done");   // ✓ Done
        } else {
            renderViewContent();             // reflect the edits just made
            holder.add(viewComponent, BorderLayout.CENTER);
            toggle.setText("✎ Edit");
        }
        holder.revalidate();
        holder.repaint();
    }

    @Override
    protected Action[] createActions() {
        return new Action[0]; // no OK/Cancel; Esc dismisses
    }

    private static boolean isDark() {
        Color bg = EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground();
        return (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) < 128;
    }
}
