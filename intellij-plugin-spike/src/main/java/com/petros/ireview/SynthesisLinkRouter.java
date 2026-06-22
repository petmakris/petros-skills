package com.petros.ireview;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Routes a clicked link href (from the JCEF page or the fallback JEditorPane)
 * to the right IDE action. Shared so the two render paths cannot drift.
 */
public final class SynthesisLinkRouter {

    public enum Kind { NAV, SYM, EXTERNAL, NONE }

    public record LinkAction(Kind kind, String payload) {}

    private static final String NAV = "ireview-nav://";
    private static final String SYM = "ireview-sym://";

    public static LinkAction classify(String href) {
        if (href == null) return new LinkAction(Kind.NONE, "");
        if (href.startsWith(NAV)) return new LinkAction(Kind.NAV, href.substring(NAV.length()));
        if (href.startsWith(SYM)) return new LinkAction(Kind.SYM, href.substring(SYM.length()));
        if (href.startsWith("http://") || href.startsWith("https://")) return new LinkAction(Kind.EXTERNAL, href);
        return new LinkAction(Kind.NONE, href);
    }

    public static void route(@NotNull Project project, String href) {
        LinkAction action = classify(href);
        switch (action.kind()) {
            case NAV -> navigate(project, action.payload());
            case SYM -> resolveAndNavigateSymbol(project, action.payload());
            case EXTERNAL -> BrowserUtil.browse(action.payload());
            case NONE -> { }
        }
    }

    private static void navigate(Project project, String payload) {
        MarkdownLinkRenderer.NavTarget t = MarkdownLinkRenderer.parseNavTarget(NAV + payload);
        String base = project.getBasePath();
        if (base == null) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(base + "/" + t.path());
        if (vf == null) return;
        int line0 = Math.max(0, t.line() - 1);
        new OpenFileDescriptor(project, vf, line0, 0).navigate(true);
    }

    /**
     * Look up `identifier` in the project's PSI symbol caches. If exactly one
     * class or method matches, navigate to it. If multiple, show a chooser
     * popup. If none, silent no-op.
     */
    private static void resolveAndNavigateSymbol(Project project, String identifier) {
        ReadAction.nonBlocking(() -> {
            var cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project);
            var scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project);
            com.intellij.psi.PsiNamedElement[] candidates = cache.getClassesByName(identifier, scope);
            if (candidates.length == 0) {
                candidates = cache.getMethodsByName(identifier, scope);
            }
            return candidates;
        })
        .finishOnUiThread(
            com.intellij.openapi.application.ModalityState.defaultModalityState(),
            candidates -> {
                if (candidates.length == 0) {
                    return;
                }
                if (candidates.length == 1) {
                    if (candidates[0] instanceof com.intellij.pom.Navigatable nav) {
                        nav.navigate(true);
                    }
                    return;
                }
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(java.util.Arrays.asList(candidates))
                    .setTitle("Multiple matches for '" + identifier + "'")
                    .setItemChosenCallback(item -> {
                        if (item instanceof com.intellij.pom.Navigatable nav) {
                            nav.navigate(true);
                        }
                    })
                    .createPopup()
                    .showCenteredInCurrentWindow(project);
            })
        .submit(AppExecutorUtil.getAppExecutorService());
    }

    private SynthesisLinkRouter() {}
}
