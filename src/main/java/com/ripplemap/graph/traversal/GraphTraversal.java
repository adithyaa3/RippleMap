package com.ripplemap.graph.traversal;

import com.ripplemap.graph.model.DependencyGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Iterative breadth-first traversals; safe for arbitrarily deep graphs and cycles. */
public final class GraphTraversal {

    public static final int UNLIMITED = Integer.MAX_VALUE;

    private GraphTraversal() {
    }

    /** All nodes reachable from {@code start} in the given direction (start excluded). */
    public static Reachability reachable(DependencyGraph graph, String start, Direction direction) {
        return reachable(graph, start, direction, UNLIMITED);
    }

    /**
     * Breadth-first traversal up to {@code maxDepth} steps. Neighbours are visited in ascending id order,
     * so distances, parents and discovery order are deterministic.
     */
    public static Reachability reachable(DependencyGraph graph, String start, Direction direction, int maxDepth) {
        int startIndex = graph.indexOf(start);
        if (startIndex < 0) {
            throw new IllegalArgumentException("Unknown node: " + start);
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must not be negative");
        }
        int n = graph.size();
        int[] distance = new int[n];
        int[] parent = new int[n];
        java.util.Arrays.fill(distance, -1);
        distance[startIndex] = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startIndex);
        List<Integer> order = new ArrayList<>();
        boolean[] truncated = {false};
        while (!queue.isEmpty()) {
            int u = queue.poll();
            int du = distance[u];
            visitNeighbours(graph, u, direction, v -> {
                if (distance[v] >= 0) {
                    return;
                }
                if (du >= maxDepth) {
                    truncated[0] = true;
                    return;
                }
                distance[v] = du + 1;
                parent[v] = u;
                order.add(v);
                queue.add(v);
            });
        }
        Map<String, Integer> distances = new LinkedHashMap<>();
        Map<String, String> parents = new LinkedHashMap<>();
        for (int v : order) {
            distances.put(graph.idAt(v), distance[v]);
            parents.put(graph.idAt(v), graph.idAt(parent[v]));
        }
        return new Reachability(start, direction, distances, parents, truncated[0]);
    }

    /** Shortest path from {@code from} to {@code to} following the direction; empty when unreachable. */
    public static Optional<List<String>> shortestPath(DependencyGraph graph, String from, String to, Direction direction) {
        int source = graph.indexOf(from);
        int target = graph.indexOf(to);
        if (source < 0 || target < 0) {
            throw new IllegalArgumentException("Unknown node: " + (source < 0 ? from : to));
        }
        if (source == target) {
            return Optional.of(List.of(from));
        }
        int n = graph.size();
        int[] parent = new int[n];
        java.util.Arrays.fill(parent, -2);
        parent[source] = -1;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        while (!queue.isEmpty() && parent[target] == -2) {
            int u = queue.poll();
            visitNeighbours(graph, u, direction, v -> {
                if (parent[v] == -2) {
                    parent[v] = u;
                    queue.add(v);
                }
            });
        }
        if (parent[target] == -2) {
            return Optional.empty();
        }
        List<String> path = new ArrayList<>();
        for (int v = target; v != -1; v = parent[v]) {
            path.add(graph.idAt(v));
        }
        Collections.reverse(path);
        return Optional.of(List.copyOf(path));
    }

    private static void visitNeighbours(DependencyGraph graph, int node, Direction direction,
                                        java.util.function.IntConsumer consumer) {
        if (direction == Direction.DEPENDENCIES) {
            graph.forEachOutgoing(node, consumer);
        } else {
            graph.forEachIncoming(node, consumer);
        }
    }
}
