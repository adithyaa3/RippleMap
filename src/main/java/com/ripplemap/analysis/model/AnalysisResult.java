package com.ripplemap.analysis.model;

import com.ripplemap.analysis.diagnostics.Diagnostic;
import com.ripplemap.analysis.diagnostics.DiagnosticCategory;

import java.util.List;
import java.util.Map;

/**
 * Complete output of one analysis run (pure data, independent of persistence).
 *
 * @param partial           true when some in-scope files could not be analyzed
 * @param partialReasons    human-readable reasons for partial results
 * @param sourceFingerprint SHA-256 over the sorted (path, content hash) pairs of analyzed files
 */
public record AnalysisResult(
        boolean partial,
        List<String> partialReasons,
        FileCounts fileCounts,
        List<SourceFileInfo> files,
        List<ExtractedEntity> entities,
        List<AggregatedRelationship> relationships,
        List<Diagnostic> diagnostics,
        Map<DiagnosticCategory, Integer> diagnosticCounts,
        boolean diagnosticsTruncated,
        ReferenceStatistics references,
        String sourceFingerprint,
        String languageLevel) {
}
