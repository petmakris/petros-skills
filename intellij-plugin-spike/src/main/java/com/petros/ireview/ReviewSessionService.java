package com.petros.ireview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project-level holder of one {@link ReviewSessionClient}.
 *
 * Lifecycle: created on first {@link #get} call, disposed when the project
 * closes (via IntelliJ's Service framework). The held client begins polling
 * for sessions matching the project's base path immediately.
 */
@Service(Service.Level.PROJECT)
public final class ReviewSessionService implements Disposable {

    private final ReviewSessionClient client;

    public ReviewSessionService(Project project) {
        String baseUrl = resolveServerUrl();
        String cwd = project.getBasePath();
        this.client = new ReviewSessionClient(
            baseUrl != null ? baseUrl : "http://127.0.0.1:54620",
            cwd != null ? cwd : System.getProperty("user.home"),
            Duration.ofSeconds(5)
        );
        // Repaint diff gutters whenever a thread is added/deleted or a question
        // goes in/out of flight, so the annotation / answering icons appear and
        // disappear immediately instead of waiting for the user's next hover.
        this.client.addListener(new ReviewSessionClient.Listener() {
            @Override public void onThreadChanged(String anchor, String synthesis, int version) {
                repaintGutters();
            }
            @Override public void onThreadDeleted(String anchor) {
                repaintGutters();
            }
            @Override public void onPendingChanged(String anchor, boolean pending) {
                repaintGutters();
            }
            private void repaintGutters() {
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    .invokeLater(SpikeDiffExtension::repaintAllGutters);
            }
        });
        this.client.start();
    }

    public static ReviewSessionService get(Project project) {
        return project.getService(ReviewSessionService.class);
    }

    public ReviewSessionClient client() { return client; }

    @Override public void dispose() { client.stop(); }

    /**
     * Read the interactive_review server URL from
     * ~/.claude/interactive-review/server.json. The skill writes that file on
     * server start. Returns null if not present or unreadable — caller falls
     * back to a default.
     */
    private static String resolveServerUrl() {
        Path p = Path.of(System.getProperty("user.home"), ".claude", "interactive-review", "server.json");
        try {
            String json = Files.readString(p);
            Matcher m = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (m.find()) return m.group(1);
        } catch (IOException ignored) {
        }
        return null;
    }
}
