package com.ripplemap.impact;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The deterministic structural impact score.
 *
 * <pre>
 * N = eligible entities in the layer, D = distinct direct dependents,
 * T = distinct transitive dependents (including D, excluding the selected entity),
 * H = maximum shortest reverse-dependency distance.
 *
 * DirectSignal = D / (N - 1)      ReachSignal = T / (N - 1)      DepthSignal = min(H, 5) / 5
 * Score = 100 * (0.45 * DirectSignal + 0.40 * ReachSignal + 0.15 * DepthSignal)
 *       = (45 * D + 40 * T) / (N - 1) + 3 * min(H, 5)            (algebraically identical)
 * </pre>
 * The second form is used for computation: it needs a single division, so golden values such as 68.5
 * and 76.0 are exact. Classification compares exact rationals, so boundary scores are never misclassified
 * by floating-point error. The score is 0 when N &le; 1 or there are no dependents.
 */
public final class ImpactScore {

    public static final double DIRECT_WEIGHT = 0.45;
    public static final double REACH_WEIGHT = 0.40;
    public static final double DEPTH_WEIGHT = 0.15;
    public static final int DEPTH_CAP = 5;

    private ImpactScore() {
    }

    public static double directSignal(int n, int d) {
        return n <= 1 ? 0.0 : (double) d / (n - 1);
    }

    public static double reachSignal(int n, int t) {
        return n <= 1 ? 0.0 : (double) t / (n - 1);
    }

    public static double depthSignal(int n, int t, int h) {
        return n <= 1 || t == 0 ? 0.0 : Math.min(h, DEPTH_CAP) / (double) DEPTH_CAP;
    }

    public static double score(int n, int d, int t, int h) {
        validate(n, d, t, h);
        if (n <= 1 || t == 0) {
            return 0.0;
        }
        double value = (45.0 * d + 40.0 * t) / (n - 1) + 3.0 * Math.min(h, DEPTH_CAP);
        return Math.max(0.0, Math.min(100.0, value));
    }

    /** {@code 100 * T / (N - 1)}; 0 when N &le; 1. */
    public static double percentage(int n, int t) {
        if (n <= 1) {
            return 0.0;
        }
        return Math.min(100.0, 100.0 * t / (n - 1));
    }

    /** Classifies using exact rational comparison of {@code score} against the thresholds. */
    public static ImpactLevel classify(int n, int d, int t, int h, ImpactThresholds thresholds) {
        validate(n, d, t, h);
        if (n <= 1 || t == 0) {
            return ImpactLevel.LOW; // score 0 is always below the low threshold, which must be > 0
        }
        // score >= x  <=>  45D + 40T + 3*min(H,5)*(N-1) >= x * (N-1)
        BigDecimal denominator = BigDecimal.valueOf(n - 1L);
        BigDecimal numerator = BigDecimal.valueOf(45L * d + 40L * t + 3L * Math.min(h, DEPTH_CAP) * (n - 1L));
        if (numerator.compareTo(thresholds.high().multiply(denominator)) >= 0) {
            return ImpactLevel.HIGH;
        }
        if (numerator.compareTo(thresholds.low().multiply(denominator)) >= 0) {
            return ImpactLevel.MEDIUM;
        }
        return ImpactLevel.LOW;
    }

    /** Display rounding: one decimal place, half-up. Computation always uses the unrounded value. */
    public static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private static void validate(int n, int d, int t, int h) {
        if (n < 0 || d < 0 || t < 0 || h < 0) {
            throw new IllegalArgumentException("Impact inputs must not be negative");
        }
        if (d > t) {
            throw new IllegalArgumentException("Direct dependents cannot exceed transitive dependents");
        }
        if (n > 0 && t > n - 1) {
            throw new IllegalArgumentException("Transitive dependents cannot exceed N - 1");
        }
        if (t == 0 && (d != 0 || h != 0)) {
            throw new IllegalArgumentException("Without dependents, D and H must be 0");
        }
        // every BFS level 1..H holds at least one node and level 1 holds the D direct dependents
        if (t > 0 && (d < 1 || h < 1 || h > t - d + 1)) {
            throw new IllegalArgumentException("Inconsistent impact inputs: D=" + d + ", T=" + t + ", H=" + h);
        }
    }
}
