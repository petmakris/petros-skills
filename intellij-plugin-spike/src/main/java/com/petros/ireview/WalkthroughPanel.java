package com.petros.ireview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Mode A renderer: the whole step list, with the active step expanded to show
 * its explanation, its Q&amp;A thread and an ask box.
 *
 * Subscribes to the controller only while {@link WalkthroughController#mode()}
 * is RAIL; in INLINE mode it renders a one-line hint instead so the two
 * renderers are never both drawing.
 */
public final class WalkthroughPanel implements Disposable {

    private final Project project;
    private final WalkthroughService service;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel steps = new JPanel();
    private final JBTextField ask = new JBTextField();
    private final JBLabel status = new JBLabel(" ");
    private final JButton back = new JButton("◀ Back");
    private final JButton next = new JButton("Next ▶");

    private final WalkthroughController.Listener controllerListener =
        new WalkthroughController.Listener() {
            @Override public void onDocChanged(WalkthroughDoc doc) { invokeRebuild(); }
            @Override public void onStepActivated(WalkthroughStep step, int i, int total) { invokeRebuild(); }
            @Override public void onModeChanged(WalkthroughController.Mode mode) { invokeRebuild(); }
        };

    private final WalkthroughSessionClient.Listener clientListener =
        new WalkthroughSessionClient.Listener() {
            @Override public void onThreadChanged(String anchor, WalkthroughSessionClient.ThreadState t) { invokeRebuild(); }
            @Override public void onPendingChanged(String anchor, boolean pending) { invokeRebuild(); }
            @Override public void onStateChanged(WalkthroughSessionClient.State s) { invokeRebuild(); }
            @Override public void onDetached() { invokeRebuild(); }
        };

    public WalkthroughPanel(Project project) {
        this.project = project;
        this.service = WalkthroughService.get(project);

        steps.setLayout(new BoxLayout(steps, BoxLayout.Y_AXIS));
        steps.setBorder(JBUI.Borders.empty(4));

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.setBorder(JBUI.Borders.empty(6));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(back);
        buttons.add(next);
        buttons.add(status);
        south.add(ask, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        root.add(new JBScrollPane(steps), BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        back.addActionListener(e -> service.controller().prev());
        next.addActionListener(e -> service.controller().next());
        ask.addActionListener(e -> submitAsk());

        service.controller().addListener(controllerListener);
        service.client().addListener(clientListener);
        rebuild();
    }

    public JComponent getComponent() { return root; }

    private void submitAsk() {
        String text = ask.getText().trim();
        if (text.isEmpty()) return;
        ask.setText("");
        service.askCurrentStep(text).exceptionally(err -> {
            SwingUtilities.invokeLater(() -> status.setText(err.getMessage()));
            return null;
        });
    }

    private void invokeRebuild() {
        SwingUtilities.invokeLater(this::rebuild);
    }

    private void rebuild() {
        steps.removeAll();
        WalkthroughController c = service.controller();

        if (c.mode() != WalkthroughController.Mode.RAIL) {
            steps.add(new JBLabel("Walkthrough is in inline mode — steps render in the editor."));
            finish(false);
            return;
        }
        WalkthroughDoc doc = c.doc();
        if (doc.isEmpty()) {
            steps.add(new JBLabel("No walkthrough for this project. Run /walkthrough <question>."));
            finish(false);
            return;
        }
        for (int i = 0; i < doc.steps().size(); i++) {
            WalkthroughStep step = doc.steps().get(i);
            boolean active = i == c.index();
            steps.add(row(step, i, active));
        }
        status.setText(statusText());
        finish(true);
    }

    /** One step row: badge + title + file:line, plus body and thread when active. */
    private JComponent row(WalkthroughStep step, int index, boolean active) {
        JPanel p = new JPanel(new BorderLayout(6, 2));
        p.setBorder(JBUI.Borders.empty(6, 8));
        p.setOpaque(active);
        if (active) p.setBackground(JBUI.CurrentTheme.List.Selection.background(false));

        JBLabel head = new JBLabel((index + 1) + ".  " + step.title());
        head.setFont(head.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN));
        head.setForeground(roleColor(step.role()));
        // Resolving every step's snippet on every rebuild would be wasted work —
        // only the active step's navigation target matters to the user right now,
        // so that's the only one re-resolved against the live document.
        String whereText = step.file() + ":" + step.line();
        if (active && isStale(step)) whereText += "  (code changed here)";
        JBLabel where = new JBLabel(whereText);
        where.setForeground(JBUI.CurrentTheme.Label.disabledForeground());

        JPanel headBox = new JPanel(new GridLayout(2, 1));
        headBox.setOpaque(false);
        headBox.add(head);
        headBox.add(where);
        p.add(headBox, BorderLayout.NORTH);

        if (active) {
            JPanel body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setOpaque(false);
            body.add(markdown(step.markdown()));
            service.client().threadFor(step.anchor()).ifPresent(t -> {
                if (!t.question().isEmpty()) {
                    JBLabel q = new JBLabel("You · " + t.question());
                    q.setFont(q.getFont().deriveFont(Font.BOLD));
                    body.add(q);
                }
                body.add(markdown(t.synthesis()));
            });
            if (service.client().isPending(step.anchor())) {
                body.add(new JBLabel("● waiting for Claude…"));
            }
            p.add(body, BorderLayout.CENTER);
        }

        p.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                service.controller().jumpTo(index);
            }
        });
        return p;
    }

    /**
     * Whether {@code step}'s snippet no longer matches nearby in its own open
     * document — mirrors {@link WalkthroughInlay}'s check so the rail gives the
     * same "code changed here" signal the inline card does.
     */
    private boolean isStale(WalkthroughStep step) {
        VirtualFile vf = WalkthroughNavigator.resolveStepFile(project, step);
        if (vf == null || vf.isDirectory()) return false;
        Document document = FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) return false;
        List<String> lines = List.of(document.getText().split("\n", -1));
        return WalkthroughNavigator.resolveLine(lines, step).kind() == AnchorResolver.Kind.STALE;
    }

    /**
     * Render markdown through the shared renderer so links behave exactly as
     * they do in the review panel (project-relative paths become clickable and
     * are routed by SynthesisLinkRouter).
     */
    private JComponent markdown(String md) {
        JEditorPane pane = new JEditorPane("text/html", MarkdownLinkRenderer.toHtml(md));
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                SynthesisLinkRouter.route(project, e.getDescription());
            }
        });
        return pane;
    }

    private String statusText() {
        WalkthroughSessionClient.State s = service.client().state();
        return switch (s) {
            case ENDED -> "session ended — read only";
            case PAUSED -> "Claude is away — asks disabled";
            case DISCONNECTED -> "reconnecting…";
            default -> (service.controller().index() + 1) + " / " + service.controller().size();
        };
    }

    private static Color roleColor(WalkthroughStep.Role role) {
        return switch (role) {
            case SEAM -> new Color(0x35, 0x74, 0xF0);
            case EDIT_SITE -> new Color(0x1F, 0x9C, 0x5B);
            case CONTEXT -> UIManager.getColor("Label.foreground");
        };
    }

    private void finish(boolean enableControls) {
        boolean live = enableControls
            && service.client().state() != WalkthroughSessionClient.State.ENDED
            && service.client().state() != WalkthroughSessionClient.State.PAUSED;
        ask.setEnabled(live);
        back.setEnabled(enableControls);
        next.setEnabled(enableControls);
        steps.revalidate();
        steps.repaint();
    }

    @Override public void dispose() {
        service.controller().removeListener(controllerListener);
        service.client().removeListener(clientListener);
    }
}
