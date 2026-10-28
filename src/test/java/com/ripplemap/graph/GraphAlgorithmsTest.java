package com.ripplemap.graph;

import com.ripplemap.graph.algorithms.CycleDetector;
import com.ripplemap.graph.algorithms.CyclicComponent;
import com.ripplemap.graph.algorithms.GraphSummary;
import com.ripplemap.graph.algorithms.StronglyConnectedComponents;
import com.ripplemap.graph.model.DependencyGraph;
import com.ripplemap.graph.traversal.Direction;
import com.ripplemap.graph.traversal.GraphTraversal;
import com.ripplemap.graph.traversal.Reachability;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class GraphAlgorithmsTest {

    /** Builds a graph from "a>b" edge specs; nodes are listed explicitly so isolated nodes can be added. */
    static DependencyGraph graph(List<String> nodes, String... edges) {
        DependencyGraph.Builder builder = DependencyGraph.builder().addNodes(nodes);
        for (String edge : edges) {
            String[] parts = edge.split(">");
            builder.addEdge(parts[0], parts[1]);
        }
        return builder.build();
    }

    @Test
    void emptyGraph() {
        DependencyGraph g = DependencyGraph.empty();
        assertThat(g.size()).isZero();
        assertThat(g.edgeCount()).isZero();
        assertThat(StronglyConnectedComponents.compute(g)).isEmpty();
        assertThat(CycleDetector.find(g)).isEmpty();
        GraphSummary summary = GraphSummary.of(g, List.of());
        assertThat(summary.nodeCount()).isZero();
        assertThat(summary.isolatedNodes()).isEmpty();
    }

    @Test
    void singleNode() {
        DependencyGraph g = graph(List.of("a"));
        assertThat(g.directDependencies("a")).isEmpty();
        assertThat(g.directDependents("a")).isEmpty();
        assertThat(GraphTraversal.reachable(g, "a", Direction.DEPENDENTS).distances()).isEmpty();
        assertThat(StronglyConnectedComponents.compute(g)).hasSize(1);
        assertThat(CycleDetector.find(g)).isEmpty();
        assertThat(GraphSummary.of(g, CycleDetector.find(g)).isolatedNodes()).containsExactly("a");
    }

    @Test
    void singleEdgeDirectionIsDependsOn() {
        DependencyGraph g = graph(List.of("controller", "service"), "controller>service");
        assertThat(g.directDependencies("controller")).containsExactly("service");
        assertThat(g.directDependents("service")).containsExactly("controller");
        assertThat(g.directDependencies("service")).isEmpty();
        assertThat(g.directDependents("controller")).isEmpty();
        assertThat(g.hasEdge("controller", "service")).isTrue();
        assertThat(g.hasEdge("service", "controller")).isFalse();
    }

    @Test
    void linearChainTraversalInBothDirections() {
        DependencyGraph g = graph(List.of("a", "b", "c", "d"), "a>b", "b>c", "c>d");
        Reachability deps = GraphTraversal.reachable(g, "a", Direction.DEPENDENCIES);
        assertThat(deps.distances()).containsExactly(entry("b", 1), entry("c", 2), entry("d", 3));
        assertThat(deps.explanationPath("d")).containsExactly("a", "b", "c", "d");

        Reachability dependents = GraphTraversal.reachable(g, "d", Direction.DEPENDENTS);
        assertThat(dependents.distances()).containsExactly(entry("c", 1), entry("b", 2), entry("a", 3));
        assertThat(dependents.explanationPath("a")).containsExactly("a", "b", "c", "d");
        assertThat(dependents.maxDistance()).isEqualTo(3);

        // the dependency direction must never be mixed up
        assertThat(GraphTraversal.reachable(g, "a", Direction.DEPENDENTS).distances()).isEmpty();
        assertThat(GraphTraversal.reachable(g, "d", Direction.DEPENDENCIES).distances()).isEmpty();
    }

    @Test
    void diamondCountsSharedDependentOnceAndPicksDeterministicParent() {
        DependencyGraph g = graph(List.of("a", "b", "c", "d"), "a>b", "a>c", "b>d", "c>d");
        Reachability r = GraphTraversal.reachable(g, "d", Direction.DEPENDENTS);
        assertThat(r.distances()).containsExactly(entry("b", 1), entry("c", 1), entry("a", 2));
        assertThat(r.parents().get("a")).isEqualTo("b");
        assertThat(r.explanationPath("a")).containsExactly("a", "b", "d");
        assertThat(r.countAtDistance(1)).isEqualTo(2);
    }

    @Test
    void branchingAndDisconnectedGraphs() {
        DependencyGraph g = graph(List.of("root", "l1", "l2", "leaf", "x", "y"),
                "root>l1", "root>l2", "l1>leaf", "x>y");
        assertThat(GraphTraversal.reachable(g, "root", Direction.DEPENDENCIES).distances().keySet())
                .containsExactly("l1", "l2", "leaf");
        assertThat(GraphTraversal.reachable(g, "y", Direction.DEPENDENTS).distances().keySet()).containsExactly("x");
        assertThat(GraphTraversal.shortestPath(g, "root", "y", Direction.DEPENDENCIES)).isEmpty();
    }

    @Test
    void selfLoopIsACycleButNotADependencyOnAnotherNode() {
        DependencyGraph g = graph(List.of("a", "b"), "a>a", "a>b");
        assertThat(g.hasSelfLoop(g.indexOf("a"))).isTrue();
        assertThat(g.directDependencies("a")).containsExactly("b");
        assertThat(g.directDependents("a")).isEmpty();
        assertThat(GraphTraversal.reachable(g, "a", Direction.DEPENDENTS).distances()).isEmpty();
        List<CyclicComponent> cycles = CycleDetector.find(g);
        assertThat(cycles).singleElement().satisfies(c -> {
            assertThat(c.members()).containsExactly("a");
            assertThat(c.representativeCycle()).containsExactly("a", "a");
            assertThat(c.selfLoopOnly()).isTrue();
        });
        GraphSummary summary = GraphSummary.of(g, cycles);
        assertThat(summary.selfLoopCount()).isEqualTo(1);
        assertThat(summary.edgeCount()).isEqualTo(1);
    }

    @Test
    void directedCycleTerminatesAndExcludesStart() {
        DependencyGraph g = graph(List.of("a", "b", "c"), "a>b", "b>c", "c>a");
        assertThat(GraphTraversal.reachable(g, "a", Direction.DEPENDENCIES).distances())
                .containsExactly(entry("b", 1), entry("c", 2));
        assertThat(GraphTraversal.reachable(g, "a", Direction.DEPENDENTS).distances())
                .containsExactly(entry("c", 1), entry("b", 2));
        List<CyclicComponent> cycles = CycleDetector.find(g);
        assertThat(cycles).singleElement().satisfies(c -> {
            assertThat(c.members()).containsExactly("a", "b", "c");
            assertThat(c.representativeCycle()).containsExactly("a", "b", "c", "a");
        });
    }

    @Test
    void multipleCyclicRegionsAreSortedBySize() {
        DependencyGraph g = graph(List.of("a", "b", "c", "d", "e", "f"),
                "a>b", "b>a", "c>d", "d>e", "e>c", "e>f", "b>c");
        List<CyclicComponent> cycles = CycleDetector.find(g);
        assertThat(cycles).extracting(CyclicComponent::members)
                .containsExactly(List.of("c", "d", "e"), List.of("a", "b"));
        assertThat(cycles.get(0).representativeCycle()).containsExactly("c", "d", "e", "c");
        assertThat(cycles.get(1).representativeCycle()).containsExactly("a", "b", "a");
        GraphSummary summary = GraphSummary.of(g, cycles);
        assertThat(summary.cyclicComponentCount()).isEqualTo(2);
        assertThat(summary.nodesInCycles()).isEqualTo(5);
    }

    @Test
    void representativeCycleIsShortestThroughSmallestMember() {
        // a>b>c>d>a is a 4-cycle, but a>d>a is a shorter cycle through a
        DependencyGraph g = graph(List.of("a", "b", "c", "d"), "a>b", "b>c", "c>d", "d>a", "a>d");
        assertThat(CycleDetector.find(g).get(0).representativeCycle()).containsExactly("a", "d", "a");
    }

    @Test
    void duplicateEdgesAreCountedOnce() {
        DependencyGraph g = graph(List.of("a", "b"), "a>b", "a>b", "a>b");
        assertThat(g.edgeCount()).isEqualTo(1);
        assertThat(g.directDependents("b")).containsExactly("a");
    }

    @Test
    void edgesToMissingNodesAreIgnoredAndCounted() {
        DependencyGraph g = DependencyGraph.builder().addNode("a").addEdge("a", "ghost").addEdge("ghost", "a").build();
        assertThat(g.edgeCount()).isZero();
        assertThat(g.ignoredEdgeCount()).isEqualTo(2);
        assertThat(g.contains("ghost")).isFalse();
    }

    @Test
    void nodesWithManyIncomingAndOutgoingEdges() {
        DependencyGraph g = graph(List.of("hub", "a", "b", "c", "x", "y"),
                "a>hub", "b>hub", "c>hub", "hub>x", "hub>y");
        assertThat(g.directDependents("hub")).containsExactly("a", "b", "c");
        assertThat(g.directDependencies("hub")).containsExactly("x", "y");
        GraphSummary summary = GraphSummary.of(g, List.of());
        assertThat(summary.maxInDegree()).isEqualTo(3);
        assertThat(summary.maxOutDegree()).isEqualTo(2);
    }

    @Test
    void shortestPathFindsMinimalRouteInEitherDirection() {
        DependencyGraph g = graph(List.of("a", "b", "c"), "a>b", "b>c", "a>c");
        assertThat(GraphTraversal.shortestPath(g, "a", "c", Direction.DEPENDENCIES)).contains(List.of("a", "c"));
        assertThat(GraphTraversal.shortestPath(g, "c", "a", Direction.DEPENDENTS)).contains(List.of("c", "a"));
        assertThat(GraphTraversal.shortestPath(g, "c", "a", Direction.DEPENDENCIES)).isEmpty();
        assertThat(GraphTraversal.shortestPath(g, "b", "b", Direction.DEPENDENCIES)).contains(List.of("b"));
    }

    @Test
    void depthLimitedTraversalReportsTruncation() {
        DependencyGraph g = graph(List.of("a", "b", "c", "d"), "a>b", "b>c", "c>d");
        Reachability limited = GraphTraversal.reachable(g, "a", Direction.DEPENDENCIES, 2);
        assertThat(limited.distances().keySet()).containsExactly("b", "c");
        assertThat(limited.truncated()).isTrue();
        assertThat(GraphTraversal.reachable(g, "a", Direction.DEPENDENCIES, 3).truncated()).isFalse();
    }

    @Test
    void insertionOrderDoesNotChangeResults() {
        DependencyGraph first = graph(List.of("d", "c", "b", "a"), "c>d", "a>c", "b>d", "a>b");
        DependencyGraph second = graph(List.of("a", "b", "c", "d"), "a>b", "a>c", "b>d", "c>d");
        assertThat(first.nodeIds()).isEqualTo(second.nodeIds());
        assertThat(GraphTraversal.reachable(first, "d", Direction.DEPENDENTS))
                .isEqualTo(GraphTraversal.reachable(second, "d", Direction.DEPENDENTS));
    }

    @Test
    void unknownNodesAreRejected() {
        DependencyGraph g = graph(List.of("a"));
        assertThatThrownBy(() -> GraphTraversal.reachable(g, "zzz", Direction.DEPENDENTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> g.directDependencies("zzz")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inducedSubgraphKeepsOnlyInternalEdges() {
        DependencyGraph g = graph(List.of("a", "b", "c"), "a>b", "b>c", "c>a");
        DependencyGraph sub = g.induced(List.of("a", "b", "missing"));
        assertThat(sub.nodeIds()).containsExactly("a", "b");
        assertThat(sub.hasEdge("a", "b")).isTrue();
        assertThat(sub.edgeCount()).isEqualTo(1);
    }

    @Test
    void largeChainAndLargeCycleDoNotOverflowTheStack() {
        int n = 200_000;
        DependencyGraph.Builder builder = DependencyGraph.builder();
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(String.format("n%06d", i));
        }
        builder.addNodes(ids);
        for (int i = 0; i + 1 < n; i++) {
            builder.addEdge(ids.get(i), ids.get(i + 1));
        }
        builder.addEdge(ids.get(n - 1), ids.get(0));
        DependencyGraph g = builder.build();
        Map<String, Integer> reach = GraphTraversal.reachable(g, ids.get(0), Direction.DEPENDENCIES).distances();
        assertThat(reach).hasSize(n - 1);
        assertThat(reach.get(ids.get(n - 1))).isEqualTo(n - 1);
        List<CyclicComponent> cycles = CycleDetector.find(g);
        assertThat(cycles).singleElement().satisfies(c -> {
            assertThat(c.size()).isEqualTo(n);
            assertThat(c.representativeCycle()).hasSize(n + 1);
        });
    }
}
