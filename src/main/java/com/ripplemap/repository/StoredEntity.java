package com.ripplemap.repository;

import com.ripplemap.analysis.model.EntityKind;
import com.ripplemap.analysis.model.ExtractedEntity;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.impact.NodeMetrics;

/** A persisted declaration of a snapshot with its stored metrics (metrics are null for fields). */
public record StoredEntity(ExtractedEntity entity, NodeMetrics metrics) {

    public String id() {
        return entity.stableId();
    }

    public EntityKind kind() {
        return entity.kind();
    }

    public GraphLayer layer() {
        return entity.kind().layer();
    }

    public String name() {
        return entity.name();
    }
}
