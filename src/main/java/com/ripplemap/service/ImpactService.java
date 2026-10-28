package com.ripplemap.service;

import com.ripplemap.analysis.diagnostics.DiagnosticCategory;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.config.RippleMapProperties;
import com.ripplemap.dto.GraphDtos;
import com.ripplemap.dto.ImpactDtos;
import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisCounts;
import com.ripplemap.entity.AnalysisStatus;
import com.ripplemap.exception.ApiException;
import com.ripplemap.graph.model.DependencyGraph;
import com.ripplemap.impact.ImpactCalculator;
import com.ripplemap.impact.ImpactComputation;
import com.ripplemap.impact.ImpactScore;
import com.ripplemap.impact.ImpactThresholds;
import com.ripplemap.repository.SnapshotStore;
import com.ripplemap.repository.StoredEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Change-impact analysis of one component on a selected snapshot. */
@Service
public class ImpactService {

    public static final String DISCLAIMER = "Potential impact does not necessarily mean the proposed change will "
            + "break the application. The score is an explainable structural heuristic, not the probability of a "
            + "defect. Unresolved dependencies may mean the reported affected set is incomplete.";
    public static final String FORMULA = "Score = 100 × (0.45 × D/(N−1) + 0.40 × T/(N−1) "
            + "+ 0.15 × min(H, 5)/5)";
    private static final int MAX_FILES = 500;
    private static final int MAX_RELATED_UNRESOLVED = 25;
    private static final Set<DiagnosticCategory> POSSIBLY_MISSING = EnumSet.of(DiagnosticCategory.UNRESOLVED_TYPE,
            DiagnosticCategory.AMBIGUOUS_TYPE, DiagnosticCategory.UNRESOLVED_CALL, DiagnosticCategory.AMBIGUOUS_CALL,
            DiagnosticCategory.UNRESOLVED_FIELD);

    private final SnapshotService snapshots;
    private final SnapshotStore store;
    private final SettingsService settings;
    private final RippleMapProperties properties;

    public ImpactService(SnapshotService snapshots, SnapshotStore store, SettingsService settings,
                         RippleMapProperties properties) {
        this.snapshots = snapshots;
        this.store = store;
        this.settings = settings;
        this.properties = properties;
    }

    /** Lists at most {@code ripplemap.graph.max-impact-entries} affected components and 500 files; counts are exact. */
    public ImpactDtos.ImpactResult analyze(long projectId, ImpactDtos.ImpactRequest request) {
        return analyze(projectId, request, properties.getGraph().getMaxImpactEntries(), MAX_FILES);
    }

    /** Reports pass {@link Integer#MAX_VALUE} to list every affected component and file. */
    public ImpactDtos.ImpactResult analyze(long projectId, ImpactDtos.ImpactRequest request, int maxEntries, int maxFiles) {
        Analysis analysis = snapshots.resolve(projectId, request.analysisId());
        SnapshotData data = snapshots.load(analysis);
        GraphLayer layer = request.layer() == null ? GraphLayer.TYPE : request.layer();
        StoredEntity selected = GraphQueryService.requireEntity(data, request.nodeId());
        if (selected.layer() != layer) {
            throw ApiException.badRequest("LAYER_MISMATCH", layer == GraphLayer.TYPE
                    ? "Select a class, interface, enum, record or annotation type for type-level impact"
                    : "Select a method or constructor for method-level impact");
        }
        boolean inferred = request.includeInferred() == null ? settings.includeInferredByDefault()
                : request.includeInferred();
        ImpactThresholds thresholds = settings.thresholds();
        DependencyGraph graph = data.graph(layer, inferred);
        ImpactComputation impact = ImpactCalculator.compute(graph, selected.id(), thresholds);

        List<ImpactDtos.AffectedItem> affected = new ArrayList<>();
        Set<String> files = new TreeSet<>();
        Set<String> types = new LinkedHashSet<>();
        for (ImpactComputation.AffectedNode node : impact.affected()) {
            StoredEntity entity = data.entity(node.id());
            if (entity.entity().filePath() != null) {
                files.add(entity.entity().filePath());
            }
            types.add(layer == GraphLayer.TYPE ? entity.id() : entity.entity().declaringTypeId());
            if (affected.size() < maxEntries) {
                List<GraphDtos.EntityRef> path = node.path().stream().map(id -> DtoMapper.ref(data.entity(id))).toList();
                affected.add(new ImpactDtos.AffectedItem(DtoMapper.ref(entity), node.distance(), node.direct(), path));
            }
        }
        int n = impact.eligibleCount();
        List<ImpactDtos.Factor> factors = List.of(
                new ImpactDtos.Factor("Direct dependents", "D / (N − 1) = " + impact.directCount() + " / "
                        + Math.max(0, n - 1), impact.directSignal(), ImpactScore.DIRECT_WEIGHT,
                        100 * ImpactScore.DIRECT_WEIGHT * impact.directSignal()),
                new ImpactDtos.Factor("Transitive reach", "T / (N − 1) = " + impact.transitiveCount() + " / "
                        + Math.max(0, n - 1), impact.reachSignal(), ImpactScore.REACH_WEIGHT,
                        100 * ImpactScore.REACH_WEIGHT * impact.reachSignal()),
                new ImpactDtos.Factor("Propagation depth", "min(H, 5) / 5 = min(" + impact.maxDistance() + ", 5) / 5",
                        impact.depthSignal(), ImpactScore.DEPTH_WEIGHT,
                        100 * ImpactScore.DEPTH_WEIGHT * impact.depthSignal()));

        List<String> limitations = limitations(analysis, layer, inferred);
        List<String> symbols = new ArrayList<>();
        symbols.add(selected.name());
        if (selected.kind().isType() && selected.name().contains(".")) {
            symbols.add(selected.name().substring(selected.name().lastIndexOf('.') + 1));
        }
        var related = store.loadDiagnosticsBySymbols(analysis.getId(), symbols, POSSIBLY_MISSING,
                MAX_RELATED_UNRESOLVED).stream().map(DtoMapper::diagnostic).toList();
        if (!related.isEmpty()) {
            limitations.add(0, related.size() + (related.size() == MAX_RELATED_UNRESOLVED ? "+" : "")
                    + " unresolved reference(s) use the name '" + selected.name()
                    + "'. They may be dependents that the analysis could not link, so the affected set may be incomplete.");
        }

        return new ImpactDtos.ImpactResult(analysis.getId(), analysis.getSequenceNumber(), analysis.getStatus(), layer,
                DtoMapper.ref(selected), inferred, n, impact.directCount(), impact.transitiveCount(),
                impact.maxDistance(), impact.directSignal(), impact.reachSignal(), impact.depthSignal(),
                impact.score(), ImpactScore.round(impact.score()), impact.percentage(),
                ImpactScore.round(impact.percentage()), impact.level(), thresholds.low(), thresholds.high(),
                files.size(), files.stream().limit(maxFiles).toList(), types.size(), affected,
                affected.size() < impact.affected().size(), factors, FORMULA, limitations, related, DISCLAIMER);
    }

    private static List<String> limitations(Analysis analysis, GraphLayer layer, boolean inferred) {
        List<String> notes = new ArrayList<>();
        AnalysisCounts counts = analysis.getCounts();
        if (analysis.getStatus() == AnalysisStatus.PARTIAL) {
            notes.add("This snapshot is partial: " + nullToZero(counts.getFilesFailed())
                    + " file(s) could not be analyzed, so dependents declared in them are not included.");
        }
        long unresolved = counts.getReferencesUnresolved() == null ? 0 : counts.getReferencesUnresolved();
        if (unresolved > 0) {
            notes.add(unresolved + " reference(s) in this snapshot could not be resolved; some dependents may be missing.");
        }
        if (!analysis.isIncludeTests()) {
            notes.add("Test sources were not analyzed, so tests that depend on this component are not listed.");
        }
        if (!inferred) {
            notes.add("Inferred relationships are excluded; only relationships confirmed by the symbol solver are followed.");
        }
        if (layer == GraphLayer.METHOD) {
            notes.add("Method-level impact follows statically resolved calls. Calls that reach this method only through "
                    + "dynamic dispatch (an override called through a supertype) are not included.");
        } else {
            notes.add("Type-level impact follows every dependency kind: inheritance, fields, signatures, object "
                    + "creation, calls, field access, annotations and other type references.");
        }
        return notes;
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    static BigDecimal round(double value) {
        return ImpactScore.round(value);
    }
}
