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

    /** Opens the step's file, re-resolves the anchor, scrolls and places the caret. */
    final class Ide implements WalkthroughNavigator {
        private final Project project;

        public Ide(Project project) { this.project = project; }

        @Override public void navigate(WalkthroughStep step) {
            VirtualFile vf = project.getBaseDir() == null
                ? null
                : project.getBaseDir().findFileByRelativePath(step.file());
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
