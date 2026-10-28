package com.ripplemap.graph.traversal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of a breadth-first traversal from a start node.
 *
 * @param distances shortest distance of each reached node, in BFS discovery order; never contains the start node
 * @param parents   for each reached node, the neighbour through which it was first discovered (one step closer to start)
 * @param truncated true when traversal stopped at the depth limit while unvisited nodes remained reachable
 */
public record Reachability(String start, Direction direction, Map<String, Integer> distances,
                           Map<String, String> parents, boolean truncated) {

    public Reachability {
        distances = Collections.unmodifiableMap(distances);
        parents = Collections.unmodifiableMap(parents);
    }

    public int maxDistance() {
        int max = 0;
        for (int d : distances.values()) {
            max = Math.max(max, d);
        }
        return max;
    }

    public long countAtDistance(int distance) {
        return distances.values().stream().filter(d -> d == distance).count();
    }

    /**
     * Shortest path from {@code node} to the start, expressed in dependency direction.
     * For {@link Direction#DEPENDENTS} this is {@code node -> ... -> start} (node depends transitively on start);
     * for {@link Direction#DEPENDENCIES} it is {@code start -> ... -> node}.
     */
    public List<String> explanationPath(String node) {
        if (!distances.containsKey(node)) {
            return List.of();
        }
        List<String> chain = new ArrayList<>();
        String current = node;
        chain.add(current);
        while (!current.equals(start)) {
            current = parents.get(current);
            chain.add(current);
        }
        if (direction == Direction.DEPENDENCIES) {
            Collections.reverse(chain);
        }
        return List.copyOf(chain);
    }
}
