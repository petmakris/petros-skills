package com.petros.ireview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UnifiedGutterPlacementTest {

    private static final String LEFT = "src/Foo.java";
    private static final String RIGHT = "src/Foo.java";

    @Test
    @DisplayName("A line present on both sides anchors to the right, matching side-by-side")
    void contextLineAnchorsRight() {
        var p = UnifiedGutterPlacement.choose(41, RIGHT, 37, LEFT);
        assertEquals("R", p.side());
        assertEquals(41, p.sideLine0());
        assertEquals(RIGHT, p.label());
    }

    @Test
    @DisplayName("An added line exists only on the right")
    void addedLineAnchorsRight() {
        var p = UnifiedGutterPlacement.choose(12, RIGHT, -1, LEFT);
        assertEquals("R", p.side());
        assertEquals(12, p.sideLine0());
    }

    @Test
    @DisplayName("A deleted line exists only on the left")
    void deletedLineAnchorsLeft() {
        var p = UnifiedGutterPlacement.choose(-1, RIGHT, 88, LEFT);
        assertEquals("L", p.side());
        assertEquals(88, p.sideLine0());
        assertEquals(LEFT, p.label());
    }

    @Test
    @DisplayName("A line on neither side gets no gutter icon")
    void unmappedLineGetsNothing() {
        assertNull(UnifiedGutterPlacement.choose(-1, RIGHT, -1, LEFT));
    }

    @Test
    @DisplayName("A right-side line falls back to the left when the file has no right path")
    void addedFileWithoutRightLabelFallsBack() {
        var p = UnifiedGutterPlacement.choose(5, null, 5, LEFT);
        assertEquals("L", p.side());
    }

    @Test
    @DisplayName("Line 0 is a real line, not an absent one")
    void zeroIsNotTreatedAsMissing() {
        var p = UnifiedGutterPlacement.choose(0, RIGHT, -1, LEFT);
        assertEquals("R", p.side());
        assertEquals(0, p.sideLine0());
    }
}
