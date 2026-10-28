package com.ripplemap.analysis.diagnostics;

import com.ripplemap.util.TextSanitizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Collects diagnostics with exact per-category counts and bounded storage.
 * Not thread-safe; one collector belongs to one analysis run.
 */
public final class DiagnosticCollector {

    public static final int MAX_MESSAGE_LENGTH = 500;
    public static final int MAX_SYMBOL_LENGTH = 200;

    private final int maxStored;
    private final List<Diagnostic> stored = new ArrayList<>();
    private final Map<DiagnosticCategory, Integer> categoryCounts = new EnumMap<>(DiagnosticCategory.class);
    private boolean truncated;

    public DiagnosticCollector(int maxStored) {
        if (maxStored < 0) {
            throw new IllegalArgumentException("maxStored must not be negative");
        }
        this.maxStored = maxStored;
    }

    public void add(DiagnosticCategory category, String filePath, int line, String entityId, String symbol, String message) {
        categoryCounts.merge(category, 1, Integer::sum);
        if (stored.size() >= maxStored) {
            truncated = true;
            return;
        }
        stored.add(new Diagnostic(
                category.severity(),
                category,
                filePath,
                Math.max(line, 0),
                entityId,
                symbol == null ? null : TextSanitizer.singleLine(symbol, MAX_SYMBOL_LENGTH),
                TextSanitizer.singleLine(message == null ? category.description() : message, MAX_MESSAGE_LENGTH)));
    }

    public List<Diagnostic> stored() {
        return Collections.unmodifiableList(stored);
    }

    public Map<DiagnosticCategory, Integer> categoryCounts() {
        return Collections.unmodifiableMap(categoryCounts);
    }

    public int count(DiagnosticCategory category) {
        return categoryCounts.getOrDefault(category, 0);
    }

    public int count(Severity severity) {
        int total = 0;
        for (Map.Entry<DiagnosticCategory, Integer> entry : categoryCounts.entrySet()) {
            if (entry.getKey().severity() == severity) {
                total += entry.getValue();
            }
        }
        return total;
    }

    public int total() {
        int total = 0;
        for (int value : categoryCounts.values()) {
            total += value;
        }
        return total;
    }

    public boolean truncated() {
        return truncated;
    }
}
