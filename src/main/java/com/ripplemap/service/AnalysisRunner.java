package com.ripplemap.service;

import com.github.javaparser.ParserConfiguration;
import com.ripplemap.analysis.AnalysisInterruptedException;
import com.ripplemap.analysis.AnalysisLimitException;
import com.ripplemap.analysis.AnalysisRequest;
import com.ripplemap.analysis.JavaProjectAnalyzer;
import com.ripplemap.analysis.model.AnalysisResult;
import com.ripplemap.config.RippleMapProperties;
import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisStatus;
import com.ripplemap.entity.Project;
import com.ripplemap.entity.SourceType;
import com.ripplemap.exception.ApiException;
import com.ripplemap.importing.LocalDirectoryPolicy;
import com.ripplemap.repository.AnalysisRepository;
import com.ripplemap.repository.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Executes one analysis: PENDING -&gt; RUNNING -&gt; COMPLETED/PARTIAL (results committed) or FAILED. */
@Component
public class AnalysisRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);

    private record Job(long analysisId, Path root, boolean includeTests) {
    }

    private final AnalysisRepository analyses;
    private final SnapshotStore store;
    private final SnapshotWriter writer;
    private final StorageService storage;
    private final LocalDirectoryPolicy localPolicy;
    private final RippleMapProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Set<Long> cancelRequests = ConcurrentHashMap.newKeySet();
    private volatile boolean shuttingDown;

    public AnalysisRunner(AnalysisRepository analyses, SnapshotStore store, SnapshotWriter writer,
                          StorageService storage, LocalDirectoryPolicy localPolicy, RippleMapProperties properties,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.analyses = analyses;
        this.store = store;
        this.writer = writer;
        this.storage = storage;
        this.localPolicy = localPolicy;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void requestCancel(long analysisId) {
        cancelRequests.add(analysisId);
    }

    void markShuttingDown() {
        shuttingDown = true;
    }

    public void run(long analysisId) {
        Job job;
        try {
            job = tx.execute(status -> start(analysisId));
        } catch (IllegalStateException e) {
            log.info("Analysis {} was not started: {}", analysisId, e.getMessage());
            cancelRequests.remove(analysisId);
            return;
        } catch (ApiException e) {
            fail(analysisId, e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.error("Could not start analysis {}", analysisId, e);
            fail(analysisId, "The analysis could not be started");
            return;
        }
        if (job == null) {
            return;
        }
        RippleMapProperties.Analysis config = properties.getAnalysis();
        long started = System.nanoTime();
        try {
            AnalysisRequest request = new AnalysisRequest(job.root(), job.includeTests(),
                    ParserConfiguration.LanguageLevel.valueOf(config.getLanguageLevel()),
                    config.getMaxFileSize().toBytes(), config.getMaxJavaFiles(), config.getMaxDiagnostics(),
                    config.getMaxReferenceSamples(), config.getReferenceBudget(), clock.instant().plus(config.getTimeout()),
                    () -> cancelRequests.contains(analysisId) || shuttingDown,
                    stage -> store.updateStage(analysisId, stage));
            AnalysisResult result = new JavaProjectAnalyzer().analyze(request);
            store.updateStage(analysisId, "Computing graph metrics");
            SnapshotMetrics metrics = SnapshotMetrics.compute(result, config.getNodeMetricsLimit(), () -> {
                if (cancelRequests.contains(analysisId) || shuttingDown || Thread.currentThread().isInterrupted()) {
                    throw new AnalysisInterruptedException(AnalysisInterruptedException.Reason.CANCELLED, "cancelled");
                }
            });
            store.updateStage(analysisId, "Saving snapshot");
            writer.persist(analysisId, result, metrics, clock.instant());
            log.info("Analysis {} finished in {} ms: {} types, {} type dependencies, partial={}", analysisId,
                    (System.nanoTime() - started) / 1_000_000, metrics.typeCount(), metrics.typeDependencyPairs(),
                    result.partial());
        } catch (AnalysisInterruptedException e) {
            if (e.reason() == AnalysisInterruptedException.Reason.DEADLINE_EXCEEDED) {
                fail(analysisId, "The analysis exceeded the time limit of " + config.getTimeout().toMinutes()
                        + " minute(s). No partial results were saved.");
            } else if (shuttingDown) {
                fail(analysisId, "The analysis was interrupted because the application shut down");
            } else {
                fail(analysisId, "The analysis was cancelled");
            }
        } catch (AnalysisLimitException e) {
            fail(analysisId, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(analysisId, "The analysis was interrupted because the application shut down");
        } catch (IOException e) {
            log.warn("Analysis {} could not read its sources: {}", analysisId, e.getMessage());
            fail(analysisId, "The project sources could not be read");
        } catch (OutOfMemoryError e) {
            log.error("Analysis {} ran out of memory", analysisId);
            fail(analysisId, "The analysis ran out of memory. Analyze a smaller scope or increase the Java heap (-Xmx).");
        } catch (RuntimeException | StackOverflowError e) {
            log.error("Analysis {} failed unexpectedly", analysisId, e);
            fail(analysisId, "The analysis failed unexpectedly (" + e.getClass().getSimpleName()
                    + "). Details are in the server log.");
        } finally {
            cancelRequests.remove(analysisId);
        }
    }

    private Job start(long analysisId) {
        Analysis analysis = analyses.findById(analysisId)
                .orElseThrow(() -> new IllegalStateException("analysis no longer exists"));
        if (analysis.getStatus() != AnalysisStatus.PENDING) {
            throw new IllegalStateException("status is " + analysis.getStatus());
        }
        if (cancelRequests.contains(analysisId)) {
            analysis.markFailed("The analysis was cancelled", clock.instant());
            return null;
        }
        Project project = analysis.getProject();
        Path root;
        if (project.getSourceType() == SourceType.LOCAL_DIRECTORY) {
            root = localPolicy.authorize(project.getLocalPath());
        } else {
            if (analysis.getSourceImport() == null) {
                throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "SOURCES_MISSING",
                        "The analysis has no stored source version");
            }
            root = storage.importDir(analysis.getSourceImport().getStorageDir());
            if (!Files.isDirectory(root)) {
                throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "SOURCES_MISSING",
                        "The stored sources of this version are missing from the data directory");
            }
        }
        analysis.markRunning(clock.instant());
        return new Job(analysisId, root, analysis.isIncludeTests());
    }

    void fail(long analysisId, String message) {
        try {
            tx.executeWithoutResult(status -> analyses.findById(analysisId).ifPresent(a -> {
                if (a.getStatus().isActive()) {
                    a.markFailed(message, clock.instant());
                }
            }));
        } catch (RuntimeException e) {
            log.error("Could not mark analysis {} as failed; it will be recovered at next startup", analysisId, e);
        }
    }
}
