package com.ripplemap.analysis.resolution;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;
import com.ripplemap.analysis.extraction.DeclarationIndex;
import com.ripplemap.analysis.extraction.TypeInfo;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the declared type of a simple name (local variable, parameter, pattern binding or field) visible at an
 * AST position, using lexical scoping only. Used by the conservative call-target inference when the symbol
 * solver cannot type an expression. A result of {@code var} or an implicit lambda parameter type means the
 * declaration exists but its type is not written.
 */
public final class VariableTypes {

    private VariableTypes() {
    }

    public static Optional<Type> declaredType(String name, Node context, DeclarationIndex index) {
        Position at = context.getBegin().orElse(null);
        Node child = context;
        for (Node current = context.getParentNode().orElse(null); current != null;
             child = current, current = current.getParentNode().orElse(null)) {
            Optional<Type> found = Optional.empty();
            if (current instanceof LambdaExpr lambda) {
                found = fromParameters(lambda.getParameters(), name);
            } else if (current instanceof CatchClause catchClause) {
                found = fromParameter(catchClause.getParameter(), name);
            } else if (current instanceof ForEachStmt forEach) {
                found = fromVariables(forEach.getVariable(), name);
            } else if (current instanceof ForStmt forStmt) {
                for (Expression init : forStmt.getInitialization()) {
                    if (found.isEmpty() && init instanceof VariableDeclarationExpr declarationExpr) {
                        found = fromVariables(declarationExpr, name);
                    }
                }
            } else if (current instanceof TryStmt tryStmt) {
                for (Expression resource : tryStmt.getResources()) {
                    if (found.isEmpty() && resource instanceof VariableDeclarationExpr declarationExpr) {
                        found = fromVariables(declarationExpr, name);
                    }
                }
            } else if (current instanceof BlockStmt block) {
                found = fromStatements(block.getStatements(), name, at);
            } else if (current instanceof SwitchEntry entry) {
                found = fromStatements(entry.getStatements(), name, at);
            } else if (current instanceof CallableDeclaration<?> callable) {
                found = fromParameters(callable.getParameters(), name);
                if (found.isEmpty()) {
                    found = fromPatterns(callable, name);
                }
            } else if (current instanceof CompactConstructorDeclaration compact
                    && compact.getParentNode().orElse(null) instanceof RecordDeclaration record) {
                found = fromParameters(record.getParameters(), name);
                if (found.isEmpty()) {
                    found = fromPatterns(compact, name);
                }
            } else if (current instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()
                    && child != creation.getType()) {
                found = fromMembers(creation.getAnonymousClassBody().get(), name);
            } else if (current instanceof TypeDeclaration<?> type) {
                found = fromType(type, name, index);
            }
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<Type> fromType(TypeDeclaration<?> type, String name, DeclarationIndex index) {
        if (type instanceof RecordDeclaration record) {
            Optional<Type> component = fromParameters(record.getParameters(), name);
            if (component.isPresent()) {
                return component;
            }
        }
        Optional<Type> own = fromMembers(type.getMembers(), name);
        if (own.isPresent()) {
            return own;
        }
        TypeInfo info = index.typeForNode(type);
        if (info == null) {
            return Optional.empty();
        }
        ArrayDeque<TypeInfo> queue = new ArrayDeque<>(info.sourceSupertypes());
        Set<TypeInfo> seen = new HashSet<>();
        seen.add(info);
        while (!queue.isEmpty()) {
            TypeInfo supertype = queue.poll();
            if (!seen.add(supertype)) {
                continue;
            }
            Optional<Type> inherited = fromMembers(supertype.node().getMembers(), name);
            if (inherited.isPresent()) {
                return inherited;
            }
            queue.addAll(supertype.sourceSupertypes());
        }
        return Optional.empty();
    }

    private static Optional<Type> fromMembers(NodeList<? extends BodyDeclaration<?>> members, String name) {
        for (BodyDeclaration<?> member : members) {
            if (member instanceof FieldDeclaration field) {
                for (VariableDeclarator variable : field.getVariables()) {
                    if (variable.getNameAsString().equals(name)) {
                        return Optional.of(variable.getType());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Type> fromStatements(NodeList<Statement> statements, String name, Position at) {
        Type found = null;
        for (Statement statement : statements) {
            if (at != null && statement.getBegin().map(p -> p.isAfter(at)).orElse(false)) {
                break;
            }
            if (statement instanceof ExpressionStmt expressionStmt
                    && expressionStmt.getExpression() instanceof VariableDeclarationExpr declarationExpr) {
                Optional<Type> type = fromVariables(declarationExpr, name);
                if (type.isPresent()) {
                    found = type.get();
                }
            }
        }
        return Optional.ofNullable(found);
    }

    private static Optional<Type> fromVariables(VariableDeclarationExpr declarationExpr, String name) {
        for (VariableDeclarator variable : declarationExpr.getVariables()) {
            if (variable.getNameAsString().equals(name)) {
                return Optional.of(variable.getType());
            }
        }
        return Optional.empty();
    }

    private static Optional<Type> fromParameters(NodeList<Parameter> parameters, String name) {
        for (Parameter parameter : parameters) {
            Optional<Type> type = fromParameter(parameter, name);
            if (type.isPresent()) {
                return type;
            }
        }
        return Optional.empty();
    }

    private static Optional<Type> fromParameter(Parameter parameter, String name) {
        return parameter.getNameAsString().equals(name) ? Optional.of(parameter.getType()) : Optional.empty();
    }

    private static Optional<Type> fromPatterns(Node body, String name) {
        for (TypePatternExpr pattern : body.findAll(TypePatternExpr.class)) {
            if (pattern.getNameAsString().equals(name)) {
                return Optional.of(pattern.getType());
            }
        }
        return Optional.empty();
    }
}
