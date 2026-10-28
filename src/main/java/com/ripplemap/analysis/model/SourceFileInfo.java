package com.ripplemap.analysis.model;

/**
 * Per-file analysis record.
 *
 * @param relativePath path relative to the analyzed root ('/' separators)
 * @param packageName  declared package, or {@code null} if the file was not parsed
 * @param sha256       content hash, or {@code null} if the file could not be read
 * @param typeCount    number of type declarations (top-level and nested member types) in the file
 */
public record SourceFileInfo(
        String relativePath,
        String packageName,
        String sha256,
        long sizeBytes,
        int lineCount,
        FileStatus status,
        boolean testSource,
        int typeCount) {

    public enum FileStatus {
        PARSED,
        FAILED
    }
}
