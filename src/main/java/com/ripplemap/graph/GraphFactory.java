package com.ripplemap.graph;

import com.ripplemap.analysis.model.AggregatedRelationship;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.RelationshipKind;
import com.ripplemap.analysis.model.Resolution;
import com.ripplemap.graph.model.DependencyGraph;

import java.util.Collection;
import java.util.Set;

/** Builds dependency graphs from aggregated relationships. */
public final class GraphFactory {

    private GraphFactory() {
    }

    /**
     * @param kinds           relationship kinds to include; {@code null} includes all kinds
     * @param includeInferred when false, relationships supported only by inferred references are left out
     */
    public static DependencyGraph build(Collection<String> nodeIds, Collection<AggregatedRelationship> relationships,
                                        GraphLayer layer, Set<RelationshipKind> kinds, boolean includeInferred) {
        DependencyGraph.Builder builder = DependencyGraph.builder().addNodes(nodeIds);
        for (AggregatedRelationship r : relationships) {
            if (accepts(r, layer, kinds, includeInferred)) {
                builder.addEdge(r.sourceId(), r.targetId());
            }
        }
        return builder.build();
    }

    public static boolean accepts(AggregatedRelationship r, GraphLayer layer, Set<RelationshipKind> kinds,
                                  boolean includeInferred) {
        return r.layer() == layer
                && (kinds == null || kinds.contains(r.kind()))
                && (includeInferred || r.resolution() == Resolution.RESOLVED);
    }
}
