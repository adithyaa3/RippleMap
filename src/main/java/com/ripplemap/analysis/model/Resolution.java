package com.ripplemap.analysis.model;

/**
 * How a reference was linked to its target.
 * Only {@link #RESOLVED} and {@link #INFERRED} references become graph edges.
 */
public enum Resolution {
    /** The JavaParser symbol solver resolved the reference to a source declaration. */
    RESOLVED,
    /** The solver failed; a documented conservative rule identified a unique source target. */
    INFERRED,
    /** No reliable target could be identified; recorded as a diagnostic only. */
    UNRESOLVED;

    public static Resolution strongest(Resolution a, Resolution b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
