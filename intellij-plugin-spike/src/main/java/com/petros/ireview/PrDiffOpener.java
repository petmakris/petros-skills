package com.petros.ireview;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-click "Show Diff with Working Tree" for an interactive-review PR.
 *
 * Reproduces what the user does by hand (Git → branch → Show Diff with
 * Working Tree) for every file the PR touches: left pane = the file at the
 * PR base ref, right pane = the on-disk working-tree file. The right pane
 * being a real {@code VirtualFile} under the project root is load-bearing —
 * that's the only shape {@link SpikeDiffExtension} attaches its gutter icons
 * to, so opening the diff this way is what makes the ask-{@code +} appear.
 *
 * The PR base branch comes from {@code <stateDir>/meta.json}; the changed
 * file list from {@code <stateDir>/diff.patch}.
 */
final class PrDiffOpener {

    private static final Logger LOG = Logger.getInstance(PrDiffOpener.class);

    private PrDiffOpener() {}

    static void open(@NotNull Project project, @NotNull ReviewSessionClient.SessionInfo session) {
        String stateDir = session.stateDir();
        if (stateDir == null || stateDir.isEmpty()) {
            notifyWarn(project, "No session state", "The review session didn't report a state directory.");
            return;
        }
        String repoRoot = project.getBasePath();
        if (repoRoot == null) {
            notifyWarn(project, "No project root", "Can't locate the git working tree for this project.");
            return;
        }

        // git calls + file reads are blocking — keep them off the EDT.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String meta = readString(Path.of(stateDir, "meta.json"));
                String patch = readString(Path.of(stateDir, "diff.patch"));
                String base = jsonField(meta, "base");
                if (base.isEmpty()) base = "master";
                String baseRef = resolveBaseRef(repoRoot, base);

                List<DiffFileList.ChangedFile> files = DiffFileList.parse(patch);
                if (files.isEmpty()) {
                    notifyWarn(project, "Nothing to diff", "No changed files found in the PR diff.");
                    return;
                }

                List<DiffRequest> requests = new ArrayList<>();
                for (DiffFileList.ChangedFile file : files) {
                    DiffRequest req = buildRequest(project, repoRoot, baseRef, file);
                    if (req != null) requests.add(req);
                }
                if (requests.isEmpty()) {
                    notifyWarn(project, "Nothing to diff",
                        "Changed files couldn't be opened (binary, or missing on disk).");
                    return;
                }

                SimpleDiffRequestChain chain = new SimpleDiffRequestChain(requests);
                int count = requests.size();
                SwingUtilities.invokeLater(() -> {
                    DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT);
                    notifyInfo(project, "Opened " + count + " PR file" + (count == 1 ? "" : "s"),
                        "All in one diff window — step through them with the ↑/↓ (next/previous "
                        + "difference) buttons in the diff toolbar; it crosses file boundaries.");
                });
            } catch (Exception e) {
                LOG.warn("PrDiffOpener failed", e);
                notifyWarn(project, "Couldn't open PR diff", String.valueOf(e.getMessage()));
            }
        });
    }

    /**
     * Left = file content at {@code baseRef}, read from the base-side path
     * (the renamed-from path for a rename; empty for files added in the PR);
     * right = the working-tree file at the b-side path. Returns null for binary
     * files or files that exist neither on disk nor at the base ref.
     */
    private static @Nullable DiffRequest buildRequest(@NotNull Project project,
                                                      @NotNull String repoRoot,
                                                      @NotNull String baseRef,
                                                      @NotNull DiffFileList.ChangedFile file) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        String basePath = file.basePath();
        String workPath = file.workPath();

        VirtualFile vf = workPath == null
            ? null
            : LocalFileSystem.getInstance().findFileByPath(repoRoot + "/" + workPath);
        if (vf != null && vf.getFileType().isBinary()) return null;

        // Read the base pane from the OLD path. For a rename this is the
        // renamed-from path, so the diff shows removals against the original
        // file instead of rendering the new-named file as entirely added.
        String baseText = basePath == null ? "" : gitShow(repoRoot, baseRef + ":" + basePath);

        DiffContent left;
        DiffContent right;
        if (vf != null) {
            FileType type = vf.getFileType();
            left = factory.create(project, baseText, type);
            right = factory.create(project, vf);
        } else {
            // Deleted in the PR — nothing on disk to annotate, but still show it.
            if (baseText.isEmpty()) return null;
            left = factory.create(project, baseText);
            right = factory.createEmpty();
        }
        String title;
        if (workPath == null) {
            title = basePath + " (deleted)";
        } else if (basePath != null && !basePath.equals(workPath)) {
            title = basePath + " → " + workPath;
        } else {
            title = workPath;
        }
        return new SimpleDiffRequest(title, left, right, baseRef, "Working Tree");
    }

    /** Prefer {@code origin/<base>} (what the user picks by hand); fall back to
     *  the local branch name if origin doesn't have it. */
    private static @NotNull String resolveBaseRef(@NotNull String repoRoot, @NotNull String base) {
        String origin = "origin/" + base;
        if (refExists(repoRoot, origin)) return origin;
        if (refExists(repoRoot, base)) return base;
        return origin;  // best effort; gitShow will just return "" if it's wrong
    }

    private static boolean refExists(@NotNull String repoRoot, @NotNull String ref) {
        try {
            GeneralCommandLine cl = new GeneralCommandLine("git", "rev-parse", "--verify", "--quiet", ref)
                .withWorkDirectory(repoRoot);
            return ExecUtil.execAndGetOutput(cl).getExitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** {@code git show <rev>:<path>}; returns "" on any failure (e.g. the path
     *  didn't exist at that revision — a file added in the PR). */
    private static @NotNull String gitShow(@NotNull String repoRoot, @NotNull String revPath) {
        try {
            GeneralCommandLine cl = new GeneralCommandLine("git", "show", revPath)
                .withWorkDirectory(repoRoot)
                .withCharset(StandardCharsets.UTF_8);
            ProcessOutput out = ExecUtil.execAndGetOutput(cl);
            return out.getExitCode() == 0 ? out.getStdout() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static @NotNull String readString(@NotNull Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Minimal scalar-string extractor, matching the codebase's no-JSON-dep style. */
    private static @NotNull String jsonField(@NotNull String json, @NotNull String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static void notifyWarn(@NotNull Project project, @NotNull String title, @NotNull String body) {
        notify(project, title, body, com.intellij.notification.NotificationType.WARNING);
    }

    private static void notifyInfo(@NotNull Project project, @NotNull String title, @NotNull String body) {
        notify(project, title, body, com.intellij.notification.NotificationType.INFORMATION);
    }

    private static void notify(@NotNull Project project, @NotNull String title, @NotNull String body,
                               @NotNull com.intellij.notification.NotificationType type) {
        com.intellij.notification.Notifications.Bus.notify(
            new com.intellij.notification.Notification("Interactive Review", title, body, type),
            project);
    }
}
