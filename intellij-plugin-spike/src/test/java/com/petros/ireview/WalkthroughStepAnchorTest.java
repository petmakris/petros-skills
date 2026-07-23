package com.petros.ireview;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalkthroughStepAnchorTest {

    private static WalkthroughStep step(int line, String snippet) {
        return new WalkthroughStep(1, "t", "F.java", line, snippet,
            WalkthroughStep.Role.CONTEXT, "m");
    }

    @Test void exactWhenSnippetStillOnRecordedLine() {
        List<String> lines = List.of("a();", "b();", "target();", "c();");
        var res = WalkthroughNavigator.resolveLine(lines, step(3, "target();"));
        assertEquals(AnchorResolver.Kind.EXACT, res.kind());
        assertEquals(3, res.line());
    }

    @Test void movedWhenCodeShiftedDown() {
        List<String> lines = List.of("new();", "new2();", "a();", "b();", "target();");
        var res = WalkthroughNavigator.resolveLine(lines, step(3, "target();"));
        assertEquals(AnchorResolver.Kind.MOVED, res.kind());
        assertEquals(5, res.line());
    }

    @Test void staleWhenSnippetGone() {
        List<String> lines = List.of("a();", "b();", "c();");
        var res = WalkthroughNavigator.resolveLine(lines, step(2, "target();"));
        assertEquals(AnchorResolver.Kind.STALE, res.kind());
        assertEquals(-1, res.line());
    }

    @Test void ignoresLeadingWhitespaceDifferences() {
        List<String> lines = List.of("a();", "        target();");
        var res = WalkthroughNavigator.resolveLine(lines, step(2, "target();"));
        assertEquals(AnchorResolver.Kind.EXACT, res.kind());
    }

    @Test void staleWhenSnippetIsAmbiguous() {
        List<String> lines = List.of("target();", "x();", "target();");
        var res = WalkthroughNavigator.resolveLine(lines, step(2, "target();"));
        assertEquals(AnchorResolver.Kind.STALE, res.kind());
    }
}
