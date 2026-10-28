package com.ripplemap.analysis.extraction;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;

import java.util.List;
import java.util.StringJoiner;

/**
 * Stable identifier construction. Identifiers depend only on source text, never on database ids.
 *
 * <pre>
 * type         com.shop.Order            (default package: Order)
 * nested type  com.shop.Order$Line       ('$' separates member types)
 * method       com.shop.Order#total(int,java.util.List)   parameter types as written, generics erased
 * constructor  com.shop.Order#&lt;init&gt;(String)
 * field        com.shop.Order#items
 * </pre>
 * When two files declare the same canonical type name, every affected type id gets the suffix
 * {@code [relative/path/File.java]} so identities stay distinct.
 */
public final class StableIds {

    public static final String CONSTRUCTOR_NAME = "<init>";

    private StableIds() {
    }

    public static String qualify(String packageName, String simpleName) {
        return packageName == null || packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    public static String callableId(String typeId, String name, List<String> parameterTypes) {
        StringJoiner joiner = new StringJoiner(",", "(", ")");
        parameterTypes.forEach(joiner::add);
        return typeId + "#" + name + joiner;
    }

    public static String fieldId(String typeId, String fieldName) {
        return typeId + "#" + fieldName;
    }

    /** Parameter type for identifiers: the written type name without generic arguments or annotations. */
    public static String parameterType(Parameter parameter) {
        return erase(parameter.getType()) + (parameter.isVarArgs() ? "..." : "");
    }

    public static String erase(Type type) {
        if (type instanceof ClassOrInterfaceType classType) {
            return classType.getNameWithScope();
        }
        if (type instanceof ArrayType arrayType) {
            return erase(arrayType.getComponentType()) + "[]";
        }
        if (type instanceof PrimitiveType primitiveType) {
            return primitiveType.asString();
        }
        return type.asString();
    }

    /** Display form of a parameter: the written type including generic arguments. */
    public static String displayParameterType(Parameter parameter) {
        return parameter.getType().asString() + (parameter.isVarArgs() ? "..." : "");
    }
}
