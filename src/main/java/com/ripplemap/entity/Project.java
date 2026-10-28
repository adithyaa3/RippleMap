package com.ripplemap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** Case-insensitive uniqueness key derived from the name. */
    @Column(name = "name_key", nullable = false, length = 100)
    private String nameKey;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Column(name = "local_path", length = 1024)
    private String localPath;

    @Column(name = "include_tests", nullable = false)
    private boolean includeTests;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Project() {
    }

    public Project(String name, String description, SourceType sourceType, String localPath, boolean includeTests,
                   Instant now) {
        rename(name);
        this.description = description;
        this.sourceType = sourceType;
        this.localPath = localPath;
        this.includeTests = includeTests;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static String nameKey(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    public void rename(String newName) {
        this.name = newName.strip();
        this.nameKey = nameKey(newName);
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setIncludeTests(boolean includeTests) {
        this.includeTests = includeTests;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNameKey() {
        return nameKey;
    }

    public String getDescription() {
        return description;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getLocalPath() {
        return localPath;
    }

    public boolean isIncludeTests() {
        return includeTests;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
