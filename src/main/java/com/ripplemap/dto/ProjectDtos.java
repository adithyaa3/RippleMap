package com.ripplemap.dto;

import com.ripplemap.entity.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record ProjectSummary(
            long id,
            String name,
            String description,
            SourceType sourceType,
            boolean includeTests,
            Instant createdAt,
            Instant updatedAt,
            long versionCount,
            AnalysisDtos.AnalysisSummary latestAnalysis,
            Long defaultAnalysisId,
            String localPath) {
    }

    public record Version(long id, int versionNumber, String originalFilename, String label, long archiveBytes,
                          int entryCount, int javaFileCount, long extractedBytes, Instant importedAt) {
    }

    public record ProjectDetail(ProjectSummary project, List<Version> versions, long analysisCount,
                                boolean analysisActive, int maxSnapshots) {
    }

    public record UpdateProjectRequest(
            @Size(min = 1, max = 100, message = "The name must have 1 to 100 characters") String name,
            @Size(max = 2000, message = "The description must not exceed 2000 characters") String description,
            Boolean includeTests) {
    }

    public record CreateLocalProjectRequest(
            @NotBlank(message = "Enter a project name") @Size(max = 100, message = "The name must not exceed 100 characters") String name,
            @Size(max = 2000, message = "The description must not exceed 2000 characters") String description,
            @NotBlank(message = "Enter a directory path") @Size(max = 1024, message = "The path is too long") String path,
            boolean includeTests) {
    }

    public record AnalyzeRequest(Long versionId, Boolean includeTests) {
    }

    public record ImportResponse(ProjectSummary project, Version version, AnalysisDtos.AnalysisSummary analysis) {
    }
}
