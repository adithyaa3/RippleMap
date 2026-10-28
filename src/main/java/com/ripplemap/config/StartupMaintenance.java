package com.ripplemap.config;

import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisStatus;
import com.ripplemap.repository.AnalysisRepository;
import com.ripplemap.repository.SourceImportRepository;
import com.ripplemap.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Runs once at startup after all beans exist but before the web server accepts requests:
 * analyses left PENDING/RUNNING by a previous process are marked FAILED (their results were never committed),
 * and import directories or temporary uploads that no database row references are removed.
 */
@Component
public class StartupMaintenance implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(StartupMaintenance.class);

    private final AnalysisRepository analyses;
    private final SourceImportRepository imports;
    private final StorageService storage;
    private final TransactionTemplate tx;
    private final Clock clock;

    public StartupMaintenance(AnalysisRepository analyses, SourceImportRepository imports, StorageService storage,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.analyses = analyses;
        this.imports = imports;
        this.storage = storage;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Integer recovered = tx.execute(status -> {
            List<Analysis> stale = analyses.findByStatusIn(List.of(AnalysisStatus.PENDING, AnalysisStatus.RUNNING));
            for (Analysis analysis : stale) {
                analysis.markFailed("Interrupted: the application stopped before this analysis finished. "
                        + "No partial results were saved; start the analysis again.", clock.instant());
            }
            return stale.size();
        });
        if (recovered != null && recovered > 0) {
            log.warn("Marked {} interrupted analysis/analyses as FAILED", recovered);
        }
        List<String> referenced = tx.execute(status -> imports.findAllStorageDirs());
        int removed = storage.removeOrphans(referenced == null ? List.of() : referenced);
        if (removed > 0) {
            log.info("Removed {} orphaned import/temporary entries", removed);
        }
        log.info("RippleMap data directory: {}", storage.dataDir());
    }
}
