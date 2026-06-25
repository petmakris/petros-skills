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
        var cache = Map.of("file.java:R:2", new ThreadState("syn", 1, "return foo();", "", ""));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.containsKey(2));
        assertFalse(idx.get(2).stale());
        assertEquals("file.java:R:2", idx.get(2).ownerAnchor());
    }

    @Test void movedThreadIndexesAtNewLine() {
        var lines = List.of("inserted", "a", "  return foo();", "b");
        var cache = Map.of("file.java:R:2", new ThreadState("syn", 1, "return foo();", "", ""));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.containsKey(3));
        assertFalse(idx.get(3).stale());
        assertFalse(idx.containsKey(2)); // not painted at the old line
    }

    @Test void staleThreadIndexesAtRecordedLineMarkedStale() {
        var lines = List.of("a", "b", "c");
        var cache = Map.of("file.java:R:2", new ThreadState("syn", 1, "return foo();", "", ""));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.get(2).stale());
    }

    @Test void ignoresOtherFilesAndSides() {
        var lines = List.of("  return foo();");
        var cache = Map.of(
            "other.java:R:1", new ThreadState("s", 1, "return foo();", "", ""),
            "file.java:L:1", new ThreadState("s", 1, "return foo();", "", ""));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.isEmpty());
    }

    @Test void legacyNullAnchorTextTreatedAsExact() {
        var lines = List.of("x", "y");
        var cache = Map.of("file.java:R:1", new ThreadState("s", 1, "", "", ""));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.containsKey(1));
        assertFalse(idx.get(1).stale());
    }

    @Test void nonStaleBeatsStaleOnSameLine() {
        // Thread A: recorded@1, text found at line 3 → MOVED (non-stale) at display line 3
        // Thread B: recorded@3, text not in document → STALE at display line 3
        // Both collide at display line 3; non-stale (A) must win.
        var lines = List.of("a", "b", "exact text here");
        var cache = Map.of(
            "file.java:R:1", new ThreadState("s", 1, "exact text here", "", ""),  // MOVED → display line 3
            "file.java:R:3", new ThreadState("s", 1, "no such line text", "", "") // STALE → display line 3
        );
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertEquals(1, idx.size(), "should be exactly one entry at display line 3");
        assertTrue(idx.containsKey(3));
        assertFalse(idx.get(3).stale(), "non-stale thread must beat the stale thread");
    }

    @Test void exactBeatsMovedOnSameLine() {
        // Thread A: recorded@3, text matches line 3 → EXACT at display line 3
        // Thread B: recorded@1, text also found at line 3 → MOVED at display line 3
        // Both collide at display line 3; EXACT (A, ownerAnchor = file.java:R:3) must win.
        var lines = List.of("a", "b", "exact text here");
        var cache = Map.of(
            "file.java:R:3", new ThreadState("s", 1, "exact text here", "", ""), // EXACT → line 3
            "file.java:R:1", new ThreadState("s", 1, "exact text here", "", "")  // MOVED → line 3
        );
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertEquals(1, idx.size(), "should be exactly one entry at display line 3");
        assertTrue(idx.containsKey(3));
        assertEquals("file.java:R:3", idx.get(3).ownerAnchor(),
            "EXACT thread (recorded@3) must beat MOVED thread (recorded@1)");
    }
}
