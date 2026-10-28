package com.ripplemap.analysis.model;

/**
 * A declaration extracted from source.
 *
 * @param stableId        deterministic identity derived from source (see docs/ANALYSIS_SEMANTICS.md)
 * @param kind            declaration kind
 * @param name            display name (simple type name, {@code Outer.Inner}, or member name)
 * @param qualifiedName   canonical name for types, {@code Type.member} for members
 * @param packageName     package of the declaring compilation unit ("" for the default package)
 * @param declaringTypeId stable id of the declaring type, or {@code null} for top-level types
 * @param signature       display signature for callables ({@code name(Type, Type)}), declared type for fields
 * @param filePath        source path relative to the analyzed root, using '/' separators
 * @param line            1-based declaration line (line of the declared name)
 * @param endLine         1-based last line of the declaration
 * @param visibility      public / protected / private / package
 * @param modifiers       space-separated non-access modifiers
 * @param testSource      whether the file was classified as test source
 */
public record ExtractedEntity(
        String stableId,
        EntityKind kind,
        String name,
        String qualifiedName,
        String packageName,
        String declaringTypeId,
        String signature,
        String filePath,
        int line,
        int endLine,
        String visibility,
        String modifiers,
        boolean testSource) {
}
