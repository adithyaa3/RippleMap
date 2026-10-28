package com.ripplemap.service;

import com.ripplemap.config.RippleMapProperties;
import com.ripplemap.dto.ProjectDtos;
import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisStatus;
import com.ripplemap.entity.AnalysisTrigger;
import com.ripplemap.entity.Project;
import com.ripplemap.entity.SourceType;
import com.ripplemap.exception.ApiException;
import com.ripplemap.importing.LocalDirectoryPolicy;
import com.ripplemap.repository.AnalysisRepository;
import com.ripplemap.repository.ProjectRepository;
import com.ripplemap.repository.SourceImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final List<AnalysisStatus> ACTIVE = List.of(AnalysisStatus.PENDING, AnalysisStatus.RUNNING);

    private final ProjectRepository projects;
    private final SourceImportRepository imports;
    private final AnalysisRepository analyses;
    private final AnalysisService analysisService;
    private final SnapshotService snapshots;
    private final StorageService storage;
    private final LocalDirectoryPolicy localPolicy;
    private final RippleMapProperties properties;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public ProjectService(ProjectRepository projects, SourceImportRepository imports, AnalysisRepository analyses,
                          AnalysisService analysisService, SnapshotService snapshots, StorageService storage,
                          LocalDirectoryPolicy localPolicy, RippleMapProperties properties,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.projects = projects;
        this.imports = imports;
        this.analyses = analyses;
        this.analysisService = analysisService;
        this.snapshots = snapshots;
        this.storage = storage;
        this.localPolicy = localPolicy;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    public List<ProjectDtos.ProjectSummary> list() {
        return readTx.execute(status -> {
            List<ProjectDtos.ProjectSummary> result = new ArrayList<>();
            for (Project project : projects.findAllByOrderByUpdatedAtDesc()) {
                result.add(toSummary(project));
            }
            return result;
        });
    }

    public ProjectDtos.ProjectSummary summary(long projectId) {
        return readTx.execute(status -> toSummary(find(projectId)));
    }

    public ProjectDtos.ProjectDetail detail(long projectId) {
        return readTx.execute(status -> {
            Project project = find(projectId);
            return new ProjectDtos.ProjectDetail(toSummary(project),
                    imports.findByProjectIdOrderByVersionNumberDesc(projectId).stream().map(DtoMapper::version).toList(),
                    analyses.countByProjectId(projectId), analyses.existsByProjectIdAndStatusIn(projectId, ACTIVE),
                    properties.getAnalysis().getMaxSnapshotsPerProject());
        });
    }

    public ProjectDtos.ProjectSummary update(long projectId, ProjectDtos.UpdateProjectRequest request) {
        try {
            tx.executeWithoutResult(status -> {
                Project project = find(projectId);
                if (request.name() != null) {
                    String name = InputValidation.projectName(request.name());
                    projects.findByNameKey(Project.nameKey(name)).ifPresent(other -> {
                        if (!other.getId().equals(projectId)) {
                            throw ApiException.conflict("PROJECT_NAME_TAKEN",
                                    "A project named '" + name + "' already exists");
                        }
                    });
                    project.rename(name);
                }
                if (request.description() != null) {
                    project.setDescription(InputValidation.description(request.description()));
                }
                if (request.includeTests() != null) {
                    project.setIncludeTests(request.includeTests());
                }
                project.touch(clock.instant());
            });
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("PROJECT_NAME_TAKEN", "Another project already uses this name");
        }
        return summary(projectId);
    }

    public ProjectDtos.ImportResponse createLocal(ProjectDtos.CreateLocalProjectRequest request) {
        Path directory = localPolicy.authorize(request.path());
        String name = InputValidation.projectName(request.name());
        String description = InputValidation.description(request.description());
        Project project;
        try {
            project = tx.execute(status -> {
                if (projects.existsByNameKey(Project.nameKey(name))) {
                    throw ApiException.conflict("PROJECT_NAME_TAKEN", "A project named '" + name + "' already exists");
                }
                return projects.save(new Project(name, description, SourceType.LOCAL_DIRECTORY, directory.toString(),
                        request.includeTests(), clock.instant()));
            });
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("PROJECT_NAME_TAKEN", "A project named '" + name + "' already exists");
        }
        Analysis analysis = analysisService.request(project.getId(), null, request.includeTests(), AnalysisTrigger.IMPORT);
        return new ProjectDtos.ImportResponse(summary(project.getId()), null, DtoMapper.analysis(analysis));
    }

    /** Starts a re-analysis (ZIP: of a stored version) or a rescan (local directory). */
    public Analysis analyze(long projectId, ProjectDtos.AnalyzeRequest request) {
        Project project = readTx.execute(status -> find(projectId));
        AnalysisTrigger trigger = project.getSourceType() == SourceType.LOCAL_DIRECTORY
                ? AnalysisTrigger.RESCAN : AnalysisTrigger.REANALYZE;
        return analysisService.request(projectId, request == null ? null : request.versionId(),
                request == null ? null : request.includeTests(), trigger);
    }

    /** Deletes a project with all versions, snapshots and stored sources. Refused while an analysis is active. */
    public void delete(long projectId) {
        List<String> storageDirs;
        synchronized (analysisService.lockFor(projectId)) {
            storageDirs = tx.execute(status -> {
                Project project = find(projectId);
                if (analyses.existsByProjectIdAndStatusIn(projectId, ACTIVE)) {
                    throw ApiException.conflict("ANALYSIS_IN_PROGRESS",
                            "The project cannot be deleted while an analysis is pending or running; cancel it first");
                }
                List<String> dirs = imports.findStorageDirsByProject(projectId);
                projects.delete(project);
                return dirs;
            });
        }
        for (String dir : storageDirs) {
            storage.deleteImportDir(dir);
        }
        snapshots.evictAll();
        log.info("Deleted project {} and {} stored version(s)", projectId, storageDirs.size());
    }

    private Project find(long projectId) {
        return projects.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project " + projectId + " was not found"));
    }

    private ProjectDtos.ProjectSummary toSummary(Project project) {
        long versions = imports.countByProjectId(project.getId());
        Analysis latest = analyses.findFirstByProjectIdOrderBySequenceNumberDesc(project.getId()).orElse(null);
        Long defaultId = analyses.findFirstByProjectIdAndStatusInOrderBySequenceNumberDesc(project.getId(),
                SnapshotService.WITH_RESULTS).map(Analysis::getId).orElse(null);
        return DtoMapper.project(project, versions, latest, defaultId);
    }
}
