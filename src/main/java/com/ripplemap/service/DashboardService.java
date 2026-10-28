package com.ripplemap.service;

import com.ripplemap.analysis.model.EntityKind;
import com.ripplemap.dto.DashboardDtos;
import com.ripplemap.dto.ProjectDtos;
import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisCounts;
import com.ripplemap.entity.AnalysisStatus;
import com.ripplemap.impact.ImpactLevel;
import com.ripplemap.impact.ImpactScore;
import com.ripplemap.impact.ImpactThresholds;
import com.ripplemap.repository.AnalysisRepository;
import com.ripplemap.repository.SnapshotStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Dashboard figures, computed from each project's latest snapshot with results. */
@Service
public class DashboardService {

    private final ProjectService projectService;
    private final AnalysisRepository analyses;
    private final SnapshotStore store;
    private final SettingsService settings;

    public DashboardService(ProjectService projectService, AnalysisRepository analyses, SnapshotStore store,
                            SettingsService settings) {
        this.projectService = projectService;
        this.analyses = analyses;
        this.store = store;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public DashboardDtos.Dashboard dashboard() {
        List<ProjectDtos.ProjectSummary> projects = projectService.list();
        List<Analysis> latest = analyses.findLatestPerProjectWithStatus(SnapshotService.WITH_RESULTS);
        long files = 0;
        long failed = 0;
        long types = 0;
        long methods = 0;
        long dependencies = 0;
        long unresolved = 0;
        long cyclic = 0;
        long warnings = 0;
        long errors = 0;
        Map<Long, Analysis> byId = new HashMap<>();
        List<DashboardDtos.CycleSummary> cycles = new ArrayList<>();
        List<DashboardDtos.WarningSummary> warningSummaries = new ArrayList<>();
        for (Analysis a : latest) {
            byId.put(a.getId(), a);
            AnalysisCounts c = a.getCounts();
            files += value(c.getFilesParsed());
            failed += value(c.getFilesFailed());
            types += value(c.getTypeCount());
            methods += value(c.getMethodCount()) + value(c.getConstructorCount());
            dependencies += value(c.getTypeEdgeCount());
            unresolved += value(c.getReferencesUnresolved());
            cyclic += value(c.getTypeCyclicComponents());
            warnings += value(c.getWarningCount());
            errors += value(c.getErrorCount());
            if (value(c.getTypeCyclicComponents()) > 0) {
                cycles.add(new DashboardDtos.CycleSummary(a.getProject().getId(), a.getProject().getName(), a.getId(),
                        (int) value(c.getTypeCyclicComponents()), (int) value(c.getTypeNodesInCycles())));
            }
            if (value(c.getFilesFailed()) > 0 || value(c.getWarningCount()) > 0 || value(c.getErrorCount()) > 0) {
                warningSummaries.add(new DashboardDtos.WarningSummary(a.getProject().getId(), a.getProject().getName(),
                        a.getId(), (int) value(c.getFilesFailed()), (int) value(c.getWarningCount()),
                        (int) value(c.getErrorCount()), value(c.getReferencesUnresolved()),
                        a.getStatus() == AnalysisStatus.PARTIAL));
            }
        }
        cycles.sort((x, y) -> Integer.compare(y.cyclicComponents(), x.cyclicComponents()));
        warningSummaries.sort((x, y) -> Integer.compare(y.errors() + y.failedFiles(), x.errors() + x.failedFiles()));

        ImpactThresholds thresholds = settings.thresholds();
        List<DashboardDtos.Hotspot> hotspots = new ArrayList<>();
        for (Map<String, Object> row : store.topImpact(byId.keySet(), 10)) {
            Analysis a = byId.get(((Number) row.get("ANALYSIS_ID")).longValue());
            double score = ((Number) row.get("IMPACT_SCORE")).doubleValue();
            int direct = ((Number) row.get("DIRECT_DEPENDENTS")).intValue();
            int transitive = ((Number) row.get("TRANSITIVE_DEPENDENTS")).intValue();
            int depth = ((Number) row.get("MAX_DEPENDENT_DISTANCE")).intValue();
            ImpactLevel level = ImpactScore.classify((int) value(a.getCounts().getTypeCount()), direct, transitive,
                    depth, thresholds);
            hotspots.add(new DashboardDtos.Hotspot(a.getProject().getId(), a.getProject().getName(), a.getId(),
                    (String) row.get("STABLE_ID"), (String) row.get("NAME"), EntityKind.valueOf((String) row.get("KIND")),
                    score, level, transitive, direct));
        }
        List<DashboardDtos.RecentAnalysis> recent = analyses.findRecent(PageRequest.of(0, 10)).stream()
                .map(a -> new DashboardDtos.RecentAnalysis(a.getProject().getId(), a.getProject().getName(),
                        DtoMapper.analysis(a)))
                .toList();
        DashboardDtos.Totals totals = new DashboardDtos.Totals(projects.size(), latest.size(), files, failed, types,
                methods, dependencies, unresolved, cyclic, warnings, errors);
        return new DashboardDtos.Dashboard(totals, projects, recent, hotspots, cycles, warningSummaries);
    }

    private static long value(Number n) {
        return n == null ? 0 : n.longValue();
    }
}
