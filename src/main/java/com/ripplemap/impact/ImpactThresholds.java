package com.ripplemap.impact;

import java.math.BigDecimal;

/**
 * Classification thresholds: LOW when score &lt; low, MEDIUM when low &le; score &lt; high, HIGH otherwise.
 * Valid thresholds satisfy {@code 0 < low < high <= 100}.
 */
public record ImpactThresholds(BigDecimal low, BigDecimal high) {

    public static final ImpactThresholds DEFAULT = new ImpactThresholds(new BigDecimal("30"), new BigDecimal("60"));

    public ImpactThresholds {
        if (low == null || high == null) {
            throw new IllegalArgumentException("Thresholds are required");
        }
        if (low.signum() <= 0) {
            throw new IllegalArgumentException("The low/medium threshold must be greater than 0");
        }
        if (high.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("The medium/high threshold must not exceed 100");
        }
        if (low.compareTo(high) >= 0) {
            throw new IllegalArgumentException("The low/medium threshold must be lower than the medium/high threshold");
        }
    }

    public static ImpactThresholds of(double low, double high) {
        return new ImpactThresholds(BigDecimal.valueOf(low), BigDecimal.valueOf(high));
    }
}
