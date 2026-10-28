package com.ripplemap.service;

import com.ripplemap.config.RippleMapProperties;
import com.ripplemap.dto.ProjectDtos;
import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisTrigger;
import com.ripplemap.entity.Project;
import com.ripplemap.entity.SourceImport;
import com.ripplemap.entity.SourceType;
import com.ripplemap.exception.ApiException;
import com.ripplemap.importing.ArchiveRejectedException;
import com.ripplemap.importing.SafeZipExtractor;
import com.ripplemap.repository.ProjectRepository;
import com.ripplemap.repository.SourceImportRepository;
import com.ripplemap.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/**
 * ZIP import workflow: stream the upload to a temporary file (bounded), extract only .java files into a new import
 * directory with {@link SafeZipExtractor}, then record the project/version and queue an analysis. Every failure
 * removes the temporary file and the partially extracted directory.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private record StoredArchive(String storageName, Path directory, String originalFilename, long archiveBytes,
                                 String sha256, SafeZipExtractor.Result extraction) {
    }

    private final ProjectRepository projects;
    private final SourceImportRepository imports;
    private final AnalysisService analysisService;
    private final ProjectService projectService;
    private final StorageService storage;
    private final RippleMapProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ImportService(ProjectRepository projects, SourceImportRepository imports, AnalysisService analysisService,
                         ProjectService projectService, StorageService storage, RippleMapProperties properties,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.projects = projects;
        this.imports = imports;
        this.analysisService = analysisService;
        this.projectService = projectService;
        this.storage = storage;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public ProjectDtos.ImportResponse importProject(MultipartFile file, String rawName, String rawDescription,
                                                    boolean includeTests) {
        String name = InputValidation.projectName(rawName);
        String description = InputValidation.description(rawDescription);
        if (projects.existsByNameKey(Project.nameKey(name))) {
            throw ApiException.conflict("PROJECT_NAME_TAKEN", "A project named '" + name + "' already exists");
        }
        StoredArchive archive = store(file);
        SourceImport version;
        try {
            version = tx.execute(status -> {
                Project project = projects.save(new Project(name, description, SourceType.ZIP_UPLOAD, null,
                        includeTests, clock.instant()));
                return imports.save(newVersion(project, 1, archive, null));
            });
        } catch (DataIntegrityViolationException e) {
            storage.deleteImportDir(archive.storageName());
            throw ApiException.conflict("PROJECT_NAME_TAKEN", "A project named '" + name + "' already exists");
        } catch (RuntimeException e) {
            storage.deleteImportDir(archive.storageName());
            throw e;
        }
        long projectId = version.getProject().getId();
        Analysis analysis = analysisService.request(projectId, version.getId(), includeTests, AnalysisTrigger.IMPORT);
        log.info("Imported project {} ({} Java files)", projectId, archive.extraction().javaFileCount());
        return new ProjectDtos.ImportResponse(projectService.summary(projectId), DtoMapper.version(version),
                DtoMapper.analysis(analysis));
    }

    public ProjectDtos.ImportResponse importVersion(long projectId, MultipartFile file, String rawLabel,
                                                    Boolean includeTests) {
        String label = InputValidation.label(rawLabel);
        Project project = projects.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project " + projectId + " was not found"));
        if (project.getSourceType() != SourceType.ZIP_UPLOAD) {
            throw ApiException.conflict("NOT_A_ZIP_PROJECT",
                    "Local-directory projects are updated by rescanning the directory, not by uploading a version");
        }
        if (analysisService.isActive(projectId)) {
            throw ApiException.conflict("ANALYSIS_IN_PROGRESS",
                    "Wait for the current analysis of this project to finish before uploading a new version");
        }
        StoredArchive archive = store(file);
        SourceImport version;
        try {
            version = tx.execute(status -> {
                Project managed = projects.findById(projectId).orElseThrow(() ->
                        ApiException.notFound("PROJECT_NOT_FOUND", "Project " + projectId + " was not found"));
                managed.touch(clock.instant());
                return imports.save(newVersion(managed, imports.maxVersionNumber(projectId) + 1, archive, label));
            });
        } catch (RuntimeException e) {
            storage.deleteImportDir(archive.storageName());
            throw e;
        }
        Analysis analysis = analysisService.request(projectId, version.getId(), includeTests, AnalysisTrigger.NEW_VERSION);
        return new ProjectDtos.ImportResponse(projectService.summary(projectId), DtoMapper.version(version),
                DtoMapper.analysis(analysis));
    }

    private SourceImport newVersion(Project project, int number, StoredArchive archive, String label) {
        SafeZipExtractor.Result x = archive.extraction();
        return new SourceImport(project, number, archive.originalFilename(), label, archive.archiveBytes(),
                archive.sha256(), x.entryCount(), x.javaFileCount(), x.extractedBytes(), archive.storageName(),
                clock.instant());
    }

    private StoredArchive store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.validation("file", "Choose a ZIP file to upload");
        }
        RippleMapProperties.Import limits = properties.getImport();
        long maxUpload = limits.getMaxUploadSize().toBytes();
        if (file.getSize() > maxUpload) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE",
                    "The file exceeds the upload limit of " + limits.getMaxUploadSize().toMegabytes() + " MB");
        }
        Path temp = null;
        StorageService.NewImportDir dir = null;
        try {
            temp = storage.newTempFile(".zip");
            MessageDigest digest = Hashing.sha256();
            long size = copyBounded(file, temp, maxUpload, digest);
            dir = storage.newImportDir();
            SafeZipExtractor.Result result = SafeZipExtractor.extract(temp, dir.path(), new SafeZipExtractor.Limits(
                    limits.getMaxExpandedSize().toBytes(), limits.getMaxEntrySize().toBytes(), limits.getMaxEntries(),
                    Math.min(limits.getMaxJavaFiles(), properties.getAnalysis().getMaxJavaFiles()),
                    limits.getMaxPathDepth(), limits.getMaxPathLength(), limits.getMaxCompressionRatio()));
            long stored = imports.totalExtractedBytes();
            if (stored + result.extractedBytes() > limits.getMaxStoredSize().toBytes()) {
                throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "STORAGE_LIMIT_REACHED",
                        "Stored imports would exceed the configured limit of " + limits.getMaxStoredSize().toMegabytes()
                                + " MB. Delete projects you no longer need.");
            }
            StoredArchive archive = new StoredArchive(dir.storageName(), dir.path(),
                    InputValidation.fileName(file.getOriginalFilename()), size, HexFormat.of().formatHex(digest.digest()),
                    result);
            dir = null; // ownership passes to the caller
            return archive;
        } catch (ArchiveRejectedException e) {
            HttpStatus status = e.reason() == ArchiveRejectedException.Reason.UPLOAD_TOO_LARGE
                    ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.UNPROCESSABLE_ENTITY;
            throw new ApiException(status, "ARCHIVE_REJECTED_" + e.reason().name(), e.getMessage());
        } catch (IOException e) {
            log.warn("Upload could not be stored: {}", e.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_FAILED",
                    "The upload could not be stored on the server");
        } finally {
            storage.deleteQuietly(temp);
            if (dir != null) {
                storage.deleteImportDir(dir.storageName());
            }
        }
    }

    private static long copyBounded(MultipartFile file, Path target, long max, MessageDigest digest)
            throws IOException, ArchiveRejectedException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = file.getInputStream();
             OutputStream out = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > max) {
                    throw new ArchiveRejectedException(ArchiveRejectedException.Reason.UPLOAD_TOO_LARGE,
                            "The file exceeds the upload limit of " + (max / (1024 * 1024)) + " MB");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return total;
    }
}
