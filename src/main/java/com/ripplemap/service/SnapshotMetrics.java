package com.ripplemap.service;

import com.ripplemap.analysis.model.AggregatedRelationship;
import com.ripplemap.analysis.model.AnalysisResult;
import com.ripplemap.analysis.model.ExtractedEntity;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.graph.GraphFactory;
import com.ripplemap.graph.algorithms.CycleDetector;
import com.ripplemap.graph.algorithms.CyclicComponent;
import com.ripplemap.graph.algorithms.GraphSummary;
import com.ripplemap.graph.model.DependencyGraph;
import com.ripplemap.impact.NodeMetrics;
import com.ripplemap.impact.NodeMetricsCalculator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Structural metrics computed once per snapshot from the default model (resolved and inferred relationships
 * of all kinds). Impact requests recompute on demand with the options the user selects.
 */
public record SnapshotMetrics(
        Map<String, NodeMetrics> nodeMetrics,
        int typeCount,
        int methodCount,
        int constructorCount,
        int fieldCount,
        int typeDependencyPairs,
        int typeRelationships,
        int methodDependencyPairs,
        int methodRelationships,
        int typeCyclicComponents,
        int typeNodesInCycles,
        int methodCyclicComponents,
        int isolatedTypes,
        boolean nodeMetricsComputed) {

    public static SnapshotMetrics compute(AnalysisResult result, int transitiveLimit, Runnable checkpoint) {
        List<String> typeIds = new ArrayList<>();
        List<String> methodIds = new ArrayList<>();
        int methods = 0;
        int constructors = 0;
        int fields = 0;
        for (ExtractedEntity e : result.entities()) {
            switch (e.kind()) {
                case METHOD -> {
                    methods++;
                    methodIds.add(e.stableId());
                }
                case CONSTRUCTOR -> {
                    constructors++;
                    methodIds.add(e.stableId());
                }
                case FIELD -> fields++;
                default -> typeIds.add(e.stableId());
            }
        }
        int typeRelationships = 0;
        int methodRelationships = 0;
        for (AggregatedRelationship r : result.relationships()) {
            if (r.layer() == GraphLayer.TYPE) {
                typeRelationships++;
            } else {
                methodRelationships++;
            }
        }
        DependencyGraph typeGraph = GraphFactory.build(typeIds, result.relationships(), GraphLayer.TYPE, null, true);
        DependencyGraph methodGraph = GraphFactory.build(methodIds, result.relationships(), GraphLayer.METHOD, null, true);
        List<CyclicComponent> typeCycles = CycleDetector.find(typeGraph);
        List<CyclicComponent> methodCycles = CycleDetector.find(methodGraph);
        GraphSummary typeSummary = GraphSummary.of(typeGraph, typeCycles);
        boolean transitive = typeIds.size() <= transitiveLimit;
        Map<String, NodeMetrics> metrics = new HashMap<>(NodeMetricsCalculator.compute(typeGraph, transitive, checkpoint));
        metrics.putAll(NodeMetricsCalculator.compute(methodGraph, false, checkpoint));
        int inCycles = 0;
        for (CyclicComponent c : typeCycles) {
            inCycles += c.size();
        }
        return new SnapshotMetrics(metrics, typeIds.size(), methods, constructors, fields, typeGraph.edgeCount(),
                typeRelationships, methodGraph.edgeCount(), methodRelationships, typeCycles.size(), inCycles,
                methodCycles.size(), typeSummary.isolatedNodes().size(), transitive);
    }
}
