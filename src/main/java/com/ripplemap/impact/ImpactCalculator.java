package com.ripplemap.impact;

import com.ripplemap.graph.model.DependencyGraph;
import com.ripplemap.graph.traversal.Direction;
import com.ripplemap.graph.traversal.GraphTraversal;
import com.ripplemap.graph.traversal.Reachability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Change-impact analysis: everything that (transitively) depends on the selected node may be affected
 * by a change to it. Impact therefore propagates against edge direction (reverse traversal).
 */
public final class ImpactCalculator {

    private ImpactCalculator() {
    }

    public static ImpactComputation compute(DependencyGraph graph, String selectedId, ImpactThresholds thresholds) {
        if (!graph.contains(selectedId)) {
            throw new IllegalArgumentException("Unknown node: " + selectedId);
        }
        Reachability reach = GraphTraversal.reachable(graph, selectedId, Direction.DEPENDENTS);
        int n = graph.size();
        int t = reach.distances().size();
        int d = (int) reach.countAtDistance(1);
        int h = reach.maxDistance();
        List<ImpactComputation.AffectedNode> affected = new ArrayList<>(t);
        for (Map.Entry<String, Integer> entry : reach.distances().entrySet()) {
            affected.add(new ImpactComputation.AffectedNode(entry.getKey(), entry.getValue(),
                    reach.explanationPath(entry.getKey())));
        }
        affected.sort(Comparator.comparingInt(ImpactComputation.AffectedNode::distance)
                .thenComparing(ImpactComputation.AffectedNode::id));
        return new ImpactComputation(selectedId, n, d, t, h,
                ImpactScore.directSignal(n, d),
                ImpactScore.reachSignal(n, t),
                ImpactScore.depthSignal(n, t, h),
                ImpactScore.score(n, d, t, h),
                ImpactScore.percentage(n, t),
                ImpactScore.classify(n, d, t, h, thresholds),
                List.copyOf(affected));
    }
}
