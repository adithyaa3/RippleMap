package com.ripplemap.analysis.model;

/**
 * Kinds of source declarations extracted by the analyzer.
 * Type kinds are nodes of the type-level graph, callable kinds are nodes of the
 * method-level graph, and fields are recorded as metadata only.
 */
public enum EntityKind {
    CLASS(GraphLayer.TYPE),
    INTERFACE(GraphLayer.TYPE),
    ENUM(GraphLayer.TYPE),
    RECORD(GraphLayer.TYPE),
    ANNOTATION(GraphLayer.TYPE),
    METHOD(GraphLayer.METHOD),
    CONSTRUCTOR(GraphLayer.METHOD),
    FIELD(null);

    private final GraphLayer layer;

    EntityKind(GraphLayer layer) {
        this.layer = layer;
    }

    /** The graph layer this kind belongs to, or {@code null} for metadata-only kinds. */
    public GraphLayer layer() {
        return layer;
    }

    public boolean isType() {
        return layer == GraphLayer.TYPE;
    }

    public boolean isCallable() {
        return layer == GraphLayer.METHOD;
    }
}
