package com.petros.ireview;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses synthesis text with markdown-style links into HTML for JEditorPane.
 *
 * Two link kinds:
 *  - URL link  [text](https://...)      external open
 *  - Path link [text](path[:line])      IDE navigation
 *
 * Path links get a custom ireview-nav:// scheme so the HyperlinkListener
 * in SynthesisPopup can route them through IntelliJ's OpenFileDescriptor
 * rather than the system browser.
 */
public final class MarkdownLinkRenderer {

    // Match the four supported markdown forms, in priority order:
    //   1. fenced code block  ```lang?\n CONTENT \n```   →  group 1 = content
    //   2. bold               **text**                    →  group 2 = text
    //   3. link               [label](target)             →  group 3, 4
    //   4. inline code        `text`                      →  group 5 = text
    // (?s) = DOTALL — fenced content may span lines.
    private static final Pattern INLINE = Pattern.compile(
        "(?s)```(?:[A-Za-z][A-Za-z0-9_+\\-]*)?\\s*?\\r?\\n?(.*?)```" + // fenced block
        "|\\*\\*([^*]+)\\*\\*"                                          + // bold
        "|\\[([^\\]]+)\\]\\(([^)]+)\\)"                                 + // link
        "|`([^`\\n]+)`"                                                   // inline code
    );
    private static final Pattern PATH_LINE = Pattern.compile("^(.+?):(\\d+)$");

    public record NavTarget(String path, int line) {}

    public static String toHtml(String synthesis) {
        if (synthesis == null) return "";
        StringBuilder out = new StringBuilder();
        Matcher m = INLINE.matcher(synthesis);
        int last = 0;
        while (m.find()) {
            out.append(escapeAndBreak(synthesis.substring(last, m.start())));
            if (m.group(1) != null) {
                // Fenced code block — preserve newlines via <pre>
                out.append("<pre class=\"code-block\">")
                   .append(escape(m.group(1).stripTrailing()))
                   .append("</pre>");
            } else if (m.group(2) != null) {
                // Bold
                out.append("<b>").append(escape(m.group(2))).append("</b>");
            } else if (m.group(3) != null) {
                // Markdown link
                String label = m.group(3);
                String target = m.group(4);
                boolean isUrl = target.startsWith("http://") || target.startsWith("https://");
                if (isUrl) {
                    out.append("<a href=\"").append(escape(target))
                       .append("\" class=\"ref-ticket\">")
                       .append(escape(label)).append("</a>");
                } else {
                    out.append("<a href=\"ireview-nav://").append(escape(target))
                       .append("\" class=\"ref-code\">")
                       .append(escape(label)).append("</a>");
                }
            } else if (m.group(5) != null) {
                // Inline backtick code → symbol-lookup link
                String sym = m.group(5);
                out.append("<a href=\"ireview-sym://").append(escape(sym))
                   .append("\" class=\"ref-sym\">")
                   .append(escape(sym)).append("</a>");
            }
            last = m.end();
        }
        out.append(escapeAndBreak(synthesis.substring(last)));
        return out.toString();
    }

    /** Escape HTML AND convert paragraph breaks. \n\n → <br><br>, single \n → space. */
    private static String escapeAndBreak(String s) {
        return escape(s)
            .replace("\n\n", "<br><br>")
            .replace("\n", " ");
    }

    public static NavTarget parseNavTarget(String url) {
        if (!url.startsWith("ireview-nav://")) {
            throw new IllegalArgumentException("not an ireview-nav URL: " + url);
        }
        String rest = url.substring("ireview-nav://".length());
        Matcher m = PATH_LINE.matcher(rest);
        if (m.matches()) {
            return new NavTarget(m.group(1), Integer.parseInt(m.group(2)));
        }
        return new NavTarget(rest, -1);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private MarkdownLinkRenderer() {}
}
