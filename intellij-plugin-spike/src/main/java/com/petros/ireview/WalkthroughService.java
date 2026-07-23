package com.petros.ireview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project-level holder of one walkthrough client + controller.
 *
 * The client discovers a session by the project's base path; when steps arrive
 * they are pushed into the controller, which activates step 1 and drives
 * whichever renderer is currently attached.
 */
@Service(Service.Level.PROJECT)
public final class WalkthroughService implements Disposable {

    private static final String MODE_KEY = "com.petros.ireview.walkthrough.mode";

    private final WalkthroughSessionClient client;
    private final WalkthroughController controller;
    private final WalkthroughInlay inline;

    public WalkthroughService(Project project) {
        String baseUrl = resolveServerUrl();
        String cwd = project.getBasePath();
        this.client = new WalkthroughSessionClient(
            baseUrl != null ? baseUrl : "http://127.0.0.1:54660",
            cwd != null ? cwd : System.getProperty("user.home"),
            Duration.ofSeconds(5));
        this.controller = new WalkthroughController(new WalkthroughNavigator.Ide(project));
        this.inline = new WalkthroughInlay(project, controller, client);
        this.controller.setMode(WalkthroughController.Mode.from(
            com.intellij.ide.util.PropertiesComponent.getInstance(project).getValue(MODE_KEY)));
        this.controller.addListener(new WalkthroughController.Listener() {
            @Override public void onModeChanged(WalkthroughController.Mode mode) {
                com.intellij.ide.util.PropertiesComponent.getInstance(project)
                    .setValue(MODE_KEY, mode.key());
                applyMode(mode);
            }
        });
        this.client.addListener(new WalkthroughSessionClient.Listener() {
            @Override public void onStepsChanged(WalkthroughDoc doc) {
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    .invokeLater(() -> controller.setDoc(doc));
            }
        });
        this.client.start();
        applyMode(controller.mode());
    }

    public static WalkthroughService get(Project project) {
        return project.getService(WalkthroughService.class);
    }

    public WalkthroughSessionClient client() { return client; }

    public WalkthroughController controller() { return controller; }

    public WalkthroughInlay inline() { return inline; }

    /** Post a question on the currently active step. */
    public CompletableFuture<Void> askCurrentStep(String text) {
        return controller.current()
            .map(step -> client.postAsk(step.id(), text))
            .orElseGet(() -> CompletableFuture.failedFuture(
                new IllegalStateException("no active step")));
    }

    /** Exactly one renderer is live: INLINE owns the inlay, RAIL owns the panel. */
    private void applyMode(WalkthroughController.Mode mode) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            if (mode == WalkthroughController.Mode.INLINE) {
                inline.attach();
            } else {
                inline.detach();
            }
        });
    }

    @Override public void dispose() {
        inline.detach();
        client.stop();
    }

    /** Read the walkthrough server URL from ~/.claude/walkthrough/server.json. */
    private static String resolveServerUrl() {
        Path p = Path.of(System.getProperty("user.home"), ".claude", "walkthrough", "server.json");
        try {
            Matcher m = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(Files.readString(p));
            if (m.find()) return m.group(1);
        } catch (IOException ignored) {
        }
        return null;
    }
}
