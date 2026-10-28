package com.ripplemap.service;

import com.ripplemap.config.RippleMapProperties;
import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisStatus;
import com.ripplemap.entity.Project;
import com.ripplemap.exception.ApiException;
import com.ripplemap.repository.AnalysisRepository;
import com.ripplemap.repository.ProjectRepository;
import com.ripplemap.repository.SnapshotStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves which snapshot a request refers to and loads it. A requested snapshot is always honoured (or the
 * request fails); the latest completed snapshot is used only when no snapshot was requested.
 */
@Service
public class SnapshotService {

    public static final List<AnalysisStatus> WITH_RESULTS = List.of(AnalysisStatus.COMPLETED, AnalysisStatus.PARTIAL);

    private final ProjectRepository projects;
    private final AnalysisRepository analyses;
    private final SnapshotStore store;
    private final Map<Long, SnapshotData> cache;
    private final int cacheSize;

    public SnapshotService(ProjectRepository projects, AnalysisRepository analyses, SnapshotStore store,
                           RippleMapProperties properties) {
        this.projects = projects;
        this.analyses = analyses;
        this.store = store;
        this.cacheSize = properties.getAnalysis().getCacheSize();
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
    }

    @Transactional(readOnly = true)
    public Project project(long projectId) {
        return projects.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project " + projectId + " was not found"));
    }

    /** The requested analysis of the project, or the latest one with results when {@code analysisId} is null. */
    @Transactional(readOnly = true)
    public Analysis resolve(long projectId, Long analysisId) {
        project(projectId);
        if (analysisId != null) {
            Analysis analysis = analyses.findByIdAndProjectId(analysisId, projectId)
                    .orElseThrow(() -> ApiException.notFound("ANALYSIS_NOT_FOUND",
                            "Analysis " + analysisId + " does not exist in project " + projectId));
            if (!analysis.getStatus().hasResults()) {
                throw ApiException.conflict("ANALYSIS_NOT_AVAILABLE", "Analysis #" + analysis.getSequenceNumber()
                        + " is " + analysis.getStatus() + " and has no results to show");
            }
            return analysis;
        }
        return analyses.findFirstByProjectIdAndStatusInOrderBySequenceNumberDesc(projectId, WITH_RESULTS)
                .orElseThrow(() -> ApiException.conflict("NO_COMPLETED_ANALYSIS",
                        "This project has no completed analysis yet"));
    }

    /** Any analysis of the project regardless of status (for status displays). */
    @Transactional(readOnly = true)
    public Analysis analysisOfProject(long projectId, long analysisId) {
        project(projectId);
        return analyses.findByIdAndProjectId(analysisId, projectId)
                .orElseThrow(() -> ApiException.notFound("ANALYSIS_NOT_FOUND",
                        "Analysis " + analysisId + " does not exist in project " + projectId));
    }

    /** Loads the snapshot data of a finished analysis (cached). */
    public SnapshotData load(Analysis analysis) {
        if (!analysis.getStatus().hasResults()) {
            throw new IllegalArgumentException("Analysis has no results");
        }
        long id = analysis.getId();
        synchronized (cache) {
            SnapshotData cached = cache.get(id);
            if (cached != null) {
                return cached;
            }
        }
        SnapshotData data = new SnapshotData(id, analysis.getProject().getId(), store.loadEntities(id),
                store.loadRelationships(id));
        synchronized (cache) {
            cache.put(id, data);
            while (cache.size() > cacheSize) {
                Long eldest = cache.keySet().iterator().next();
                cache.remove(eldest);
            }
        }
        return data;
    }

    public void evict(long analysisId) {
        synchronized (cache) {
            cache.remove(analysisId);
        }
    }

    public void evictAll() {
        synchronized (cache) {
            cache.clear();
        }
    }
}
