package com.petros.ireview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SynthesisHtmlRendererTest {

    @Test
    void gfmTableRendersAsTableElement() {
        String md = "| a | b |\n|---|---|\n| 1 | 2 |\n";
        String html = SynthesisHtmlRenderer.toBodyHtml(md);
        assertTrue(html.contains("<table"), html);
        assertTrue(html.contains("<th"), html);
        assertTrue(html.contains("<td"), html);
    }

    @Test
    void pathLinkIsRewrittenToIreviewNavScheme() {
        String html = SynthesisHtmlRenderer.toBodyHtml("[Foo](src/Foo.java:18)");
        assertTrue(html.contains("href=\"ireview-nav://src/Foo.java:18\""), html);
    }

    @Test
    void httpLinkStaysExternal() {
        String html = SynthesisHtmlRenderer.toBodyHtml("[t](https://example.com/x)");
        assertTrue(html.contains("href=\"https://example.com/x\""), html);
        assertFalse(html.contains("ireview-nav://"), html);
    }

    @Test
    void inlineCodeBecomesSymbolLink() {
        String html = SynthesisHtmlRenderer.toBodyHtml("call `Foo` now");
        assertTrue(html.contains("href=\"ireview-sym://Foo\""), html);
        assertTrue(html.contains("<code>Foo</code>"), html);
    }

    @Test
    void inlineCodeWithAngleBracketsIsEscaped() {
        String html = SynthesisHtmlRenderer.toBodyHtml("see `Map<K,V>`");
        assertTrue(html.contains("ireview-sym://Map&lt;K,V&gt;"), html);
        assertTrue(html.contains("<code>Map&lt;K,V&gt;</code>"), html);
    }

    @Test
    void inlineCodeWithDoubleQuoteIsEscapedInAttribute() {
        String html = SynthesisHtmlRenderer.toBodyHtml("via `f(\"x\")`");
        assertTrue(html.contains("ireview-sym://f(&quot;x&quot;)"), html);
    }

    @Test
    void fencedCodeBlockIsPreAndNotASymbolLink() {
        String html = SynthesisHtmlRenderer.toBodyHtml("```\nint x = 1;\n```\n");
        assertTrue(html.contains("<pre>"), html);
        assertFalse(html.contains("ireview-sym://"), html);
    }

    @Test
    void unorderedListRenders() {
        String html = SynthesisHtmlRenderer.toBodyHtml("- one\n- two\n");
        assertTrue(html.contains("<ul>"), html);
        assertTrue(html.contains("<li>one</li>"), html);
    }

    @Test
    void headingRenders() {
        String html = SynthesisHtmlRenderer.toBodyHtml("# Title\n");
        assertTrue(html.contains("<h1>Title</h1>"), html);
    }

    @Test
    void emptyMarkdownIsEmptyBody() {
        assertEquals("", SynthesisHtmlRenderer.toBodyHtml(""));
        assertEquals("", SynthesisHtmlRenderer.toBodyHtml(null));
    }

    @Test
    void documentWrapsBodyWithThemeAndScript() {
        SynthesisHtmlRenderer.Theme theme = new SynthesisHtmlRenderer.Theme(
            "#1e1f22", "#d8d8d8", "monospace", 13, "#3b72e8", "#2b2d30", "#393b40");
        String doc = SynthesisHtmlRenderer.toDocument("# Hi\n", theme, "console.log(1);");
        assertTrue(doc.contains("<html>"), doc);
        assertTrue(doc.contains("<h1>Hi</h1>"), doc);
        assertTrue(doc.contains("#1e1f22"), doc);
        assertTrue(doc.contains("font-size: 13px"), doc);
        assertTrue(doc.contains("<script>console.log(1);</script>"), doc);
    }

    @Test
    void documentOmitsScriptTagWhenNull() {
        SynthesisHtmlRenderer.Theme theme = new SynthesisHtmlRenderer.Theme(
            "#000", "#fff", "monospace", 12, "#00f", "#111", "#222");
        String doc = SynthesisHtmlRenderer.toDocument("hi", theme, null);
        assertFalse(doc.contains("<script>"), doc);
    }

    @Test
    void rawHtmlScriptIsEscaped() {
        String html = SynthesisHtmlRenderer.toBodyHtml("<script>alert(1)</script>");
        assertFalse(html.contains("<script>"), html);
        assertTrue(html.contains("&lt;script&gt;"), html);
    }

    @Test
    void rawHtmlImgIsEscaped() {
        String html = SynthesisHtmlRenderer.toBodyHtml("text <img src=x onerror=alert(1)> more");
        assertFalse(html.contains("<img"), html);
    }

    @Test
    void markdownImageDoesNotEmitImgTagAndKeepsAltText() {
        String html = SynthesisHtmlRenderer.toBodyHtml("![logo](https://evil/track.png)");
        assertFalse(html.contains("<img"), html);
        assertFalse(html.contains("track.png"), html);
        assertTrue(html.contains("logo"), html);
    }

    @Test
    void javascriptLinkIsNeutralizedToNavScheme() {
        String html = SynthesisHtmlRenderer.toBodyHtml("[x](javascript:alert(1))");
        assertFalse(html.contains("href=\"javascript:"), html);
        assertTrue(html.contains("ireview-nav://"), html);
    }

    @Test
    void pathLinkStillRewrittenAfterEscapeHtml() {
        String html = SynthesisHtmlRenderer.toBodyHtml("[Foo](src/Foo.java:18)");
        assertTrue(html.contains("href=\"ireview-nav://src/Foo.java:18\""), html);
    }

    @Test
    void httpLinkStillExternalAfterEscapeHtml() {
        String html = SynthesisHtmlRenderer.toBodyHtml("[t](https://example.com/x)");
        assertTrue(html.contains("href=\"https://example.com/x\""), html);
    }

    @Test
    void inlineCodeDoubleQuoteEscapedInCodeText() {
        String html = SynthesisHtmlRenderer.toBodyHtml("via `f(\"x\")`");
        assertTrue(html.contains("<code>f(&quot;x&quot;)</code>"), html);
    }
}
