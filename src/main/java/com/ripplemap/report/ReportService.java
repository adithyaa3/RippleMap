package com.ripplemap.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ripplemap.analysis.diagnostics.Diagnostic;
import com.ripplemap.analysis.model.AggregatedRelationship;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.ReferenceOccurrence;
import com.ripplemap.dto.AnalysisDtos;
import com.ripplemap.dto.ComparisonDtos;
import com.ripplemap.dto.CycleDtos;
import com.ripplemap.dto.GraphDtos;
import com.ripplemap.dto.ImpactDtos;
import com.ripplemap.entity.Analysis;
import com.ripplemap.entity.AnalysisCounts;
import com.ripplemap.entity.Project;
import com.ripplemap.entity.SourceImport;
import com.ripplemap.impact.ImpactScore;
import com.ripplemap.impact.ImpactThresholds;
import com.ripplemap.impact.NodeMetrics;
import com.ripplemap.repository.SnapshotStore;
import com.ripplemap.repository.StoredEntity;
import com.ripplemap.service.ComparisonService;
import com.ripplemap.service.CycleService;
import com.ripplemap.service.DiagnosticsService;
import com.ripplemap.service.DtoMapper;
import com.ripplemap.service.ImpactService;
import com.ripplemap.service.SettingsService;
import com.ripplemap.service.SnapshotData;
import com.ripplemap.service.SnapshotService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds downloadable reports from the data of one selected snapshot (never from any other snapshot). */
@Service
public class ReportService {

    public static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    public static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    public static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);

    public static final List<String> LIMITATIONS = List.of(
            "RippleMap analyzes source code as data. Nothing is compiled or executed.",
            "Method calls are resolved statically; targets reached only through polymorphic dispatch are not followed.",
            "Reflection, dependency-injection wiring, configuration files, annotation processing and generated code are not analyzed.",
            "Library classes are not available to the analyzer. References to them are classified as external, and members inherited from libraries are not project dependencies.",
            "Unresolved references may hide project dependencies; the affected sets can therefore be incomplete. See the diagnostics.",
            "Inferred relationships come from documented conservative rules (see docs/ANALYSIS_SEMANTICS.md) and are marked as INFERRED.");

    public record Report(byte[] content, String filename, MediaType mediaType) {
    }

    private final SnapshotService snapshots;
    private final SnapshotStore store;
    private final ImpactService impactService;
    private final CycleService cycleService;
    private final ComparisonService comparisonService;
    private final SettingsService settings;
    private final ObjectMapper json;
    private final Clock clock;

    public ReportService(SnapshotService snapshots, SnapshotStore store, ImpactService impactService,
                         CycleService cycleService, ComparisonService comparisonService, SettingsService settings,
                         ObjectMapper mapper, Clock clock) {
        this.snapshots = snapshots;
        this.store = store;
        this.impactService = impactService;
        this.cycleService = cycleService;
        this.comparisonService = comparisonService;
        this.settings = settings;
        this.json = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.clock = clock;
    }

    private record Context(Project project, Analysis analysis, SnapshotData data) {
    }

    private Context context(long projectId, Long analysisId) {
        Analysis analysis = snapshots.resolve(projectId, analysisId);
        return new Context(snapshots.project(projectId), analysis, snapshots.load(analysis));
    }

    // ---------------------------------------------------------------- CSV

    @Transactional(readOnly = true)
    public Report summaryCsv(long projectId, Long analysisId) {
        Context ctx = context(projectId, analysisId);
        CsvWriter csv = new CsvWriter().row("section", "metric", "value");
        for (Map.Entry<String, Map<String, Object>> section : summary(ctx).entrySet()) {
            for (Map.Entry<String, Object> metric : section.getValue().entrySet()) {
                csv.row(section.getKey(), metric.getKey(), metric.getValue());
            }
        }
        for (String limitation : LIMITATIONS) {
            csv.row("Limitations", "note", limitation);
        }
        return new Report(csv.toBytes(), filename(ctx, "summary", "csv"), CSV);
    }

    @Transactional(readOnly = true)
    public Report entitiesCsv(long projectId, Long analysisId) {
        Context ctx = context(projectId, analysisId);
        ImpactThresholds thresholds = settings.thresholds();
        int typeCount = ctx.data().nodes(GraphLayer.TYPE).size();
        CsvWriter csv = new CsvWriter().row("layer", "id", "name", "kind", "package", "declaring_type", "signature",
                "file", "line", "test_source", "direct_dependencies", "direct_dependents", "transitive_dependents",
                "max_dependent_distance", "impact_score", "impact_level", "in_cycle");
        for (GraphLayer layer : GraphLayer.values()) {
            for (StoredEntity e : ctx.data().nodes(layer)) {
                NodeMetrics m = e.metrics();
                String level = null;
                if (m != null && m.transitiveDependents() != null && layer == GraphLayer.TYPE) {
                    level = ImpactScore.classify(typeCount, m.directDependents(), m.transitiveDependents(),
                            m.maxDependentDistance(), thresholds).name();
                }
                csv.row(layer.name(), e.id(), e.name(), e.kind().name(), e.entity().packageName(),
                        e.entity().declaringTypeId(), e.entity().signature(), e.entity().filePath(), e.entity().line(),
                        e.entity().testSource(), m == null ? null : m.directDependencies(),
                        m == null ? null : m.directDependents(), m == null ? null : m.transitiveDependents(),
                        m == null ? null : m.maxDependentDistance(),
                        m == null || m.impactScore() == null ? null : ImpactScore.round(m.impactScore()), level,
                        m != null && m.inCycle());
            }
        }
        return new Report(csv.toBytes(), filename(ctx, "entities", "csv"), CSV);
    }

    @Transactional(readOnly = true)
    public Report dependenciesCsv(long projectId, Long analysisId) {
        Context ctx = context(projectId, analysisId);
        CsvWriter csv = new CsvWriter().row("layer", "source_id", "source_name", "target_id", "target_name", "kind",
                "resolution", "reference_count", "resolved_count", "inferred_count", "first_file", "first_line",
                "first_detail", "inference_rule");
        for (GraphLayer layer : GraphLayer.values()) {
            for (AggregatedRelationship r : ctx.data().relationships(layer)) {
                ReferenceOccurrence first = r.samples().isEmpty() ? null : r.samples().get(0);
                csv.row(layer.name(), r.sourceId(), name(ctx, r.sourceId()), r.targetId(), name(ctx, r.targetId()),
                        r.kind().name(), r.resolution().name(), r.referenceCount(), r.resolvedCount(),
                        r.inferredCount(), first == null ? null : first.filePath(), first == null ? null : first.line(),
                        first == null ? null : first.detail(), first == null ? null : first.rule());
            }
        }
        return new Report(csv.toBytes(), filename(ctx, "dependencies", "csv"), CSV);
    }

    @Transactional(readOnly = true)
    public Report diagnosticsCsv(long projectId, Long analysisId) {
        Context ctx = context(projectId, analysisId);
        CsvWriter csv = new CsvWriter().row("severity", "category", "file", "line", "entity_id", "symbol", "message");
        for (Diagnostic d : store.loadAllDiagnostics(ctx.analysis().getId())) {
            csv.row(d.severity().name(), d.category().name(), d.filePath(), d.line(), d.entityId(), d.symbol(), d.message());
        }
        return new Report(csv.toBytes(), filename(ctx, "diagnostics", "csv"), CSV);
    }

    @Transactional(readOnly = true)
    public Report cyclesCsv(long projectId, Long analysisId, GraphLayer layer) {
        Context ctx = context(projectId, analysisId);
        CycleDtos.CycleList cycles = cycleService.cycles(projectId, ctx.analysis().getId(), layer, true);
        CsvWriter csv = new CsvWriter().row("layer", "component", "size", "members", "representative_cycle");
        for (CycleDtos.CycleComponent c : cycles.components()) {
            csv.row(cycles.layer().name(), c.index() + 1, c.size(), joinNames(c.members()), joinNames(c.cycle()));
        }
        return new Report(csv.toBytes(), filename(ctx, "cycles-" + cycles.layer().name().toLowerCase(Locale.ROOT), "csv"), CSV);
    }

    // ---------------------------------------------------------------- analysis HTML / JSON

    @Transactional(readOnly = true)
    public Report analysisHtml(long projectId, Long analysisId) {
        Context ctx = context(projectId, analysisId);
        Analysis a = ctx.analysis();
        AnalysisCounts c = a.getCounts();
        HtmlReport html = new HtmlReport("RippleMap analysis report - " + ctx.project().getName());
        html.heading(1, "Analysis report: " + ctx.project().getName());
        html.paragraph("Snapshot #" + a.getSequenceNumber() + " (analysis id " + a.getId() + "), status " + a.getStatus()
                + ", finished " + a.getFinishedAt(), true);
        if (ctx.project().getDescription() != null) {
            html.paragraph(ctx.project().getDescription(), false);
        }
        if (!a.getStatus().equals(com.ripplemap.entity.AnalysisStatus.COMPLETED)) {
            html.note("This snapshot is PARTIAL: " + a.getPartialReasons()
                    + ". Results do not include declarations from files that could not be analyzed.");
        }
        html.cards(List.of(
                new String[]{"Java files parsed", c.getFilesParsed() + " of " + c.getFilesInScope()},
                new String[]{"Types", String.valueOf(c.getTypeCount())},
                new String[]{"Methods and constructors", String.valueOf(value(c.getMethodCount()) + value(c.getConstructorCount()))},
                new String[]{"Type dependencies", String.valueOf(c.getTypeEdgeCount())},
                new String[]{"Unresolved references", String.valueOf(c.getReferencesUnresolved())},
                new String[]{"Cyclic components", String.valueOf(c.getTypeCyclicComponents())}));
        for (Map.Entry<String, Map<String, Object>> section : summary(ctx).entrySet()) {
            html.heading(2, section.getKey());
            List<Object[]> rows = new ArrayList<>();
            section.getValue().forEach((k, v) -> rows.add(new Object[]{k, v instanceof Number ? v : String.valueOf(v)}));
            html.table(new String[]{"Metric", "Value"}, rows);
        }

        html.heading(2, "Highest structural impact (types)");
        html.paragraph(ImpactService.FORMULA + ". Thresholds: LOW below " + settings.thresholds().low()
                + ", HIGH from " + settings.thresholds().high() + ".", true);
        ImpactThresholds thresholds = settings.thresholds();
        int typeCount = ctx.data().nodes(GraphLayer.TYPE).size();
        List<StoredEntity> ranked = new ArrayList<>(ctx.data().nodes(GraphLayer.TYPE));
        ranked.removeIf(e -> e.metrics() == null || e.metrics().impactScore() == null || e.metrics().impactScore() <= 0);
        ranked.sort(Comparator.<StoredEntity>comparingDouble(e -> -e.metrics().impactScore()).thenComparing(StoredEntity::id));
        List<Object[]> hot = new ArrayList<>();
        for (StoredEntity e : ranked.subList(0, Math.min(25, ranked.size()))) {
            NodeMetrics m = e.metrics();
            hot.add(new Object[]{e.name(), HtmlReport.code(e.id()), m.directDependents(), m.transitiveDependents(),
                    m.maxDependentDistance(), ImpactScore.round(m.impactScore()),
                    HtmlReport.badge(ImpactScore.classify(typeCount, m.directDependents(), m.transitiveDependents(),
                            m.maxDependentDistance(), thresholds).name())});
        }
        html.table(new String[]{"Type", "Id", "Direct dependents", "Transitive dependents", "Max depth", "Score", "Level"}, hot);
        if (!Boolean.TRUE.equals(c.getNodeMetricsComputed())) {
            html.note("Per-type transitive metrics were not precomputed for this large snapshot; use the Impact Analysis page.");
        }

        CycleDtos.CycleList cycles = cycleService.cycles(projectId, a.getId(), GraphLayer.TYPE, true);
        html.heading(2, "Circular dependencies (types)");
        html.paragraph(CycleService.NOTE, true);
        List<Object[]> cycleRows = new ArrayList<>();
        for (CycleDtos.CycleComponent comp : cycles.components().subList(0, Math.min(50, cycles.components().size()))) {
            cycleRows.add(new Object[]{comp.index() + 1, comp.size(), joinNames(comp.members()), joinNames(comp.cycle())});
        }
        html.table(new String[]{"#", "Size", "Members", "Representative cycle"}, cycleRows);

        html.heading(2, "Diagnostics");
        List<Object[]> categoryRows = new ArrayList<>();
        for (AnalysisDtos.CategoryCount cc : DiagnosticsService.categories(store.loadDiagnosticCounts(a.getId()))) {
            categoryRows.add(new Object[]{cc.category().name(), cc.severity().name(), cc.count(), cc.description()});
        }
        html.table(new String[]{"Category", "Severity", "Count", "Meaning"}, categoryRows);
        List<Object[]> diagRows = new ArrayList<>();
        for (Diagnostic d : store.loadDiagnostics(a.getId(), null, null, null, 0, 200)) {
            diagRows.add(new Object[]{d.severity().name(), d.category().name(),
                    d.filePath() == null ? "" : d.filePath() + (d.line() > 0 ? ":" + d.line() : ""), d.symbol(), d.message()});
        }
        html.heading(3, "First " + diagRows.size() + " diagnostics");
        html.table(new String[]{"Severity", "Category", "Location", "Symbol", "Message"}, diagRows);

        html.heading(2, "Known limitations");
        html.list(LIMITATIONS);
        return new Report(html.render(footer()), filename(ctx, "analysis", "html"), HTML);
    }

    @Transactional(readOnly = true)
    public Report analysisJson(long projectId, Long analysisId) {
        Context ctx = context(projectId, analysisId);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("report", "ripplemap-analysis");
        doc.put("formatVersion", 1);
        doc.put("generatedAt", clock.instant());
        doc.put("project", projectInfo(ctx.project()));
        doc.put("analysis", DtoMapper.analysis(ctx.analysis()));
        doc.put("summary", summary(ctx));
        List<Map<String, Object>> entities = new ArrayList<>();
        for (StoredEntity e : ctx.data().entities().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("kind", e.kind());
            m.put("name", e.name());
            m.put("qualifiedName", e.entity().qualifiedName());
            m.put("declaringTypeId", e.entity().declaringTypeId());
            m.put("signature", e.entity().signature());
            m.put("file", e.entity().filePath());
            m.put("line", e.entity().line());
            m.put("visibility", e.entity().visibility());
            m.put("modifiers", e.entity().modifiers());
            m.put("testSource", e.entity().testSource());
            m.put("metrics", e.metrics());
            entities.add(m);
        }
        doc.put("entities", entities);
        List<AggregatedRelationship> relationships = new ArrayList<>(ctx.data().relationships(GraphLayer.TYPE));
        relationships.addAll(ctx.data().relationships(GraphLayer.METHOD));
        doc.put("relationships", relationships);
        doc.put("cycles", cycleService.cycles(projectId, ctx.analysis().getId(), GraphLayer.TYPE, true));
        doc.put("diagnosticCounts", store.loadDiagnosticCounts(ctx.analysis().getId()));
        doc.put("diagnostics", store.loadAllDiagnostics(ctx.analysis().getId()));
        doc.put("limitations", LIMITATIONS);
        return new Report(toJson(doc), filename(ctx, "analysis", "json"), JSON);
    }

    // ---------------------------------------------------------------- impact

    @Transactional(readOnly = true)
    public Report impact(long projectId, ImpactDtos.ImpactRequest request, String format) {
        Context ctx = context(projectId, request.analysisId());
        ImpactDtos.ImpactRequest pinned = new ImpactDtos.ImpactRequest(ctx.analysis().getId(), request.nodeId(),
                request.layer(), request.includeInferred());
        ImpactDtos.ImpactResult r = impactService.analyze(projectId, pinned, Integer.MAX_VALUE, Integer.MAX_VALUE);
        String base = "impact-" + slug(r.selected().name());
        return switch (format) {
            case "csv" -> {
                CsvWriter csv = new CsvWriter();
                csv.row("selected_id", "selected_name", "layer", "analysis_id", "snapshot", "include_inferred", "N", "D",
                        "T", "H", "score", "percentage", "level");
                csv.row(r.selected().id(), r.selected().name(), r.layer().name(), r.analysisId(), r.analysisSequence(),
                        r.includeInferred(), r.eligibleCount(), r.directCount(), r.transitiveCount(), r.maxDistance(),
                        r.scoreRounded(), r.percentageRounded(), r.level().name());
                csv.endRow();
                csv.row("affected_id", "affected_name", "kind", "file", "line", "distance", "direct", "explanation_path");
                for (ImpactDtos.AffectedItem item : r.affected()) {
                    csv.row(item.entity().id(), item.entity().name(), item.entity().kind().name(), item.entity().filePath(),
                            item.entity().line(), item.distance(), item.direct(), joinNames(item.path(), " -> "));
                }
                yield new Report(csv.toBytes(), filename(ctx, base, "csv"), CSV);
            }
            case "json" -> {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("report", "ripplemap-impact");
                doc.put("formatVersion", 1);
                doc.put("generatedAt", clock.instant());
                doc.put("project", projectInfo(ctx.project()));
                doc.put("impact", r);
                yield new Report(toJson(doc), filename(ctx, base, "json"), JSON);
            }
            default -> new Report(impactHtml(ctx, r), filename(ctx, base, "html"), HTML);
        };
    }

    private byte[] impactHtml(Context ctx, ImpactDtos.ImpactResult r) {
        HtmlReport html = new HtmlReport("RippleMap impact report - " + r.selected().name());
        html.heading(1, "Impact of changing " + r.selected().name());
        html.paragraph(ctx.project().getName() + " - snapshot #" + r.analysisSequence() + " (analysis id "
                + r.analysisId() + ", " + r.analysisStatus() + "), " + r.layer() + " layer, inferred relationships "
                + (r.includeInferred() ? "included" : "excluded"), true);
        html.note(r.disclaimer());
        html.cards(List.of(
                new String[]{"Impact score", r.scoreRounded() + " (" + r.level() + ")"},
                new String[]{"Direct dependents (D)", String.valueOf(r.directCount())},
                new String[]{"Transitive dependents (T)", String.valueOf(r.transitiveCount())},
                new String[]{"Affected share", r.percentageRounded() + " % of " + Math.max(0, r.eligibleCount() - 1)},
                new String[]{"Max distance (H)", String.valueOf(r.maxDistance())},
                new String[]{"Affected files", String.valueOf(r.affectedFileCount())}));
        html.heading(2, "Score explanation");
        html.paragraph(r.formula() + "; N = " + r.eligibleCount() + " eligible " + r.layer().name().toLowerCase(Locale.ROOT)
                + "-level entities. LOW below " + r.lowThreshold() + ", HIGH from " + r.highThreshold() + ".", true);
        List<Object[]> factors = new ArrayList<>();
        for (ImpactDtos.Factor f : r.factors()) {
            factors.add(new Object[]{f.name(), f.formula(), ImpactScore.round(f.value() * 100) + " %", f.weight(),
                    ImpactScore.round(f.contribution())});
        }
        html.table(new String[]{"Factor", "Formula", "Signal", "Weight", "Contribution"}, factors);
        html.heading(2, "Potentially affected components");
        List<Object[]> rows = new ArrayList<>();
        for (ImpactDtos.AffectedItem item : r.affected()) {
            rows.add(new Object[]{item.entity().name(), item.entity().kind().name(), item.distance(),
                    item.direct() ? "direct" : "transitive", item.entity().filePath() + ":" + item.entity().line(),
                    joinNames(item.path(), " → ")});
        }
        html.table(new String[]{"Component", "Kind", "Distance", "Type", "Location", "Explanation path"}, rows);
        if (r.affectedTruncated()) {
            html.note("The list was truncated; the counts above are exact.");
        }
        html.heading(2, "Limitations");
        html.list(r.limitations());
        return html.render(footer());
    }

    // ---------------------------------------------------------------- comparison

    @Transactional(readOnly = true)
    public Report comparison(long projectId, Long baseId, Long targetId, GraphLayer layer, String format) {
        ComparisonDtos.Comparison cmp = comparisonService.compare(projectId, baseId, targetId, layer, Integer.MAX_VALUE);
        Project project = snapshots.project(projectId);
        String name = "ripplemap-" + slug(project.getName()) + "-compare-" + cmp.base().sequenceNumber() + "-to-"
                + cmp.target().sequenceNumber();
        if ("csv".equals(format)) {
            CsvWriter csv = new CsvWriter().row("change", "element", "id", "name", "detail");
            for (ComparisonDtos.EntityChange e : cmp.addedEntities()) {
                csv.row("added", "entity", e.id(), e.name(), e.kind().name());
            }
            for (ComparisonDtos.EntityChange e : cmp.removedEntities()) {
                csv.row("removed", "entity", e.id(), e.name(), e.kind().name());
            }
            for (ComparisonDtos.EdgeChange e : cmp.addedEdges()) {
                csv.row("added", "dependency", e.source() + " -> " + e.target(), e.sourceName() + " -> " + e.targetName(),
                        String.join(" ", e.kinds().stream().map(Enum::name).toList()));
            }
            for (ComparisonDtos.EdgeChange e : cmp.removedEdges()) {
                csv.row("removed", "dependency", e.source() + " -> " + e.target(), e.sourceName() + " -> " + e.targetName(),
                        String.join(" ", e.kinds().stream().map(Enum::name).toList()));
            }
            for (ComparisonDtos.MetricDelta d : cmp.metrics()) {
                csv.row("metric", d.metric(), d.base(), d.target(), d.delta());
            }
            return new Report(csv.toBytes(), name + ".csv", CSV);
        }
        HtmlReport html = new HtmlReport("RippleMap comparison - " + project.getName());
        html.heading(1, "Snapshot comparison: " + project.getName());
        html.paragraph("Base #" + cmp.base().sequenceNumber() + " (analysis " + cmp.base().id() + ") compared with #"
                + cmp.target().sequenceNumber() + " (analysis " + cmp.target().id() + "), " + cmp.layer() + " layer. "
                + "Changes are detected by stable source identities.", true);
        html.cards(List.of(
                new String[]{"Added entities", String.valueOf(cmp.addedEntityCount())},
                new String[]{"Removed entities", String.valueOf(cmp.removedEntityCount())},
                new String[]{"Added dependencies", String.valueOf(cmp.addedEdgeCount())},
                new String[]{"Removed dependencies", String.valueOf(cmp.removedEdgeCount())}));
        html.heading(2, "Metrics");
        List<Object[]> metrics = new ArrayList<>();
        for (ComparisonDtos.MetricDelta d : cmp.metrics()) {
            metrics.add(new Object[]{d.metric(), d.base(), d.target(), d.delta()});
        }
        html.table(new String[]{"Metric", "Base", "Target", "Change"}, metrics);
        html.heading(2, "Added entities");
        html.table(new String[]{"Name", "Kind", "Id"}, cmp.addedEntities().stream()
                .map(e -> new Object[]{e.name(), e.kind().name(), HtmlReport.code(e.id())}).toList());
        html.heading(2, "Removed entities");
        html.table(new String[]{"Name", "Kind", "Id"}, cmp.removedEntities().stream()
                .map(e -> new Object[]{e.name(), e.kind().name(), HtmlReport.code(e.id())}).toList());
        html.heading(2, "Added dependencies");
        html.table(new String[]{"Source", "Target", "Kinds"}, cmp.addedEdges().stream()
                .map(e -> new Object[]{e.sourceName(), e.targetName(), kinds(e)}).toList());
        html.heading(2, "Removed dependencies");
        html.table(new String[]{"Source", "Target", "Kinds"}, cmp.removedEdges().stream()
                .map(e -> new Object[]{e.sourceName(), e.targetName(), kinds(e)}).toList());
        if (cmp.truncated()) {
            html.note("Lists are limited to " + cmp.listLimit() + " items each; counts are exact.");
        }
        return new Report(html.render(footer()), name + ".html", HTML);
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Map<String, Object>> summary(Context ctx) {
        Analysis a = ctx.analysis();
        AnalysisCounts c = a.getCounts();
        SourceImport source = a.getSourceImport();
        Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("Project", ctx.project().getName());
        project.put("Project id", ctx.project().getId());
        project.put("Source type", ctx.project().getSourceType().name());
        project.put("Version", source == null ? "current directory contents" : "v" + source.getVersionNumber()
                + (source.getOriginalFilename() == null ? "" : " (" + source.getOriginalFilename() + ")"));
        sections.put("Project", project);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("Analysis id", a.getId());
        snapshot.put("Snapshot number", a.getSequenceNumber());
        snapshot.put("Status", a.getStatus().name());
        snapshot.put("Started", String.valueOf(a.getStartedAt()));
        snapshot.put("Finished", String.valueOf(a.getFinishedAt()));
        snapshot.put("Duration (ms)", a.getDurationMs());
        snapshot.put("Scan scope", a.isIncludeTests() ? "production and test sources" : "production sources only");
        snapshot.put("Java language level", a.getLanguageLevel());
        snapshot.put("Analyzer version", a.getAnalyzerVersion());
        snapshot.put("Source fingerprint (SHA-256)", a.getSourceFingerprint());
        sections.put("Snapshot", snapshot);
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("Java files discovered", c.getFilesDiscovered());
        files.put("Files in scope", c.getFilesInScope());
        files.put("Test files excluded", c.getFilesExcludedTests());
        files.put("Generated files excluded", c.getFilesExcludedGenerated());
        files.put("Files parsed", c.getFilesParsed());
        files.put("Files failed", c.getFilesFailed());
        sections.put("Files", files);
        Map<String, Object> declarations = new LinkedHashMap<>();
        declarations.put("Types", c.getTypeCount());
        declarations.put("Methods", c.getMethodCount());
        declarations.put("Constructors", c.getConstructorCount());
        declarations.put("Fields", c.getFieldCount());
        sections.put("Declarations", declarations);
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("Type dependencies (distinct source/target pairs)", c.getTypeEdgeCount());
        graph.put("Type relationships (pairs by kind)", c.getTypeRelationshipCount());
        graph.put("Method dependencies (distinct pairs)", c.getMethodEdgeCount());
        graph.put("Method relationships (pairs by kind)", c.getMethodRelationshipCount());
        graph.put("Cyclic components (types)", c.getTypeCyclicComponents());
        graph.put("Types in cycles", c.getTypeNodesInCycles());
        graph.put("Cyclic components (methods)", c.getMethodCyclicComponents());
        graph.put("Isolated types", c.getIsolatedTypes());
        sections.put("Dependency graph", graph);
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("Resolved by the symbol solver", c.getReferencesResolved());
        refs.put("Inferred by documented rules", c.getReferencesInferred());
        refs.put("Unresolved (may hide dependencies)", c.getReferencesUnresolved());
        refs.put("Unresolved, likely external", c.getReferencesLikelyExternal());
        refs.put("External (JDK, libraries, generated code)", c.getReferencesExternal());
        refs.put("Internal (self and local types)", c.getReferencesInternal());
        sections.put("References", refs);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("Errors", c.getErrorCount());
        diagnostics.put("Warnings", c.getWarningCount());
        diagnostics.put("Information", c.getInfoCount());
        diagnostics.put("Stored diagnostics", c.getDiagnosticsStored());
        diagnostics.put("Diagnostic list truncated", Boolean.TRUE.equals(c.getDiagnosticsTruncated()));
        sections.put("Diagnostics", diagnostics);
        return sections;
    }

    private static Map<String, Object> projectInfo(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("sourceType", p.getSourceType());
        return m;
    }

    private static String name(Context ctx, String id) {
        StoredEntity e = ctx.data().entity(id);
        return e == null ? id : e.name();
    }

    private static String joinNames(List<GraphDtos.EntityRef> refs) {
        return joinNames(refs, " | ");
    }

    private static String joinNames(List<GraphDtos.EntityRef> refs, String separator) {
        return String.join(separator, refs.stream().map(GraphDtos.EntityRef::name).toList());
    }

    private static String kinds(ComparisonDtos.EdgeChange e) {
        return String.join(", ", e.kinds().stream().map(Enum::name).toList());
    }

    private byte[] toJson(Object doc) {
        try {
            return json.writeValueAsBytes(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize report", e);
        }
    }

    private String footer() {
        Instant now = clock.instant();
        return "Generated by RippleMap on " + now + ". Impact scores are structural heuristics, not defect probabilities.";
    }

    static String filename(Context ctx, String kind, String extension) {
        return "ripplemap-" + slug(ctx.project().getName()) + "-s" + ctx.analysis().getSequenceNumber() + "-" + kind
                + "." + extension;
    }

    static String slug(String text) {
        String slug = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "project" : slug;
    }

    private static long value(Integer n) {
        return n == null ? 0 : n;
    }
}
