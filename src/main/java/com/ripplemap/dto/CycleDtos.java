package com.ripplemap.dto;

import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.RelationshipKind;

import java.util.List;

public final class CycleDtos {

    private CycleDtos() {
    }

    public record CycleStep(GraphDtos.EntityRef from, GraphDtos.EntityRef to, List<RelationshipKind> kinds,
                            GraphDtos.Reference firstReference) {
    }

    public record CycleComponent(int index, int size, boolean selfLoop, List<GraphDtos.EntityRef> members,
                                 boolean membersTruncated, List<GraphDtos.EntityRef> cycle, List<CycleStep> steps) {
    }

    public record CycleList(long analysisId, GraphLayer layer, boolean includeInferred, int componentCount,
                            int nodesInCycles, boolean truncated, List<CycleComponent> components, String note) {
    }
}
