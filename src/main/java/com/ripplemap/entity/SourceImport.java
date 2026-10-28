package com.ripplemap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** One stored version of the sources of a ZIP project. Never modified after creation. */
@Entity
@Table(name = "source_import")
public class SourceImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(length = 100)
    private String label;

    @Column(name = "archive_bytes", nullable = false)
    private long archiveBytes;

    @Column(name = "archive_sha256", length = 64)
    private String archiveSha256;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;

    @Column(name = "java_file_count", nullable = false)
    private int javaFileCount;

    @Column(name = "extracted_bytes", nullable = false)
    private long extractedBytes;

    /** Directory name below {@code <data-dir>/imports} (a random UUID). */
    @Column(name = "storage_dir", nullable = false, length = 255)
    private String storageDir;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    protected SourceImport() {
    }

    public SourceImport(Project project, int versionNumber, String originalFilename, String label, long archiveBytes,
                        String archiveSha256, int entryCount, int javaFileCount, long extractedBytes,
                        String storageDir, Instant importedAt) {
        this.project = project;
        this.versionNumber = versionNumber;
        this.originalFilename = originalFilename;
        this.label = label;
        this.archiveBytes = archiveBytes;
        this.archiveSha256 = archiveSha256;
        this.entryCount = entryCount;
        this.javaFileCount = javaFileCount;
        this.extractedBytes = extractedBytes;
        this.storageDir = storageDir;
        this.importedAt = importedAt;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getLabel() {
        return label;
    }

    public long getArchiveBytes() {
        return archiveBytes;
    }

    public String getArchiveSha256() {
        return archiveSha256;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public int getJavaFileCount() {
        return javaFileCount;
    }

    public long getExtractedBytes() {
        return extractedBytes;
    }

    public String getStorageDir() {
        return storageDir;
    }

    public Instant getImportedAt() {
        return importedAt;
    }
}
