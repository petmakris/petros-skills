package com.petros.ireview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/**
 * Mode B position indicator: a small always-on-top balloon showing
 * "3 / 7 · title" plus the navigation keys. Replaced (not stacked) on each step.
 */
public final class WalkthroughHud {

    private final Project project;
    private Balloon balloon;

    public WalkthroughHud(Project project) { this.project = project; }

    public void show(WalkthroughStep step, int index, int total) {
        hide();
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        panel.setOpaque(false);
        panel.add(new JBLabel((index + 1) + " / " + total + " · " + step.title()));
        panel.add(new JBLabel(WalkthroughActions.hintText()));
        balloon = JBPopupFactory.getInstance()
            .createBalloonBuilder(panel)
            .setFadeoutTime(0)
            .setHideOnClickOutside(false)
            .setHideOnKeyOutside(false)
            .setBorderInsets(JBUI.insets(4, 10))
            .createBalloon();
        JComponent frame = (JComponent) WindowManager.getInstance()
            .getIdeFrame(project).getComponent();
        Point at = new Point(frame.getWidth() / 2, frame.getHeight() - JBUI.scale(60));
        balloon.show(new RelativePoint(frame, at), Balloon.Position.above);
    }

    public void hide() {
        if (balloon != null) {
            balloon.hide();
            balloon = null;
        }
    }
}
