package com.ripplemap.analysis.extraction;

import com.ripplemap.analysis.model.AggregatedRelationship;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.ReferenceOccurrence;
import com.ripplemap.analysis.model.RelationshipKind;
import com.ripplemap.analysis.model.Resolution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates reference occurrences into unique relationships keyed by {@code (layer, source, target, kind)}.
 * Sample occurrences are capped per relationship; the first occurrences in deterministic processing order
 * (files sorted by path, AST pre-order) are kept and then sorted by file, line and detail.
 */
public final class RelationshipAggregator {

    private record Key(GraphLayer layer, String source, String target, RelationshipKind kind) {
    }

    private static final class Accumulator {
        int count;
        int resolved;
        int inferred;
        final List<ReferenceOccurrence> samples = new ArrayList<>(2);
    }

    private static final Comparator<ReferenceOccurrence> OCCURRENCE_ORDER = Comparator
            .comparing(ReferenceOccurrence::filePath)
            .thenComparingInt(ReferenceOccurrence::line)
            .thenComparing(ReferenceOccurrence::detail, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final Comparator<AggregatedRelationship> RELATIONSHIP_ORDER = Comparator
            .comparing(AggregatedRelationship::layer)
            .thenComparing(AggregatedRelationship::sourceId)
            .thenComparing(AggregatedRelationship::targetId)
            .thenComparing(AggregatedRelationship::kind);

    private final Map<Key, Accumulator> relationships = new HashMap<>();
    private final int maxSamples;

    public RelationshipAggregator(int maxSamples) {
        if (maxSamples < 1) {
            throw new IllegalArgumentException("maxSamples must be at least 1");
        }
        this.maxSamples = maxSamples;
    }

    public void add(GraphLayer layer, String source, String target, RelationshipKind kind, ReferenceOccurrence occurrence) {
        if (occurrence.resolution() == Resolution.UNRESOLVED) {
            throw new IllegalArgumentException("Unresolved references must not become relationships");
        }
        if (!kind.appliesTo(layer)) {
            throw new IllegalArgumentException(kind + " is not a " + layer + " relationship");
        }
        Accumulator acc = relationships.computeIfAbsent(new Key(layer, source, target, kind), k -> new Accumulator());
        acc.count++;
        if (occurrence.resolution() == Resolution.RESOLVED) {
            acc.resolved++;
        } else {
            acc.inferred++;
        }
        if (acc.samples.size() < maxSamples) {
            acc.samples.add(occurrence);
        }
    }

    public List<AggregatedRelationship> build() {
        List<AggregatedRelationship> result = new ArrayList<>(relationships.size());
        for (Map.Entry<Key, Accumulator> entry : relationships.entrySet()) {
            Key key = entry.getKey();
            Accumulator acc = entry.getValue();
            List<ReferenceOccurrence> samples = new ArrayList<>(acc.samples);
            samples.sort(OCCURRENCE_ORDER);
            result.add(new AggregatedRelationship(key.layer(), key.source(), key.target(), key.kind(),
                    acc.resolved > 0 ? Resolution.RESOLVED : Resolution.INFERRED,
                    acc.count, acc.resolved, acc.inferred, List.copyOf(samples)));
        }
        result.sort(RELATIONSHIP_ORDER);
        return result;
    }
}
