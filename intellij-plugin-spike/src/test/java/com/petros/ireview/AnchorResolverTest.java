package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.petros.ireview.AnchorResolver.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class AnchorResolverTest {
    private static final int K = 25;

    @Test void exactWhenLineUnchanged() {
        var lines = List.of("a", "  return foo();", "b");
        var r = AnchorResolver.resolve(lines, 2, "return foo();", K);
        assertEquals(EXACT, r.kind());
        assertEquals(2, r.line());
    }

    @Test void movedWhenUniqueNearby() {
        var lines = List.of("new", "a", "  return foo();", "b");
        var r = AnchorResolver.resolve(lines, 2, "return foo();", K);
        assertEquals(MOVED, r.kind());
        assertEquals(3, r.line());
    }

    @Test void staleWhenGone() {
        var lines = List.of("a", "b", "c");
        var r = AnchorResolver.resolve(lines, 2, "return foo();", K);
        assertEquals(STALE, r.kind());
        assertEquals(-1, r.line());
    }

    @Test void staleWhenAmbiguous() {
        var lines = List.of("x", "}", "y", "}", "z");
        var r = AnchorResolver.resolve(lines, 1, "}", K);
        assertEquals(STALE, r.kind());
    }

    @Test void exactWhenAnchorTextBlank() {
        var lines = List.of("a", "b");
        var r = AnchorResolver.resolve(lines, 1, "   ", K);
        assertEquals(EXACT, r.kind());
        assertEquals(1, r.line());
    }

    @Test void staleWhenOutsideWindow() {
        var lines = new java.util.ArrayList<String>();
        for (int i = 0; i < 100; i++) lines.add("filler" + i);
        lines.set(80, "target();"); // 1-based line 81
        var r = AnchorResolver.resolve(lines, 1, "target();", 25); // window 1..26 — out of range
        assertEquals(STALE, r.kind());
    }

    @Test void recordedLinePastEndStillSearches() {
        var lines = List.of("a", "  return foo();", "b");
        var r = AnchorResolver.resolve(lines, 999, "return foo();", K);
        assertEquals(MOVED, r.kind());
        assertEquals(2, r.line());
    }
}
