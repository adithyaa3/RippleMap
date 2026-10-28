package com.ripplemap.graph.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntConsumer;

/**
 * Immutable directed graph over string node ids.
 *
 * <p><b>Edge direction:</b> an edge {@code A -> B} means <em>A depends on B</em>.
 * Dependencies of a node are reached through outgoing edges; dependents through incoming edges.
 *
 * <p>Nodes are stored in ascending id order and adjacency lists are sorted and duplicate-free,
 * so every traversal over this graph is deterministic. Self-loops are allowed.
 */
public final class DependencyGraph {

    private final String[] ids;
    private final Map<String, Integer> indexById;
    private final int[][] outgoing;
    private final int[][] incoming;
    private final int edgeCount;
    private final int ignoredEdges;

    private DependencyGraph(String[] ids, Map<String, Integer> indexById, int[][] outgoing, int[][] incoming,
                            int edgeCount, int ignoredEdges) {
        this.ids = ids;
        this.indexById = indexById;
        this.outgoing = outgoing;
        this.incoming = incoming;
        this.edgeCount = edgeCount;
        this.ignoredEdges = ignoredEdges;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static DependencyGraph empty() {
        return builder().build();
    }

    /** Number of nodes. */
    public int size() {
        return ids.length;
    }

    /** Number of distinct directed edges, including self-loops. */
    public int edgeCount() {
        return edgeCount;
    }

    /** Edges passed to the builder whose source or target was not a node (they were not added). */
    public int ignoredEdgeCount() {
        return ignoredEdges;
    }

    public boolean contains(String id) {
        return indexById.containsKey(id);
    }

    /** Index of the node, or -1 when the id is not a node. */
    public int indexOf(String id) {
        Integer index = indexById.get(id);
        return index == null ? -1 : index;
    }

    public String idAt(int index) {
        return ids[index];
    }

    public List<String> nodeIds() {
        return Collections.unmodifiableList(Arrays.asList(ids));
    }

    public int outDegree(int index) {
        return outgoing[index].length;
    }

    public int inDegree(int index) {
        return incoming[index].length;
    }

    /** Visits targets of outgoing edges (dependencies) in ascending id order. */
    public void forEachOutgoing(int index, IntConsumer consumer) {
        for (int target : outgoing[index]) {
            consumer.accept(target);
        }
    }

    /** Visits sources of incoming edges (dependents) in ascending id order. */
    public void forEachIncoming(int index, IntConsumer consumer) {
        for (int source : incoming[index]) {
            consumer.accept(source);
        }
    }

    public boolean hasEdge(String sourceId, String targetId) {
        int s = indexOf(sourceId);
        int t = indexOf(targetId);
        return s >= 0 && t >= 0 && Arrays.binarySearch(outgoing[s], t) >= 0;
    }

    public boolean hasEdge(int source, int target) {
        return Arrays.binarySearch(outgoing[source], target) >= 0;
    }

    public boolean hasSelfLoop(int index) {
        return hasEdge(index, index);
    }

    /** Distinct direct dependencies of the node (targets of outgoing edges), excluding the node itself. */
    public Set<String> directDependencies(String id) {
        return neighbours(id, outgoing);
    }

    /** Distinct direct dependents of the node (sources of incoming edges), excluding the node itself. */
    public Set<String> directDependents(String id) {
        return neighbours(id, incoming);
    }

    /** Count of distinct neighbours other than the node itself. */
    public int distinctOutNeighbours(int index) {
        return outgoing[index].length - (hasSelfLoop(index) ? 1 : 0);
    }

    public int distinctInNeighbours(int index) {
        return incoming[index].length - (hasSelfLoop(index) ? 1 : 0);
    }

    private Set<String> neighbours(String id, int[][] adjacency) {
        int index = indexOf(id);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown node: " + id);
        }
        Set<String> result = new LinkedHashSet<>();
        for (int n : adjacency[index]) {
            if (n != index) {
                result.add(ids[n]);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** Builds the subgraph induced by the given node ids (unknown ids are ignored). */
    public DependencyGraph induced(Collection<String> nodeIds) {
        Builder builder = builder();
        boolean[] keep = new boolean[ids.length];
        for (String id : nodeIds) {
            int index = indexOf(id);
            if (index >= 0) {
                keep[index] = true;
                builder.addNode(id);
            }
        }
        for (int s = 0; s < ids.length; s++) {
            if (!keep[s]) {
                continue;
            }
            for (int t : outgoing[s]) {
                if (keep[t]) {
                    builder.addEdge(ids[s], ids[t]);
                }
            }
        }
        return builder.build();
    }

    public static final class Builder {
        private final TreeSet<String> nodes = new TreeSet<>();
        private final List<String[]> edges = new ArrayList<>();

        private Builder() {
        }

        public Builder addNode(String id) {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("Node id must not be empty");
            }
            nodes.add(id);
            return this;
        }

        public Builder addNodes(Collection<String> ids) {
            ids.forEach(this::addNode);
            return this;
        }

        /** Adds {@code source -> target} ("source depends on target"). Endpoints must be added as nodes. */
        public Builder addEdge(String source, String target) {
            edges.add(new String[]{source, target});
            return this;
        }

        public DependencyGraph build() {
            String[] ids = nodes.toArray(new String[0]);
            Map<String, Integer> indexById = new HashMap<>(ids.length * 2);
            for (int i = 0; i < ids.length; i++) {
                indexById.put(ids[i], i);
            }
            List<TreeSet<Integer>> out = new ArrayList<>(ids.length);
            List<TreeSet<Integer>> in = new ArrayList<>(ids.length);
            for (int i = 0; i < ids.length; i++) {
                out.add(new TreeSet<>());
                in.add(new TreeSet<>());
            }
            int ignored = 0;
            for (String[] edge : edges) {
                Integer s = edge[0] == null ? null : indexById.get(edge[0]);
                Integer t = edge[1] == null ? null : indexById.get(edge[1]);
                if (s == null || t == null) {
                    ignored++;
                    continue;
                }
                out.get(s).add(t);
                in.get(t).add(s);
            }
            int[][] outgoing = new int[ids.length][];
            int[][] incoming = new int[ids.length][];
            int edgeCount = 0;
            for (int i = 0; i < ids.length; i++) {
                outgoing[i] = out.get(i).stream().mapToInt(Integer::intValue).toArray();
                incoming[i] = in.get(i).stream().mapToInt(Integer::intValue).toArray();
                edgeCount += outgoing[i].length;
            }
            return new DependencyGraph(ids, Collections.unmodifiableMap(indexById), outgoing, incoming, edgeCount, ignored);
        }
    }
}
