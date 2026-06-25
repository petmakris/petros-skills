package com.petros.ireview;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves the single readable label for an annotations-panel row.
 *
 * Pure and Swing-free. A thread's row leads with a clean title; this picks it
 * via a fallback chain and flattens markdown to plain text for the synthesis
 * rung. Uses the commonmark dependency already bundled in the plugin.
 */
public final class PanelRowTitle {

    private static final Pattern LINK_URL_PATTERN = Pattern.compile(" \\([^)]+\\)");

    private static final List<Extension> EXT = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXT).build();
    private static final TextContentRenderer TEXT =
        TextContentRenderer.builder().extensions(EXT).build();

    private PanelRowTitle() {}

    /**
     * Row label fallback chain: title → question → first line of plain-text
     * synthesis → anchor. Whitespace-only rungs are skipped.
     */
    public static String resolve(String title, String question, String synthesis, String anchor) {
        String t = collapse(title);
        if (!t.isEmpty()) return t;
        String q = collapse(question);
        if (!q.isEmpty()) return q;
        String s = firstLinePlainText(synthesis);
        if (!s.isEmpty()) return s;
        return anchor == null ? "" : anchor;
    }

    /** Flatten markdown to plain text and return its first non-blank line. */
    public static String firstLinePlainText(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        String text = TEXT.render(PARSER.parse(markdown));
        text = LINK_URL_PATTERN.matcher(text).replaceAll("");
        for (String line : text.split("\n")) {
            String t = collapse(line);
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static String collapse(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }
}
