package com.petros.ireview;

import com.google.gson.Gson;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import java.awt.Color;
import java.awt.Dimension;
import java.util.List;

/**
 * Interactive shortcut cheat-sheet. View mode is read-only with an Edit button;
 * Edit mode is a checklist whose toggles/category changes post through a
 * {@link JBCefJSQuery} bridge into {@link ShortcutPrefs} (persisted immediately).
 * JCEF-gated; the {@link JEditorPane} fallback is view-only.
 */
public final class ShortcutsOverlay extends DialogWrapper {

    private final Project project;
    private final ShortcutPrefs prefs;
    private final Gson gson = new Gson();
    private JBCefBrowser browser;
    private JBCefJSQuery query;

    public ShortcutsOverlay(@Nullable Project project, ShortcutPrefs prefs) {
        super(project, false);
        this.project = project;
        this.prefs = prefs;
        setTitle("Keyboard Shortcuts");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        if (JBCefApp.isSupported()) {
            browser = new JBCefBrowser();
            Disposer.register(getDisposable(), browser);
            query = JBCefJSQuery.create((JBCefBrowserBase) browser);
            query.addHandler(payload -> {
                ApplicationManager.getApplication().invokeLater(() -> handle(payload));
                return new JBCefJSQuery.Response(null);
            });
            renderView();
            JComponent c = browser.getComponent();
            c.setPreferredSize(new Dimension(940, 600));
            return c;
        }
        // Fallback: view-only (no bridge available in Swing HTML).
        ResolvedSheet sheet = ViewModelBuilder.build(ShortcutCatalog.build(new IdeKeymapCatalog()), prefs);
        JEditorPane pane = new JEditorPane("text/html", ShortcutsHtmlRenderer.toDocument(sheet, isDark()));
        pane.setEditable(false);
        JBScrollPane scroll = new JBScrollPane(pane);
        scroll.setPreferredSize(new Dimension(760, 600));
        return scroll;
    }

    private String bridge() {
        return "function ireviewSend(json){" + query.inject("json") + "}";
    }

    private void renderView() {
        ResolvedSheet sheet = ViewModelBuilder.build(ShortcutCatalog.build(new IdeKeymapCatalog()), prefs);
        browser.loadHTML(ShortcutsHtmlRenderer.renderView(sheet, isDark(), true, bridge()));
    }

    private void renderEdit() {
        EditSheet sheet = EditModelBuilder.build(ShortcutCatalog.build(new IdeKeymapCatalog()), prefs);
        browser.loadHTML(ShortcutsHtmlRenderer.renderEdit(sheet, isDark(), bridge()));
    }

    private void handle(String payloadJson) {
        if (browser == null || Disposer.isDisposed(getDisposable())) return;
        Msg m;
        try { m = gson.fromJson(payloadJson, Msg.class); } catch (Exception e) { return; }
        if (m == null || m.type == null) return;
        switch (m.type) {
            case "enterEdit" -> renderEdit();
            case "exitEdit"  -> renderView();
            case "toggle"    -> { if (m.id != null) prefs.setEnabled(m.id, m.on); }   // no re-render
            case "setCategory" -> { if (m.id != null) prefs.setCategory(m.id, m.category); }
            case "newCategory" -> {
                String name = Messages.showInputDialog(project, "New category name:", "New Category", null);
                if (name != null && !name.isBlank() && m.id != null) prefs.setCategory(m.id, name.trim());
                renderEdit();   // reflect the new option (or reset the <select> if cancelled)
            }
            default -> { /* ignore unknown */ }
        }
    }

    /** Bridge message shape. */
    private static final class Msg {
        String type;
        String id;
        boolean on;
        String category;
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
