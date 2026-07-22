package com.petros.ireview;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.List;

/**
 * Moves the IDE to a step's anchor. Split from the controller so the walking
 * logic is testable without an IDE: tests pass a recording implementation.
 */
public interface WalkthroughNavigator {

    void navigate(WalkthroughStep step);

    /**
     * Re-locate a step in a document by its snippet. The recorded line is only a
     * hint — {@link AnchorResolver} searches a window around it and reports
     * EXACT / MOVED / STALE. Pure; unit-tested directly.
     */
    static AnchorResolver.Resolution resolveLine(List<String> documentLines, WalkthroughStep step) {
        return AnchorResolver.resolve(documentLines, step.line(), step.snippet(),
            AnchorResolver.DEFAULT_K);
    }

    /** Resolves a step's project-relative path to the {@link VirtualFile} it names, or null. */
    static VirtualFile resolveStepFile(Project project, WalkthroughStep step) {
        if (project == null || step == null || project.getBaseDir() == null) return null;
        return project.getBaseDir().findFileByRelativePath(step.file());
    }

    /**
     * True when {@code vf} is the file this step anchors to. Resolves the step's
     * project-relative path against the project root and compares identity —
     * a raw {@code endsWith} on paths would match `other/module/src/Api.java`
     * for a step anchored at `src/Api.java`.
     */
    static boolean isStepFile(Project project, VirtualFile vf, WalkthroughStep step) {
        if (vf == null) return false;
        VirtualFile stepFile = resolveStepFile(project, step);
        return stepFile != null && stepFile.equals(vf);
    }

    /** Opens the step's file, re-resolves the anchor, scrolls and places the caret. */
    final class Ide implements WalkthroughNavigator {
        private final Project project;

        public Ide(Project project) { this.project = project; }

        @Override public void navigate(WalkthroughStep step) {
            VirtualFile vf = resolveStepFile(project, step);
            if (vf == null || vf.isDirectory()) return;
            com.intellij.openapi.editor.Document doc =
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
            int line = step.line();
            if (doc != null) {
                List<String> lines = List.of(doc.getText().split("\n", -1));
                AnchorResolver.Resolution res = resolveLine(lines, step);
                if (res.kind() != AnchorResolver.Kind.STALE) line = res.line();
            }
            int line0 = Math.max(0, line - 1);
            OpenFileDescriptor descriptor = new OpenFileDescriptor(project, vf, line0, 0);
            Editor editor = FileEditorManager.getInstance(project)
                .openTextEditor(descriptor, true);
            if (editor != null) {
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            }
        }
    }
}
