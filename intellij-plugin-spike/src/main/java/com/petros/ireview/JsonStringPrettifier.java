package com.petros.ireview;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pretty-prints JSON that may contain {@code {{placeholder}}} tokens, which by
 * themselves make the text invalid JSON (e.g. {@code "id": {{orderId}}}).
 *
 * <p>The tokens are swapped for JSON-valid sentinels before reformatting, then
 * restored afterwards preserving their original quoting. No external dependencies;
 * the reformatter is a string-aware structural re-indenter (2 spaces per depth)
 * that validates by bracket/quote balance only.
 */
public final class JsonStringPrettifier {

    // Private-use code points, won't collide with real JSON content.
    private static final char SENT_OPEN = '\uE000';
    private static final char SENT_CLOSE = '\uE001';

    private JsonStringPrettifier() {
    }

    /** Returns the reformatted JSON, or empty if {@code content} is not structurally valid JSON. */
    public static Optional<String> prettify(String content) {
        Tokenized t = tokenize(content);
        return reindent(t.text).map(t::restore);
    }

    private static String sentinel(int index) {
        return SENT_OPEN + Integer.toString(index) + SENT_CLOSE;
    }

    private record Tokenized(String text, List<String> tokens, List<Boolean> quoted) {
        String restore(String formatted) {
            String r = formatted;
            for (int i = 0; i < tokens.size(); i++) {
                String sent = sentinel(i);
                if (quoted.get(i)) {
                    r = r.replace('"' + sent + '"', tokens.get(i));
                } else {
                    r = r.replace(sent, tokens.get(i));
                }
            }
            return r;
        }
    }

    /**
     * Replaces every {@code {{...}}} run with a sentinel. In value/key position the
     * sentinel is wrapped in quotes so the surrounding text becomes valid JSON; inside
     * an existing string it is left bare. Real JSON never produces a literal {@code {{}
     * (object keys must be strings), so this never triggers on genuine structure.
     */
    private static Tokenized tokenize(String s) {
        StringBuilder out = new StringBuilder();
        List<String> tokens = new ArrayList<>();
        List<Boolean> quoted = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    out.append(c);
                    escaped = false;
                    i++;
                } else if (c == '\\') {
                    out.append(c);
                    escaped = true;
                    i++;
                } else if (c == '"') {
                    out.append(c);
                    inString = false;
                    i++;
                } else if (isTokenStart(s, i)) {
                    int end = s.indexOf("}}", i + 2);
                    if (end < 0) {
                        out.append(c);
                        i++;
                    } else {
                        tokens.add(s.substring(i, end + 2));
                        quoted.add(false);
                        out.append(sentinel(tokens.size() - 1));
                        i = end + 2;
                    }
                } else {
                    out.append(c);
                    i++;
                }
            } else if (c == '"') {
                out.append(c);
                inString = true;
                i++;
            } else if (isTokenStart(s, i)) {
                int end = s.indexOf("}}", i + 2);
                if (end < 0) {
                    out.append(c);
                    i++;
                } else {
                    tokens.add(s.substring(i, end + 2));
                    quoted.add(true);
                    out.append('"').append(sentinel(tokens.size() - 1)).append('"');
                    i = end + 2;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return new Tokenized(out.toString(), tokens, quoted);
    }

    private static boolean isTokenStart(String s, int i) {
        return s.charAt(i) == '{' && i + 1 < s.length() && s.charAt(i + 1) == '{';
    }

    /**
     * Structural re-indenter. Collapses whitespace outside strings; emits a newline plus
     * 2-space-per-depth indent after {@code { [} and after each {@code ,}, and before
     * {@code } ]}. Empty {@code {}}/{@code []} stay inline. Returns empty on unbalanced
     * brackets or an unterminated string.
     */
    private static Optional<String> reindent(String s) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    out.append(c);
                }
                case '{', '[' -> {
                    out.append(c);
                    char close = c == '{' ? '}' : ']';
                    int j = nextNonWhitespace(s, i + 1);
                    if (j < n && s.charAt(j) == close) {
                        out.append(close);
                        i = j;
                    } else {
                        depth++;
                        out.append('\n').append(indent(depth));
                    }
                }
                case '}', ']' -> {
                    depth--;
                    if (depth < 0) {
                        return Optional.empty();
                    }
                    out.append('\n').append(indent(depth)).append(c);
                }
                case ',' -> out.append(c).append('\n').append(indent(depth));
                case ':' -> out.append(": ");
                default -> out.append(c);
            }
        }
        if (depth != 0 || inString) {
            return Optional.empty();
        }
        return Optional.of(out.toString());
    }

    private static int nextNonWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String indent(int depth) {
        return "  ".repeat(depth);
    }
}
