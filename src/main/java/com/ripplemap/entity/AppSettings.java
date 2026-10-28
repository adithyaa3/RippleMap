package com.ripplemap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/** Single-row table of user-editable settings (id = 1). */
@Entity
@Table(name = "app_settings")
public class AppSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "low_threshold", nullable = false, precision = 6, scale = 2)
    private BigDecimal lowThreshold;

    @Column(name = "high_threshold", nullable = false, precision = 6, scale = 2)
    private BigDecimal highThreshold;

    @Column(name = "include_inferred", nullable = false)
    private boolean includeInferred;

    @Column(name = "explorer_node_limit", nullable = false)
    private int explorerNodeLimit;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AppSettings() {
    }

    public void update(BigDecimal low, BigDecimal high, boolean inferred, int nodeLimit, Instant now) {
        this.lowThreshold = low;
        this.highThreshold = high;
        this.includeInferred = inferred;
        this.explorerNodeLimit = nodeLimit;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getLowThreshold() {
        return lowThreshold;
    }

    public BigDecimal getHighThreshold() {
        return highThreshold;
    }

    public boolean isIncludeInferred() {
        return includeInferred;
    }

    public int getExplorerNodeLimit() {
        return explorerNodeLimit;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
