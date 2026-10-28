package com.ripplemap.analysis.extraction;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A successfully parsed compilation unit together with its import metadata. */
public final class ParsedUnit {

    /** {@code memberName -> type canonical names} for single static imports. */
    private final Map<String, List<String>> staticSingleImports = new LinkedHashMap<>();
    /** simple name -> canonical name for single-type imports. */
    private final Map<String, String> singleTypeImports = new LinkedHashMap<>();
    private final List<String> onDemandImports = new ArrayList<>();
    private final List<String> staticOnDemandImports = new ArrayList<>();
    private final String relativePath;
    private final String packageName;
    private final CompilationUnit compilationUnit;
    private final boolean testSource;
    private final int importCount;

    public ParsedUnit(String relativePath, CompilationUnit compilationUnit, boolean testSource) {
        this.relativePath = relativePath;
        this.compilationUnit = compilationUnit;
        this.testSource = testSource;
        this.packageName = compilationUnit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        int count = 0;
        for (ImportDeclaration imp : compilationUnit.getImports()) {
            count++;
            String name = imp.getNameAsString();
            if (imp.isStatic()) {
                if (imp.isAsterisk()) {
                    staticOnDemandImports.add(name);
                } else {
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        staticSingleImports.computeIfAbsent(name.substring(dot + 1), k -> new ArrayList<>())
                                .add(name.substring(0, dot));
                    }
                }
            } else if (imp.isAsterisk()) {
                onDemandImports.add(name);
            } else {
                int dot = name.lastIndexOf('.');
                singleTypeImports.putIfAbsent(dot >= 0 ? name.substring(dot + 1) : name, name);
            }
        }
        this.importCount = count;
    }

    public String relativePath() {
        return relativePath;
    }

    public String packageName() {
        return packageName;
    }

    public CompilationUnit compilationUnit() {
        return compilationUnit;
    }

    public boolean testSource() {
        return testSource;
    }

    public int importCount() {
        return importCount;
    }

    public Map<String, String> singleTypeImports() {
        return Collections.unmodifiableMap(singleTypeImports);
    }

    public List<String> onDemandImports() {
        return Collections.unmodifiableList(onDemandImports);
    }

    public Map<String, List<String>> staticSingleImports() {
        return Collections.unmodifiableMap(staticSingleImports);
    }

    public List<String> staticOnDemandImports() {
        return Collections.unmodifiableList(staticOnDemandImports);
    }

    public boolean hasStaticImports() {
        return !staticSingleImports.isEmpty() || !staticOnDemandImports.isEmpty();
    }
}
