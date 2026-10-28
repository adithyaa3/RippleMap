package com.ripplemap.graph.algorithms;

import com.ripplemap.graph.model.DependencyGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Detects circular dependencies using strongly connected components.
 * The number of cyclic components is <em>not</em> the number of distinct simple cycles; each component
 * is reported with one representative shortest cycle through its smallest member.
 */
public final class CycleDetector {

    private CycleDetector() {
    }

    /** Cyclic components ordered by size (largest first), then by smallest member id. */
    public static List<CyclicComponent> find(DependencyGraph graph) {
        List<CyclicComponent> result = new ArrayList<>();
        for (int[] component : StronglyConnectedComponents.compute(graph)) {
            if (component.length > 1) {
                result.add(new CyclicComponent(ids(graph, component), representativeCycle(graph, component)));
            } else if (graph.hasSelfLoop(component[0])) {
                String id = graph.idAt(component[0]);
                result.add(new CyclicComponent(List.of(id), List.of(id, id)));
            }
        }
        result.sort(Comparator.comparingInt(CyclicComponent::size).reversed()
                .thenComparing(c -> c.members().get(0)));
        return result;
    }

    /** True when the node lies on a cycle (multi-node SCC or self-loop). */
    public static boolean[] cyclicMembership(DependencyGraph graph) {
        boolean[] cyclic = new boolean[graph.size()];
        for (int[] component : StronglyConnectedComponents.compute(graph)) {
            if (component.length > 1) {
                for (int v : component) {
                    cyclic[v] = true;
                }
            } else if (graph.hasSelfLoop(component[0])) {
                cyclic[component[0]] = true;
            }
        }
        return cyclic;
    }

    /**
     * Shortest cycle through the smallest member, found by BFS restricted to the component:
     * a shortest path {@code s -> ... -> u} followed by an edge {@code u -> s}, with {@code u != s}.
     */
    static List<String> representativeCycle(DependencyGraph graph, int[] component) {
        int start = component[0];
        boolean[] inComponent = new boolean[graph.size()];
        for (int v : component) {
            inComponent[v] = true;
        }
        int[] parent = new int[graph.size()];
        Arrays.fill(parent, -2);
        parent[start] = -1;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        int closing = -1;
        while (!queue.isEmpty() && closing < 0) {
            int u = queue.poll();
            if (u != start && graph.hasEdge(u, start)) {
                closing = u;
                break;
            }
            graph.forEachOutgoing(u, v -> {
                if (inComponent[v] && parent[v] == -2) {
                    parent[v] = u;
                    queue.add(v);
                }
            });
        }
        if (closing < 0) {
            throw new IllegalStateException("Component is not strongly connected");
        }
        List<String> path = new ArrayList<>();
        for (int v = closing; v != -1; v = parent[v]) {
            path.add(graph.idAt(v));
        }
        Collections.reverse(path);
        path.add(graph.idAt(start));
        return path;
    }

    private static List<String> ids(DependencyGraph graph, int[] component) {
        List<String> ids = new ArrayList<>(component.length);
        for (int v : component) {
            ids.add(graph.idAt(v));
        }
        return ids;
    }
}
