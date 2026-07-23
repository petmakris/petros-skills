package com.petros.ireview;

import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.Timer;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Puts the ask-Claude gutter on every regular file editor in the project, not only
 * on diff viewers — so a question can be asked from wherever the code is being read.
 * Icons appear only while a review session is live.
 */
public final class WorkingCopyAskGutter implements EditorFactoryListener {

    private static final int REATTACH_DELAY_MS = 400;

    private record Attachment(DocumentListener listener, Timer debounce) {}

    private static final Map<EditorEx, Attachment> ATTACHED = new WeakHashMap<>();

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        if (!(event.getEditor() instanceof EditorEx editor)) return;
        // Diff editors are handled by SpikeDiffExtension with side-aware anchors;
        // attaching here too would double every marker.
        if (editor.getEditorKind() != EditorKind.MAIN_EDITOR) return;
        Project project = editor.getProject();
        if (project == null || ATTACHED.containsKey(editor)) return;

        String label = projectRelativePath(editor, project);
        if (label == null) return;

        // Edits shift lines under the fixed-line markers, so rebuild shortly after
        // typing pauses rather than on every keystroke.
        Timer debounce = new Timer(REATTACH_DELAY_MS,
            e -> SpikeDiffExtension.attachWorkingCopyLines(editor, label, project));
        debounce.setRepeats(false);
        DocumentListener onEdit = new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent e) {
                debounce.restart();
            }
        };
        editor.getDocument().addDocumentListener(onEdit);
        ATTACHED.put(editor, new Attachment(onEdit, debounce));

        SpikeDiffExtension.attachWorkingCopy(editor, label, project);
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        if (!(event.getEditor() instanceof EditorEx editor)) return;
        Attachment attachment = ATTACHED.remove(editor);
        if (attachment == null) return;
        attachment.debounce().stop();
        editor.getDocument().removeDocumentListener(attachment.listener());
    }

    private static String projectRelativePath(EditorEx editor, Project project) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        String base = project.getBasePath();
        if (file == null || base == null) return null;
        String path = file.getPath();
        return path.startsWith(base + "/") ? path.substring(base.length() + 1) : null;
    }
}
