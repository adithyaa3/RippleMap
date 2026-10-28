package com.ripplemap.controller;

import com.ripplemap.dto.AnalysisDtos;
import com.ripplemap.dto.ProjectDtos;
import com.ripplemap.service.DtoMapper;
import com.ripplemap.service.ImportService;
import com.ripplemap.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/projects")
@Tag(name = "Projects")
public class ProjectController {

    private final ProjectService projects;
    private final ImportService imports;

    public ProjectController(ProjectService projects, ImportService imports) {
        this.projects = projects;
        this.imports = imports;
    }

    @GetMapping
    @Operation(summary = "List projects with their latest analysis status")
    public List<ProjectDtos.ProjectSummary> list() {
        return projects.list();
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Create a project from a ZIP archive and start its first analysis")
    public ResponseEntity<ProjectDtos.ImportResponse> importProject(
            @RequestPart("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "includeTests", defaultValue = "false") boolean includeTests) {
        ProjectDtos.ImportResponse response = imports.importProject(file, name, description, includeTests);
        return ResponseEntity.created(URI.create("/api/projects/" + response.project().id())).body(response);
    }

    @PostMapping(path = "/local", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Register a project for an authorized local directory and start its first scan")
    public ResponseEntity<ProjectDtos.ImportResponse> createLocal(
            @Valid @RequestBody ProjectDtos.CreateLocalProjectRequest request) {
        ProjectDtos.ImportResponse response = projects.createLocal(request);
        return ResponseEntity.created(URI.create("/api/projects/" + response.project().id())).body(response);
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "Project details with stored versions")
    public ProjectDtos.ProjectDetail detail(@PathVariable long projectId) {
        return projects.detail(projectId);
    }

    @PatchMapping(path = "/{projectId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rename a project or change its description or default scan scope")
    public ProjectDtos.ProjectSummary update(@PathVariable long projectId,
                                             @Valid @RequestBody ProjectDtos.UpdateProjectRequest request) {
        return projects.update(projectId, request);
    }

    @DeleteMapping("/{projectId}")
    @Operation(summary = "Delete a project, its versions, snapshots and stored sources")
    public ResponseEntity<Void> delete(@PathVariable long projectId) {
        projects.delete(projectId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{projectId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a new version (ZIP) of a project and analyze it")
    public ResponseEntity<ProjectDtos.ImportResponse> uploadVersion(
            @PathVariable long projectId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "label", required = false) String label,
            @RequestParam(value = "includeTests", required = false) Boolean includeTests) {
        ProjectDtos.ImportResponse response = imports.importVersion(projectId, file, label, includeTests);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{projectId}/analyze")
    @Operation(summary = "Re-analyze a stored version (ZIP projects) or rescan the directory (local projects)")
    public ResponseEntity<AnalysisDtos.AnalysisSummary> analyze(
            @PathVariable long projectId,
            @RequestBody(required = false) ProjectDtos.AnalyzeRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(DtoMapper.analysis(projects.analyze(projectId, request)));
    }
}
