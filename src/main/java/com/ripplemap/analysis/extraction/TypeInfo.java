package com.ripplemap.analysis.extraction;

import com.github.javaparser.ast.body.TypeDeclaration;
import com.ripplemap.analysis.model.EntityKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A source type that is a node of the type-level graph (top-level or member type). */
public final class TypeInfo {

    private final String id;
    private final String canonicalName;
    private final String simpleName;
    private final String displayName;
    private final String packageName;
    private final EntityKind kind;
    private final TypeDeclaration<?> node;
    private final ParsedUnit unit;
    private final TypeInfo enclosing;
    private final List<TypeInfo> nestedTypes = new ArrayList<>();
    private final Map<String, List<MemberInfo>> methodsByName = new LinkedHashMap<>();
    private final List<MemberInfo> constructors = new ArrayList<>();
    private final Set<String> fieldNames = new LinkedHashSet<>();
    private final Set<String> implicitMethodNames = new LinkedHashSet<>();
    private final List<TypeInfo> sourceSupertypes = new ArrayList<>();
    private boolean nonSourceSupertype;

    TypeInfo(String id, String canonicalName, String simpleName, String displayName, String packageName,
             EntityKind kind, TypeDeclaration<?> node, ParsedUnit unit, TypeInfo enclosing) {
        this.id = id;
        this.canonicalName = canonicalName;
        this.simpleName = simpleName;
        this.displayName = displayName;
        this.packageName = packageName;
        this.kind = kind;
        this.node = node;
        this.unit = unit;
        this.enclosing = enclosing;
    }

    public String id() {
        return id;
    }

    public String canonicalName() {
        return canonicalName;
    }

    public String simpleName() {
        return simpleName;
    }

    public String displayName() {
        return displayName;
    }

    public String packageName() {
        return packageName;
    }

    public EntityKind kind() {
        return kind;
    }

    public TypeDeclaration<?> node() {
        return node;
    }

    public ParsedUnit unit() {
        return unit;
    }

    public TypeInfo enclosing() {
        return enclosing;
    }

    public List<TypeInfo> nestedTypes() {
        return Collections.unmodifiableList(nestedTypes);
    }

    void addNested(TypeInfo nested) {
        nestedTypes.add(nested);
    }

    public Map<String, List<MemberInfo>> methodsByName() {
        return Collections.unmodifiableMap(methodsByName);
    }

    void addMethod(MemberInfo method) {
        methodsByName.computeIfAbsent(method.name(), k -> new ArrayList<>()).add(method);
    }

    public List<MemberInfo> constructors() {
        return Collections.unmodifiableList(constructors);
    }

    void addConstructor(MemberInfo constructor) {
        constructors.add(constructor);
    }

    public Set<String> fieldNames() {
        return Collections.unmodifiableSet(fieldNames);
    }

    void addFieldName(String name) {
        fieldNames.add(name);
    }

    /** Implicitly declared methods without AST (enum values/valueOf, record accessors). */
    public Set<String> implicitMethodNames() {
        return Collections.unmodifiableSet(implicitMethodNames);
    }

    void addImplicitMethodName(String name) {
        implicitMethodNames.add(name);
    }

    public List<TypeInfo> sourceSupertypes() {
        return Collections.unmodifiableList(sourceSupertypes);
    }

    void addSourceSupertype(TypeInfo supertype) {
        if (supertype != this && !sourceSupertypes.contains(supertype)) {
            sourceSupertypes.add(supertype);
        }
    }

    /** True when at least one declared supertype is not a project type (or could not be resolved). */
    public boolean hasNonSourceSupertype() {
        return nonSourceSupertype;
    }

    void markNonSourceSupertype() {
        this.nonSourceSupertype = true;
    }

    public String filePath() {
        return unit.relativePath();
    }

    @Override
    public String toString() {
        return id;
    }
}
