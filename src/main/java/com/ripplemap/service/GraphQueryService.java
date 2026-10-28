package com.ripplemap.service;

import com.ripplemap.analysis.model.AggregatedRelationship;
import com.ripplemap.analysis.model.EntityKind;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.RelationshipKind;
import com.ripplemap.analysis.model.Resolution;
import com.ripplemap.config.RippleMapProperties;
import com.ripplemap.dto.GraphDtos;
import com.ripplemap.entity.Analysis;
import com.ripplemap.exception.ApiException;
import com.ripplemap.graph.GraphFactory;
import com.ripplemap.graph.algorithms.CycleDetector;
import com.ripplemap.graph.algorithms.CyclicComponent;
import com.ripplemap.graph.model.DependencyGraph;
import com.ripplemap.graph.traversal.Direction;
import com.ripplemap.graph.traversal.GraphTraversal;
import com.ripplemap.graph.traversal.Reachability;
import com.ripplemap.impact.ImpactScore;
import com.ripplemap.impact.ImpactThresholds;
import com.ripplemap.impact.NodeMetrics;
import com.ripplemap.repository.SnapshotStore;
import com.ripplemap.repository.StoredEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Read-only graph queries over a selected snapshot. All responses are bounded. */
@Service
public class GraphQueryService {

    public enum Scope { ALL, NEIGHBORHOOD, DEPENDENTS, DEPENDENCIES, CYCLE }

    public static final int MAX_DEPTH = 6;
    private static final int MAX_NEIGHBORS = 500;
    private static final int MAX_MEMBERS = 500;

    private final SnapshotService snapshots;
    private final SnapshotStore store;
    private final SettingsService settings;
    private final RippleMapProperties properties;

    public GraphQueryService(SnapshotService snapshots, SnapshotStore store, SettingsService settings,
                             RippleMapProperties properties) {
        this.snapshots = snapshots;
        this.store = store;
        this.settings = settings;
        this.properties = properties;
    }

    public record GraphQuery(Long analysisId, GraphLayer layer, Scope scope, String focus, Integer depth,
                             Integer componentIndex, Integer limit, Set<EntityKind> nodeKinds,
                             Set<RelationshipKind> relationshipKinds, Boolean includeInferred) {
    }

    public GraphDtos.GraphResponse graph(long projectId, GraphQuery q) {
        Analysis analysis = snapshots.resolve(projectId, q.analysisId());
        SnapshotData data = snapshots.load(analysis);
        GraphLayer layer = q.layer() == null ? GraphLayer.TYPE : q.layer();
        Scope scope = q.scope() == null ? (q.focus() == null ? Scope.ALL : Scope.NEIGHBORHOOD) : q.scope();
        boolean inferred = q.includeInferred() == null ? true : q.includeInferred();
        Set<RelationshipKind> kinds = normalizeKinds(q.relationshipKinds(), layer);
        Set<EntityKind> nodeKinds = normalizeNodeKinds(q.nodeKinds(), layer);
        DependencyGraph graph = data.graph(layer, kinds, nodeKinds, inferred);
        int hardMax = properties.getGraph().getMaxResponseNodes();
        int limit = q.limit() == null ? settings.get().explorerNodeLimit() : q.limit();
        if (limit < 1) {
            throw ApiException.validation("limit", "The node limit must be at least 1");
        }
        limit = Math.min(limit, hardMax);
        int depth = q.depth() == null ? 1 : q.depth();
        if (depth < 1 || depth > MAX_DEPTH) {
            throw ApiException.validation("depth", "Depth must be between 1 and " + MAX_DEPTH);
        }

        LinkedHashMap<String, Integer> selected = new LinkedHashMap<>();
        int matched;
        String reason = null;
        switch (scope) {
            case ALL -> {
                matched = graph.size();
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < graph.size(); i++) {
                    order.add(i);
                }
                if (matched > limit) {
                    order.sort(Comparator.<Integer>comparingInt(i -> -(graph.inDegree(i) + graph.outDegree(i)))
                            .thenComparing(graph::idAt));
                    reason = "Showing the " + limit + " most connected of " + matched
                            + " nodes. Search for a component to explore its neighborhood.";
                }
                for (int i = 0; i < Math.min(limit, order.size()); i++) {
                    selected.put(graph.idAt(order.get(i)), null);
                }
            }
            case NEIGHBORHOOD, DEPENDENTS, DEPENDENCIES -> {
                String focus = requireFocus(q.focus(), data, graph, layer);
                Map<String, Integer> reached = scope == Scope.NEIGHBORHOOD
                        ? neighborhood(graph, focus, depth)
                        : withStart(focus, GraphTraversal.reachable(graph, focus,
                        scope == Scope.DEPENDENTS ? Direction.DEPENDENTS : Direction.DEPENDENCIES,
                        q.depth() == null ? GraphTraversal.UNLIMITED : depth));
                matched = reached.size();
                for (Map.Entry<String, Integer> e : reached.entrySet()) {
                    if (selected.size() >= limit) {
                        reason = "Showing the " + limit + " nearest of " + matched + " nodes.";
                        break;
                    }
                    selected.put(e.getKey(), e.getValue());
                }
            }
            case CYCLE -> {
                List<CyclicComponent> cycles = CycleDetector.find(graph);
                int index = q.componentIndex() == null ? 0 : q.componentIndex();
                if (index < 0 || index >= cycles.size()) {
                    throw ApiException.notFound("CYCLE_NOT_FOUND", "Cyclic component " + index + " does not exist");
                }
                List<String> members = cycles.get(index).members();
                matched = members.size();
                for (String member : members) {
                    if (selected.size() >= limit) {
                        reason = "Showing " + limit + " of " + matched + " members of this cyclic component.";
                        break;
                    }
                    selected.put(member, null);
                }
            }
            default -> throw new IllegalStateException();
        }

        List<GraphDtos.Node> nodes = new ArrayList<>(selected.size());
        for (Map.Entry<String, Integer> e : selected.entrySet()) {
            nodes.add(node(data.entity(e.getKey()), graph, e.getValue()));
        }
        List<GraphDtos.Edge> edges = edgesAmong(data, graph, selected.keySet(), layer, kinds, inferred);
        return new GraphDtos.GraphResponse(analysis.getId(), layer, scope.name(), q.focus(), depth, graph.size(),
                graph.edgeCount(), matched, reason != null, reason, limit, inferred,
                List.copyOf(kinds == null ? RelationshipKind.forLayer(layer) : kinds),
                List.copyOf(nodeKinds == null ? SnapshotData.kindsOf(layer) : nodeKinds), nodes, edges);
    }

    public GraphDtos.NodeDetail nodeDetail(long projectId, Long analysisId, String nodeId) {
        Analysis analysis = snapshots.resolve(projectId, analysisId);
        SnapshotData data = snapshots.load(analysis);
        StoredEntity entity = requireEntity(data, nodeId);
        GraphLayer layer = entity.layer();
        List<GraphDtos.Neighbor> dependencies = new ArrayList<>();
        List<GraphDtos.Neighbor> dependents = new ArrayList<>();
        int dependencyCount = 0;
        int dependentCount = 0;
        List<GraphDtos.EntityRef> members = new ArrayList<>();
        if (layer != null) {
            DependencyGraph graph = data.graph(layer, true);
            for (String target : graph.directDependencies(nodeId)) {
                dependencyCount++;
                if (dependencies.size() < MAX_NEIGHBORS) {
                    dependencies.add(neighbor(data, layer, nodeId, target, data.entity(target)));
                }
            }
            for (String source : graph.directDependents(nodeId)) {
                dependentCount++;
                if (dependents.size() < MAX_NEIGHBORS) {
                    dependents.add(neighbor(data, layer, source, nodeId, data.entity(source)));
                }
            }
        }
        List<String> diagnosticOwners = new ArrayList<>();
        diagnosticOwners.add(nodeId);
        for (StoredEntity member : data.members(nodeId)) {
            if (members.size() < MAX_MEMBERS) {
                members.add(DtoMapper.ref(member));
            }
            if (member.kind().isCallable()) {
                diagnosticOwners.add(member.id());
            }
        }
        members.sort(Comparator.comparing(GraphDtos.EntityRef::line).thenComparing(GraphDtos.EntityRef::id));
        var diagnostics = store.loadDiagnosticsForEntities(analysis.getId(), diagnosticOwners, 50).stream()
                .map(DtoMapper::diagnostic).toList();
        long diagnosticCount = store.countDiagnosticsForEntities(analysis.getId(), diagnosticOwners);
        return new GraphDtos.NodeDetail(analysis.getId(), layer, detail(data, entity), dependencies, dependents,
                dependencyCount, dependentCount, members, diagnostics, diagnosticCount);
    }

    public GraphDtos.EdgeDetail edgeDetail(long projectId, Long analysisId, GraphLayer layer, String source, String target) {
        Analysis analysis = snapshots.resolve(projectId, analysisId);
        SnapshotData data = snapshots.load(analysis);
        StoredEntity from = requireEntity(data, source);
        StoredEntity to = requireEntity(data, target);
        GraphLayer effective = layer == null ? from.layer() : layer;
        List<AggregatedRelationship> relationships = data.relationshipsBetween(effective, source, target);
        if (relationships.isEmpty()) {
            throw ApiException.notFound("EDGE_NOT_FOUND", "There is no dependency from the source to the target");
        }
        List<GraphDtos.RelationshipDetail> details = new ArrayList<>();
        int references = 0;
        for (AggregatedRelationship r : relationships) {
            references += r.referenceCount();
            details.add(new GraphDtos.RelationshipDetail(r.kind(), r.kind().label(), r.resolution(), r.referenceCount(),
                    r.resolvedCount(), r.inferredCount(), r.samples().stream()
                    .map(s -> new GraphDtos.Reference(s.filePath(), s.line(), s.detail(), s.resolution(), s.rule()))
                    .toList()));
        }
        return new GraphDtos.EdgeDetail(analysis.getId(), effective, DtoMapper.ref(from), DtoMapper.ref(to), details,
                references);
    }

    public GraphDtos.ReachableList reachable(long projectId, Long analysisId, String nodeId, Direction direction,
                                             boolean transitive, Boolean includeInferred) {
        Analysis analysis = snapshots.resolve(projectId, analysisId);
        SnapshotData data = snapshots.load(analysis);
        StoredEntity entity = requireEntity(data, nodeId);
        if (entity.layer() == null) {
            throw ApiException.badRequest("NOT_A_GRAPH_NODE", "Fields are not nodes of the dependency graph");
        }
        boolean inferred = includeInferred == null || includeInferred;
        DependencyGraph graph = data.graph(entity.layer(), inferred);
        Reachability reach = GraphTraversal.reachable(graph, nodeId, direction,
                transitive ? GraphTraversal.UNLIMITED : 1);
        int max = properties.getGraph().getMaxImpactEntries();
        List<GraphDtos.ReachableItem> items = new ArrayList<>();
        for (Map.Entry<String, Integer> e : reach.distances().entrySet()) {
            if (items.size() >= max) {
                break;
            }
            items.add(new GraphDtos.ReachableItem(DtoMapper.ref(data.entity(e.getKey())), e.getValue(),
                    reach.explanationPath(e.getKey())));
        }
        return new GraphDtos.ReachableList(analysis.getId(), entity.layer(), nodeId, direction, transitive, inferred,
                reach.distances().size(), reach.distances().size() > items.size(), items);
    }

    public GraphDtos.SearchResult search(long projectId, Long analysisId, GraphLayer layer, String query,
                                         EntityKind kind, int limit) {
        Analysis analysis = snapshots.resolve(projectId, analysisId);
        SnapshotData data = snapshots.load(analysis);
        GraphLayer effective = layer == null ? GraphLayer.TYPE : layer;
        int bounded = Math.max(1, Math.min(limit, 200));
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        record Hit(StoredEntity entity, int rank) {
        }
        List<Hit> hits = new ArrayList<>();
        for (StoredEntity e : data.nodes(effective)) {
            if (kind != null && e.kind() != kind) {
                continue;
            }
            int rank = needle.isEmpty() ? 3 : rank(e, needle);
            if (rank >= 0) {
                hits.add(new Hit(e, rank));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::rank)
                .thenComparing(h -> -score(h.entity()))
                .thenComparing(h -> h.entity().id()));
        List<GraphDtos.SearchItem> items = new ArrayList<>();
        for (Hit hit : hits.subList(0, Math.min(bounded, hits.size()))) {
            NodeMetrics m = hit.entity().metrics();
            items.add(new GraphDtos.SearchItem(DtoMapper.ref(hit.entity()), m == null ? null : m.directDependents(),
                    m == null ? null : m.transitiveDependents(), m == null ? null : m.impactScore()));
        }
        return new GraphDtos.SearchResult(analysis.getId(), effective, query, hits.size(), hits.size() > items.size(),
                items);
    }

    public GraphDtos.PathResponse path(long projectId, Long analysisId, String from, String to, Boolean includeInferred) {
        Analysis analysis = snapshots.resolve(projectId, analysisId);
        SnapshotData data = snapshots.load(analysis);
        StoredEntity source = requireEntity(data, from);
        StoredEntity target = requireEntity(data, to);
        if (source.layer() == null || source.layer() != target.layer()) {
            throw ApiException.badRequest("LAYER_MISMATCH", "Both components must be nodes of the same graph layer");
        }
        DependencyGraph graph = data.graph(source.layer(), includeInferred == null || includeInferred);
        Optional<List<String>> path = GraphTraversal.shortestPath(graph, from, to, Direction.DEPENDENCIES);
        return new GraphDtos.PathResponse(analysis.getId(), source.layer(), from, to, path.isPresent(),
                path.map(p -> p.stream().map(id -> DtoMapper.ref(data.entity(id))).toList()).orElse(List.of()));
    }

    // ---------------------------------------------------------------- helpers

    GraphDtos.EntityDetail detail(SnapshotData data, StoredEntity entity) {
        NodeMetrics m = entity.metrics();
        GraphDtos.Metrics metrics = null;
        if (m != null) {
            String level = null;
            if (m.transitiveDependents() != null && m.maxDependentDistance() != null && entity.layer() != null) {
                ImpactThresholds thresholds = settings.thresholds();
                level = ImpactScore.classify(data.nodes(entity.layer()).size(), m.directDependents(),
                        m.transitiveDependents(), m.maxDependentDistance(), thresholds).name();
            }
            metrics = new GraphDtos.Metrics(m.directDependencies(), m.directDependents(), m.transitiveDependents(),
                    m.maxDependentDistance(), m.impactScore(), level, m.inCycle());
        }
        var e = entity.entity();
        StoredEntity declaring = e.declaringTypeId() == null ? null : data.entity(e.declaringTypeId());
        return new GraphDtos.EntityDetail(e.stableId(), e.kind(), e.name(), e.qualifiedName(), e.packageName(),
                e.declaringTypeId(), declaring == null ? null : declaring.name(), e.signature(), e.filePath(), e.line(),
                e.endLine(), e.visibility(), e.modifiers(), e.testSource(), metrics);
    }

    private static GraphDtos.Node node(StoredEntity e, DependencyGraph graph, Integer distance) {
        int index = graph.indexOf(e.id());
        NodeMetrics m = e.metrics();
        String label = e.kind().isCallable() ? shortMethodLabel(e) : e.name();
        return new GraphDtos.Node(e.id(), label, e.name(), e.kind(), e.entity().packageName(),
                e.entity().declaringTypeId(), e.entity().filePath(), e.entity().line(), e.entity().testSource(),
                graph.distinctInNeighbours(index), graph.distinctOutNeighbours(index), m != null && m.inCycle(),
                m == null ? null : m.impactScore(), distance);
    }

    private static String shortMethodLabel(StoredEntity e) {
        String declaring = e.entity().declaringTypeId();
        String owner = declaring == null ? "" : declaring.substring(Math.max(declaring.lastIndexOf('.'),
                declaring.lastIndexOf('$')) + 1) + ".";
        String signature = e.entity().signature();
        int colon = signature == null ? -1 : signature.indexOf(" : ");
        return owner + (signature == null ? e.name() : colon > 0 ? signature.substring(0, colon) : signature);
    }

    private static List<GraphDtos.Edge> edgesAmong(SnapshotData data, DependencyGraph graph, Set<String> selected,
                                                   GraphLayer layer, Set<RelationshipKind> kinds, boolean inferred) {
        List<GraphDtos.Edge> edges = new ArrayList<>();
        for (String source : selected) {
            int s = graph.indexOf(source);
            graph.forEachOutgoing(s, t -> {
                String target = graph.idAt(t);
                if (!selected.contains(target)) {
                    return;
                }
                TreeSet<RelationshipKind> edgeKinds = new TreeSet<>();
                boolean resolved = false;
                int references = 0;
                for (AggregatedRelationship r : data.relationshipsBetween(layer, source, target)) {
                    if (GraphFactory.accepts(r, layer, kinds, inferred)) {
                        edgeKinds.add(r.kind());
                        resolved |= r.resolution() == Resolution.RESOLVED;
                        references += r.referenceCount();
                    }
                }
                edges.add(new GraphDtos.Edge("e" + edges.size(), source, target, List.copyOf(edgeKinds),
                        resolved ? Resolution.RESOLVED : Resolution.INFERRED, references));
            });
        }
        return edges;
    }

    private static GraphDtos.Neighbor neighbor(SnapshotData data, GraphLayer layer, String source, String target,
                                               StoredEntity other) {
        TreeSet<RelationshipKind> kinds = new TreeSet<>();
        boolean resolved = false;
        int references = 0;
        for (AggregatedRelationship r : data.relationshipsBetween(layer, source, target)) {
            kinds.add(r.kind());
            resolved |= r.resolution() == Resolution.RESOLVED;
            references += r.referenceCount();
        }
        return new GraphDtos.Neighbor(DtoMapper.ref(other), List.copyOf(kinds),
                resolved ? Resolution.RESOLVED : Resolution.INFERRED, references);
    }

    /** Breadth-first neighborhood following edges in both directions; focus first, then by distance. */
    private static Map<String, Integer> neighborhood(DependencyGraph graph, String focus, int depth) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        int start = graph.indexOf(focus);
        int[] distance = new int[graph.size()];
        java.util.Arrays.fill(distance, -1);
        distance[start] = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        result.put(focus, 0);
        while (!queue.isEmpty()) {
            int u = queue.poll();
            if (distance[u] >= depth) {
                continue;
            }
            java.util.function.IntConsumer visit = v -> {
                if (distance[v] < 0) {
                    distance[v] = distance[u] + 1;
                    result.put(graph.idAt(v), distance[v]);
                    queue.add(v);
                }
            };
            graph.forEachOutgoing(u, visit);
            graph.forEachIncoming(u, visit);
        }
        return result;
    }

    private static Map<String, Integer> withStart(String focus, Reachability reach) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        result.put(focus, 0);
        result.putAll(reach.distances());
        return result;
    }

    private static String requireFocus(String focus, SnapshotData data, DependencyGraph graph, GraphLayer layer) {
        if (focus == null || focus.isBlank()) {
            throw ApiException.validation("focus", "Select a component to focus on");
        }
        StoredEntity entity = data.entity(focus);
        if (entity == null || entity.layer() != layer) {
            throw ApiException.notFound("NODE_NOT_FOUND", "The component does not exist in this snapshot and layer");
        }
        if (!graph.contains(focus)) {
            throw ApiException.badRequest("NODE_FILTERED_OUT", "The component is hidden by the current node-type filter");
        }
        return focus;
    }

    static StoredEntity requireEntity(SnapshotData data, String id) {
        if (id == null || id.isBlank()) {
            throw ApiException.validation("nodeId", "Select a component");
        }
        StoredEntity entity = data.entity(id);
        if (entity == null) {
            throw ApiException.notFound("NODE_NOT_FOUND", "The component does not exist in this snapshot");
        }
        return entity;
    }

    private static Set<RelationshipKind> normalizeKinds(Set<RelationshipKind> kinds, GraphLayer layer) {
        if (kinds == null || kinds.isEmpty()) {
            return null;
        }
        EnumSet<RelationshipKind> applicable = EnumSet.noneOf(RelationshipKind.class);
        for (RelationshipKind kind : kinds) {
            if (kind.appliesTo(layer)) {
                applicable.add(kind);
            }
        }
        return applicable;
    }

    private static Set<EntityKind> normalizeNodeKinds(Set<EntityKind> kinds, GraphLayer layer) {
        if (kinds == null || kinds.isEmpty()) {
            return null;
        }
        EnumSet<EntityKind> applicable = EnumSet.noneOf(EntityKind.class);
        for (EntityKind kind : kinds) {
            if (kind.layer() == layer) {
                applicable.add(kind);
            }
        }
        return applicable;
    }

    private static int rank(StoredEntity e, String needle) {
        String name = e.name().toLowerCase(Locale.ROOT);
        String qualified = e.entity().qualifiedName() == null ? "" : e.entity().qualifiedName().toLowerCase(Locale.ROOT);
        String signature = e.entity().signature() == null ? "" : e.entity().signature().toLowerCase(Locale.ROOT);
        if (name.equals(needle) || qualified.equals(needle)) {
            return 0;
        }
        if (name.startsWith(needle) || signature.startsWith(needle)) {
            return 1;
        }
        if (name.contains(needle) || qualified.contains(needle) || signature.contains(needle)
                || e.id().toLowerCase(Locale.ROOT).contains(needle)) {
            return 2;
        }
        return -1;
    }

    private static double score(StoredEntity e) {
        NodeMetrics m = e.metrics();
        if (m == null) {
            return 0;
        }
        if (m.impactScore() != null) {
            return m.impactScore();
        }
        return m.directDependents();
    }
}
