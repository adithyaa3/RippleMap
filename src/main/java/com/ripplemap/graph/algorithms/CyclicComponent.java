package com.ripplemap.graph.algorithms;

import java.util.List;

/**
 * A circularly connected region: a strongly connected component with more than one node,
 * or a single node with a self-loop.
 *
 * @param members             member ids in ascending order
 * @param representativeCycle one valid closed cycle through the smallest member; first element equals the last
 */
public record CyclicComponent(List<String> members, List<String> representativeCycle) {

    public CyclicComponent {
        members = List.copyOf(members);
        representativeCycle = List.copyOf(representativeCycle);
    }

    public int size() {
        return members.size();
    }

    public boolean selfLoopOnly() {
        return members.size() == 1;
    }
}
