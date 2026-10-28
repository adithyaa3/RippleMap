package com.ripplemap.entity;

import com.ripplemap.analysis.model.FileCounts;
import com.ripplemap.analysis.model.ReferenceStatistics;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Summary numbers of a finished analysis. All counts are exact; null until the analysis finishes. */
@Embeddable
public class AnalysisCounts {

    @Column(name = "files_discovered")
    private Integer filesDiscovered;
    @Column(name = "files_in_scope")
    private Integer filesInScope;
    @Column(name = "files_excluded_tests")
    private Integer filesExcludedTests;
    @Column(name = "files_excluded_generated")
    private Integer filesExcludedGenerated;
    @Column(name = "files_parsed")
    private Integer filesParsed;
    @Column(name = "files_failed")
    private Integer filesFailed;
    @Column(name = "files_skipped_links")
    private Integer filesSkippedLinks;
    @Column(name = "type_count")
    private Integer typeCount;
    @Column(name = "method_count")
    private Integer methodCount;
    @Column(name = "constructor_count")
    private Integer constructorCount;
    @Column(name = "field_count")
    private Integer fieldCount;
    /** Distinct type-level source-to-target pairs. */
    @Column(name = "type_edge_count")
    private Integer typeEdgeCount;
    /** Distinct type-level (source, target, kind) relationships. */
    @Column(name = "type_relationship_count")
    private Integer typeRelationshipCount;
    @Column(name = "method_edge_count")
    private Integer methodEdgeCount;
    @Column(name = "method_relationship_count")
    private Integer methodRelationshipCount;
    @Column(name = "references_resolved")
    private Long referencesResolved;
    @Column(name = "references_inferred")
    private Long referencesInferred;
    @Column(name = "references_unresolved")
    private Long referencesUnresolved;
    @Column(name = "references_likely_external")
    private Long referencesLikelyExternal;
    @Column(name = "references_external")
    private Long referencesExternal;
    @Column(name = "references_internal")
    private Long referencesInternal;
    @Column(name = "solver_failures")
    private Long solverFailures;
    @Column(name = "info_count")
    private Integer infoCount;
    @Column(name = "warning_count")
    private Integer warningCount;
    @Column(name = "error_count")
    private Integer errorCount;
    @Column(name = "diagnostics_stored")
    private Integer diagnosticsStored;
    @Column(name = "diagnostics_truncated")
    private Boolean diagnosticsTruncated;
    @Column(name = "type_cyclic_components")
    private Integer typeCyclicComponents;
    @Column(name = "type_nodes_in_cycles")
    private Integer typeNodesInCycles;
    @Column(name = "method_cyclic_components")
    private Integer methodCyclicComponents;
    @Column(name = "isolated_types")
    private Integer isolatedTypes;
    @Column(name = "node_metrics_computed")
    private Boolean nodeMetricsComputed;

    public AnalysisCounts() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Groups related counts so that values cannot silently shift between positional arguments. */
    public static final class Builder {
        private final AnalysisCounts c = new AnalysisCounts();

        public Builder files(FileCounts files) {
            c.filesDiscovered = files.discovered();
            c.filesInScope = files.inScope();
            c.filesExcludedTests = files.excludedTests();
            c.filesExcludedGenerated = files.excludedGenerated();
            c.filesParsed = files.parsed();
            c.filesFailed = files.failed();
            c.filesSkippedLinks = files.skippedLinks();
            return this;
        }

        public Builder declarations(int types, int methods, int constructors, int fields) {
            c.typeCount = types;
            c.methodCount = methods;
            c.constructorCount = constructors;
            c.fieldCount = fields;
            return this;
        }

        public Builder typeLayer(int distinctPairs, int relationships) {
            c.typeEdgeCount = distinctPairs;
            c.typeRelationshipCount = relationships;
            return this;
        }

        public Builder methodLayer(int distinctPairs, int relationships) {
            c.methodEdgeCount = distinctPairs;
            c.methodRelationshipCount = relationships;
            return this;
        }

        public Builder references(ReferenceStatistics references) {
            c.referencesResolved = references.resolved();
            c.referencesInferred = references.inferred();
            c.referencesUnresolved = references.unresolved();
            c.referencesLikelyExternal = references.likelyExternal();
            c.referencesExternal = references.external();
            c.referencesInternal = references.internal();
            c.solverFailures = references.solverFailures();
            return this;
        }

        public Builder diagnostics(int info, int warnings, int errors, int stored, boolean truncated) {
            c.infoCount = info;
            c.warningCount = warnings;
            c.errorCount = errors;
            c.diagnosticsStored = stored;
            c.diagnosticsTruncated = truncated;
            return this;
        }

        public Builder structure(int typeCyclicComponents, int typeNodesInCycles, int methodCyclicComponents,
                                 int isolatedTypes, boolean nodeMetricsComputed) {
            c.typeCyclicComponents = typeCyclicComponents;
            c.typeNodesInCycles = typeNodesInCycles;
            c.methodCyclicComponents = methodCyclicComponents;
            c.isolatedTypes = isolatedTypes;
            c.nodeMetricsComputed = nodeMetricsComputed;
            return this;
        }

        public AnalysisCounts build() {
            return c;
        }
    }

    public Integer getFilesDiscovered() {
        return filesDiscovered;
    }

    public Integer getFilesInScope() {
        return filesInScope;
    }

    public Integer getFilesExcludedTests() {
        return filesExcludedTests;
    }

    public Integer getFilesExcludedGenerated() {
        return filesExcludedGenerated;
    }

    public Integer getFilesParsed() {
        return filesParsed;
    }

    public Integer getFilesFailed() {
        return filesFailed;
    }

    public Integer getFilesSkippedLinks() {
        return filesSkippedLinks;
    }

    public Integer getTypeCount() {
        return typeCount;
    }

    public Integer getMethodCount() {
        return methodCount;
    }

    public Integer getConstructorCount() {
        return constructorCount;
    }

    public Integer getFieldCount() {
        return fieldCount;
    }

    public Integer getTypeEdgeCount() {
        return typeEdgeCount;
    }

    public Integer getTypeRelationshipCount() {
        return typeRelationshipCount;
    }

    public Integer getMethodEdgeCount() {
        return methodEdgeCount;
    }

    public Integer getMethodRelationshipCount() {
        return methodRelationshipCount;
    }

    public Long getReferencesResolved() {
        return referencesResolved;
    }

    public Long getReferencesInferred() {
        return referencesInferred;
    }

    public Long getReferencesUnresolved() {
        return referencesUnresolved;
    }

    public Long getReferencesLikelyExternal() {
        return referencesLikelyExternal;
    }

    public Long getReferencesExternal() {
        return referencesExternal;
    }

    public Long getReferencesInternal() {
        return referencesInternal;
    }

    public Long getSolverFailures() {
        return solverFailures;
    }

    public Integer getInfoCount() {
        return infoCount;
    }

    public Integer getWarningCount() {
        return warningCount;
    }

    public Integer getErrorCount() {
        return errorCount;
    }

    public Integer getDiagnosticsStored() {
        return diagnosticsStored;
    }

    public Boolean getDiagnosticsTruncated() {
        return diagnosticsTruncated;
    }

    public Integer getTypeCyclicComponents() {
        return typeCyclicComponents;
    }

    public Integer getTypeNodesInCycles() {
        return typeNodesInCycles;
    }

    public Integer getMethodCyclicComponents() {
        return methodCyclicComponents;
    }

    public Integer getIsolatedTypes() {
        return isolatedTypes;
    }

    public Boolean getNodeMetricsComputed() {
        return nodeMetricsComputed;
    }
}
