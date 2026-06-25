package com.petros.ireview;

import com.intellij.diff.DiffContext;
import com.intellij.diff.DiffExtension;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.project.Project;
import com.intellij.util.ui.EmptyIcon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public final class SpikeDiffExtension extends DiffExtension {

    private static final Logger LOG = Logger.getInstance(SpikeDiffExtension.class);
    private static final Icon ASK_ICON = AllIcons.General.Add;
    private static final Icon ANNOTATED_ICON =
            IconLoader.getIcon("/icons/annotation_yellow.svg", SpikeDiffExtension.class);
    private static final Icon STALE_ICON =
            IconLoader.getIcon("/icons/annotation_stale.svg", SpikeDiffExtension.class);
    private static final Icon HIDDEN_ICON = EmptyIcon.ICON_16;

    /** Per-editor "currently hovered line index" (0-based), or -1. */
    private static final Map<EditorEx, Integer> HOVERED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Registry mapping `<project-relative-path>:<L|R>` to the diff-viewer editor
     * currently showing that side. Used by the side-panel: clicking a row
     * focuses the original diff viewer (keeps PR context) instead of opening
     * the working-copy file.
     *
     * Stores WeakReference so closed diff viewers don't leak. The
     * `editorFor(pathSideKey)` accessor returns null if the entry is GC'd or
     * the editor is disposed.
     */
    private static final Map<String, java.lang.ref.WeakReference<EditorEx>> DIFF_EDITORS =
            Collections.synchronizedMap(new java.util.HashMap<>());

    /** Public lookup for {@link AnnotationsPanel#onRowClicked}. */
    public static @Nullable EditorEx editorFor(@NotNull String pathSideKey) {
        var ref = DIFF_EDITORS.get(pathSideKey);
        if (ref == null) return null;
        EditorEx editor = ref.get();
        if (editor == null || editor.isDisposed()) {
            DIFF_EDITORS.remove(pathSideKey);
            return null;
        }
        return editor;
    }

    /** Repaint every currently-tracked diff editor's gutter. Called from a
     *  session listener so deletes / new threads update icons immediately
     *  instead of waiting for the next hover-driven repaint. */
    public static void repaintAllGutters() {
        synchronized (DIFF_EDITORS) {
            for (var ref : DIFF_EDITORS.values()) {
                EditorEx editor = ref.get();
                if (editor != null && !editor.isDisposed()) {
                    editor.getGutterComponentEx().repaint();
                }
            }
        }
    }

    @Override
    public void onViewerCreated(@NotNull FrameDiffTool.DiffViewer viewer,
                                @NotNull DiffContext context,
                                @NotNull DiffRequest request) {
        if (!(viewer instanceof TwosideTextDiffViewer twoSide)) return;
        Project project = context.getProject();
        if (project == null) return;

        String leftLabel = null;
        String rightLabel = null;
        if (request instanceof ContentDiffRequest cdr) {
            leftLabel = extractRelativePath(cdr, 0, project);
            rightLabel = extractRelativePath(cdr, 1, project);
        }
        if (leftLabel != null) attachAllLines(twoSide.getEditor(Side.LEFT), "L", leftLabel, project);
        if (rightLabel != null) attachAllLines(twoSide.getEditor(Side.RIGHT), "R", rightLabel, project);
    }

    private static void attachAllLines(@Nullable EditorEx editor,
                                       @NotNull String side,
                                       @NotNull String label,
                                       @NotNull Project project) {
        if (editor == null) return;
        DIFF_EDITORS.put(label + ":" + side, new java.lang.ref.WeakReference<>(editor));
        Document doc = editor.getDocument();
        int lineCount = Math.max(1, doc.getLineCount());
        for (int line = 0; line < lineCount; line++) {
            int start = doc.getLineStartOffset(line);
            int end = doc.getLineEndOffset(line);
            var h = editor.getMarkupModel().addRangeHighlighter(
                    start, end, HighlighterLayer.LAST, null, HighlighterTargetArea.LINES_IN_RANGE);
            h.setGutterIconRenderer(new AskGutterRenderer(editor, side, label, line, project));
        }
        installHoverTracker(editor);
        LOG.debug("SpikeDiffExtension: attached " + lineCount + " hover-aware gutter slots; "
                 + "side=" + side + " label=" + label);
    }

    private static void installHoverTracker(@NotNull EditorEx editor) {
        // Idempotent: WeakHashMap.put returns previous; we only register listener once.
        if (HOVERED.put(editor, -1) != null) return;
        editor.addEditorMouseMotionListener(new EditorMouseMotionListener() {
            @Override
            public void mouseMoved(@NotNull EditorMouseEvent e) {
                int newLine = e.getLogicalPosition().line;
                Integer prev = HOVERED.get(editor);
                if (prev != null && prev == newLine) return;
                HOVERED.put(editor, newLine);
                editor.getGutterComponentEx().repaint();
            }
        });
        editor.addEditorMouseListener(new EditorMouseListener() {
            @Override
            public void mouseExited(@NotNull EditorMouseEvent e) {
                Integer prev = HOVERED.put(editor, -1);
                if (prev != null && prev != -1) editor.getGutterComponentEx().repaint();
            }
        });
    }

    private static boolean isHovered(@NotNull EditorEx editor, int line) {
        Integer v = HOVERED.get(editor);
        return v != null && v == line;
    }

    private static java.util.List<String> documentLines(@NotNull EditorEx editor) {
        Document doc = editor.getDocument();
        int n = doc.getLineCount();
        java.util.List<String> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(doc.getText(new com.intellij.openapi.util.TextRange(
                doc.getLineStartOffset(i), doc.getLineEndOffset(i))));
        }
        return out;
    }

    /** Memoized gutter index per editor — rebuilt only when the document or the
     *  session cache actually changes, not on every line's paint. */
    private static final Map<EditorEx, CachedIndex> INDEX_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private record CachedIndex(long docStamp, long cacheVersion,
                               Map<Integer, GutterAnchorIndex.LineAnchor> index) {}

    /** The thread (if any) that should render at this 0-based line. */
    private static GutterAnchorIndex.@Nullable LineAnchor lineAnchorFor(
            @NotNull EditorEx editor, @NotNull String label, @NotNull String side,
            int line0, @NotNull Project project) {
        var client = ReviewSessionService.get(project).client();
        long docStamp = editor.getDocument().getModificationStamp();
        long ver = client.cacheVersion();
        CachedIndex memo = INDEX_CACHE.get(editor);
        Map<Integer, GutterAnchorIndex.LineAnchor> index;
        if (memo != null && memo.docStamp() == docStamp && memo.cacheVersion() == ver) {
            index = memo.index();
        } else {
            index = GutterAnchorIndex.build(documentLines(editor),
                client.snapshotCache(), label, side, AnchorResolver.DEFAULT_K);
            INDEX_CACHE.put(editor, new CachedIndex(docStamp, ver, index));
        }
        return index.get(line0 + 1); // index is 1-based
    }

    private static @Nullable String extractRelativePath(@NotNull ContentDiffRequest request,
                                                        int sideIndex,
                                                        @NotNull Project project) {
        List<com.intellij.diff.contents.DiffContent> contents = request.getContents();
        if (sideIndex >= contents.size()) return null;
        com.intellij.diff.contents.DiffContent content = contents.get(sideIndex);
        com.intellij.openapi.vfs.VirtualFile vf = null;
        if (content instanceof com.intellij.diff.contents.FileContent fc) {
            vf = fc.getFile();
        } else if (content instanceof com.intellij.diff.contents.DocumentContent dc) {
            vf = dc.getHighlightFile();
        }
        if (vf == null) return null;
        String projBase = project.getBasePath();
        String path = vf.getPath();
        if (projBase != null && path.startsWith(projBase + "/")) {
            return path.substring(projBase.length() + 1);
        }
        return path;
    }

    private static final class AskGutterRenderer extends GutterIconRenderer {
        private final EditorEx editor;
        private final String side;
        private final String label;
        private final int lineZeroBased;
        private final Project project;

        AskGutterRenderer(EditorEx editor, String side, String label, int line, Project project) {
            this.editor = editor;
            this.side = side;
            this.label = label;
            this.lineZeroBased = line;
            this.project = project;
        }

        private String anchor() { return label + ":" + side + ":" + (lineZeroBased + 1); }

        @Override public @NotNull Icon getIcon() {
            var la = lineAnchorFor(editor, label, side, lineZeroBased, project);
            if (la != null) return la.stale() ? STALE_ICON : ANNOTATED_ICON;
            if (isHovered(editor, lineZeroBased)) return ASK_ICON;
            return HIDDEN_ICON;
        }

        @Override public @NotNull String getTooltipText() {
            var la = lineAnchorFor(editor, label, side, lineZeroBased, project);
            if (la != null) {
                return (la.stale() ? "Annotation stale (line changed) · " : "Annotated · ")
                    + la.ownerAnchor();
            }
            return "Comment on " + anchor();
        }

        @Override public boolean isNavigateAction() { return true; }

        @Override
        public @Nullable AnAction getClickAction() {
            return new AnAction() {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    var la = lineAnchorFor(editor, label, side, lineZeroBased, project);
                    // Open the thread that lives here (recorded anchor), else a
                    // fresh thread for this line.
                    String target = la != null ? la.ownerAnchor() : anchor();
                    SynthesisPopup.show(project, editor, target, lineZeroBased);
                }
            };
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AskGutterRenderer that)) return false;
            return lineZeroBased == that.lineZeroBased
                    && side.equals(that.side)
                    && Objects.equals(label, that.label);
        }

        @Override
        public int hashCode() { return Objects.hash(side, label, lineZeroBased); }
    }
}
