package com.petros.ireview;

import com.petros.ireview.AnchorResolver.Kind;
import com.petros.ireview.ReviewSessionClient.ThreadState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure index from a display line (1-based) to the annotation thread that should
 * render there, for one diff side of one file. Built fresh from the live
 * document lines + the client's thread cache; consulted by the gutter renderer.
 */
public final class GutterAnchorIndex {

    /** @param ownerAnchor the thread's recorded anchor (for click/tooltip). */
    public record LineAnchor(boolean stale, String ownerAnchor) {}

    private GutterAnchorIndex() {}

    public static Map<Integer, LineAnchor> build(List<String> lines,
                                                 Map<String, ThreadState> cache,
                                                 String label, String side, int k) {
        Map<Integer, LineAnchor> out = new HashMap<>();
        // Parallel: candidate metadata needed for collision resolution.
        Map<Integer, long[]> priority = new HashMap<>(); // display line → [kindRank, recordedLine]
        String prefix = label + ":" + side + ":";
        for (var e : cache.entrySet()) {
            String anchor = e.getKey();
            if (!anchor.startsWith(prefix)) continue;
            int recorded;
            try {
                recorded = Integer.parseInt(anchor.substring(prefix.length()));
            } catch (NumberFormatException nfe) {
                continue; // general/non-line anchor
            }
            var res = AnchorResolver.resolve(lines, recorded, e.getValue().anchorText(), k);
            int displayLine;
            Kind kind = res.kind();
            boolean stale;
            switch (kind) {
                case EXACT, MOVED -> { displayLine = res.line(); stale = false; }
                case STALE -> { displayLine = recorded; stale = true; }
                default -> { continue; }
            }
            // kindRank: EXACT=0, MOVED=1, STALE=2 — lower is higher priority
            long kindRank = switch (kind) {
                case EXACT -> 0L;
                case MOVED -> 1L;
                case STALE -> 2L;
            };
            long[] existing = priority.get(displayLine);
            if (existing != null) {
                long existingKindRank = existing[0];
                long existingRecorded  = existing[1];
                // Keep existing if it is strictly higher priority
                if (existingKindRank < kindRank) continue;
                if (existingKindRank == kindRank && existingRecorded <= recorded) continue;
            }
            out.put(displayLine, new LineAnchor(stale, anchor));
            priority.put(displayLine, new long[]{kindRank, recorded});
        }
        return out;
    }
}
