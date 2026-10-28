package com.ripplemap.analysis.extraction;

import com.github.javaparser.ast.Node;
import com.ripplemap.analysis.model.ExtractedEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Index of all source declarations of one analysis run. */
public final class DeclarationIndex {

    private final List<ParsedUnit> units;
    private final List<TypeInfo> types = new ArrayList<>();
    private final Map<String, TypeInfo> typesById = new HashMap<>();
    private final Map<Node, TypeInfo> typeByNode = new IdentityHashMap<>();
    private final List<MemberInfo> members = new ArrayList<>();
    private final Map<String, MemberInfo> membersById = new HashMap<>();
    private final Map<Node, MemberInfo> memberByNode = new IdentityHashMap<>();
    private final Map<String, List<TypeInfo>> typesByCanonicalName = new HashMap<>();
    private final Map<String, List<TypeInfo>> typesBySimpleName = new HashMap<>();
    private final Set<String> packagePrefixes = new HashSet<>();
    private final Set<String> methodNames = new HashSet<>();
    private final Set<String> fieldNames = new HashSet<>();
    private final Set<String> unparsedTypeNames = new HashSet<>();
    private final List<ExtractedEntity> entities = new ArrayList<>();
    private int fieldCount;

    DeclarationIndex(List<ParsedUnit> units, Set<String> unparsedTypeNames) {
        this.units = List.copyOf(units);
        this.unparsedTypeNames.addAll(unparsedTypeNames);
        for (ParsedUnit unit : units) {
            String pkg = unit.packageName();
            if (!pkg.isEmpty()) {
                int dot = -1;
                do {
                    dot = pkg.indexOf('.', dot + 1);
                    packagePrefixes.add(dot < 0 ? pkg : pkg.substring(0, dot));
                } while (dot >= 0);
            }
        }
    }

    void addType(TypeInfo type) {
        types.add(type);
        typesById.put(type.id(), type);
        typeByNode.put(type.node(), type);
        typesByCanonicalName.computeIfAbsent(type.canonicalName(), k -> new ArrayList<>(1)).add(type);
        typesBySimpleName.computeIfAbsent(type.simpleName(), k -> new ArrayList<>(1)).add(type);
    }

    void addMember(MemberInfo member) {
        members.add(member);
        membersById.put(member.id(), member);
        memberByNode.put(member.node(), member);
    }

    void addEntity(ExtractedEntity entity) {
        entities.add(entity);
    }

    void addMethodName(String name) {
        methodNames.add(name);
    }

    void addFieldName(String name) {
        fieldNames.add(name);
    }

    void incrementFieldCount() {
        fieldCount++;
    }

    boolean hasMemberId(String id) {
        return membersById.containsKey(id);
    }

    public List<ParsedUnit> units() {
        return units;
    }

    public List<TypeInfo> types() {
        return Collections.unmodifiableList(types);
    }

    public List<MemberInfo> members() {
        return Collections.unmodifiableList(members);
    }

    public List<ExtractedEntity> entities() {
        return Collections.unmodifiableList(entities);
    }

    public int fieldCount() {
        return fieldCount;
    }

    public TypeInfo typeById(String id) {
        return typesById.get(id);
    }

    public MemberInfo memberById(String id) {
        return membersById.get(id);
    }

    public TypeInfo typeForNode(Node node) {
        return typeByNode.get(node);
    }

    public MemberInfo memberForNode(Node node) {
        return memberByNode.get(node);
    }

    /** All project types with this canonical name (more than one means a duplicate declaration). */
    public List<TypeInfo> typesByCanonicalName(String canonicalName) {
        return typesByCanonicalName.getOrDefault(canonicalName, List.of());
    }

    /** The unique project type with this canonical name, or null if absent or duplicated. */
    public TypeInfo uniqueType(String canonicalName) {
        List<TypeInfo> candidates = typesByCanonicalName.get(canonicalName);
        return candidates != null && candidates.size() == 1 ? candidates.get(0) : null;
    }

    public List<TypeInfo> typesBySimpleName(String simpleName) {
        return typesBySimpleName.getOrDefault(simpleName, List.of());
    }

    public boolean isTypeSimpleName(String simpleName) {
        return typesBySimpleName.containsKey(simpleName);
    }

    /**
     * Whether the name is the base name of a project file that could not be parsed. Such a file most likely
     * declares a type of that name, so references to it are reported as unresolved rather than external.
     */
    public boolean isUnparsedTypeName(String simpleName) {
        return unparsedTypeNames.contains(simpleName) && !typesBySimpleName.containsKey(simpleName);
    }

    /** Whether some project type declares (explicitly or implicitly) a method with this name. */
    public boolean isMethodName(String name) {
        return methodNames.contains(name);
    }

    /** Whether some project type declares a field, enum constant or record component with this name. */
    public boolean isFieldName(String name) {
        return fieldNames.contains(name);
    }

    /** Whether the name is a declared project package or a prefix of one. */
    public boolean isPackageOrPrefix(String name) {
        return packagePrefixes.contains(name);
    }

    public Map<String, List<TypeInfo>> canonicalNameIndex() {
        return Collections.unmodifiableMap(typesByCanonicalName);
    }
}
