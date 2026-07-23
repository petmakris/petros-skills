package com.petros.ireview;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a step's anchor to the enclosing symbol for the rail's chip —
 * {@code ClassName.methodName()}, {@code ClassName} (field/enum
 * constant/import — no enclosing method), or a {@code FileName.java:123}
 * fallback for anything unresolved (non-Java file, no PSI, indexing, a
 * blank/whitespace-only anchor line).
 *
 * <p>{@link #describe} re-resolves the anchor line with
 * {@link WalkthroughNavigator#resolveLine} first — same as
 * {@link WalkthroughInlay} and {@link WalkthroughGutter} — so a drifted
 * anchor still names the symbol the step is actually pointing at today, not
 * whatever used to be on the recorded line number.
 */
public final class WalkthroughSymbols {

    private WalkthroughSymbols() {}

    /**
     * {@code rebuild()} runs on every client event (thread updates, pending
     * flips, mode changes …) and every rebuild redraws every row — a PSI
     * lookup per row per rebuild would make the rail sluggish on a large
     * project. Keyed on file + resolved line + the document's modification
     * stamp, so an edit anywhere in the file (which bumps the stamp)
     * invalidates every entry for that file rather than serving a stale
     * symbol. Bounded with a hand-rolled LRU (access-order
     * {@link LinkedHashMap} + {@code removeEldestEntry}) so a long session
     * across many files can't grow this unboundedly.
     */
    private static final int MAX_ENTRIES = 300;
    private static final Map<CacheKey, String> CACHE =
        java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<CacheKey, String> eldest) {
                return size() > MAX_ENTRIES;
            }
        });

    private record CacheKey(VirtualFile file, int line, long modStamp) {}

    public static String describe(Project project, WalkthroughStep step) {
        String recordedFallback = fallback(step, step.line());
        if (project == null || project.isDisposed() || step == null) return recordedFallback;

        VirtualFile vf = WalkthroughNavigator.resolveStepFile(project, step);
        if (vf == null || vf.isDirectory()) return recordedFallback;
        // PSI is unreliable mid-index and must never be touched while dumb.
        if (DumbService.isDumb(project)) return recordedFallback;

        return ReadAction.compute(() -> {
            Document document = FileDocumentManager.getInstance().getDocument(vf);
            if (document == null) return recordedFallback;
            List<String> lines = List.of(document.getText().split("\n", -1));
            AnchorResolver.Resolution res = WalkthroughNavigator.resolveLine(lines, step);
            int line = res.kind() == AnchorResolver.Kind.STALE ? step.line() : res.line();
            String resolvedFallback = fallback(step, line);

            CacheKey key = new CacheKey(vf, line, document.getModificationStamp());
            String cached = CACHE.get(key);
            if (cached != null) return cached;

            String computed = resolveSymbol(project, vf, document, line, resolvedFallback);
            CACHE.put(key, computed);
            return computed;
        });
    }

    /** Must run inside a read action — touches PSI. */
    private static String resolveSymbol(Project project, VirtualFile vf, Document document,
                                         int line1Based, String fallback) {
        if (!"java".equalsIgnoreCase(vf.getExtension())) return fallback;
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return fallback;

        int lineCount = document.getLineCount();
        if (lineCount == 0) return fallback;
        int line0 = Math.max(0, Math.min(line1Based - 1, lineCount - 1));
        int start = document.getLineStartOffset(line0);
        int end = document.getLineEndOffset(line0);

        PsiElement el = firstNonWhitespaceElement(psiFile, start, end);
        if (el == null) return fallback;

        PsiMethod method = PsiTreeUtil.getParentOfType(el, PsiMethod.class);
        PsiClass cls = PsiTreeUtil.getParentOfType(el, PsiClass.class, false);
        // Anonymous/local classes have no name — bubble up to the nearest
        // named enclosing class so an anchor inside an anonymous listener
        // still names something a user recognises.
        while (cls != null && cls.getName() == null) {
            cls = PsiTreeUtil.getParentOfType(cls, PsiClass.class);
        }
        if (cls == null) return fallback;
        String className = cls.getName();
        if (className == null) return fallback;

        if (method != null && method.getName() != null) {
            return className + "." + method.getName() + "()";
        }
        return className;
    }

    /** Scans the anchor line left to right for the first non-whitespace PSI leaf. */
    private static PsiElement firstNonWhitespaceElement(PsiFile psiFile, int start, int end) {
        for (int offset = start; offset < end; offset++) {
            PsiElement candidate = psiFile.findElementAt(offset);
            if (candidate != null && !(candidate instanceof PsiWhiteSpace)) {
                return candidate;
            }
        }
        // Blank line: fall back to whatever sits at the line end (e.g. a
        // closing brace on its own line, or null for a genuinely empty file).
        return end > start ? psiFile.findElementAt(end - 1) : null;
    }

    private static String fallback(WalkthroughStep step, int line) {
        String file = step.file();
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String name = slash >= 0 ? file.substring(slash + 1) : file;
        return name + ":" + line;
    }
}
