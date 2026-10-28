package com.ripplemap.service;

import com.ripplemap.analysis.model.AggregatedRelationship;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.RelationshipKind;
import com.ripplemap.analysis.model.Resolution;
import com.ripplemap.dto.CycleDtos;
import com.ripplemap.dto.GraphDtos;
import com.ripplemap.entity.Analysis;
import com.ripplemap.graph.algorithms.CycleDetector;
import com.ripplemap.graph.algorithms.CyclicComponent;
import com.ripplemap.graph.model.DependencyGraph;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Circular dependencies: strongly connected components with a representative cycle each. */
@Service
public class CycleService {

    public static final String NOTE = "Each cyclic component is a set of components that all (transitively) depend "
            + "on each other. The number of components is not the number of distinct cycles; one shortest cycle "
            + "through the first member is shown as an example.";
    private static final int MAX_COMPONENTS = 200;
    private static final int MAX_MEMBERS = 200;

    private final SnapshotService snapshots;

    public CycleService(SnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    public CycleDtos.CycleList cycles(long projectId, Long analysisId, GraphLayer layer, Boolean includeInferred) {
        Analysis analysis = snapshots.resolve(projectId, analysisId);
        SnapshotData data = snapshots.load(analysis);
        GraphLayer effective = layer == null ? GraphLayer.TYPE : layer;
        boolean inferred = includeInferred == null || includeInferred;
        DependencyGraph graph = data.graph(effective, inferred);
        List<CyclicComponent> all = CycleDetector.find(graph);
        List<CycleDtos.CycleComponent> components = new ArrayList<>();
        int nodesInCycles = 0;
        for (int i = 0; i < all.size(); i++) {
            CyclicComponent c = all.get(i);
            nodesInCycles += c.size();
            if (components.size() >= MAX_COMPONENTS) {
                continue;
            }
            List<GraphDtos.EntityRef> members = c.members().stream().limit(MAX_MEMBERS)
                    .map(id -> DtoMapper.ref(data.entity(id))).toList();
            List<GraphDtos.EntityRef> cycle = c.representativeCycle().stream()
                    .map(id -> DtoMapper.ref(data.entity(id))).toList();
            List<CycleDtos.CycleStep> steps = new ArrayList<>();
            for (int s = 0; s + 1 < c.representativeCycle().size(); s++) {
                String from = c.representativeCycle().get(s);
                String to = c.representativeCycle().get(s + 1);
                TreeSet<RelationshipKind> kinds = new TreeSet<>();
                GraphDtos.Reference first = null;
                for (AggregatedRelationship r : data.relationshipsBetween(effective, from, to)) {
                    if (!inferred && r.resolution() != Resolution.RESOLVED) {
                        continue;
                    }
                    kinds.add(r.kind());
                    if (first == null && !r.samples().isEmpty()) {
                        var sample = r.samples().get(0);
                        first = new GraphDtos.Reference(sample.filePath(), sample.line(), sample.detail(),
                                sample.resolution(), sample.rule());
                    }
                }
                steps.add(new CycleDtos.CycleStep(DtoMapper.ref(data.entity(from)), DtoMapper.ref(data.entity(to)),
                        List.copyOf(kinds), first));
            }
            components.add(new CycleDtos.CycleComponent(i, c.size(), c.selfLoopOnly(), members,
                    c.size() > members.size(), cycle, steps));
        }
        return new CycleDtos.CycleList(analysis.getId(), effective, inferred, all.size(), nodesInCycles,
                all.size() > components.size(), components, NOTE);
    }
}
