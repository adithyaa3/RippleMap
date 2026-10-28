package com.ripplemap.graph.traversal;

/** Traversal direction relative to the "A depends on B" edge convention. */
public enum Direction {
    /** Follow outgoing edges: what the start node depends on. */
    DEPENDENCIES,
    /** Follow incoming edges: what depends on the start node (impact propagation). */
    DEPENDENTS
}
