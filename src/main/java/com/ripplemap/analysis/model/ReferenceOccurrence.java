package com.ripplemap.analysis.model;

/**
 * One source occurrence that supports a relationship.
 *
 * @param filePath relative source path
 * @param line     1-based line of the referencing expression or declaration
 * @param detail   short human-readable description of the originating construct
 * @param resolution RESOLVED or INFERRED
 * @param rule     inference rule identifier for INFERRED references, otherwise {@code null}
 */
public record ReferenceOccurrence(String filePath, int line, String detail, Resolution resolution, String rule) {
}
