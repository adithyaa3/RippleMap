package com.ripplemap.analysis.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Relationship kinds. Every relationship is directed: {@code source -> target}
 * means the source depends on the target.
 */
public enum RelationshipKind {
    EXTENDS("Extends", true, false),
    IMPLEMENTS("Implements", true, false),
    FIELD_TYPE("Field type", true, false),
    METHOD_PARAMETER_TYPE("Method parameter type", true, false),
    METHOD_RETURN_TYPE("Method return type", true, false),
    CONSTRUCTOR_PARAMETER_TYPE("Constructor parameter type", true, false),
    OBJECT_CREATION("Object creation", true, true),
    METHOD_CALL("Method call", true, true),
    METHOD_REFERENCE("Method reference", true, true),
    CONSTRUCTOR_CALL("Explicit constructor call", true, true),
    FIELD_ACCESS("Field access", true, false),
    ANNOTATION_USAGE("Annotation usage", true, false),
    TYPE_REFERENCE("Other type reference", true, false);

    private final String label;
    private final boolean typeLayer;
    private final boolean methodLayer;

    RelationshipKind(String label, boolean typeLayer, boolean methodLayer) {
        this.label = label;
        this.typeLayer = typeLayer;
        this.methodLayer = methodLayer;
    }

    public String label() {
        return label;
    }

    public boolean appliesTo(GraphLayer layer) {
        return layer == GraphLayer.TYPE ? typeLayer : methodLayer;
    }

    public static Set<RelationshipKind> forLayer(GraphLayer layer) {
        EnumSet<RelationshipKind> kinds = EnumSet.noneOf(RelationshipKind.class);
        for (RelationshipKind kind : values()) {
            if (kind.appliesTo(layer)) {
                kinds.add(kind);
            }
        }
        return kinds;
    }
}
