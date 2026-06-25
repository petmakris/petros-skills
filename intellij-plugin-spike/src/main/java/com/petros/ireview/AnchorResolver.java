package com.petros.ireview;

import java.util.List;

/**
 * Resolves a recorded annotation anchor against the live document. The anchor's
 * line number drifts when code is edited/rebased; we re-locate it by the line's
 * stored text. Pure and side-effect free so it can be unit-tested in isolation.
 */
public final class AnchorResolver {

    public static final int DEFAULT_K = 25;

    public enum Kind { EXACT, MOVED, STALE }

    /** @param line 1-based resolved line for EXACT/MOVED, -1 for STALE. */
    public record Resolution(Kind kind, int line) {}

    private AnchorResolver() {}

    /**
     * @param lines             document lines (0-indexed list, each without newline)
     * @param recordedLine1Based the anchor's recorded line number (1-based)
     * @param anchorText        the line text captured when the annotation was created
     * @param k                 search radius in lines around the recorded line
     */
    public static Resolution resolve(List<String> lines, int recordedLine1Based,
                                     String anchorText, int k) {
        String needle = anchorText == null ? "" : anchorText.strip();
        // Blank/unknown text isn't matchable — keep today's behavior.
        if (needle.isEmpty()) return new Resolution(Kind.EXACT, recordedLine1Based);

        int idx = recordedLine1Based - 1; // to 0-based
        if (idx >= 0 && idx < lines.size() && lines.get(idx).strip().equals(needle)) {
            return new Resolution(Kind.EXACT, recordedLine1Based);
        }
        // Clamp the recorded line to the valid range before computing the window
        int searchIdx = Math.max(0, Math.min(idx, lines.size() - 1));
        int lo = Math.max(0, searchIdx - k);
        int hi = Math.min(lines.size() - 1, searchIdx + k);
        int match = -1;
        for (int i = lo; i <= hi; i++) {
            if (lines.get(i).strip().equals(needle)) {
                if (match != -1) return new Resolution(Kind.STALE, -1); // ambiguous
                match = i;
            }
        }
        if (match == -1) return new Resolution(Kind.STALE, -1);
        return new Resolution(Kind.MOVED, match + 1);
    }
}
