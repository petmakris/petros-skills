package com.petros.ireview;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a unified diff (git / {@code gh pr diff}) into its changed files,
 * tracking the base-side (a/) path separately from the working-tree (b/) path.
 *
 * That separation is what makes renamed files diff correctly: the left pane
 * must be read from the base ref using the OLD path ({@link ChangedFile#basePath}),
 * while the right pane is the on-disk file at the NEW path ({@link ChangedFile#workPath}).
 * A naive b-side-only parse uses the new path for both, so the base lookup
 * (git show base:newPath) misses and the whole renamed file renders as added.
 */
final class DiffFileList {

    private DiffFileList() {}

    /**
     * One changed file. {@code basePath} is the path at the base ref (a-side),
     * {@code workPath} the working-tree path (b-side). They differ for a rename
     * or copy and are equal for an in-place modification. {@code basePath} is
     * null for an addition (no base blob); {@code workPath} is null for a
     * deletion (nothing on disk).
     */
    record ChangedFile(String basePath, String workPath) {}

    private static final Pattern DIFF_GIT = Pattern.compile("^diff --git a/(.+?) b/(.+)$");

    static List<ChangedFile> parse(String patch) {
        List<ChangedFile> out = new ArrayList<>();
        if (patch == null || patch.isEmpty()) return out;

        String aPath = null;
        String bPath = null;
        boolean added = false;
        boolean deleted = false;
        boolean inFile = false;

        for (String line : patch.split("\n", -1)) {
            Matcher m = DIFF_GIT.matcher(line);
            if (m.matches()) {
                flush(out, aPath, bPath, added, deleted);
                aPath = m.group(1).trim();
                bPath = m.group(2).trim();
                added = false;
                deleted = false;
                inFile = true;
                continue;
            }
            if (!inFile) continue;
            if (line.startsWith("new file mode")) {
                added = true;
            } else if (line.startsWith("deleted file mode")) {
                deleted = true;
            } else if (line.startsWith("rename from ")) {
                aPath = line.substring("rename from ".length()).trim();
            } else if (line.startsWith("rename to ")) {
                bPath = line.substring("rename to ".length()).trim();
            } else if (line.startsWith("copy from ")) {
                aPath = line.substring("copy from ".length()).trim();
            } else if (line.startsWith("copy to ")) {
                bPath = line.substring("copy to ".length()).trim();
            }
        }
        flush(out, aPath, bPath, added, deleted);
        return out;
    }

    private static void flush(List<ChangedFile> out, String aPath, String bPath,
                              boolean added, boolean deleted) {
        if (bPath == null || bPath.isEmpty()) return;  // no diff --git seen yet
        String basePath = added ? null : aPath;
        String workPath = deleted ? null : bPath;
        out.add(new ChangedFile(basePath, workPath));
    }
}
