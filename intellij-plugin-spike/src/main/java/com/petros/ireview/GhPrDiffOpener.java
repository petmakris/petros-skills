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
            String nodeId = resolveNodeId(repoRoot, prNumber);
            if (nodeId.isEmpty()) {
                notifyWarn(project, "Couldn't resolve PR #" + prNumber,
                    "`gh pr view " + prNumber + " --json id` returned nothing — is gh authenticated for this repo?");
                return;
            }
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

    /** {@code gh pr view <n> --json id --jq .id} → the PR's GraphQL node id, or "". */
    private static @NotNull String resolveNodeId(@NotNull String repoRoot, long number) {
        try {
            GeneralCommandLine cl = new GeneralCommandLine(
                "gh", "pr", "view", String.valueOf(number), "--json", "id", "--jq", ".id")
                .withWorkDirectory(repoRoot);
            ProcessOutput out = ExecUtil.execAndGetOutput(cl);
            return out.getExitCode() == 0 ? out.getStdout().trim() : "";
        } catch (Exception e) {
            LOG.warn("gh pr view failed", e);
            return "";
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
