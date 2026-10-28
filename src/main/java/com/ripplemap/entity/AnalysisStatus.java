package com.ripplemap.entity;

public enum AnalysisStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED;

    /** Whether the snapshot has persisted results that may be displayed. */
    public boolean hasResults() {
        return this == COMPLETED || this == PARTIAL;
    }

    public boolean isActive() {
        return this == PENDING || this == RUNNING;
    }
}
