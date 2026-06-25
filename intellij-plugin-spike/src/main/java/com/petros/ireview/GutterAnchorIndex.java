package com.petros.ireview;

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
            switch (res.kind()) {
                case EXACT, MOVED -> out.put(res.line(), new LineAnchor(false, anchor));
                case STALE -> out.put(recorded, new LineAnchor(true, anchor));
            }
        }
        return out;
    }
}
