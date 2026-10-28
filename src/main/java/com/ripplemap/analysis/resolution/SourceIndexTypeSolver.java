package com.ripplemap.analysis.resolution;

import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.ripplemap.analysis.extraction.DeclarationIndex;
import com.ripplemap.analysis.extraction.TypeInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A JavaParser {@link TypeSolver} backed by the already-parsed project sources.
 *
 * <p>Unlike JavaParser's directory-based solver it does not re-read files, it works for files whose
 * directory does not match their package, and it deliberately refuses to resolve canonical names that
 * are declared by more than one file (the correct target would depend on build configuration).
 */
public final class SourceIndexTypeSolver implements TypeSolver {

    private final Map<String, TypeDeclaration<?>> uniqueTypes = new HashMap<>();
    private final Map<String, SymbolReference<ResolvedReferenceTypeDeclaration>> cache = new HashMap<>();
    private TypeSolver parent;

    public void register(DeclarationIndex index) {
        uniqueTypes.clear();
        cache.clear();
        for (Map.Entry<String, List<TypeInfo>> entry : index.canonicalNameIndex().entrySet()) {
            if (entry.getValue().size() == 1) {
                uniqueTypes.put(entry.getKey(), entry.getValue().get(0).node());
            }
        }
    }

    @Override
    public TypeSolver getParent() {
        return parent;
    }

    @Override
    public void setParent(TypeSolver parent) {
        if (parent == this) {
            throw new IllegalStateException("A TypeSolver cannot be its own parent");
        }
        if (this.parent != null) {
            throw new IllegalStateException("This TypeSolver already has a parent");
        }
        this.parent = parent;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        SymbolReference<ResolvedReferenceTypeDeclaration> cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        TypeDeclaration<?> declaration = uniqueTypes.get(name);
        SymbolReference<ResolvedReferenceTypeDeclaration> result = declaration == null
                ? SymbolReference.unsolved()
                : SymbolReference.solved(JavaParserFacade.get(this).getTypeDeclaration(declaration));
        cache.put(name, result);
        return result;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(String qualifiedModuleName,
                                                                                   String simpleTypeName) {
        return SymbolReference.unsolved();
    }

    @Override
    public String toString() {
        return "SourceIndexTypeSolver(" + uniqueTypes.size() + " types)";
    }
}
