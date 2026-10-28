package com.ripplemap.impact;

/**
 * Per-node structural metrics stored with a snapshot.
 *
 * @param directDependencies   distinct nodes this node depends on (excluding itself)
 * @param directDependents     distinct nodes depending on this node (excluding itself)
 * @param transitiveDependents T, or null when transitive metrics were not computed
 * @param maxDependentDistance H, or null when transitive metrics were not computed
 * @param impactScore          default-model impact score, or null when not computed
 */
public record NodeMetrics(int directDependencies, int directDependents, Integer transitiveDependents,
                          Integer maxDependentDistance, Double impactScore, boolean inCycle) {
}
