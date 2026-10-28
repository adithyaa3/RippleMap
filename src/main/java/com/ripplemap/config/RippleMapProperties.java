package com.ripplemap.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** All RippleMap configuration (prefix {@code ripplemap}); defaults are documented in docs/SECURITY.md. */
@Validated
@ConfigurationProperties(prefix = "ripplemap")
public class RippleMapProperties {

    /** Directory for the H2 database and stored imports. */
    @NotBlank
    private String dataDir;

    @Valid
    private final Analysis analysis = new Analysis();
    @Valid
    private final Import importLimits = new Import();
    @Valid
    private final LocalScan localScan = new LocalScan();
    @Valid
    private final Graph graph = new Graph();
    @Valid
    private final Security security = new Security();

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public Analysis getAnalysis() {
        return analysis;
    }

    public Import getImport() {
        return importLimits;
    }

    public LocalScan getLocalScan() {
        return localScan;
    }

    public Graph getGraph() {
        return graph;
    }

    public Security getSecurity() {
        return security;
    }

    public static class Analysis {
        /** Analyses that may run at the same time (symbol resolution itself is serialized). */
        @Min(1)
        @Max(16)
        private int concurrency = 2;
        /** Analyses that may wait for a worker. */
        @Min(1)
        @Max(1000)
        private int queueCapacity = 16;
        /** Cooperative time limit for one analysis. */
        @NotNull
        private Duration timeout = Duration.ofMinutes(10);
        /** Time one symbol-solver invocation may take before the documented fallback rules are used. */
        @NotNull
        private Duration referenceBudget = Duration.ofSeconds(2);
        /** JavaParser language level (e.g. JAVA_21). */
        @NotBlank
        private String languageLevel = "JAVA_21";
        @Min(1)
        private int maxJavaFiles = 10_000;
        @NotNull
        private DataSize maxFileSize = DataSize.ofMegabytes(2);
        @Min(0)
        private int maxDiagnostics = 5_000;
        @Min(1)
        @Max(200)
        private int maxReferenceSamples = 20;
        /** Completed or failed snapshots kept per project before new analyses are refused. */
        @Min(1)
        private int maxSnapshotsPerProject = 100;
        /** Type graphs larger than this skip per-node transitive metrics (computed on demand instead). */
        @Min(0)
        private int nodeMetricsLimit = 20_000;
        /** Snapshots kept in the in-memory graph cache. */
        @Min(1)
        @Max(256)
        private int cacheSize = 8;
        /** Stack size of analysis worker threads (deeply nested code needs a deep stack). */
        @NotNull
        private DataSize workerStackSize = DataSize.ofMegabytes(64);

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getReferenceBudget() {
            return referenceBudget;
        }

        public void setReferenceBudget(Duration referenceBudget) {
            this.referenceBudget = referenceBudget;
        }

        public String getLanguageLevel() {
            return languageLevel;
        }

        public void setLanguageLevel(String languageLevel) {
            this.languageLevel = languageLevel;
        }

        public int getMaxJavaFiles() {
            return maxJavaFiles;
        }

        public void setMaxJavaFiles(int maxJavaFiles) {
            this.maxJavaFiles = maxJavaFiles;
        }

        public DataSize getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(DataSize maxFileSize) {
            this.maxFileSize = maxFileSize;
        }

        public int getMaxDiagnostics() {
            return maxDiagnostics;
        }

        public void setMaxDiagnostics(int maxDiagnostics) {
            this.maxDiagnostics = maxDiagnostics;
        }

        public int getMaxReferenceSamples() {
            return maxReferenceSamples;
        }

        public void setMaxReferenceSamples(int maxReferenceSamples) {
            this.maxReferenceSamples = maxReferenceSamples;
        }

        public int getMaxSnapshotsPerProject() {
            return maxSnapshotsPerProject;
        }

        public void setMaxSnapshotsPerProject(int maxSnapshotsPerProject) {
            this.maxSnapshotsPerProject = maxSnapshotsPerProject;
        }

        public int getNodeMetricsLimit() {
            return nodeMetricsLimit;
        }

        public void setNodeMetricsLimit(int nodeMetricsLimit) {
            this.nodeMetricsLimit = nodeMetricsLimit;
        }

        public int getCacheSize() {
            return cacheSize;
        }

        public void setCacheSize(int cacheSize) {
            this.cacheSize = cacheSize;
        }

        public DataSize getWorkerStackSize() {
            return workerStackSize;
        }

        public void setWorkerStackSize(DataSize workerStackSize) {
            this.workerStackSize = workerStackSize;
        }
    }

    public static class Import {
        /** Largest accepted ZIP upload (also enforced by the multipart limits). */
        @NotNull
        private DataSize maxUploadSize = DataSize.ofMegabytes(50);
        /** Total bytes of extracted .java files per archive. */
        @NotNull
        private DataSize maxExpandedSize = DataSize.ofMegabytes(200);
        /** Size of a single extracted .java file. */
        @NotNull
        private DataSize maxEntrySize = DataSize.ofMegabytes(2);
        @Min(1)
        private int maxEntries = 20_000;
        @Min(1)
        private int maxJavaFiles = 10_000;
        @Min(1)
        @Max(200)
        private int maxPathDepth = 40;
        @Min(16)
        @Max(4096)
        private int maxPathLength = 512;
        /** Largest accepted uncompressed/compressed ratio for an extracted entry larger than 64 KB. */
        @Min(10)
        private int maxCompressionRatio = 200;
        /** Total bytes of all stored imports. */
        @NotNull
        private DataSize maxStoredSize = DataSize.ofGigabytes(2);

        public DataSize getMaxUploadSize() {
            return maxUploadSize;
        }

        public void setMaxUploadSize(DataSize maxUploadSize) {
            this.maxUploadSize = maxUploadSize;
        }

        public DataSize getMaxExpandedSize() {
            return maxExpandedSize;
        }

        public void setMaxExpandedSize(DataSize maxExpandedSize) {
            this.maxExpandedSize = maxExpandedSize;
        }

        public DataSize getMaxEntrySize() {
            return maxEntrySize;
        }

        public void setMaxEntrySize(DataSize maxEntrySize) {
            this.maxEntrySize = maxEntrySize;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public int getMaxJavaFiles() {
            return maxJavaFiles;
        }

        public void setMaxJavaFiles(int maxJavaFiles) {
            this.maxJavaFiles = maxJavaFiles;
        }

        public int getMaxPathDepth() {
            return maxPathDepth;
        }

        public void setMaxPathDepth(int maxPathDepth) {
            this.maxPathDepth = maxPathDepth;
        }

        public int getMaxPathLength() {
            return maxPathLength;
        }

        public void setMaxPathLength(int maxPathLength) {
            this.maxPathLength = maxPathLength;
        }

        public int getMaxCompressionRatio() {
            return maxCompressionRatio;
        }

        public void setMaxCompressionRatio(int maxCompressionRatio) {
            this.maxCompressionRatio = maxCompressionRatio;
        }

        public DataSize getMaxStoredSize() {
            return maxStoredSize;
        }

        public void setMaxStoredSize(DataSize maxStoredSize) {
            this.maxStoredSize = maxStoredSize;
        }
    }

    public static class LocalScan {
        /** Directories below which local projects may be registered. Empty disables local scanning. */
        private List<String> allowedRoots = new ArrayList<>();

        public List<String> getAllowedRoots() {
            return allowedRoots;
        }

        public void setAllowedRoots(List<String> allowedRoots) {
            this.allowedRoots = allowedRoots == null ? new ArrayList<>() : allowedRoots;
        }

        public boolean isEnabled() {
            return allowedRoots.stream().anyMatch(r -> r != null && !r.isBlank());
        }
    }

    public static class Graph {
        /** Hard upper bound of nodes returned by one graph request. */
        @Min(10)
        @Max(20_000)
        private int maxResponseNodes = 2_000;
        /** Upper bound of affected entities listed in one impact result (reports always list all). */
        @Min(1)
        private int maxImpactEntries = 5_000;
        /** Upper bound of each list (added/removed entities and dependencies) in one comparison result (reports list all). */
        @Min(1)
        private int maxComparisonEntries = 500;

        public int getMaxResponseNodes() {
            return maxResponseNodes;
        }

        public void setMaxResponseNodes(int maxResponseNodes) {
            this.maxResponseNodes = maxResponseNodes;
        }

        public int getMaxImpactEntries() {
            return maxImpactEntries;
        }

        public void setMaxImpactEntries(int maxImpactEntries) {
            this.maxImpactEntries = maxImpactEntries;
        }

        public int getMaxComparisonEntries() {
            return maxComparisonEntries;
        }

        public void setMaxComparisonEntries(int maxComparisonEntries) {
            this.maxComparisonEntries = maxComparisonEntries;
        }
    }

    public static class Security {
        /** Accepted Host header names (DNS-rebinding protection). */
        private List<String> allowedHosts = new ArrayList<>(List.of("localhost", "127.0.0.1", "[::1]"));

        public List<String> getAllowedHosts() {
            return allowedHosts;
        }

        public void setAllowedHosts(List<String> allowedHosts) {
            this.allowedHosts = allowedHosts == null ? new ArrayList<>() : allowedHosts;
        }
    }
}
