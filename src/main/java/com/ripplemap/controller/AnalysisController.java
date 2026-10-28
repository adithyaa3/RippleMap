package com.ripplemap.controller;

import com.ripplemap.analysis.diagnostics.DiagnosticCategory;
import com.ripplemap.analysis.diagnostics.Severity;
import com.ripplemap.dto.AnalysisDtos;
import com.ripplemap.repository.AnalysisRepository;
import com.ripplemap.service.AnalysisService;
import com.ripplemap.service.DiagnosticsService;
import com.ripplemap.service.DtoMapper;
import com.ripplemap.service.SnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Analyses")
public class AnalysisController {

    private final SnapshotService snapshots;
    private final AnalysisRepository analyses;
    private final AnalysisService analysisService;
    private final DiagnosticsService diagnostics;

    public AnalysisController(SnapshotService snapshots, AnalysisRepository analyses, AnalysisService analysisService,
                              DiagnosticsService diagnostics) {
        this.snapshots = snapshots;
        this.analyses = analyses;
        this.analysisService = analysisService;
        this.diagnostics = diagnostics;
    }

    @GetMapping("/analyses")
    @Transactional(readOnly = true)
    @Operation(summary = "All snapshots of a project, newest first")
    public List<AnalysisDtos.AnalysisSummary> list(@PathVariable long projectId) {
        snapshots.project(projectId);
        return analyses.findByProjectIdOrderBySequenceNumberDesc(projectId).stream().map(DtoMapper::analysis).toList();
    }

    @GetMapping("/history")
    @Transactional(readOnly = true)
    @Operation(summary = "Alias of /analyses")
    public List<AnalysisDtos.AnalysisSummary> history(@PathVariable long projectId) {
        return list(projectId);
    }

    @GetMapping("/analyses/{analysisId}")
    @Transactional(readOnly = true)
    @Operation(summary = "One snapshot with its status and counts")
    public AnalysisDtos.AnalysisSummary get(@PathVariable long projectId, @PathVariable long analysisId) {
        return DtoMapper.analysis(snapshots.analysisOfProject(projectId, analysisId));
    }

    @PostMapping("/analyses/{analysisId}/cancel")
    @Operation(summary = "Cancel a pending analysis or ask a running analysis to stop")
    public AnalysisDtos.AnalysisSummary cancel(@PathVariable long projectId, @PathVariable long analysisId) {
        return DtoMapper.analysis(analysisService.cancel(projectId, analysisId));
    }

    @DeleteMapping("/analyses/{analysisId}")
    @Operation(summary = "Delete a finished snapshot")
    public ResponseEntity<Void> delete(@PathVariable long projectId, @PathVariable long analysisId) {
        analysisService.deleteSnapshot(projectId, analysisId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/analyses/{analysisId}/diagnostics")
    @Operation(summary = "Diagnostics of a snapshot, filterable and paginated")
    public AnalysisDtos.DiagnosticPage diagnostics(@PathVariable long projectId, @PathVariable long analysisId,
                                                   @RequestParam(required = false) Severity severity,
                                                   @RequestParam(required = false) DiagnosticCategory category,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return diagnostics.page(projectId, analysisId, severity, category, page, size);
    }

    @GetMapping("/analyses/{analysisId}/files")
    @Operation(summary = "Source files of a snapshot with parse status")
    public AnalysisDtos.SourceFileList files(@PathVariable long projectId, @PathVariable long analysisId) {
        return diagnostics.files(projectId, analysisId);
    }
}
