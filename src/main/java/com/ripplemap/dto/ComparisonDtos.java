package com.ripplemap.dto;

import com.ripplemap.analysis.model.EntityKind;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.RelationshipKind;

import java.util.List;

public final class ComparisonDtos {

    private ComparisonDtos() {
    }

    public record EntityChange(String id, String name, EntityKind kind, String filePath) {
    }

    public record EdgeChange(String source, String sourceName, String target, String targetName,
                             List<RelationshipKind> kinds) {
    }

    public record MetricDelta(String metric, long base, long target, long delta) {
    }

    /**
     * Differences between two snapshots of the same project, keyed by stable source identities.
     * Edge changes compare distinct dependency pairs of the selected layer; relationship counts compare
     * (source, target, kind) triples.
     */
    public record Comparison(
            long projectId,
            AnalysisDtos.AnalysisSummary base,
            AnalysisDtos.AnalysisSummary target,
            GraphLayer layer,
            int addedEntityCount,
            int removedEntityCount,
            int unchangedEntityCount,
            List<EntityChange> addedEntities,
            List<EntityChange> removedEntities,
            int addedEdgeCount,
            int removedEdgeCount,
            int unchangedEdgeCount,
            List<EdgeChange> addedEdges,
            List<EdgeChange> removedEdges,
            int addedRelationshipCount,
            int removedRelationshipCount,
            List<MetricDelta> metrics,
            List<MetricDelta> diagnostics,
            int listLimit,
            boolean truncated) {
    }
}
