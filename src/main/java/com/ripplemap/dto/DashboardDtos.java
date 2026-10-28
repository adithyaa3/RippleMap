package com.ripplemap.dto;

import com.ripplemap.analysis.model.EntityKind;
import com.ripplemap.impact.ImpactLevel;

import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    /** Totals over each project's latest snapshot with results. */
    public record Totals(int projects, int analyzedProjects, long analyzedFiles, long failedFiles, long types,
                         long methods, long dependencies, long unresolvedReferences, long cyclicComponents,
                         long warnings, long errors) {
    }

    public record RecentAnalysis(long projectId, String projectName, AnalysisDtos.AnalysisSummary analysis) {
    }

    public record Hotspot(long projectId, String projectName, long analysisId, String entityId, String name,
                          EntityKind kind, double impactScore, ImpactLevel level, int transitiveDependents,
                          int directDependents) {
    }

    public record CycleSummary(long projectId, String projectName, long analysisId, int cyclicComponents,
                               int nodesInCycles) {
    }

    public record WarningSummary(long projectId, String projectName, long analysisId, int failedFiles,
                                 int warnings, int errors, long unresolvedReferences, boolean partial) {
    }

    public record Dashboard(Totals totals, List<ProjectDtos.ProjectSummary> projects, List<RecentAnalysis> recent,
                            List<Hotspot> hotspots, List<CycleSummary> cycles, List<WarningSummary> warnings) {
    }
}
