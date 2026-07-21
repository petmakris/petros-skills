package com.petros.ireview;

import org.jetbrains.annotations.Nullable;

/**
 * Which side a unified-diff screen line anchors to. Pure so the rule can be
 * tested without an IDE: the platform supplies the two candidate line numbers,
 * this decides which one an annotation on that screen line belongs to.
 *
 * <p>The right side wins whenever the line exists there, so context lines — which
 * exist on both — anchor to the post-change file, matching the side-by-side
 * behaviour of hovering the right pane. Deleted lines exist only on the left.
 */
public final class UnifiedGutterPlacement {

    /** @param sideLine0 0-based line within that side's own document. */
    public record Placement(String side, String label, int sideLine0) {}

    private UnifiedGutterPlacement() {}

    /**
     * @param rightLine0 the right-side line for this screen line, or -1
     * @param rightLabel project-relative path of the right side, or null
     * @param leftLine0  the left-side line for this screen line, or -1
     * @param leftLabel  project-relative path of the left side, or null
     * @return null when the screen line anchors to neither side (no gutter icon)
     */
    public static @Nullable Placement choose(int rightLine0, @Nullable String rightLabel,
                                             int leftLine0, @Nullable String leftLabel) {
        if (rightLine0 >= 0 && rightLabel != null) {
            return new Placement("R", rightLabel, rightLine0);
        }
        if (leftLine0 >= 0 && leftLabel != null) {
            return new Placement("L", leftLabel, leftLine0);
        }
        return null;
    }
}
