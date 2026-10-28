package com.ripplemap.impact;

import java.util.List;

/**
 * Result of a change-impact calculation for one selected node.
 *
 * @param eligibleCount  N: nodes in the analyzed layer
 * @param directCount    D: distinct direct dependents
 * @param transitiveCount T: distinct transitive dependents (selected node excluded)
 * @param maxDistance    H: maximum shortest reverse-dependency distance
 * @param score          unrounded score in [0, 100]
 * @param percentage     unrounded {@code 100 * T / (N - 1)}
 * @param affected       affected nodes ordered by distance, then id
 */
public record ImpactComputation(
        String selectedId,
        int eligibleCount,
        int directCount,
        int transitiveCount,
        int maxDistance,
        double directSignal,
        double reachSignal,
        double depthSignal,
        double score,
        double percentage,
        ImpactLevel level,
        List<AffectedNode> affected) {

    /**
     * @param path shortest explanatory path in dependency direction: {@code id -> ... -> selected}
     */
    public record AffectedNode(String id, int distance, List<String> path) {
        public boolean direct() {
            return distance == 1;
        }
    }
}
