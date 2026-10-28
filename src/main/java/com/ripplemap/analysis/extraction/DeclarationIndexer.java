package com.ripplemap.analysis.extraction;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.ripplemap.analysis.diagnostics.DiagnosticCategory;
import com.ripplemap.analysis.diagnostics.DiagnosticCollector;
import com.ripplemap.analysis.model.EntityKind;
import com.ripplemap.analysis.model.ExtractedEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Builds the {@link DeclarationIndex}: all top-level and member types, their methods,
 * constructors and fields. Local and anonymous classes are not indexed; code inside them is
 * attributed to the enclosing indexed declaration by the relationship extractor.
 */
public final class DeclarationIndexer {

    private record Candidate(TypeDeclaration<?> node, ParsedUnit unit, Candidate enclosing,
                             String canonicalName, String binaryName, String displayName) {
    }

    private DeclarationIndexer() {
    }

    public static DeclarationIndex build(List<ParsedUnit> units, DiagnosticCollector diagnostics) {
        return build(units, Set.of(), diagnostics);
    }

    /**
     * @param unparsedTypeNames base names of in-scope files that could not be parsed
     */
    public static DeclarationIndex build(List<ParsedUnit> units, Set<String> unparsedTypeNames,
                                         DiagnosticCollector diagnostics) {
        DeclarationIndex index = new DeclarationIndex(units, unparsedTypeNames);
        List<Candidate> candidates = new ArrayList<>();
        for (ParsedUnit unit : units) {
            for (TypeDeclaration<?> type : unit.compilationUnit().getTypes()) {
                collect(type, unit, null, candidates);
            }
        }
        Map<String, Integer> canonicalCounts = new HashMap<>();
        for (Candidate c : candidates) {
            canonicalCounts.merge(c.canonicalName(), 1, Integer::sum);
        }
        Map<Candidate, TypeInfo> created = new IdentityHashMap<>();
        for (Candidate c : candidates) {
            boolean duplicate = canonicalCounts.get(c.canonicalName()) > 1;
            String id = duplicate ? c.binaryName() + "[" + c.unit().relativePath() + "]" : c.binaryName();
            TypeInfo enclosing = c.enclosing() == null ? null : created.get(c.enclosing());
            TypeInfo info = new TypeInfo(id, c.canonicalName(), c.node().getNameAsString(), c.displayName(),
                    c.unit().packageName(), kindOf(c.node()), c.node(), c.unit(), enclosing);
            created.put(c, info);
            if (enclosing != null) {
                enclosing.addNested(info);
            }
            index.addType(info);
            if (duplicate) {
                diagnostics.add(DiagnosticCategory.DUPLICATE_TYPE, c.unit().relativePath(), line(c.node()), id,
                        c.canonicalName(), "Type " + c.canonicalName() + " is declared in "
                                + canonicalCounts.get(c.canonicalName()) + " files");
            }
            index.addEntity(new ExtractedEntity(id, info.kind(), c.displayName(), c.canonicalName(),
                    c.unit().packageName(), enclosing == null ? null : enclosing.id(), null,
                    c.unit().relativePath(), line(c.node()), endLine(c.node()),
                    visibility(c.node(), enclosing != null && isInterfaceLike(enclosing.node())),
                    modifiers(c.node()), c.unit().testSource()));
        }
        for (TypeInfo type : index.types()) {
            indexMembers(type, index, diagnostics);
        }
        return index;
    }

    private static void collect(TypeDeclaration<?> node, ParsedUnit unit, Candidate enclosing, List<Candidate> out) {
        String simple = node.getNameAsString();
        Candidate candidate = enclosing == null
                ? new Candidate(node, unit, null, StableIds.qualify(unit.packageName(), simple),
                StableIds.qualify(unit.packageName(), simple), simple)
                : new Candidate(node, unit, enclosing, enclosing.canonicalName() + "." + simple,
                enclosing.binaryName() + "$" + simple, enclosing.displayName() + "." + simple);
        out.add(candidate);
        for (BodyDeclaration<?> member : node.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collect(nested, unit, candidate, out);
            }
        }
    }

    private static void indexMembers(TypeInfo type, DeclarationIndex index, DiagnosticCollector diagnostics) {
        TypeDeclaration<?> node = type.node();
        boolean interfaceLike = isInterfaceLike(node);
        if (node instanceof RecordDeclaration record) {
            for (Parameter component : record.getParameters()) {
                String name = component.getNameAsString();
                addField(type, index, diagnostics, name, component.getType().asString(), component,
                        "private", "final record-component");
                index.addMethodName(name);
                if (record.getMethodsBySignature(name).isEmpty()) {
                    type.addImplicitMethodName(name);
                }
            }
        }
        if (node instanceof EnumDeclaration enumDeclaration) {
            for (EnumConstantDeclaration constant : enumDeclaration.getEntries()) {
                addField(type, index, diagnostics, constant.getNameAsString(), type.displayName(), constant,
                        "public", "static final enum-constant");
            }
            for (String implicit : List.of("values", "valueOf")) {
                index.addMethodName(implicit);
                type.addImplicitMethodName(implicit);
            }
        }
        for (BodyDeclaration<?> member : node.getMembers()) {
            if (member instanceof MethodDeclaration method) {
                addCallable(type, index, diagnostics, EntityKind.METHOD, method.getNameAsString(),
                        method.getParameters(), method, method.isStatic(), interfaceLike,
                        method.getType().asString());
            } else if (member instanceof ConstructorDeclaration constructor) {
                addCallable(type, index, diagnostics, EntityKind.CONSTRUCTOR, StableIds.CONSTRUCTOR_NAME,
                        constructor.getParameters(), constructor, false, interfaceLike, null);
            } else if (member instanceof CompactConstructorDeclaration compact
                    && node instanceof RecordDeclaration record) {
                addCallable(type, index, diagnostics, EntityKind.CONSTRUCTOR, StableIds.CONSTRUCTOR_NAME,
                        record.getParameters(), compact, false, false, null);
            } else if (member instanceof FieldDeclaration field) {
                for (VariableDeclarator variable : field.getVariables()) {
                    addField(type, index, diagnostics, variable.getNameAsString(), variable.getType().asString(),
                            variable, visibility(field, interfaceLike), modifiers(field));
                }
            }
        }
    }

    private static void addCallable(TypeInfo type, DeclarationIndex index, DiagnosticCollector diagnostics,
                                    EntityKind kind, String name, NodeList<Parameter> parameters, Node node,
                                    boolean isStatic, boolean interfaceMember, String returnType) {
        List<String> idTypes = new ArrayList<>(parameters.size());
        StringJoiner display = new StringJoiner(", ", "(", ")");
        boolean varargs = false;
        for (Parameter p : parameters) {
            idTypes.add(StableIds.parameterType(p));
            display.add(StableIds.displayParameterType(p));
            varargs |= p.isVarArgs();
        }
        String id = StableIds.callableId(type.id(), name, idTypes);
        int line = line(node);
        if (index.hasMemberId(id)) {
            String original = id;
            id = id + "@L" + line;
            int ordinal = 2;
            while (index.hasMemberId(id)) {
                id = original + "@L" + line + "~" + ordinal++;
            }
            diagnostics.add(DiagnosticCategory.DUPLICATE_MEMBER, type.filePath(), line, id, original,
                    "Duplicate member signature " + original);
        }
        String displayName = kind == EntityKind.CONSTRUCTOR ? type.simpleName() : name;
        String signature = displayName + display;
        MemberInfo member = new MemberInfo(id, kind, displayName, signature, type, node, parameters.size(),
                varargs, isStatic);
        index.addMember(member);
        if (kind == EntityKind.CONSTRUCTOR) {
            type.addConstructor(member);
        } else {
            type.addMethod(member);
            index.addMethodName(name);
        }
        String vis = node instanceof NodeWithModifiers<?> withModifiers
                ? visibility(withModifiers, interfaceMember) : "public";
        String mods = node instanceof NodeWithModifiers<?> withModifiers ? modifiers(withModifiers) : "";
        if (node instanceof CompactConstructorDeclaration) {
            mods = (mods.isEmpty() ? "" : mods + " ") + "compact";
        }
        index.addEntity(new ExtractedEntity(id, kind, displayName, type.canonicalName() + "." + displayName,
                type.packageName(), type.id(), returnType == null ? signature : signature + " : " + returnType,
                type.filePath(), line, endLine(node), vis, mods, type.unit().testSource()));
    }

    private static void addField(TypeInfo type, DeclarationIndex index, DiagnosticCollector diagnostics,
                                 String name, String declaredType, Node node, String visibility, String modifiers) {
        String id = StableIds.fieldId(type.id(), name);
        if (type.fieldNames().contains(name)) {
            id = id + "@L" + line(node);
            diagnostics.add(DiagnosticCategory.DUPLICATE_MEMBER, type.filePath(), line(node), id, name,
                    "Duplicate field name " + name);
        }
        type.addFieldName(name);
        index.addFieldName(name);
        index.incrementFieldCount();
        index.addEntity(new ExtractedEntity(id, EntityKind.FIELD, name, type.canonicalName() + "." + name,
                type.packageName(), type.id(), declaredType, type.filePath(), line(node), endLine(node),
                visibility, modifiers, type.unit().testSource()));
    }

    static EntityKind kindOf(TypeDeclaration<?> node) {
        if (node instanceof ClassOrInterfaceDeclaration c) {
            return c.isInterface() ? EntityKind.INTERFACE : EntityKind.CLASS;
        }
        if (node instanceof EnumDeclaration) {
            return EntityKind.ENUM;
        }
        if (node instanceof RecordDeclaration) {
            return EntityKind.RECORD;
        }
        if (node instanceof AnnotationDeclaration) {
            return EntityKind.ANNOTATION;
        }
        return EntityKind.CLASS;
    }

    private static boolean isInterfaceLike(TypeDeclaration<?> node) {
        return (node instanceof ClassOrInterfaceDeclaration c && c.isInterface()) || node instanceof AnnotationDeclaration;
    }

    static int line(Node node) {
        Node anchor = node;
        if (node instanceof TypeDeclaration<?> t) {
            anchor = t.getName();
        } else if (node instanceof MethodDeclaration m) {
            anchor = m.getName();
        } else if (node instanceof ConstructorDeclaration c) {
            anchor = c.getName();
        } else if (node instanceof CompactConstructorDeclaration c) {
            anchor = c.getName();
        } else if (node instanceof VariableDeclarator v) {
            anchor = v.getName();
        } else if (node instanceof EnumConstantDeclaration e) {
            anchor = e.getName();
        } else if (node instanceof Parameter p) {
            anchor = p.getName();
        }
        return anchor.getBegin().map(p -> p.line).orElse(0);
    }

    static int endLine(Node node) {
        return node.getEnd().map(p -> p.line).orElse(line(node));
    }

    private static String visibility(NodeWithModifiers<?> node, boolean interfaceMember) {
        return switch (node.getAccessSpecifier()) {
            case PUBLIC -> "public";
            case PROTECTED -> "protected";
            case PRIVATE -> "private";
            default -> interfaceMember ? "public" : "package";
        };
    }

    private static String modifiers(NodeWithModifiers<?> node) {
        StringJoiner joiner = new StringJoiner(" ");
        for (Modifier modifier : node.getModifiers()) {
            Modifier.Keyword keyword = modifier.getKeyword();
            if (keyword != Modifier.Keyword.PUBLIC && keyword != Modifier.Keyword.PROTECTED
                    && keyword != Modifier.Keyword.PRIVATE) {
                joiner.add(keyword.asString());
            }
        }
        return joiner.toString();
    }
}
