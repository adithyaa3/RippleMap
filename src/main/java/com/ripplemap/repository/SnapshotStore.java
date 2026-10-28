package com.ripplemap.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ripplemap.analysis.diagnostics.Diagnostic;
import com.ripplemap.analysis.diagnostics.DiagnosticCategory;
import com.ripplemap.analysis.diagnostics.Severity;
import com.ripplemap.analysis.model.AggregatedRelationship;
import com.ripplemap.analysis.model.EntityKind;
import com.ripplemap.analysis.model.ExtractedEntity;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.ReferenceOccurrence;
import com.ripplemap.analysis.model.RelationshipKind;
import com.ripplemap.analysis.model.Resolution;
import com.ripplemap.analysis.model.SourceFileInfo;
import com.ripplemap.impact.NodeMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk persistence of immutable snapshot rows (entities, relationships, diagnostics, files) through JDBC batches.
 * Writes must run inside the transaction that marks the analysis finished, so a snapshot is either complete or absent.
 */
@Repository
public class SnapshotStore {

    private static final int BATCH = 500;
    private static final TypeReference<List<ReferenceOccurrence>> SAMPLES = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SnapshotStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ writes

    public void insertEntities(long analysisId, Collection<ExtractedEntity> entities, Map<String, NodeMetrics> metrics) {
        String sql = "INSERT INTO code_entity (analysis_id, stable_id, kind, layer, name, qualified_name, package_name, "
                + "declaring_type_id, signature, file_path, line, end_line, visibility, modifiers, test_source, "
                + "direct_dependencies, direct_dependents, transitive_dependents, max_dependent_distance, impact_score, "
                + "in_cycle) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        List<Object[]> rows = new ArrayList<>(BATCH);
        for (ExtractedEntity e : entities) {
            NodeMetrics m = metrics.get(e.stableId());
            GraphLayer layer = e.kind().layer();
            rows.add(new Object[]{analysisId, e.stableId(), e.kind().name(), layer == null ? null : layer.name(),
                    e.name(), e.qualifiedName(), e.packageName(), e.declaringTypeId(), e.signature(), e.filePath(),
                    e.line(), e.endLine(), e.visibility(), e.modifiers(), e.testSource(),
                    m == null ? null : m.directDependencies(), m == null ? null : m.directDependents(),
                    m == null ? null : m.transitiveDependents(), m == null ? null : m.maxDependentDistance(),
                    m == null ? null : m.impactScore(), m == null ? null : m.inCycle()});
            flushIfFull(sql, rows);
        }
        flush(sql, rows);
    }

    public void insertRelationships(long analysisId, Collection<AggregatedRelationship> relationships) {
        String sql = "INSERT INTO dependency_edge (analysis_id, layer, source_id, target_id, kind, resolution, "
                + "reference_count, resolved_count, inferred_count, samples) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        List<Object[]> rows = new ArrayList<>(BATCH);
        for (AggregatedRelationship r : relationships) {
            rows.add(new Object[]{analysisId, r.layer().name(), r.sourceId(), r.targetId(), r.kind().name(),
                    r.resolution().name(), r.referenceCount(), r.resolvedCount(), r.inferredCount(),
                    toJson(r.samples())});
            flushIfFull(sql, rows);
        }
        flush(sql, rows);
    }

    public void insertDiagnostics(long analysisId, Collection<Diagnostic> diagnostics,
                                  Map<DiagnosticCategory, Integer> counts) {
        String sql = "INSERT INTO diagnostic (analysis_id, severity, category, file_path, line, entity_id, symbol, message) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        List<Object[]> rows = new ArrayList<>(BATCH);
        for (Diagnostic d : diagnostics) {
            rows.add(new Object[]{analysisId, d.severity().name(), d.category().name(), d.filePath(), d.line(),
                    d.entityId(), d.symbol(), d.message()});
            flushIfFull(sql, rows);
        }
        flush(sql, rows);
        String countSql = "INSERT INTO diagnostic_count (analysis_id, category, item_count) VALUES (?, ?, ?)";
        List<Object[]> countRows = new ArrayList<>();
        counts.forEach((category, count) -> countRows.add(new Object[]{analysisId, category.name(), count}));
        flush(countSql, countRows);
    }

    public void insertFiles(long analysisId, Collection<SourceFileInfo> files) {
        String sql = "INSERT INTO source_file (analysis_id, relative_path, package_name, sha256, size_bytes, line_count, "
                + "status, test_source, type_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        List<Object[]> rows = new ArrayList<>(BATCH);
        for (SourceFileInfo f : files) {
            rows.add(new Object[]{analysisId, f.relativePath(), f.packageName(), f.sha256(), f.sizeBytes(),
                    f.lineCount(), f.status().name(), f.testSource(), f.typeCount()});
            flushIfFull(sql, rows);
        }
        flush(sql, rows);
    }

    public void updateStage(long analysisId, String stage) {
        jdbc.update("UPDATE analysis SET stage = ? WHERE id = ? AND status = 'RUNNING'", stage, analysisId);
    }

    private void flushIfFull(String sql, List<Object[]> rows) {
        if (rows.size() >= BATCH) {
            flush(sql, rows);
        }
    }

    private void flush(String sql, List<Object[]> rows) {
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(sql, rows);
            rows.clear();
        }
    }

    // ------------------------------------------------------------------ reads

    public List<StoredEntity> loadEntities(long analysisId) {
        return jdbc.query("SELECT * FROM code_entity WHERE analysis_id = ? ORDER BY stable_id",
                (rs, i) -> mapEntity(rs), analysisId);
    }

    public List<AggregatedRelationship> loadRelationships(long analysisId) {
        return jdbc.query("SELECT * FROM dependency_edge WHERE analysis_id = ? ORDER BY layer, source_id, target_id, kind",
                (rs, i) -> new AggregatedRelationship(
                        GraphLayer.valueOf(rs.getString("layer")),
                        rs.getString("source_id"),
                        rs.getString("target_id"),
                        RelationshipKind.valueOf(rs.getString("kind")),
                        Resolution.valueOf(rs.getString("resolution")),
                        rs.getInt("reference_count"),
                        rs.getInt("resolved_count"),
                        rs.getInt("inferred_count"),
                        fromJson(rs.getString("samples"))), analysisId);
    }

    public List<SourceFileInfo> loadFiles(long analysisId) {
        return jdbc.query("SELECT * FROM source_file WHERE analysis_id = ? ORDER BY relative_path",
                (rs, i) -> new SourceFileInfo(rs.getString("relative_path"), rs.getString("package_name"),
                        rs.getString("sha256"), rs.getLong("size_bytes"), rs.getInt("line_count"),
                        SourceFileInfo.FileStatus.valueOf(rs.getString("status")), rs.getBoolean("test_source"),
                        rs.getInt("type_count")), analysisId);
    }

    public Map<DiagnosticCategory, Integer> loadDiagnosticCounts(long analysisId) {
        Map<DiagnosticCategory, Integer> counts = new EnumMap<>(DiagnosticCategory.class);
        jdbc.query("SELECT category, item_count FROM diagnostic_count WHERE analysis_id = ?", rs -> {
            counts.put(DiagnosticCategory.valueOf(rs.getString("category")), rs.getInt("item_count"));
        }, analysisId);
        return counts;
    }

    /** Diagnostics page in stored (deterministic) order; null filters match everything. */
    public List<Diagnostic> loadDiagnostics(long analysisId, Severity severity, DiagnosticCategory category,
                                            String entityId, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM diagnostic WHERE analysis_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(analysisId);
        appendFilters(sql, args, severity, category, entityId);
        sql.append(" ORDER BY id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, i) -> mapDiagnostic(rs), args.toArray());
    }

    public long countDiagnostics(long analysisId, Severity severity, DiagnosticCategory category, String entityId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM diagnostic WHERE analysis_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(analysisId);
        appendFilters(sql, args, severity, category, entityId);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** Unresolved-type and unresolved-call diagnostics whose symbol equals one of the given names. */
    public List<Diagnostic> loadDiagnosticsBySymbols(long analysisId, Collection<String> symbols,
                                                     Collection<DiagnosticCategory> categories, int limit) {
        if (symbols.isEmpty() || categories.isEmpty()) {
            return List.of();
        }
        String symbolMarks = String.join(",", Collections.nCopies(symbols.size(), "?"));
        String categoryMarks = String.join(",", Collections.nCopies(categories.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(analysisId);
        args.addAll(symbols);
        categories.forEach(c -> args.add(c.name()));
        args.add(limit);
        return jdbc.query("SELECT * FROM diagnostic WHERE analysis_id = ? AND symbol IN (" + symbolMarks
                        + ") AND category IN (" + categoryMarks + ") ORDER BY id LIMIT ?",
                (rs, i) -> mapDiagnostic(rs), args.toArray());
    }

    /** Diagnostics recorded in any of the given declarations (bounded). */
    public List<Diagnostic> loadDiagnosticsForEntities(long analysisId, Collection<String> entityIds, int limit) {
        if (entityIds.isEmpty()) {
            return List.of();
        }
        List<Diagnostic> result = new ArrayList<>();
        List<String> ids = new ArrayList<>(entityIds);
        for (int from = 0; from < ids.size() && result.size() < limit; from += 200) {
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + 200));
            String marks = String.join(",", Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(analysisId);
            args.addAll(chunk);
            args.add(limit - result.size());
            result.addAll(jdbc.query("SELECT * FROM diagnostic WHERE analysis_id = ? AND entity_id IN (" + marks
                    + ") ORDER BY id LIMIT ?", (rs, i) -> mapDiagnostic(rs), args.toArray()));
        }
        return result;
    }

    public long countDiagnosticsForEntities(long analysisId, Collection<String> entityIds) {
        long total = 0;
        List<String> ids = new ArrayList<>(entityIds);
        for (int from = 0; from < ids.size(); from += 200) {
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + 200));
            String marks = String.join(",", Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(analysisId);
            args.addAll(chunk);
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM diagnostic WHERE analysis_id = ? AND entity_id IN ("
                    + marks + ")", Long.class, args.toArray());
            total += count == null ? 0 : count;
        }
        return total;
    }

    public List<Diagnostic> loadAllDiagnostics(long analysisId) {
        return jdbc.query("SELECT * FROM diagnostic WHERE analysis_id = ? ORDER BY id", (rs, i) -> mapDiagnostic(rs),
                analysisId);
    }

    /** Highest stored impact scores among type entities of the given analyses. */
    public List<Map<String, Object>> topImpact(Collection<Long> analysisIds, int limit) {
        if (analysisIds.isEmpty()) {
            return List.of();
        }
        String marks = String.join(",", Collections.nCopies(analysisIds.size(), "?"));
        List<Object> args = new ArrayList<>(analysisIds);
        args.add(limit);
        return jdbc.queryForList("SELECT analysis_id, stable_id, name, kind, impact_score, transitive_dependents, "
                + "direct_dependents, max_dependent_distance FROM code_entity WHERE layer = 'TYPE' AND impact_score > 0 "
                + "AND analysis_id IN (" + marks + ") ORDER BY impact_score DESC, stable_id LIMIT ?", args.toArray());
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, Severity severity,
                                      DiagnosticCategory category, String entityId) {
        if (severity != null) {
            sql.append(" AND severity = ?");
            args.add(severity.name());
        }
        if (category != null) {
            sql.append(" AND category = ?");
            args.add(category.name());
        }
        if (entityId != null) {
            sql.append(" AND entity_id = ?");
            args.add(entityId);
        }
    }

    private static Diagnostic mapDiagnostic(ResultSet rs) throws SQLException {
        return new Diagnostic(Severity.valueOf(rs.getString("severity")),
                DiagnosticCategory.valueOf(rs.getString("category")), rs.getString("file_path"), rs.getInt("line"),
                rs.getString("entity_id"), rs.getString("symbol"), rs.getString("message"));
    }

    private static StoredEntity mapEntity(ResultSet rs) throws SQLException {
        ExtractedEntity entity = new ExtractedEntity(
                rs.getString("stable_id"),
                EntityKind.valueOf(rs.getString("kind")),
                rs.getString("name"),
                rs.getString("qualified_name"),
                rs.getString("package_name"),
                rs.getString("declaring_type_id"),
                rs.getString("signature"),
                rs.getString("file_path"),
                rs.getInt("line"),
                rs.getInt("end_line"),
                rs.getString("visibility"),
                rs.getString("modifiers"),
                rs.getBoolean("test_source"));
        Integer direct = (Integer) rs.getObject("direct_dependencies");
        NodeMetrics metrics = null;
        if (direct != null) {
            metrics = new NodeMetrics(direct, rs.getInt("direct_dependents"),
                    (Integer) rs.getObject("transitive_dependents"), (Integer) rs.getObject("max_dependent_distance"),
                    rs.getObject("impact_score") == null ? null : rs.getDouble("impact_score"),
                    rs.getBoolean("in_cycle"));
        }
        return new StoredEntity(entity, metrics);
    }

    private String toJson(List<ReferenceOccurrence> samples) {
        try {
            return mapper.writeValueAsString(samples);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize reference samples", e);
        }
    }

    private List<ReferenceOccurrence> fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            return List.copyOf(mapper.readValue(json, SAMPLES));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored reference samples are corrupted", e);
        }
    }
}
