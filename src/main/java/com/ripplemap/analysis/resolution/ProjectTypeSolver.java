package com.ripplemap.analysis.resolution;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.util.HashMap;
import java.util.Map;

/**
 * Root type solver of one analysis: project sources first, then JDK types (never application classpath classes).
 * Every lookup passes through {@link ResolutionBudget#check()}, which gives the otherwise uninterruptible symbol
 * solver a cooperative cancellation point. JDK lookups (including misses, which cost a ClassNotFoundException each)
 * are cached.
 */
public final class ProjectTypeSolver implements TypeSolver {

    private final SourceIndexTypeSolver sources;
    private final ReflectionTypeSolver jdk;
    private final Map<String, SymbolReference<ResolvedReferenceTypeDeclaration>> jdkCache = new HashMap<>();
    private ResolutionBudget budget;

    public ProjectTypeSolver(SourceIndexTypeSolver sources) {
        this.sources = sources;
        this.jdk = new ReflectionTypeSolver(true);
        sources.setParent(this);
        jdk.setParent(this);
    }

    public void useBudget(ResolutionBudget budget) {
        this.budget = budget;
    }

    @Override
    public TypeSolver getParent() {
        return null;
    }

    @Override
    public void setParent(TypeSolver parent) {
        throw new UnsupportedOperationException("ProjectTypeSolver is always the root solver");
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        if (budget != null) {
            budget.check();
        }
        SymbolReference<ResolvedReferenceTypeDeclaration> source = sources.tryToSolveType(name);
        if (source.isSolved()) {
            return source;
        }
        return jdkType(name);
    }

    /** Whether the JDK (java.* / javax.*) provides the type; usable without an active resolution. */
    public boolean isJdkType(String name) {
        try {
            return jdkType(name).isSolved();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private SymbolReference<ResolvedReferenceTypeDeclaration> jdkType(String name) {
        SymbolReference<ResolvedReferenceTypeDeclaration> cached = jdkCache.get(name);
        if (cached == null) {
            cached = jdk.tryToSolveType(name);
            jdkCache.put(name, cached);
        }
        return cached;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(String qualifiedModuleName,
                                                                                   String simpleTypeName) {
        return SymbolReference.unsolved();
    }
}
