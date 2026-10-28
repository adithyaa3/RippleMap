package com.ripplemap.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class SettingsDtos {

    private SettingsDtos() {
    }

    public record Settings(BigDecimal lowThreshold, BigDecimal highThreshold, boolean includeInferred,
                           int explorerNodeLimit, Instant updatedAt) {
    }

    public record UpdateSettingsRequest(
            @NotNull(message = "Enter the low/medium threshold")
            @DecimalMin(value = "0", inclusive = false, message = "The low/medium threshold must be greater than 0")
            @DecimalMax(value = "100", message = "The low/medium threshold must not exceed 100")
            @Digits(integer = 3, fraction = 2, message = "Use at most two decimal places")
            BigDecimal lowThreshold,
            @NotNull(message = "Enter the medium/high threshold")
            @DecimalMin(value = "0", inclusive = false, message = "The medium/high threshold must be greater than 0")
            @DecimalMax(value = "100", message = "The medium/high threshold must not exceed 100")
            @Digits(integer = 3, fraction = 2, message = "Use at most two decimal places")
            BigDecimal highThreshold,
            @NotNull(message = "Choose whether inferred relationships are included") Boolean includeInferred,
            @NotNull(message = "Enter a node limit")
            @Min(value = 25, message = "The node limit must be at least 25")
            @Max(value = 2000, message = "The node limit must not exceed 2000")
            Integer explorerNodeLimit) {
    }

    public record Limit(String name, String value, String property) {
    }

    public record SystemInfo(String applicationVersion, String analyzerVersion, String javaVersion,
                             String javaVendor, String operatingSystem, String dataDirectory, String languageLevel,
                             boolean localScanEnabled, List<String> localScanRoots, List<Limit> limits) {
    }
}
