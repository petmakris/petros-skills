package com.petros.ireview;

/**
 * One row in the annotations side panel.
 *
 * @param anchor    Full anchor string, e.g. "src/.../Foo.java:R:37".
 * @param title     The resolved row label (agent title, or a fallback).
 * @param version   Thread version (monotonically increasing per anchor).
 * @param updatedAt Server-side updated_at timestamp (epoch seconds).
 * @param isNew     True if this row's version exceeds the panel's last-seen
 *                  version. Drives the "updated" dot.
 */
public record AnnotationEntry(
    String anchor,
    String title,
    int version,
    long updatedAt,
    boolean isNew
) {}
