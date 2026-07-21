package com.petros.ireview;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.github.pullrequest.data.GHPRIdentifier;
import org.jetbrains.plugins.github.pullrequest.ui.GHPRProjectViewModel;

import javax.swing.SwingUtilities;

/**
 * Drive the REAL JetBrains GitHub PR diff (the "Diff for Pull Request #N" tab,
 * with inline GH review comments) — instead of an isolated, locally-rebuilt diff.
 *
 * The seam is the bundled GitHub plugin's {@link GHPRProjectViewModel} project
 * service → its connected VM's {@code openPullRequestDiff}. Once that diff is
 * open it's a normal {@code TwosideTextDiffViewer}, so {@link SpikeDiffExtension}
 * already attaches our gutter icons + line-scroll to it, same as when the user
 * opens the PR by hand. These GH classes are {@code @ApiStatus.Internal}; we
 * compile against the local IDE so they match what we run.
 */
final class GhPrDiffOpener {

    private static final Logger LOG = Logger.getInstance(GhPrDiffOpener.class);

    private GhPrDiffOpener() {}

    /** Open the PR diff and show its first changed file. */
    static void open(@NotNull Project project, @NotNull ReviewSessionClient.SessionInfo session) {
        resolveAndShow(project, session, null, "R", 0);
    }

    /** Open the PR diff scrolled to {@code filePath} at {@code line0} (0-based) on {@code side} ("L"/"R"). */
    static void openAt(@NotNull Project project, @NotNull ReviewSessionClient.SessionInfo session,
                       @NotNull String filePath, @NotNull String side, int line0) {
        resolveAndShow(project, session, filePath, side, line0);
    }

    private static void resolveAndShow(@NotNull Project project, @NotNull ReviewSessionClient.SessionInfo session,
                                       @Nullable String filePath, @NotNull String side, int line0) {
        long number = parseNumber(session.prRef());
        if (number <= 0) {
            notifyWarn(project, "No PR number", "Couldn't read a PR number from \"" + session.prRef() + "\".");
            return;
        }
        String repoRoot = project.getBasePath();
        if (repoRoot == null) {
            notifyWarn(project, "No project root", "Can't locate the git working tree for this project.");
            return;
        }
        final long prNumber = number;
        // gh shells out — keep it off the EDT, then hop back on for the GH VM.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            NodeIdResult result = resolveNodeId(repoRoot, prNumber);
            if (result.nodeId().isEmpty()) {
                notifyWarn(project, "Couldn't resolve PR #" + prNumber, result.failure());
                return;
            }
            String nodeId = result.nodeId();
            GHPRProjectViewModel vm = project.getService(GHPRProjectViewModel.class);
            if (vm == null) {
                notifyWarn(project, "GitHub plugin unavailable", "No GHPRProjectViewModel for this project.");
                return;
            }
            GHPRIdentifier id = new GHPRIdentifier(nodeId, prNumber);
            // Java handles the Function1 callback cleanly; the Kotlin driver takes
            // over for the coroutine/value-class work of driving the diff.
            SwingUtilities.invokeLater(() -> vm.activateAndAwaitProject(connected -> {
                GhPrDiffDriver.show(project, connected, id, filePath, side, line0);
                return kotlin.Unit.INSTANCE;
            }));
        });
    }

    /** @param failure why it failed, shown to the user; empty when nodeId is set. */
    private record NodeIdResult(@NotNull String nodeId, @NotNull String failure) {
        static NodeIdResult ok(String id) { return new NodeIdResult(id, ""); }
        static NodeIdResult failed(String why) { return new NodeIdResult("", why); }
    }

    /** {@code gh pr view <n> --json id --jq .id} → the PR's GraphQL node id. */
    private static @NotNull NodeIdResult resolveNodeId(@NotNull String repoRoot, long number) {
        // The IDE's own PATH is minimal when launched from the Dock; the shell
        // environment is what the user's terminal would see.
        String path = com.intellij.util.EnvironmentUtil.getValue("PATH");
        if (path == null) path = System.getenv("PATH");
        String gh = GhExecutable.resolve(path, p -> new java.io.File(p).canExecute());
        if (gh == null) {
            return NodeIdResult.failed("Couldn't find the `gh` command. Install the GitHub CLI, "
                + "or launch the IDE from a terminal so it inherits your PATH.");
        }
        try {
            GeneralCommandLine cl = new GeneralCommandLine(
                gh, "pr", "view", String.valueOf(number), "--json", "id", "--jq", ".id")
                .withWorkDirectory(repoRoot)
                .withEnvironment(com.intellij.util.EnvironmentUtil.getEnvironmentMap());
            ProcessOutput out = ExecUtil.execAndGetOutput(cl);
            if (out.getExitCode() == 0 && !out.getStdout().isBlank()) {
                return NodeIdResult.ok(out.getStdout().trim());
            }
            String stderr = out.getStderr().trim();
            LOG.warn("gh pr view exited " + out.getExitCode() + ": " + stderr);
            return NodeIdResult.failed(stderr.isEmpty()
                ? "`gh pr view " + number + "` returned nothing (exit " + out.getExitCode() + ")."
                : stderr);
        } catch (Exception e) {
            LOG.warn("gh pr view failed", e);
            return NodeIdResult.failed("Couldn't run `" + gh + "`: " + e.getMessage());
        }
    }

    /** First run of digits in the PR ref ("6272", "owner/repo#6272", a URL …). */
    private static long parseNumber(@NotNull String prRef) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(prRef);
        try {
            return m.find() ? Long.parseLong(m.group()) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void notifyWarn(@NotNull Project project, @NotNull String title, @NotNull String body) {
        com.intellij.notification.Notifications.Bus.notify(
            new com.intellij.notification.Notification("Interactive Review", title, body,
                com.intellij.notification.NotificationType.WARNING),
            project);
    }
}
