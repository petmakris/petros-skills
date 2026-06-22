package com.petros.ireview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownLinkRendererTest {

    @Test
    void plainTextHasNoLinks() {
        String html = MarkdownLinkRenderer.toHtml("Just words, no links.");
        assertEquals("Just words, no links.", html);
    }

    @Test
    void pathLinkBecomesAnchorTagWithIreviewNavScheme() {
        String html = MarkdownLinkRenderer.toHtml(
            "see [forDashboard](src/main/java/Foo.java:18) for details");
        assertEquals(
            "see <a href=\"ireview-nav://src/main/java/Foo.java:18\" class=\"ref-code\">forDashboard</a> for details",
            html);
    }

    @Test
    void urlLinkBecomesAnchorTagWithExternalScheme() {
        String html = MarkdownLinkRenderer.toHtml(
            "[PMP-171](https://example.com/PMP-171) is the ticket");
        assertEquals(
            "<a href=\"https://example.com/PMP-171\" class=\"ref-ticket\">PMP-171</a> is the ticket",
            html);
    }

    @Test
    void escapesHtmlInLabelsAndText() {
        String html = MarkdownLinkRenderer.toHtml("a < b and [x](y.java:1)");
        assertEquals("a &lt; b and <a href=\"ireview-nav://y.java:1\" class=\"ref-code\">x</a>", html);
    }

    @Test
    void parseNavTargetExtractsPathAndLine() {
        MarkdownLinkRenderer.NavTarget t = MarkdownLinkRenderer.parseNavTarget(
            "ireview-nav://src/main/java/Foo.java:18");
        assertEquals("src/main/java/Foo.java", t.path());
        assertEquals(18, t.line());
    }

    @Test
    void parseNavTargetWithoutLine() {
        MarkdownLinkRenderer.NavTarget t = MarkdownLinkRenderer.parseNavTarget(
            "ireview-nav://Foo.java");
        assertEquals("Foo.java", t.path());
        assertEquals(-1, t.line());
    }

    @Test
    void backtickCodeBecomesSymbolAnchor() {
        String html = MarkdownLinkRenderer.toHtml("call `Foo` here");
        assertEquals(
            "call <a href=\"ireview-sym://Foo\" class=\"ref-sym\">Foo</a> here",
            html);
    }

    @Test
    void backtickContentIsHtmlEscaped() {
        String html = MarkdownLinkRenderer.toHtml("see `Map<K,V>`");
        assertEquals(
            "see <a href=\"ireview-sym://Map&lt;K,V&gt;\" class=\"ref-sym\">Map&lt;K,V&gt;</a>",
            html);
    }

    @Test
    void backtickContentWithDoubleQuotesDoesNotBreakAttribute() {
        // A symbol with a string literal — the embedded " must be escaped, else
        // it terminates the href attribute early and the rest leaks as markup.
        String html = MarkdownLinkRenderer.toHtml(
            "seeds via `DatabasePopulator.fromScript(\"scripts/<TestClass>/init.sql\")`");
        assertEquals(
            "seeds via <a href=\"ireview-sym://DatabasePopulator.fromScript(&quot;scripts/&lt;TestClass&gt;/init.sql&quot;)\""
                + " class=\"ref-sym\">DatabasePopulator.fromScript(&quot;scripts/&lt;TestClass&gt;/init.sql&quot;)</a>",
            html);
    }
}
