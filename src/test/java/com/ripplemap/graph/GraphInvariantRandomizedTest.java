package com.ripplemap.graph;

import com.ripplemap.graph.algorithms.CycleDetector;
import com.ripplemap.graph.algorithms.CyclicComponent;
import com.ripplemap.graph.algorithms.StronglyConnectedComponents;
import com.ripplemap.graph.model.DependencyGraph;
import com.ripplemap.graph.traversal.Direction;
import com.ripplemap.graph.traversal.GraphTraversal;
import com.ripplemap.graph.traversal.Reachability;
import com.ripplemap.impact.ImpactCalculator;
import com.ripplemap.impact.ImpactComputation;
import com.ripplemap.impact.ImpactThresholds;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Randomized invariant tests with recorded seeds. The oracle is an independent all-pairs shortest-path
 * matrix (Floyd-Warshall) computed directly from the generated edge list, not from the code under test.
 */
class GraphInvariantRandomizedTest {

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 120);
    }

    private record RandomGraph(DependencyGraph graph, List<String> ids, boolean[][] edge, int[][] dist) {
    }

    private static RandomGraph generate(long seed) {
        Random random = new Random(seed);
        int n = 1 + random.nextInt(30);
        double density = 0.02 + random.nextDouble() * 0.25;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(String.format("v%02d", i));
        }
        boolean[][] edge = new boolean[n][n];
        DependencyGraph.Builder builder = DependencyGraph.builder().addNodes(ids);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (random.nextDouble() < density) {
                    edge[i][j] = true;
                    builder.addEdge(ids.get(i), ids.get(j));
                    if (random.nextInt(5) == 0) {
                        builder.addEdge(ids.get(i), ids.get(j)); // duplicate edge
                    }
                }
            }
        }
        int inf = Integer.MAX_VALUE / 4;
        int[][] dist = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                dist[i][j] = edge[i][j] ? 1 : inf;
            }
        }
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (dist[i][k] + dist[k][j] < dist[i][j]) {
                        dist[i][j] = dist[i][k] + dist[k][j];
                    }
                }
            }
        }
        return new RandomGraph(builder.build(), ids, edge, dist);
    }

    private static boolean reaches(RandomGraph g, int from, int to) {
        return g.dist()[from][to] < Integer.MAX_VALUE / 4;
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void reachabilityMatchesOracleInBothDirections(long seed) {
        RandomGraph g = generate(seed);
        int n = g.ids().size();
        for (int s = 0; s < n; s++) {
            Reachability deps = GraphTraversal.reachable(g.graph(), g.ids().get(s), Direction.DEPENDENCIES);
            Reachability dependents = GraphTraversal.reachable(g.graph(), g.ids().get(s), Direction.DEPENDENTS);
            for (int t = 0; t < n; t++) {
                String id = g.ids().get(t);
                if (t == s) {
                    assertThat(deps.distances()).doesNotContainKey(id);
                    assertThat(dependents.distances()).doesNotContainKey(id);
                    continue;
                }
                if (reaches(g, s, t)) {
                    assertThat(deps.distances().get(id)).as("dist %s->%s", s, t).isEqualTo(g.dist()[s][t]);
                } else {
                    assertThat(deps.distances()).doesNotContainKey(id);
                }
                if (reaches(g, t, s)) {
                    assertThat(dependents.distances().get(id)).isEqualTo(g.dist()[t][s]);
                } else {
                    assertThat(dependents.distances()).doesNotContainKey(id);
                }
            }
            assertThat(new HashSet<>(deps.distances().keySet())).hasSize(deps.distances().size());
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void explanationPathsConsistOfRealEdges(long seed) {
        RandomGraph g = generate(seed);
        for (String start : g.ids()) {
            Reachability dependents = GraphTraversal.reachable(g.graph(), start, Direction.DEPENDENTS);
            for (String node : dependents.distances().keySet()) {
                List<String> path = dependents.explanationPath(node);
                assertThat(path.get(0)).isEqualTo(node);
                assertThat(path.get(path.size() - 1)).isEqualTo(start);
                assertThat(path).hasSize(dependents.distances().get(node) + 1);
                for (int i = 0; i + 1 < path.size(); i++) {
                    assertThat(g.graph().hasEdge(path.get(i), path.get(i + 1)))
                            .as("edge %s -> %s", path.get(i), path.get(i + 1)).isTrue();
                }
                assertThat(new HashSet<>(path)).hasSize(path.size());
            }
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void sccMembersAreExactlyTheMutuallyReachableNodes(long seed) {
        RandomGraph g = generate(seed);
        int n = g.ids().size();
        List<int[]> components = StronglyConnectedComponents.compute(g.graph());
        int[] componentOf = new int[n];
        int covered = 0;
        for (int c = 0; c < components.size(); c++) {
            for (int v : components.get(c)) {
                componentOf[v] = c;
                covered++;
            }
        }
        assertThat(covered).isEqualTo(n);
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                boolean mutual = a == b || (reaches(g, a, b) && reaches(g, b, a));
                assertThat(componentOf[a] == componentOf[b]).as("%s ~ %s", a, b).isEqualTo(mutual);
            }
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void representativeCyclesAreValidClosedSimpleCycles(long seed) {
        RandomGraph g = generate(seed);
        Set<String> cyclicNodes = new HashSet<>();
        for (CyclicComponent component : CycleDetector.find(g.graph())) {
            List<String> cycle = component.representativeCycle();
            assertThat(cycle.get(0)).isEqualTo(component.members().get(0));
            assertThat(cycle.get(cycle.size() - 1)).isEqualTo(cycle.get(0));
            assertThat(new HashSet<>(cycle.subList(0, cycle.size() - 1))).hasSize(cycle.size() - 1);
            for (int i = 0; i + 1 < cycle.size(); i++) {
                assertThat(g.graph().hasEdge(cycle.get(i), cycle.get(i + 1))).isTrue();
                assertThat(component.members()).contains(cycle.get(i));
            }
            cyclicNodes.addAll(component.members());
        }
        for (int v = 0; v < g.ids().size(); v++) {
            boolean onCycle = reaches(g, v, v);
            assertThat(cyclicNodes.contains(g.ids().get(v))).as("node %s on cycle", v).isEqualTo(onCycle);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void impactMetricsMatchOracleAndWeightedFormula(long seed) {
        RandomGraph g = generate(seed);
        int n = g.ids().size();
        for (int s = 0; s < n; s++) {
            ImpactComputation impact = ImpactCalculator.compute(g.graph(), g.ids().get(s), ImpactThresholds.DEFAULT);
            int t = 0;
            int d = 0;
            int h = 0;
            for (int v = 0; v < n; v++) {
                if (v != s && reaches(g, v, s)) {
                    t++;
                    h = Math.max(h, g.dist()[v][s]);
                    if (g.edge()[v][s]) {
                        d++;
                    }
                }
            }
            assertThat(impact.transitiveCount()).isEqualTo(t);
            assertThat(impact.directCount()).isEqualTo(d);
            assertThat(impact.maxDistance()).isEqualTo(h);
            assertThat(impact.affected()).hasSize(t);
            double expected = (n <= 1 || t == 0) ? 0.0
                    : 100.0 * (0.45 * d / (n - 1) + 0.40 * t / (n - 1) + 0.15 * Math.min(h, 5) / 5.0);
            assertThat(impact.score()).isCloseTo(expected, within(1e-9));
            assertThat(impact.score()).isBetween(0.0, 100.0);
        }
    }
}
