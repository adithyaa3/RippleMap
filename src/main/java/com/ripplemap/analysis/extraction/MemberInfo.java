package com.ripplemap.analysis.extraction;

import com.github.javaparser.ast.Node;
import com.ripplemap.analysis.model.EntityKind;

/**
 * A method or constructor declared in a source type; a node of the method-level graph.
 * Uses identity equality (AST nodes have structural equality, which must not leak into lookups).
 */
public final class MemberInfo {

    private final String id;
    private final EntityKind kind;
    private final String name;
    private final String displaySignature;
    private final TypeInfo declaringType;
    private final Node node;
    private final int parameterCount;
    private final boolean varargs;
    private final boolean isStatic;

    MemberInfo(String id, EntityKind kind, String name, String displaySignature, TypeInfo declaringType,
               Node node, int parameterCount, boolean varargs, boolean isStatic) {
        this.id = id;
        this.kind = kind;
        this.name = name;
        this.displaySignature = displaySignature;
        this.declaringType = declaringType;
        this.node = node;
        this.parameterCount = parameterCount;
        this.varargs = varargs;
        this.isStatic = isStatic;
    }

    public String id() {
        return id;
    }

    public EntityKind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public String displaySignature() {
        return displaySignature;
    }

    public TypeInfo declaringType() {
        return declaringType;
    }

    public Node node() {
        return node;
    }

    public int parameterCount() {
        return parameterCount;
    }

    public boolean varargs() {
        return varargs;
    }

    public boolean isStatic() {
        return isStatic;
    }

    /** Whether a call with {@code argumentCount} arguments is applicable by arity. */
    public boolean acceptsArity(int argumentCount) {
        if (varargs) {
            return argumentCount >= parameterCount - 1;
        }
        return argumentCount == parameterCount;
    }

    @Override
    public String toString() {
        return id;
    }
}
