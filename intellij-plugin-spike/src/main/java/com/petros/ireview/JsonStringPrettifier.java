package com.petros.ireview;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pretty-prints JSON that may contain {@code {{placeholder}}} tokens.
 *
 * <p>A bare {@code {{placeholder}}} in value or key position (e.g. {@code "id": {{orderId}}})
 * is not valid JSON, so a parser would reject it outright. We work around that with a small
 * hand-written tokenizer that swaps each such token for a quoted, JSON-valid sentinel; the
 * resulting valid JSON is then formatted by Gson; finally the original tokens are restored,
 * keeping their original (unquoted) form. Placeholders that already sit inside a JSON string
 * are left untouched — they're valid as-is and Gson preserves them verbatim.
 */
public final class JsonStringPrettifier {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private JsonStringPrettifier() {
    }

    /** Returns the reformatted JSON, or empty if the content (post-tokenization) isn't valid JSON. */
    public static Optional<String> prettify(String content) {
        String nonce = newNonce();
        Tokenized t = tokenize(content, nonce);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(t.text);
        } catch (JsonSyntaxException e) {
            return Optional.empty();
        }
        return Optional.of(t.restore(GSON.toJson(parsed)));
    }

    /**
     * A per-invocation random nonce, woven into every sentinel. Without it,
     * {@code restore}'s global string-replace would clobber any literal
     * {@code @@IREVIEW_PH_<i>@@} text that happened to sit inside a real string
     * value in the input. A random nonce the input can't predict makes that
     * collision impossible.
     */
    private static String newNonce() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private static String sentinel(String nonce, int index) {
        return "@@IREVIEW_PH_" + nonce + "_" + index + "@@";
    }

    private record Tokenized(String text, String nonce, List<String> tokens) {
        String restore(String formatted) {
            String r = formatted;
            for (int i = 0; i < tokens.size(); i++) {
                // Each sentinel was emitted quoted, so drop the quotes Gson kept around it.
                r = r.replace('"' + sentinel(nonce, i) + '"', tokens.get(i));
            }
            return r;
        }
    }

    /**
     * Replaces every {@code {{...}}} run that sits <em>outside</em> a JSON string with a quoted
     * sentinel, making the text valid JSON. Tokens already inside a string are left in place.
     * Real JSON never produces a literal {@code {{} outside a string (object keys must be quoted),
     * so this never triggers on genuine structure.
     */
    private static Tokenized tokenize(String s, String nonce) {
        StringBuilder out = new StringBuilder();
        List<String> tokens = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        int i = 0;
        int n = s.length();
        while (i < n) {
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
                i++;
            } else if (c == '"') {
                out.append(c);
                inString = true;
                i++;
            } else if (c == '{' && i + 1 < n && s.charAt(i + 1) == '{') {
                int end = s.indexOf("}}", i + 2);
                if (end < 0) {
                    out.append(c);
                    i++;
                } else {
                    tokens.add(s.substring(i, end + 2));
                    out.append('"').append(sentinel(nonce, tokens.size() - 1)).append('"');
                    i = end + 2;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return new Tokenized(out.toString(), nonce, tokens);
    }
}
