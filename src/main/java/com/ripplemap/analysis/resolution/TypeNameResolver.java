package com.ripplemap.analysis.resolution;

import com.github.javaparser.ast.Node;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import com.ripplemap.analysis.extraction.DeclarationIndex;
import com.ripplemap.analysis.extraction.ParsedUnit;
import com.ripplemap.analysis.extraction.TypeInfo;
import com.ripplemap.analysis.AnalysisInterruptedException;
import com.ripplemap.analysis.model.Resolution;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a type name used at a given AST position to a project type.
 * The symbol solver is tried first (RESOLVED); when it fails, {@link NameLookup} may infer a target (INFERRED).
 * Names whose last segment is not the simple name of any project type are EXTERNAL without consulting the
 * solver: they cannot denote a project type.
 */
public final class TypeNameResolver {

    public enum Outcome {
        /** A project type. */
        SOURCE,
        /** A JDK or library type; not a project dependency. */
        EXTERNAL,
        /** A type variable or a local/anonymous type; not a dependency between graph nodes. */
        NOT_A_DEPENDENCY,
        /** Unresolved, but most likely an external library type. */
        LIKELY_EXTERNAL,
        /** Matches several project types. */
        AMBIGUOUS,
        /** Could not be resolved; may be a missing project dependency. */
        UNRESOLVED
    }

    public record Target(Outcome outcome, TypeInfo type, Resolution resolution, String rule) {
        static final Target EXTERNAL = new Target(Outcome.EXTERNAL, null, null, null);
        static final Target NOT_A_DEPENDENCY = new Target(Outcome.NOT_A_DEPENDENCY, null, null, null);
        static final Target LIKELY_EXTERNAL = new Target(Outcome.LIKELY_EXTERNAL, null, null, null);
        static final Target AMBIGUOUS = new Target(Outcome.AMBIGUOUS, null, null, null);
        static final Target UNRESOLVED = new Target(Outcome.UNRESOLVED, null, null, null);

        static Target resolved(TypeInfo type) {
            return new Target(Outcome.SOURCE, type, Resolution.RESOLVED, null);
        }

        static Target inferred(TypeInfo type, String rule) {
            return new Target(Outcome.SOURCE, type, Resolution.INFERRED, rule);
        }

        public boolean isSource() {
            return outcome == Outcome.SOURCE;
        }
    }

    private final DeclarationIndex index;
    private final TypeSolver typeSolver;
    private final NameLookup lookup;
    private final ResolutionBudget budget;
    private final Map<Node, Map<String, Target>> cache = new IdentityHashMap<>();
    private int solverFailures;

    public TypeNameResolver(DeclarationIndex index, TypeSolver typeSolver, NameLookup lookup, ResolutionBudget budget) {
        this.index = index;
        this.typeSolver = typeSolver;
        this.lookup = lookup;
        this.budget = budget;
    }

    public Target resolve(Node context, String nameWithScope, ParsedUnit unit) {
        String simple = lastSegment(nameWithScope);
        if (!index.isTypeSimpleName(simple)) {
            return index.isUnparsedTypeName(simple) ? Target.UNRESOLVED : Target.EXTERNAL;
        }
        Map<String, Target> byName = cache.computeIfAbsent(context, k -> new HashMap<>(2));
        Target cached = byName.get(nameWithScope);
        if (cached != null) {
            return cached;
        }
        Target target = resolveUncached(context, nameWithScope, unit);
        byName.put(nameWithScope, target);
        return target;
    }

    private Target resolveUncached(Node context, String nameWithScope, ParsedUnit unit) {
        budget.begin();
        try {
            SymbolReference<ResolvedTypeDeclaration> ref =
                    JavaParserFactory.getContext(context, typeSolver).solveType(nameWithScope, null);
            if (ref.isSolved()) {
                Target target = fromDeclaration(ref.getCorrespondingDeclaration());
                if (target != null) {
                    return target;
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            if (e instanceof AnalysisInterruptedException interrupted) {
                throw interrupted;
            }
            solverFailures++;
        } finally {
            budget.end();
        }
        NameLookup.Result result = lookup.lookup(nameWithScope, context, unit);
        return switch (result.status()) {
            case FOUND -> Target.inferred(result.type(), result.rule());
            case TYPE_PARAMETER, LOCAL_TYPE -> Target.NOT_A_DEPENDENCY;
            case EXTERNAL -> Target.EXTERNAL;
            case LIKELY_EXTERNAL -> Target.LIKELY_EXTERNAL;
            case AMBIGUOUS -> Target.AMBIGUOUS;
            case NOT_FOUND -> Target.UNRESOLVED;
        };
    }

    /** Maps a solver result to a target; returns null when the result is not trustworthy (duplicates). */
    public Target fromDeclaration(ResolvedTypeDeclaration declaration) {
        if (declaration.isTypeParameter()) {
            return Target.NOT_A_DEPENDENCY;
        }
        Node ast = astOf(declaration);
        if (ast != null) {
            TypeInfo info = index.typeForNode(ast);
            if (info != null) {
                return Target.resolved(info);
            }
            if (ast.findCompilationUnit().isPresent()) {
                return Target.NOT_A_DEPENDENCY; // local or anonymous class inside project code
            }
        }
        String qualifiedName = qualifiedName(declaration);
        if (qualifiedName != null) {
            List<TypeInfo> candidates = index.typesByCanonicalName(qualifiedName);
            if (candidates.size() == 1) {
                return Target.resolved(candidates.get(0));
            }
            if (candidates.size() > 1) {
                return null;
            }
        }
        return Target.EXTERNAL;
    }

    /** Maps a resolved declaration to a project type (by AST identity, then unique canonical name). */
    public TypeInfo toProjectType(ResolvedTypeDeclaration declaration) {
        if (declaration == null || declaration.isTypeParameter()) {
            return null;
        }
        Node ast = astOf(declaration);
        if (ast != null) {
            TypeInfo info = index.typeForNode(ast);
            if (info != null) {
                return info;
            }
        }
        String qualifiedName = qualifiedName(declaration);
        return qualifiedName == null ? null : index.uniqueType(qualifiedName);
    }

    public int solverFailures() {
        return solverFailures;
    }

    public NameLookup lookup() {
        return lookup;
    }

    static Node astOf(ResolvedTypeDeclaration declaration) {
        try {
            Optional<Node> ast = declaration.toAst();
            return ast.orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String qualifiedName(ResolvedTypeDeclaration declaration) {
        try {
            return declaration.getQualifiedName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
