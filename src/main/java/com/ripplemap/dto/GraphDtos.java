package com.ripplemap.dto;

import com.ripplemap.analysis.model.EntityKind;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.RelationshipKind;
import com.ripplemap.analysis.model.Resolution;
import com.ripplemap.graph.traversal.Direction;

import java.util.List;

public final class GraphDtos {

    private GraphDtos() {
    }

    public record EntityRef(String id, String name, EntityKind kind, String qualifiedName, String filePath, int line) {
    }

    public record Node(String id, String label, String name, EntityKind kind, String packageName,
                       String declaringTypeId, String filePath, int line, boolean testSource, int inDegree,
                       int outDegree, boolean inCycle, Double impactScore, Integer distance) {
    }

    public record Edge(String id, String source, String target, List<RelationshipKind> kinds, Resolution resolution,
                       int referenceCount) {
    }

    /**
     * @param totalNodes   nodes of the whole layer
     * @param totalEdges   distinct dependency pairs of the whole layer (self-loops included)
     * @param matchedNodes nodes selected by scope and filters before the display limit
     * @param truncated    true when fewer nodes are returned than matched
     */
    public record GraphResponse(long analysisId, GraphLayer layer, String scope, String focus, int depth,
                                int totalNodes, int totalEdges, int matchedNodes, boolean truncated,
                                String truncationReason, int limit, boolean includeInferred,
                                List<RelationshipKind> relationshipKinds, List<EntityKind> nodeKinds,
                                List<Node> nodes, List<Edge> edges) {
    }

    public record Metrics(int directDependencies, int directDependents, Integer transitiveDependents,
                          Integer maxDependentDistance, Double impactScore, String impactLevel, boolean inCycle) {
    }

    public record EntityDetail(String id, EntityKind kind, String name, String qualifiedName, String packageName,
                               String declaringTypeId, String declaringTypeName, String signature, String filePath,
                               int line, int endLine, String visibility, String modifiers, boolean testSource,
                               Metrics metrics) {
    }

    public record Neighbor(EntityRef entity, List<RelationshipKind> kinds, Resolution resolution, int referenceCount) {
    }

    public record NodeDetail(long analysisId, GraphLayer layer, EntityDetail entity, List<Neighbor> dependencies,
                             List<Neighbor> dependents, int dependencyCount, int dependentCount,
                             List<EntityRef> members, List<AnalysisDtos.DiagnosticItem> diagnostics,
                             long diagnosticCount) {
    }

    public record Reference(String filePath, int line, String detail, Resolution resolution, String rule) {
    }

    public record RelationshipDetail(RelationshipKind kind, String kindLabel, Resolution resolution, int referenceCount,
                                     int resolvedCount, int inferredCount, List<Reference> samples) {
    }

    public record EdgeDetail(long analysisId, GraphLayer layer, EntityRef source, EntityRef target,
                             List<RelationshipDetail> relationships, int referenceCount) {
    }

    public record ReachableItem(EntityRef entity, int distance, List<String> path) {
    }

    public record ReachableList(long analysisId, GraphLayer layer, String nodeId, Direction direction,
                                boolean transitive, boolean includeInferred, int total, boolean truncated,
                                List<ReachableItem> items) {
    }

    public record SearchItem(EntityRef entity, Integer directDependents, Integer transitiveDependents,
                             Double impactScore) {
    }

    public record SearchResult(long analysisId, GraphLayer layer, String query, int total, boolean truncated,
                               List<SearchItem> items) {
    }

    public record PathResponse(long analysisId, GraphLayer layer, String from, String to, boolean found,
                               List<EntityRef> path) {
    }
}
