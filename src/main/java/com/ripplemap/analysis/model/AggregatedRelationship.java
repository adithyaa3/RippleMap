package com.ripplemap.analysis.model;

import java.util.List;

/**
 * All occurrences of one relationship {@code (layer, source, target, kind)} aggregated.
 *
 * @param resolution     RESOLVED when at least one occurrence was resolved, otherwise INFERRED
 * @param referenceCount total number of occurrences (may exceed {@code samples.size()})
 * @param resolvedCount  occurrences resolved by the symbol solver
 * @param inferredCount  occurrences linked by an inference rule
 * @param samples        deterministic, bounded list of occurrences (sorted by file, line, detail)
 */
public record AggregatedRelationship(
        GraphLayer layer,
        String sourceId,
        String targetId,
        RelationshipKind kind,
        Resolution resolution,
        int referenceCount,
        int resolvedCount,
        int inferredCount,
        List<ReferenceOccurrence> samples) {
}
