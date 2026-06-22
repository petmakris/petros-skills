package com.petros.ireview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Color;

/**
 * Embedded Chromium (JCEF) view of the synthesis markdown. Renders via
 * {@link SynthesisHtmlRenderer} and intercepts every <a> click through a
 * {@link JBCefJSQuery} bridge, routing the href via {@link SynthesisLinkRouter}.
 *
 * Only constructed when {@code JBCefApp.isSupported()}; the popup falls back to
 * the JEditorPane renderer otherwise.
 */
public final class SynthesisBrowser implements Disposable {

    private final JBCefBrowser browser;
    private final String navScript;

    public SynthesisBrowser(@NotNull Project project) {
        this.browser = new JBCefBrowser();
        Disposer.register(this, browser);

        JBCefJSQuery linkQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        linkQuery.addHandler(href -> {
            SwingUtilities.invokeLater(() -> SynthesisLinkRouter.route(project, href));
            return new JBCefJSQuery.Response(null);
        });

        // Intercept all link clicks; getAttribute('href') returns the raw,
        // un-resolved scheme value (e.g. ireview-sym://Foo), not a percent-
        // encoded absolute URL.
        this.navScript =
            "document.addEventListener('click',function(e){"
          + "var a=e.target.closest('a');if(!a)return;"
          + "e.preventDefault();"
          + "var href=a.getAttribute('href');"
          + linkQuery.inject("href")
          + "},true);";
    }

    public JComponent getComponent() {
        return browser.getComponent();
    }

    public void render(@NotNull String markdown) {
        browser.loadHTML(SynthesisHtmlRenderer.toDocument(markdown, currentTheme(), navScript));
    }

    @Override
    public void dispose() {
        // browser (and the JS query created from it) are disposed via Disposer.
    }

    private static SynthesisHtmlRenderer.Theme currentTheme() {
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        String bg = hex(scheme.getDefaultBackground());
        String fg = hex(scheme.getDefaultForeground());
        String font = scheme.getEditorFontName();
        int size = Math.max(11, scheme.getEditorFontSize());
        String codeBg = hex(shift(scheme.getDefaultBackground(), 12));
        String border = hex(shift(scheme.getDefaultBackground(), 36));
        return new SynthesisHtmlRenderer.Theme(bg, fg, "'" + font + "'", size, "#4f83ed", codeBg, border);
    }

    /** Nudge a color lighter (dark themes) or darker (light themes) by delta. */
    private static Color shift(Color c, int delta) {
        boolean dark = (c.getRed() + c.getGreen() + c.getBlue()) / 3 < 128;
        int d = dark ? delta : -delta;
        return new Color(clamp(c.getRed() + d), clamp(c.getGreen() + d), clamp(c.getBlue() + d));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
