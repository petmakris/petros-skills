package com.petros.ireview;

import com.petros.ireview.ReviewSessionClient.ThreadState;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GutterAnchorIndexTest {
    private static final int K = 25;

    @Test void exactThreadIndexesAtRecordedLine() {
        var lines = List.of("a", "  return foo();", "b");
        var cache = Map.of("file.java:R:2", new ThreadState("syn", 1, "return foo();"));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.containsKey(2));
        assertFalse(idx.get(2).stale());
        assertEquals("file.java:R:2", idx.get(2).ownerAnchor());
    }

    @Test void movedThreadIndexesAtNewLine() {
        var lines = List.of("inserted", "a", "  return foo();", "b");
        var cache = Map.of("file.java:R:2", new ThreadState("syn", 1, "return foo();"));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.containsKey(3));
        assertFalse(idx.get(3).stale());
        assertFalse(idx.containsKey(2)); // not painted at the old line
    }

    @Test void staleThreadIndexesAtRecordedLineMarkedStale() {
        var lines = List.of("a", "b", "c");
        var cache = Map.of("file.java:R:2", new ThreadState("syn", 1, "return foo();"));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.get(2).stale());
    }

    @Test void ignoresOtherFilesAndSides() {
        var lines = List.of("  return foo();");
        var cache = Map.of(
            "other.java:R:1", new ThreadState("s", 1, "return foo();"),
            "file.java:L:1", new ThreadState("s", 1, "return foo();"));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.isEmpty());
    }

    @Test void legacyNullAnchorTextTreatedAsExact() {
        var lines = List.of("x", "y");
        var cache = Map.of("file.java:R:1", new ThreadState("s", 1, ""));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.containsKey(1));
        assertFalse(idx.get(1).stale());
    }
}
