package com.ripplemap.controller;

import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.dto.ImpactDtos;
import com.ripplemap.entity.Analysis;
import com.ripplemap.report.ReportService;
import com.ripplemap.service.SnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Report downloads. Every report is built from the snapshot named by analysisId (or the latest completed one). */
@RestController
@RequestMapping("/api/projects/{projectId}/reports")
@Tag(name = "Reports")
public class ReportController {

    public record ReportLink(String key, String title, String description, String format, String href) {
    }

    public record ReportCatalog(long analysisId, int sequenceNumber, String status, List<ReportLink> reports) {
    }

    private final ReportService reports;
    private final SnapshotService snapshots;

    public ReportController(ReportService reports, SnapshotService snapshots) {
        this.reports = reports;
        this.snapshots = snapshots;
    }

    @GetMapping
    @Operation(summary = "Available reports for a snapshot")
    public ReportCatalog catalog(@PathVariable long projectId, @RequestParam(required = false) Long analysisId) {
        Analysis analysis = snapshots.resolve(projectId, analysisId);
        String base = "/api/projects/" + projectId + "/reports/";
        String query = "?analysisId=" + analysis.getId();
        return new ReportCatalog(analysis.getId(), analysis.getSequenceNumber(), analysis.getStatus().name(), List.of(
                new ReportLink("analysis-html", "Analysis report", "Readable summary: counts, highest-impact types, "
                        + "circular dependencies, diagnostics and limitations.", "HTML", base + "analysis.html" + query),
                new ReportLink("analysis-json", "Full analysis export", "Every entity, relationship, cycle and "
                        + "diagnostic of the snapshot.", "JSON", base + "analysis.json" + query),
                new ReportLink("summary-csv", "Summary metrics", "Snapshot identity, scope and counts as "
                        + "section/metric/value rows.", "CSV", base + "summary.csv" + query),
                new ReportLink("entities-csv", "Entities", "Types, methods and constructors with dependency "
                        + "metrics and impact scores.", "CSV", base + "entities.csv" + query),
                new ReportLink("dependencies-csv", "Dependencies", "Every relationship with kind, resolution, "
                        + "reference count and first source location.", "CSV", base + "dependencies.csv" + query),
                new ReportLink("cycles-csv", "Circular dependencies", "Cyclic components with members and a "
                        + "representative cycle.", "CSV", base + "cycles.csv" + query),
                new ReportLink("diagnostics-csv", "Diagnostics", "Parse errors, unresolved references and other "
                        + "findings.", "CSV", base + "diagnostics.csv" + query)));
    }

    @GetMapping("/analysis.html")
    public ResponseEntity<byte[]> analysisHtml(@PathVariable long projectId, @RequestParam(required = false) Long analysisId) {
        return download(reports.analysisHtml(projectId, analysisId));
    }

    @GetMapping("/analysis.json")
    public ResponseEntity<byte[]> analysisJson(@PathVariable long projectId, @RequestParam(required = false) Long analysisId) {
        return download(reports.analysisJson(projectId, analysisId));
    }

    @GetMapping("/summary.csv")
    public ResponseEntity<byte[]> summaryCsv(@PathVariable long projectId, @RequestParam(required = false) Long analysisId) {
        return download(reports.summaryCsv(projectId, analysisId));
    }

    @GetMapping("/entities.csv")
    public ResponseEntity<byte[]> entitiesCsv(@PathVariable long projectId, @RequestParam(required = false) Long analysisId) {
        return download(reports.entitiesCsv(projectId, analysisId));
    }

    @GetMapping("/dependencies.csv")
    public ResponseEntity<byte[]> dependenciesCsv(@PathVariable long projectId,
                                                  @RequestParam(required = false) Long analysisId) {
        return download(reports.dependenciesCsv(projectId, analysisId));
    }

    @GetMapping("/cycles.csv")
    public ResponseEntity<byte[]> cyclesCsv(@PathVariable long projectId, @RequestParam(required = false) Long analysisId,
                                            @RequestParam(required = false) GraphLayer layer) {
        return download(reports.cyclesCsv(projectId, analysisId, layer));
    }

    @GetMapping("/diagnostics.csv")
    public ResponseEntity<byte[]> diagnosticsCsv(@PathVariable long projectId,
                                                 @RequestParam(required = false) Long analysisId) {
        return download(reports.diagnosticsCsv(projectId, analysisId));
    }

    @GetMapping("/impact.{format:csv|html|json}")
    @Operation(summary = "Impact report for one component (same calculation as the impact-analysis endpoint)")
    public ResponseEntity<byte[]> impact(@PathVariable long projectId, @PathVariable String format,
                                         @RequestParam(required = false) Long analysisId, @RequestParam String nodeId,
                                         @RequestParam(required = false) GraphLayer layer,
                                         @RequestParam(required = false) Boolean includeInferred) {
        return download(reports.impact(projectId, new ImpactDtos.ImpactRequest(analysisId, nodeId, layer,
                includeInferred), format));
    }

    @GetMapping("/comparison.{format:csv|html}")
    @Operation(summary = "Comparison report for two snapshots")
    public ResponseEntity<byte[]> comparison(@PathVariable long projectId, @PathVariable String format,
                                             @RequestParam Long base, @RequestParam Long target,
                                             @RequestParam(required = false) GraphLayer layer) {
        return download(reports.comparison(projectId, base, target, layer, format));
    }

    private static ResponseEntity<byte[]> download(ReportService.Report report) {
        return ResponseEntity.ok()
                .contentType(report.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(report.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(report.content());
    }
}
