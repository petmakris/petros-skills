package com.petros.ireview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PanelRowTitleTest {

    @Test void titleWinsWhenPresent() {
        assertEquals("My Title",
            PanelRowTitle.resolve("My Title", "the question", "the **synthesis**", "a:R:1"));
    }

    @Test void questionWhenNoTitle() {
        assertEquals("the question",
            PanelRowTitle.resolve("", "the question", "the synthesis", "a:R:1"));
    }

    @Test void synthesisFirstLineWhenNoTitleOrQuestion() {
        assertEquals("Because foo is null",
            PanelRowTitle.resolve("  ", "", "Because **foo** is null\n\nmore detail", "a:R:1"));
    }

    @Test void anchorWhenEverythingBlank() {
        assertEquals("a:R:1", PanelRowTitle.resolve("", "", "   ", "a:R:1"));
    }

    @Test void whitespaceOnlyRungsAreSkipped() {
        assertEquals("real", PanelRowTitle.resolve("\t\n", "  ", "real", "a:R:1"));
    }

    @Test void firstLineStripsBoldCodeAndLinks() {
        String md = "Use `foo()` and **bar**, see [the file](src/X.java:10).";
        String out = PanelRowTitle.firstLinePlainText(md);
        assertFalse(out.contains("**"));
        assertFalse(out.contains("`"));
        assertFalse(out.contains("src/X.java"));   // link target (url) gone
        assertFalse(out.contains("]("));           // link markup gone
        assertTrue(out.contains("foo()"));         // code text (with its parens) kept
        assertTrue(out.contains("bar"));
        assertTrue(out.contains("the file"));  // link label kept
    }

    @Test void firstLineTakesHeadingThenStops() {
        assertEquals("Heading", PanelRowTitle.firstLinePlainText("# Heading\n\nbody text"));
    }

    @Test void firstLineOfBlankIsEmpty() {
        assertEquals("", PanelRowTitle.firstLinePlainText("   \n  "));
        assertEquals("", PanelRowTitle.firstLinePlainText(null));
    }

    @Test void firstLineKeepsNonLinkParentheticals() {
        String out = PanelRowTitle.firstLinePlainText("foo can be null (in edge cases) here");
        assertTrue(out.contains("(in edge cases)"));
    }
}
