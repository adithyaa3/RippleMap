package com.ripplemap.graph.algorithms;

import com.ripplemap.graph.model.DependencyGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Tarjan's strongly connected components algorithm, implemented iteratively (no recursion depth limits). */
public final class StronglyConnectedComponents {

    private StronglyConnectedComponents() {
    }

    /**
     * Returns every strongly connected component as an ascending array of node indices.
     * Components are ordered by their smallest member index, so output is deterministic.
     */
    public static List<int[]> compute(DependencyGraph graph) {
        int n = graph.size();
        int[] index = new int[n];
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(index, -1);
        int[] stack = new int[n];
        int stackSize = 0;
        int[] callNode = new int[n];
        int[] callPos = new int[n];
        int[][] adjacency = new int[n][];
        for (int i = 0; i < n; i++) {
            int[] targets = new int[graph.outDegree(i)];
            int[] cursor = {0};
            graph.forEachOutgoing(i, t -> targets[cursor[0]++] = t);
            adjacency[i] = targets;
        }
        int counter = 0;
        List<int[]> components = new ArrayList<>();
        for (int root = 0; root < n; root++) {
            if (index[root] != -1) {
                continue;
            }
            int depth = 0;
            callNode[0] = root;
            callPos[0] = 0;
            index[root] = low[root] = counter++;
            stack[stackSize++] = root;
            onStack[root] = true;
            while (depth >= 0) {
                int u = callNode[depth];
                if (callPos[depth] < adjacency[u].length) {
                    int w = adjacency[u][callPos[depth]++];
                    if (index[w] == -1) {
                        index[w] = low[w] = counter++;
                        stack[stackSize++] = w;
                        onStack[w] = true;
                        depth++;
                        callNode[depth] = w;
                        callPos[depth] = 0;
                    } else if (onStack[w]) {
                        low[u] = Math.min(low[u], index[w]);
                    }
                } else {
                    if (low[u] == index[u]) {
                        // u is the root of a component: it and everything above it on the stack
                        int position = stackSize - 1;
                        while (stack[position] != u) {
                            position--;
                        }
                        int[] component = Arrays.copyOfRange(stack, position, stackSize);
                        for (int w : component) {
                            onStack[w] = false;
                        }
                        stackSize = position;
                        Arrays.sort(component);
                        components.add(component);
                    }
                    depth--;
                    if (depth >= 0) {
                        int parent = callNode[depth];
                        low[parent] = Math.min(low[parent], low[u]);
                    }
                }
            }
        }
        components.sort(Comparator.comparingInt(c -> c[0]));
        return components;
    }
}
