package com.ripplemap.performance;

import com.ripplemap.analysis.AnalysisRequest;
import com.ripplemap.analysis.JavaProjectAnalyzer;
import com.ripplemap.analysis.model.AnalysisResult;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.graph.GraphFactory;
import com.ripplemap.graph.algorithms.CycleDetector;
import com.ripplemap.graph.model.DependencyGraph;
import com.ripplemap.impact.ImpactCalculator;
import com.ripplemap.impact.ImpactThresholds;
import com.ripplemap.service.SnapshotMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures the pipeline on generated projects. Run with {@code mvnw test -Pperformance}; results are printed as
 * PERF lines. A real project can be measured with {@code -Dripplemap.perf.dir=/path/to/sources}.
 */
@Tag("performance")
class AnalysisPerformanceTest {

    @TempDir
    Path root;

    /**
     * Writes {@code classes} classes in 10 packages. Every class has two fields of earlier classes, calls a method
     * on each of them and creates one object, giving a layered graph. About one class in 25 (after the first 20) also
     * references a later class, and that later class references it back, closing a two-type cycle.
     */
    static void generate(Path root, int classes, long seed) throws IOException {
        Random random = new Random(seed);
        Map<Integer, Integer> backReference = new HashMap<>();
        for (int i = 0; i < classes; i++) {
            int pkg = i % 10;
            StringBuilder sb = new StringBuilder();
            sb.append("package gen.p").append(pkg).append(";\n\n");
            int a = i == 0 ? -1 : random.nextInt(i);
            int b = i == 0 ? -1 : random.nextInt(i);
            if (i > 20 && random.nextInt(25) == 0 && i + 1 < classes) {
                a = Math.min(classes - 1, i + 1 + random.nextInt(5)); // forward reference ...
                backReference.putIfAbsent(a, i);
            }
            if (backReference.containsKey(i)) {
                b = backReference.get(i); // ... answered by a reference back, which closes the cycle
            }
            for (int ref : new int[]{a, b}) {
                if (ref >= 0) {
                    sb.append("import gen.p").append(ref % 10).append(".C").append(ref).append(";\n");
                }
            }
            sb.append("\npublic class C").append(i).append(" {\n");
            if (a >= 0) {
                sb.append("    private C").append(a).append(" first;\n");
            }
            if (b >= 0) {
                sb.append("    private final C").append(b).append(" second = new C").append(b).append("();\n");
            }
            sb.append("    public int value").append(i).append("(int x) {\n        int sum = x;\n");
            if (a >= 0) {
                sb.append("        sum += first.value").append(a).append("(x + 1);\n");
            }
            if (b >= 0) {
                sb.append("        sum += second.value").append(b).append("(x * 2);\n");
            }
            sb.append("        return sum;\n    }\n}\n");
            Path file = root.resolve("src/main/java/gen/p" + pkg + "/C" + i + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString());
        }
    }

    record Measured(AnalysisResult result, int cycles) {
    }

    private static Measured timed(String label, Path root) throws Exception {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long before = runtime.totalMemory() - runtime.freeMemory();
        long start = System.nanoTime();
        AnalysisResult result = new JavaProjectAnalyzer().analyze(AnalysisRequest.defaults(root, false)
                .withDeadline(Instant.now().plus(Duration.ofMinutes(30))).withLimits(2L * 1024 * 1024, 20_000, 5_000));
        long analysisMs = (System.nanoTime() - start) / 1_000_000;
        long metricsStart = System.nanoTime();
        SnapshotMetrics metrics = SnapshotMetrics.compute(result, 20_000, () -> {
        });
        long metricsMs = (System.nanoTime() - metricsStart) / 1_000_000;
        long after = runtime.totalMemory() - runtime.freeMemory();
        List<String> types = result.entities().stream().filter(e -> e.kind().isType()).map(e -> e.stableId()).toList();
        DependencyGraph graph = GraphFactory.build(types, result.relationships(), GraphLayer.TYPE, null, true);
        long impactStart = System.nanoTime();
        int impacts = 0;
        for (String id : types.subList(0, Math.min(100, types.size()))) {
            ImpactCalculator.compute(graph, id, ImpactThresholds.DEFAULT);
            impacts++;
        }
        double impactMs = (System.nanoTime() - impactStart) / 1_000_000.0 / Math.max(1, impacts);
        long cycleStart = System.nanoTime();
        int cycles = CycleDetector.find(graph).size();
        long cycleMs = (System.nanoTime() - cycleStart) / 1_000_000;
        System.out.printf("PERF %s files=%d parsed=%d types=%d typeDeps=%d methodDeps=%d resolved=%d inferred=%d unresolved=%d "
                        + "solverFailures=%d overBudget=%d analysisMs=%d metricsMs=%d impactMsAvg=%.2f cycles=%d cycleMs=%d heapDeltaMB=%d%n",
                label, result.fileCounts().inScope(), result.fileCounts().parsed(), metrics.typeCount(),
                metrics.typeDependencyPairs(), metrics.methodDependencyPairs(), result.references().resolved(),
                result.references().inferred(), result.references().unresolved(), result.references().solverFailures(),
                result.references().budgetExceeded(), analysisMs, metricsMs, impactMs, cycles, cycleMs,
                Math.max(0, (after - before) / (1024 * 1024)));
        return new Measured(result, cycles);
    }

    @ParameterizedTest(name = "{0} classes")
    @ValueSource(ints = {10, 100, 500, 1000})
    void generatedProjects(int classes) throws Exception {
        generate(root, classes, 42);
        Measured measured = timed("generated-" + classes, root);
        assertThat(measured.result().fileCounts().parsed()).isEqualTo(classes);
        assertThat(measured.result().references().unresolved()).isZero();
        if (classes >= 100) {
            assertThat(measured.cycles()).isPositive();
        }
    }

    @Test
    void externalDirectory() throws Exception {
        String dir = System.getProperty("ripplemap.perf.dir");
        if (dir == null || dir.isBlank()) {
            System.out.println("PERF external skipped (set -Dripplemap.perf.dir)");
            return;
        }
        timed("external", Path.of(dir));
    }
}
