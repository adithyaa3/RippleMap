package com.ripplemap.analysis;

import com.ripplemap.analysis.diagnostics.DiagnosticCategory;
import com.ripplemap.analysis.model.AnalysisResult;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.Resolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ripplemap.testsupport.Fixtures.analyze;
import static com.ripplemap.testsupport.Fixtures.entity;
import static com.ripplemap.testsupport.Fixtures.relationships;
import static com.ripplemap.testsupport.Fixtures.typeIds;
import static com.ripplemap.testsupport.Fixtures.typePairs;
import static com.ripplemap.testsupport.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaProjectAnalyzerEdgeCaseTest {

    @TempDir
    Path root;

    private AnalysisResult run() {
        return analyze(root, false);
    }

    private long count(AnalysisResult result, DiagnosticCategory category) {
        return result.diagnosticCounts().getOrDefault(category, 0);
    }

    @Test
    void emptyAndPackageOnlyFilesParseWithoutDeclarations() {
        write(root, "src/main/java/p/Empty.java", "");
        write(root, "src/main/java/p/package-info.java", "package p;\n");
        AnalysisResult result = run();
        assertThat(result.partial()).isFalse();
        assertThat(result.fileCounts().parsed()).isEqualTo(2);
        assertThat(result.entities()).isEmpty();
        assertThat(result.relationships()).isEmpty();
    }

    @Test
    void multipleTopLevelClassesInOneFile() {
        write(root, "src/main/java/p/Pair.java", """
                package p;
                public class Pair { Second second; }
                class Second { }
                """);
        AnalysisResult result = run();
        assertThat(typeIds(result)).containsExactlyInAnyOrder("p.Pair", "p.Second");
        assertThat(typePairs(result)).containsExactly("p.Pair->p.Second");
        assertThat(result.files()).singleElement().satisfies(f -> assertThat(f.typeCount()).isEqualTo(2));
    }

    @Test
    void byteOrderMarkIsIgnoredAndInvalidUtf8IsReported() throws IOException {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "package p; public class WithBom { Other o; }".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("src/main/java/p"));
        byte[] withBom = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(body, 0, withBom, bom.length, body.length);
        Files.write(root.resolve("src/main/java/p/WithBom.java"), withBom);
        byte[] latin1 = "package p; /* café */ public class Other { }".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(root.resolve("src/main/java/p/Other.java"), latin1);

        AnalysisResult result = run();
        assertThat(result.partial()).isFalse();
        assertThat(typePairs(result)).containsExactly("p.WithBom->p.Other");
        assertThat(count(result, DiagnosticCategory.ENCODING_REPLACED)).isEqualTo(1);
    }

    @Test
    void generatedBuildOutputIsExcluded() {
        write(root, "src/main/java/p/Real.java", "package p; public class Real { Gen g; }");
        write(root, "target/generated-sources/annotations/p/Gen.java", "package p; public class Gen { }");
        write(root, "com/x/build/Kept.java", "package com.x.build; public class Kept { }");
        AnalysisResult result = run();
        assertThat(result.fileCounts().discovered()).isEqualTo(3);
        assertThat(result.fileCounts().excludedGenerated()).isEqualTo(1);
        assertThat(typeIds(result)).containsExactlyInAnyOrder("p.Real", "com.x.build.Kept");
        // the excluded type is no project type: the reference is external, not an edge
        assertThat(result.relationships()).isEmpty();
    }

    @Test
    void oversizedFilesAreSkippedAndMakeTheAnalysisPartial() throws Exception {
        write(root, "src/main/java/p/Small.java", "package p; public class Small { }");
        write(root, "src/main/java/p/Big.java", "package p; public class Big { }" + " ".repeat(5000));
        AnalysisRequest request = AnalysisRequest.defaults(root, false).withLimits(1000, 100, 100);
        AnalysisResult result = new JavaProjectAnalyzer().analyze(request);
        assertThat(result.partial()).isTrue();
        assertThat(result.partialReasons()).containsExactly("1 file(s) exceeded the size limit");
        assertThat(count(result, DiagnosticCategory.FILE_TOO_LARGE)).isEqualTo(1);
        assertThat(typeIds(result)).containsExactly("p.Small");
    }

    @Test
    void tooManyFilesAbortsBeforeParsing() {
        for (int i = 0; i < 5; i++) {
            write(root, "src/main/java/p/C" + i + ".java", "package p; class C" + i + " {}");
        }
        AnalysisRequest request = AnalysisRequest.defaults(root, false).withLimits(10_000, 4, 100);
        assertThatThrownBy(() -> new JavaProjectAnalyzer().analyze(request))
                .isInstanceOf(AnalysisLimitException.class)
                .hasMessageContaining("5 Java files");
    }

    @Test
    void cancellationAndDeadlineStopTheAnalysis() {
        write(root, "src/main/java/p/A.java", "package p; class A {}");
        AnalysisRequest base = AnalysisRequest.defaults(root, false);
        AnalysisRequest cancelled = new AnalysisRequest(root, false, base.languageLevel(), base.maxFileBytes(),
                base.maxJavaFiles(), base.maxDiagnostics(), base.maxReferenceSamples(), base.referenceBudget(), base.deadline(),
                new AtomicBoolean(true)::get, s -> {
        });
        assertThatThrownBy(() -> new JavaProjectAnalyzer().analyze(cancelled))
                .isInstanceOfSatisfying(AnalysisInterruptedException.class,
                        e -> assertThat(e.reason()).isEqualTo(AnalysisInterruptedException.Reason.CANCELLED));
        AnalysisRequest expired = base.withDeadline(Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> new JavaProjectAnalyzer().analyze(expired))
                .isInstanceOfSatisfying(AnalysisInterruptedException.class,
                        e -> assertThat(e.reason()).isEqualTo(AnalysisInterruptedException.Reason.DEADLINE_EXCEEDED));
    }

    @Test
    void exhaustedSolverBudgetFallsBackToTheDocumentedRules() throws Exception {
        AnalysisRequest request = AnalysisRequest.defaults(com.ripplemap.testsupport.Fixtures.path("chain"), false)
                .withReferenceBudget(java.time.Duration.ofNanos(1));
        AnalysisResult result = new JavaProjectAnalyzer().analyze(request);
        String c = "com.acme.chain.";
        // every solver invocation is cut off at its first type lookup, yet the inference rules find the same edges
        assertThat(typePairs(result)).containsExactly(c + "Controller->" + c + "Service", c + "Service->" + c + "Repository");
        assertThat(relationships(result, GraphLayer.METHOD)).containsExactlyInAnyOrder(
                c + "Controller#get(long)->" + c + "Service#describe(long):METHOD_CALL",
                c + "Service#describe(long)->" + c + "Repository#find(long):METHOD_CALL");
        assertThat(result.relationships()).allMatch(r -> r.resolution() == Resolution.INFERRED);
        assertThat(result.references().budgetExceeded()).isPositive();
        assertThat(count(result, DiagnosticCategory.RESOLUTION_TIMEOUT)).isEqualTo(result.references().budgetExceeded());
        assertThat(result.references().unresolved()).isZero();
    }

    @Test
    void localAnonymousAndLambdaCodeIsAttributedToTheEnclosingDeclaration() {
        write(root, "src/main/java/p/Base.java", "package p; public class Base { }");
        write(root, "src/main/java/p/Target.java", "package p; public class Target { public void go() { } }");
        write(root, "src/main/java/p/Outer.java", """
                package p;
                import java.util.function.Supplier;
                public class Outer {
                    void run() {
                        class Local extends Base { Target t; }
                        Runnable r = new Runnable() {
                            public void run() { new Target().go(); }
                        };
                        Supplier<Target> s = () -> new Target();
                    }
                }
                """);
        AnalysisResult result = run();
        assertThat(typeIds(result)).containsExactlyInAnyOrder("p.Base", "p.Target", "p.Outer");
        assertThat(relationships(result, GraphLayer.TYPE)).containsExactlyInAnyOrder(
                "p.Outer->p.Base:TYPE_REFERENCE",
                "p.Outer->p.Target:TYPE_REFERENCE",
                "p.Outer->p.Target:OBJECT_CREATION",
                "p.Outer->p.Target:METHOD_CALL");
        assertThat(relationships(result, GraphLayer.METHOD)).containsExactly("p.Outer#run()->p.Target#go():METHOD_CALL");
    }

    @Test
    void inheritedMethodCallsTargetTheDeclaringSupertype() {
        write(root, "src/main/java/p/Parent.java", "package p; public class Parent { protected int helper() { return 1; } }");
        write(root, "src/main/java/p/Child.java", "package p; public class Child extends Parent { int m() { return helper(); } }");
        write(root, "src/main/java/p/Caller.java", "package p; public class Caller { int c(Child child) { return child.helper(); } }");
        AnalysisResult result = run();
        assertThat(relationships(result, GraphLayer.TYPE)).containsExactlyInAnyOrder(
                "p.Child->p.Parent:EXTENDS",
                "p.Child->p.Parent:METHOD_CALL",
                "p.Caller->p.Child:METHOD_PARAMETER_TYPE",
                "p.Caller->p.Parent:METHOD_CALL");
        assertThat(relationships(result, GraphLayer.METHOD)).containsExactlyInAnyOrder(
                "p.Child#m()->p.Parent#helper():METHOD_CALL",
                "p.Caller#c(Child)->p.Parent#helper():METHOD_CALL");
    }

    @Test
    void duplicateCanonicalNamesGetDistinctIdsAndAmbiguousReferences() {
        write(root, "module-a/src/main/java/com/x/App.java", "package com.x; public class App { }");
        write(root, "module-b/src/main/java/com/x/App.java", "package com.x; public class App { }");
        write(root, "module-a/src/main/java/com/x/User.java", "package com.x; public class User { App app; }");
        AnalysisResult result = run();
        assertThat(typeIds(result)).containsExactlyInAnyOrder(
                "com.x.App[module-a/src/main/java/com/x/App.java]",
                "com.x.App[module-b/src/main/java/com/x/App.java]",
                "com.x.User");
        assertThat(count(result, DiagnosticCategory.DUPLICATE_TYPE)).isEqualTo(2);
        assertThat(count(result, DiagnosticCategory.AMBIGUOUS_TYPE)).isEqualTo(1);
        assertThat(result.relationships()).isEmpty();
    }

    @Test
    void filesWhosePackageDoesNotMatchTheirDirectoryStillResolve() {
        write(root, "misplaced/Model.java", "package com.real; public class Model { }");
        write(root, "src/Service.java", "package com.app; import com.real.Model; public class Service { Model model; }");
        AnalysisResult result = run();
        assertThat(typePairs(result)).containsExactly("com.app.Service->com.real.Model");
        assertThat(result.relationships()).allMatch(r -> r.resolution() == Resolution.RESOLVED);
    }

    @Test
    void defaultPackageTypes() {
        write(root, "Main.java", "public class Main { public static void main(String[] a) { Util.help(); } }");
        write(root, "Util.java", "public class Util { static void help() { } }");
        AnalysisResult result = run();
        assertThat(typeIds(result)).containsExactlyInAnyOrder("Main", "Util");
        assertThat(entity(result, "Main").packageName()).isEmpty();
        assertThat(relationships(result, GraphLayer.METHOD)).containsExactly("Main#main(String[])->Util#help():METHOD_CALL");
    }

    @Test
    void varargsAndOverloadsResolveToTheRightDeclaration() {
        write(root, "src/main/java/p/Log.java", """
                package p;
                public class Log {
                    public static void write(String first, String... rest) { }
                    public static void write(int code) { }
                }
                """);
        write(root, "src/main/java/p/App.java", """
                package p;
                public class App {
                    void run() { Log.write("a", "b", "c"); Log.write(7); }
                }
                """);
        AnalysisResult result = run();
        assertThat(relationships(result, GraphLayer.METHOD)).containsExactlyInAnyOrder(
                "p.App#run()->p.Log#write(String,String...):METHOD_CALL",
                "p.App#run()->p.Log#write(int):METHOD_CALL");
    }

    @Test
    void recursionIsAMethodLevelSelfLoopButNotATypeLevelEdge() {
        write(root, "src/main/java/p/Node.java", """
                package p;
                public class Node {
                    Node next;
                    int depth() { return next == null ? 0 : 1 + next.depth(); }
                }
                """);
        AnalysisResult result = run();
        assertThat(typePairs(result)).isEmpty();
        assertThat(relationships(result, GraphLayer.METHOD)).containsExactly("p.Node#depth()->p.Node#depth():METHOD_CALL");
        assertThat(result.references().internal()).isPositive();
    }

    @Test
    void unknownLibraryTypesWithProjectNamesComeFromOnDemandImports() {
        write(root, "src/main/java/p/Order.java", "package p; public class Order { }");
        write(root, "src/main/java/q/Client.java", """
                package q;
                import org.lib.*;
                public class Client { Order order; }
                """);
        AnalysisResult result = run();
        assertThat(result.relationships()).isEmpty();
        assertThat(count(result, DiagnosticCategory.LIKELY_EXTERNAL)).isEqualTo(1);
        assertThat(result.references().likelyExternal()).isEqualTo(1);
        assertThat(result.references().unresolved()).isZero();
    }

    @Test
    void filesUsingCodeGeneratorsAreReportedBecauseGeneratedMembersAreInvisible() {
        write(root, "src/main/java/p/Order.java", """
                package p;

                import lombok.Getter;
                import lombok.experimental.Accessors;

                @Getter
                public class Order { private int total; }
                """);
        write(root, "src/main/java/p/Billing.java", """
                package p;
                public class Billing {
                    int sum(Order order) { return order.getTotal(); }
                }
                """);
        AnalysisResult result = run();
        // the generated getter is not a declaration: the type-level dependency remains, the call adds no edge
        assertThat(relationships(result, GraphLayer.TYPE)).containsExactly("p.Billing->p.Order:METHOD_PARAMETER_TYPE");
        assertThat(relationships(result, GraphLayer.METHOD)).isEmpty();
        assertThat(count(result, DiagnosticCategory.GENERATED_CODE)).isEqualTo(1);
        assertThat(result.diagnostics()).filteredOn(d -> d.category() == DiagnosticCategory.GENERATED_CODE)
                .singleElement().satisfies(d -> {
                    assertThat(d.filePath()).isEqualTo("src/main/java/p/Order.java");
                    assertThat(d.line()).isEqualTo(3);
                    assertThat(d.symbol()).isEqualTo("lombok.Getter");
                });
    }
}
