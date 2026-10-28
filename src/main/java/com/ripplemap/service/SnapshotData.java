package com.ripplemap.service;

import com.ripplemap.analysis.model.AggregatedRelationship;
import com.ripplemap.analysis.model.EntityKind;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.RelationshipKind;
import com.ripplemap.graph.GraphFactory;
import com.ripplemap.graph.model.DependencyGraph;
import com.ripplemap.repository.StoredEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable in-memory view of one persisted snapshot. Graphs are built lazily per filter and cached, which is
 * safe because snapshots never change after they are written.
 */
public final class SnapshotData {

    private final long analysisId;
    private final long projectId;
    private final Map<String, StoredEntity> entities;
    private final Map<GraphLayer, List<StoredEntity>> nodes = new HashMap<>();
    private final Map<GraphLayer, List<AggregatedRelationship>> relationships = new HashMap<>();
    private final Map<String, List<AggregatedRelationship>> relationshipsByPair = new HashMap<>();
    private final Map<String, List<StoredEntity>> membersByType = new HashMap<>();
    private final Map<String, DependencyGraph> graphs = new ConcurrentHashMap<>();

    public SnapshotData(long analysisId, long projectId, List<StoredEntity> allEntities,
                        List<AggregatedRelationship> allRelationships) {
        this.analysisId = analysisId;
        this.projectId = projectId;
        Map<String, StoredEntity> byId = new LinkedHashMap<>();
        for (GraphLayer layer : GraphLayer.values()) {
            nodes.put(layer, new ArrayList<>());
            relationships.put(layer, new ArrayList<>());
        }
        for (StoredEntity entity : allEntities) {
            byId.put(entity.id(), entity);
            if (entity.layer() != null) {
                nodes.get(entity.layer()).add(entity);
            }
            if (entity.entity().declaringTypeId() != null && !entity.kind().isType()) {
                membersByType.computeIfAbsent(entity.entity().declaringTypeId(), k -> new ArrayList<>()).add(entity);
            }
        }
        this.entities = Collections.unmodifiableMap(byId);
        for (AggregatedRelationship r : allRelationships) {
            relationships.get(r.layer()).add(r);
            relationshipsByPair.computeIfAbsent(pairKey(r.layer(), r.sourceId(), r.targetId()), k -> new ArrayList<>(2))
                    .add(r);
        }
    }

    public long analysisId() {
        return analysisId;
    }

    public long projectId() {
        return projectId;
    }

    public StoredEntity entity(String id) {
        return entities.get(id);
    }

    public Map<String, StoredEntity> entities() {
        return entities;
    }

    /** Graph nodes of a layer in ascending id order. */
    public List<StoredEntity> nodes(GraphLayer layer) {
        return Collections.unmodifiableList(nodes.get(layer));
    }

    public List<AggregatedRelationship> relationships(GraphLayer layer) {
        return Collections.unmodifiableList(relationships.get(layer));
    }

    public List<AggregatedRelationship> relationshipsBetween(GraphLayer layer, String source, String target) {
        return relationshipsByPair.getOrDefault(pairKey(layer, source, target), List.of());
    }

    /** Methods, constructors and fields declared directly by a type. */
    public List<StoredEntity> members(String typeId) {
        return membersByType.getOrDefault(typeId, List.of());
    }

    public DependencyGraph graph(GraphLayer layer, boolean includeInferred) {
        return graph(layer, null, null, includeInferred);
    }

    /**
     * @param kinds     relationship kinds to include, null for all
     * @param nodeKinds node kinds to include, null for all
     */
    public DependencyGraph graph(GraphLayer layer, Set<RelationshipKind> kinds, Set<EntityKind> nodeKinds,
                                 boolean includeInferred) {
        Set<RelationshipKind> effectiveKinds = kinds == null || kinds.containsAll(RelationshipKind.forLayer(layer))
                ? null : EnumSet.copyOf(kinds.isEmpty() ? EnumSet.noneOf(RelationshipKind.class) : kinds);
        Set<EntityKind> effectiveNodeKinds = nodeKinds == null || nodeKinds.containsAll(kindsOf(layer))
                ? null : nodeKinds;
        String key = layer + "|" + (effectiveKinds == null ? "*" : new TreeSet<>(effectiveKinds)) + "|"
                + (effectiveNodeKinds == null ? "*" : new TreeSet<>(effectiveNodeKinds)) + "|" + includeInferred;
        return graphs.computeIfAbsent(key, k -> {
            List<String> ids = new ArrayList<>();
            for (StoredEntity entity : nodes.get(layer)) {
                if (effectiveNodeKinds == null || effectiveNodeKinds.contains(entity.kind())) {
                    ids.add(entity.id());
                }
            }
            return GraphFactory.build(ids, relationships.get(layer), layer, effectiveKinds, includeInferred);
        });
    }

    public static Set<EntityKind> kindsOf(GraphLayer layer) {
        EnumSet<EntityKind> kinds = EnumSet.noneOf(EntityKind.class);
        for (EntityKind kind : EntityKind.values()) {
            if (kind.layer() == layer) {
                kinds.add(kind);
            }
        }
        return kinds;
    }

    private static String pairKey(GraphLayer layer, String source, String target) {
        return layer.name() + '\u0000' + source + '\u0000' + target;
    }
}
