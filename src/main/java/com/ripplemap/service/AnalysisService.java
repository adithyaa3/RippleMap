package com.ripplemap.service;

import com.ripplemap.config.RippleMapProperties;
import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisStatus;
import com.ripplemap.entity.AnalysisTrigger;
import com.ripplemap.entity.Project;
import com.ripplemap.entity.SourceImport;
import com.ripplemap.entity.SourceType;
import com.ripplemap.exception.ApiException;
import com.ripplemap.repository.AnalysisRepository;
import com.ripplemap.repository.ProjectRepository;
import com.ripplemap.repository.SourceImportRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Schedules analyses on a bounded worker pool. At most one analysis per project is pending or running.
 * This service owns the worker pool, so it is shut down (and running analyses are marked failed) before the
 * database closes.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private static final List<AnalysisStatus> ACTIVE = List.of(AnalysisStatus.PENDING, AnalysisStatus.RUNNING);

    private final ProjectRepository projects;
    private final AnalysisRepository analyses;
    private final SourceImportRepository imports;
    private final AnalysisRunner runner;
    private final SnapshotService snapshots;
    private final RippleMapProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private final Map<Long, Object> projectLocks = new ConcurrentHashMap<>();

    public AnalysisService(ProjectRepository projects, AnalysisRepository analyses, SourceImportRepository imports,
                           AnalysisRunner runner, SnapshotService snapshots, RippleMapProperties properties,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.projects = projects;
        this.analyses = analyses;
        this.imports = imports;
        this.runner = runner;
        this.snapshots = snapshots;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        RippleMapProperties.Analysis config = properties.getAnalysis();
        AtomicInteger threads = new AtomicInteger();
        long stackSize = config.getWorkerStackSize().toBytes();
        this.executor = new ThreadPoolExecutor(config.getConcurrency(), config.getConcurrency(), 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.getQueueCapacity()),
                task -> {
                    Thread thread = new Thread(null, task, "analysis-" + threads.incrementAndGet(), stackSize);
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Creates a PENDING analysis and queues it.
     *
     * @param versionId    stored version to analyze (ZIP projects); null selects the latest version
     * @param includeTests null uses the project's default scope
     */
    public Analysis request(long projectId, Long versionId, Boolean includeTests, AnalysisTrigger trigger) {
        Analysis created;
        synchronized (projectLocks.computeIfAbsent(projectId, k -> new Object())) {
            created = tx.execute(status -> create(projectId, versionId, includeTests, trigger));
        }
        submit(created.getId());
        return tx.execute(status -> analyses.findByIdAndProjectId(created.getId(), projectId).orElseThrow());
    }

    private Analysis create(long projectId, Long versionId, Boolean includeTests, AnalysisTrigger trigger) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project " + projectId + " was not found"));
        if (analyses.existsByProjectIdAndStatusIn(projectId, ACTIVE)) {
            throw ApiException.conflict("ANALYSIS_IN_PROGRESS",
                    "An analysis of this project is already pending or running");
        }
        int max = properties.getAnalysis().getMaxSnapshotsPerProject();
        if (analyses.countByProjectId(projectId) >= max) {
            throw ApiException.conflict("SNAPSHOT_LIMIT_REACHED", "This project already has " + max
                    + " snapshots. Delete old snapshots in Analysis History before starting a new analysis.");
        }
        SourceImport source = null;
        if (project.getSourceType() == SourceType.ZIP_UPLOAD) {
            source = versionId == null
                    ? imports.findFirstByProjectIdOrderByVersionNumberDesc(projectId)
                    .orElseThrow(() -> ApiException.conflict("NO_VERSION", "The project has no stored version"))
                    : imports.findByIdAndProjectId(versionId, projectId)
                    .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND",
                            "Version " + versionId + " does not exist in project " + projectId));
        } else if (versionId != null) {
            throw ApiException.badRequest("VERSION_NOT_APPLICABLE",
                    "Local-directory projects have no stored versions; a rescan reads the current files");
        }
        boolean tests = includeTests != null ? includeTests : project.isIncludeTests();
        int sequence = analyses.maxSequenceNumber(projectId) + 1;
        Analysis analysis = new Analysis(project, source, sequence, trigger, tests,
                properties.getAnalysis().getLanguageLevel(), clock.instant());
        project.touch(clock.instant());
        return analyses.save(analysis);
    }

    private void submit(long analysisId) {
        try {
            executor.execute(() -> runner.run(analysisId));
        } catch (RejectedExecutionException e) {
            runner.fail(analysisId, "The analysis queue was full; start the analysis again later");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ANALYSIS_QUEUE_FULL",
                    "Too many analyses are waiting. Try again when the current analyses have finished.");
        }
    }

    /** Cancels a pending analysis immediately, or asks a running one to stop at its next checkpoint. */
    public Analysis cancel(long projectId, long analysisId) {
        Analysis analysis = snapshots.analysisOfProject(projectId, analysisId);
        if (!analysis.getStatus().isActive()) {
            throw ApiException.conflict("ANALYSIS_NOT_ACTIVE", "Analysis #" + analysis.getSequenceNumber()
                    + " is already " + analysis.getStatus());
        }
        runner.requestCancel(analysisId);
        if (analysis.getStatus() == AnalysisStatus.PENDING) {
            runner.fail(analysisId, "The analysis was cancelled");
        }
        return tx.execute(status -> analyses.findByIdAndProjectId(analysisId, projectId).orElseThrow());
    }

    /** Deletes one finished snapshot and its rows. */
    public void deleteSnapshot(long projectId, long analysisId) {
        synchronized (projectLocks.computeIfAbsent(projectId, k -> new Object())) {
            tx.executeWithoutResult(status -> {
                Analysis analysis = analyses.findByIdAndProjectId(analysisId, projectId)
                        .orElseThrow(() -> ApiException.notFound("ANALYSIS_NOT_FOUND",
                                "Analysis " + analysisId + " does not exist in project " + projectId));
                if (analysis.getStatus().isActive()) {
                    throw ApiException.conflict("ANALYSIS_IN_PROGRESS",
                            "A pending or running analysis cannot be deleted; cancel it first");
                }
                analyses.delete(analysis);
            });
        }
        snapshots.evict(analysisId);
    }

    public boolean isActive(long projectId) {
        return Boolean.TRUE.equals(tx.execute(status -> analyses.existsByProjectIdAndStatusIn(projectId, ACTIVE)));
    }

    /** Lock used by project deletion so that no analysis is scheduled concurrently. */
    Object lockFor(long projectId) {
        return projectLocks.computeIfAbsent(projectId, k -> new Object());
    }

    @PreDestroy
    void shutdown() {
        runner.markShuttingDown();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                log.warn("Analysis workers did not stop in time; unfinished analyses will be marked failed at next start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
