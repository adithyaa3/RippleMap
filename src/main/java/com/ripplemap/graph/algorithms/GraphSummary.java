package com.ripplemap.graph.algorithms;

import com.ripplemap.graph.model.DependencyGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Structural summary of a dependency graph.
 *
 * @param edgeCount             distinct directed edges between different nodes (self-loops excluded)
 * @param selfLoopCount         nodes with a self-loop
 * @param isolatedNodes         ids of nodes without edges to or from any other node, ascending
 * @param cyclicComponentCount  strongly connected components that contain a cycle
 * @param nodesInCycles         nodes that belong to a cyclic component
 */
public record GraphSummary(
        int nodeCount,
        int edgeCount,
        int selfLoopCount,
        List<String> isolatedNodes,
        int cyclicComponentCount,
        int nodesInCycles,
        int maxInDegree,
        int maxOutDegree) {

    public static GraphSummary of(DependencyGraph graph, List<CyclicComponent> cycles) {
        int selfLoops = 0;
        int maxIn = 0;
        int maxOut = 0;
        List<String> isolated = new ArrayList<>();
        for (int i = 0; i < graph.size(); i++) {
            boolean self = graph.hasSelfLoop(i);
            if (self) {
                selfLoops++;
            }
            int in = graph.distinctInNeighbours(i);
            int out = graph.distinctOutNeighbours(i);
            maxIn = Math.max(maxIn, in);
            maxOut = Math.max(maxOut, out);
            if (in == 0 && out == 0) {
                isolated.add(graph.idAt(i));
            }
        }
        int inCycles = 0;
        for (CyclicComponent c : cycles) {
            inCycles += c.size();
        }
        return new GraphSummary(graph.size(), graph.edgeCount() - selfLoops, selfLoops, List.copyOf(isolated),
                cycles.size(), inCycles, maxIn, maxOut);
    }
}
