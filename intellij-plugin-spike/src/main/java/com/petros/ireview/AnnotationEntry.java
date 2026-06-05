package com.petros.ireview;

/**
 * One row in the annotations side panel.
 *
 * @param anchor       Full anchor string, e.g. "src/.../Foo.java:R:37".
 * @param snippet      First 160 chars of the latest synthesis, with newlines collapsed.
 * @param version      Thread version (monotonically increasing per anchor).
 * @param updatedAt    Server-side updated_at timestamp (epoch seconds).
 * @param isNew        True if this row's version is greater than the last-seen version
 *                     recorded by the panel. Drives the yellow "updated" dot.
 */
public record AnnotationEntry(
    String anchor,
    String snippet,
    int version,
    long updatedAt,
    boolean isNew
) {}
