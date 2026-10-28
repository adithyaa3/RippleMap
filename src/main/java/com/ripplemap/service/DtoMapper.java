package com.ripplemap.service;

import com.ripplemap.analysis.diagnostics.Diagnostic;
import com.ripplemap.dto.AnalysisDtos;
import com.ripplemap.dto.GraphDtos;
import com.ripplemap.dto.ProjectDtos;
import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisCounts;
import com.ripplemap.entity.Project;
import com.ripplemap.entity.SourceImport;
import com.ripplemap.repository.StoredEntity;

import java.util.Arrays;
import java.util.List;

/** Converts persistence objects to API DTOs. */
public final class DtoMapper {

    private DtoMapper() {
    }

    public static AnalysisDtos.AnalysisSummary analysis(Analysis a) {
        SourceImport source = a.getSourceImport();
        AnalysisCounts c = a.getCounts();
        AnalysisDtos.Counts counts = new AnalysisDtos.Counts(
                c.getFilesDiscovered(), c.getFilesInScope(), c.getFilesExcludedTests(), c.getFilesExcludedGenerated(),
                c.getFilesParsed(), c.getFilesFailed(), c.getFilesSkippedLinks(), c.getTypeCount(), c.getMethodCount(),
                c.getConstructorCount(), c.getFieldCount(), c.getTypeEdgeCount(), c.getTypeRelationshipCount(),
                c.getMethodEdgeCount(), c.getMethodRelationshipCount(), c.getReferencesResolved(),
                c.getReferencesInferred(), c.getReferencesUnresolved(), c.getReferencesLikelyExternal(),
                c.getReferencesExternal(), c.getReferencesInternal(), c.getSolverFailures(), c.getInfoCount(),
                c.getWarningCount(), c.getErrorCount(), c.getDiagnosticsStored(), c.getDiagnosticsTruncated(),
                c.getTypeCyclicComponents(), c.getTypeNodesInCycles(), c.getMethodCyclicComponents(),
                c.getIsolatedTypes(), c.getNodeMetricsComputed());
        List<String> partialReasons = a.getPartialReasons() == null || a.getPartialReasons().isBlank()
                ? List.of() : Arrays.asList(a.getPartialReasons().split("; "));
        return new AnalysisDtos.AnalysisSummary(a.getId(), a.getProject().getId(), a.getSequenceNumber(),
                a.getStatus(), a.getTrigger(), a.isIncludeTests(), source == null ? null : source.getId(),
                source == null ? null : source.getVersionNumber(), a.getCreatedAt(), a.getStartedAt(),
                a.getFinishedAt(), a.getDurationMs(), a.getStage(), a.getErrorMessage(), partialReasons,
                a.getStatus().hasResults(), a.getLanguageLevel(), a.getSourceFingerprint(), counts);
    }

    public static ProjectDtos.Version version(SourceImport i) {
        return new ProjectDtos.Version(i.getId(), i.getVersionNumber(), i.getOriginalFilename(), i.getLabel(),
                i.getArchiveBytes(), i.getEntryCount(), i.getJavaFileCount(), i.getExtractedBytes(), i.getImportedAt());
    }

    public static ProjectDtos.ProjectSummary project(Project p, long versionCount, Analysis latest, Long defaultAnalysisId) {
        return new ProjectDtos.ProjectSummary(p.getId(), p.getName(), p.getDescription(), p.getSourceType(),
                p.isIncludeTests(), p.getCreatedAt(), p.getUpdatedAt(), versionCount,
                latest == null ? null : analysis(latest), defaultAnalysisId, p.getLocalPath());
    }

    public static AnalysisDtos.DiagnosticItem diagnostic(Diagnostic d) {
        return new AnalysisDtos.DiagnosticItem(d.severity(), d.category(), d.filePath(), d.line(), d.entityId(),
                d.symbol(), d.message());
    }

    public static GraphDtos.EntityRef ref(StoredEntity e) {
        return new GraphDtos.EntityRef(e.id(), e.name(), e.kind(), e.entity().qualifiedName(), e.entity().filePath(),
                e.entity().line());
    }
}
