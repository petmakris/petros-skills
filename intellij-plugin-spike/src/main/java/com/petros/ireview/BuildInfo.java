package com.petros.ireview;

import org.jetbrains.annotations.NotNull;

import java.util.Properties;

/**
 * Reads the per-build stamp baked into the jar by the {@code generateBuildInfo}
 * Gradle task. Lets the UI show exactly which build is loaded — the plugin
 * version (git commit count) doesn't move between rebuilds, but {@code buildTime}
 * does, so a stale plugin is immediately obvious after a restart.
 */
final class BuildInfo {

    private static final String RESOURCE = "/com/petros/ireview/build-info.properties";

    private BuildInfo() {}

    /** e.g. {@code "b85 · 16:05:03"} (full date in {@link #tooltip()}). */
    static @NotNull String label() {
        Properties p = load();
        String count = p.getProperty("gitCount", "?");
        String time = p.getProperty("buildTime", "");
        String clock = time.length() >= 19 ? time.substring(11) : time;  // HH:mm:ss
        return "b" + count + (clock.isEmpty() ? "" : " · " + clock);
    }

    /** Full build stamp for a tooltip. */
    static @NotNull String tooltip() {
        Properties p = load();
        String time = p.getProperty("buildTime", "?");
        String count = p.getProperty("gitCount", "?");
        return "Built " + time + " (git #" + count + ")";
    }

    private static @NotNull Properties load() {
        Properties p = new Properties();
        try (var in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {
            // Missing/unreadable stamp → empty props → "b?" — never throw into the UI.
        }
        return p;
    }
}
