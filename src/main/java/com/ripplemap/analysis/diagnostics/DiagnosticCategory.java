package com.ripplemap.analysis.diagnostics;

/** Diagnostic categories with their fixed severity. */
public enum DiagnosticCategory {
    PARSE_ERROR(Severity.ERROR, "The file could not be parsed; no declarations or relationships were extracted from it."),
    FILE_UNREADABLE(Severity.ERROR, "The file could not be read."),
    FILE_TOO_LARGE(Severity.ERROR, "The file exceeds the configured size limit and was not analyzed."),
    ENCODING_REPLACED(Severity.WARNING, "The file contains bytes that are not valid UTF-8; they were replaced before parsing."),
    EXCLUDED_SOURCES(Severity.INFO, "Files outside the selected scan scope were not analyzed."),
    SKIPPED_LINK(Severity.WARNING, "A symbolic link was skipped; links are never followed."),
    DUPLICATE_TYPE(Severity.WARNING, "Several files declare the same fully qualified type name; references to it cannot be resolved unambiguously."),
    DUPLICATE_MEMBER(Severity.WARNING, "Several members produce the same identifier; a line suffix was added to keep identities distinct."),
    UNRESOLVED_TYPE(Severity.WARNING, "A type name matches a project type name but could not be resolved to a declaration."),
    AMBIGUOUS_TYPE(Severity.WARNING, "A type name matches several project types and could not be disambiguated."),
    UNRESOLVED_CALL(Severity.WARNING, "A call could target a project method but its target could not be resolved."),
    AMBIGUOUS_CALL(Severity.WARNING, "A call matches several project methods and could not be disambiguated."),
    UNRESOLVED_FIELD(Severity.WARNING, "A field access could target a project field but could not be resolved."),
    LIKELY_EXTERNAL(Severity.INFO, "A reference could not be resolved, but its name is most likely provided by an external library."),
    GENERATED_CODE(Severity.INFO, "The file uses an annotation processor that generates code at compile time. Generated members and classes are not visible to source analysis; references to them are counted as external and add no edges."),
    RESOLUTION_ERROR(Severity.WARNING, "The symbol solver failed unexpectedly on this expression; it was skipped."),
    RESOLUTION_TIMEOUT(Severity.INFO, "The symbol solver exceeded its time budget for this expression; the documented fallback rules were used instead."),
    LIMIT_REACHED(Severity.WARNING, "A configured analysis limit was reached.");

    private final Severity severity;
    private final String description;

    DiagnosticCategory(Severity severity, String description) {
        this.severity = severity;
        this.description = description;
    }

    public Severity severity() {
        return severity;
    }

    public String description() {
        return description;
    }

    /** Categories that mean a project-internal dependency may be missing from the graph. */
    public boolean indicatesPossiblyMissingDependency() {
        return this == UNRESOLVED_TYPE || this == AMBIGUOUS_TYPE || this == UNRESOLVED_CALL
                || this == AMBIGUOUS_CALL || this == UNRESOLVED_FIELD || this == RESOLUTION_ERROR;
    }
}
