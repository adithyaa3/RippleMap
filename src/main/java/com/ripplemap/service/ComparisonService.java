package com.ripplemap.service;

import com.ripplemap.analysis.diagnostics.DiagnosticCategory;
import com.ripplemap.analysis.model.AggregatedRelationship;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.RelationshipKind;
import com.ripplemap.config.RippleMapProperties;
import com.ripplemap.dto.ComparisonDtos;
import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisCounts;
import com.ripplemap.exception.ApiException;
import com.ripplemap.repository.SnapshotStore;
import com.ripplemap.repository.StoredEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares two snapshots of one project by stable source identities (never by database ids).
 * Entities and dependency pairs are compared within the selected layer.
 */
@Service
public class ComparisonService {

    private final SnapshotService snapshots;
    private final SnapshotStore store;
    private final RippleMapProperties properties;

    public ComparisonService(SnapshotService snapshots, SnapshotStore store, RippleMapProperties properties) {
        this.snapshots = snapshots;
        this.store = store;
        this.properties = properties;
    }

    /** Lists at most {@code ripplemap.graph.max-comparison-entries} items per change list; counts are always exact. */
    public ComparisonDtos.Comparison compare(long projectId, Long baseId, Long targetId, GraphLayer layer) {
        return compare(projectId, baseId, targetId, layer, properties.getGraph().getMaxComparisonEntries());
    }

    /** Reports pass {@link Integer#MAX_VALUE} to list every change. */
    public ComparisonDtos.Comparison compare(long projectId, Long baseId, Long targetId, GraphLayer layer, int listLimit) {
        if (baseId == null || targetId == null) {
            throw ApiException.validation("base", "Select two snapshots to compare");
        }
        Analysis base = snapshots.resolve(projectId, baseId);
        Analysis target = snapshots.resolve(projectId, targetId);
        GraphLayer effective = layer == null ? GraphLayer.TYPE : layer;
        SnapshotData baseData = snapshots.load(base);
        SnapshotData targetData = snapshots.load(target);

        Map<String, StoredEntity> baseNodes = byId(baseData.nodes(effective));
        Map<String, StoredEntity> targetNodes = byId(targetData.nodes(effective));
        List<ComparisonDtos.EntityChange> added = new ArrayList<>();
        List<ComparisonDtos.EntityChange> removed = new ArrayList<>();
        int addedCount = 0;
        int removedCount = 0;
        int unchanged = 0;
        for (StoredEntity e : targetNodes.values()) {
            if (baseNodes.containsKey(e.id())) {
                unchanged++;
            } else {
                addedCount++;
                if (added.size() < listLimit) {
                    added.add(change(e));
                }
            }
        }
        for (StoredEntity e : baseNodes.values()) {
            if (!targetNodes.containsKey(e.id())) {
                removedCount++;
                if (removed.size() < listLimit) {
                    removed.add(change(e));
                }
            }
        }

        Map<String, Set<RelationshipKind>> basePairs = pairs(baseData.relationships(effective));
        Map<String, Set<RelationshipKind>> targetPairs = pairs(targetData.relationships(effective));
        List<ComparisonDtos.EdgeChange> addedEdges = new ArrayList<>();
        List<ComparisonDtos.EdgeChange> removedEdges = new ArrayList<>();
        int addedEdgeCount = 0;
        int removedEdgeCount = 0;
        int unchangedEdges = 0;
        for (Map.Entry<String, Set<RelationshipKind>> e : targetPairs.entrySet()) {
            if (basePairs.containsKey(e.getKey())) {
                unchangedEdges++;
            } else {
                addedEdgeCount++;
                if (addedEdges.size() < listLimit) {
                    addedEdges.add(edgeChange(e.getKey(), e.getValue(), targetData));
                }
            }
        }
        for (Map.Entry<String, Set<RelationshipKind>> e : basePairs.entrySet()) {
            if (!targetPairs.containsKey(e.getKey())) {
                removedEdgeCount++;
                if (removedEdges.size() < listLimit) {
                    removedEdges.add(edgeChange(e.getKey(), e.getValue(), baseData));
                }
            }
        }
        Set<String> baseTriples = triples(baseData.relationships(effective));
        Set<String> targetTriples = triples(targetData.relationships(effective));
        int addedRelationships = 0;
        for (String t : targetTriples) {
            if (!baseTriples.contains(t)) {
                addedRelationships++;
            }
        }
        int removedRelationships = 0;
        for (String t : baseTriples) {
            if (!targetTriples.contains(t)) {
                removedRelationships++;
            }
        }
        boolean truncated = addedCount > added.size() || removedCount > removed.size()
                || addedEdgeCount > addedEdges.size() || removedEdgeCount > removedEdges.size();
        return new ComparisonDtos.Comparison(projectId, DtoMapper.analysis(base), DtoMapper.analysis(target), effective,
                addedCount, removedCount, unchanged, added, removed, addedEdgeCount, removedEdgeCount, unchangedEdges,
                addedEdges, removedEdges, addedRelationships, removedRelationships,
                metricDeltas(base.getCounts(), target.getCounts()),
                diagnosticDeltas(store.loadDiagnosticCounts(base.getId()), store.loadDiagnosticCounts(target.getId())),
                listLimit, truncated);
    }

    private static Map<String, StoredEntity> byId(List<StoredEntity> entities) {
        Map<String, StoredEntity> map = new LinkedHashMap<>();
        for (StoredEntity e : entities) {
            map.put(e.id(), e);
        }
        return map;
    }

    private static ComparisonDtos.EntityChange change(StoredEntity e) {
        return new ComparisonDtos.EntityChange(e.id(), e.name(), e.kind(), e.entity().filePath());
    }

    private static Map<String, Set<RelationshipKind>> pairs(List<AggregatedRelationship> relationships) {
        Map<String, Set<RelationshipKind>> pairs = new TreeMap<>();
        for (AggregatedRelationship r : relationships) {
            pairs.computeIfAbsent(r.sourceId() + '\u0000' + r.targetId(), k -> EnumSet.noneOf(RelationshipKind.class))
                    .add(r.kind());
        }
        return pairs;
    }

    private static Set<String> triples(List<AggregatedRelationship> relationships) {
        Set<String> triples = new TreeSet<>();
        for (AggregatedRelationship r : relationships) {
            triples.add(r.sourceId() + '\u0000' + r.targetId() + '\u0000' + r.kind());
        }
        return triples;
    }

    private static ComparisonDtos.EdgeChange edgeChange(String key, Set<RelationshipKind> kinds, SnapshotData data) {
        int split = key.indexOf('\u0000');
        String source = key.substring(0, split);
        String target = key.substring(split + 1);
        StoredEntity s = data.entity(source);
        StoredEntity t = data.entity(target);
        return new ComparisonDtos.EdgeChange(source, s == null ? source : s.name(), target, t == null ? target : t.name(),
                List.copyOf(new TreeSet<>(kinds)));
    }

    private static List<ComparisonDtos.MetricDelta> metricDeltas(AnalysisCounts a, AnalysisCounts b) {
        List<ComparisonDtos.MetricDelta> deltas = new ArrayList<>();
        add(deltas, "Java files parsed", a.getFilesParsed(), b.getFilesParsed());
        add(deltas, "Java files failed", a.getFilesFailed(), b.getFilesFailed());
        add(deltas, "Types", a.getTypeCount(), b.getTypeCount());
        add(deltas, "Methods", a.getMethodCount(), b.getMethodCount());
        add(deltas, "Constructors", a.getConstructorCount(), b.getConstructorCount());
        add(deltas, "Fields", a.getFieldCount(), b.getFieldCount());
        add(deltas, "Type dependencies", a.getTypeEdgeCount(), b.getTypeEdgeCount());
        add(deltas, "Type relationships", a.getTypeRelationshipCount(), b.getTypeRelationshipCount());
        add(deltas, "Method dependencies", a.getMethodEdgeCount(), b.getMethodEdgeCount());
        add(deltas, "Unresolved references", a.getReferencesUnresolved(), b.getReferencesUnresolved());
        add(deltas, "Inferred references", a.getReferencesInferred(), b.getReferencesInferred());
        add(deltas, "Cyclic components (types)", a.getTypeCyclicComponents(), b.getTypeCyclicComponents());
        add(deltas, "Types in cycles", a.getTypeNodesInCycles(), b.getTypeNodesInCycles());
        add(deltas, "Warnings", a.getWarningCount(), b.getWarningCount());
        add(deltas, "Errors", a.getErrorCount(), b.getErrorCount());
        return deltas;
    }

    private static void add(List<ComparisonDtos.MetricDelta> deltas, String name, Number base, Number target) {
        long b = base == null ? 0 : base.longValue();
        long t = target == null ? 0 : target.longValue();
        deltas.add(new ComparisonDtos.MetricDelta(name, b, t, t - b));
    }

    private static List<ComparisonDtos.MetricDelta> diagnosticDeltas(Map<DiagnosticCategory, Integer> base,
                                                                     Map<DiagnosticCategory, Integer> target) {
        List<ComparisonDtos.MetricDelta> deltas = new ArrayList<>();
        for (DiagnosticCategory category : DiagnosticCategory.values()) {
            int b = base.getOrDefault(category, 0);
            int t = target.getOrDefault(category, 0);
            if (b != 0 || t != 0) {
                deltas.add(new ComparisonDtos.MetricDelta(category.name(), b, t, (long) t - b));
            }
        }
        return deltas;
    }
}
