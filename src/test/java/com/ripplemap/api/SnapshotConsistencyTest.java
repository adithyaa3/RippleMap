package com.ripplemap.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.ripplemap.config.StartupMaintenance;
import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisStatus;
import com.ripplemap.entity.AnalysisTrigger;
import com.ripplemap.entity.Project;
import com.ripplemap.repository.AnalysisRepository;
import com.ripplemap.repository.ProjectRepository;
import com.ripplemap.repository.SnapshotStore;
import com.ripplemap.repository.SourceImportRepository;
import com.ripplemap.service.StorageService;
import com.ripplemap.testsupport.IntegrationTestBase;
import com.ripplemap.testsupport.ZipFixtures;
import com.ripplemap.util.FileTrees;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/** Snapshot atomicity and failure recovery. */
class SnapshotConsistencyTest extends IntegrationTestBase {

    /** This class runs in its own application context (spy bean), so it also gets its own data directory. */
    @DynamicPropertySource
    static void ownDataDir(DynamicPropertyRegistry registry) throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ripplemap-consistency-");
        registry.add("ripplemap.data-dir", dir::toString);
    }

    @MockitoSpyBean
    SnapshotStore store;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AnalysisRepository analyses;
    @Autowired
    ProjectRepository projects;
    @Autowired
    SourceImportRepository imports;
    @Autowired
    StorageService storage;
    @Autowired
    StartupMaintenance maintenance;
    @Autowired
    TransactionTemplate tx;

    private long rows(String table, long analysisId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE analysis_id = ?", Long.class, analysisId);
        return count == null ? 0 : count;
    }

    @Test
    void aFailureWhileWritingLeavesNoPartialSnapshotAndKeepsTheLastCompletedOne() throws Exception {
        long[] first = importFixture("chain");
        long projectId = first[0];
        assertThat(rows("code_entity", first[1])).isPositive();

        // entities are written first; the relationship write fails afterwards inside the same transaction
        doThrow(new IllegalStateException("simulated disk failure")).when(store).insertRelationships(anyLong(), anyCollection());
        try {
            long second = read(postJson("/api/projects/" + projectId + "/analyze", Map.of())).path("id").asLong();
            JsonNode failed = awaitAnalysis(projectId, second);
            assertThat(failed.path("status").asText()).isEqualTo("FAILED");
            assertThat(failed.path("errorMessage").asText()).contains("failed unexpectedly").doesNotContain("simulated");
            assertThat(rows("code_entity", second)).isZero();
            assertThat(rows("dependency_edge", second)).isZero();
            assertThat(rows("source_file", second)).isZero();
            assertThat(rows("diagnostic", second)).isZero();
        } finally {
            doCallRealMethod().when(store).insertRelationships(anyLong(), anyCollection());
        }
        // the previous snapshot is still the default and unchanged
        JsonNode graph = getJson("/api/projects/" + projectId + "/graph");
        assertThat(graph.path("analysisId").asLong()).isEqualTo(first[1]);
        assertThat(graph.path("edges")).hasSize(2);
        JsonNode detail = getJson("/api/projects/" + projectId);
        assertThat(detail.path("project").path("latestAnalysis").path("status").asText()).isEqualTo("FAILED");
        assertThat(detail.path("project").path("defaultAnalysisId").asLong()).isEqualTo(first[1]);
    }

    @Test
    void missingStoredSourcesFailTheAnalysisWithoutTouchingEarlierSnapshots() throws Exception {
        long[] first = importFixture("diamond");
        long projectId = first[0];
        String storageDir = imports.findStorageDirsByProject(projectId).get(0);
        FileTrees.deleteRecursively(storage.importDir(storageDir));
        long second = read(postJson("/api/projects/" + projectId + "/analyze", Map.of())).path("id").asLong();
        JsonNode failed = awaitAnalysis(projectId, second);
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("errorMessage").asText()).contains("missing");
        assertThat(getJson("/api/projects/" + projectId + "/graph").path("analysisId").asLong()).isEqualTo(first[1]);
        assertThat(read(postJson("/api/projects/" + projectId + "/impact-analysis", Map.of("nodeId", "com.acme.diamond.D")))
                .path("transitiveCount").asInt()).isEqualTo(3);
    }

    @Test
    void interruptedAnalysesAreMarkedFailedAtStartup() {
        long projectId = tx.execute(status -> projects.save(new Project(unique("Interrupted"), null,
                com.ripplemap.entity.SourceType.ZIP_UPLOAD, null, false, Instant.now())).getId());
        long[] ids = tx.execute(status -> {
            Project project = projects.findById(projectId).orElseThrow();
            Analysis pending = analyses.save(new Analysis(project, null, 1, AnalysisTrigger.IMPORT, false, "JAVA_21", Instant.now()));
            Analysis running = analyses.save(new Analysis(project, null, 2, AnalysisTrigger.REANALYZE, false, "JAVA_21", Instant.now()));
            running.markRunning(Instant.now());
            return new long[]{pending.getId(), running.getId()};
        });
        maintenance.afterSingletonsInstantiated();
        tx.executeWithoutResult(status -> {
            for (long id : ids) {
                Analysis a = analyses.findById(id).orElseThrow();
                assertThat(a.getStatus()).isEqualTo(AnalysisStatus.FAILED);
                assertThat(a.getErrorMessage()).startsWith("Interrupted");
                assertThat(a.getFinishedAt()).isNotNull();
            }
        });
    }

    @Test
    void finishedSnapshotsCannotBeModified() throws Exception {
        long[] ids = importFixture("cycle");
        tx.executeWithoutResult(status -> {
            Analysis analysis = analyses.findById(ids[1]).orElseThrow();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> analysis.markRunning(Instant.now()))
                    .isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> analysis.markFailed("x", Instant.now()))
                    .isInstanceOf(IllegalStateException.class);
        });
        // a new version upload never alters the rows of the existing snapshot
        long entitiesBefore = rows("code_entity", ids[1]);
        long edgesBefore = rows("dependency_edge", ids[1]);
        long v2 = read(uploadVersion(ids[0], ZipFixtures.zip("diamond"), null)).path("analysis").path("id").asLong();
        awaitAnalysis(ids[0], v2);
        assertThat(rows("code_entity", ids[1])).isEqualTo(entitiesBefore);
        assertThat(rows("dependency_edge", ids[1])).isEqualTo(edgesBefore);
    }
}
