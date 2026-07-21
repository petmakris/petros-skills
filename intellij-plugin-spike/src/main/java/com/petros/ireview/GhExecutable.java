package com.petros.ireview;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Locate the {@code gh} binary.
 *
 * <p>An IDE launched from the Dock inherits a minimal PATH that excludes the
 * package-manager directories where {@code gh} normally lives, so relying on the
 * process PATH alone makes the feature work from a terminal-launched IDE and
 * fail from a Dock-launched one. PATH is still searched first — it respects a
 * deliberately chosen install — with the usual locations as fallback.
 *
 * <p>Pure: the caller supplies PATH and the executable test, so the search order
 * is testable without touching the filesystem.
 */
public final class GhExecutable {

    /** Searched in order when PATH yields nothing. */
    public static final List<String> FALLBACK_DIRS = List.of(
        "/opt/homebrew/bin",   // Homebrew, Apple silicon
        "/usr/local/bin",      // Homebrew, Intel + manual installs
        "/opt/local/bin",      // MacPorts
        "/usr/bin",
        System.getProperty("user.home") + "/.local/bin");

    private GhExecutable() {}

    /**
     * @param pathEnv      the PATH to search, or null
     * @param isExecutable test for "this absolute path is a runnable file"
     * @return absolute path to gh, or null if nowhere to be found
     */
    public static @Nullable String resolve(@Nullable String pathEnv,
                                           Predicate<String> isExecutable) {
        List<String> dirs = new ArrayList<>();
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) dirs.add(dir);
            }
        }
        dirs.addAll(FALLBACK_DIRS);
        for (String dir : dirs) {
            String candidate = dir.endsWith("/") ? dir + "gh" : dir + "/gh";
            if (isExecutable.test(candidate)) return candidate;
        }
        return null;
    }
}
