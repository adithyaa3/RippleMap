package com.ripplemap.dto;

import com.ripplemap.analysis.diagnostics.DiagnosticCategory;
import com.ripplemap.analysis.diagnostics.Severity;
import com.ripplemap.entity.AnalysisStatus;
import com.ripplemap.entity.AnalysisTrigger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AnalysisDtos {

    private AnalysisDtos() {
    }

    public record AnalysisSummary(
            long id,
            long projectId,
            int sequenceNumber,
            AnalysisStatus status,
            AnalysisTrigger trigger,
            boolean includeTests,
            Long versionId,
            Integer versionNumber,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            String stage,
            String errorMessage,
            List<String> partialReasons,
            boolean hasResults,
            String languageLevel,
            String sourceFingerprint,
            Counts counts) {
    }

    /** Snapshot counts; all null until the analysis has finished successfully. */
    public record Counts(
            Integer filesDiscovered,
            Integer filesInScope,
            Integer filesExcludedTests,
            Integer filesExcludedGenerated,
            Integer filesParsed,
            Integer filesFailed,
            Integer filesSkippedLinks,
            Integer typeCount,
            Integer methodCount,
            Integer constructorCount,
            Integer fieldCount,
            Integer typeDependencyCount,
            Integer typeRelationshipCount,
            Integer methodDependencyCount,
            Integer methodRelationshipCount,
            Long referencesResolved,
            Long referencesInferred,
            Long referencesUnresolved,
            Long referencesLikelyExternal,
            Long referencesExternal,
            Long referencesInternal,
            Long solverFailures,
            Integer infoCount,
            Integer warningCount,
            Integer errorCount,
            Integer diagnosticsStored,
            Boolean diagnosticsTruncated,
            Integer typeCyclicComponents,
            Integer typeNodesInCycles,
            Integer methodCyclicComponents,
            Integer isolatedTypes,
            Boolean nodeMetricsComputed) {
    }

    public record DiagnosticItem(Severity severity, DiagnosticCategory category, String filePath, int line,
                                 String entityId, String symbol, String message) {
    }

    public record CategoryCount(DiagnosticCategory category, Severity severity, int count, String description,
                                boolean mayHideDependencies) {
    }

    public record DiagnosticPage(long analysisId, int page, int size, long total, List<DiagnosticItem> items,
                                 List<CategoryCount> categories, boolean storageTruncated) {
    }

    public record SourceFileItem(String path, String packageName, String status, boolean testSource, long sizeBytes,
                                 int lineCount, int typeCount, String sha256) {
    }

    public record SourceFileList(long analysisId, int total, List<SourceFileItem> files) {
    }

    public record StatusMap(Map<Long, AnalysisSummary> analyses) {
    }
}
