package com.ripplemap.analysis;

import com.github.javaparser.ParserConfiguration;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Input of one analysis run.
 *
 * @param root                directory containing the project sources (never modified)
 * @param includeTests        whether test sources are analyzed
 * @param maxFileBytes        files larger than this are not parsed (recorded as FILE_TOO_LARGE)
 * @param maxJavaFiles        in-scope Java files above this count abort the analysis
 * @param maxDiagnostics      diagnostics stored (counts stay exact)
 * @param maxReferenceSamples reference locations stored per relationship
 * @param referenceBudget     time one symbol-solver invocation may take before the fallback rules are used
 * @param deadline            cooperative deadline, checked between files, while walking ASTs and inside the solver
 * @param cancelled           cooperative cancellation flag
 * @param progress            receives short stage descriptions
 */
public record AnalysisRequest(
        Path root,
        boolean includeTests,
        ParserConfiguration.LanguageLevel languageLevel,
        long maxFileBytes,
        int maxJavaFiles,
        int maxDiagnostics,
        int maxReferenceSamples,
        Duration referenceBudget,
        Instant deadline,
        BooleanSupplier cancelled,
        Consumer<String> progress) {

    public static AnalysisRequest defaults(Path root, boolean includeTests) {
        return new AnalysisRequest(root, includeTests, ParserConfiguration.LanguageLevel.JAVA_21,
                2L * 1024 * 1024, 10_000, 5_000, 20, Duration.ofSeconds(2), Instant.now().plusSeconds(600), () -> false, stage -> {
        });
    }

    public AnalysisRequest withDeadline(Instant newDeadline) {
        return new AnalysisRequest(root, includeTests, languageLevel, maxFileBytes, maxJavaFiles, maxDiagnostics,
                maxReferenceSamples, referenceBudget, newDeadline, cancelled, progress);
    }

    public AnalysisRequest withReferenceBudget(Duration budget) {
        return new AnalysisRequest(root, includeTests, languageLevel, maxFileBytes, maxJavaFiles, maxDiagnostics,
                maxReferenceSamples, budget, deadline, cancelled, progress);
    }

    public AnalysisRequest withLimits(long newMaxFileBytes, int newMaxJavaFiles, int newMaxDiagnostics) {
        return new AnalysisRequest(root, includeTests, languageLevel, newMaxFileBytes, newMaxJavaFiles,
                newMaxDiagnostics, maxReferenceSamples, referenceBudget, deadline, cancelled, progress);
    }
}
