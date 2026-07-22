package com.petros.ireview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class WalkthroughToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        WalkthroughPanel panel = new WalkthroughPanel(project);
        Content content = ContentFactory.getInstance()
            .createContent(panel.getComponent(), "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
