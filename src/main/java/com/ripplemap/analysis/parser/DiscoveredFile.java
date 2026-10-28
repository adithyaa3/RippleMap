package com.ripplemap.analysis.parser;

import java.nio.file.Path;

/**
 * @param relativePath path relative to the analyzed root using '/' separators
 * @param absolutePath resolved location on disk
 */
public record DiscoveredFile(String relativePath, Path absolutePath, SourceScope scope) {
}
