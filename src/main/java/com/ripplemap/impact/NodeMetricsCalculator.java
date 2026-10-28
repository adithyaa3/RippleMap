package com.ripplemap.impact;

import com.ripplemap.graph.algorithms.CycleDetector;
import com.ripplemap.graph.model.DependencyGraph;
import com.ripplemap.graph.traversal.Direction;
import com.ripplemap.graph.traversal.GraphTraversal;
import com.ripplemap.graph.traversal.Reachability;

import java.util.HashMap;
import java.util.Map;

/** Computes {@link NodeMetrics} for every node of a graph. */
public final class NodeMetricsCalculator {

    private NodeMetricsCalculator() {
    }

    /**
     * @param transitive whether to run a reverse traversal per node (O(N * (N + E)))
     * @param checkpoint invoked between nodes so long computations can be cancelled
     */
    public static Map<String, NodeMetrics> compute(DependencyGraph graph, boolean transitive, Runnable checkpoint) {
        boolean[] cyclic = CycleDetector.cyclicMembership(graph);
        int n = graph.size();
        Map<String, NodeMetrics> metrics = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            String id = graph.idAt(i);
            int deps = graph.distinctOutNeighbours(i);
            int dependents = graph.distinctInNeighbours(i);
            if (transitive) {
                if ((i & 63) == 0) {
                    checkpoint.run();
                }
                Reachability reach = GraphTraversal.reachable(graph, id, Direction.DEPENDENTS);
                int t = reach.distances().size();
                int h = reach.maxDistance();
                metrics.put(id, new NodeMetrics(deps, dependents, t, h,
                        ImpactScore.score(n, dependents, t, h), cyclic[i]));
            } else {
                metrics.put(id, new NodeMetrics(deps, dependents, null, null, null, cyclic[i]));
            }
        }
        return metrics;
    }
}
