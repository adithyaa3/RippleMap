package com.ripplemap.analysis.extraction;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.RecordPatternExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.UnionType;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.ast.type.VarType;
import com.github.javaparser.ast.type.WildcardType;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.ripplemap.analysis.AnalysisInterruptedException;
import com.ripplemap.analysis.diagnostics.DiagnosticCategory;
import com.ripplemap.analysis.diagnostics.DiagnosticCollector;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.ReferenceOccurrence;
import com.ripplemap.analysis.model.ReferenceStatistics;
import com.ripplemap.analysis.model.RelationshipKind;
import com.ripplemap.analysis.model.Resolution;
import com.ripplemap.analysis.resolution.NameLookup;
import com.ripplemap.analysis.resolution.ResolutionBudget;
import com.ripplemap.analysis.resolution.TypeNameResolver;
import com.ripplemap.analysis.resolution.VariableTypes;
import com.ripplemap.util.TextSanitizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Extracts dependency relationships from parsed compilation units.
 *
 * <p>Every reference is attributed to the nearest enclosing indexed declaration: the type-level source is the
 * nearest top-level or member type, the method-level source is the nearest method or constructor of such a type.
 * Code in lambdas, anonymous classes and local classes is attributed to the enclosing declaration; code in field
 * initializers and initializer blocks contributes to the type level only.
 *
 * <p>Resolution: the JavaParser symbol solver is tried first (RESOLVED). When it fails, conservative rules may
 * infer a unique target (INFERRED, the rule id is recorded). Anything else becomes a diagnostic, never an edge.
 * Names that cannot denote any project declaration (no project type/method/field has that name) are classified
 * as external without consulting the solver.
 */
public final class RelationshipExtractor {

    private static final int MAX_DETAIL = 160;
    private static final int MAX_RECEIVER_DEPTH = 4;
    private static final Set<String> OBJECT_METHODS = Set.of(
            "equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait", "clone", "finalize");

    private record Ctx(TypeInfo type, MemberInfo member) {
    }

    private record Usage(RelationshipKind kind, String detail) {
    }

    private enum TargetKind { MEMBER, TYPE_ONLY, EXTERNAL, INTERNAL, LIKELY_EXTERNAL, PROBLEM }

    private record CallTarget(TargetKind kind, MemberInfo member, TypeInfo type, Resolution resolution, String rule,
                              DiagnosticCategory problem, String reason) {
        static CallTarget member(MemberInfo member, Resolution resolution, String rule) {
            return new CallTarget(TargetKind.MEMBER, member, member.declaringType(), resolution, rule, null, null);
        }

        static CallTarget typeOnly(TypeInfo type, Resolution resolution, String rule, DiagnosticCategory problem,
                                   String reason) {
            return new CallTarget(TargetKind.TYPE_ONLY, null, type, resolution, rule, problem, reason);
        }

        static CallTarget of(TargetKind kind) {
            return new CallTarget(kind, null, null, null, null, null, null);
        }

        static CallTarget problem(DiagnosticCategory category, String reason) {
            return new CallTarget(TargetKind.PROBLEM, null, null, null, null, category, reason);
        }

        static CallTarget likelyExternal(String reason) {
            return new CallTarget(TargetKind.LIKELY_EXTERNAL, null, null, null, null, DiagnosticCategory.LIKELY_EXTERNAL, reason);
        }
    }

    private enum ReceiverKind { SOURCE, EXTERNAL, LIKELY_EXTERNAL, UNKNOWN }

    private record Receiver(ReceiverKind kind, TypeInfo type, Resolution resolution, String rule) {
        static final Receiver EXTERNAL = new Receiver(ReceiverKind.EXTERNAL, null, null, null);
        static final Receiver LIKELY_EXTERNAL = new Receiver(ReceiverKind.LIKELY_EXTERNAL, null, null, null);
        static final Receiver UNKNOWN = new Receiver(ReceiverKind.UNKNOWN, null, null, null);

        static Receiver source(TypeInfo type, Resolution resolution, String rule) {
            return new Receiver(ReceiverKind.SOURCE, type, resolution, rule);
        }
    }

    private final DeclarationIndex index;
    private final TypeNameResolver typeResolver;
    private final JavaParserFacade facade;
    private final RelationshipAggregator sink;
    private final DiagnosticCollector diagnostics;
    private final ReferenceStatistics stats;
    private final Runnable checkpoint;
    private final ResolutionBudget budget;
    private final Consumer<String> progress;
    private ParsedUnit unit;

    public RelationshipExtractor(DeclarationIndex index, TypeNameResolver typeResolver, JavaParserFacade facade,
                                 RelationshipAggregator sink, DiagnosticCollector diagnostics,
                                 ReferenceStatistics stats, Runnable checkpoint, ResolutionBudget budget,
                                 Consumer<String> progress) {
        this.index = index;
        this.typeResolver = typeResolver;
        this.facade = facade;
        this.sink = sink;
        this.diagnostics = diagnostics;
        this.stats = stats;
        this.checkpoint = checkpoint;
        this.budget = budget;
        this.progress = progress;
    }

    /**
     * Runs one symbol-solver invocation under the per-reference budget. Returns null when the solver failed or
     * ran out of budget; the caller then applies the documented fallback rules.
     */
    private <T> T solve(Supplier<T> invocation, Node at) {
        budget.begin();
        try {
            return invocation.get();
        } catch (ResolutionBudget.BudgetExceededException e) {
            stats.solverFailures++;
            stats.budgetExceeded++;
            Ctx ctx = safeContext(at);
            diagnostics.add(DiagnosticCategory.RESOLUTION_TIMEOUT, unit.relativePath(), line(at),
                    ctx == null ? null : idOf(ctx), shortText(at), null);
            return null;
        } catch (RuntimeException | StackOverflowError e) {
            if (e instanceof AnalysisInterruptedException interrupted) {
                throw interrupted;
            }
            stats.solverFailures++;
            return null;
        } finally {
            budget.end();
        }
    }

    public void extract() {
        int done = 0;
        int total = index.units().size();
        long lastReport = 0;
        for (ParsedUnit parsed : index.units()) {
            checkpoint.run();
            long now = System.nanoTime();
            if (now - lastReport > 1_000_000_000L) {
                progress.accept("Resolving symbols: file " + (done + 1) + " of " + total);
                lastReport = now;
            }
            done++;
            this.unit = parsed;
            int[] counter = {0};
            parsed.compilationUnit().walk(Node.TreeTraversal.PREORDER, node -> {
                if ((++counter[0] & 0xFF) == 0) {
                    checkpoint.run();
                }
                visit(node);
            });
        }
        this.unit = null;
    }

    private void visit(Node node) {
        try {
            if (node instanceof ClassOrInterfaceType type) {
                handleTypeReference(type);
            } else if (node instanceof AnnotationExpr annotation) {
                handleAnnotation(annotation);
            } else if (node instanceof MethodCallExpr call) {
                handleMethodCall(call);
            } else if (node instanceof ObjectCreationExpr creation) {
                handleConstructorTarget(creation);
            } else if (node instanceof ExplicitConstructorInvocationStmt invocation) {
                handleExplicitConstructorCall(invocation);
            } else if (node instanceof MethodReferenceExpr reference) {
                handleMethodReference(reference);
            } else if (node instanceof FieldAccessExpr access) {
                handleFieldAccess(access);
            } else if (node instanceof NameExpr name && unit.hasStaticImports()) {
                handleStaticImportedName(name);
            }
        } catch (StackOverflowError | RuntimeException e) {
            if (e instanceof AnalysisInterruptedException interrupted) {
                throw interrupted;
            }
            stats.unresolved++;
            Ctx ctx = safeContext(node);
            diagnostics.add(DiagnosticCategory.RESOLUTION_ERROR, unit.relativePath(), line(node),
                    ctx == null ? null : idOf(ctx), shortText(node),
                    "Resolution failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ---------------------------------------------------------------- type references

    private void handleTypeReference(ClassOrInterfaceType type) {
        if (type.getParentNode().orElse(null) instanceof ClassOrInterfaceType parent
                && parent.getScope().map(scope -> scope == type).orElse(false)) {
            return; // qualifier of another type name, e.g. "Map" in Map.Entry
        }
        if (!index.isTypeSimpleName(type.getNameAsString()) && !index.isUnparsedTypeName(type.getNameAsString())) {
            stats.external++;
            return;
        }
        Ctx ctx = contextOf(type);
        if (ctx == null) {
            return;
        }
        Usage usage = classifyTypeUsage(type, ctx);
        TypeNameResolver.Target target = typeResolver.resolve(type, type.getNameWithScope(), unit);
        applyTypeTarget(ctx, target, usage.kind(), usage.detail(), type, type.getNameWithScope());
    }

    private void handleAnnotation(AnnotationExpr annotation) {
        String name = annotation.getNameAsString();
        if (!index.isTypeSimpleName(lastSegment(name)) && !index.isUnparsedTypeName(lastSegment(name))) {
            stats.external++;
            return;
        }
        Ctx ctx = contextOf(annotation);
        if (ctx == null) {
            return;
        }
        TypeNameResolver.Target target = typeResolver.resolve(annotation, name, unit);
        applyTypeTarget(ctx, target, RelationshipKind.ANNOTATION_USAGE, "@" + name, annotation, name);
    }

    private void applyTypeTarget(Ctx ctx, TypeNameResolver.Target target, RelationshipKind kind, String detail,
                                 Node at, String symbol) {
        switch (target.outcome()) {
            case SOURCE -> {
                if (typeEdge(ctx, target.type(), kind, target.resolution(), target.rule(), at, detail)) {
                    count(target.resolution());
                } else {
                    stats.internal++;
                }
            }
            case EXTERNAL -> stats.external++;
            case NOT_A_DEPENDENCY -> stats.internal++;
            case LIKELY_EXTERNAL -> {
                stats.likelyExternal++;
                diagnostics.add(DiagnosticCategory.LIKELY_EXTERNAL, unit.relativePath(), line(at), idOf(ctx), symbol,
                        "Type " + symbol + " was not resolved; it is probably provided by an on-demand imported library");
            }
            case AMBIGUOUS -> {
                stats.unresolved++;
                diagnostics.add(DiagnosticCategory.AMBIGUOUS_TYPE, unit.relativePath(), line(at), idOf(ctx), symbol,
                        "Type " + symbol + " matches several project types");
            }
            case UNRESOLVED -> {
                stats.unresolved++;
                diagnostics.add(DiagnosticCategory.UNRESOLVED_TYPE, unit.relativePath(), line(at), idOf(ctx), symbol,
                        "Type " + symbol + " could not be resolved (" + detail + ")");
            }
        }
    }

    private Usage classifyTypeUsage(ClassOrInterfaceType type, Ctx ctx) {
        Node child = type;
        Node parent = type.getParentNode().orElse(null);
        boolean typeArgument = false;
        while (parent instanceof ClassOrInterfaceType || parent instanceof ArrayType || parent instanceof WildcardType
                || parent instanceof UnionType || parent instanceof IntersectionType) {
            if (parent instanceof WildcardType) {
                typeArgument = true;
            }
            if (parent instanceof ClassOrInterfaceType owner && owner.getTypeArguments().isPresent()
                    && containsIdentity(owner.getTypeArguments().get(), child)) {
                typeArgument = true;
            }
            child = parent;
            parent = parent.getParentNode().orElse(null);
        }
        String name = type.getNameWithScope();
        RelationshipKind other = RelationshipKind.TYPE_REFERENCE;
        if (parent instanceof TypeParameter) {
            return new Usage(other, "type bound " + name);
        }
        if (parent instanceof ClassOrInterfaceDeclaration declaration) {
            boolean indexed = index.typeForNode(declaration) != null;
            if (containsIdentity(declaration.getExtendedTypes(), child)) {
                return supertypeUsage(RelationshipKind.EXTENDS, "extends ", name, indexed, typeArgument);
            }
            if (containsIdentity(declaration.getImplementedTypes(), child)) {
                return supertypeUsage(RelationshipKind.IMPLEMENTS, "implements ", name, indexed, typeArgument);
            }
            if (containsIdentity(declaration.getPermittedTypes(), child)) {
                return new Usage(other, "permits " + name);
            }
        }
        if (parent instanceof EnumDeclaration declaration && containsIdentity(declaration.getImplementedTypes(), child)) {
            return supertypeUsage(RelationshipKind.IMPLEMENTS, "implements ", name,
                    index.typeForNode(declaration) != null, typeArgument);
        }
        if (parent instanceof RecordDeclaration declaration && containsIdentity(declaration.getImplementedTypes(), child)) {
            return supertypeUsage(RelationshipKind.IMPLEMENTS, "implements ", name,
                    index.typeForNode(declaration) != null, typeArgument);
        }
        if (parent instanceof VariableDeclarator variable) {
            if (variable.getParentNode().orElse(null) instanceof FieldDeclaration field) {
                boolean own = field.getParentNode().map(p -> p == ctx.type().node()).orElse(false);
                return own ? new Usage(RelationshipKind.FIELD_TYPE, "field " + variable.getNameAsString())
                        : new Usage(other, "field " + variable.getNameAsString() + " of a local or anonymous class");
            }
            return new Usage(other, "local variable " + variable.getNameAsString());
        }
        if (parent instanceof Parameter parameter) {
            Node owner = parameter.getParentNode().orElse(null);
            String pname = parameter.getNameAsString();
            if (owner instanceof MethodDeclaration method) {
                return index.memberForNode(method) != null
                        ? new Usage(RelationshipKind.METHOD_PARAMETER_TYPE,
                        "parameter " + pname + " of " + method.getNameAsString() + "()")
                        : new Usage(other, "parameter " + pname + " of local or anonymous class method");
            }
            if (owner instanceof ConstructorDeclaration constructor) {
                return index.memberForNode(constructor) != null
                        ? new Usage(RelationshipKind.CONSTRUCTOR_PARAMETER_TYPE, "constructor parameter " + pname)
                        : new Usage(other, "parameter " + pname + " of local class constructor");
            }
            if (owner instanceof RecordDeclaration record) {
                return index.typeForNode(record) != null
                        ? new Usage(RelationshipKind.FIELD_TYPE, "record component " + pname)
                        : new Usage(other, "component " + pname + " of a local record");
            }
            if (owner instanceof CatchClause) {
                return new Usage(other, "catch " + name);
            }
            if (owner instanceof LambdaExpr) {
                return new Usage(other, "lambda parameter " + pname);
            }
            return new Usage(other, "parameter " + pname);
        }
        if (parent instanceof MethodDeclaration method) {
            if (method.getType() == child) {
                return index.memberForNode(method) != null
                        ? new Usage(RelationshipKind.METHOD_RETURN_TYPE, "return type of " + method.getNameAsString() + "()")
                        : new Usage(other, "return type of local or anonymous class method");
            }
            if (containsIdentity(method.getThrownExceptions(), child)) {
                return new Usage(other, "throws " + name);
            }
        }
        if (parent instanceof ConstructorDeclaration constructor && containsIdentity(constructor.getThrownExceptions(), child)) {
            return new Usage(other, "throws " + name);
        }
        if (parent instanceof ObjectCreationExpr creation) {
            if (creation.getType() == child && !typeArgument) {
                return new Usage(RelationshipKind.OBJECT_CREATION, "new " + name + "(…)");
            }
            return new Usage(other, "type argument of object creation");
        }
        if (parent instanceof AnnotationMemberDeclaration) {
            return new Usage(other, "annotation member type " + name);
        }
        if (parent instanceof CastExpr) {
            return new Usage(other, "cast to " + name);
        }
        if (parent instanceof InstanceOfExpr) {
            return new Usage(other, "instanceof " + name);
        }
        if (parent instanceof TypePatternExpr || parent instanceof RecordPatternExpr) {
            return new Usage(other, "pattern " + name);
        }
        if (parent instanceof ClassExpr) {
            return new Usage(other, name + ".class");
        }
        if (parent instanceof ArrayCreationExpr) {
            return new Usage(other, "new " + name + "[]");
        }
        if (parent instanceof TypeExpr) {
            return new Usage(other, "type expression " + name);
        }
        if (parent instanceof MethodCallExpr) {
            return new Usage(other, "explicit type argument " + name);
        }
        return new Usage(other, "type " + name);
    }

    private static Usage supertypeUsage(RelationshipKind kind, String prefix, String name, boolean indexed,
                                        boolean typeArgument) {
        if (typeArgument) {
            return new Usage(RelationshipKind.TYPE_REFERENCE, "type argument in " + prefix.strip() + " clause");
        }
        if (!indexed) {
            return new Usage(RelationshipKind.TYPE_REFERENCE, "local class " + prefix + name);
        }
        return new Usage(kind, prefix + name);
    }

    // ---------------------------------------------------------------- calls

    private void handleMethodCall(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (!index.isMethodName(name)) {
            stats.external++;
            return;
        }
        Ctx ctx = contextOf(call);
        if (ctx == null) {
            return;
        }
        if (call.getScope().isPresent() && declaredOutsideProject(call.getScope().get())) {
            stats.external++; // e.g. list.add(..) on a java.util.List variable: a library method by construction
            return;
        }
        CallTarget target = resolveCall(call, ctx, 0);
        int args = call.getArguments().size();
        applyCallTarget(ctx, target, RelationshipKind.METHOD_CALL, true, call,
                "call " + name + "(" + args + (args == 1 ? " arg)" : " args)"), name);
    }

    private CallTarget resolveCall(MethodCallExpr call, Ctx ctx, int depth) {
        CallTarget target = solve(() -> {
            SymbolReference<ResolvedMethodDeclaration> ref = facade.solve(call);
            return ref.isSolved() ? fromResolvedMethod(ref.getCorrespondingDeclaration()) : null;
        }, call);
        return target != null ? target : inferCall(call, ctx, depth);
    }

    private CallTarget fromResolvedMethod(ResolvedMethodDeclaration declaration) {
        Node ast = astOf(declaration);
        if (ast != null) {
            MemberInfo member = index.memberForNode(ast);
            if (member != null) {
                return CallTarget.member(member, Resolution.RESOLVED, null);
            }
            if (ast.findCompilationUnit().isPresent()) {
                return implicitOrLocal(ast);
            }
        }
        ResolvedReferenceTypeDeclaration declaring;
        try {
            declaring = declaration.declaringType();
        } catch (RuntimeException e) {
            return null;
        }
        TypeInfo type = typeResolver.toProjectType(declaring);
        if (type != null) {
            return CallTarget.typeOnly(type, Resolution.RESOLVED, null, null, null); // implicit member, e.g. values()
        }
        return CallTarget.of(TargetKind.EXTERNAL);
    }

    private CallTarget inferCall(MethodCallExpr call, Ctx ctx, int depth) {
        String name = call.getNameAsString();
        int args = call.getArguments().size();
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return inferUnqualifiedCall(call, ctx, name, args);
        }
        Receiver receiver = receiverType(scope.get(), ctx, depth);
        return switch (receiver.kind()) {
            case EXTERNAL -> CallTarget.of(TargetKind.EXTERNAL);
            case LIKELY_EXTERNAL -> CallTarget.likelyExternal("Receiver of " + name + "() is most likely a library type");
            case UNKNOWN -> CallTarget.problem(DiagnosticCategory.UNRESOLVED_CALL,
                    "The receiver type of " + name + "() could not be determined");
            case SOURCE -> callOnProjectType(receiver.type(), name, args, true, "RECEIVER_TYPE");
        };
    }

    private CallTarget inferUnqualifiedCall(Node call, Ctx ctx, String name, int args) {
        if (localOrAnonymousDeclaresMethod(call, ctx, name)) {
            return CallTarget.of(TargetKind.INTERNAL);
        }
        for (TypeInfo type = ctx.type(); type != null; type = type.enclosing()) {
            HierarchySearch search = searchHierarchy(type, name, args, true);
            if (!search.isEmpty()) {
                return pick(search, name, "ENCLOSING_SCOPE");
            }
            if (search.nonSourceAncestor()) {
                return CallTarget.likelyExternal(name + "() may be inherited from a library supertype of " + type.displayName());
            }
        }
        Set<TypeInfo> imported = new LinkedHashSet<>();
        boolean externalStatic = false;
        for (String typeName : unit.staticSingleImports().getOrDefault(name, List.of())) {
            TypeInfo type = index.uniqueType(typeName);
            if (type != null) {
                imported.add(type);
            } else {
                externalStatic = true;
            }
        }
        for (String typeName : unit.staticOnDemandImports()) {
            TypeInfo type = index.uniqueType(typeName);
            if (type == null) {
                externalStatic = true;
            } else if (type.methodsByName().containsKey(name)) {
                imported.add(type);
            }
        }
        List<MemberInfo> candidates = new ArrayList<>();
        for (TypeInfo type : imported) {
            for (MemberInfo method : type.methodsByName().getOrDefault(name, List.of())) {
                if (method.isStatic() && method.acceptsArity(args)) {
                    candidates.add(method);
                }
            }
        }
        if (candidates.size() == 1) {
            return CallTarget.member(candidates.get(0), Resolution.INFERRED, "STATIC_IMPORT_UNIQUE_ARITY");
        }
        if (candidates.size() > 1) {
            return CallTarget.problem(DiagnosticCategory.AMBIGUOUS_CALL,
                    name + "() matches " + candidates.size() + " statically imported methods");
        }
        if (externalStatic) {
            return CallTarget.likelyExternal(name + "() is probably a statically imported library method");
        }
        if (OBJECT_METHODS.contains(name)) {
            return CallTarget.of(TargetKind.EXTERNAL);
        }
        return CallTarget.problem(DiagnosticCategory.UNRESOLVED_CALL, "No project method " + name + "() is visible here");
    }

    /** Whether a local or anonymous class between the call and its indexed type declares the method. */
    private boolean localOrAnonymousDeclaresMethod(Node node, Ctx ctx, String name) {
        for (Node current = node.getParentNode().orElse(null); current != null && current != ctx.type().node();
             current = current.getParentNode().orElse(null)) {
            if (current instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
                if (declaresMethod(creation.getAnonymousClassBody().get(), name)) {
                    return true;
                }
                // methods inherited by the anonymous class from a project supertype also take precedence;
                // library supertypes cannot be inspected, so the enclosing scopes are searched next
                TypeNameResolver.Target supertype = typeResolver.resolve(creation.getType(),
                        creation.getType().getNameWithScope(), unit);
                if (supertype.isSource() && !searchHierarchy(supertype.type(), name, -1, false).isEmpty()) {
                    return true;
                }
            }
            if (current instanceof TypeDeclaration<?> declaration && index.typeForNode(declaration) == null
                    && declaresMethod(declaration.getMembers(), name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresMethod(NodeList<? extends BodyDeclaration<?>> members, String name) {
        for (BodyDeclaration<?> member : members) {
            if (member instanceof MethodDeclaration method && method.getNameAsString().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Candidates declared by {@code type} and its project supertypes (arity {@code -1} = any arity). */
    private record HierarchySearch(List<MemberInfo> methods, List<TypeInfo> implicitOwners, boolean nonSourceAncestor) {
        boolean isEmpty() {
            return methods.isEmpty() && implicitOwners.isEmpty();
        }
    }

    private HierarchySearch searchHierarchy(TypeInfo type, String name, int args, boolean includeImplicit) {
        List<MemberInfo> methods = new ArrayList<>();
        List<TypeInfo> implicit = new ArrayList<>();
        boolean nonSource = false;
        ArrayDeque<TypeInfo> queue = new ArrayDeque<>();
        queue.add(type);
        Set<TypeInfo> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            TypeInfo current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            for (MemberInfo method : current.methodsByName().getOrDefault(name, List.of())) {
                if (args < 0 || method.acceptsArity(args)) {
                    methods.add(method);
                }
            }
            if (includeImplicit && current.implicitMethodNames().contains(name)) {
                implicit.add(current);
            }
            nonSource |= current.hasNonSourceSupertype();
            queue.addAll(current.sourceSupertypes());
        }
        return new HierarchySearch(methods, implicit, nonSource);
    }

    private CallTarget callOnProjectType(TypeInfo type, String name, int args, boolean includeImplicit, String rule) {
        HierarchySearch search = searchHierarchy(type, name, args, includeImplicit);
        if (!search.isEmpty()) {
            return pick(search, name, rule);
        }
        if (search.nonSourceAncestor() || OBJECT_METHODS.contains(name)) {
            return CallTarget.of(TargetKind.EXTERNAL); // inherited from a library supertype (or java.lang.Object)
        }
        return CallTarget.problem(DiagnosticCategory.UNRESOLVED_CALL,
                "No method " + name + "() with " + args + " argument(s) found in " + type.displayName());
    }

    /**
     * Picks a unique target: exactly one arity-compatible candidate, or several candidates that all share the
     * same written signature (overrides along the hierarchy; the most derived one is chosen). Candidates on one
     * declaring type with different signatures still yield a type-level edge but are ambiguous at method level.
     */
    private CallTarget pick(HierarchySearch search, String name, String rule) {
        List<MemberInfo> methods = search.methods();
        if (methods.isEmpty()) {
            TypeInfo owner = search.implicitOwners().get(0);
            return CallTarget.typeOnly(owner, Resolution.INFERRED, rule + "_IMPLICIT_MEMBER", null, null);
        }
        if (methods.size() == 1) {
            return CallTarget.member(methods.get(0), Resolution.INFERRED, rule + "_UNIQUE_ARITY");
        }
        String signature = signatureKey(methods.get(0));
        boolean sameSignature = true;
        boolean sameOwner = true;
        for (MemberInfo method : methods) {
            sameSignature &= signatureKey(method).equals(signature);
            sameOwner &= method.declaringType() == methods.get(0).declaringType();
        }
        if (sameSignature) {
            return CallTarget.member(methods.get(0), Resolution.INFERRED, rule + "_MOST_DERIVED_OVERRIDE");
        }
        if (sameOwner) {
            return CallTarget.typeOnly(methods.get(0).declaringType(), Resolution.INFERRED, rule + "_OVERLOAD_OWNER",
                    DiagnosticCategory.AMBIGUOUS_CALL,
                    name + "() matches " + methods.size() + " overloads; method-level target is ambiguous");
        }
        return CallTarget.problem(DiagnosticCategory.AMBIGUOUS_CALL,
                name + "() matches " + methods.size() + " methods in different project types");
    }

    private static String signatureKey(MemberInfo member) {
        String id = member.id();
        return id.substring(id.indexOf('#'));
    }

    private Receiver receiverType(Expression scope, Ctx ctx, int depth) {
        if (depth > MAX_RECEIVER_DEPTH) {
            return Receiver.UNKNOWN;
        }
        if (scope instanceof EnclosedExpr enclosed) {
            return receiverType(enclosed.getInner(), ctx, depth + 1);
        }
        if (scope instanceof ThisExpr thisExpr) {
            if (thisExpr.getTypeName().isPresent()) {
                return fromTypeTarget(typeResolver.resolve(thisExpr, thisExpr.getTypeName().get().asString(), unit));
            }
            return insideLocalOrAnonymous(scope, ctx) ? Receiver.UNKNOWN
                    : Receiver.source(ctx.type(), Resolution.INFERRED, "THIS");
        }
        if (scope instanceof SuperExpr superExpr) {
            if (superExpr.getTypeName().isPresent()) {
                return fromTypeTarget(typeResolver.resolve(superExpr, superExpr.getTypeName().get().asString(), unit));
            }
            if (insideLocalOrAnonymous(scope, ctx)) {
                return Receiver.UNKNOWN;
            }
            if (ctx.type().node() instanceof ClassOrInterfaceDeclaration declaration
                    && !declaration.isInterface() && declaration.getExtendedTypes().isNonEmpty()) {
                ClassOrInterfaceType superclass = declaration.getExtendedTypes().get(0);
                return fromTypeTarget(typeResolver.resolve(superclass, superclass.getNameWithScope(), ctx.type().unit()));
            }
            return Receiver.EXTERNAL; // java.lang.Object, Enum or Record
        }
        Receiver solved = receiverFromSolver(scope);
        if (solved != null) {
            return solved;
        }
        if (scope instanceof NameExpr nameExpr) {
            String name = nameExpr.getNameAsString();
            Optional<Type> declared = VariableTypes.declaredType(name, scope, index);
            if (declared.isPresent()) {
                return fromDeclaredType(declared.get());
            }
            return fromTypeTarget(typeResolver.resolve(scope, name, unit)); // static call on a type name
        }
        if (scope instanceof FieldAccessExpr access && isQualifiedName(access)) {
            Receiver asType = fromTypeTarget(typeResolver.resolve(access, access.toString(), unit));
            if (asType.kind() != ReceiverKind.UNKNOWN) {
                return asType;
            }
        }
        if (scope instanceof ObjectCreationExpr creation) {
            return fromDeclaredType(creation.getType());
        }
        if (scope instanceof CastExpr cast) {
            return fromDeclaredType(cast.getType());
        }
        if (scope instanceof MethodCallExpr inner) {
            CallTarget innerTarget = resolveCall(inner, ctx, depth + 1);
            if (innerTarget.kind() == TargetKind.EXTERNAL) {
                return Receiver.EXTERNAL;
            }
            if (innerTarget.kind() == TargetKind.MEMBER && innerTarget.member().node() instanceof MethodDeclaration method) {
                Type returnType = method.getType();
                Receiver receiver = fromDeclaredType(returnType, innerTarget.member().declaringType().unit());
                return receiver.kind() == ReceiverKind.SOURCE
                        ? Receiver.source(receiver.type(), Resolution.INFERRED, "RETURN_TYPE") : receiver;
            }
        }
        return Receiver.UNKNOWN;
    }

    /**
     * True when the receiver is a variable whose declared type cannot be a project type: its simple name matches no
     * project type (and it is not a type parameter), or it is a primitive or array type. Library types cannot
     * declare or inherit project methods, so no symbol resolution is needed. Unknown or inferred ({@code var})
     * types return false and go through the solver.
     */
    private boolean declaredOutsideProject(Expression scope) {
        String name;
        if (scope instanceof NameExpr nameExpr) {
            name = nameExpr.getNameAsString();
        } else if (scope instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr thisExpr
                && thisExpr.getTypeName().isEmpty()) {
            name = access.getNameAsString();
        } else {
            return false;
        }
        Optional<Type> declared = VariableTypes.declaredType(name, scope, index);
        if (declared.isEmpty()) {
            return false;
        }
        Type type = declared.get();
        if (type.isPrimitiveType() || type.isArrayType()) {
            return true;
        }
        if (type instanceof ClassOrInterfaceType classType && !index.isTypeSimpleName(classType.getNameAsString())
                && !index.isUnparsedTypeName(classType.getNameAsString())) {
            return typeResolver.lookup().lookup(classType.getNameWithScope(), classType, unit).status()
                    != NameLookup.Status.TYPE_PARAMETER;
        }
        return false;
    }

    private Receiver receiverFromSolver(Expression scope) {
        return solve(() -> {
            ResolvedType type = facade.getType(scope);
            if (type.isReferenceType()) {
                Optional<ResolvedReferenceTypeDeclaration> declaration = type.asReferenceType().getTypeDeclaration();
                if (declaration.isPresent()) {
                    TypeInfo info = typeResolver.toProjectType(declaration.get());
                    return info != null ? Receiver.source(info, Resolution.RESOLVED, null) : Receiver.EXTERNAL;
                }
            } else if (type.isArray() || type.isPrimitive()) {
                return Receiver.EXTERNAL;
            }
            return null;
        }, scope);
    }

    private Receiver fromDeclaredType(Type type) {
        return fromDeclaredType(type, unit);
    }

    private Receiver fromDeclaredType(Type type, ParsedUnit owner) {
        if (type instanceof VarType || type instanceof UnknownType) {
            return Receiver.UNKNOWN;
        }
        if (type instanceof ClassOrInterfaceType classType) {
            return fromTypeTarget(typeResolver.resolve(classType, classType.getNameWithScope(), owner));
        }
        return type.isPrimitiveType() || type.isArrayType() ? Receiver.EXTERNAL : Receiver.UNKNOWN;
    }

    private static Receiver fromTypeTarget(TypeNameResolver.Target target) {
        return switch (target.outcome()) {
            case SOURCE -> Receiver.source(target.type(), target.resolution(), target.rule());
            case EXTERNAL -> Receiver.EXTERNAL;
            case LIKELY_EXTERNAL -> Receiver.LIKELY_EXTERNAL;
            default -> Receiver.UNKNOWN;
        };
    }

    private boolean insideLocalOrAnonymous(Node node, Ctx ctx) {
        for (Node current = node.getParentNode().orElse(null); current != null && current != ctx.type().node();
             current = current.getParentNode().orElse(null)) {
            if (current instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
                return true;
            }
            if (current instanceof TypeDeclaration<?> declaration && index.typeForNode(declaration) == null) {
                return true;
            }
        }
        return false;
    }

    private boolean isQualifiedName(FieldAccessExpr access) {
        Expression current = access;
        while (current instanceof FieldAccessExpr field) {
            current = field.getScope();
        }
        return current instanceof NameExpr;
    }

    private void handleConstructorTarget(ObjectCreationExpr creation) {
        ClassOrInterfaceType type = creation.getType();
        if (!index.isTypeSimpleName(type.getNameAsString())) {
            return; // the type reference itself is counted by handleTypeReference
        }
        Ctx ctx = contextOf(creation);
        if (ctx == null || ctx.member() == null) {
            return; // constructor edges exist only in the method-level graph
        }
        TypeNameResolver.Target typeTarget = typeResolver.resolve(type, type.getNameWithScope(), unit);
        if (!typeTarget.isSource() || typeTarget.type().constructors().isEmpty()) {
            return; // library type, or implicit default constructor (no method-level node)
        }
        CallTarget target = solve(() -> {
            SymbolReference<ResolvedConstructorDeclaration> ref = facade.solve(creation);
            return ref.isSolved() ? fromResolvedConstructor(ref.getCorrespondingDeclaration()) : null;
        }, creation);
        if (target == null) {
            target = constructorByArity(typeTarget.type(), creation.getArguments().size(), "CONSTRUCTOR");
        }
        applyCallTarget(ctx, target, RelationshipKind.OBJECT_CREATION, false, creation,
                "new " + type.getNameWithScope() + "(…)", type.getNameWithScope());
    }

    private void handleExplicitConstructorCall(ExplicitConstructorInvocationStmt invocation) {
        Ctx ctx = contextOf(invocation);
        if (ctx == null || ctx.member() == null) {
            return;
        }
        String detail = invocation.isThis() ? "this(…)" : "super(…)";
        TypeInfo targetType;
        if (invocation.isThis()) {
            targetType = ctx.type();
        } else {
            targetType = null;
            if (ctx.type().node() instanceof ClassOrInterfaceDeclaration declaration
                    && declaration.getExtendedTypes().isNonEmpty()) {
                ClassOrInterfaceType superclass = declaration.getExtendedTypes().get(0);
                TypeNameResolver.Target target = typeResolver.resolve(superclass, superclass.getNameWithScope(), unit);
                if (target.isSource()) {
                    targetType = target.type();
                }
            }
            if (targetType == null) {
                stats.external++;
                return;
            }
        }
        if (targetType.constructors().isEmpty()) {
            return;
        }
        CallTarget target = solve(() -> {
            SymbolReference<ResolvedConstructorDeclaration> ref = facade.solve(invocation);
            return ref.isSolved() ? fromResolvedConstructor(ref.getCorrespondingDeclaration()) : null;
        }, invocation);
        if (target == null) {
            target = constructorByArity(targetType, invocation.getArguments().size(),
                    invocation.isThis() ? "THIS_CONSTRUCTOR" : "SUPER_CONSTRUCTOR");
        }
        applyCallTarget(ctx, target, RelationshipKind.CONSTRUCTOR_CALL, true, invocation, detail, detail);
    }

    private CallTarget fromResolvedConstructor(ResolvedConstructorDeclaration declaration) {
        Node ast = astOf(declaration);
        if (ast != null) {
            MemberInfo member = index.memberForNode(ast);
            if (member != null) {
                return CallTarget.member(member, Resolution.RESOLVED, null);
            }
            if (ast.findCompilationUnit().isPresent()) {
                return implicitOrLocal(ast);
            }
        }
        return null;
    }

    /**
     * A resolved callable whose AST is not an indexed member: implicit members (enum {@code values()}, record
     * accessors, default constructors) report their declaring type as AST, which yields a type-level target;
     * members of local or anonymous classes are internal to the enclosing declaration.
     */
    private CallTarget implicitOrLocal(Node ast) {
        for (Node current = ast; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()
                    && current != ast) {
                return CallTarget.of(TargetKind.INTERNAL);
            }
            if (current instanceof TypeDeclaration<?> declaration) {
                TypeInfo owner = index.typeForNode(declaration);
                return owner == null ? CallTarget.of(TargetKind.INTERNAL)
                        : CallTarget.typeOnly(owner, Resolution.RESOLVED, null, null, null);
            }
        }
        return CallTarget.of(TargetKind.INTERNAL);
    }

    private CallTarget constructorByArity(TypeInfo type, int args, String rule) {
        List<MemberInfo> candidates = new ArrayList<>();
        for (MemberInfo constructor : type.constructors()) {
            if (constructor.acceptsArity(args)) {
                candidates.add(constructor);
            }
        }
        if (candidates.size() == 1) {
            return CallTarget.member(candidates.get(0), Resolution.INFERRED, rule + "_UNIQUE_ARITY");
        }
        if (candidates.isEmpty()) {
            return CallTarget.problem(DiagnosticCategory.UNRESOLVED_CALL,
                    "No constructor of " + type.displayName() + " accepts " + args + " argument(s)");
        }
        return CallTarget.problem(DiagnosticCategory.AMBIGUOUS_CALL,
                "Constructor of " + type.displayName() + " is ambiguous (" + candidates.size() + " candidates)");
    }

    private void handleMethodReference(MethodReferenceExpr reference) {
        String name = reference.getIdentifier();
        if ("new".equals(name)) {
            return; // the referenced type is recorded by handleTypeReference
        }
        if (!index.isMethodName(name)) {
            stats.external++;
            return;
        }
        Ctx ctx = contextOf(reference);
        if (ctx == null) {
            return;
        }
        CallTarget target = solve(() -> {
            SymbolReference<ResolvedMethodDeclaration> ref = facade.solve(reference);
            return ref.isSolved() ? fromResolvedMethod(ref.getCorrespondingDeclaration()) : null;
        }, reference);
        if (target == null) {
            Expression scope = reference.getScope();
            Receiver receiver = scope instanceof TypeExpr typeExpr
                    ? fromDeclaredType(typeExpr.getType())
                    : receiverType(scope, ctx, 0);
            target = switch (receiver.kind()) {
                case EXTERNAL -> CallTarget.of(TargetKind.EXTERNAL);
                case LIKELY_EXTERNAL -> CallTarget.likelyExternal("::" + name + " most likely refers to a library method");
                case UNKNOWN -> CallTarget.problem(DiagnosticCategory.UNRESOLVED_CALL,
                        "The target type of ::" + name + " could not be determined");
                case SOURCE -> callOnProjectType(receiver.type(), name, -1, true, "METHOD_REFERENCE");
            };
        }
        applyCallTarget(ctx, target, RelationshipKind.METHOD_REFERENCE, true, reference, "::" + name, "::" + name);
    }

    private void applyCallTarget(Ctx ctx, CallTarget target, RelationshipKind kind, boolean typeLevel, Node at,
                                 String detail, String symbol) {
        switch (target.kind()) {
            case MEMBER -> {
                boolean recorded = false;
                if (ctx.member() != null) {
                    record(GraphLayer.METHOD, ctx.member().id(), target.member().id(), kind, target.resolution(),
                            target.rule(), at, detail);
                    recorded = true;
                }
                if (typeLevel) {
                    recorded |= typeEdge(ctx, target.member().declaringType(), kind, target.resolution(), target.rule(),
                            at, detail);
                }
                if (recorded) {
                    count(target.resolution());
                } else {
                    stats.internal++;
                }
            }
            case TYPE_ONLY -> {
                boolean recorded = typeLevel
                        && typeEdge(ctx, target.type(), kind, target.resolution(), target.rule(), at, detail);
                if (recorded) {
                    count(target.resolution());
                } else {
                    stats.internal++;
                }
                if (target.problem() != null && ctx.member() != null) {
                    diagnostics.add(target.problem(), unit.relativePath(), line(at), idOf(ctx), symbol, target.reason());
                }
            }
            case EXTERNAL -> stats.external++;
            case INTERNAL -> stats.internal++;
            case LIKELY_EXTERNAL -> {
                stats.likelyExternal++;
                diagnostics.add(DiagnosticCategory.LIKELY_EXTERNAL, unit.relativePath(), line(at), idOf(ctx), symbol,
                        target.reason());
            }
            case PROBLEM -> {
                stats.unresolved++;
                diagnostics.add(target.problem(), unit.relativePath(), line(at), idOf(ctx), symbol, target.reason());
            }
        }
    }

    // ---------------------------------------------------------------- fields

    private void handleFieldAccess(FieldAccessExpr access) {
        String name = access.getNameAsString();
        if (!index.isFieldName(name)) {
            return;
        }
        String text = access.toString();
        if (index.isPackageOrPrefix(text) || !index.typesByCanonicalName(text).isEmpty()) {
            return; // a package or type name used as a qualifier, not a field access
        }
        Ctx ctx = contextOf(access);
        if (ctx == null) {
            return;
        }
        Resolution resolution = Resolution.RESOLVED;
        String rule = null;
        Optional<TypeInfo> solvedOwner = solve(() -> {
            SymbolReference<ResolvedValueDeclaration> ref = facade.solve(access);
            return ref.isSolved() ? Optional.ofNullable(declaringProjectType(ref.getCorrespondingDeclaration())) : null;
        }, access);
        boolean solved = solvedOwner != null;
        TypeInfo owner = solved ? solvedOwner.orElse(null) : null;
        String detail = TextSanitizer.singleLine(text, MAX_DETAIL);
        if (!solved) {
            Receiver receiver = receiverType(access.getScope(), ctx, 0);
            switch (receiver.kind()) {
                case EXTERNAL -> {
                    stats.external++;
                    return;
                }
                case LIKELY_EXTERNAL -> {
                    stats.likelyExternal++;
                    diagnostics.add(DiagnosticCategory.LIKELY_EXTERNAL, unit.relativePath(), line(access), idOf(ctx),
                            detail, "Field " + name + " most likely belongs to a library type");
                    return;
                }
                case UNKNOWN -> {
                    stats.unresolved++;
                    diagnostics.add(DiagnosticCategory.UNRESOLVED_FIELD, unit.relativePath(), line(access), idOf(ctx),
                            detail, "The owner of field " + name + " could not be determined");
                    return;
                }
                case SOURCE -> {
                    owner = fieldOwner(receiver.type(), name);
                    if (owner == null) {
                        if (hasNonSourceAncestor(receiver.type())) {
                            stats.external++;
                        } else {
                            stats.unresolved++;
                            diagnostics.add(DiagnosticCategory.UNRESOLVED_FIELD, unit.relativePath(), line(access),
                                    idOf(ctx), detail, "No field " + name + " found in " + receiver.type().displayName());
                        }
                        return;
                    }
                    resolution = Resolution.INFERRED;
                    rule = "RECEIVER_TYPE_FIELD";
                }
            }
        }
        if (owner == null) {
            stats.external++;
            return;
        }
        if (typeEdge(ctx, owner, RelationshipKind.FIELD_ACCESS, resolution, rule, access, detail)) {
            count(resolution);
        } else {
            stats.internal++;
        }
    }

    private void handleStaticImportedName(NameExpr nameExpr) {
        String name = nameExpr.getNameAsString();
        if (!index.isFieldName(name)) {
            return;
        }
        Node parent = nameExpr.getParentNode().orElse(null);
        if ((parent instanceof FieldAccessExpr access && access.getScope() == nameExpr)
                || (parent instanceof MethodCallExpr call && call.getScope().map(s -> s == nameExpr).orElse(false))
                || (parent instanceof MethodReferenceExpr reference && reference.getScope() == nameExpr)) {
            return; // qualifier; handled by the enclosing expression
        }
        List<TypeInfo> importedOwners = new ArrayList<>();
        for (String typeName : unit.staticSingleImports().getOrDefault(name, List.of())) {
            TypeInfo type = index.uniqueType(typeName);
            if (type != null && type.fieldNames().contains(name)) {
                importedOwners.add(type);
            }
        }
        for (String typeName : unit.staticOnDemandImports()) {
            TypeInfo type = index.uniqueType(typeName);
            if (type != null && type.fieldNames().contains(name)) {
                importedOwners.add(type);
            }
        }
        if (importedOwners.isEmpty()) {
            return; // not a statically imported project field (local variables and own fields are not dependencies)
        }
        Ctx ctx = contextOf(nameExpr);
        if (ctx == null) {
            return;
        }
        Resolution resolution = Resolution.RESOLVED;
        String rule = null;
        Optional<TypeInfo> solvedOwner = solve(() -> {
            SymbolReference<? extends ResolvedValueDeclaration> ref = facade.solve(nameExpr);
            return ref.isSolved() ? Optional.ofNullable(declaringProjectType(ref.getCorrespondingDeclaration())) : null;
        }, nameExpr);
        boolean solved = solvedOwner != null;
        TypeInfo owner = solved ? solvedOwner.orElse(null) : null;
        if (!solved) {
            boolean shadowed = VariableTypes.declaredType(name, nameExpr, index).isPresent();
            if (shadowed) {
                return; // a local variable, parameter or field with this name is in scope
            }
            if (importedOwners.size() > 1) {
                stats.unresolved++;
                diagnostics.add(DiagnosticCategory.UNRESOLVED_FIELD, unit.relativePath(), line(nameExpr), idOf(ctx),
                        name, "Statically imported field " + name + " is provided by several project types");
                return;
            }
            owner = importedOwners.get(0);
            resolution = Resolution.INFERRED;
            rule = "STATIC_IMPORT_FIELD";
        }
        if (owner == null) {
            return; // resolved to a local variable, parameter or library field
        }
        if (typeEdge(ctx, owner, RelationshipKind.FIELD_ACCESS, resolution, rule, nameExpr, "static import " + name)) {
            count(resolution);
        } else {
            stats.internal++;
        }
    }

    private TypeInfo declaringProjectType(ResolvedValueDeclaration declaration) {
        try {
            if (declaration.isField()) {
                ResolvedTypeDeclaration declaring = declaration.asField().declaringType();
                return typeResolver.toProjectType(declaring);
            }
            if (declaration.isEnumConstant()) {
                ResolvedType type = declaration.asEnumConstant().getType();
                if (type.isReferenceType()) {
                    return type.asReferenceType().getTypeDeclaration().map(typeResolver::toProjectType).orElse(null);
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static TypeInfo fieldOwner(TypeInfo type, String name) {
        ArrayDeque<TypeInfo> queue = new ArrayDeque<>();
        queue.add(type);
        Set<TypeInfo> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            TypeInfo current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (current.fieldNames().contains(name)) {
                return current;
            }
            queue.addAll(current.sourceSupertypes());
        }
        return null;
    }

    private static boolean hasNonSourceAncestor(TypeInfo type) {
        ArrayDeque<TypeInfo> queue = new ArrayDeque<>();
        queue.add(type);
        Set<TypeInfo> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            TypeInfo current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (current.hasNonSourceSupertype()) {
                return true;
            }
            queue.addAll(current.sourceSupertypes());
        }
        return false;
    }

    // ---------------------------------------------------------------- recording

    /** Records a type-level edge unless it is a self-reference. Returns whether an edge was recorded. */
    private boolean typeEdge(Ctx ctx, TypeInfo target, RelationshipKind kind, Resolution resolution, String rule,
                             Node at, String detail) {
        if (target == ctx.type()) {
            return false;
        }
        record(GraphLayer.TYPE, ctx.type().id(), target.id(), kind, resolution, rule, at, detail);
        return true;
    }

    private void record(GraphLayer layer, String source, String target, RelationshipKind kind, Resolution resolution,
                        String rule, Node at, String detail) {
        sink.add(layer, source, target, kind, new ReferenceOccurrence(unit.relativePath(), line(at),
                TextSanitizer.singleLine(detail, MAX_DETAIL), resolution, resolution == Resolution.INFERRED ? rule : null));
    }

    private void count(Resolution resolution) {
        if (resolution == Resolution.RESOLVED) {
            stats.resolved++;
        } else {
            stats.inferred++;
        }
    }

    private Ctx contextOf(Node node) {
        for (Node current = node.getParentNode().orElse(null); current != null;
             current = current.getParentNode().orElse(null)) {
            MemberInfo member = index.memberForNode(current);
            if (member != null) {
                return new Ctx(member.declaringType(), member);
            }
            TypeInfo type = index.typeForNode(current);
            if (type != null) {
                return new Ctx(type, null);
            }
        }
        return null;
    }

    private Ctx safeContext(Node node) {
        try {
            return contextOf(node);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String idOf(Ctx ctx) {
        return ctx.member() != null ? ctx.member().id() : ctx.type().id();
    }

    private static Node astOf(ResolvedDeclaration declaration) {
        try {
            return declaration.toAst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean containsIdentity(NodeList<? extends Node> list, Node node) {
        for (Node element : list) {
            if (element == node) {
                return true;
            }
        }
        return false;
    }

    private static int line(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    private static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String shortText(Node node) {
        try {
            return TextSanitizer.singleLine(node.toString(), 120);
        } catch (RuntimeException e) {
            return node.getClass().getSimpleName();
        }
    }
}
