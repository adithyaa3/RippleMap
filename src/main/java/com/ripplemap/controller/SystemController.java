package com.ripplemap.controller;

import com.ripplemap.config.RippleMapProperties;
import com.ripplemap.dto.DashboardDtos;
import com.ripplemap.dto.SettingsDtos;
import com.ripplemap.entity.Analysis;
import com.ripplemap.importing.LocalDirectoryPolicy;
import com.ripplemap.service.DashboardService;
import com.ripplemap.service.SettingsService;
import com.ripplemap.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Dashboard, settings and system")
public class SystemController {

    private final DashboardService dashboard;
    private final SettingsService settings;
    private final RippleMapProperties properties;
    private final StorageService storage;
    private final LocalDirectoryPolicy localPolicy;

    public SystemController(DashboardService dashboard, SettingsService settings, RippleMapProperties properties,
                            StorageService storage, LocalDirectoryPolicy localPolicy) {
        this.dashboard = dashboard;
        this.settings = settings;
        this.properties = properties;
        this.storage = storage;
        this.localPolicy = localPolicy;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Totals and highlights over each project's latest completed snapshot")
    public DashboardDtos.Dashboard dashboard() {
        return dashboard.dashboard();
    }

    @GetMapping("/settings")
    public SettingsDtos.Settings settings() {
        return settings.get();
    }

    @PutMapping(path = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update impact thresholds and display defaults")
    public SettingsDtos.Settings updateSettings(@Valid @RequestBody SettingsDtos.UpdateSettingsRequest request) {
        return settings.update(request);
    }

    @GetMapping("/system")
    @Operation(summary = "Version, data directory and configured limits")
    public SettingsDtos.SystemInfo system() {
        RippleMapProperties.Analysis a = properties.getAnalysis();
        RippleMapProperties.Import i = properties.getImport();
        List<SettingsDtos.Limit> limits = List.of(
                new SettingsDtos.Limit("Maximum upload size", i.getMaxUploadSize().toMegabytes() + " MB", "ripplemap.import.max-upload-size"),
                new SettingsDtos.Limit("Maximum extracted Java bytes per archive", i.getMaxExpandedSize().toMegabytes() + " MB", "ripplemap.import.max-expanded-size"),
                new SettingsDtos.Limit("Maximum size of one Java file", i.getMaxEntrySize().toKilobytes() + " KB", "ripplemap.import.max-entry-size"),
                new SettingsDtos.Limit("Maximum archive entries", String.valueOf(i.getMaxEntries()), "ripplemap.import.max-entries"),
                new SettingsDtos.Limit("Maximum Java files per project", String.valueOf(Math.min(i.getMaxJavaFiles(), a.getMaxJavaFiles())), "ripplemap.analysis.max-java-files"),
                new SettingsDtos.Limit("Maximum path depth / length", i.getMaxPathDepth() + " / " + i.getMaxPathLength(), "ripplemap.import.max-path-depth"),
                new SettingsDtos.Limit("Maximum compression ratio", i.getMaxCompressionRatio() + ":1", "ripplemap.import.max-compression-ratio"),
                new SettingsDtos.Limit("Total stored imports", i.getMaxStoredSize().toMegabytes() + " MB", "ripplemap.import.max-stored-size"),
                new SettingsDtos.Limit("Concurrent analyses / queue", a.getConcurrency() + " / " + a.getQueueCapacity(), "ripplemap.analysis.concurrency"),
                new SettingsDtos.Limit("Analysis time limit", a.getTimeout().toMinutes() + " min", "ripplemap.analysis.timeout"),
                new SettingsDtos.Limit("Snapshots per project", String.valueOf(a.getMaxSnapshotsPerProject()), "ripplemap.analysis.max-snapshots-per-project"),
                new SettingsDtos.Limit("Stored diagnostics per snapshot", String.valueOf(a.getMaxDiagnostics()), "ripplemap.analysis.max-diagnostics"),
                new SettingsDtos.Limit("Graph nodes per response", String.valueOf(properties.getGraph().getMaxResponseNodes()), "ripplemap.graph.max-response-nodes"));
        Runtime runtime = Runtime.getRuntime();
        return new SettingsDtos.SystemInfo(
                RippleMapProperties.class.getPackage().getImplementationVersion() == null ? "1.0.0"
                        : RippleMapProperties.class.getPackage().getImplementationVersion(),
                Analysis.ANALYZER_VERSION, System.getProperty("java.version"), System.getProperty("java.vendor"),
                System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", " + runtime.availableProcessors()
                        + " CPUs, max heap " + (runtime.maxMemory() / (1024 * 1024)) + " MB",
                storage.dataDir().toString(), a.getLanguageLevel(), localPolicy.enabled(), localPolicy.allowedRoots(),
                limits);
    }
}
