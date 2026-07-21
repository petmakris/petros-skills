package com.petros.ireview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GhExecutableTest {

    private static java.util.function.Predicate<String> present(String... paths) {
        Set<String> set = Set.of(paths);
        return set::contains;
    }

    @Test
    @DisplayName("PATH wins over the fallback locations")
    void pathTakesPrecedence() {
        String found = GhExecutable.resolve("/custom/bin:/usr/bin",
            present("/custom/bin/gh", "/opt/homebrew/bin/gh"));
        assertEquals("/custom/bin/gh", found);
    }

    @Test
    @DisplayName("A Dock-launched IDE's minimal PATH still finds a Homebrew install")
    void fallbackCoversMinimalPath() {
        String found = GhExecutable.resolve("/usr/bin:/bin",
            present("/opt/homebrew/bin/gh"));
        assertEquals("/opt/homebrew/bin/gh", found);
    }

    @Test
    @DisplayName("A null PATH is not fatal — fallbacks are still searched")
    void nullPathFallsBack() {
        assertEquals("/usr/local/bin/gh",
            GhExecutable.resolve(null, present("/usr/local/bin/gh")));
    }

    @Test
    @DisplayName("Nowhere to be found reports null rather than a bogus path")
    void missingEverywhere() {
        assertNull(GhExecutable.resolve("/usr/bin:/bin", present()));
    }

    @Test
    @DisplayName("Trailing slashes in PATH entries don't produce a doubled separator")
    void trailingSlashHandled() {
        assertEquals("/custom/bin/gh",
            GhExecutable.resolve("/custom/bin/", present("/custom/bin/gh")));
    }

    @Test
    @DisplayName("Blank PATH entries are skipped")
    void blankEntriesSkipped() {
        assertEquals("/opt/homebrew/bin/gh",
            GhExecutable.resolve("::", present("/opt/homebrew/bin/gh")));
    }
}
