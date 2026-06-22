package com.petros.ireview;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts synthesis markdown into HTML for the JCEF popup.
 *
 * Two custom rules preserve the popup's existing link behavior:
 *  - a markdown link whose destination is NOT http(s) is rewritten to the
 *    ireview-nav:// scheme (file:line navigation);
 *  - inline `code` is wrapped in an ireview-sym:// anchor (symbol lookup).
 *
 * Everything else (tables, lists, headings, fenced blocks, emphasis) comes
 * from commonmark-java's standard renderers. Pure: no IntelliJ dependencies,
 * so it is fully unit-testable.
 */
public final class SynthesisHtmlRenderer {

    /** Theme colors/font sourced from the IDE editor scheme by the caller. */
    public record Theme(String background, String foreground, String fontFamily,
                        int fontSize, String accent, String codeBackground,
                        String border) {}

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());

    private static final Parser PARSER = Parser.builder()
            .extensions(EXTENSIONS)
            .build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .attributeProviderFactory(ctx -> new NavLinkAttributeProvider())
            .nodeRendererFactory(SymbolCodeRenderer::new)
            .build();

    public static String toBodyHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        Node doc = PARSER.parse(markdown);
        return RENDERER.render(doc);
    }

    public static String toDocument(String markdown, Theme theme, String navScript) {
        String body = toBodyHtml(markdown);
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><style>");
        sb.append(css(theme));
        sb.append("</style></head><body>");
        sb.append(body);
        if (navScript != null && !navScript.isEmpty()) {
            sb.append("<script>").append(navScript).append("</script>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String css(Theme t) {
        return "html,body{margin:0;padding:0;}"
             + "body{background:" + t.background() + ";color:" + t.foreground() + ";"
             +   "font-family:" + t.fontFamily() + ",monospace;font-size: " + t.fontSize() + "px;"
             +   "line-height:1.45;padding:6px 8px;word-wrap:break-word;}"
             + "a{color:" + t.accent() + ";text-decoration:none;}"
             + "a:hover{text-decoration:underline;}"
             + "code{background:" + t.codeBackground() + ";border-radius:3px;padding:0 3px;}"
             + "pre{background:" + t.codeBackground() + ";border:1px solid " + t.border() + ";"
             +   "border-radius:4px;padding:8px 10px;overflow:auto;}"
             + "pre code{background:transparent;padding:0;}"
             + "table{border-collapse:collapse;margin:6px 0;}"
             + "th,td{border:1px solid " + t.border() + ";padding:4px 8px;text-align:left;}"
             + "th{background:" + t.codeBackground() + ";}"
             + "h1,h2,h3{margin:8px 0 4px;}"
             + "ul,ol{margin:4px 0;padding-left:22px;}"
             + "blockquote{margin:6px 0;padding-left:10px;border-left:3px solid " + t.border() + ";color:" + t.foreground() + ";}";
    }

    /** Rewrites non-http link destinations to the ireview-nav:// scheme. */
    private static final class NavLinkAttributeProvider implements AttributeProvider {
        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            if (!(node instanceof Link)) return;
            String href = attributes.get("href");
            if (href == null) return;
            if (href.startsWith("http://") || href.startsWith("https://")) return;
            attributes.put("href", "ireview-nav://" + href);
        }
    }

    /** Renders inline code as a clickable symbol-lookup link. */
    private static final class SymbolCodeRenderer implements NodeRenderer {
        private final HtmlWriter html;

        SymbolCodeRenderer(HtmlNodeRendererContext context) {
            this.html = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Code.class);
        }

        @Override
        public void render(Node node) {
            String literal = ((Code) node).getLiteral();
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("href", "ireview-sym://" + literal);
            html.tag("a", attrs);
            html.tag("code");
            html.text(literal);
            html.tag("/code");
            html.tag("/a");
        }
    }

    private SynthesisHtmlRenderer() {}
}
