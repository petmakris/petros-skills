package com.petros.ireview;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayProperties;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.JBUI;

import java.awt.*;
import java.util.List;

/**
 * Mode B renderer: one block inlay under the active step's anchor line, holding
 * the step's explanation and its latest Claude reply.
 *
 * Exactly one inlay exists at a time — {@link #refresh()} disposes the previous
 * one before creating the next, so stepping never leaves cards behind.
 */
public final class WalkthroughInlay {

    private final Project project;
    private final WalkthroughHud hud;
    private Inlay<?> currentInlay;
    private boolean attached;

    private final WalkthroughController.Listener listener = new WalkthroughController.Listener() {
        @Override public void onStepActivated(WalkthroughStep step, int index, int total) { refresh(); }
        @Override public void onDocChanged(WalkthroughDoc doc) { refresh(); }
    };

    public WalkthroughInlay(Project project) {
        this.project = project;
        this.hud = new WalkthroughHud(project);
    }

    public void attach() {
        if (attached) return;
        attached = true;
        WalkthroughService.get(project).controller().addListener(listener);
        refresh();
    }

    public void detach() {
        if (!attached) return;
        attached = false;
        WalkthroughService.get(project).controller().removeListener(listener);
        disposeInlay();
        hud.hide();
    }

    /** Rebuild the inlay at the current step's re-resolved anchor. */
    public void refresh() {
        disposeInlay();
        if (!attached) return;
        WalkthroughService service = WalkthroughService.get(project);
        WalkthroughController c = service.controller();
        var maybeStep = c.current();
        if (maybeStep.isEmpty()) { hud.hide(); return; }
        WalkthroughStep step = maybeStep.get();

        Editor editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .getSelectedTextEditor();
        if (editor == null) { hud.show(step, c.index(), c.size()); return; }
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null || !vf.getPath().endsWith(step.file())) {
            // The editor is showing a different file — the controller's navigate
            // will land here again once the right file is open.
            hud.show(step, c.index(), c.size());
            return;
        }
        List<String> lines = List.of(editor.getDocument().getText().split("\n", -1));
        AnchorResolver.Resolution res = WalkthroughNavigator.resolveLine(lines, step);
        int line = res.kind() == AnchorResolver.Kind.STALE ? step.line() : res.line();
        int offset = editor.getDocument().getLineEndOffset(
            Math.min(Math.max(0, line - 1), editor.getDocument().getLineCount() - 1));

        String body = step.markdown();
        var thread = service.client().threadFor(step.anchor());
        if (thread.isPresent()) {
            body = body + "\n\nYou · " + thread.get().question() + "\n" + thread.get().synthesis();
        } else if (service.client().isPending(step.anchor())) {
            body = body + "\n\n● waiting for Claude…";
        }
        String header = "Step " + (c.index() + 1) + " of " + c.size() + " — " + step.title()
            + (res.kind() == AnchorResolver.Kind.STALE ? "  (code changed here)" : "");

        currentInlay = editor.getInlayModel().addBlockElement(
            offset, new InlayProperties().showAbove(false).relatesToPrecedingText(true),
            new CardRenderer(header, body));
        hud.show(step, c.index(), c.size());
    }

    private void disposeInlay() {
        if (currentInlay != null) {
            com.intellij.openapi.util.Disposer.dispose(currentInlay);
            currentInlay = null;
        }
    }

    /** Plain-text card. Kept deliberately simple: the rail is where rich rendering lives. */
    private static final class CardRenderer implements EditorCustomElementRenderer {
        private final String header;
        private final String body;

        CardRenderer(String header, String body) {
            this.header = header;
            this.body = body;
        }

        private static String[] lines(String text) { return text.split("\n", -1); }

        @Override public int calcWidthInPixels(Inlay inlay) {
            Editor e = inlay.getEditor();
            FontMetrics fm = e.getContentComponent().getFontMetrics(e.getColorsScheme().getFont(
                com.intellij.openapi.editor.colors.EditorFontType.PLAIN));
            int max = fm.stringWidth(header);
            for (String l : lines(body)) max = Math.max(max, fm.stringWidth(l));
            return max + JBUI.scale(32);
        }

        @Override public int calcHeightInPixels(Inlay inlay) {
            int rows = lines(body).length + 2;
            return rows * inlay.getEditor().getLineHeight() + JBUI.scale(8);
        }

        @Override public void paint(Inlay inlay, Graphics g, Rectangle target, TextAttributes attributes) {
            Editor e = inlay.getEditor();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0xF5, 0xF8, 0xFF));
                g2.fillRect(target.x, target.y, target.width, target.height);
                g2.setColor(new Color(0x35, 0x74, 0xF0));
                g2.fillRect(target.x, target.y, JBUI.scale(3), target.height);
                g2.setFont(e.getColorsScheme().getFont(
                    com.intellij.openapi.editor.colors.EditorFontType.BOLD));
                int lineHeight = e.getLineHeight();
                int x = target.x + JBUI.scale(14);
                int y = target.y + lineHeight;
                g2.setColor(new Color(0x1F, 0x21, 0x26));
                g2.drawString(header, x, y);
                g2.setFont(e.getColorsScheme().getFont(
                    com.intellij.openapi.editor.colors.EditorFontType.PLAIN));
                g2.setColor(new Color(0x2F, 0x32, 0x37));
                for (String l : lines(body)) {
                    y += lineHeight;
                    g2.drawString(l, x, y);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
