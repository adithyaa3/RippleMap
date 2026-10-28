package com.ripplemap.impact;

import com.ripplemap.graph.model.DependencyGraph;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Expected values are calculated by hand from the documented formula. */
class ImpactCalculatorTest {

    private static DependencyGraph graph(List<String> nodes, String... edges) {
        DependencyGraph.Builder builder = DependencyGraph.builder().addNodes(nodes);
        for (String edge : edges) {
            String[] parts = edge.split(">");
            builder.addEdge(parts[0], parts[1]);
        }
        return builder.build();
    }

    @Test
    void goldenChainScoreIs68Point5() {
        DependencyGraph g = graph(List.of("Controller", "Service", "Repository"),
                "Controller>Service", "Service>Repository");
        ImpactComputation impact = ImpactCalculator.compute(g, "Repository", ImpactThresholds.DEFAULT);

        assertThat(impact.eligibleCount()).isEqualTo(3);
        assertThat(impact.directCount()).isEqualTo(1);
        assertThat(impact.transitiveCount()).isEqualTo(2);
        assertThat(impact.maxDistance()).isEqualTo(2);
        assertThat(impact.directSignal()).isCloseTo(0.5, within(1e-12));
        assertThat(impact.reachSignal()).isCloseTo(1.0, within(1e-12));
        assertThat(impact.depthSignal()).isCloseTo(0.4, within(1e-12));
        assertThat(impact.score()).isCloseTo(68.5, within(1e-9));
        assertThat(ImpactScore.round(impact.score())).isEqualByComparingTo("68.5");
        assertThat(impact.percentage()).isCloseTo(100.0, within(1e-9));
        assertThat(impact.level()).isEqualTo(ImpactLevel.HIGH);

        assertThat(impact.affected()).extracting(ImpactComputation.AffectedNode::id)
                .containsExactly("Service", "Controller");
        assertThat(impact.affected().get(0).direct()).isTrue();
        assertThat(impact.affected().get(0).path()).containsExactly("Service", "Repository");
        assertThat(impact.affected().get(1).direct()).isFalse();
        assertThat(impact.affected().get(1).path()).containsExactly("Controller", "Service", "Repository");
        assertThat(impact.affected()).extracting(ImpactComputation.AffectedNode::id).doesNotContain("Repository");
    }

    @Test
    void changingTheTopOfTheChainAffectsNothing() {
        DependencyGraph g = graph(List.of("Controller", "Service", "Repository"),
                "Controller>Service", "Service>Repository");
        ImpactComputation impact = ImpactCalculator.compute(g, "Controller", ImpactThresholds.DEFAULT);
        assertThat(impact.affected()).isEmpty();
        assertThat(impact.score()).isZero();
        assertThat(impact.level()).isEqualTo(ImpactLevel.LOW);
    }

    @Test
    void goldenDiamondScoreIs76AndCountsSharedDependentOnce() {
        DependencyGraph g = graph(List.of("A", "B", "C", "D"), "A>B", "A>C", "B>D", "C>D");
        ImpactComputation impact = ImpactCalculator.compute(g, "D", ImpactThresholds.DEFAULT);
        assertThat(impact.eligibleCount()).isEqualTo(4);
        assertThat(impact.directCount()).isEqualTo(2);
        assertThat(impact.transitiveCount()).isEqualTo(3);
        assertThat(impact.maxDistance()).isEqualTo(2);
        assertThat(impact.score()).isCloseTo(76.0, within(1e-9));
        assertThat(impact.percentage()).isCloseTo(100.0, within(1e-9));
        assertThat(impact.level()).isEqualTo(ImpactLevel.HIGH);
        assertThat(impact.affected()).extracting(ImpactComputation.AffectedNode::id).containsExactly("B", "C", "A");
        assertThat(impact.affected().get(2).path()).containsExactly("A", "B", "D");
    }

    @Test
    void isolatedNodeHasZeroImpact() {
        DependencyGraph g = graph(List.of("Isolated", "User", "Helper"), "User>Helper");
        ImpactComputation impact = ImpactCalculator.compute(g, "Isolated", ImpactThresholds.DEFAULT);
        assertThat(impact.directCount()).isZero();
        assertThat(impact.transitiveCount()).isZero();
        assertThat(impact.maxDistance()).isZero();
        assertThat(impact.score()).isZero();
        assertThat(impact.percentage()).isZero();
        assertThat(impact.level()).isEqualTo(ImpactLevel.LOW);
    }

    @Test
    void singleDependentInLargerGraph() {
        // N=5, D=1, T=1, H=1: (45 + 40) / 4 + 3 = 24.25
        DependencyGraph g = graph(List.of("a", "b", "c", "d", "e"), "a>b");
        ImpactComputation impact = ImpactCalculator.compute(g, "b", ImpactThresholds.DEFAULT);
        assertThat(impact.score()).isCloseTo(24.25, within(1e-9));
        assertThat(impact.percentage()).isCloseTo(25.0, within(1e-9));
        assertThat(impact.level()).isEqualTo(ImpactLevel.LOW);
        assertThat(ImpactScore.round(impact.score())).isEqualByComparingTo("24.3");
    }

    @Test
    void multipleLevelsAndOverlappingPaths() {
        // e<-d<-c<-b<-a plus a shortcut a>c and f>e : changing e
        DependencyGraph g = graph(List.of("a", "b", "c", "d", "e", "f"),
                "a>b", "b>c", "c>d", "d>e", "a>c", "f>e");
        ImpactComputation impact = ImpactCalculator.compute(g, "e", ImpactThresholds.DEFAULT);
        // dependents: d(1), f(1), c(2), a(3 via a>c), b(3)
        assertThat(impact.affected()).extracting(ImpactComputation.AffectedNode::id, ImpactComputation.AffectedNode::distance)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("d", 1), org.assertj.core.groups.Tuple.tuple("f", 1),
                        org.assertj.core.groups.Tuple.tuple("c", 2), org.assertj.core.groups.Tuple.tuple("a", 3),
                        org.assertj.core.groups.Tuple.tuple("b", 3));
        // N=6, D=2, T=5, H=3: (90 + 200) / 5 + 9 = 67
        assertThat(impact.score()).isCloseTo(67.0, within(1e-9));
        assertThat(impact.affected().get(3).path()).containsExactly("a", "c", "d", "e");
    }

    @Test
    void cyclesTerminateAndExcludeSelectedNode() {
        DependencyGraph g = graph(List.of("a", "b", "c"), "a>b", "b>c", "c>a");
        ImpactComputation impact = ImpactCalculator.compute(g, "a", ImpactThresholds.DEFAULT);
        assertThat(impact.affected()).extracting(ImpactComputation.AffectedNode::id).containsExactly("c", "b");
        assertThat(impact.transitiveCount()).isEqualTo(2);
        assertThat(impact.score()).isCloseTo(68.5, within(1e-9));
    }

    @Test
    void disconnectedComponentsAreNotAffected() {
        DependencyGraph g = graph(List.of("a", "b", "x", "y"), "a>b", "x>y");
        ImpactComputation impact = ImpactCalculator.compute(g, "b", ImpactThresholds.DEFAULT);
        assertThat(impact.affected()).extracting(ImpactComputation.AffectedNode::id).containsExactly("a");
        // N=4, D=1, T=1, H=1: 85/3 + 3
        assertThat(impact.score()).isCloseTo(85.0 / 3 + 3, within(1e-9));
    }

    @Test
    void depthSignalIsCappedAtFive() {
        // chain of 8: n7 <- n6 <- ... <- n0, changing n7 gives H = 7, capped to 5
        List<String> nodes = List.of("n0", "n1", "n2", "n3", "n4", "n5", "n6", "n7");
        DependencyGraph g = graph(nodes, "n0>n1", "n1>n2", "n2>n3", "n3>n4", "n4>n5", "n5>n6", "n6>n7");
        ImpactComputation impact = ImpactCalculator.compute(g, "n7", ImpactThresholds.DEFAULT);
        assertThat(impact.maxDistance()).isEqualTo(7);
        assertThat(impact.depthSignal()).isCloseTo(1.0, within(1e-12));
        // (45 + 280) / 7 + 15
        assertThat(impact.score()).isCloseTo(325.0 / 7 + 15, within(1e-9));
    }

    @Test
    void representativeScoresMatchHandCalculation() {
        assertThat(ImpactScore.score(2, 1, 1, 1)).isCloseTo(88.0, within(1e-9));
        assertThat(ImpactScore.score(7, 1, 6, 6)).isCloseTo((45 + 240) / 6.0 + 15, within(1e-9));
        assertThat(ImpactScore.score(6, 5, 5, 1)).isCloseTo(88.0, within(1e-9));
    }

    @Test
    void smallGraphsScoreZero() {
        assertThat(ImpactScore.score(0, 0, 0, 0)).isZero();
        assertThat(ImpactScore.score(1, 0, 0, 0)).isZero();
        assertThat(ImpactScore.percentage(1, 0)).isZero();
        DependencyGraph single = graph(List.of("only"));
        assertThat(ImpactCalculator.compute(single, "only", ImpactThresholds.DEFAULT).score()).isZero();
    }

    @Test
    void classificationAtExactBoundariesUsesExactArithmetic() {
        // N=21, D=4, T=6, H=3: (180 + 240) / 20 + 9 = 30 exactly -> MEDIUM
        assertThat(ImpactScore.score(21, 4, 6, 3)).isCloseTo(30.0, within(1e-12));
        assertThat(ImpactScore.classify(21, 4, 6, 3, ImpactThresholds.DEFAULT)).isEqualTo(ImpactLevel.MEDIUM);
        // N=21, D=4, T=6, H=2: 21 + 6 = 27 -> LOW
        assertThat(ImpactScore.classify(21, 4, 6, 2, ImpactThresholds.DEFAULT)).isEqualTo(ImpactLevel.LOW);
        // N=11, D=4, T=9, H=2: (180 + 360) / 10 + 6 = 60 exactly -> HIGH
        assertThat(ImpactScore.score(11, 4, 9, 2)).isCloseTo(60.0, within(1e-12));
        assertThat(ImpactScore.classify(11, 4, 9, 2, ImpactThresholds.DEFAULT)).isEqualTo(ImpactLevel.HIGH);
        // N=11, D=3, T=9, H=2: (135 + 360) / 10 + 6 = 55.5 -> MEDIUM
        assertThat(ImpactScore.classify(11, 3, 9, 2, ImpactThresholds.DEFAULT)).isEqualTo(ImpactLevel.MEDIUM);
    }

    @Test
    void customThresholdsAreApplied() {
        ImpactThresholds strict = new ImpactThresholds(new BigDecimal("10"), new BigDecimal("20.5"));
        assertThat(ImpactScore.classify(5, 1, 1, 1, strict)).isEqualTo(ImpactLevel.HIGH);   // 24.25
        assertThat(ImpactScore.classify(21, 1, 1, 1, strict)).isEqualTo(ImpactLevel.LOW);   // 85/20+3 = 7.25
        assertThat(ImpactScore.classify(11, 1, 1, 1, strict)).isEqualTo(ImpactLevel.MEDIUM); // 8.5+3 = 11.5
    }

    @Test
    void invalidThresholdsAreRejected() {
        assertThatThrownBy(() -> ImpactThresholds.of(0, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImpactThresholds.of(-5, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImpactThresholds.of(60, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImpactThresholds.of(50, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImpactThresholds.of(30, 100.5)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ImpactThresholds.of(30, 100).high()).isEqualByComparingTo("100");
    }

    @Test
    void inconsistentInputsAreRejected() {
        assertThatThrownBy(() -> ImpactScore.score(5, 3, 2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImpactScore.score(3, 1, 3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImpactScore.score(5, 0, 2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImpactScore.score(5, 1, 2, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImpactScore.score(5, 0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void displayRoundingIsHalfUpToOneDecimal() {
        assertThat(ImpactScore.round(68.49999999999999)).isEqualByComparingTo("68.5");
        assertThat(ImpactScore.round(33.35)).isEqualByComparingTo("33.4");
        assertThat(ImpactScore.round(76.04)).isEqualByComparingTo("76.0");
        assertThat(ImpactScore.round(0.0)).isEqualByComparingTo("0.0");
    }

    @Test
    void unknownSelectionIsRejected() {
        DependencyGraph g = graph(List.of("a"));
        assertThatThrownBy(() -> ImpactCalculator.compute(g, "b", ImpactThresholds.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
