package com.petros.ireview;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Paints a numbered badge in the gutter of every line that anchors a step of the
 * active tour. Independent of the current mode — the badges are the one part of
 * the walkthrough that both renderers share.
 *
 * <p>A single {@link WalkthroughController.Listener} is registered per project
 * (guarded by project user data, so a re-entrant {@link #editorCreated} is a
 * no-op) rather than one listener per editor: editors open and close constantly,
 * while the project — and its controller — live for the life of the project.
 * The one listener repaints every open editor of that project on each callback;
 * per-editor state (the highlighters actually painted) still lives in a
 * {@link WeakHashMap} keyed by editor and is torn down in
 * {@link #editorReleased}, mirroring {@link WorkingCopyAskGutter}.
 */
public final class WalkthroughGutter implements EditorFactoryListener {

    private static final Key<Boolean> LISTENER_ATTACHED =
        Key.create("com.petros.ireview.walkthrough.gutterListenerAttached");

    private static final Map<Editor, List<RangeHighlighter>> PAINTED = new WeakHashMap<>();

    @Override public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        Project project = editor.getProject();
        // Diff editors are handled separately (SpikeDiffExtension paints interactive-review's
        // ask icon there); attaching here too would double every marker on those gutters.
        if (project == null || !(editor instanceof EditorEx) || editor.getEditorKind() != EditorKind.MAIN_EDITOR) return;
        WalkthroughService service = WalkthroughService.get(project);
        ensureProjectListener(project, service);
        repaint(editor, service);
    }

    @Override public void editorReleased(@NotNull EditorFactoryEvent event) {
        clear(event.getEditor());
    }

    /** Registers the one controller listener for this project, once. */
    private static void ensureProjectListener(Project project, WalkthroughService service) {
        if (Boolean.TRUE.equals(project.getUserData(LISTENER_ATTACHED))) return;
        project.putUserData(LISTENER_ATTACHED, Boolean.TRUE);
        service.controller().addListener(new WalkthroughController.Listener() {
            @Override public void onDocChanged(WalkthroughDoc doc) {
                repaintAll(project, service);
            }
            @Override public void onStepActivated(WalkthroughStep step, int i, int total) {
                repaintAll(project, service);
            }
        });
    }

    /** Controller callbacks may arrive off the EDT; every open editor of this project
     *  is repainted on the EDT since repaint mutates the markup model. */
    private static void repaintAll(Project project, WalkthroughService service) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                if (editor.getProject() == project) repaint(editor, service);
            }
        });
    }

    private static void clear(Editor editor) {
        List<RangeHighlighter> old = PAINTED.remove(editor);
        if (old == null) return;
        for (RangeHighlighter h : old) editor.getMarkupModel().removeHighlighter(h);
    }

    private static void repaint(Editor editor, WalkthroughService service) {
        if (editor.isDisposed()) return;
        clear(editor);
        Project project = editor.getProject();
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (project == null || vf == null) return;
        WalkthroughDoc doc = service.controller().doc();
        if (doc.isEmpty()) return;
        List<String> lines = List.of(editor.getDocument().getText().split("\n", -1));
        List<RangeHighlighter> painted = new ArrayList<>();
        for (int i = 0; i < doc.steps().size(); i++) {
            WalkthroughStep step = doc.steps().get(i);
            if (!WalkthroughNavigator.isStepFile(project, vf, step)) continue;
            AnchorResolver.Resolution res = WalkthroughNavigator.resolveLine(lines, step);
            // A STALE snippet still gets a badge — at the recorded line, greyed —
            // rather than vanishing. The click still jumps the controller to the
            // step; the navigator safely no-ops when the anchor can't be resolved.
            boolean stale = res.kind() == AnchorResolver.Kind.STALE;
            int line0 = (stale ? step.line() : res.line()) - 1;
            if (line0 < 0 || line0 >= editor.getDocument().getLineCount()) continue;
            RangeHighlighter h = editor.getMarkupModel().addRangeHighlighter(
                editor.getDocument().getLineStartOffset(line0),
                editor.getDocument().getLineEndOffset(line0),
                HighlighterLayer.LAST, null, HighlighterTargetArea.LINES_IN_RANGE);
            int index = i;
            boolean active = index == service.controller().index();
            h.setGutterIconRenderer(new BadgeRenderer(step, index, active, stale, service));
            painted.add(h);
        }
        PAINTED.put(editor, painted);
    }

    private static final class BadgeRenderer extends GutterIconRenderer {
        private final WalkthroughStep step;
        private final int index;
        private final boolean active;
        private final boolean stale;
        private final WalkthroughService service;

        BadgeRenderer(WalkthroughStep step, int index, boolean active, boolean stale, WalkthroughService service) {
            this.step = step;
            this.index = index;
            this.active = active;
            this.stale = stale;
            this.service = service;
        }

        @Override public @NotNull Icon getIcon() {
            return new BadgeIcon(index + 1, step.role(), active, stale);
        }

        @Override public String getTooltipText() {
            return "Step " + (index + 1) + " — " + step.title() + (stale ? "  (code changed here)" : "");
        }

        @Override public AnAction getClickAction() {
            return new AnAction() {
                @Override public void actionPerformed(@NotNull AnActionEvent e) {
                    service.controller().jumpTo(index);
                }
            };
        }

        @Override public boolean equals(Object o) {
            return o instanceof BadgeRenderer b && b.index == index && b.active == active && b.stale == stale;
        }

        @Override public int hashCode() { return index * 31 + (active ? 1 : 0) + (stale ? 2 : 0); }
    }

    /** Small filled circle with the step number; colour encodes the step's role.
     *  A stale anchor (snippet no longer found nearby) is painted grey regardless
     *  of role, signalling "code changed here" without hiding the step. */
    private static final class BadgeIcon implements Icon {
        private final int number;
        private final WalkthroughStep.Role role;
        private final boolean active;
        private final boolean stale;

        BadgeIcon(int number, WalkthroughStep.Role role, boolean active, boolean stale) {
            this.number = number;
            this.role = role;
            this.active = active;
            this.stale = stale;
        }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill;
                if (stale) {
                    fill = new Color(0x8A, 0x8D, 0x93, active ? 180 : 100);
                } else {
                    fill = switch (role) {
                        case SEAM -> new Color(0x35, 0x74, 0xF0);
                        case EDIT_SITE -> new Color(0x1F, 0x9C, 0x5B);
                        case CONTEXT -> new Color(0x8A, 0x8D, 0x93);
                    };
                    if (!active) fill = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 150);
                }
                g2.setColor(fill);
                g2.fillOval(x, y, getIconWidth(), getIconHeight());
                g2.setColor(Color.WHITE);
                g2.setFont(g2.getFont().deriveFont(Font.BOLD, 9f));
                String s = String.valueOf(number);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(s, x + (getIconWidth() - fm.stringWidth(s)) / 2,
                    y + (getIconHeight() + fm.getAscent()) / 2 - 1);
            } finally {
                g2.dispose();
            }
        }

        @Override public int getIconWidth() { return com.intellij.util.ui.JBUI.scale(14); }

        @Override public int getIconHeight() { return com.intellij.util.ui.JBUI.scale(14); }
    }
}
