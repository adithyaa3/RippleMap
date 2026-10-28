package com.ripplemap.analysis.resolution;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.TypeParameter;
import com.ripplemap.analysis.extraction.DeclarationIndex;
import com.ripplemap.analysis.extraction.ParsedUnit;
import com.ripplemap.analysis.extraction.StableIds;
import com.ripplemap.analysis.extraction.TypeInfo;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Conservative, JLS-ordered type-name lookup over the project index. It is only consulted when the
 * JavaParser symbol solver could not resolve a name, and every match it produces is labeled INFERRED.
 *
 * <p>Lookup order for a simple name {@code N} (first match wins):
 * <ol>
 *   <li>a type parameter or local class named {@code N} in scope: not a project dependency;</li>
 *   <li>a lexically enclosing project type named {@code N}, or a member type of one (including member
 *       types inherited from project supertypes);</li>
 *   <li>a single-type import ending in {@code N} (an import of a non-project type means EXTERNAL);</li>
 *   <li>a top-level type {@code N} in the same package;</li>
 *   <li>exactly one project type provided by on-demand imports (several means AMBIGUOUS);</li>
 *   <li>otherwise EXTERNAL if {@code java.lang.N} or an imported JDK package provides it, LIKELY_EXTERNAL
 *       if an on-demand import of a non-project package could provide it, NOT_FOUND otherwise.</li>
 * </ol>
 * Qualified names are matched exactly against canonical names, or resolved head-first
 * ({@code Outer.Inner}); names qualified by a package outside the project are EXTERNAL.
 */
public final class NameLookup {

    public enum Status {
        FOUND,
        TYPE_PARAMETER,
        LOCAL_TYPE,
        EXTERNAL,
        LIKELY_EXTERNAL,
        AMBIGUOUS,
        NOT_FOUND
    }

    public record Result(Status status, TypeInfo type, String rule) {
        static Result found(TypeInfo type, String rule) {
            return new Result(Status.FOUND, type, rule);
        }

        static Result of(Status status) {
            return new Result(status, null, null);
        }
    }

    private final DeclarationIndex index;
    private final Predicate<String> jdkType;
    private boolean hierarchyAvailable;

    /**
     * @param jdkType tests whether a canonical name denotes a JDK type
     */
    public NameLookup(DeclarationIndex index, Predicate<String> jdkType) {
        this.index = index;
        this.jdkType = jdkType;
    }

    /** Enables lookup of member types inherited from project supertypes (after the hierarchy is built). */
    public void enableInheritedMemberTypes() {
        this.hierarchyAvailable = true;
    }

    public Result lookup(String nameWithScope, Node context, ParsedUnit unit) {
        if (nameWithScope.indexOf('.') < 0) {
            return lookupSimple(nameWithScope, context, unit);
        }
        return lookupQualified(nameWithScope, context, unit);
    }

    private Result lookupQualified(String name, Node context, ParsedUnit unit) {
        List<TypeInfo> exact = index.typesByCanonicalName(name);
        if (exact.size() == 1) {
            return Result.found(exact.get(0), "QUALIFIED_NAME");
        }
        if (exact.size() > 1) {
            return Result.of(Status.AMBIGUOUS);
        }
        int dot = name.indexOf('.');
        String head = name.substring(0, dot);
        String rest = name.substring(dot + 1);
        Result headResult = lookupSimple(head, context, unit);
        switch (headResult.status()) {
            case FOUND -> {
                List<TypeInfo> nested = index.typesByCanonicalName(headResult.type().canonicalName() + "." + rest);
                if (nested.size() == 1) {
                    return Result.found(nested.get(0), headResult.rule() + "+MEMBER_TYPE");
                }
                if (nested.size() > 1) {
                    return Result.of(Status.AMBIGUOUS);
                }
                if (hierarchyAvailable && rest.indexOf('.') < 0) {
                    TypeInfo inherited = inheritedMemberType(headResult.type(), rest);
                    if (inherited != null) {
                        return Result.found(inherited, headResult.rule() + "+INHERITED_MEMBER_TYPE");
                    }
                }
                return headResult.type().hasNonSourceSupertype()
                        ? Result.of(Status.LIKELY_EXTERNAL) : Result.of(Status.NOT_FOUND);
            }
            case EXTERNAL, LIKELY_EXTERNAL, AMBIGUOUS -> {
                return Result.of(headResult.status());
            }
            case TYPE_PARAMETER, LOCAL_TYPE -> {
                return Result.of(Status.LOCAL_TYPE);
            }
            default -> {
                String qualifier = name.substring(0, name.lastIndexOf('.'));
                if (!index.isPackageOrPrefix(qualifier) && index.typesByCanonicalName(qualifier).isEmpty()) {
                    return Result.of(Status.EXTERNAL);
                }
                return Result.of(Status.NOT_FOUND);
            }
        }
    }

    private Result lookupSimple(String name, Node context, ParsedUnit unit) {
        TypeInfo enclosing = null;
        for (Node current = context; current != null; current = current.getParentNode().orElse(null)) {
            // type parameters of every enclosing declaration are in scope (e.g. an inner class using Outer<T>'s T)
            if (current instanceof NodeWithTypeParameters<?> withTypeParameters) {
                for (TypeParameter parameter : withTypeParameters.getTypeParameters()) {
                    if (parameter.getNameAsString().equals(name)) {
                        return Result.of(Status.TYPE_PARAMETER);
                    }
                }
            }
            if (enclosing != null) {
                continue;
            }
            if (current instanceof BlockStmt block && declaresLocalType(block, name)) {
                return Result.of(Status.LOCAL_TYPE);
            }
            if (current instanceof TypeDeclaration<?> declaration) {
                TypeInfo info = index.typeForNode(declaration);
                if (info != null) {
                    enclosing = info;
                } else if (declaration.getNameAsString().equals(name) || hasMemberType(declaration, name)) {
                    return Result.of(Status.LOCAL_TYPE);
                }
            }
        }
        for (TypeInfo type = enclosing; type != null; type = type.enclosing()) {
            if (type.simpleName().equals(name)) {
                return Result.found(type, "ENCLOSING_TYPE");
            }
            for (TypeInfo nested : type.nestedTypes()) {
                if (nested.simpleName().equals(name)) {
                    return Result.found(nested, "MEMBER_TYPE");
                }
            }
            if (hierarchyAvailable) {
                TypeInfo inherited = inheritedMemberType(type, name);
                if (inherited != null) {
                    return Result.found(inherited, "INHERITED_MEMBER_TYPE");
                }
            }
        }
        String imported = unit.singleTypeImports().get(name);
        if (imported != null) {
            List<TypeInfo> candidates = index.typesByCanonicalName(imported);
            if (candidates.size() == 1) {
                return Result.found(candidates.get(0), "SINGLE_TYPE_IMPORT");
            }
            return Result.of(candidates.isEmpty() ? Status.EXTERNAL : Status.AMBIGUOUS);
        }
        List<TypeInfo> samePackage = index.typesByCanonicalName(StableIds.qualify(unit.packageName(), name));
        if (samePackage.size() == 1) {
            return Result.found(samePackage.get(0), "SAME_PACKAGE");
        }
        if (samePackage.size() > 1) {
            return Result.of(Status.AMBIGUOUS);
        }
        Set<TypeInfo> onDemand = new LinkedHashSet<>();
        boolean jdkMatch = false;
        boolean externalOnDemand = false;
        for (String prefix : unit.onDemandImports()) {
            List<TypeInfo> candidates = index.typesByCanonicalName(prefix + "." + name);
            onDemand.addAll(candidates);
            boolean projectPrefix = index.isPackageOrPrefix(prefix) || !index.typesByCanonicalName(prefix).isEmpty();
            if (!projectPrefix) {
                if (jdkType.test(prefix + "." + name)) {
                    jdkMatch = true;
                } else {
                    externalOnDemand = true;
                }
            }
        }
        if (onDemand.size() == 1) {
            return Result.found(onDemand.iterator().next(), "ON_DEMAND_IMPORT");
        }
        if (onDemand.size() > 1) {
            return Result.of(Status.AMBIGUOUS);
        }
        if (jdkMatch || jdkType.test("java.lang." + name)) {
            return Result.of(Status.EXTERNAL);
        }
        if (externalOnDemand) {
            return Result.of(Status.LIKELY_EXTERNAL);
        }
        return Result.of(Status.NOT_FOUND);
    }

    /** Breadth-first search for a member type named {@code name} declared in a project supertype. */
    public TypeInfo inheritedMemberType(TypeInfo type, String name) {
        ArrayDeque<TypeInfo> queue = new ArrayDeque<>(type.sourceSupertypes());
        Set<TypeInfo> seen = new HashSet<>();
        seen.add(type);
        while (!queue.isEmpty()) {
            TypeInfo current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            for (TypeInfo nested : current.nestedTypes()) {
                if (nested.simpleName().equals(name)) {
                    return nested;
                }
            }
            queue.addAll(current.sourceSupertypes());
        }
        return null;
    }

    private static boolean declaresLocalType(BlockStmt block, String name) {
        for (Statement statement : block.getStatements()) {
            if (statement instanceof LocalClassDeclarationStmt local
                    && local.getClassDeclaration().getNameAsString().equals(name)) {
                return true;
            }
            if (statement instanceof LocalRecordDeclarationStmt local
                    && local.getRecordDeclaration().getNameAsString().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMemberType(TypeDeclaration<?> declaration, String name) {
        for (BodyDeclaration<?> member : declaration.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested && nested.getNameAsString().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
