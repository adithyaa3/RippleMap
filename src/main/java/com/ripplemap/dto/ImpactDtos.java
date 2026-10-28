package com.ripplemap.dto;

import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.entity.AnalysisStatus;
import com.ripplemap.impact.ImpactLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public final class ImpactDtos {

    private ImpactDtos() {
    }

    /**
     * @param analysisId      snapshot to analyze; null selects the latest completed snapshot
     * @param layer           TYPE (default) or METHOD
     * @param includeInferred null uses the configured default
     */
    public record ImpactRequest(Long analysisId,
                                @NotBlank(message = "Select a component") @Size(max = 4096) String nodeId,
                                GraphLayer layer,
                                Boolean includeInferred) {
    }

    public record Factor(String name, String formula, double value, double weight, double contribution) {
    }

    public record AffectedItem(GraphDtos.EntityRef entity, int distance, boolean direct, List<GraphDtos.EntityRef> path) {
    }

    public record ImpactResult(
            long analysisId,
            int analysisSequence,
            AnalysisStatus analysisStatus,
            GraphLayer layer,
            GraphDtos.EntityRef selected,
            boolean includeInferred,
            int eligibleCount,
            int directCount,
            int transitiveCount,
            int maxDistance,
            double directSignal,
            double reachSignal,
            double depthSignal,
            double score,
            BigDecimal scoreRounded,
            double percentage,
            BigDecimal percentageRounded,
            ImpactLevel level,
            BigDecimal lowThreshold,
            BigDecimal highThreshold,
            int affectedFileCount,
            List<String> affectedFiles,
            int affectedTypeCount,
            List<AffectedItem> affected,
            boolean affectedTruncated,
            List<Factor> factors,
            String formula,
            List<String> limitations,
            List<AnalysisDtos.DiagnosticItem> relatedUnresolved,
            String disclaimer) {
    }
}
