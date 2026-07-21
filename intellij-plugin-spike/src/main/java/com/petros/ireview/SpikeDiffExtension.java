package com.petros.ireview;

import com.intellij.diff.DiffContext;
import com.intellij.diff.DiffExtension;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
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
    /** Hover "ask Claude here" — a speech bubble reads as a comment, where the
     *  old generic "+" read as "insert/expand". */
    private static final Icon ASK_ICON =
            IconLoader.getIcon("/icons/comment.svg", SpikeDiffExtension.class);
    private static final Icon ANNOTATED_ICON =
            IconLoader.getIcon("/icons/annotation_yellow.svg", SpikeDiffExtension.class);
    private static final Icon STALE_ICON =
            IconLoader.getIcon("/icons/annotation_stale.svg", SpikeDiffExtension.class);
    /** A question on this line is in flight (asked, answer not yet back). */
    private static final Icon PENDING_ICON =
            IconLoader.getIcon("/icons/annotation_pending.svg", SpikeDiffExtension.class);
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
     * Values are WeakReferences so a closed diff viewer's editor can be GC'd,
     * but the map entry itself (key + cleared ref) does NOT evict on its own —
     * a WeakHashMap keyed by editor wouldn't support the path→editor lookup.
     * So cleared/disposed entries are actively pruned in {@link #editorFor} and
     * {@link #repaintAllGutters} (which runs on every session change), bounding
     * the registry to live viewers.
     */
    private static final Map<String, DiffTarget> DIFF_EDITORS =
            Collections.synchronizedMap(new java.util.HashMap<>());

    /**
     * One registered diff surface for one `<path>:<side>`.
     *
     * <p>{@code toDisplayLine} maps a side-relative 0-based line (what anchors are
     * recorded in) to the 0-based line to scroll to in {@code editor}. Side-by-side
     * shows one side per editor, so it is the identity; the unified viewer merges
     * both sides into one document, so it delegates to the platform's convertor.
     */
    private record DiffTarget(java.lang.ref.WeakReference<EditorEx> editor,
                              java.lang.ref.WeakReference<Document> sideDoc,
                              java.util.function.IntUnaryOperator toDisplayLine) {}

    /** Public lookup for {@link AnnotationsPanel#onRowClicked}. */
    public static @Nullable EditorEx editorFor(@NotNull String pathSideKey) {
        var target = DIFF_EDITORS.get(pathSideKey);
        if (target == null) return null;
        EditorEx editor = target.editor().get();
        if (editor == null || editor.isDisposed()) {
            DIFF_EDITORS.remove(pathSideKey);
            return null;
        }
        return editor;
    }

    /**
     * The document holding that side's own text — what anchors are recorded
     * against. Side-by-side this is the editor's document; unified it is the
     * pre- or post-change file behind the merged view.
     */
    public static @Nullable Document sideDocumentFor(@NotNull String pathSideKey) {
        var target = DIFF_EDITORS.get(pathSideKey);
        return target == null ? null : target.sideDoc().get();
    }

    /**
     * Where a side-relative line lands on screen in the registered viewer.
     * Returns {@code sideLine0} unchanged when nothing is registered, so callers
     * can use it unconditionally.
     */
    public static int displayLineFor(@NotNull String pathSideKey, int sideLine0) {
        var target = DIFF_EDITORS.get(pathSideKey);
        if (target == null) return sideLine0;
        EditorEx editor = target.editor().get();
        if (editor == null || editor.isDisposed()) return sideLine0;
        int mapped = target.toDisplayLine().applyAsInt(sideLine0);
        return mapped < 0 ? sideLine0 : mapped;
    }

    /** Repaint every currently-tracked diff editor's gutter. Called from a
     *  session listener so deletes / new threads update icons immediately
     *  instead of waiting for the next hover-driven repaint. */
    public static void repaintAllGutters() {
        synchronized (DIFF_EDITORS) {
            var it = DIFF_EDITORS.values().iterator();
            while (it.hasNext()) {
                EditorEx editor = it.next().editor().get();
                if (editor == null || editor.isDisposed()) {
                    it.remove(); // prune dead viewers so the registry can't grow unbounded
                    continue;
                }
                editor.getGutterComponentEx().repaint();
            }
        }
    }

    @Override
    public void onViewerCreated(@NotNull FrameDiffTool.DiffViewer viewer,
                                @NotNull DiffContext context,
                                @NotNull DiffRequest request) {
        // A changed file renders as a TwosideTextDiffViewer (side-by-side) or a
        // UnifiedDiffViewer. A file that exists on only one side — added or
        // deleted — has no content to pair against, so the platform renders it
        // with a OnesideTextDiffViewer instead, in both view modes. Empty
        // placeholder blocks come through as ErrorDiffTool$MyViewer and are
        // skipped here.
        if (LOG.isDebugEnabled()) {
            LOG.debug("onViewerCreated viewer=" + viewer.getClass().getName());
        }
        if (!(viewer instanceof TwosideTextDiffViewer)
                && !(viewer instanceof UnifiedDiffViewer)
                && !(viewer instanceof OnesideTextDiffViewer)) {
            return;
        }
        Project project = context.getProject();
        if (project == null) return;

        String leftLabel = null;
        String rightLabel = null;
        if (request instanceof ContentDiffRequest cdr) {
            leftLabel = extractRelativePath(cdr, 0, project);
            rightLabel = extractRelativePath(cdr, 1, project);
        }
        if (viewer instanceof TwosideTextDiffViewer twoSide) {
            if (leftLabel != null) {
                attachAllLines(twoSide.getEditor(Side.LEFT), "L", leftLabel, project);
            }
            if (rightLabel != null) {
                attachAllLines(twoSide.getEditor(Side.RIGHT), "R", rightLabel, project);
            }
        } else if (viewer instanceof UnifiedDiffViewer unifiedViewer) {
            attachUnified(unifiedViewer, leftLabel, rightLabel, project);
        } else {
            attachOneside((OnesideTextDiffViewer) viewer, leftLabel, rightLabel, project);
        }
        // First time a diff is opened during an active review, tell the user the
        // gutter is interactive — otherwise the affordance is invisible until a
        // chance hover. Shown once ever (persisted), and only mid-review so it
        // never fires on unrelated diffs.
        if (ReviewSessionService.get(project).client().currentSession().isPresent()) {
            maybeShowGutterHint(project);
        }
    }

    private static void maybeShowGutterHint(@NotNull Project project) {
        var props = com.intellij.ide.util.PropertiesComponent.getInstance();
        if (props.getBoolean("ireview.gutterHintShown", false)) return;
        props.setValue("ireview.gutterHintShown", true);
        com.intellij.notification.Notifications.Bus.notify(
            new com.intellij.notification.Notification(
                "Interactive Review",
                "Ask Claude about any line",
                "Hover a changed line in this diff and click the 💬 in the gutter to ask. "
                    + "Answered lines get a yellow marker; a line you've asked about shows an amber "
                    + "marker until the answer arrives.",
                com.intellij.notification.NotificationType.INFORMATION),
            project);
    }

    private static void attachAllLines(@Nullable EditorEx editor,
                                       @NotNull String side,
                                       @NotNull String label,
                                       @NotNull Project project) {
        if (editor == null) return;
        Document doc = editor.getDocument();
        DIFF_EDITORS.put(label + ":" + side,
                new DiffTarget(new java.lang.ref.WeakReference<>(editor),
                               new java.lang.ref.WeakReference<>(doc),
                               java.util.function.IntUnaryOperator.identity()));
        int lineCount = Math.max(1, doc.getLineCount());
        for (int line = 0; line < lineCount; line++) {
            int start = doc.getLineStartOffset(line);
            int end = doc.getLineEndOffset(line);
            var h = editor.getMarkupModel().addRangeHighlighter(
                    start, end, HighlighterLayer.LAST, null, HighlighterTargetArea.LINES_IN_RANGE);
            h.setGutterIconRenderer(new AskGutterRenderer(editor, doc, side, label, line, line, project));
        }
        installHoverTracker(editor);
        LOG.debug("SpikeDiffExtension: attached " + lineCount + " hover-aware gutter slots; "
                 + "side=" + side + " label=" + label);
    }

    /**
     * An added or deleted file has content on one side only, so the platform gives
     * it a single editor. That editor is the whole file, so lines map straight to
     * anchors — the only question is which side it represents.
     */
    private static void attachOneside(@NotNull OnesideTextDiffViewer viewer,
                                      @Nullable String leftLabel,
                                      @Nullable String rightLabel,
                                      @NotNull Project project) {
        boolean isLeft = viewer.getSide().isLeft();
        String label = isLeft ? leftLabel : rightLabel;
        // The absent side contributes no path, so on an added file only rightLabel
        // is set and on a deleted file only leftLabel — take whichever exists.
        if (label == null) label = isLeft ? rightLabel : leftLabel;
        if (label == null) return;
        attachAllLines(viewer.getEditor(), isLeft ? "L" : "R", label, project);
    }

    /** Gutter highlighters this extension owns, so a re-attach can drop the previous set. */
    private static final Map<EditorEx, List<com.intellij.openapi.editor.markup.RangeHighlighter>> OWNED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The unified viewer merges both sides into a single document, so a screen
     * line is not a side line and one editor serves both sides. The platform's
     * line convertor supplies the mapping in both directions.
     */
    private static void attachUnified(@NotNull UnifiedDiffViewer viewer,
                                      @Nullable String leftLabel,
                                      @Nullable String rightLabel,
                                      @NotNull Project project) {
        EditorEx editor = viewer.getEditor();
        if (editor == null || (leftLabel == null && rightLabel == null)) return;

        if (leftLabel != null) {
            DIFF_EDITORS.put(leftLabel + ":L",
                    new DiffTarget(new java.lang.ref.WeakReference<>(editor),
                                   new java.lang.ref.WeakReference<>(viewer.getDocument(Side.LEFT)),
                                   line -> viewer.transferLineToOneside(Side.LEFT, line)));
        }
        if (rightLabel != null) {
            DIFF_EDITORS.put(rightLabel + ":R",
                    new DiffTarget(new java.lang.ref.WeakReference<>(editor),
                                   new java.lang.ref.WeakReference<>(viewer.getDocument(Side.RIGHT)),
                                   line -> viewer.transferLineToOneside(Side.RIGHT, line)));
        }
        installHoverTracker(editor);

        // The merged document is assembled after the viewer is created and rebuilt
        // on every rediff (whitespace toggle, content reload), which invalidates the
        // line mapping and our highlighters. Attaching once at creation would bind
        // to an empty document, so re-attach on each rebuild.
        Runnable attach = () -> attachUnifiedLines(viewer, editor, leftLabel, rightLabel, project);
        // Parented to the viewer: the listener holds the editor and project, so it
        // must die with the diff rather than outlive it.
        editor.getDocument().addDocumentListener(new com.intellij.openapi.editor.event.DocumentListener() {
            @Override
            public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent e) {
                com.intellij.openapi.application.ApplicationManager.getApplication()
                        .invokeLater(attach, com.intellij.openapi.application.ModalityState.any());
            }
        }, viewer);
        attach.run();
    }

    private static void attachUnifiedLines(@NotNull UnifiedDiffViewer viewer,
                                           @NotNull EditorEx editor,
                                           @Nullable String leftLabel,
                                           @Nullable String rightLabel,
                                           @NotNull Project project) {
        if (editor.isDisposed()) return;
        var previous = OWNED.remove(editor);
        if (previous != null) previous.forEach(h -> { if (h.isValid()) h.dispose(); });

        Document doc = editor.getDocument();
        int lineCount = doc.getLineCount();
        var added = new java.util.ArrayList<com.intellij.openapi.editor.markup.RangeHighlighter>(lineCount);
        for (int line = 0; line < lineCount; line++) {
            var placement = UnifiedGutterPlacement.choose(
                    transferStrict(viewer, Side.RIGHT, line), rightLabel,
                    transferStrict(viewer, Side.LEFT, line), leftLabel);
            if (placement == null) continue;
            Side side = "R".equals(placement.side()) ? Side.RIGHT : Side.LEFT;
            Document sideDoc = viewer.getDocument(side);
            var h = editor.getMarkupModel().addRangeHighlighter(
                    doc.getLineStartOffset(line), doc.getLineEndOffset(line),
                    HighlighterLayer.LAST, null, HighlighterTargetArea.LINES_IN_RANGE);
            h.setGutterIconRenderer(new AskGutterRenderer(
                    editor, sideDoc, placement.side(), placement.label(),
                    placement.sideLine0(), line, project));
            added.add(h);
        }
        OWNED.put(editor, added);
        LOG.debug("SpikeDiffExtension: attached " + added.size() + " unified gutter slots of "
                 + lineCount + " lines");
    }

    /** -1 when this screen line has no counterpart on {@code side}. */
    private static int transferStrict(@NotNull UnifiedDiffViewer viewer, @NotNull Side side, int line) {
        try {
            return viewer.transferLineFromOnesideStrict(side, line);
        } catch (RuntimeException e) {
            return -1; // mapping not built yet; the next rebuild re-attaches
        }
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

    private static java.util.List<String> documentLines(@NotNull Document doc) {
        int n = doc.getLineCount();
        java.util.List<String> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(doc.getText(new com.intellij.openapi.util.TextRange(
                doc.getLineStartOffset(i), doc.getLineEndOffset(i))));
        }
        return out;
    }

    /** Memoized gutter index per side document — rebuilt only when that document
     *  or the session cache actually changes, not on every line's paint. Keyed by
     *  document rather than editor because the unified viewer renders both sides
     *  in one editor, and each side needs its own index. */
    private static final Map<Document, CachedIndex> INDEX_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private record CachedIndex(long docStamp, long cacheVersion,
                               Map<Integer, GutterAnchorIndex.LineAnchor> index) {}

    /**
     * The thread (if any) that should render at this line.
     *
     * @param sideDoc the side's own document — anchors are recorded against it,
     *                which in the unified viewer is not the document on screen
     * @param sideLine0 0-based line within {@code sideDoc}
     */
    private static GutterAnchorIndex.@Nullable LineAnchor lineAnchorFor(
            @NotNull Document sideDoc, @NotNull String label, @NotNull String side,
            int sideLine0, @NotNull Project project) {
        var client = ReviewSessionService.get(project).client();
        long docStamp = sideDoc.getModificationStamp();
        long ver = client.cacheVersion();
        CachedIndex memo = INDEX_CACHE.get(sideDoc);
        Map<Integer, GutterAnchorIndex.LineAnchor> index;
        if (memo != null && memo.docStamp() == docStamp && memo.cacheVersion() == ver) {
            index = memo.index();
        } else {
            index = GutterAnchorIndex.build(documentLines(sideDoc),
                client.snapshotCache(), label, side, AnchorResolver.DEFAULT_K);
            INDEX_CACHE.put(sideDoc, new CachedIndex(docStamp, ver, index));
        }
        return index.get(sideLine0 + 1); // index is 1-based
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
        /** The side's own document, which anchors are recorded against. Equal to
         *  the editor's document side-by-side; the pre/post file in unified. */
        private final Document sideDoc;
        private final String side;
        private final String label;
        /** Line within {@code sideDoc} — what the anchor string carries. */
        private final int sideLine0;
        /** Line within the editor on screen — where the icon is drawn. */
        private final int displayLine0;
        private final Project project;

        AskGutterRenderer(EditorEx editor, Document sideDoc, String side, String label,
                          int sideLine0, int displayLine0, Project project) {
            this.editor = editor;
            this.sideDoc = sideDoc;
            this.side = side;
            this.label = label;
            this.sideLine0 = sideLine0;
            this.displayLine0 = displayLine0;
            this.project = project;
        }

        private String anchor() { return label + ":" + side + ":" + (sideLine0 + 1); }

        @Override public @NotNull Icon getIcon() {
            if (ReviewSessionService.get(project).client().isPending(anchor())) return PENDING_ICON;
            var la = lineAnchorFor(sideDoc, label, side, sideLine0, project);
            if (la != null) return la.stale() ? STALE_ICON : ANNOTATED_ICON;
            if (isHovered(editor, displayLine0)) return ASK_ICON;
            return HIDDEN_ICON;
        }

        @Override public @NotNull String getTooltipText() {
            if (ReviewSessionService.get(project).client().isPending(anchor())) {
                return "Claude is answering…";
            }
            var la = lineAnchorFor(sideDoc, label, side, sideLine0, project);
            if (la != null) {
                return (la.stale() ? "Annotation stale (line changed) · " : "Annotated · ")
                    + la.ownerAnchor();
            }
            return "Ask Claude about " + anchor();
        }

        @Override public boolean isNavigateAction() { return true; }

        @Override
        public @Nullable AnAction getClickAction() {
            return new AnAction() {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    var la = lineAnchorFor(sideDoc, label, side, sideLine0, project);
                    // Open the thread that lives here (recorded anchor), else a
                    // fresh thread for this line.
                    String target = la != null ? la.ownerAnchor() : anchor();
                    SynthesisPopup.show(project, editor, target, displayLine0);
                }
            };
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AskGutterRenderer that)) return false;
            return sideLine0 == that.sideLine0
                    && displayLine0 == that.displayLine0
                    && side.equals(that.side)
                    && Objects.equals(label, that.label);
        }

        @Override
        public int hashCode() { return Objects.hash(side, label, sideLine0, displayLine0); }
    }
}
