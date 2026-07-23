package com.petros.ireview;

/**
 * One stop on a guided tour. Immutable; produced by {@link WalkthroughDoc#parse}.
 *
 * <p>{@code line} is a hint only — {@code snippet} is the verbatim text of the
 * anchored line and is what {@link AnchorResolver} uses to re-locate the step
 * after the file shifts.
 */
public record WalkthroughStep(int id, String title, String file, int line,
                              String snippet, Role role, String markdown) {

    public enum Role {
        /** Explains existing behaviour. */
        CONTEXT,
        /** Where behaviour is extended without editing this code. */
        SEAM,
        /** Where new code actually goes. */
        EDIT_SITE;

        /** Unknown / missing roles degrade to CONTEXT rather than failing the tour. */
        public static Role from(String raw) {
            if (raw == null) return CONTEXT;
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "seam" -> SEAM;
                case "edit-site", "edit_site" -> EDIT_SITE;
                default -> CONTEXT;
            };
        }
    }

    /** Thread anchor for this step — must match the server's {@code step:<id>} form. */
    public String anchor() {
        return "step:" + id;
    }
}
