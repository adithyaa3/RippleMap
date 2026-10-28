package com.ripplemap.service;

import com.ripplemap.analysis.diagnostics.DiagnosticCategory;
import com.ripplemap.analysis.diagnostics.Severity;
import com.ripplemap.dto.AnalysisDtos;
import com.ripplemap.entity.Analysis;
import com.ripplemap.exception.ApiException;
import com.ripplemap.repository.SnapshotStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DiagnosticsService {

    public static final int MAX_PAGE_SIZE = 200;

    private final SnapshotService snapshots;
    private final SnapshotStore store;

    public DiagnosticsService(SnapshotService snapshots, SnapshotStore store) {
        this.snapshots = snapshots;
        this.store = store;
    }

    public AnalysisDtos.DiagnosticPage page(long projectId, long analysisId, Severity severity,
                                            DiagnosticCategory category, int page, int size) {
        if (page < 0) {
            throw ApiException.validation("page", "The page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.validation("size", "The page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Analysis analysis = snapshots.resolve(projectId, analysisId);
        long total = store.countDiagnostics(analysis.getId(), severity, category, null);
        var items = store.loadDiagnostics(analysis.getId(), severity, category, null, page * size, size).stream()
                .map(DtoMapper::diagnostic).toList();
        return new AnalysisDtos.DiagnosticPage(analysis.getId(), page, size, total, items,
                categories(store.loadDiagnosticCounts(analysis.getId())),
                Boolean.TRUE.equals(analysis.getCounts().getDiagnosticsTruncated()));
    }

    public AnalysisDtos.SourceFileList files(long projectId, long analysisId) {
        Analysis analysis = snapshots.resolve(projectId, analysisId);
        var files = store.loadFiles(analysis.getId()).stream()
                .map(f -> new AnalysisDtos.SourceFileItem(f.relativePath(), f.packageName(), f.status().name(),
                        f.testSource(), f.sizeBytes(), f.lineCount(), f.typeCount(), f.sha256()))
                .toList();
        return new AnalysisDtos.SourceFileList(analysis.getId(), files.size(), files);
    }

    public static List<AnalysisDtos.CategoryCount> categories(Map<DiagnosticCategory, Integer> counts) {
        List<AnalysisDtos.CategoryCount> result = new ArrayList<>();
        for (DiagnosticCategory category : DiagnosticCategory.values()) {
            Integer count = counts.get(category);
            if (count != null && count > 0) {
                result.add(new AnalysisDtos.CategoryCount(category, category.severity(), count, category.description(),
                        category.indicatesPossiblyMissingDependency()));
            }
        }
        return result;
    }
}
