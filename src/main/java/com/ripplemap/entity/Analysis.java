package com.ripplemap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;

/**
 * One analysis snapshot. Lifecycle: PENDING -&gt; RUNNING -&gt; COMPLETED | PARTIAL | FAILED.
 * Once finished, a snapshot is never modified (only deleted with its project or explicitly by the user).
 */
@Entity
@Table(name = "analysis")
public class Analysis {

    public static final String ANALYZER_VERSION = "1.0";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_import_id")
    private SourceImport sourceImport;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private AnalysisTrigger trigger;

    @Column(name = "include_tests", nullable = false)
    private boolean includeTests;

    @Column(name = "language_level", length = 20)
    private String languageLevel;

    @Column(name = "analyzer_version", nullable = false, length = 20)
    private String analyzerVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** Current stage while running (e.g. "Parsing 120 source files"). */
    @Column(length = 200)
    private String stage;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "partial_reasons", length = 1000)
    private String partialReasons;

    @Column(name = "source_fingerprint", length = 64)
    private String sourceFingerprint;

    @Embedded
    private AnalysisCounts counts = new AnalysisCounts();

    @Version
    private long version;

    protected Analysis() {
    }

    public Analysis(Project project, SourceImport sourceImport, int sequenceNumber, AnalysisTrigger trigger,
                    boolean includeTests, String languageLevel, Instant now) {
        this.project = project;
        this.sourceImport = sourceImport;
        this.sequenceNumber = sequenceNumber;
        this.trigger = trigger;
        this.includeTests = includeTests;
        this.languageLevel = languageLevel;
        this.analyzerVersion = ANALYZER_VERSION;
        this.status = AnalysisStatus.PENDING;
        this.createdAt = now;
    }

    public void markRunning(Instant now) {
        requireStatus(AnalysisStatus.PENDING);
        this.status = AnalysisStatus.RUNNING;
        this.startedAt = now;
        this.stage = "Starting";
    }

    public void updateStage(String newStage) {
        if (status == AnalysisStatus.RUNNING) {
            this.stage = truncate(newStage, 200);
        }
    }

    public void markFinished(boolean partial, String partialReasonText, String fingerprint, AnalysisCounts finalCounts,
                             Instant now) {
        requireStatus(AnalysisStatus.RUNNING);
        this.status = partial ? AnalysisStatus.PARTIAL : AnalysisStatus.COMPLETED;
        this.partialReasons = truncate(partialReasonText, 1000);
        this.sourceFingerprint = fingerprint;
        this.counts = finalCounts;
        finish(now);
    }

    public void markFailed(String message, Instant now) {
        if (!status.isActive()) {
            throw new IllegalStateException("Analysis " + id + " is already " + status);
        }
        this.status = AnalysisStatus.FAILED;
        this.errorMessage = truncate(message, 1000);
        finish(now);
    }

    private void finish(Instant now) {
        this.finishedAt = now;
        this.stage = null;
        if (startedAt != null) {
            this.durationMs = Duration.between(startedAt, now).toMillis();
        }
    }

    private void requireStatus(AnalysisStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Analysis " + id + " is " + status + ", expected " + expected);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public SourceImport getSourceImport() {
        return sourceImport;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public AnalysisTrigger getTrigger() {
        return trigger;
    }

    public boolean isIncludeTests() {
        return includeTests;
    }

    public String getLanguageLevel() {
        return languageLevel;
    }

    public String getAnalyzerVersion() {
        return analyzerVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getStage() {
        return stage;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getPartialReasons() {
        return partialReasons;
    }

    public String getSourceFingerprint() {
        return sourceFingerprint;
    }

    public AnalysisCounts getCounts() {
        return counts == null ? new AnalysisCounts() : counts;
    }
}
