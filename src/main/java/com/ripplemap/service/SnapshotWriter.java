package com.ripplemap.service;

import com.ripplemap.analysis.diagnostics.DiagnosticCategory;
import com.ripplemap.analysis.diagnostics.Severity;
import com.ripplemap.analysis.model.AnalysisResult;
import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisCounts;
import com.ripplemap.entity.AnalysisStatus;
import com.ripplemap.repository.AnalysisRepository;
import com.ripplemap.repository.SnapshotStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Writes a finished snapshot. All rows and the final status are committed in one transaction, so a snapshot is
 * never visible as COMPLETED/PARTIAL with missing rows; if anything fails, nothing is committed.
 */
@Service
public class SnapshotWriter {

    private final AnalysisRepository analyses;
    private final SnapshotStore store;

    public SnapshotWriter(AnalysisRepository analyses, SnapshotStore store) {
        this.analyses = analyses;
        this.store = store;
    }

    @Transactional
    public void persist(long analysisId, AnalysisResult result, SnapshotMetrics metrics, Instant now) {
        Analysis analysis = analyses.findById(analysisId)
                .orElseThrow(() -> new IllegalStateException("Analysis " + analysisId + " disappeared"));
        if (analysis.getStatus() != AnalysisStatus.RUNNING) {
            throw new IllegalStateException("Analysis " + analysisId + " is no longer running");
        }
        store.insertFiles(analysisId, result.files());
        store.insertEntities(analysisId, result.entities(), metrics.nodeMetrics());
        store.insertRelationships(analysisId, result.relationships());
        store.insertDiagnostics(analysisId, result.diagnostics(), result.diagnosticCounts());
        AnalysisCounts counts = AnalysisCounts.builder()
                .files(result.fileCounts())
                .declarations(metrics.typeCount(), metrics.methodCount(), metrics.constructorCount(), metrics.fieldCount())
                .typeLayer(metrics.typeDependencyPairs(), metrics.typeRelationships())
                .methodLayer(metrics.methodDependencyPairs(), metrics.methodRelationships())
                .references(result.references())
                .diagnostics(count(result.diagnosticCounts(), Severity.INFO), count(result.diagnosticCounts(), Severity.WARNING),
                        count(result.diagnosticCounts(), Severity.ERROR), result.diagnostics().size(),
                        result.diagnosticsTruncated())
                .structure(metrics.typeCyclicComponents(), metrics.typeNodesInCycles(),
                        metrics.methodCyclicComponents(), metrics.isolatedTypes(), metrics.nodeMetricsComputed())
                .build();
        analysis.markFinished(result.partial(), String.join("; ", result.partialReasons()), result.sourceFingerprint(),
                counts, now);
    }

    private static int count(Map<DiagnosticCategory, Integer> counts, Severity severity) {
        int total = 0;
        for (Map.Entry<DiagnosticCategory, Integer> e : counts.entrySet()) {
            if (e.getKey().severity() == severity) {
                total += e.getValue();
            }
        }
        return total;
    }
}
