package com.ripplemap.analysis.extraction;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.ripplemap.analysis.resolution.TypeNameResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the declared supertypes of every project type. Enums, records and annotation types have an
 * implicit library supertype ({@code java.lang.Enum}, {@code java.lang.Record},
 * {@code java.lang.annotation.Annotation}), so they are marked as having a non-project supertype.
 */
public final class HierarchyResolver {

    private HierarchyResolver() {
    }

    public static void resolve(DeclarationIndex index, TypeNameResolver resolver, Runnable checkpoint) {
        for (TypeInfo type : index.types()) {
            checkpoint.run();
            TypeDeclaration<?> node = type.node();
            List<ClassOrInterfaceType> declared = new ArrayList<>();
            if (node instanceof ClassOrInterfaceDeclaration declaration) {
                declared.addAll(declaration.getExtendedTypes());
                declared.addAll(declaration.getImplementedTypes());
            } else if (node instanceof EnumDeclaration declaration) {
                declared.addAll(declaration.getImplementedTypes());
                type.markNonSourceSupertype();
            } else if (node instanceof RecordDeclaration declaration) {
                declared.addAll(declaration.getImplementedTypes());
                type.markNonSourceSupertype();
            } else if (node instanceof AnnotationDeclaration) {
                type.markNonSourceSupertype();
            }
            for (ClassOrInterfaceType supertype : declared) {
                TypeNameResolver.Target target = resolver.resolve(supertype, supertype.getNameWithScope(), type.unit());
                if (target.isSource()) {
                    type.addSourceSupertype(target.type());
                } else {
                    type.markNonSourceSupertype();
                }
            }
        }
    }
}
